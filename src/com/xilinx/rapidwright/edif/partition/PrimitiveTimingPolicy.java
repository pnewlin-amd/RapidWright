/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFCellInst;

/** Conservative pin roles for trusted AMD memory and DSP primitives.
 * LUT RAM and SRLs have synchronous writes but asynchronous address-to-output
 * paths. A separate capture endpoint avoids inventing write-data feedthrough.
 * Sources: AMD UG953/UG574 and Vivado 2025.1 UNISIM RAM/SRL models. */
public final class PrimitiveTimingPolicy {
    static final int COMBINATIONAL = 1;
    static final int CAPTURE = 2;
    public static final String CONTROL_SCOPE = "functional_synchronous_controls_v2_UNISIM_2025_1";
    public static final String DEFAULT_AUTHORITY = "AMD_VIVADO_2025_1_UNISIM_SYNCHRONOUS_CONTROL_DEFAULTS_V2";

    private PrimitiveTimingPolicy() { }

    private static boolean trusted(EDIFHierCellInst primitive) {
        return primitive.getCellType().getLibrary().isHDIPrimitivesLibrary();
    }

    static boolean trusted(EDIFCellInst primitive) {
        return primitive != null && primitive.getCellType().getLibrary().isHDIPrimitivesLibrary();
    }

    /** Only known top-module reset-stage defaults from pinned Vivado 2025.1
     * UNISIM declarations are used. Invalid present values never use a default. */
    static Integer registerParameter(EDIFCellInst primitive, String key) {
        if (!trusted(primitive)) return null;
        String type = primitive.getCellType().getName();
        boolean dsp = type.matches("DSP48E[12]");
        boolean bram = type.matches("RAMB(18|36)E[12]");
        boolean two = "AREG".equals(key) || "BREG".equals(key);
        boolean recognized = dsp && key.matches("AREG|BREG|CREG|DREG|ADREG|ALUMODEREG|INMODEREG|OPMODEREG|CARRYINREG|CARRYINSELREG|MREG|PREG")
                || bram && key.matches("DOA_REG|DOB_REG");
        if (!recognized) return null;
        EDIFPropertyValue property = primitive.getProperty(key);
        if (property == null) return dsp || type.endsWith("E2") ? 1 : 0;
        try {
            String literal = property.getValue().trim();
            if (!literal.matches("[+-]?[0-9]+")) return null;
            int value = Integer.parseInt(literal);
            return value >= 0 && value <= (two ? 2 : 1) ? value : null;
        } catch (NumberFormatException e) { return null; }
    }

    private static String resetMode(EDIFCellInst primitive, char port) {
        if (!trusted(primitive) || !primitive.getCellType().getName().matches("URAM288(_BASE)?")) return null;
        EDIFPropertyValue property = primitive.getProperty("RST_MODE_" + port);
        String value = property == null ? "SYNC" : property.getValue();
        return "SYNC".equals(value) || "ASYNC".equals(value) ? value : null;
    }

    static boolean validControlParameters(EDIFCellInst primitive) {
        if (!trusted(primitive)) return false;
        String type = primitive.getCellType().getName();
        String[] keys = type.matches("DSP48E[12]") ? new String[] {"AREG", "BREG", "CREG", "DREG", "ADREG", "ALUMODEREG", "INMODEREG", "OPMODEREG", "CARRYINREG", "CARRYINSELREG", "MREG", "PREG"}
                : type.matches("RAMB(18|36)E[12]") ? new String[] {"DOA_REG", "DOB_REG"} : new String[0];
        for (String key : keys) if (primitive.getProperty(key) != null && registerParameter(primitive, key) == null) return false;
        if (type.matches("URAM288(_BASE)?")) for (char port : new char[] {'A', 'B'}) {
            if (primitive.getProperty("RST_MODE_" + port) != null && resetMode(primitive, port) == null) return false;
        }
        return true;
    }

    /** Reset/set input dependency: captured, excluded async/disabled, or unknown
     * conservative combinational. Unknown/untrusted names cannot gain barriers. */
    static int resetRoles(EDIFCellInst primitive, String pin) {
        if (!trusted(primitive) || !validControlParameters(primitive)) return COMBINATIONAL;
        String type = primitive.getCellType().getName();
        if ("FDRE".equals(type) && "R".equals(pin) || "FDSE".equals(type) && "S".equals(pin)) return CAPTURE;
        if (("FDCE".equals(type) || "LDCE".equals(type)) && "CLR".equals(pin)
                || ("FDPE".equals(type) || "LDPE".equals(type)) && "PRE".equals(pin)) return 0;
        if (type.matches("RAMB(18|36)E[12]")) {
            if ("RSTRAMARSTRAM".equals(pin) || "RSTRAMB".equals(pin)) return CAPTURE;
            if ("RSTREGARSTREG".equals(pin) || "RSTREGB".equals(pin)) {
                Integer enabled = registerParameter(primitive, "RSTREGB".equals(pin) ? "DOB_REG" : "DOA_REG");
                return enabled == null ? COMBINATIONAL : enabled == 1 ? CAPTURE : 0;
            }
        }
        if (type.matches("URAM288(_BASE)?") && pin.matches("RST_[AB]")) {
            String mode = resetMode(primitive, pin.charAt(pin.length() - 1));
            return mode == null ? COMBINATIONAL : "SYNC".equals(mode) ? CAPTURE : 0;
        }
        if (type.matches("DSP48E[12]")) {
            String[] stages;
            switch (pin) {
            case "RSTA": stages = new String[] {"AREG"}; break;
            case "RSTB": stages = new String[] {"BREG"}; break;
            case "RSTC": stages = new String[] {"CREG"}; break;
            case "RSTD": stages = new String[] {"DREG", "ADREG"}; break;
            case "RSTALUMODE": stages = new String[] {"ALUMODEREG"}; break;
            case "RSTINMODE": stages = new String[] {"INMODEREG"}; break;
            case "RSTCTRL": stages = new String[] {"OPMODEREG", "CARRYINSELREG"}; break;
            case "RSTALLCARRYIN": stages = new String[] {"CARRYINREG", "MREG"}; break;
            case "RSTM": stages = new String[] {"MREG"}; break;
            case "RSTP": stages = new String[] {"PREG"}; break;
            default: return COMBINATIONAL;
            }
            boolean capture = false;
            for (String stage : stages) {
                Integer enabled = registerParameter(primitive, stage);
                if (enabled == null) return COMBINATIONAL;
                capture |= enabled > 0;
            }
            return capture ? CAPTURE : 0;
        }
        return COMBINATIONAL;
    }

    static boolean clockPin(EDIFCellInst primitive, String pin) {
        return trusted(primitive) && LogicalResourceCounts.isClockPin(primitive.getCellType().getName(), pin);
    }

    static boolean resetPin(EDIFCellInst primitive, String pin) {
        if (!trusted(primitive)) return false;
        String type = primitive.getCellType().getName();
        return "FDRE".equals(type) && "R".equals(pin) || "FDSE".equals(type) && "S".equals(pin)
                || ("FDCE".equals(type) || "LDCE".equals(type)) && "CLR".equals(pin)
                || ("FDPE".equals(type) || "LDPE".equals(type)) && "PRE".equals(pin)
                || type.matches("RAMB(18|36)E[12]") && pin.matches("RSTRAMARSTRAM|RSTRAMB|RSTREGARSTREG|RSTREGB")
                || type.matches("URAM288(_BASE)?") && pin.matches("RST_[AB]")
                || type.matches("DSP48E[12]") && pin.matches("RSTA|RSTB|RSTC|RSTD|RSTALUMODE|RSTINMODE|RSTCTRL|RSTALLCARRYIN|RSTM|RSTP");
    }

    static boolean hasResetCapture(EDIFHierCellInst primitive) {
        if (LogicalResourceCounts.isFlipFlop(primitive.getCellType().getName())) return false;
        for (EDIFHierPortInst pin : primitive.getHierPortInsts()) {
            String name = pin.getPortInst().getPort().getBusName();
            if (pin.isInput() && pin.getHierarchicalNet() != null
                    && LogicalResourceCounts.isResetPin(primitive.getCellType().getName(), name)
                    && resetRoles(primitive.getInst(), name) == CAPTURE) return true;
        }
        return false;
    }

    static boolean unresolvedResetControls(EDIFHierCellInst primitive) {
        if (!trusted(primitive)) return false; // Already counted as opaque by graph.
        if (!validControlParameters(primitive.getInst())) return true;
        for (EDIFHierPortInst pin : primitive.getHierPortInsts()) {
            String name = pin.getPortInst().getPort().getBusName();
            if (pin.isInput() && pin.getHierarchicalNet() != null
                    && LogicalResourceCounts.isResetPin(primitive.getCellType().getName(), name)
                    && resetRoles(primitive.getInst(), name) == COMBINATIONAL) return true;
        }
        return false;
    }

    static boolean hasWriteCapture(EDIFHierCellInst primitive) {
        if (!trusted(primitive)) return false;
        String type = primitive.getCellType().getName();
        if (!type.startsWith("RAM") && !type.startsWith("SRL") && !type.startsWith("URAM")) return false;
        if (type.startsWith("RAMB") && !type.matches("RAMB(18|36)E[12]")) return false;
        if (type.startsWith("URAM") && !type.matches("URAM288(_BASE)?")) return false;
        return LogicalResourceCounts.isShiftRegister(type) || LogicalResourceCounts.isLutRam(type)
                || type.matches("RAM[DS](32(M64)?|64E[15]?)")
                || "RAM64X8SW".equals(type) || "RAM32X16DR8".equals(type)
                || type.startsWith("RAMB") || type.startsWith("URAM");
    }

    private static boolean connectedOutput(EDIFHierCellInst primitive, String output) {
        for (EDIFHierPortInst pin : primitive.getHierPortInsts()) {
            if (pin.isOutput() && pin.getHierarchicalNet() != null
                    && output.equals(pin.getPortInst().getPort().getBusName())) return true;
        }
        return false;
    }

    private static int readRole(EDIFHierCellInst primitive, String output) {
        return connectedOutput(primitive, output) ? COMBINATIONAL : 0;
    }

    /** Bit mask: keep a read/unknown arc, a synchronous capture arc, or both. */
    static int inputRoles(EDIFHierCellInst primitive, String pin) {
        if (!hasWriteCapture(primitive)) return COMBINATIONAL;
        String type = primitive.getCellType().getName();
        if ("CLK".equals(pin) || "WCLK".equals(pin)) return 0;
        if (type.startsWith("RAMB")) {
            // Cascades may bypass storage; ordinary address, write and enable
            // pins still act only on a clock edge even when a cascade is used.
            // E2 cascade output select/enable controls have their own flops
            // (UNISIM RAMB36E2.v:2487-2506); cascade data still may bypass.
            if (("RAMB18E2".equals(type) || "RAMB36E2".equals(type))
                    && pin.matches("CAS(DOMUX|OREGIMUX)([AB]|EN_[AB])")) return CAPTURE;
            if (pin.startsWith("CAS")) return COMBINATIONAL | CAPTURE;
            if (pin.matches("DI(A|B|ADI|BDI)?|DIP(A|B|ADIP|BDIP)?|DIN(A|B|ADIN|BDIN|PADINP|PBDINP)|ADDR(A|B|ENA|ENB|ARDADDR|BWRADDR)|WE(A|B|BWE)?|EN(A|B|ARDEN|BWREN)|REGCE(A|B|AREGCE)?|ECCPIPECE|INJECT[DS]BITERR")) {
                return CAPTURE;
            }
            return COMBINATIONAL; // Unknown controls (e.g. SLEEP) remain conservative.
        }
        if (type.startsWith("URAM")) {
            if (pin.startsWith("CAS")) return COMBINATIONAL | CAPTURE;
            if (pin.matches("DIN_[AB]|ADDR_[AB]|BWE_[AB]|EN_[AB]|RDB_WR_[AB]|REG_CE_[AB]|INJECT_[DS]BITERR_[AB]")) {
                return CAPTURE;
            }
            return COMBINATIONAL;
        }
        if ("D".equals(pin) || "I".equals(pin) || pin.matches("DI[A-H]?")
                || "WE".equals(pin) || "CE".equals(pin) || pin.startsWith("WADR")
                || "WSEL".equals(pin)) return CAPTURE;
        if (LogicalResourceCounts.isShiftRegister(type)) {
            return pin.matches("A[0-9]*") ? readRole(primitive, "Q") : COMBINATIONAL;
        }
        // Shared single-port address and multi-port write/read address: preserve
        // the asynchronous read dependency AND record the write capture endpoint.
        if (type.matches("RAM[0-9]+X[0-9]+S.*") && (pin.matches("A[0-9]*") || "A".equals(pin))) {
            return readRole(primitive, "O") | CAPTURE;
        }
        if (type.startsWith("RAMS") && pin.startsWith("ADR")) return readRole(primitive, "O") | CAPTURE;
        if (type.matches("RAM(32|64)M(8|16)?|RAM32X16DR8") && pin.matches("ADDR[A-H]")) {
            char port = pin.charAt(pin.length() - 1);
            char write = "RAM32M".equals(type) || "RAM64M".equals(type) ? 'D' : 'H';
            return readRole(primitive, "DO" + port) | (port == write ? CAPTURE : 0);
        }
        // Dual-port primitives retain their A write/read-address path to SPO;
        // DPRA/RADR remain asynchronous reads, never capture-only boundaries.
        if (type.matches("RAM[0-9]+X1D.*") && pin.matches("A[0-9]+")) {
            return readRole(primitive, "SPO") | CAPTURE;
        }
        if (type.matches("RAM[0-9]+X1D.*") && pin.matches("DPRA[0-9]+")) return readRole(primitive, "DPO");
        return COMBINATIONAL;
    }

    /** PREG can be a whole-cell barrier only when every connected output uses it. */
    static boolean registeredDspOutputs(EDIFHierCellInst primitive) {
        if (!trusted(primitive)) return false;
        String type = primitive.getCellType().getName();
        if (!"DSP48E1".equals(type) && !"DSP48E2".equals(type)) return false;
        if (!validControlParameters(primitive.getInst())) return false;
        Integer preg = registerParameter(primitive.getInst(), "PREG");
        if (preg == null || preg != 1) return false;
        boolean output = false;
        for (EDIFHierPortInst pin : primitive.getHierPortInsts()) {
            if (!pin.isOutput() || pin.getHierarchicalNet() == null) continue;
            output = true;
            String name = pin.getPortInst().getPort().getBusName();
            if (!name.matches("P|PCOUT|CARRYOUT|CARRYCASCOUT|MULTSIGNOUT|PATTERNDETECT|PATTERNBDETECT|OVERFLOW|UNDERFLOW|XOROUT")) {
                return false;
            }
        }
        return output;
    }
}
