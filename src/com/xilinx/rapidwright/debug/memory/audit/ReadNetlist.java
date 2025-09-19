package com.xilinx.rapidwright.debug.memory.audit;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFTools;

public class ReadNetlist {

    private static void memAudit(String label) {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        System.out.printf("PARTITIONER DEBUG: %s -> used=%d MB total=%d MB free=%d MB%n",
                label, used / (1024 * 1024), total / (1024 * 1024), free / (1024 * 1024));
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: ReadNetlist <netlist.[edf|dcp]>");
            return;
        }

        memAudit("readnetlist mem usg before read");
        System.out.println("Reading netlist....");
        Design design = null;
        if (args[0].endsWith(".dcp")) {
            design = Design.readCheckpoint(args[0]);
        } else {
            design = new Design(EDIFTools.readEdifFile(args[0]));
        }
        memAudit("readnetlist mem usg after read");


        //kept reference to prevent GC until after waiting
        if (design == null) {
            System.err.println("ERROR: Failed to load design.");
        }
    }
}
