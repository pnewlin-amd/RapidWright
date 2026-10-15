# Complete native electrical-net census in a compressed seven-list stream.
# This does not optimize, rewrite or wrap a netlist. Source-only without the flag.
# vivado -mode batch -source THIS -tclargs --export-native-cuts DCP OUT \
#   ?auto|zlib|pipe? ?MAX_GZIP_BYTES? ?MAX_EXPORT_SECONDS?
set ::rw_native_cut_exporter [file normalize [info script]]

proc rw_cut_plain_list {value} {
  return [encoding convertfrom utf-8 [encoding convertto utf-8 $value]]
}

proc rw_cut_open_gzip {partial mode} {
  if {[file exists $partial]} { error "Refuse existing partial: $partial" }
  if {$mode eq "auto"} {
    set mode [expr {[llength [info commands zlib]] ? "zlib" : "pipe"}]
  }
  if {$mode eq "zlib"} {
    if {![llength [info commands zlib]]} { error "Tcl zlib unavailable; use pipe" }
    set fp [open $partial {WRONLY CREAT EXCL}]
    fconfigure $fp -translation binary -encoding binary
    if {[catch {zlib push gzip $fp -level 1} message options]} {
      close $fp
      return -options $options $message
    }
  } elseif {$mode eq "pipe"} {
    set python [auto_execok python]
    if {[info exists ::env(BIGFPGA_PYTHON)]} { set python [list $::env(BIGFPGA_PYTHON)] }
    if {![llength $python]} { error "Host Python required for gzip pipe fallback" }
    set helper [file join [file dirname $::rw_native_cut_exporter] native_cut_gzip_pipe.py]
    set saved [dict create]
    foreach key {PYTHONHOME PYTHONPATH TCL_LIBRARY TK_LIBRARY TCLLIBPATH} {
      if {[info exists ::env($key)]} { dict set saved $key $::env($key); unset ::env($key) }
    }
    try {
      set fp [open [concat | $python [list $helper --output $partial]] w]
    } finally {
      dict for {key value} $saved { set ::env($key) $value }
    }
  } else { error "Unknown compression mode: $mode" }
  fconfigure $fp -encoding utf-8 -translation lf
  return [dict create channel $fp compression $mode]
}

proc rw_export_complete_native_cuts {checkpoint output {mode auto} {max_bytes 2147483648} {max_seconds 28800}} {
  if {![string is wideinteger -strict $max_bytes] || $max_bytes <= 0 ||
      ![string is integer -strict $max_seconds] || $max_seconds <= 0} {
    error "Export bounds must be positive integers"
  }
  set checkpoint [file normalize $checkpoint]
  set output [file normalize $output]
  if {![file isfile $checkpoint]} { error "Missing checkpoint: $checkpoint" }
  file mkdir $output
  set bulk [file join $output native_net_endpoint_lists.tcl.gz]
  set partial "$bulk.partial.[pid]"
  set complete [file join $output native_endpoint_complete.txt]
  foreach target [list $bulk $partial $complete] {
    if {[file exists $target]} { error "Refuse overwriting existing evidence: $target" }
  }
  file copy $::rw_native_cut_exporter [file join $output native_cut_export_script.tcl]
  file copy [file join [file dirname $::rw_native_cut_exporter] native_cut_gzip_pipe.py] \
    [file join $output native_cut_gzip_pipe.py]
  set_param general.maxThreads 1
  open_checkpoint $checkpoint
  set native_nets [get_nets -quiet -hierarchical]
  set net_names [rw_cut_plain_list [get_property NAME $native_nets]]
  set native_count [llength $native_nets]
  if {$native_count != [llength $net_names]} { error "Native net NAME alignment mismatch" }
  set compression [rw_cut_open_gzip $partial $mode]
  set fp [dict get $compression channel]
  array set seen {}
  set net_id 0
  set started [clock milliseconds]
  try {
    for {set first 0} {$first < $native_count} {incr first 512} {
      set last [expr {min($first + 511, $native_count - 1)}]
      set batch_nets [lrange $native_nets $first $last]
      set batch_names [lrange $net_names $first $last]
      set batch_count [llength $batch_nets]
      if {$batch_count != [llength $batch_names]} { error "Native batch NAME alignment mismatch" }
      for {set index 0} {$index < $batch_count} {incr index} {
        set net_name [lindex $batch_names $index]
        if {[info exists seen($net_name)]} { continue }
        set net [lrange $batch_nets $index $index]
        set anchors [get_pins -quiet -of_objects $net]
        if {![llength $anchors]} { set anchors [get_ports -quiet -of_objects $net] }
        if {[llength $anchors]} {
          set segments [get_nets -quiet -segments -of_objects [lrange $anchors 0 0]]
        } else { set segments $net }
        if {![llength $segments]} { error "Native anchor returned no electrical segments: $net_name" }
        set segment_names [rw_cut_plain_list [get_property NAME $segments]]
        foreach segment $segment_names { set seen($segment) 1 }
        set canonical [lindex [lsort $segment_names] 0]
        set pins [get_pins -quiet -of_objects $segments]
        set pin_names {}; set pin_refs {}; set pin_dirs {}; set port_names {}; set port_dirs {}
        if {[llength $pins]} {
          set pin_names [get_property NAME $pins]
          set pin_refs [get_property REF_PIN_NAME $pins]
          set pin_dirs [get_property DIRECTION $pins]
        }
        set ports [get_ports -quiet -of_objects $segments]
        if {[llength $ports]} {
          set port_names [get_property NAME $ports]
          set port_dirs [get_property DIRECTION $ports]
        }
        puts $fp [list $net_id $canonical $pin_names $pin_refs $pin_dirs $port_names $port_dirs]
        incr net_id
        if {$net_id % 10000 == 0} {
          flush $fp
          set elapsed [expr {[clock milliseconds]-$started}]
          puts "COMPRESSED_NATIVE_ENDPOINT_PROGRESS nets=$net_id source_slots=$first gzip_bytes=[file size $partial] elapsed_ms=$elapsed"
          if {[file size $partial] > $max_bytes} { error "Compressed output exceeded byte budget" }
          if {$elapsed > 1000*$max_seconds} { error "Native export exceeded time budget" }
        }
      }
    }
    # Independently ensure every enumerated hierarchical segment was covered.
    foreach name $net_names {
      if {![info exists seen($name)]} { error "Native census left an uncovered segment: $name" }
    }
  } on error {message options} {
    catch {close $fp}
    return -options $options $message
  }
  close $fp
  if {[file size $partial] > $max_bytes} { error "Compressed output exceeded byte budget" }
  file rename $partial $bulk
  set fp [open $complete {WRONLY CREAT EXCL}]
  fconfigure $fp -encoding utf-8 -translation lf
  puts $fp "status=COMPRESSED_NATIVE_ENDPOINT_EXPORT_PASS"
  puts $fp "checkpoint=$checkpoint"
  puts $fp "canonical_nets=$net_id"
  puts $fp "hierarchical_segments=$native_count"
  puts $fp "covered_alias_segments=[array size seen]"
  puts $fp "all_enumerated_segments_covered=1"
  puts $fp "elapsed_ms=[expr {[clock milliseconds]-$started}]"
  puts $fp "compression=[dict get $compression compression]"
  puts $fp "gzip_bytes=[file size $bulk]"
  puts $fp "vivado_version=[version -short]"
  puts $fp "top=[get_property TOP [current_design]]"
  puts $fp "part=[get_property PART [current_design]]"
  close $fp
  puts "COMPRESSED_NATIVE_ENDPOINT_EXPORT_PASS nets=$net_id"
}

if {[info exists ::argv] && [llength $::argv] && [lindex $::argv 0] eq "--export-native-cuts"} {
  if {[llength $::argv] < 3 || [llength $::argv] > 6} { error "Expected --export-native-cuts DCP OUT ?MODE? ?MAX_BYTES? ?MAX_SECONDS?" }
  rw_export_complete_native_cuts {*}[lrange $::argv 1 end]
  exit
}
