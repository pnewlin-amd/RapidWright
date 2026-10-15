# Native Vivado 2025.1 setup-path sampling. Source this file to define the proc.
# Standalone execution is explicit: -tclargs --partition-timing-standalone DCP EDF OUT ?MAX_PATHS?
# Source references: AMD UG912 TIMING_PATH and UG835 get_timing_paths/get_pins.
# Vivado 2025.1 has no TIMING_POINTS timing_path property; get_pins -of_objects
# timing_path supplies its ordered data pins (launch Q through capture D).
set ::rw_partition_timing_tools [file dirname [file normalize [info script]]]

proc rw_partition_timing_python {} {
    if {[info exists ::env(RW_TIMING_PYTHON)]} { return [list $::env(RW_TIMING_PYTHON)] }
    if {[info exists ::env(RW_PARTITION_PYTHON)]} { return [list $::env(RW_PARTITION_PYTHON)] }
    if {[info exists ::env(BIGFPGA_PYTHON)]} { return [list $::env(BIGFPGA_PYTHON)] }
    set python [auto_execok python]
    if {![llength $python]} { error "Python 3.11+ with tkinter is required for timing-path serialization" }
    return $python
}

proc rw_partition_timing_exec_python {python args} {
    # Vivado exports its private PYTHONHOME/Tcl library. A host Python executable
    # must load its own standard library and matching Tcl runtime instead.
    set saved [dict create]
    foreach name {PYTHONHOME PYTHONPATH TCL_LIBRARY TK_LIBRARY TCLLIBPATH} {
        if {[info exists ::env($name)]} {
            dict set saved $name $::env($name)
            unset ::env($name)
        }
    }
    try {
        return [exec -- {*}$python {*}$args]
    } finally {
        dict for {name value} $saved { set ::env($name) $value }
    }
}

proc rw_export_partition_timing {checkpoint edif output {max_paths 1000}} {
    if {![string is integer -strict $max_paths] || $max_paths < 1 || $max_paths > 100000} {
        error "Timing sample max_paths must be in 1..100000"
    }
    set checkpoint [file normalize $checkpoint]
    set edif [file normalize $edif]
    set output [file normalize $output]
    if {![file isfile $checkpoint] || ![file isfile $edif]} { error "Timing export requires an existing source DCP and matching EDF" }
    file mkdir $output
    set python [rw_partition_timing_python]
    set helper [file join $::rw_partition_timing_tools prepare_timing_paths.py]
    rw_partition_timing_exec_python $python $helper $checkpoint $edif $output --record-identity --max-paths $max_paths
    file copy -force [file join $::rw_partition_timing_tools export_partition_timing.tcl] [file join $output export_partition_timing.tcl]
    file copy -force $helper [file join $output prepare_timing_paths.py]
    set complete [file join $output native_timing_complete.txt]
    if {[file exists $complete]} { file rename -force $complete [file join $output previous_native_timing_complete.txt] }
    set inventory [open [file join $output native_primitive_names.tcllist] w]
    fconfigure $inventory -encoding utf-8 -translation lf
    puts $inventory [get_property NAME [get_cells -quiet -hierarchical -filter {IS_PRIMITIVE == 1}]]
    close $inventory
    report_clocks -file [file join $output timing_native_clocks.rpt]
    # Do not manufacture clocks, input/output delays, or timing exceptions.
    # Default nworst=1 gives a bounded sample of distinct worst endpoints.
    set paths [get_timing_paths -delay_type max -sort_by slack -max_paths $max_paths -nworst 1]
    set count [llength $paths]
    set fp [open [file join $output native_timing_paths.tcllist] w]
    fconfigure $fp -encoding utf-8 -translation lf
    for {set i 0} {$i < $count} {incr i} {
        set path [lrange $paths $i $i]
        set start [get_property STARTPOINT_PIN $path]
        set end [get_property ENDPOINT_PIN $path]
        set pins [get_pins -of_objects $path]
        set pin_names {}
        set pin_parents {}
        if {[llength $pins]} {
            set pin_names [get_property NAME $pins]
            set pin_parents [get_property PARENT_CELL $pins]
        }
        set start_parent {}
        set end_parent {}
        set start_class [get_property CLASS $start]
        set end_class [get_property CLASS $end]
        if {$start_class eq "pin"} { set start_parent [get_property PARENT_CELL $start] }
        if {$end_class eq "pin"} { set end_parent [get_property PARENT_CELL $end] }
        puts $fp [list $i [get_property SLACK $path] [get_property REQUIREMENT $path] \
            [get_property DATAPATH_DELAY $path] [get_property STARTPOINT_CLOCK $path] \
            [get_property ENDPOINT_CLOCK $path] [get_property NAME $start] [get_property NAME $end] \
            $start_class $end_class $start_parent $end_parent $pin_names $pin_parents]
    }
    close $fp
    if {$count > 0} {
        # Keep an independent native human-readable order/scalar reference.
        report_timing -of_objects [lrange $paths 0 0] -file [file join $output timing_native_worst_path.rpt]
    }
    set metadata [open $complete w]
    fconfigure $metadata -encoding utf-8 -translation lf
    puts $metadata "status=NATIVE_TIMING_PATH_EXPORT_PASS"
    puts $metadata "checkpoint=$checkpoint"
    puts $metadata "edif=$edif"
    puts $metadata "requested_max_paths=$max_paths"
    puts $metadata "native_path_count=$count"
    puts $metadata "native_clock_count=[llength [get_clocks -quiet]]"
    puts $metadata "vivado_version=[version -short]"
    puts $metadata "top=[get_property TOP [current_design]]"
    puts $metadata "part=[get_property PART [current_design]]"
    close $metadata
    set result [rw_partition_timing_exec_python $python $helper $checkpoint $edif $output --process-native --max-paths $max_paths]
    set summary [dict create {*}$result]
    puts "PARTITION_TIMING_EXPORT_PASS sampled=$count exported=[dict get $summary exported_paths] available=[dict get $summary available]"
    return $summary
}

if {[info exists ::argv] && [llength $::argv] && [lindex $::argv 0] eq "--partition-timing-standalone"} {
    if {[llength $::argv] < 4 || [llength $::argv] > 5} { error "Usage: --partition-timing-standalone DCP EDF OUT ?MAX_PATHS?" }
    set_param general.maxThreads 1
    open_checkpoint [lindex $::argv 1]
    set max_paths 1000
    if {[llength $::argv] == 5} { set max_paths [lindex $::argv 4] }
    rw_export_partition_timing [lindex $::argv 1] [lindex $::argv 2] [lindex $::argv 3] $max_paths
}
