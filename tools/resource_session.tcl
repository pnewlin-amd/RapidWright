# Source the unchanged native resource oracle for each sequential request.
# Protocol: REQUEST_ID<TAB>MAPPING<TAB>OUTPUT, followed by STOP.
if {$argc != 3} { error "Expected CHECKPOINT REPORT_SCRIPT REPORT_SCRIPT_SHA256" }
set rw_bound_checkpoint [file normalize [lindex $argv 0]]
set rw_report_script [file normalize [lindex $argv 1]]
set rw_report_sha256 [lindex $argv 2]
set rw_loaded 0
set rw_bound_sha256 ""
fconfigure stdout -buffering line
fconfigure stdin -buffering line -encoding utf-8
proc rw_session_sha256 {path} {
  global tcl_platform
  if {$tcl_platform(platform) eq "windows"} {
    foreach line [split [exec certutil.exe -hashfile $path SHA256] "\n"] {
      set line [string trim $line]
      if {[regexp {^[a-fA-F0-9]{64}$} $line]} { return [string tolower $line] }
    }
  } else {
    set hash [lindex [exec sha256sum -- $path] 0]
    if {[regexp {^[a-fA-F0-9]{64}$} $hash]} { return [string tolower $hash] }
  }
  error "Cannot hash session input $path"
}
rename open_checkpoint rw_original_open_checkpoint
rename exit rw_original_exit
proc open_checkpoint {path args} {
  global checkpoint_sha256 rw_loaded rw_bound_checkpoint rw_bound_sha256
  if {[llength $args] || [file normalize $path] ne $rw_bound_checkpoint} {
    error "Persistent oracle cannot switch checkpoint"
  }
  if {$rw_bound_sha256 eq ""} { set rw_bound_sha256 $checkpoint_sha256 }
  if {$checkpoint_sha256 ne $rw_bound_sha256} {
    error "Persistent checkpoint bytes changed; discard loaded design"
  }
  if {$rw_loaded} {
    puts "RW_NATIVE_CHECKPOINT_REUSED $rw_bound_sha256"
    return
  }
  rw_original_open_checkpoint $path
  set rw_loaded 1
  puts "RW_NATIVE_CHECKPOINT_OPENED $rw_bound_sha256"
}
proc exit {args} {
  if {[llength $args] && [lindex $args 0] != 0} { error "Oracle requested nonzero exit" }
  return
}
proc rw_session_source_report {report_script checkpoint mapping output} {
  global checkpoint_sha256
  set argv [list $checkpoint $mapping $output]
  set argc 3
  source $report_script
}
puts "RW_NATIVE_READY"
while {[gets stdin rw_request] >= 0} {
  if {$rw_request eq "STOP"} {
    puts "RW_NATIVE_STOPPED"
    rw_original_exit 0
  }
  set rw_parts [split $rw_request "\t"]
  set rw_request_id [lindex $rw_parts 0]
  if {[llength $rw_parts] != 3 || ![regexp {^[A-Za-z0-9_-]+$} $rw_request_id]} {
    puts "RW_NATIVE_RESPONSE\t$rw_request_id\tFAIL\tInvalid request protocol"
    rw_original_exit 1
  }
  set rw_mapping [file normalize [lindex $rw_parts 1]]
  set rw_output [file normalize [lindex $rw_parts 2]]
  if {[catch {
    # The persistent protocol requires the normal Python prepared manifest.
    # That path avoids repeating a very large native primitive census.
    if {![file isfile [file join $rw_output preassigned_cells.tsv]]} {
      error "Request needs preassigned_cells.tsv from the existing preparer"
    }
    if {[rw_session_sha256 $rw_report_script] ne $rw_report_sha256} {
      error "Production report script changed during session"
    }
    # Each source invocation owns its report variables and collections.
    rw_session_source_report $rw_report_script $rw_bound_checkpoint $rw_mapping $rw_output
    if {[rw_session_sha256 $rw_bound_checkpoint] ne $rw_bound_sha256} {
      error "Persistent checkpoint changed during request"
    }
  } rw_error]} {
    set rw_error [string range [string map {\t " " \r " " \n " "} $rw_error] 0 2000]
    puts "RW_NATIVE_RESPONSE\t$rw_request_id\tFAIL\t$rw_error"
    rw_original_exit 1
  }
  puts "RW_NATIVE_RESPONSE\t$rw_request_id\tPASS\t$rw_bound_sha256"
}
puts "RW_NATIVE_STOPPED EOF"
rw_original_exit 0
