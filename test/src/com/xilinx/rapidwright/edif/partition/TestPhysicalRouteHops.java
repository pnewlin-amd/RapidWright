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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent enumeration and objective-equivalence checks, not native timing claims. */
public class TestPhysicalRouteHops {
    @TempDir Path directory;

    @Test void exactMinimumLinkAndRelayBoundsMatchExhaustiveTinyPaths() throws Exception {
        RouteHopProof.main(new String[] {directory.resolve("report").toString()});
    }

    @Test void sparseObjectivesImproveGuidanceAndCompleteJ4BehaviorIsPreserved() throws Exception {
        RouteObjectiveProof.main(new String[] {directory.resolve("objective").toString()});
    }
}
