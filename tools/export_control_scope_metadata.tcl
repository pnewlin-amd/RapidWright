# Held/source-only producer interface v3. No checkpoint open or launch.
# Caller supplies the already-open original native design and binds this output
# to its existing checkpoint/export/actual native-exit receipt. Old seven-list
# electrical endpoint streams remain byte-format compatible.
proc rw_control_scope_metadata {output logical_inventory} {
    if {[version -short] ne "2025.1"} {error "Control default authority requires Vivado 2025.1"}
    if {[file exists $output]} {error "Fresh control metadata required"}
    # The caller must pin this exact original logical TSV in its native receipt.
    # It is the same inventory consumed by compare_native_cuts_stream.py.
    array set logical {}
    set logical_order [list]
    set input [open $logical_inventory r]
    fconfigure $input -encoding utf-8 -translation auto
    try {
        if {[gets $input header] < 0} {error "Missing source-bound logical inventory header"}
        set columns [split [string trimleft $header \ufeff] \t]
        set name_index [lsearch -exact $columns name]; set ref_index [lsearch -exact $columns ref_name]
        if {$name_index < 0 || $ref_index < 0 || [llength [lsort -unique $columns]] != [llength $columns]} {error "Logical inventory schema differs"}
        while {[gets $input line] >= 0} {
            set fields [split $line \t]
            if {[llength $fields] != [llength $columns]} {error "Malformed logical inventory row"}
            set name [lindex $fields $name_index]; set ref [lindex $fields $ref_index]
            if {$name eq "" || $ref eq "" || [info exists logical($name)]} {error "Empty/duplicate logical inventory identity"}
            set logical($name) $ref
            lappend logical_order $name
        }
    } finally {close $input}
    foreach name $logical_order {
        set parent $name
        while {[set slash [string last / $parent]] >= 0} {
            set parent [string range $parent 0 [expr {$slash-1}]]
            if {[info exists logical($parent)]} {error "Logical inventory includes primitive implementation descendant"}
        }
    }
    set fp [open $output {WRONLY CREAT EXCL}]
    fconfigure $fp -encoding utf-8 -translation lf
    try {
    puts $fp {#control_scope=functional_synchronous_controls_v2_UNISIM_2025_1}
    puts $fp {#default_authority=AMD_VIVADO_2025_1_UNISIM_SYNCHRONOUS_CONTROL_DEFAULTS_V2}
    puts $fp "name\tref_name\tis_primitive\tis_blackbox\tparameters_tcl_list"
    set cells [get_cells -quiet -hierarchical -filter {IS_PRIMITIVE == 1 || IS_BLACKBOX == 1}]
    set keys [dict create DSP48E1 {AREG BREG CREG DREG ADREG ALUMODEREG INMODEREG OPMODEREG CARRYINREG CARRYINSELREG MREG PREG} DSP48E2 {AREG BREG CREG DREG ADREG ALUMODEREG INMODEREG OPMODEREG CARRYINREG CARRYINSELREG MREG PREG} RAMB18E1 {DOA_REG DOB_REG} RAMB36E1 {DOA_REG DOB_REG} RAMB18E2 {DOA_REG DOB_REG} RAMB36E2 {DOA_REG DOB_REG} URAM288 {RST_MODE_A RST_MODE_B} URAM288_BASE {RST_MODE_A RST_MODE_B}]
    array set seen {}
    # Four native column queries per bounded batch. Parameter queries are only
    # for the eight reset-configurable reference types, never one per FF/LUT.
    for {set first 0} {$first < [llength $cells]} {incr first 50000} {
        set batch [lrange $cells $first [expr {$first+49999}]]
        set columns [dict create]
        foreach property {NAME REF_NAME IS_PRIMITIVE IS_BLACKBOX} {
            set values [get_property $property $batch]
            if {[llength $values] != [llength $batch]} {error "Native control metadata columns differ"}
            dict set columns $property $values
        }
        array unset groups; array set groups {}
        set group_order [list]; set selected [list]
        array unset trustedNames; array set trustedNames {}
        foreach cell $batch name [dict get $columns NAME] ref [dict get $columns REF_NAME] primitive_raw [dict get $columns IS_PRIMITIVE] blackbox_raw [dict get $columns IS_BLACKBOX] {
            if {![info exists logical($name)]} {
                set parent $name; set owned 0
                while {[set slash [string last / $parent]] >= 0} {
                    set parent [string range $parent 0 [expr {$slash-1}]]
                    if {[info exists logical($parent)]} {set owned 1; break}
                }
                if {!$owned} {error "Native primitive absent from source-bound logical inventory: $name"}
                continue
            }
            if {$ref ne $logical($name) || [info exists seen($name)]} {error "Native logical owner/reference identity differs"}
            set primitive [string tolower $primitive_raw]
            set blackbox [string tolower $blackbox_raw]
            if {$primitive ni {0 1 false true} || $blackbox ni {0 1 false true}} {error "Native logical trust flags unavailable"}
            set seen($name) 1; lappend selected [list $name $ref $primitive_raw $blackbox_raw]
            if {$primitive in {1 true} && $blackbox in {0 false} && [dict exists $keys $ref]} {
                if {![info exists groups($ref)]} {set groups($ref) [list]; lappend group_order $ref}
                lappend groups($ref) $cell; set trustedNames($cell) $name
            }
        }
        array unset parameters; array set parameters {}
        foreach ref $group_order {
            set group $groups($ref)
            # Inspect each configurable trusted object. REF_NAME does not prove
            # a uniform property schema, and blackboxes never populate it.
            foreach cell $group {
                set available [list_property $cell]
                foreach key [dict get $keys $ref] {
                    if {[lsearch -exact $available $key] < 0} {error "Trusted native reset parameter unavailable: $cell $key"}
                }
            }
            set names [list]; foreach cell $group {lappend names $trustedNames($cell)}
            foreach key [dict get $keys $ref] {
                set values [get_property $key $group]
                if {[llength $values] != [llength $group]} {error "Native reset parameter column differs"}
                foreach name $names value $values {
                    if {[string trim $value] eq ""} {error "Trusted native reset parameter value unavailable"}
                    dict set parameters($name) $key $value
                }
            }
        }
        foreach record $selected {
            lassign $record name ref primitive blackbox
            set values [expr {[info exists parameters($name)] ? $parameters($name) : [dict create]}]
            foreach value [list $name $ref $primitive $blackbox $values] {
                if {[regexp {[\t\r\n]} $value]} {error "Unsupported native control metadata token"}
            }
            puts $fp "$name\t$ref\t$primitive\t$blackbox\t$values"
        }
    }
    if {[array size seen] != [array size logical]} {error "Native logical owner coverage incomplete"}
    } finally {close $fp}
    return [array size seen]
}
