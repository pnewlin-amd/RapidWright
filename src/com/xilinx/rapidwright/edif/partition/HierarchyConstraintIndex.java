/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.edif.partition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexed hierarchy constraints; preserves the existing fixed-rule semantics.
 * Path normalization, existence validation and FPGA-label validation remain the
 * caller's responsibility. Unlike a new parser, this class deliberately keeps
 * the original raw path spelling, including empty segments and trailing '/'.
 */
public final class HierarchyConstraintIndex {
    private static final class Node {
        Map<String, Node> children;
        Rule rule;

        Node child(String segment, boolean create) {
            if (children == null) {
                if (!create) { return null; }
                children = new HashMap<String, Node>();
            }
            Node result = children.get(segment);
            if (result == null && create) {
                result = new Node();
                children.put(segment, result);
            }
            return result;
        }
    }

    private static final class Rule {
        final String path;
        final int partition;
        final int specificity;
        final int ordinal;
        int matched;

        Rule(String path, int partition, int ordinal) {
            this.path = path;
            this.partition = partition;
            this.ordinal = ordinal;
            // Deliberately identical to Partitioner.java, not trie depth.
            this.specificity = path.isEmpty() ? 0 : path.split("/").length;
        }
    }

    public static final class Choice {
        public final Integer partition;
        public final int specificity;
        public final List<String> paths;

        private Choice(Integer partition, int specificity, List<String> paths) {
            this.partition = partition;
            this.specificity = specificity;
            this.paths = Collections.unmodifiableList(new ArrayList<String>(paths));
        }
    }

    private final Node root = new Node();
    private final List<Rule> orderedRules = new ArrayList<Rule>();
    private int nodeCount = 1;
    private long relatedRulesVisited;

    public static HierarchyConstraintIndex paths(Collection<String> paths) {
        HierarchyConstraintIndex result = new HierarchyConstraintIndex();
        for (String path : paths) { result.put(path, 0); }
        return result;
    }

    /** Capture the supplied map's iteration order, which the old loop uses. */
    public static HierarchyConstraintIndex assignments(Map<String, Integer> rules) {
        HierarchyConstraintIndex result = new HierarchyConstraintIndex();
        for (Map.Entry<String, Integer> rule : rules.entrySet()) {
            result.put(rule.getKey(), rule.getValue().intValue());
        }
        return result;
    }

    /** Identical duplicates are harmless; conflicting identical paths fail. */
    public void put(String path, int partition) {
        if (path == null) { throw new NullPointerException("path"); }
        Node node = find(path, true);
        if (node.rule != null) {
            if (node.rule.partition != partition) {
                throw new RuntimeException("conflicting constraints for hierarchy path '" + path + "'");
            }
            return;
        }
        Rule rule = new Rule(path, partition, orderedRules.size());
        node.rule = rule;
        orderedRules.add(rule);
    }

    private Node find(String path, boolean create) {
        Node node = root;
        if (path.isEmpty()) { return node; }
        int start = 0;
        while (true) {
            int slash = path.indexOf('/', start);
            int end = slash < 0 ? path.length() : slash;
            boolean absent = node.children == null || !node.children.containsKey(path.substring(start, end));
            node = node.child(path.substring(start, end), create);
            if (node == null) { return null; }
            if (create && absent) { nodeCount++; }
            if (slash < 0) { return node; }
            start = slash + 1;
        }
    }

    /** Exact replacement for alignment's strict-descendant predicate. */
    public boolean hasStrictDescendant(String owner) {
        Node node = find(owner, false);
        return node != null && node.children != null && !node.children.isEmpty();
    }

    /** Longest textual ancestor, useful for lifting an unchanged assignment. */
    public Choice longestPrefix(String owner) {
        List<Rule> ancestors = ancestors(owner);
        Rule best = null;
        for (Rule rule : ancestors) {
            if (best == null || rule.path.length() > best.path.length()) { best = rule; }
        }
        return best == null ? new Choice(null, -1, Collections.<String>emptyList())
                : new Choice(Integer.valueOf(best.partition), best.specificity,
                             Collections.singletonList(best.path));
    }

    private List<Rule> ancestors(String owner) {
        List<Rule> result = new ArrayList<Rule>();
        if (root.rule != null) { result.add(root.rule); }
        Node node = root;
        if (owner.isEmpty()) { return result; }
        int start = 0;
        while (true) {
            int slash = owner.indexOf('/', start);
            int end = slash < 0 ? owner.length() : slash;
            node = node.child(owner.substring(start, end), false);
            if (node == null) { return result; }
            if (node.rule != null) { result.add(node.rule); }
            if (slash < 0) { return result; }
            start = slash + 1;
        }
    }

    private static void collect(Node node, List<Rule> result) {
        // Iterative traversal avoids a stack overflow for very deep hierarchy.
        List<Node> pending = new ArrayList<Node>();
        pending.add(node);
        while (!pending.isEmpty()) {
            Node next = pending.remove(pending.size() - 1);
            if (next.rule != null) { result.add(next.rule); }
            if (next.children != null) { pending.addAll(next.children.values()); }
        }
    }

    /**
     * Exact replacement for the original related-rule scan, including matched
     * counters incremented before selection and an early equal-depth conflict.
     * Only related rules are visited. Sorting their captured ordinals preserves
     * rare unsplittable coarse-owner behavior dependent on HashMap iteration.
     */
    public Choice selectRelatedAndCount(String owner) {
        List<Rule> related = ancestors(owner);
        if (owner.isEmpty()) {
            // Original Main matches leading '/' descendants of an empty owner,
            // but not ordinary relative descendants. Alignment differs here.
            Node leadingSlash = root.child("", false);
            if (leadingSlash != null) { collect(leadingSlash, related); }
        } else {
            Node node = find(owner, false);
            if (node != null && node.children != null) {
                for (Node child : node.children.values()) { collect(child, related); }
            }
        }
        Collections.sort(related, new Comparator<Rule>() {
            @Override public int compare(Rule a, Rule b) { return Integer.compare(a.ordinal, b.ordinal); }
        });
        int bestSpecificity = -1;
        Integer bestPartition = null;
        List<String> bestPaths = new ArrayList<String>();
        for (Rule rule : related) {
            rule.matched++; // Same int arithmetic and same pre-conflict update.
            relatedRulesVisited++;
            if (rule.specificity > bestSpecificity) {
                bestSpecificity = rule.specificity;
                bestPartition = Integer.valueOf(rule.partition);
                bestPaths.clear();
                bestPaths.add(rule.path);
            } else if (rule.specificity == bestSpecificity) {
                bestPaths.add(rule.path);
                if (bestPartition.intValue() != rule.partition) {
                    throw new RuntimeException("equally-specific conflicting constraints for vertex '"
                            + owner + "': " + bestPaths);
                }
            }
        }
        return new Choice(bestPartition, bestSpecificity, bestPaths);
    }

    public Map<String, Integer> matchedCounts() {
        Map<String, Integer> result = new HashMap<String, Integer>();
        for (Rule rule : orderedRules) { result.put(rule.path, Integer.valueOf(rule.matched)); }
        return result;
    }

    public int ruleCount() { return orderedRules.size(); }
    public int nodeCount() { return nodeCount; }
    public long relatedRulesVisited() { return relatedRulesVisited; }
}
