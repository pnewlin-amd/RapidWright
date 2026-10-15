# Native, read-only preparation for the partition-mapping frontend.
# vivado -mode batch -source tools/export_partition_input.tcl \
#   -tclargs INPUT_DCP_OR_EDIF OUTPUT_DIR ?PART? ?TIMING_MAX_PATHS?
# No synthesis, optimization, placement, partition wrapping, or source overwrite.
if {$argc < 2 || $argc > 4} {
  error "Expected INPUT_DCP_OR_EDIF OUTPUT_DIR ?PART? ?TIMING_MAX_PATHS?"
}
lassign $argv source_input output requested_part timing_max_paths
if {$timing_max_paths eq ""} { set timing_max_paths 1000 }
if {![string is integer -strict $timing_max_paths] || $timing_max_paths < 0 || $timing_max_paths > 100000} {
  error "TIMING_MAX_PATHS must be in 0..100000"
}
set source_input [file normalize $source_input]
set output [file normalize $output]
set script_dir [file dirname [file normalize [info script]]]
set helper [file join $script_dir export_partition_input.py]
set python [auto_execok python]
if {$python eq ""} { set python [auto_execok python3] }
if {[info exists ::env(RW_PARTITION_PYTHON)]} { set python [list $::env(RW_PARTITION_PYTHON)] }
if {$python eq ""} { error "Python 3 is required; set RW_PARTITION_PYTHON to its executable" }
file mkdir $output
set selected_part [string trim [exec {*}$python $helper begin $source_input $output $requested_part]]
set_param general.maxThreads 1
set extension [string tolower [file extension $source_input]]
if {$extension eq ".dcp"} {
  open_checkpoint $source_input
  set checkpoint $source_input
  set input_edif [file join $output post_synth.edf]
  if {[file normalize $input_edif] eq $source_input} { error "Output must not overwrite input" }
  write_edif -force $input_edif
} elseif {$extension in {".edf" ".edif"}} {
  if {$selected_part eq ""} { error "EDIF has no part property; supply PART as the third argument" }
  read_edif $source_input
  # The default mode inserts I/O buffers into an OOC netlist. Preserve the
  # supplied logical boundary and port set by explicitly linking OOC instead.
  link_design -mode out_of_context -part $selected_part
  set input_edif $source_input
  set checkpoint [file join $output source.dcp]
  write_checkpoint -force $checkpoint
} else {
  error "Input must be a .dcp, .edf, or .edif file"
}
set actual_part [get_property PART [current_design]]
if {$requested_part ne "" && ![string equal -nocase $requested_part $actual_part]} {
  error "Requested part $requested_part disagrees with checkpoint part $actual_part"
}
puts "PARTITION_INPUT_NETLIST_READY checkpoint=$checkpoint edif=$input_edif"
report_utilization -file [file join $output utilization.rpt]
report_utilization -hierarchical -hierarchical_depth 8 -file [file join $output utilization_hierarchical.rpt]
# Keep typed native objects for get_property, but serialize each column in bulk.
# No per-cell native Tcl iteration: Python zips the plain column files afterward.
set primitives [get_cells -quiet -hierarchical -filter {IS_PRIMITIVE == 1}]
set count [llength $primitives]
set columns [file join $output native_columns]
file mkdir $columns
set properties {NAME REF_NAME PRIMITIVE_TYPE PRIMITIVE_GROUP PRIMITIVE_SUBGROUP IS_SEQUENTIAL}
set handles [dict create]
foreach property $properties {
  set fp [open [file join $columns $property.txt] w]
  fconfigure $fp -encoding utf-8 -translation lf
  dict set handles $property $fp
}
for {set start 0} {$start < $count} {incr start 50000} {
  set batch [lrange $primitives $start [expr {$start + 49999}]]
  foreach property $properties {
    set values [encoding convertfrom utf-8 [encoding convertto utf-8 [get_property $property $batch]]]
    if {[llength $values] != [llength $batch]} { error "Misaligned native property column $property" }
    puts [dict get $handles $property] [join $values "\n"]
  }
}
foreach property $properties { close [dict get $handles $property] }
puts "PARTITION_INPUT_NATIVE_COLUMNS_READY count=$count"
puts [exec {*}$python $helper finish $source_input $output $checkpoint $input_edif $actual_part [get_property TOP [current_design]] [version -short] $count]
# The base EDIF/census is complete before optional source-STA sampling. Use
# only constraints already present in this open checkpoint. Missing clocks or
# timing-export errors preserve the base input and select structural fallback.
set timing_error ""
if {$timing_max_paths == 0} {
  puts [exec {*}$python $helper disable-timing $source_input $output]
} elseif {[catch {
  source [file join $script_dir export_partition_timing.tcl]
  rw_export_partition_timing $checkpoint $input_edif [file join $output timing] $timing_max_paths
} timing_error]} {
  set fp [open [file join $output timing_export_error.txt] w]
  puts $fp $timing_error
  close $fp
  puts "PARTITION_INPUT_TIMING_UNAVAILABLE"
} else {
  set timing_error ""
}
puts [exec {*}$python $helper attach-timing $source_input $output $timing_error]
puts "PARTITION_INPUT_EXPORT_PASS"
exit
