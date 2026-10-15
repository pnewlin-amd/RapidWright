package com.xilinx.rapidwright.edif.partition;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Source-object witnesses for virtual slash prefixes emitted by mapping compression.
 * No netlist rewriting, primitive implementation traversal, or frontier/rule scan.
 */
final class SourceConstraintPrefixes {
    private final EDIFNetlist netlist;
    private final Map<EDIFCell, Boolean> primitiveDescendant = new IdentityHashMap<>();
    private final Map<EDIFCell, TreeMap<String, EDIFCellInst>> childPrefixes = new IdentityHashMap<>();
    private long childrenIndexed;
    private long prefixQueries;

    SourceConstraintPrefixes(EDIFNetlist netlist) { this.netlist = netlist; }

    private static boolean insidePrimitive(EDIFHierCellInst instance) {
        for (EDIFHierCellInst parent = instance.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getCellType().getLibrary().isHDIPrimitivesLibrary()) { return true; }
        }
        return false;
    }

    private static Boolean boundary(EDIFCell cell) {
        if (cell.getLibrary().isHDIPrimitivesLibrary()) {
            // A logical UNISIM is indivisible even when its library describes
            // implementation children. Real infrastructure remains visible;
            // this witness is not the independent useful-occupancy policy.
            return Boolean.valueOf(!cell.getName().equals("GND") && !cell.getName().equals("VCC"));
        }
        return cell.isLeafCellOrBlackBox() ? Boolean.FALSE : null;
    }

    private static final class Frame {
        final EDIFCell cell;
        final Iterator<EDIFCellInst> children;
        boolean found;
        Frame(EDIFCell cell) { this.cell = cell; this.children = cell.getCellInsts().iterator(); }
    }

    private boolean ownsRealPrimitive(EDIFCell cell) {
        Boolean known = primitiveDescendant.get(cell);
        if (known != null) { return known.booleanValue(); }
        Boolean terminal = boundary(cell);
        if (terminal != null) { primitiveDescendant.put(cell, terminal); return terminal.booleanValue(); }
        // Iterative postorder and identity memoization bound deep/shared cells.
        List<Frame> stack = new ArrayList<>();
        Set<EDIFCell> active = Collections.newSetFromMap(new IdentityHashMap<EDIFCell, Boolean>());
        stack.add(new Frame(cell)); active.add(cell);
        while (!stack.isEmpty()) {
            Frame frame = stack.get(stack.size() - 1);
            if (!frame.children.hasNext()) {
                primitiveDescendant.put(frame.cell, Boolean.valueOf(frame.found));
                active.remove(frame.cell); stack.remove(stack.size() - 1);
                if (!stack.isEmpty()) { stack.get(stack.size() - 1).found |= frame.found; }
                continue;
            }
            EDIFCell child = frame.children.next().getCellType();
            Boolean value = primitiveDescendant.get(child);
            if (value == null) { value = boundary(child); }
            if (value != null) { primitiveDescendant.put(child, value); frame.found |= value.booleanValue(); }
            else {
                if (!active.add(child)) { throw new IllegalArgumentException("Recursive EDIF hierarchy at " + child.getName()); }
                stack.add(new Frame(child));
            }
        }
        return primitiveDescendant.get(cell).booleanValue();
    }

    private TreeMap<String, EDIFCellInst> indexedChildren(EDIFCell cell) {
        TreeMap<String, EDIFCellInst> result = childPrefixes.get(cell);
        if (result != null) { return result; }
        result = new TreeMap<>();
        for (EDIFCellInst child : cell.getCellInsts()) {
            childrenIndexed++;
            if (ownsRealPrimitive(child.getCellType())) { result.put(child.getName(), child); }
        }
        childPrefixes.put(cell, result);
        return result;
    }

    boolean exists(String path) {
        EDIFHierCellInst exact = netlist.getHierCellInstFromName(path);
        if (exact != null) {
            // A literal "a/b" and actual a->b can share one printed path.
            // The ordinary getter chooses one; ownership must not silently do so.
            int ancestorEnd = path.lastIndexOf('/');
            while (true) {
                String ancestorName = ancestorEnd < 0 ? "" : path.substring(0, ancestorEnd);
                EDIFHierCellInst ancestor = netlist.getHierCellInstFromName(ancestorName);
                if (ancestor != null && !insidePrimitive(ancestor)
                        && !ancestor.getCellType().getLibrary().isHDIPrimitivesLibrary()) {
                    String relative = ancestorEnd < 0 ? path : path.substring(ancestorEnd + 1);
                    EDIFCellInst child = ancestor.getCellType().getCellInst(relative);
                    if (child != null && !ancestor.getChild(child).equals(exact)) {
                        throw new IllegalArgumentException("Ambiguous source hierarchy path: " + path);
                    }
                }
                if (ancestorEnd < 0) { break; }
                ancestorEnd = path.lastIndexOf('/', ancestorEnd - 1);
            }
            return !insidePrimitive(exact);
        }
        int end = path.lastIndexOf('/');
        while (true) {
            String ancestorName = end < 0 ? "" : path.substring(0, end);
            EDIFHierCellInst ancestor = netlist.getHierCellInstFromName(ancestorName);
            if (ancestor != null && !insidePrimitive(ancestor)
                    && !ancestor.getCellType().getLibrary().isHDIPrimitivesLibrary()) {
                String relative = end < 0 ? path : path.substring(end + 1);
                String prefix = relative + "/";
                TreeMap<String, EDIFCellInst> children = indexedChildren(ancestor.getCellType());
                prefixQueries++;
                String witness = children.ceilingKey(prefix);
                if (witness != null && witness.startsWith(prefix)) {
                    EDIFHierCellInst child = ancestor.getChild(children.get(witness));
                    EDIFHierCellInst selected = netlist.getHierCellInstFromName(child.toString());
                    if (selected != null && !selected.equals(child)) {
                        throw new IllegalArgumentException("Ambiguous source hierarchy path: " + child);
                    }
                    return true;
                }
            }
            if (end < 0) { return false; }
            end = path.lastIndexOf('/', end - 1);
        }
    }

    long childrenIndexed() { return childrenIndexed; }
    long prefixQueries() { return prefixQueries; }
}
