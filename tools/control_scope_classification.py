"""Held v2 native control-category oracle; electrical fingerprints stay separate.

Input cell metadata must come from source-bound native IS_PRIMITIVE/IS_BLACKBOX
and parameter queries. This pure module never invokes Vivado or guesses source
trust from a REF_NAME alone. Defaults are pinned to the 2025.1 top modules.
"""
import re
import csv
import hashlib
from pathlib import Path
import tkinter

SCOPE = "functional_synchronous_controls_v2_UNISIM_2025_1"
AUTHORITY = "AMD_VIVADO_2025_1_UNISIM_SYNCHRONOUS_CONTROL_DEFAULTS_V2"
CAPTURE, UNKNOWN, EXCLUDED = "capture", "unknown", "excluded"
DSP_STAGES = {
    "RSTA": ("AREG",), "RSTB": ("BREG",), "RSTC": ("CREG",), "RSTD": ("DREG", "ADREG"),
    "RSTALUMODE": ("ALUMODEREG",), "RSTINMODE": ("INMODEREG",), "RSTCTRL": ("OPMODEREG", "CARRYINSELREG"),
    "RSTALLCARRYIN": ("CARRYINREG", "MREG"), "RSTM": ("MREG",), "RSTP": ("PREG",)}


def load_metadata(path, expected_sha256, expected_cells):
    """Read an exact source-receipt-bound sidecar; Tcl data is never evaluated.
    Repeated reference/configuration metadata is shared to bound resident size.
    """
    path = Path(path)
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256): raise ValueError("Exact metadata digest required")
    with path.open("rb") as handle: digest = hashlib.file_digest(handle, "sha256").hexdigest()
    if digest != expected_sha256: raise ValueError("Native metadata digest mismatch")
    cells, interned = {}, {}
    tcl = tkinter.Tcl()
    with path.open(encoding="utf-8", newline="") as stream:
        if stream.readline().rstrip("\r\n") != "#control_scope=" + SCOPE: raise ValueError("Unknown native control scope")
        if stream.readline().rstrip("\r\n") != "#default_authority=" + AUTHORITY: raise ValueError("Unknown native default authority")
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames != ["name", "ref_name", "is_primitive", "is_blackbox", "parameters_tcl_list"]: raise ValueError("Native metadata schema differs")
        for row in reader:
            if set(row) != set(reader.fieldnames) or any(value is None for value in row.values()): raise ValueError("Malformed native metadata row")
            name, kind = row["name"], row["ref_name"]
            if not name or not kind or name in cells: raise ValueError("Duplicate/empty native cell metadata")
            flags = []
            for field in ("is_primitive", "is_blackbox"):
                value = row[field].lower()
                if value not in ("0", "1", "false", "true"): raise ValueError("Native trust flags unavailable")
                flags.append(value in ("1", "true"))
            words = tcl.splitlist(row["parameters_tcl_list"])
            if len(words) % 2 or len(set(words[::2])) != len(words) // 2: raise ValueError("Native parameter dictionary differs")
            parameters = dict(zip(words[::2], words[1::2]))
            key = (kind, *flags, tuple(sorted(parameters.items())))
            if key not in interned: interned[key] = dict(ref_name=kind, is_primitive=flags[0], is_blackbox=flags[1], parameters=parameters)
            cells[name] = interned[key]
    with path.open("rb") as handle: final_digest = hashlib.file_digest(handle, "sha256").hexdigest()
    if len(cells) != expected_cells or final_digest != digest:
        raise ValueError("Native metadata count or bytes changed")
    return cells


def trusted(cell):
    return cell.get("is_primitive") is True and cell.get("is_blackbox") is False


def parameter(cell, key):
    kind = cell["ref_name"]
    dsp = kind in ("DSP48E1", "DSP48E2")
    bram = re.fullmatch(r"RAMB(?:18|36)E[12]", kind) is not None
    if not trusted(cell) or not (dsp and key in {name for names in DSP_STAGES.values() for name in names}
                                or bram and key in ("DOA_REG", "DOB_REG")):
        return None
    values = cell.get("parameters", {})
    if key not in values:
        return 1 if dsp or kind.endswith("E2") else 0
    raw = str(values[key]).strip("".join(chr(i) for i in range(33)))
    if not re.fullmatch(r"[+-]?[0-9]+", raw): return None
    number = int(raw)
    return number if 0 <= number <= (2 if key in ("AREG", "BREG") else 1) else None


def reset_pin(cell, pin):
    if not trusted(cell): return False
    kind = cell["ref_name"]
    return ((kind == "FDRE" and pin == "R") or (kind == "FDSE" and pin == "S")
            or (kind in ("FDCE", "LDCE") and pin == "CLR") or (kind in ("FDPE", "LDPE") and pin == "PRE")
            or (re.fullmatch(r"RAMB(?:18|36)E[12]", kind) and pin in ("RSTRAMARSTRAM", "RSTRAMB", "RSTREGARSTREG", "RSTREGB"))
            or (kind in ("URAM288", "URAM288_BASE") and pin in ("RST_A", "RST_B"))
            or (kind in ("DSP48E1", "DSP48E2") and pin in DSP_STAGES))


def reset_role(cell, pin):
    if not reset_pin(cell, pin): return UNKNOWN
    kind = cell["ref_name"]
    keys = {name for names in DSP_STAGES.values() for name in names} if kind in ("DSP48E1", "DSP48E2") else {"DOA_REG", "DOB_REG"} if kind.startswith("RAMB") else set()
    if any(key in cell.get("parameters", {}) and parameter(cell, key) is None for key in keys): return UNKNOWN
    if kind.startswith("URAM") and any(cell.get("parameters", {}).get("RST_MODE_" + port, "SYNC") not in ("SYNC", "ASYNC") for port in ("A", "B")): return UNKNOWN
    if kind in ("FDRE", "FDSE"): return CAPTURE
    if kind in ("FDCE", "FDPE", "LDCE", "LDPE"): return EXCLUDED
    if kind.startswith("RAMB"):
        if pin.startswith("RSTRAM"): return CAPTURE
        value = parameter(cell, "DOB_REG" if pin == "RSTREGB" else "DOA_REG")
        return UNKNOWN if value is None else CAPTURE if value else EXCLUDED
    if kind.startswith("URAM"):
        mode = cell.get("parameters", {}).get("RST_MODE_" + pin[-1], "SYNC")
        return CAPTURE if mode == "SYNC" else EXCLUDED if mode == "ASYNC" else UNKNOWN
    values = [parameter(cell, name) for name in DSP_STAGES[pin]]
    if any(value is None for value in values): return UNKNOWN
    return CAPTURE if any(values) else EXCLUDED


def clock_pin(cell, pin):
    if not trusted(cell): return False
    kind = cell["ref_name"]
    if re.fullmatch(r"FD(?:C|CE|CP|CPE|E|P|PE|R|RE|RS|RSE|S|SE)?(?:_1)?", kind): return pin == "C"
    if re.fullmatch(r"LD(?:C|CE|CP|CPE|E|P|PE)?(?:_1)?", kind): return pin == "G"
    if kind.startswith(("RAMB", "FIFO", "URAM")): return pin.startswith("CLK") or pin in ("RDCLK", "WRCLK")
    if kind.startswith("DSP"): return pin == "CLK"
    if kind.startswith(("BUFG", "BUFH", "BUFR", "BUFIO", "BUFDIV")): return pin in ("I", "I0", "I1")
    lut_ram = kind in ("RAMD32", "RAMD32M64", "RAMD64E", "RAMD64E5", "RAMS32", "RAMS64E", "RAMS64E1", "RAMS64E5") or re.fullmatch(r"RAM(?:16|32|64|128|256|512)(?:X[0-9]+[SD](?:R[0-9]+|W)?|M[0-9]*)(?:_[1-8])?", kind)
    shift = re.fullmatch(r"SRLC?(?:16|32)E?(?:_1)?", kind)
    if lut_ram or shift: return pin in ("CLK", "WCLK")
    return False


def classify_net(endpoints, cells):
    """Classify actual load roles without filtering any electrical endpoint.

    Endpoints have path/pin/direction/top_port. Ambiguous direction/driver
    evidence cannot produce a clean native category parity result.
    """
    loads, drivers = [], []
    for endpoint in endpoints:
        top = endpoint["top_port"]
        direction = endpoint["direction"]
        if direction not in ("IN", "OUT"): raise ValueError("Ambiguous native endpoint direction")
        (drivers if direction == ("IN" if top else "OUT") else loads).append(endpoint)
    if len(drivers) != 1: raise ValueError("Native category requires exactly one real driver")
    if not loads: return "data_or_functional_sync_control"
    roles = []
    for endpoint in loads:
        if endpoint["top_port"]: return "data_or_functional_sync_control"
        if endpoint["path"] not in cells: raise ValueError("Native logical leaf metadata missing")
        cell = cells[endpoint["path"]]
        pin = endpoint["pin"].split("[", 1)[0]
        roles.append((clock_pin(cell, pin), reset_pin(cell, pin), reset_role(cell, pin)))
    if all(clock for clock, _, _ in roles): return "clock"
    if all(reset for _, reset, _ in roles) and all(role != UNKNOWN for _, _, role in roles):
        if drivers[0]["top_port"] or all(role == EXCLUDED for _, _, role in roles): return "reset_only"
    return "data_or_functional_sync_control"


class ControlCategoryParity:
    """Optional category check during the existing one-net-at-a-time scan.

    The old electrical result remains independent. Expected category rows must
    bind the same fingerprint AND directed pairs as each case's existing TSV.
    Caller binds native metadata/producer/checkpoint bytes via its receipt.
    """
    def __init__(self, cells, cases, reports):
        if set(reports) != {case["name"] for case in cases}: raise ValueError("Every native case needs one control report")
        self.cells, self.rows, self.seen = cells, {}, {}
        self.mismatch, self.mismatch_counts, self.counts = {}, {}, {}
        for case in cases:
            path = Path(reports[case["name"]])
            rows = {}
            with path.open(encoding="utf-8", newline="") as stream:
                if stream.readline().rstrip("\r\n") != "#control_scope=" + SCOPE: raise ValueError("RapidWright control scope differs")
                if stream.readline().rstrip("\r\n") != "#default_authority=" + AUTHORITY: raise ValueError("RapidWright control authority differs")
                reader = csv.DictReader(stream, delimiter="\t")
                if reader.fieldnames != ["CANONICAL_NET", "CATEGORY", "DIRECTED_PARTITION_PAIRS", "ENDPOINT_FINGERPRINT"]:
                    raise ValueError("RapidWright control schema differs")
                for row in reader:
                    key = row["ENDPOINT_FINGERPRINT"]
                    if key in rows or key not in case["expected"]: raise ValueError("Control report fingerprints differ")
                    pairs = sorted(row["DIRECTED_PARTITION_PAIRS"].split(","))
                    if pairs != case["expected"][key]["pairs"]: raise ValueError("Control report mapping pairs differ")
                    if row["CATEGORY"] not in ("clock", "reset_only", "data_or_functional_sync_control"): raise ValueError("Control report category differs")
                    rows[key] = row["CATEGORY"]
            if rows.keys() != case["expected"].keys(): raise ValueError("Control report boundary is incomplete")
            self.rows[case["name"]], self.seen[case["name"]] = rows, set()
            self.mismatch[case["name"]], self.counts[case["name"]] = [], {"clock": 0, "reset_only": 0, "data_or_functional_sync_control": 0}
            self.mismatch_counts[case["name"]] = 0

    def validate_cells(self, types):
        if self.cells.keys() != types.keys() or any(self.cells[name]["ref_name"] != kind for name, kind in types.items()):
            raise ValueError("Native control metadata logical census differs")

    def observe(self, emitted, cases, crossings, fingerprint):
        endpoints = [dict(path=owner, pin=pin, direction=direction, top_port=kind == "port")
                     for (kind, _), (direction, owner, pin) in emitted.items()]
        category = classify_net(endpoints, self.cells)
        for case, pairs in zip(cases, crossings):
            if not pairs: continue
            name = case["name"]
            self.counts[name][category] += len(pairs)
            if fingerprint not in self.rows[name]: continue  # Existing electrical checker reports extra.
            if fingerprint in self.seen[name]: raise ValueError("Duplicate native control fingerprint")
            self.seen[name].add(fingerprint)
            if category != self.rows[name][fingerprint]:
                self.mismatch_counts[name] += 1
                if len(self.mismatch[name]) < 10: self.mismatch[name].append(dict(fingerprint=fingerprint, native=category, rapidwright=self.rows[name][fingerprint]))

    def finish(self):
        cases = []
        for name, rows in self.rows.items():
            missing = rows.keys() - self.seen[name]
            cases.append(dict(name=name, passed=not missing and not self.mismatch[name], compared=len(self.seen[name]),
                              missing_count=len(missing), category_mismatch_count=self.mismatch_counts[name],
                              mismatch_sample=self.mismatch[name][:10], directed_destinations_by_category=self.counts[name]))
        return dict(scope=SCOPE, default_authority=AUTHORITY, passed=all(case["passed"] for case in cases), cases=cases,
                    scope_unit="canonical electrical crossing net per directed FPGA pair; source-bound functional synchronous control roles")
