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
