import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('tested_ranked_frontend', Path(__file__).with_name('partitioner.py'))
frontend = importlib.util.module_from_spec(spec)
spec.loader.exec_module(frontend)

def row(**changes):
    value = dict(path_analysis_complete=True, minimum_link_hop_analysis_available=True,
                 max_minimum_link_hops=5, max_crossings=5, timing_cost=0.0,
                 endpoint_excess=26752, transit_primitives=8124,
                 target_imbalance=.0066, transfers=3909, topology_distance=3909.0,
                 guard_reference_transfers=3460, guard_reference_topology=3460.0)
    value.update(changes)
    return value

class RankedQualityGuardTests(unittest.TestCase):
    def accepted(self, proposal, baseline=None, policy='ranked', growth=.15):
        return frontend.within_guard(proposal, baseline or row(), growth, policy)

    def test_four_crossings_can_replace_five_with_one_more_transit_primitive(self):
        proposal = row(max_minimum_link_hops=4, max_crossings=4, endpoint_excess=26583,
                       transit_primitives=8125, transfers=3905, topology_distance=3905.0)
        self.assertTrue(self.accepted(proposal))
        self.assertFalse(self.accepted(proposal, policy='componentwise'))

    def test_higher_hop_primary_is_rejected_despite_fewer_cuts_and_transit(self):
        self.assertFalse(self.accepted(row(max_minimum_link_hops=6, max_crossings=4,
                                          transfers=100, endpoint_excess=0, transit_primitives=0)))

    def test_sparse_topology_priority_is_hops_before_logical_crossings(self):
        baseline = row(max_minimum_link_hops=5, max_crossings=3)
        self.assertTrue(self.accepted(row(max_minimum_link_hops=4, max_crossings=4), baseline))
        self.assertFalse(self.accepted(row(max_minimum_link_hops=4, max_crossings=4), baseline, 'componentwise'))

    def test_equal_hops_worse_logical_crossings_is_rejected(self):
        self.assertFalse(self.accepted(row(max_crossings=5, endpoint_excess=0), row(max_crossings=4)))

    def test_equal_boundaries_worse_sampled_timing_is_rejected(self):
        self.assertFalse(self.accepted(row(timing_cost=.001, endpoint_excess=0)))

    def test_source_mode_better_boundary_can_trade_sampled_timing(self):
        self.assertTrue(self.accepted(row(max_minimum_link_hops=4, max_crossings=4, timing_cost=10.0)))

    def test_equal_primary_better_endpoint_excess_can_trade_transit(self):
        self.assertTrue(self.accepted(row(endpoint_excess=26751, transit_primitives=9000)))

    def test_worse_endpoint_excess_cannot_hide_behind_lower_transit(self):
        self.assertFalse(self.accepted(row(endpoint_excess=26753, transit_primitives=0)))

    def test_equal_quality_does_not_promote_soft_balance_or_cuts_to_guard(self):
        self.assertTrue(self.accepted(row(target_imbalance=1.0, transfers=3910)))

    def test_explicit_delay_improved_worst_slack_is_primary(self):
        baseline = row(modeled_worst_slack_ns=-10.0)
        proposal = row(modeled_worst_slack_ns=-9.0, timing_cost=100.0,
                       max_minimum_link_hops=6, max_crossings=6, transit_primitives=10000)
        self.assertTrue(self.accepted(proposal, baseline))
        self.assertFalse(self.accepted(proposal, baseline, 'componentwise'))

    def test_explicit_delay_worse_slack_rejects_even_better_boundaries(self):
        self.assertFalse(self.accepted(row(modeled_worst_slack_ns=-11.0, max_minimum_link_hops=1, max_crossings=1),
                                       row(modeled_worst_slack_ns=-10.0)))

    def test_explicit_delay_equal_slack_compares_cost_before_hops(self):
        self.assertFalse(self.accepted(row(modeled_worst_slack_ns=-10.0, timing_cost=1, max_minimum_link_hops=1, max_crossings=1),
                                       row(modeled_worst_slack_ns=-10.0)))

    def test_ranked_timing_uses_exact_existing_double_comparison(self):
        proposal = row(timing_cost=1e-10)
        self.assertFalse(self.accepted(proposal))
        self.assertTrue(self.accepted(proposal, policy='componentwise'))

    def test_incomplete_analysis_cannot_replace_complete_analysis(self):
        self.assertFalse(self.accepted(row(path_analysis_complete=False, max_minimum_link_hops=0, max_crossings=0)))

    def test_missing_timing_or_hop_metrics_fail_closed(self):
        for field in ('timing_cost', 'minimum_link_hop_analysis_available'):
            proposal = row()
            proposal.pop(field)
            self.assertFalse(self.accepted(proposal), field)
        self.assertFalse(self.accepted(row(), row(modeled_worst_slack_ns=0)))

    def test_cut_budget_stays_hard_despite_better_boundaries(self):
        baseline = row(guard_reference_transfers=3269, guard_reference_topology=3269)
        self.assertFalse(self.accepted(row(max_minimum_link_hops=4, max_crossings=4), baseline, growth=.10))

    def test_topology_budget_stays_hard_despite_better_boundaries(self):
        self.assertFalse(self.accepted(row(max_minimum_link_hops=4, max_crossings=4, topology_distance=3980)))

    def test_budget_reference_does_not_compound(self):
        baseline = row(transfers=11, topology_distance=11, guard_reference_transfers=10, guard_reference_topology=10)
        self.assertFalse(self.accepted(row(transfers=12, topology_distance=12, max_minimum_link_hops=4, max_crossings=4), baseline, growth=.10))

    def test_candidate_order_shares_the_guard_prefix(self):
        proposal = row(max_minimum_link_hops=4, max_crossings=4, transit_primitives=8125)
        self.assertLess(frontend.candidate_priority(proposal), frontend.candidate_priority(row()))
        self.assertEqual(frontend.candidate_priority(proposal)[:6], frontend.quality_guard_priority(proposal))

    def test_public_default_and_explicit_conservative_switch(self):
        flags = ['--input', 'source.dcp', '--system-topology', 'j4.json']
        self.assertEqual(frontend.parser().parse_args(flags).quality_guard, 'ranked')
        self.assertEqual(frontend.parser().parse_args(flags + ['--quality-guard', 'componentwise']).quality_guard, 'componentwise')

    def test_legacy_helper_default_preserves_componentwise_behavior(self):
        self.assertFalse(frontend.within_guard(row(transit_primitives=8125, max_minimum_link_hops=4, max_crossings=4), row(), .15))

    def test_unknown_policy_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown'):
            self.accepted(row(), policy='invented')

    def test_new_engine_policy_confirmation_and_mismatch(self):
        provenance = dict(quality_guard_policy_version=1, quality_guard_policy='ranked')
        self.assertTrue(frontend.validate_quality_guard_report(dict(quality_guard_policy_version='1', quality_guard_policy='ranked'), provenance, 'ranked'))
        for report, metadata in ((dict(quality_guard_policy_version='1'), provenance),
                                  (dict(quality_guard_policy_version='1', quality_guard_policy='componentwise'), provenance),
                                  (dict(quality_guard_policy_version='1', quality_guard_policy='ranked'), dict(quality_guard_policy_version=1))):
            with self.assertRaisesRegex(ValueError, 'differs'):
                frontend.validate_quality_guard_report(report, metadata, 'ranked')

    def test_legacy_unconfirmed_policy_and_invalid_version(self):
        self.assertFalse(frontend.validate_quality_guard_report({}, {}, 'ranked'))
        for version in (True, '1', 0, 2):
            with self.assertRaisesRegex(ValueError, 'version'):
                frontend.validate_quality_guard_report({}, dict(quality_guard_policy_version=version), 'ranked')

    def test_report_version_is_checked_independently(self):
        provenance = dict(quality_guard_policy_version=1, quality_guard_policy='ranked')
        for version in ('2', '01', True, 1):
            with self.assertRaisesRegex(ValueError, 'report.*version'):
                frontend.validate_quality_guard_report(dict(quality_guard_policy_version=version, quality_guard_policy='ranked'), provenance, 'ranked')

    def test_partial_version_confirmation_cannot_claim_new_policy(self):
        for report, provenance in ((dict(quality_guard_policy='ranked'), dict(quality_guard_policy_version=1, quality_guard_policy='ranked')),
                                   (dict(quality_guard_policy_version='1', quality_guard_policy='ranked'), {})):
            with self.assertRaisesRegex(ValueError, 'Incomplete.*version'):
                frontend.validate_quality_guard_report(report, provenance, 'ranked')

if __name__ == '__main__':
    unittest.main()
