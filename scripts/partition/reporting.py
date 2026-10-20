"""Readable, source-bound reports for the selected accepted FPGA assignment.

The PNG palette is deliberately reduced after rasterization.  Plot antialiasing
must not silently introduce additional colors into the user's three-color art.
This module does not partition a design or modify its mapping.
"""
import csv
import hashlib
import html
import json
import math
from pathlib import Path
import re


PALETTE = ("#F26522", "#C4470C", "#000000")
REPORT_FILES = ("lut_balance.png", "io_cut_balance.png", "fpga_connectivity.png",
                "report.html", "report_data.json", "report_manifest.json")
HISTORICAL_STATUS = "HISTORICAL_METRICS_PRESERVED_BY_EXACT_OWNERSHIP_EQUIVALENCE"
_LABEL = re.compile(r"FPGA_[A-Za-z0-9_]+\Z")
_INTEGER = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_CATEGORIES = ("data_or_functional_sync_control", "clock", "reset_only")


def _sha(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def _integer(value, description):
    if not isinstance(value, str) or not _INTEGER.fullmatch(value):
        raise ValueError("Expected a nonnegative integer for " + description)
    return int(value)


def _number(value, description):
    if isinstance(value, bool):
        raise ValueError("Expected a finite number for " + description)
    try:
        result = float(value)
    except (ValueError, TypeError, OverflowError) as error:
        raise ValueError("Expected a finite number for " + description) from error
    if not math.isfinite(result):
        raise ValueError("Expected a finite number for " + description)
    return result


def deviations(values):
    """Signed deviation from the arithmetic mean; an all-zero design is 0%."""
    mean = sum(values) / len(values) if values else 0.0
    return mean, [100 * (value / mean - 1) if mean else 0.0 for value in values]


def _tsv(path, required):
    with Path(path).open(encoding="utf-8-sig", newline="") as handle:
        rows = csv.DictReader((line for line in handle if not line.startswith("#")), delimiter="\t")
        if rows.fieldnames is None or not set(required).issubset(rows.fieldnames):
            raise ValueError("Missing required TSV columns in " + Path(path).name)
        if len(set(rows.fieldnames)) != len(rows.fieldnames):
            raise ValueError("Duplicate TSV columns in " + Path(path).name)
        for row in rows:
            if None in row or any(value is None for value in row.values()):
                raise ValueError("Malformed TSV record in " + Path(path).name)
            yield row


def _mapping_labels(path):
    labels, paths = set(), set()
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        tokens = line.split()
        if len(tokens) != 2 or not _LABEL.fullmatch(tokens[1]):
            raise ValueError("Malformed mapping record in report input")
        if tokens[0] in paths:
            raise ValueError("Duplicate mapping source path in report input")
        paths.add(tokens[0])
        labels.add(tokens[1])
    if not labels:
        raise ValueError("The report requires a nonempty accepted mapping")
    return labels


def _native_resources(path, labels):
    result = {}
    for row in _tsv(path, ("partition", "clb_luts")):
        label = row["partition"]
        if label == "DESIGN":
            continue
        if label not in labels or label in result:
            raise ValueError("Native resource labels differ from the accepted mapping")
        parsed = {"clb_luts": _integer(row["clb_luts"], label + " CLB LUTs")}
        for key in ("logical_lut_primitives", "lut_as_logic", "lut_as_memory", "registers",
                    "flip_flops", "latches", "dsps", "uram", "primitive_leaves"):
            if key in row:
                # Vivado reports unsupported resource families as literal NA
                # (for example UltraRAM on 7-series). Preserve unknown values
                # as null, never silently count them as zero.
                parsed[key] = None if row[key] == "NA" else _integer(row[key], label + " " + key)
        if "block_ram_tiles" in row:
            parsed["block_ram_tiles"] = None if row["block_ram_tiles"] == "NA" else _number(row["block_ram_tiles"], label + " block RAM tiles")
            if parsed["block_ram_tiles"] is not None and parsed["block_ram_tiles"] < 0:
                raise ValueError("Negative native block RAM count")
        result[label] = parsed
    if set(result) != labels:
        raise ValueError("Missing native resource row for an accepted FPGA")
    return result


def _check_lut_report(path, resources):
    if not path.is_file():
        return
    counts = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        match = re.fullmatch(r"([0-9]+)LUTS (FPGA_[A-Za-z0-9_]+|mean)", line)
        if match is None or match[2] in counts:
            raise ValueError("Malformed or duplicate native LUT summary record")
        counts[match[2]] = _integer(match[1], "native LUT summary")
    if set(counts) != set(resources) | {"mean"}:
        raise ValueError("Native LUT summary labels differ from resource table")
    if any(counts[label] != row["clb_luts"] for label, row in resources.items()):
        raise ValueError("Native LUT summary differs from resource table")
    if counts["mean"] != math.ceil(sum(row["clb_luts"] for row in resources.values()) / len(resources)):
        raise ValueError("Native LUT summary mean differs from resource table")


def read_cut_categories(path, labels):
    """Count net bits, deduplicating one multicast transmitter on its source.

    A canonical net from A to B and C contributes A=1, B=1, C=1 to the
    per-FPGA I/O total, and one bit to each directed A->B and A->C matrix cell.
    Clock/reset-only nets remain separately visible, outside the data chart.
    """
    labels = list(labels)
    totals = {category: {label: {"incoming": 0, "outgoing": 0, "total": 0}
                         for label in labels} for category in _CATEGORIES}
    matrices = {category: {source: {sink: 0 for sink in labels} for source in labels}
                for category in _CATEGORIES}
    unique = {category: 0 for category in _CATEGORIES}
    directed = {category: 0 for category in _CATEGORIES}
    seen = set()
    for row in _tsv(path, ("CANONICAL_NET", "CATEGORY", "DIRECTED_PARTITION_PAIRS")):
        net, category = row["CANONICAL_NET"], row["CATEGORY"]
        if not net or net in seen:
            raise ValueError("Empty or duplicate canonical net in cut categories")
        seen.add(net)
        if category not in _CATEGORIES:
            raise ValueError("Unknown cut category: " + category)
        pairs, sources, sinks = set(), set(), set()
        for pair in row["DIRECTED_PARTITION_PAIRS"].split(","):
            tokens = pair.split("->")
            if len(tokens) != 2 or any(label not in labels for label in tokens) or tokens[0] == tokens[1]:
                raise ValueError("Invalid directed FPGA pair in cut categories: " + pair)
            source, sink = tokens
            if pair in pairs:
                raise ValueError("Duplicate directed pair for one canonical net")
            pairs.add(pair)
            sources.add(source)
            sinks.add(sink)
            matrices[category][source][sink] += 1
        if not pairs:
            raise ValueError("Cut category row has no directed pairs")
        unique[category] += 1
        directed[category] += len(pairs)
        for label in sources:
            totals[category][label]["outgoing"] += 1
        for label in sinks:
            totals[category][label]["incoming"] += 1
    for category in _CATEGORIES:
        for values in totals[category].values():
            values["total"] = values["incoming"] + values["outgoing"]
    return {"by_category": totals, "directed_matrices": matrices,
            "unique_crossing_nets": unique, "directed_net_destinations": directed,
            "source": Path(path).name,
            "authority": "Source-netlist logical canonical net bits, not placed package pins or achieved pinmux ratio",
            "per_fpga_definition": "Unique outgoing data/control net bits plus unique incoming data/control net bits; one multicast source counted once",
            "matrix_definition": "One canonical data/control net bit per directed source/destination FPGA pair",
            "excluded_from_data_charts": ["clock", "reset_only"]}


def _quality(path):
    result = {}
    if not path.is_file():
        return result
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        if "=" not in line:
            raise ValueError("Malformed hardware quality record")
        key, value = line.split("=", 1)
        if key in result:
            raise ValueError("Duplicate hardware quality key: " + key)
        result[key] = value
    integer_keys = ("after_max_register_free_crossings", "after_endpoint_excess_crossings",
                    "after_transit_primitives", "after_transfers", "after_max_minimum_link_hops",
                    "before_max_register_free_crossings", "before_endpoint_excess_crossings",
                    "before_transit_primitives", "before_transfers", "before_max_minimum_link_hops",
                    "timing_paths_count", "timing_paths_scored", "timing_paths_unscored")
    number_keys = ("timing_cost_after", "timing_cost_before", "timing_source_worst_slack_ns", "modeled_worst_slack_after_ns",
                   "after_topology_distance")
    for key in integer_keys:
        if key in result:
            result[key] = _integer(result[key], key)
    for key in number_keys:
        if key in result:
            result[key] = _number(result[key], key)
    return result


def _capacities(state, metrics, labels):
    target = state.get("target_capacity")
    path = metrics / "target_capacity.json"
    if target is None and path.is_file():
        target = json.loads(path.read_text(encoding="utf-8-sig"))
    result = {label: {"device": None, "physical_lut_capacity": None, "hard_lut_cap": None}
              for label in labels}
    if target is not None:
        for device in target.get("devices", []):
            label = device.get("partition")
            if label not in labels:
                raise ValueError("Target capacity contains a foreign FPGA label")
            result[label]["device"] = device.get("device", device.get("part"))
            for source, destination in (("physical_lut_capacity", "physical_lut_capacity"),
                                        ("effective_hard_cap", "hard_lut_cap")):
                if device.get(source) is not None:
                    result[label][destination] = _integer(str(device[source]), source)
    for label, cap in state.get("hard_native_clb_lut_caps", {}).items():
        if label not in labels:
            raise ValueError("Hard LUT cap contains a foreign FPGA label")
        parsed = _integer(str(cap), "hard LUT cap")
        old = result[label]["hard_lut_cap"]
        result[label]["hard_lut_cap"] = min(old, parsed) if old is not None else parsed
    caps_file = metrics / "native_lut_caps.tsv"
    if caps_file.is_file():
        seen = set()
        for row in _tsv(caps_file, ("partition", "clb_luts")):
            label = row["partition"]
            if label not in labels or label in seen:
                raise ValueError("Native LUT caps contain duplicate or foreign labels")
            seen.add(label)
            cap = _integer(row["clb_luts"], "native LUT cap")
            old = result[label]["hard_lut_cap"]
            result[label]["hard_lut_cap"] = min(old, cap) if old is not None else cap
    return result


def _verify_identity(state, metrics, mapping, digest):
    if state.get("accepted_mapping_sha256") not in (None, digest):
        raise ValueError("Report mapping differs from the accepted mapping hash")
    provenance_path = metrics / "run_provenance.json"
    provenance = json.loads(provenance_path.read_text(encoding="utf-8-sig")) if provenance_path.is_file() else {}
    if provenance.get("mapping_sha256") not in (None, digest):
        raise ValueError("Report mapping differs from selected run provenance")
    contract = state.get("mapping_contract", {})
    if isinstance(contract, dict) and contract.get("mapping_sha256") not in (None, digest):
        raise ValueError("Report mapping differs from its mapping contract audit")
    source_mapping = metrics / "source_mapping.txt"
    if source_mapping.is_file() and _sha(source_mapping) != digest:
        raise ValueError("Report mapping differs from the native resource mapping snapshot")
    for name, expected in state.get("selected_report_sha256", {}).items():
        if name in ("hardware_quality.txt", "fpga_control_categories.tsv", "run_provenance.json") and expected is not None:
            if not (metrics / name).is_file() or _sha(metrics / name) != expected:
                raise ValueError("Selected report hash changed: " + name)
    selected = state.get("selected_iteration")
    matches = [row for row in state.get("candidates", []) if row.get("iteration") == selected]
    if len(matches) > 1:
        raise ValueError("Multiple selected iterations in report state")
    if matches:
        row = matches[0]
        if row.get("mapping_sha256") not in (None, digest):
            raise ValueError("Selected iteration mapping hash differs from report")
        for name, expected in row.get("native_regenerated_report_sha256", {}).items():
            if name in ("vivado_resources.tsv", "source_mapping.txt") and expected is not None:
                if not (metrics / name).is_file() or _sha(metrics / name) != expected:
                    raise ValueError("Selected native report hash changed: " + name)
    return provenance


def _historical_evidence(directory, metrics, mapping, digest):
    """Verify an explicit, source-preserving re-encoding proof, never fake a
    fresh native certificate for the successor mapping's different bytes.
    """
    directory, metrics = Path(directory), Path(metrics)
    names = ("prior_native_evidence.json", "reencoding_equivalence.json", "mapping_contract.json")
    pinned = {name: _sha(directory / name) for name in names}
    prior, equivalent, contract = [json.loads((directory / name).read_text(encoding="utf-8-sig")) for name in names]
    old_digest = prior.get("historical_mapping_sha256")
    if (prior.get("status") != "HISTORICAL_EVIDENCE_REFERENCED_BY_OWNERSHIP_EQUIVALENCE"
            or prior.get("successor_mapping_sha256") != digest
            or equivalent.get("status") != "PASS" or contract.get("status") != "PASS"
            or prior.get("equivalence_sha256") != pinned["reencoding_equivalence.json"]
            or equivalent.get("mapping_sha256") != digest or contract.get("mapping_sha256") != digest
            or equivalent.get("legacy_mapping_sha256") != old_digest
            or type(equivalent.get("ownership_mismatches")) is not int or equivalent["ownership_mismatches"] != 0
            or type(equivalent.get("functional_leaves_compared")) is not int or equivalent["functional_leaves_compared"] < 1
            or equivalent.get("functional_leaves_compared") != contract.get("functional_leaf_count")
            or equivalent.get("top_port_owner_unchanged") is not True):
        raise ValueError("Historical metric reuse requires exact source ownership and root-owner equivalence")
    for key in ("descendants_before_ancestors", "residual_root_explicit", "cells_agreement", "source_paths_exact",
                "nearest_ancestor_redundancy_absent", "frontier_ownership_compared"):
        if contract.get(key) is not True:
            raise ValueError("Historical successor mapping contract check failed: " + key)
    for name, key in (("mapping_records.tsv", "records_manifest_sha256"),
                      ("mapping_ownership.tsv.gz", "ownership_manifest_sha256")):
        pinned[name] = _sha(directory / name)
        if pinned[name] != contract.get(key):
            raise ValueError("Historical successor manifest changed: " + name)
    historical_hashes = prior.get("historical_artifact_sha256", {})
    required = {"mapping.txt", "source_mapping.txt", "vivado_resources.tsv", "fpga_control_categories.tsv",
                "STATUS.json", "run_provenance.json", "hardware_quality.txt", "final_assignment.part"}
    if not required.issubset(historical_hashes):
        raise ValueError("Historical evidence omits required source measurements")
    for name, expected in historical_hashes.items():
        path = (metrics / name).resolve()
        if not path.is_relative_to(metrics.resolve()) or not path.is_file() or _sha(path) != expected:
            raise ValueError("Historical artifact changed or escaped its package: " + name)
    if historical_hashes["mapping.txt"] != old_digest or historical_hashes["source_mapping.txt"] != old_digest:
        raise ValueError("Historical source/native mapping hashes disagree")
    original_state = json.loads((metrics / "STATUS.json").read_text(encoding="utf-8-sig"))
    provenance = _verify_identity(original_state, metrics, metrics / "mapping.txt", old_digest)
    input_digest = prior.get("source_edif_sha256")
    if (not input_digest or equivalent.get("input_sha256") != input_digest
            or contract.get("input_sha256") != input_digest or provenance.get("input_sha256") != input_digest
            or original_state.get("checkpoint_sha256") != prior.get("source_checkpoint_sha256")
            or equivalent.get("final_assignment_sha256") != historical_hashes["final_assignment.part"]):
        raise ValueError("Historical equivalence source and assignment identities disagree")
    resources = _native_resources(metrics / "vivado_resources.tsv", _mapping_labels(mapping))
    if {label: row["clb_luts"] for label, row in resources.items()} != prior.get("native_luts"):
        raise ValueError("Historical native LUT values differ from preserved evidence")
    return {"status": HISTORICAL_STATUS, "fresh_native_measurement": False,
            "historical_mapping_sha256": old_digest, "successor_mapping_sha256": digest,
            "functional_leaves_compared": equivalent["functional_leaves_compared"],
            "ownership_mismatches": 0, "top_port_owner_unchanged": True,
            "source_edif_sha256": input_digest, "source_checkpoint_sha256": prior["source_checkpoint_sha256"],
            "proof_files_sha256": pinned, "historical_artifact_sha256": historical_hashes,
            "historical_metrics_directory": str(metrics.resolve()),
            "scope": "Historical source-netlist resource and connectivity measurements preserved by checked exact functional ownership and top-port owner equivalence; no new Vivado, regroup, wrapper or hardware validation"}, original_state, provenance


def _plot_imports(font_path):
    try:
        import matplotlib
        matplotlib.use("Agg", force=True)
        from matplotlib import pyplot as plt
        from matplotlib.font_manager import FontProperties
        from PIL import Image
    except ImportError as error:
        raise RuntimeError("Partition reports need Matplotlib and Pillow; install the partitioner Python rendering requirements before starting a solve") from error
    font_path = Path(font_path)
    if not font_path.is_file():
        raise ValueError("The bundled report font is missing: " + str(font_path))
    plt.rcParams.update({"text.usetex": False, "axes.unicode_minus": False,
                         "savefig.facecolor": PALETTE[2], "figure.facecolor": PALETTE[2]})
    return plt, FontProperties(fname=str(font_path)), Image


def preflight(font_path):
    """Fail before an expensive solve when the portable renderer is unusable."""
    plt, font, Image = _plot_imports(font_path)
    import matplotlib
    from matplotlib.ft2font import FT2Font
    try:
        face = FT2Font(str(font_path))
    except (RuntimeError, OSError) as error:
        raise ValueError("The bundled report font cannot be loaded: " + str(font_path)) from error
    return {"matplotlib_version": matplotlib.__version__, "pillow_version": Image.__version__,
            "font_filename": Path(font_path).name, "font_sha256": _sha(font_path),
            "font_family": face.family_name}


def _save_palette(fig, path, plt, Image):
    temporary = path.with_suffix(".render.png")
    try:
        fig.savefig(temporary, dpi=150, facecolor=PALETTE[2], edgecolor=PALETTE[2])
        with Image.open(temporary) as source:
            palette = Image.new("P", (1, 1))
            colors = [int(value[i:i + 2], 16) for value in PALETTE for i in (1, 3, 5)]
            palette.putpalette(colors + [0] * (768 - len(colors)))
            converted = source.convert("RGB").quantize(palette=palette, dither=Image.Dither.NONE)
            converted.save(path, optimize=True)
            palette.close()
            converted.close()
    finally:
        plt.close(fig)
        temporary.unlink(missing_ok=True)


def _text(ax, font, title, ylabel=None):
    ax.set_facecolor(PALETTE[2])
    ax.set_title(title, color=PALETTE[0], fontproperties=font, fontsize=16, pad=15)
    if ylabel:
        ax.set_ylabel(ylabel, color=PALETTE[0], fontproperties=font, fontsize=12)
    ax.tick_params(colors=PALETTE[0], length=3)
    for spine in ax.spines.values():
        spine.set_color(PALETTE[1])
    for text in ax.get_xticklabels() + ax.get_yticklabels():
        text.set_fontproperties(font)
        text.set_fontsize(10)
    ax.yaxis.offsetText.set_fontproperties(font)
    ax.yaxis.offsetText.set_color(PALETTE[0])


def _balance_chart(path, labels, values, title, ylabel, font_path):
    plt, font, Image = _plot_imports(font_path)
    mean, deviation = deviations(values)
    fig, axes = plt.subplots(2, 1, figsize=(14, 9), gridspec_kw={"hspace": 0.5})
    fig.subplots_adjust(left=0.09, right=0.98, bottom=0.14, top=0.91)
    fig.suptitle(title, color=PALETTE[0], fontproperties=font, fontsize=21)
    for ax, series, heading, axis_title in zip(axes, (values, deviation),
            ("Count on each FPGA", "Signed difference from the mean"), (ylabel, "Deviation from mean (%)")):
        ax.bar(range(len(labels)), series, width=0.67,
               color=[PALETTE[0] if value >= 0 else PALETTE[1] for value in series])
        ax.set_xticks(range(len(labels)), labels, rotation=45 if len(labels) > 12 else 0,
                      ha="right" if len(labels) > 12 else "center", fontproperties=font)
        ax.ticklabel_format(axis="y", style="plain", useOffset=False)
        _text(ax, font, heading, axis_title)
        if ax is axes[0]:
            ax.axhline(mean, color=PALETTE[1], linewidth=2.2, linestyle="--")
            ax.text(0.99, 0.94, "Mean: " + format(mean, ",.2f"), transform=ax.transAxes,
                    ha="right", va="top", fontproperties=font, color=PALETTE[0], fontsize=12,
                    bbox={"facecolor": PALETTE[2], "edgecolor": PALETTE[1], "pad": 5})
            ax.set_ylim(0, max(max(series), mean, 1) * 1.23)
        else:
            ax.axhline(0, color=PALETTE[1], linewidth=1.5)
            extent = max(max((abs(value) for value in series), default=0) * 1.25, 5)
            ax.set_ylim(-extent, extent)
        for index, value in enumerate(series):
            label = format(value, ",") if ax is axes[0] else format(value, "+.1f") + "%"
            ax.annotate(label, (index, value), xytext=(0, 6 if value >= 0 else -6),
                        textcoords="offset points", ha="center", va="bottom" if value >= 0 else "top",
                        color=PALETTE[0], fontproperties=font, fontsize=10 if len(labels) <= 12 else 8)
    _save_palette(fig, path, plt, Image)


def _connectivity_chart(path, labels, matrix, font_path):
    plt, font, Image = _plot_imports(font_path)
    from matplotlib.colors import BoundaryNorm, ListedColormap
    from matplotlib.patches import FancyArrowPatch, Circle
    count = len(labels)
    if count <= 8:
        fig, (graph, ax) = plt.subplots(1, 2, figsize=(14, 7), gridspec_kw={"width_ratios": [1, 1.15]})
        positions = {label: (math.cos(math.pi / 2 + 2 * math.pi * index / count),
                             math.sin(math.pi / 2 + 2 * math.pi * index / count)) for index, label in enumerate(labels)}
        maximum = max((matrix[a][b] for a in labels for b in labels), default=1) or 1
        for source in labels:
            for sink in labels:
                value = matrix[source][sink]
                if value:
                    graph.add_patch(FancyArrowPatch(positions[source], positions[sink],
                        connectionstyle="arc3,rad=0.16", shrinkA=29, shrinkB=29,
                        arrowstyle="-|>", mutation_scale=12, color=PALETTE[1],
                        linewidth=0.8 + 3 * value / maximum))
        for label, (x, y) in positions.items():
            graph.add_patch(Circle((x, y), 0.18, facecolor=PALETTE[2], edgecolor=PALETTE[0], linewidth=2))
            graph.text(x, y, label, color=PALETTE[0], ha="center", va="center", fontproperties=font, fontsize=11)
        graph.set(xlim=(-1.4, 1.4), ylim=(-1.4, 1.4), aspect="equal")
        graph.axis("off")
        graph.set_title("Direction of communication\nArrow width scales with net bits; exact counts at right",
                        color=PALETTE[0], fontproperties=font, fontsize=12)
    else:
        fig, ax = plt.subplots(figsize=(14, 13))
    values = [[matrix[source][sink] for sink in labels] for source in labels]
    maximum = max((max(row) for row in values), default=0)
    boundaries = [-0.5, 0.5, max(maximum / 2, 1) + 0.5, max(maximum, 2) + 1]
    ax.imshow(values, cmap=ListedColormap([PALETTE[2], PALETTE[1], PALETTE[0]]),
              norm=BoundaryNorm(boundaries, 3), interpolation="nearest")
    ax.set_xticks(range(count), labels, rotation=90, fontproperties=font)
    ax.set_yticks(range(count), labels, fontproperties=font)
    ax.set_xlabel("Destination FPGA (receives)", color=PALETTE[0], fontproperties=font, fontsize=12)
    _text(ax, font, "Directed data/control net bits", "Source FPGA (transmits)")
    fontsize = 11 if count <= 8 else 8 if count <= 28 else 6
    for i, row in enumerate(values):
        for j, value in enumerate(row):
            ax.text(j, i, format(value, ","), ha="center", va="center", fontproperties=font,
                    fontsize=fontsize, color=PALETTE[2] if value else PALETTE[0])
    fig.suptitle("FPGA communication for the selected mapping", fontproperties=font,
                 color=PALETTE[0], fontsize=20, y=0.98)
    fig.subplots_adjust(bottom=0.16, left=0.12 if count > 8 else 0.02, right=0.98, top=0.88, wspace=0.25)
    _save_palette(fig, path, plt, Image)


def _html(data):
    escape = lambda value: html.escape(str(value), quote=True)
    fmt = lambda value: "Unavailable" if value is None else format(value, ",") if isinstance(value, int) else escape(value)
    def table(headers, rows):
        return "<div class=table-scroll><table><thead><tr>" + "".join("<th>" + escape(h) + "</th>" for h in headers) + "</tr></thead><tbody>" + "".join("<tr>" + "".join("<td>" + escape(c) + "</td>" for c in row) + "</tr>" for row in rows) + "</tbody></table></div>"
    resources, cuts, quality = data["resources"], data["io_cuts"], data["quality"]
    labels, selected = data["fpga_labels"], data["selected_iteration"]
    cells = cuts["by_category"]["data_or_functional_sync_control"]
    resource_rows = []
    other_resource_rows = []
    for label in labels:
        r, cap, c = resources[label], data["capacities"][label], cells[label]
        utilization = "Unavailable" if not cap["physical_lut_capacity"] else format(100 * r["clb_luts"] / cap["physical_lut_capacity"], ".3f") + "%"
        resource_rows.append((label, fmt(r["clb_luts"]), utilization, fmt(cap["hard_lut_cap"]),
                              fmt(c["outgoing"]), fmt(c["incoming"]), fmt(c["total"])))
        other_resource_rows.append((label, cap.get("device") or "Unavailable", fmt(r.get("registers")),
                                    fmt(r.get("block_ram_tiles")), fmt(r.get("dsps")), fmt(r.get("uram"))))
    metric_names = (("after_max_minimum_link_hops", "Worst minimum physical link hops without a register", "Lower bound from the declared topology; not a routed transport path."),
                    ("after_max_register_free_crossings", "Worst FPGA boundary crossings without a register", "Largest number of ownership changes on a modeled combinational path."),
                    ("after_endpoint_excess_crossings", "Extra crossings summed over modeled endpoints", "For each endpoint, add crossings beyond the first; this counts path severity, not distinct nets."),
                    ("after_transit_primitives", "Combinational primitives on transit paths", "Logic that receives a remote signal and can forward it to another FPGA without a register capture."),
                    ("after_transfers", "Directed hypergraph transfers", "Sum of destination partitions beyond the first over modeled hyperedges; not the full net-bit I/O census."),
                    ("timing_paths_scored", "Sampled source timing paths scored", "Sampled Vivado paths only; this is not all-path timing closure."),
                    ("timing_cost_after", "Weighted cost on sampled source timing paths", "A relative ranking cost; not measured FPGA-to-FPGA delay or achieved DUT frequency."))
    metric_rows = [(name, fmt(quality.get(key.replace("after_", "before_", 1) if key.startswith("after_") else
                                          "timing_cost_before" if key == "timing_cost_after" else "__unavailable__")),
                   fmt(quality.get(key)), explanation) for key, name, explanation in metric_names]
    topology = data.get("topology", {})
    topology_summary = "Unavailable; the selected topology manifest was not supplied."
    topology_details = ""
    if topology:
        topology_summary = "Board: " + escape(topology.get("board", "Unnamed")) + "; selected FPGA count: " + fmt(topology.get("fpga_count")) + "; direct connected pairs: " + fmt(topology.get("connected_pairs")) + "."
        topology_rows = [(entry.get("partition", "Unavailable"), entry.get("path", "Unavailable"),
                          fmt(entry.get("selected_degree"))) for entry in topology.get("fpgas", [])]
        edge_rows = [(entry.get("partition_a", "Unavailable"), entry.get("partition_b", "Unavailable"),
                     fmt(entry.get("lanes")), fmt(entry.get("wires_at_min_compression"))) for entry in topology.get("edges", [])]
        topology_details = "<details><summary>Selected devices and declared direct links</summary>" + table(("Logical FPGA", "Physical topology location", "Direct selected neighbors"), topology_rows) + table(("FPGA", "Neighbor", "Declared lanes", "Wires at topology minimum compression"), edge_rows) + "</details>"
    historical_notice = ""
    if data.get("historical_evidence"):
        historical_notice = "<p class=notice><strong>Historical measurements preserved by exact ownership equivalence.</strong> This mapping is a contract-compliant re-encoding of an earlier assignment. Its leaf owners and top-port owner are unchanged. The resource, cut, path and iteration measurements below come from that historical run; they are <strong>not a fresh Vivado measurement or a new frontend acceptance run</strong>. Both mapping hashes and the checked equivalence proof are retained in report_data.json. Downstream regroup and hardware remain unverified.</p>"
    progress = []
    for row in data["candidates"]:
        iteration = row.get("iteration")
        reason = "Selected best accepted mapping" if iteration == selected else "Not selected"
        if row.get("cap_violations"):
            reason = "Rejected: hard LUT cap exceeded"
        elif row.get("within_native_search_envelope") is False:
            reason += "; outside the requested soft balance search envelope"
        elif row.get("path_analysis_complete") is False:
            reason += "; incomplete path model"
        progress.append((fmt(iteration), reason, fmt(row.get("max_crossings")), fmt(row.get("endpoint_excess")),
                         fmt(row.get("transit_primitives")), fmt(row.get("transfers")),
                         "Unavailable" if row.get("target_imbalance") is None else format(100 * _number(row["target_imbalance"], "candidate imbalance"), ".2f") + "%"))
    title = "FPGA partition report"
    category_rows = [("Data and functional synchronous control", fmt(cuts["unique_crossing_nets"][_CATEGORIES[0]]), fmt(cuts["directed_net_destinations"][_CATEGORIES[0]])),
                     ("Clock only", fmt(cuts["unique_crossing_nets"]["clock"]), fmt(cuts["directed_net_destinations"]["clock"])),
                     ("Reset only", fmt(cuts["unique_crossing_nets"]["reset_only"]), fmt(cuts["directed_net_destinations"]["reset_only"]))]
    body = ["<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>",
            "<title>" + title + "</title><style>html{color:#111;background:white;font:17px/1.6 Georgia,'Times New Roman',serif}body{max-width:1180px;margin:3rem auto;padding:0 1.5rem}h1,h2,h3{font-weight:normal;line-height:1.2}h1,h2{border-bottom:1px solid #999;padding-bottom:.3em}h1{font-size:2.4rem}h2{margin-top:2.5rem}a{color:#111}p,li{max-width:95ch}.lede{font-size:1.15rem}.notice{border:1px solid #888;padding:1rem;background:#fafafa;overflow-wrap:anywhere}.meta{font:13px/1.5 monospace;overflow-wrap:anywhere}pre.meta{white-space:pre-wrap;overflow-wrap:anywhere}figure{margin:2rem 0}img{max-width:100%;height:auto;display:block}figcaption{margin:.5rem 0;color:#222;font-size:.92rem}table{width:100%;border-collapse:collapse;font-size:.9rem}th,td{text-align:left;padding:.55rem .6rem;border-bottom:1px solid #bbb;vertical-align:top}th{background:#eee;border-top:1px solid #555}.table-scroll{overflow-x:auto}code{font-size:.9em;overflow-wrap:anywhere}summary{cursor:pointer}footer{border-top:1px solid #999;margin-top:3rem;padding-top:1rem;font-size:.85rem}@media print{body{margin:0;font-size:11pt}h2,figure,table{break-inside:avoid}a{text-decoration:none}}</style>",
            "<h1>" + title + "</h1><p class=lede>Selected mapping for <strong>" + str(len(labels)) + " FPGAs</strong>. The plots and resource counts describe the selected best accepted mapping, even when the final search attempt was worse.</p>",
            "<p class=notice><strong>Status:</strong> " + escape(data["status"]) + ". <strong>Selected iteration:</strong> " + fmt(selected) + " (search indices start at zero). <strong>Mapping:</strong> <a href='mapping.txt'>mapping.txt</a>. Hard capacity checks and mapping contract checks remain acceptance gates; these plots do not replace them.</p>",
            historical_notice,
            "<h2>Resources and I/O on each FPGA</h2>",
            table(("Logical FPGA", "Native Vivado CLB LUTs", "Device LUT utilization", "Hard LUT cap", "Outgoing net bits", "Incoming net bits", "Total I/O net bits"), resource_rows),
            "<details><summary>Registers, memories, arithmetic blocks and target devices</summary>" + table(("Logical FPGA", "Target device", "Registers", "Block RAM tiles", "DSP blocks", "UltraRAM blocks"), other_resource_rows) + "</details>",
            "<figure><a href='lut_balance.png'><img src='lut_balance.png' alt='Native Vivado LUT count and signed percentage deviation from the mean for every selected FPGA'></a><figcaption>Native Vivado CLB LUT count. The dashed horizontal line is the arithmetic mean. The lower chart is 100 × (FPGA count / mean − 1). All-zero designs have 0% deviation. Deviation from the mean is different from device utilization.</figcaption></figure>",
            "<figure><a href='io_cut_balance.png'><img src='io_cut_balance.png' alt='Unique incoming plus outgoing data/control net bits and signed percentage deviation from the mean'></a><figcaption>Per-FPGA I/O cut load: unique outgoing plus unique incoming canonical data/control net bits. A multicast A→B,C counts once at A and once at each receiver. Clock-only and reset-only nets are listed separately below.</figcaption></figure>",
            "<h2>What an I/O cut means</h2><p>A cut is a signal bit that crosses an FPGA ownership boundary. These counts describe the logical payload that a later prototyping transport or pinmux flow must carry. They are <strong>not physical package pin counts</strong>, bus counts, transport routing, a pinmux compression ratio, or a DUT clock prediction. A bus contributes one canonical net per connected bit. These charts count FPGA-to-FPGA connections; top-level external ports have no invented FPGA home. A cut hypergraph edge is an optimization-model connection; hypergraph transfers can differ from the complete net-bit census.</p>",
            table(("Signal category", "Unique net bits crossing any boundary", "Directed source/destination net-bit pairs"), category_rows),
            "<h2>Target system topology</h2><p>" + topology_summary + " Logical FPGA labels in mapping.txt are separate from physical board locations. The declared link counts guide placement and relative communication cost; these reports do not route transport or certify physical link capacity.</p>" + topology_details,
            "<h2>Communication between FPGAs</h2><figure><a href='fpga_connectivity.png'><img src='fpga_connectivity.png' alt='Directed FPGA communication matrix with exact data/control net-bit counts'></a><figcaption>Rows transmit; columns receive. Each cell counts one canonical data/control net bit for that source/destination pair. The matrix reports logical communication, not physical topology link bandwidth. Larger systems use the matrix alone so every FPGA remains readable.</figcaption></figure>",
            "<h2>Snake paths and timing</h2><p>A snake path crosses between FPGAs and passes through combinational logic before a register captures it. Repeated crossings on the same register-free path can reduce the usable DUT clock. The partitioner compares several complementary measurements rather than equating a lower total cut count with a faster design.</p>",
            table(("Measurement", "Before local cleanup", "Selected result", "Meaning and limit"), metric_rows),
            "<p>Before local cleanup refers to the selected candidate before its local refinement passes. It is not necessarily the first iteration or a separate baseline partitioner. The search-history table below compares complete iterations.</p>",
            "<p><strong>Timing sample coverage:</strong> " + escape(quality.get("timing_sample_coverage", "Unavailable")) + ". <strong>Path model:</strong> " + escape(quality.get("path_model", "Unavailable")) + ". Timing and topology values are model guidance, not post-regroup timing closure.</p>",
            "<h2>Search progress</h2><p>More iterations give the solver additional opportunities; improvement is not guaranteed. The accepted best candidate is retained. A later row can be worse or rejected. The table distinguishes hard cap failures and reported model/search limitations; a nonselected row is not automatically invalid.</p>",
            table(("Iteration", "Disposition", "Worst register-free crossings", "Extra endpoint crossings", "Transit primitives", "Hypergraph transfers", "Utilization imbalance"), progress) if progress else "<p>Per-iteration history was not supplied for this report.</p>",
            "<h2>Reproducibility and scope</h2><p>Every figure describes the exact mapping identified below. Native resources count the original synthesized design assigned to each FPGA; future wrapper, transport, clocking and debug resources are outside this count. The 75% LUT policy, when selected, is an engineering capacity bound rather than a guarantee of routing or timing closure. Mapping ordering, exact paths, residual ownership and nearest-ancestor redundancy rules are checked separately before publication.</p>",
            "<p class=meta>Mapping SHA256: " + escape(data["mapping_sha256"]) + "</p>",
            "<details><summary>Run settings and source identities</summary><pre class=meta>" + escape(json.dumps(data["run_settings"], indent=2, ensure_ascii=False)) + "</pre><pre class=meta>" + escape(json.dumps(data["source_files_sha256"], indent=2)) + "</pre></details>",
            "<p><a href='report_data.json'>Machine-readable report data</a> · <a href='report_manifest.json'>Report file hashes</a></p><footer>Figures use the bundled font and only light orange #F26522, dark orange #C4470C, and black #000000. This page uses a separate black-and-white document style. No external scripts, fonts, or network resources are required to read it.</footer></html>"]
    return "\n".join(body)


def generate_report(directory, state, mapping_path=None, font_path=None, metrics_directory=None,
                    historical_evidence_directory=None):
    """Render the selected candidate into an output staging directory.

    The caller publishes these artifacts and mapping.txt together only after
    this function succeeds.  Existing reports are never overwritten; a failed
    render cleans up its own partial output.  metrics_directory permits the
    native reports to remain in the original output while PNGs are staged.
    """
    directory = Path(directory)
    metrics = Path(metrics_directory) if metrics_directory is not None else directory
    mapping = Path(mapping_path) if mapping_path is not None else metrics / "mapping.txt"
    if font_path is None:
        raise ValueError("An explicit bundled report font is required")
    font_path = Path(font_path)
    if not font_path.is_file():
        raise ValueError("The bundled report font is missing: " + str(font_path))
    if any((directory / name).exists() for name in REPORT_FILES):
        raise ValueError("Report output files already exist; use a fresh report staging directory")
    directory.mkdir(parents=True, exist_ok=True)
    renderer = preflight(font_path)
    digest = _sha(mapping)
    sources = {}
    for name in ("vivado_resources.tsv", "lut_report.txt", "fpga_control_categories.tsv", "hardware_quality.txt",
                 "run_provenance.json", "target_capacity.json", "native_lut_caps.tsv", "cells.txt",
                 "mapping_contract.json", "mapping_connections_manifest.json", "topology_manifest.json"):
        if (metrics / name).is_file():
            sources[name] = _sha(metrics / name)
    labels_set = _mapping_labels(mapping)
    # Preserve native/cells order so A..Z,AZ..ZZ follow the topology's actual
    # selected destination order rather than lexicographic or invented labels.
    resources = _native_resources(metrics / "vivado_resources.tsv", labels_set)
    labels = list(resources)
    _check_lut_report(metrics / "lut_report.txt", resources)
    historical = None
    if historical_evidence_directory is None:
        provenance = _verify_identity(state, metrics, mapping, digest)
    else:
        historical, original_state, provenance = _historical_evidence(historical_evidence_directory, metrics, mapping, digest)
        if state.get("status") != HISTORICAL_STATUS or state.get("accepted_mapping_sha256") != digest:
            raise ValueError("Historical rendering must explicitly identify preserved measurements and the successor mapping")
    cuts = read_cut_categories(metrics / "fpga_control_categories.tsv", labels)
    capacities = _capacities(state, metrics, labels_set)
    for label in labels:
        cap = capacities[label]["hard_lut_cap"]
        if cap is not None and resources[label]["clb_luts"] > cap:
            raise ValueError("Report accepted mapping exceeds a hard native LUT cap")
    quality = _quality(metrics / "hardware_quality.txt")
    topology_path = metrics / "topology_manifest.json"
    topology = json.loads(topology_path.read_text(encoding="utf-8-sig")) if topology_path.is_file() else {}
    if not isinstance(topology, dict):
        raise ValueError("Selected topology manifest must be an object")
    if topology.get("fpgas") and {entry.get("partition") for entry in topology["fpgas"]} != labels_set:
        raise ValueError("Selected topology manifest FPGA labels differ from the mapping")
    settings = {key: value for key, value in state.items() if key not in
                ("candidates", "input_snapshot_sha256", "selected_report_sha256", "target_capacity", "mapping_contract")}
    data = {"schema_version": 1, "status": state.get("status", "UNSPECIFIED"), "fpga_labels": labels,
            "mapping_file": "mapping.txt", "mapping_sha256": digest, "source_files_sha256": sources,
            "selected_iteration": state.get("selected_iteration"), "resources": resources, "capacities": capacities,
            "lut_mean": deviations([resources[label]["clb_luts"] for label in labels])[0],
            "lut_deviation_percent": dict(zip(labels, deviations([resources[label]["clb_luts"] for label in labels])[1])),
            "io_cuts": cuts, "quality": quality, "topology": topology, "candidates": state.get("candidates", []),
            "historical_evidence": historical,
            "run_settings": settings, "run_provenance": provenance,
            "font": {"filename": font_path.name, "sha256": renderer["font_sha256"], "family": renderer["font_family"]},
            "renderer": renderer, "png_palette": list(PALETTE),
            "deviation_definition": "100*(per-FPGA value/arithmetic mean - 1); all-zero values have 0% deviation",
            "scope": "Selected source-netlist mapping only; no downstream regroup, package-pin allocation, implemented timing or hardware claim"}
    io_values = [cuts["by_category"][_CATEGORIES[0]][label]["total"] for label in labels]
    data["io_mean"], io_deviation = deviations(io_values)
    data["io_deviation_percent"] = dict(zip(labels, io_deviation))
    # JSON rejects nonfinite numbers anywhere, including optional history or
    # settings, before any file is published by the frontend.
    encoded = json.dumps(data, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    try:
        _balance_chart(directory / REPORT_FILES[0], labels, [resources[label]["clb_luts"] for label in labels],
                       "LUT resources for the selected mapping", "Native Vivado CLB LUTs", font_path)
        _balance_chart(directory / REPORT_FILES[1], labels, io_values,
                       "I/O cut load for the selected mapping", "Incoming + outgoing net bits", font_path)
        _connectivity_chart(directory / REPORT_FILES[2], labels, cuts["directed_matrices"][_CATEGORIES[0]], font_path)
        (directory / "report_data.json").write_text(encoded, encoding="utf-8")
        (directory / "report.html").write_text(_html(data), encoding="utf-8")
        # Check immutable inputs again after potentially slow rendering.
        if _sha(mapping) != digest or _sha(font_path) != data["font"]["sha256"]:
            raise ValueError("Mapping or report font changed during rendering")
        for name, expected in sources.items():
            if _sha(metrics / name) != expected:
                raise ValueError("Report input changed during rendering: " + name)
        if historical is None:
            _verify_identity(state, metrics, mapping, digest)
        else:
            after, unused_state, unused_provenance = _historical_evidence(historical_evidence_directory, metrics, mapping, digest)
            if after != historical:
                raise ValueError("Historical equivalence evidence changed during rendering")
        manifest = {"schema_version": 1, "mapping_sha256": digest, "font": data["font"],
                    "png_palette": list(PALETTE), "source_files_sha256": sources,
                    "files_sha256": {name: _sha(directory / name) for name in REPORT_FILES if name != "report_manifest.json"}}
        if historical is not None:
            manifest["historical_evidence"] = historical
        (directory / "report_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        return manifest
    except Exception:
        for name in REPORT_FILES:
            (directory / name).unlink(missing_ok=True)
        raise


def generate_historical_report(directory, historical_metrics_directory, font_path, output_directory=None):
    """Backfill a readable report without relabeling historical native proofs.

    All three successor proof manifests and every pinned historical artifact
    must be intact.  Ordinary partition runs never enter this explicit mode.
    """
    directory, metrics = Path(directory), Path(historical_metrics_directory)
    state = json.loads((metrics / "STATUS.json").read_text(encoding="utf-8-sig"))
    state.update(status=HISTORICAL_STATUS, accepted_mapping_sha256=_sha(directory / "mapping.txt"),
                 historical_metrics_scope="Original native and source-graph measurements preserved by exact ownership equivalence; not rerun")
    return generate_report(output_directory if output_directory is not None else directory,
                           state, directory / "mapping.txt", font_path, metrics,
                           historical_evidence_directory=directory)
