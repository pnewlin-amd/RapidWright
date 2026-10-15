"""Declared-route lower-bound reporting, ranking and independent hard guards."""
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("route_hop_frontend_tested", Path(__file__).with_name("partitioner.py"))
FRONTEND = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FRONTEND)


def row(**changes):
    value = {"path_analysis_complete": True, "transfers": 100, "topology_distance": 100.0,
             "guard_reference_transfers": 100, "guard_reference_topology": 100.0,
             "max_crossings": 3, "endpoint_excess": 10, "transit_primitives": 5,
             "timing_cost": 2.0, "target_imbalance": .02,
             "minimum_link_hop_analysis_available": True, "max_minimum_link_hops": 3,
             "max_minimum_relay_traversals": 0}
    value.update(changes)
    return value


class RouteHopQualityContract(unittest.TestCase):
    def test_legacy_missing_fields_are_unavailable_without_invented_hops(self):
        result = FRONTEND.route_hop_metrics({}, logical_max=4)
        self.assertEqual(result, {"minimum_link_hop_analysis_available": False})
        result = FRONTEND.route_hop_metrics({"minimum_link_hop_analysis_available": "false",
                                             "after_max_minimum_link_hops": "-1"}, logical_max=4)
        self.assertEqual(result, {"minimum_link_hop_analysis_available": False})

    def test_new_engine_requires_available_complete_report_fields(self):
        for report in ({}, {"minimum_link_hop_analysis_available": "false"},
                       {"minimum_link_hop_analysis_available": "true"}):
            with self.subTest(report=report), self.assertRaises(ValueError):
                FRONTEND.route_hop_metrics(report, logical_max=1, required=True)

    def test_source_report_counts_are_parsed_and_consistency_checked(self):
        report = {"minimum_link_hop_analysis_available": "true",
                  "after_max_minimum_link_hops": "7", "after_max_minimum_relay_traversals": "2"}
        self.assertEqual(FRONTEND.route_hop_metrics(report, 3, required=True), {
            "minimum_link_hop_analysis_available": True,
            "max_minimum_link_hops": 7, "max_minimum_relay_traversals": 2})
        invalid = ({"minimum_link_hop_analysis_available": "maybe"},
                   {"after_max_minimum_link_hops": "3.0"}, {"after_max_minimum_link_hops": "nan"},
                   {"after_max_minimum_link_hops": "-1"}, {"after_max_minimum_link_hops": "2"},
                   {"after_max_minimum_relay_traversals": "-1"},
                   {"after_max_minimum_relay_traversals": "8"})
        for changes in invalid:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                FRONTEND.route_hop_metrics(dict(report, **changes), 3, required=True)

    def test_default_prioritizes_minimum_physical_hops_then_logical_crossings(self):
        lower_hops = row(max_minimum_link_hops=3, max_crossings=3, timing_cost=100)
        fewer_logical = row(max_minimum_link_hops=4, max_crossings=1, max_minimum_relay_traversals=3, timing_cost=0)
        self.assertLess(FRONTEND.candidate_priority(lower_hops), FRONTEND.candidate_priority(fewer_logical))
        fewer_logical.update(max_minimum_link_hops=3, max_minimum_relay_traversals=2, timing_cost=100)
        lower_hops.update(timing_cost=0)
        self.assertLess(FRONTEND.candidate_priority(fewer_logical), FRONTEND.candidate_priority(lower_hops))

    def test_explicit_delay_mode_preserves_slack_and_timing_priority(self):
        longer_route = row(max_minimum_link_hops=4, max_minimum_relay_traversals=2, modeled_worst_slack_ns=2.0, timing_cost=10)
        shorter_route = row(max_minimum_link_hops=3, modeled_worst_slack_ns=1.0, timing_cost=0)
        self.assertLess(FRONTEND.candidate_priority(longer_route), FRONTEND.candidate_priority(shorter_route))
        shorter_route["modeled_worst_slack_ns"] = 2.0
        longer_route["timing_cost"] = 0
        shorter_route["timing_cost"] = 10
        self.assertLess(FRONTEND.candidate_priority(longer_route), FRONTEND.candidate_priority(shorter_route))

    def test_hop_guard_is_independent_of_cut_budget_and_other_improvements(self):
        baseline = row()
        worse_route = row(max_minimum_link_hops=4, max_crossings=2, max_minimum_relay_traversals=2, timing_cost=1,
                          endpoint_excess=5, transit_primitives=2, transfers=90, topology_distance=90)
        self.assertFalse(FRONTEND.within_guard(worse_route, baseline, .10))
        missing_route = row(minimum_link_hop_analysis_available=False)
        missing_route.pop("max_minimum_link_hops")
        self.assertFalse(FRONTEND.within_guard(missing_route, baseline, .10))
        worse_logical = row(max_minimum_link_hops=3, max_crossings=4)
        self.assertFalse(FRONTEND.within_guard(worse_logical, baseline, .10))

    def test_relay_maximum_is_report_only_and_does_not_change_guard_or_rank(self):
        baseline = row(max_minimum_relay_traversals=0)
        different_worst_path = row(max_minimum_relay_traversals=2)
        self.assertTrue(FRONTEND.within_guard(different_worst_path, baseline, .10))
        self.assertEqual(FRONTEND.candidate_priority(baseline), FRONTEND.candidate_priority(different_worst_path))

    def test_complete_graph_h_equals_c_preserves_legacy_pair_order(self):
        # All physical pairs exist. Enabling the proved H=C value must preserve
        # both source-only and explicit-delay ordering for a candidate pair.
        for modeled in (None, 3.0):
            left = row(max_crossings=2, max_minimum_link_hops=2, timing_cost=10)
            right = row(max_crossings=3, max_minimum_link_hops=3, timing_cost=1)
            if modeled is not None:
                left["modeled_worst_slack_ns"] = modeled
                right["modeled_worst_slack_ns"] = modeled + 1
            new_less = FRONTEND.candidate_priority(left) < FRONTEND.candidate_priority(right)
            for value in (left, right):
                value["minimum_link_hop_analysis_available"] = False
                value.pop("max_minimum_link_hops")
                value.pop("max_minimum_relay_traversals")
            self.assertEqual(new_less, FRONTEND.candidate_priority(left) < FRONTEND.candidate_priority(right))


if __name__ == "__main__":
    unittest.main()
