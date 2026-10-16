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

import java.util.Map;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

/** Held caller policy: whole trusted HDI vertices may move; their native
 * implementation descendants may never become independent frontier vertices. */
public final class CohortCleanupPolicy {
    public static final String SOURCE_PROTECTION = "BIGFPGA_COHORT_PROTECT";
    public static final String SCOPE = "fixed_explicit_source_policy_and_atomic_HDI_frontier_v1";
    public static final class Prepared {
        public final String requested, reason;
        public final boolean enabled;
        private final boolean[] sourceProtected;
        public final int sourceProtectedCount;
        Prepared(String requested,String reason,boolean enabled,boolean[] mask,int count) {
            this.requested=requested;this.reason=reason;this.enabled=enabled;sourceProtected=mask;sourceProtectedCount=count;
        }
        public boolean[] protectedOwners(int[] fixed) {
            if(!enabled)throw new IllegalStateException("Disabled cohort caller has no protection mask");
            if(fixed.length!=sourceProtected.length)throw new IllegalArgumentException("Fixed/frontier protection scope differs");
            boolean[] mask=sourceProtected.clone();for(int owner=1;owner<mask.length;owner++)mask[owner]|=fixed[owner]>=0;return mask;
        }
    }
    public static String validateMode(String mode) {
        if(!"auto".equals(mode)&&!"off".equals(mode))throw new IllegalArgumentException("Capture cohorts must be auto or off");return mode;
    }
    private static boolean protects(EDIFPropertyValue property) {
        if(property==null)return false;
        String value=property.getValue().trim();
        // Explicit false is the only unprotected present value. Unknown source
        // policy values conservatively protect; they never override ancestors.
        return !value.equalsIgnoreCase("false")&&!value.equals("0")&&!value.equalsIgnoreCase("no");
    }
    public static Prepared prepare(String mode,int k,int passes,Map<EDIFHierCellInst,Integer> frontier) {
        validateMode(mode);
        if(passes<0||k<1)throw new IllegalArgumentException("Invalid cohort caller partition/pass count");
        if("off".equals(mode))return new Prepared(mode,"requested_off",false,null,0);
        if(passes==0)return new Prepared(mode,"zero_pass_incumbent_audit",false,null,0);
        if(k==1)return new Prepared(mode,"single_fpga",false,null,0);
        boolean[] mask=new boolean[frontier.size()+1],seen=new boolean[frontier.size()+1];int count=0;
        for(Map.Entry<EDIFHierCellInst,Integer> entry:frontier.entrySet()) {
            int owner=entry.getValue();if(owner<1||owner>=mask.length||seen[owner])throw new IllegalArgumentException("Cohort frontier IDs are not unique/contiguous");seen[owner]=true;
            EDIFHierCellInst leaf=entry.getKey();boolean protectedOwner=false;
            for(EDIFHierCellInst current=leaf;current!=null;current=current.getParent()) {
                if(current!=leaf&&current.getCellType().getLibrary().isHDIPrimitivesLibrary())
                    throw new IllegalArgumentException("Cohort frontier splits logical HDI implementation: "+leaf);
                if(current.getInst()!=null)protectedOwner|=protects(current.getInst().getProperty(SOURCE_PROTECTION));
                protectedOwner|=protects(current.getCellType().getProperty(SOURCE_PROTECTION));
            }
            // Opaque frontier owners are protected; the prototype also skips the
            // entire phase when the supplied graph reports any opacity.
            protectedOwner|=!leaf.getCellType().getLibrary().isHDIPrimitivesLibrary()&&leaf.getCellType().isLeafCellOrBlackBox();
            mask[owner]=protectedOwner;if(protectedOwner)count++;
        }
        return new Prepared(mode,"eligible_auto",true,mask,count);
    }
    public static CaptureFaninCohort.Limits limits(HardwarePartitionGraph graph,int passes) {
        return limitsForCounts(graph.ownerVertex.length,graph.resources.length,graph.nets.size(),passes);
    }
    static CaptureFaninCohort.Limits limitsForCounts(int nodes,int owners,int nets,int passes) {
        if(nodes<0||owners<0||nets<0||passes<1)throw new IllegalArgumentException("Invalid cohort effort counts/passes");
        long scope=(long)nodes+owners+nets;
        int captures,targets,proposals;long scale,floor,ceiling;
        if(passes<=1){captures=1;targets=2;proposals=2;scale=64;floor=32_000_000;ceiling=1_000_000_000;}
        else if(passes<=3){captures=4;targets=4;proposals=8;scale=128;floor=64_000_000;ceiling=2_000_000_000;}
        else{captures=8;targets=8;proposals=16;scale=256;floor=128_000_000;ceiling=4_000_000_000L;}
        long work=Math.min(ceiling,Math.max(floor,Math.multiplyExact(scope,scale)));
        return new CaptureFaninCohort.Limits(65536,8192,1048576,captures,targets,proposals,work);
    }
    private CohortCleanupPolicy() { }
}
