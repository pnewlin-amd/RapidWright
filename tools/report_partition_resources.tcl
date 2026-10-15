# Native resource oracle for a mapping on an UNMODIFIED post-synthesis DCP.
# No wrapper, placement, regrouping or partitioned netlist is generated.
# Usage: vivado -mode batch -source tools/report_partition_resources.tcl \
#          -tclargs INPUT_DCP MAPPING_TXT OUTPUT_DIR
if {$argc < 3 || $argc % 2 != 1} { error "Expected INPUT_DCP MAPPING_TXT OUTPUT_DIR ?MAPPING_TXT OUTPUT_DIR ...?" }
set checkpoint [file normalize [lindex $argv 0]]
proc file_sha256 {path} {
  global tcl_platform
  if {$tcl_platform(platform) eq "windows"} {
    set result [exec certutil.exe -hashfile $path SHA256]
    foreach line [split $result "\n"] {
      set line [string trim $line]
      if {[regexp {^[a-fA-F0-9]{64}$} $line]} { return [string tolower $line] }
    }
  } else {
    set result [exec sha256sum -- $path]
    set hash [lindex $result 0]
    if {[regexp {^[a-fA-F0-9]{64}$} $hash]} { return [string tolower $hash] }
  }
  error "Cannot obtain SHA256 for $path"
}
set checkpoint_sha256 [file_sha256 $checkpoint]
set_param general.maxThreads 1
open_checkpoint $checkpoint
set top [get_property TOP [current_design]]
if {$top eq ""} { set top [get_property NAME [current_design]] }
set all_preassigned 1
foreach {mapping output} [lrange $argv 1 end] {
  if {![file exists [file join $output preassigned_cells.tsv]]} { set all_preassigned 0 }
}
if {!$all_preassigned} {
set all_primitive_cells [get_cells -quiet -hierarchical -filter {IS_PRIMITIVE == 1}]
# Vivado can return its native collection representation even for NAME values.
# Roundtrip the serialized Tcl list through UTF-8 to prevent quadratic native
# collection access during ordinary dictionary/ancestor scans.
set all_primitive_names [encoding convertfrom utf-8 [encoding convertto utf-8 [get_property NAME $all_primitive_cells]]]
set all_primitive_refs [encoding convertfrom utf-8 [encoding convertto utf-8 [get_property REF_NAME $all_primitive_cells]]]
puts "NATIVE_RESOURCE_PLAIN_NAMES_READY [llength $all_primitive_names]"
# The EDIF frontier contains outer primitive macro wrappers (e.g. RAM32M16),
# while Vivado's IS_PRIMITIVE collection also contains their expanded children.
# Select the logical boundary once; native -cells expands it for utilization.
set primitive_name_set [dict create]
foreach name $all_primitive_names { dict set primitive_name_set $name 1 }
set logical_cells [list]
set logical_names [list]
set logical_refs [list]
foreach name $all_primitive_names ref $all_primitive_refs {
  set parent $name
  set internal 0
  while {[string last / $parent] >= 0} {
    set parent [string range $parent 0 [expr {[string last / $parent] - 1}]]
    if {[dict exists $primitive_name_set $parent]} { set internal 1; break }
  }
  if {!$internal} { lappend logical_cells $name; lappend logical_names $name; lappend logical_refs $ref }
}
set all_primitive_cells $logical_cells
set all_primitive_names $logical_names
set all_primitive_refs $logical_refs
puts "NATIVE_RESOURCE_BOUNDARIES_READY [llength $logical_names]"
}

foreach {mapping output} [lrange $argv 1 end] {
set mapping [file normalize $mapping]
set output [file normalize $output]
file mkdir $output
if {[file exists [file join $output native_complete.txt]]} {
  file rename -force [file join $output native_complete.txt] [file join $output native_previous_complete.txt]
}
set rules [dict create]
if {![file exists [file join $output preassigned_cells.tsv]]} {
set fp [open $mapping r]
while {[gets $fp line] >= 0} {
  set line [string trim $line]
  if {$line eq "" || [string match "#*" $line]} { continue }
  if {![regexp {^(\S+)\s+(FPGA_[A-Z]+)$} $line unused path partition]} {
    error "Invalid mapping rule: $line"
  }
  if {$path eq $top} { set path "" } elseif {[string first "$top/" $path] == 0} {
    set path [string range $path [expr {[string length $top] + 1}] end]
  }
  if {[dict exists $rules $path] && [dict get $rules $path] ne $partition} {
    error "Conflicting mapping rules for $path"
  }
  dict set rules $path $partition
}
close $fp
if {![dict size $rules]} { error "Empty mapping" }
} else { puts "NATIVE_RESOURCE_PREASSIGNED_MAPPING_READY $mapping" }

proc resolve_partition {path rules} {
  while {1} {
    if {[dict exists $rules $path]} { return [dict get $rules $path] }
    set slash [string last / $path]
    if {$slash < 0} {
      if {[dict exists $rules ""]} { return [dict get $rules ""] }
      error "Unmapped leaf primitive: $path"
    }
    set path [string range $path 0 [expr {$slash-1}]]
  }
}

set assigned [dict create]
set logical_luts [dict create]
set manifest [file join $output preassigned_cells.tsv]
if {[file exists $manifest]} {
  set fp [open [file join $output preassigned_hashes.txt] r]
  set prepared_hashes [dict create]
  while {[gets $fp line] >= 0} {
    set equal [string first = $line]
    if {$equal > 0} { dict set prepared_hashes [string range $line 0 [expr {$equal-1}]] [string range $line [expr {$equal+1}] end] }
  }
  close $fp
  if {[dict get $prepared_hashes mapping_sha256] ne [file_sha256 $mapping]
      || [dict get $prepared_hashes checkpoint_sha256] ne $checkpoint_sha256} {
    error "Preassigned cell lists belong to another mapping/checkpoint"
  }
  puts "NATIVE_RESOURCE_PREASSIGNED_HASHES_READY $mapping"
  if {[dict get $prepared_hashes native_inventory_cells] != [llength [get_cells -quiet -hierarchical -filter {IS_PRIMITIVE == 1}]]} {
    error "Native primitive inventory count differs from the prepared reference"
  }
  puts "NATIVE_RESOURCE_PREASSIGNED_INVENTORY_READY $mapping"
  set fp [open $manifest r]
  set manifest_rows [split [read $fp] "\n"]
  close $fp
  set total_primitive_count 0
  foreach row [lrange $manifest_rows 1 end] {
    if {[string trim $row] eq ""} { continue }
    lassign [split $row "\t"] partition cell_file logical_count primitive_count
    set fp [open $cell_file r]
    set data [string trimright [read $fp] "\r\n"]
    close $fp
    set selected [list]
    if {$data ne ""} { set selected [split [string map {\r ""} $data] "\n"] }
    if {[llength $selected] != $primitive_count} { error "Preassigned cell count mismatch: $partition" }
    dict set assigned $partition $selected
    dict set logical_luts $partition $logical_count
    incr total_primitive_count $primitive_count
  }
  puts "NATIVE_RESOURCE_FILE_ASSIGNMENTS_READY $mapping $total_primitive_count"
} else {
foreach partition [dict values $rules] { dict set assigned $partition [list] }
set assigned_count 0
foreach name $all_primitive_names ref $all_primitive_refs {
  set partition [resolve_partition $name $rules]
  dict lappend assigned $partition $name
  if {[regexp {^LUT[1-6](_[DL])?$|^LUT6_2$|^LUT6CY$|^CFGLUT5$|^LUTCY[12]$} $ref]} {
    dict incr logical_luts $partition
  }
  incr assigned_count
  if {$assigned_count % 20000 == 0} { puts "NATIVE_RESOURCE_ASSIGNMENTS $mapping $assigned_count" }
}
puts "NATIVE_RESOURCE_ASSIGNMENTS_READY $mapping $assigned_count"
set total_primitive_count [llength $all_primitive_cells]
}

# Parse ONLY the Used column of the authoritative native report. Preserve the
# complete report beside the table for review; absent categories remain NA.
proc parse_used {report_file} {
  set result [dict create]
  set fp [open $report_file r]
  while {[gets $fp line] >= 0} {
    if {![string match "|*" $line]} { continue }
    set fields [split $line |]
    if {[llength $fields] < 4} { continue }
    set label [string trim [lindex $fields 1]]
    set value [string map {, ""} [string trim [lindex $fields 2]]]
    if {![string is double -strict $value]} { continue }
    switch -glob -- $label {
      "CLB LUTs*" - "Slice LUTs*" { dict set result clb_luts $value }
      "LUT as Logic*" { dict set result lut_as_logic $value }
      "LUT as Memory*" { dict set result lut_as_memory $value }
      "CLB Registers*" - "Slice Registers*" { dict set result registers $value }
      "Register as Flip Flop*" { dict set result flip_flops $value }
      "Register as Latch*" { dict set result latches $value }
      "Block RAM Tile*" { dict set result block_ram_tiles $value }
      "DSPs*" { dict set result dsps $value }
      "URAM*" { dict set result uram $value }
    }
  }
  close $fp
  foreach required {clb_luts lut_as_logic lut_as_memory registers flip_flops latches} {
    if {![dict exists $result $required]} { error "Missing native utilization row $required in $report_file" }
  }
  return $result
}

set columns {partition logical_lut_primitives clb_luts lut_as_logic lut_as_memory registers flip_flops latches block_ram_tiles dsps uram primitive_leaves}
set table [open [file join $output vivado_resources.tsv] w]
puts $table [join $columns "\t"]
set design_report [file join $output DESIGN_utilization.rpt]
report_utilization -file $design_report
set design_metrics [parse_used $design_report]
set design_luts 0
foreach partition [dict keys $logical_luts] { incr design_luts [dict get $logical_luts $partition] }
set reports [dict create DESIGN $design_metrics]
set primitive_counts [dict create DESIGN $total_primitive_count]
dict set logical_luts DESIGN $design_luts
foreach partition [lsort [dict keys $assigned]] {
  set selected [dict get $assigned $partition]
  set report_file [file join $output "${partition}_utilization.rpt"]
  if {[llength $selected]} {
    report_utilization -cells $selected -file $report_file
    dict set reports $partition [parse_used $report_file]
  } else {
    set empty_metrics [dict create]
    foreach metric [dict keys $design_metrics] { dict set empty_metrics $metric 0 }
    dict set reports $partition $empty_metrics
    set fp [open $report_file w]
    puts $fp "Empty assigned primitive collection: all Used values are zero."
    close $fp
  }
  dict set primitive_counts $partition [llength $selected]
}
foreach partition [linsert [lsort [dict keys $assigned]] 0 DESIGN] {
  set metrics [dict get $reports $partition]
  set row [list $partition]
  foreach column [lrange $columns 1 end] {
    if {$column eq "logical_lut_primitives"} {
      set value 0
      if {[dict exists $logical_luts $partition]} { set value [dict get $logical_luts $partition] }
    } elseif {$column eq "primitive_leaves"} {
      set value [dict get $primitive_counts $partition]
    } elseif {[dict exists $metrics $column]} {
      set value [dict get $metrics $column]
    } else { set value NA }
    lappend row $value
  }
  puts $table [join $row "\t"]
}
close $table
file copy -force $mapping [file join $output source_mapping.txt]
set fp [open [file join $output provenance.txt] w]
puts $fp "vivado_version=[version -short]"
puts $fp "checkpoint=$checkpoint"
puts $fp "checkpoint_sha256=$checkpoint_sha256"
puts $fp "mapping_sha256=[file_sha256 $mapping]"
puts $fp "mapping=$mapping"
puts $fp "top=$top"
puts $fp "part=[get_property PART [current_design]]"
puts $fp "method=report_utilization -cells original_assigned_primitive_collection"
puts $fp "unit=Vivado Used column on original synthesized netlist; combines LUTs according to native tool semantics"
puts $fp "partition_sums_may_differ_from_design=Combining across partition boundaries changes subset resource accounting"
close $fp
set fp [open [file join $output native_complete.txt] w]
puts $fp "status=VIVADO_PARTITION_RESOURCE_MAPPING_PASS"
puts $fp "checkpoint_sha256=$checkpoint_sha256"
puts $fp "mapping_sha256=[file_sha256 $mapping]"
close $fp
puts "VIVADO_PARTITION_RESOURCE_MAPPING_PASS $mapping"
}
puts "VIVADO_PARTITION_RESOURCE_ORACLE_PASS"
exit
