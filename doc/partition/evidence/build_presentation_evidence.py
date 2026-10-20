#!/usr/bin/env python3
"""Extract plain-English paper data without running partitioning or Vivado.

Uses checked-in evidence and the separately preserved OpenTitan iteration CSV
and final summary. Run from any directory with --workspace pointing to their
common parent. Hashes always identify input bytes, before path normalization.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[2]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def renamed_metrics(row: dict) -> dict:
    names = {
        "max_minimum_link_hops": "worst_minimum_physical_link_hops",
        "max_crossings": "worst_uncaptured_crossings",
        "endpoint_excess": "extra_crossings_at_endpoints",
        "transit_primitives": "combinational_transit_cells",
        "transfers": "optimization_transfers",
        "timing_cost": "sampled_source_timing_cost",
        "timing_path_count": "sampled_source_timing_path_count",
        "native_imbalance": "native_lut_imbalance_fraction",
        "native_subset_imbalance": "native_lut_imbalance_fraction",
    }
    return {destination: row[source] for source, destination in names.items() if source in row}


def plain_paths(value, workspace: Path):
    if isinstance(value, dict):
        return {key: plain_paths(item, workspace) for key, item in value.items()}
    if isinstance(value, list):
        return [plain_paths(item, workspace) for item in value]
    if isinstance(value, str):
        return value.replace(str(workspace), "workspace").replace("\\", "/")
    return value


def quality_fields(text: str) -> dict[str, str]:
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def csv_number(row, key, converter=int):
    return None if row[key] == "" else converter(row[key])


def csv_boolean(row, key):
    value = row[key]
    if value == "":
        return None
    if value not in ("True", "False"):
        raise ValueError(f"Invalid Boolean {key}: {value}")
    return value == "True"


def build(workspace: Path) -> dict:
    paths = {
        "selected_case_snapshot": HERE / "validation_evidence.json",
        "mapping_contract_snapshot": HERE / "mapping_contract_validation.json",
        "opentitan_final_summary": workspace / "work/opentitan_j4_70pct_20261015/FINAL_EXPERIMENT_SUMMARY.json",
        "opentitan_iteration_csv": workspace / "work/opentitan_j4_70pct_20261015/iteration_progress.csv",
    }
    source = {name: json.loads(path.read_text(encoding="utf-8"))
              for name, path in paths.items() if path.suffix == ".json"}
    snapshot = source["selected_case_snapshot"]
    contract = source["mapping_contract_snapshot"]
    original = {case["case"]: case for case in snapshot["cases"]}
    selected = []
    refinement = []
    for case in contract["cases"]:
        old = original[case["case"]]
        identity = old["identity.json"]
        assert case["native_luts"] == identity["native_resources"]
        assert case["ownership_mismatches"] == 0
        assert case["historical_mapping_sha256"] == identity["mapping_sha256"]
        selected.append({
            "case": case["case"], "design": case["design"],
            "fpga_count": case["fpga_count"],
            "native_clb_luts_by_fpga": case["native_luts"],
            "native_lut_imbalance_fraction": case["native_imbalance"],
            "effective_hard_lut_caps": case["effective_hard_caps"],
            "metrics": renamed_metrics(identity["quality"]),
            "historical_boundary_proof": case["historical_cut_proof"],
            "historical_mapping_sha256": case["historical_mapping_sha256"],
            "corrected_mapping_sha256": case["mapping_sha256"],
            "source_edif_sha256": case["source_edif_sha256"],
            "source_checkpoint_sha256": case["source_checkpoint_sha256"],
            "measurement_scope": case["native_measurement_status"],
            "contract_status": case["contract_status"],
            "functional_leaves_compared": case["functional_leaves_compared"],
            "ownership_mismatches": case["ownership_mismatches"],
        })
        if case["fpga_count"] == 1:
            continue
        quality = quality_fields(old["hardware_quality.txt"])
        phases = {}
        for phase in ("before", "after"):
            phases[phase] = {
                "worst_minimum_physical_link_hops": int(quality[f"{phase}_max_minimum_link_hops"]),
                "worst_uncaptured_crossings": int(quality[f"{phase}_max_register_free_crossings"]),
                "extra_crossings_at_endpoints": int(quality[f"{phase}_endpoint_excess_crossings"]),
                "combinational_transit_cells": int(quality[f"{phase}_transit_primitives"]),
                "optimization_transfers": int(quality[f"{phase}_transfers"]),
                "sampled_source_timing_cost": float(quality[f"timing_cost_{phase}"]),
            }
        assert phases["after"]["worst_uncaptured_crossings"] == identity["quality"]["max_crossings"]
        assert phases["after"]["optimization_transfers"] == identity["quality"]["transfers"]
        assert phases["after"]["extra_crossings_at_endpoints"] == identity["quality"]["endpoint_excess"]
        assert phases["after"]["combinational_transit_cells"] == identity["quality"]["transit_primitives"]
        refinement.append({
            "case": case["case"], "fpga_count": case["fpga_count"], **phases,
            "original_transfer_budget_reference": int(quality["transfer_growth_reference"]),
            "quality_report_sha256": identity["selected_report_sha256"]["hardware_quality.txt"],
            "scope": "Same candidate before/after refinement; before is not independently Native resource/boundary qualified; after is the historical selected result. Not an isolated causal ablation.",
        })

    mesh = snapshot["controlled_comparisons"]["mesh3_feedback_candidates"]
    mesh_rows = []
    for candidate in mesh["candidates"]:
        row = candidate["candidate_record"]
        mesh_rows.append({"iteration_zero_based": row["iteration"],
                          "selected": row["iteration"] == mesh["selected_iteration"],
                          "metrics": renamed_metrics(row),
                          "native_clb_luts_by_fpga": row["native_clb_luts"],
                          "mapping_sha256": row["mapping_sha256"]})

    aes = snapshot["controlled_comparisons"]["aes_recorded_tradeoffs"]
    aes_rows = []
    for label, rows in (("selected_followup", aes["selected_V5_cases"]),
                        ("earlier_outside_envelope", aes["old_main_LIMIT_baselines"]),
                        ("alternate_followup", [aes["separate_V4_k2_alternative"]]),
                        ("coarse_outside_envelope_not_qualified", [aes["separate_coarse2_LIMIT"]])):
        for row in rows:
            aes_rows.append({"case": row["case"], "comparison_role": label,
                             "metrics": renamed_metrics(row["metrics"]),
                             "native_clb_luts_by_fpga": row["native_CLB_LUTs"],
                             "status": row["frontend_status"],
                             "native_30_percent_envelope_met": row["measured_native30_met"],
                             "mapping_sha256": row["accepted_mapping_sha256"],
                             "source_quality_report_sha256": row["selected_quality_report_sha256"],
                             "boundary_proof": row["category_parity"],
                             "effort_iterations_starts_passes": row["requested_budget"]})

    rows = list(csv.DictReader(io.StringIO(paths["opentitan_iteration_csv"].read_text(encoding="utf-8-sig"))))
    iterations = []
    for row in rows:
        iterations.append({
            "profile": row["profile"], "row_kind": row["row_kind"],
            "iteration_zero_based": csv_number(row, "iteration_zero_based"),
            "attempt_one_based": csv_number(row, "attempt_one_based"),
            "selected_final": csv_boolean(row, "selected_final"),
            "worst_minimum_physical_link_hops": csv_number(row, "H_minimum_link_hops"),
            "worst_uncaptured_crossings": csv_number(row, "C_register_free_crossings"),
            "extra_crossings_at_endpoints": csv_number(row, "E_endpoint_excess"),
            "combinational_transit_cells": csv_number(row, "V_transit_primitives"),
            "optimization_transfers": csv_number(row, "T_optimization_transfers"),
            "sampled_source_timing_cost": csv_number(row, "sampled_timing_cost", float),
            "native_lut_imbalance_percent": csv_number(row, "native_lut_imbalance_percent", float),
            "within_search_envelope": csv_boolean(row, "within_native_search_envelope"),
            "hard_native_caps_pass": csv_boolean(row, "hard_native_caps_pass"),
            "native_clb_luts_by_fpga": {label: csv_number(row, f"native_luts_{label}")
                                       for label in ("FPGA_A", "FPGA_B", "FPGA_C", "FPGA_D")},
            "unique_data_control_crossing_nets": csv_number(row, "unique_data_cut_nets"),
            "directed_data_control_destinations": csv_number(row, "directed_data_cut_destinations"),
            "clock_crossing_nets": csv_number(row, "clock_cut_nets"),
            "reset_only_crossing_nets": csv_number(row, "reset_only_cut_nets"),
            "boundary_comparison_scope": row["boundary_category_proof"],
            "cut_report_authority": row["cut_report_authority"],
        })
    final = source["opentitan_final_summary"]
    profiles = []
    profile_keys = ("selected_iteration_zero_based", "completed_attempts", "native_lut_measured_rows",
                    "quality_anchor_rejections", "other_rejections", "backend_solution_files",
                    "starts_per_attempt", "actual_native_boundary_category_comparison_exit_code")
    for name, row in final["profiles"].items():
        profiles.append({"name": name, **{key: row[key] for key in profile_keys},
                         "native_boundary_result": row["native_boundary_result"],
                         "native_category_result": row["native_category_result"]})
    assert len(selected) == 16 and len(iterations) == 24
    assert final["total_completed_attempts"] == 23
    assert final["total_backend_solution_files"] == 276
    for profile in profiles:
        profile_rows = [row for row in iterations if row["profile"] == profile["name"]]
        assert len(profile_rows) == profile["completed_attempts"]
        selected_rows = [row for row in profile_rows if row["selected_final"]]
        assert len(selected_rows) == 1
        assert selected_rows[0]["iteration_zero_based"] == profile["selected_iteration_zero_based"]
    for row in iterations:
        if row["row_kind"] == "QUALITY_ANCHOR_REJECTION":
            assert row["worst_uncaptured_crossings"] is None
            assert all(value is None for value in row["native_clb_luts_by_fpga"].values())
    return plain_paths({
        "schema_version": 1,
        "scope": "Paper/figure presentation extract only; no new partitioning, synthesis, timing, Native or hardware execution. Corrected mapping hashes identify representation repairs with zero ownership changes; Native measurements retain historical scope.",
        "sources": {name: {"path": str(path.relative_to(workspace)), "sha256": digest(path)} for name, path in paths.items()},
        "generator": {"path": "doc/partition/evidence/build_presentation_evidence.py", "sha256": digest(Path(__file__))},
        "metric_definitions": {
            "worst_uncaptured_crossings": "Maximum FPGA-boundary changes before a register capture or modeled terminal; lower is better.",
            "worst_minimum_physical_link_hops": "Maximum sum of declared minimum link counts along a modeled uncaptured path; a routing lower bound, not a chosen route.",
            "extra_crossings_at_endpoints": "Sum over capture/terminal endpoints of max(0, that endpoint's worst incoming crossing count minus one); not a count of all paths.",
            "combinational_transit_cells": "Combinational primitives reachable from a cut ingress and able to reach a cut egress within their FPGA without capture; not a path count.",
            "optimization_transfers": "Optimizer's directed structural net-to-FPGA-destination count; narrower than full Native electrical boundary scope and not physical pins.",
            "native_lut_imbalance_fraction": "Maximum original-source Native CLB LUT subset use divided by mean use minus one, for these equal VP1902 targets.",
            "missing_values": "null means not measured or unavailable, never zero; rejected attempts retain their status.",
        },
        "selected_j4_cases": selected,
        "local_refinement_comparisons": refinement,
        "mesh_three_fpga_feedback": {"source_status_sha256": mesh["source_status_sha256"], "scope": mesh["scope"], "candidates": mesh_rows},
        "aes_alternatives": {"scope": aes["scope"], "candidates": aes_rows},
        "opentitan_iterative_search": {"total_attempts": 23, "total_starting_solutions": 276,
                                       "scope": final["scope"], "profiles": profiles, "rows": iterations},
    }, workspace)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=REPOSITORY.parent)
    parser.add_argument("--output", type=Path, default=HERE / "presentation_evidence.json")
    arguments = parser.parse_args()
    output = build(arguments.workspace.resolve())
    arguments.output.write_text(json.dumps(output, indent=2, ensure_ascii=True, allow_nan=False) + "\n", encoding="utf-8")
    print(f"Wrote {arguments.output}: 16 selected cases; 23 OpenTitan attempts / 276 starting solutions")


if __name__ == "__main__":
    main()
