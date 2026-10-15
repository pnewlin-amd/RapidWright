# Optional licensed-tool smoke fixture. Run from any directory with one fresh
# output-directory argument. Only this small fixture is synthesized.
if {$argc != 1} { error "Expected one fresh output directory" }
set output [file normalize [lindex $argv 0]]
if {[file exists $output]} { error "Choose a fresh smoke source directory" }
file mkdir $output
set here [file dirname [file normalize [info script]]]
read_verilog [file join $here registered_pipeline.v]
synth_design -top partition_smoke -part xc7a35tcpg236-1 -mode out_of_context -flatten_hierarchy none
create_clock -name dut_clock -period 10.000 [get_ports clk]
write_checkpoint [file join $output synth.dcp]
puts "PARTITION_RELEASE_SMOKE_SOURCE_PASS"
exit 0
