#!/usr/bin/env python3
"""Draw the paper's OpenTitan figures from versioned evidence only.

No solver, Vivado, source netlist or workstation experiment directory is read.
Install scripts/partition/requirements.txt first. This script uses the same
bundled font and exact three-color PNG conversion as ordinary run reports.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[1]
sys.path.insert(0, str(REPOSITORY / "scripts/partition"))
import reporting  # noqa: E402
from reporting import PALETTE, _balance_chart, _connectivity_chart, _plot_imports, _save_palette  # noqa: E402


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def render_selected_example(figures: Path, font_path: Path, evidence: dict) -> dict:
    """Regenerate the three paper examples using the ordinary report renderer.

    The input is a versioned presentation extract, not a fresh Vivado report.
    Cross-check its repaired mapping and historical LUT ownership before drawing.
    """
    source = HERE / "examples/j4/opentitan_earl_grey/k4/report_data.json"
    source_digest = sha256(source)
    data = json.loads(source.read_text(encoding="utf-8"))
    expected = next(case for case in evidence["selected_j4_cases"]
                    if case["case"] == "OPENTITAN_EARL_GREY_k4")
    if data["status"] != "HISTORICAL_METRICS_PRESERVED_BY_EXACT_OWNERSHIP_EQUIVALENCE":
        raise ValueError("The paper examples must preserve their historical measurement scope")
    if data["mapping_sha256"] != expected["corrected_mapping_sha256"]:
        raise ValueError("Example mapping identity differs from the versioned ownership evidence")
    labels = data["fpga_labels"]
    if labels != ["FPGA_A", "FPGA_B", "FPGA_C", "FPGA_D"]:
        raise ValueError("The paper example requires the four selected logical J4 owners")
    resources = {label: data["resources"][label]["clb_luts"] for label in labels}
    if resources != expected["native_clb_luts_by_fpga"]:
        raise ValueError("Example LUT counts differ from historical Native source subsets")
    if data["font"]["sha256"] != sha256(font_path):
        raise ValueError("The example font differs from the bundled paper font")
    category = "data_or_functional_sync_control"
    io_values = [data["io_cuts"]["by_category"][category][label]["total"] for label in labels]
    matrix = data["io_cuts"]["directed_matrices"][category]
    for value in list(resources.values()) + io_values + [matrix[a][b] for a in labels for b in labels]:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError("An example count must be a nonnegative integer")
    outputs = {
        "lut_balance": figures / "opentitan_four_fpga_lut_balance.png",
        "io_cut_balance": figures / "opentitan_four_fpga_io_cut_balance.png",
        "connectivity": figures / "opentitan_four_fpga_connectivity.png",
    }
    _balance_chart(outputs["lut_balance"], labels, [resources[label] for label in labels],
                   "LUT resources for the selected mapping", "Native Vivado CLB LUTs", font_path)
    _balance_chart(outputs["io_cut_balance"], labels, io_values,
                   "I/O cut load for the selected mapping", "Incoming + outgoing net bits", font_path)
    _connectivity_chart(outputs["connectivity"], labels, matrix, font_path)
    if sha256(source) != source_digest:
        raise ValueError("Versioned example data changed during rendering")
    return {
        "source_path": str(source.relative_to(REPOSITORY)).replace("\\", "/"),
        "source_sha256": source_digest, "mapping_sha256": data["mapping_sha256"],
        "measurement_status": data["status"],
        "scope": "Historical Native/source-model measurements preserved by exact ownership equivalence; no new Native measurement.",
        "outputs_sha256": {path.name: sha256(path) for path in outputs.values()},
    }


def main() -> None:
    evidence_path = HERE / "evidence/presentation_evidence.json"
    font_path = REPOSITORY / "scripts/partition/assets/fonts/TitilliumWeb-Regular.ttf"
    helper_path = Path(reporting.__file__).resolve()
    helper_digest = sha256(helper_path)
    data = json.loads(evidence_path.read_text(encoding="utf-8"))
    search = data["opentitan_iterative_search"]
    rows = sorted((row for row in search["rows"] if row["profile"] == "main_auto1044_cut10"),
                  key=lambda row: row["attempt_one_based"])
    if [row["attempt_one_based"] for row in rows] != list(range(1, 13)):
        raise ValueError("The paper figure requires the recorded twelve-attempt profile")
    measured = [row for row in rows if row["row_kind"] == "NATIVE_LUT_VALIDATED_WITHIN_ENVELOPE"]
    if [row["attempt_one_based"] for row in measured] != [1, 6] or not measured[-1]["selected_final"]:
        raise ValueError("Unexpected accepted-candidate history; do not invent an incumbent ranking")
    rejected = [row["attempt_one_based"] for row in rows if row["row_kind"] == "QUALITY_ANCHOR_REJECTION"]
    if len(rejected) != 10:
        raise ValueError("The paper figure requires ten explicitly rejected attempts")

    plt, font, Image = _plot_imports(font_path)
    from matplotlib.ticker import StrMethodFormatter
    from matplotlib.text import Text
    fig, axes = plt.subplot_mosaic(
        [["worst", "endpoint"], ["transit", "transfers"], ["attempts", "attempts"]],
        figsize=(14, 12.5), gridspec_kw={"height_ratios": (1, 1, 0.25)},
    )
    fig.subplots_adjust(left=0.08, right=0.97, top=0.82, bottom=0.18,
                        hspace=0.65, wspace=0.30)
    fig.suptitle("OpenTitan on four FPGAs: twelve search attempts", x=0.08, y=0.97,
                 ha="left", fontsize=23, fontproperties=font, color=PALETTE[0])
    fig.text(0.08, 0.915,
             "The worst uncaptured path improves at attempt 6; later attempts keep that candidate.\n"
             "Each panel shows a separate measurement. No combined quality score is invented.",
             ha="left", va="top", fontsize=15, fontproperties=font, color=PALETTE[0])

    metrics = (
        ("worst", "worst_uncaptured_crossings", "Worst uncaptured path", "FPGA-boundary crossings"),
        ("endpoint", "extra_crossings_at_endpoints", "Extra crossings at endpoints", "Sum of endpoint excess crossings"),
        ("transit", "combinational_transit_cells", "Combinational transit logic", "Primitive cells without a capture"),
        ("transfers", "optimization_transfers", "Optimization communication", "Directed structural transfers"),
    )
    plotted = {}
    for name, key, title, ylabel in metrics:
        ax = axes[name]
        first, selected = measured[0][key], measured[1][key]
        if first is None or selected is None:
            raise ValueError("A missing metric is not a zero-valued measurement")
        retained = [first if attempt < 6 else selected for attempt in range(1, 13)]
        ax.step(range(1, 13), retained, where="post", color=PALETTE[0], linewidth=2.8)
        ax.scatter([1, 6], [first, selected], color=PALETTE[0], s=65, zorder=5)
        ax.set_title(title, loc="left", fontsize=17, fontproperties=font, color=PALETTE[0], pad=12)
        ax.set_ylabel(ylabel, fontsize=13, fontproperties=font, color=PALETTE[0])
        ax.set_xlabel("Feedback attempt", fontsize=13, fontproperties=font, color=PALETTE[0])
        ax.set_xticks(range(1, 13))
        ax.set_xlim(0.7, 12.3)
        ax.yaxis.set_major_formatter(StrMethodFormatter("{x:,.0f}"))
        low, high = min(first, selected), max(first, selected)
        padding = max((high - low) * 0.35, 0.3)
        ax.set_ylim(low - padding, high + padding)
        if key == "worst_uncaptured_crossings":
            ax.set_yticks([4, 5])
        ax.annotate(f"{first:,}", (1, first), xytext=(9, 12), textcoords="offset points",
                    fontsize=14, fontproperties=font, color=PALETTE[0])
        ax.annotate(f"{selected:,}", (6, selected), xytext=(9, 12), textcoords="offset points",
                    fontsize=14, fontproperties=font, color=PALETTE[0])
        plotted[key] = retained

    status = axes["attempts"]
    status.scatter(rejected, [0] * len(rejected), color=PALETTE[1], marker="x", s=75, linewidths=2.5)
    status.scatter([1, 6], [0, 0], color=PALETTE[0], marker="o", s=65)
    status.set_xticks(range(1, 13))
    status.set_xlim(0.7, 12.3)
    status.set_ylim(-0.5, 0.5)
    status.set_yticks([])
    status.set_xlabel("Attempt status: circle = accepted candidate; cross = rejected, incumbent retained",
                      fontsize=14, fontproperties=font, color=PALETTE[0], labelpad=9)
    for ax in axes.values():
        ax.set_facecolor(PALETTE[2])
        ax.tick_params(colors=PALETTE[0], labelsize=12)
        for spine in ax.spines.values():
            spine.set_color(PALETTE[1])
        ax.grid(axis="y", color=PALETTE[1], linewidth=0.7)
    for item in fig.findobj(Text):
        size = item.get_fontsize()
        item.set_fontproperties(font)
        item.set_fontsize(size)
        item.set_color(PALETTE[0])
    fig.text(0.08, 0.035,
             "144 starts in this profile (12 per attempt). LUT resources were measured for the two accepted candidates.\n"
             "Only the final selected map has this profile's independent full-source boundary/category comparison.\n"
             "These are source-model measurements; they do not establish physical pinmux ratios or DUT frequency.",
             fontsize=12, fontproperties=font, color=PALETTE[0], va="bottom")

    figures = HERE / "figures"
    figures.mkdir(exist_ok=True)
    output = figures / "opentitan_iteration_progress.png"
    _save_palette(fig, output, plt, Image)
    with Image.open(output) as image:
        rgb = image.convert("RGB")
        actual_colors = {color for count, color in rgb.getcolors(maxcolors=256)}
        rgb.close()
    allowed_colors = {tuple(int(color[i:i + 2], 16) for i in (1, 3, 5)) for color in PALETTE}
    if not actual_colors.issubset(allowed_colors):
        raise ValueError("Paper PNG contains a color outside the requested palette")
    selected_example = render_selected_example(figures, font_path, data)
    for name in selected_example["outputs_sha256"]:
        with Image.open(figures / name) as image:
            rgb = image.convert("RGB")
            colors = rgb.getcolors(maxcolors=256)
            rgb.close()
        if colors is None or not {color for count, color in colors}.issubset(allowed_colors):
            raise ValueError("A paper example PNG contains an unrequested color")
    if sha256(helper_path) != helper_digest:
        raise ValueError("The report renderer changed during paper figure generation")
    manifest = {
        "scope": "Presentation of existing versioned evidence only; no new measurements.",
        "source_evidence_sha256": sha256(evidence_path),
        "generator_sha256": sha256(Path(__file__)),
        "font_path": str(font_path.relative_to(REPOSITORY)).replace("\\", "/"),
        "font_sha256": sha256(font_path), "png_palette": list(PALETTE),
        "report_renderer_helper_sha256": helper_digest,
        "output": output.name, "output_sha256": sha256(output),
        "profile": "main_auto1044_cut10", "attempt_count": 12,
        "accepted_candidate_attempts": [1, 6], "rejected_attempts": rejected,
        "selected_attempt": 6, "plotted_retained_measurements": plotted,
        "selected_opentitan_four_fpga_examples": selected_example,
    }
    (figures / "report_figures_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {output}; exact palette verified; two accepted candidates and ten rejections")


if __name__ == "__main__":
    main()
