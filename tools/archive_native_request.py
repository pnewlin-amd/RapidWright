#!/usr/bin/env python3
"""Losslessly archive sealed per-mapping Vivado reports/cell lists after Java oracle exit 0.

Tables, completion/provenance, source_mapping, manifests, frontier and assignment
anchors remain plain. --restore --apply recovers exact original bytes.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
from decimal import Decimal, InvalidOperation
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import uuid

CHUNK = 4 * 1024 * 1024
SCHEMA = 1


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(CHUNK), b""):
            value.update(block)
    return value.hexdigest()


def bounded(path: Path, directory: Path) -> Path:
    path = path.resolve()
    if path == directory or not path.is_relative_to(directory):
        raise ValueError(f"Artifact escapes native request directory: {path}")
    return path


def request_directory(directory: Path, workspace_root: Path) -> Path:
    directory, workspace_root = directory.resolve(), workspace_root.resolve()
    if directory == workspace_root or not directory.is_relative_to(workspace_root) or not directory.is_dir():
        raise ValueError(f"Native request must be an existing directory inside workspace: {directory}")
    return directory


def eligible(path: Path) -> bool:
    return path.suffix.lower() == ".rpt" or bool(re.fullmatch(r"native_cells_FPGA_[A-Z]+\.txt", path.name))


def metadata(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in result and result[key] != value:
            raise ValueError(f"Conflicting metadata field {key}: {path}")
        result[key] = value
    return result


def atomic_json(path: Path, value: dict) -> None:
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


@contextmanager
def directory_lock(directory: Path):
    path = bounded(directory / ".native_request_archive.lock", directory)
    with path.open("a+b") as stream:
        if stream.seek(0, 2) == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def protected_hashes(directory: Path) -> dict[str, str]:
    return {path.name: sha256(bounded(path, directory)) for path in directory.iterdir()
            if path.is_file() and not eligible(path) and not path.name.endswith((".gz", ".archive.json", ".tmp"))
            and not path.name.startswith(("native_request_archive", ".native_request_archive"))}


def seal_native_request(directory: Path, mapping: Path | None, oracle_exit_code: int | None, workspace_root: Path) -> dict:
    if oracle_exit_code != 0:
        raise ValueError("Archiving requires explicit --oracle-exit-code 0 after Java oracle returned successfully")
    complete_path = bounded(directory / "native_complete.txt", directory)
    complete = metadata(complete_path)
    if complete.get("status") != "VIVADO_PARTITION_RESOURCE_MAPPING_PASS":
        raise ValueError("Native request is not complete")
    provenance_path = directory / "vivado_provenance.txt"
    if not provenance_path.is_file():
        provenance_path = directory / "provenance.txt"
    provenance = metadata(bounded(provenance_path, directory))
    checkpoint_hash = complete.get("checkpoint_sha256", "")
    mapping_hash = complete.get("mapping_sha256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", checkpoint_hash) or checkpoint_hash != provenance.get("checkpoint_sha256"):
        raise ValueError("Inconsistent native checkpoint provenance")
    checkpoint = Path(provenance.get("checkpoint", ""))
    if not checkpoint.is_absolute() or not checkpoint.resolve().is_relative_to(workspace_root.resolve()) or not checkpoint.is_file() or sha256(checkpoint) != checkpoint_hash:
        raise ValueError("Native source checkpoint fingerprint mismatch")
    source_mapping = bounded(directory / "source_mapping.txt", directory)
    if not re.fullmatch(r"[0-9a-f]{64}", mapping_hash) or mapping_hash != provenance.get("mapping_sha256") or sha256(source_mapping) != mapping_hash:
        raise ValueError("Native source mapping fingerprint mismatch")
    if mapping is not None and (not mapping.resolve().is_relative_to(workspace_root.resolve()) or sha256(mapping.resolve()) != mapping_hash):
        raise ValueError("Caller mapping differs from sealed native request")
    expected = set()
    for line in source_mapping.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        fields = line.split()
        if len(fields) != 2:
            raise ValueError("Malformed source mapping rule")
        expected.add(fields[1])
    table = bounded(directory / "vivado_resources.tsv", directory)
    lines = table.read_text(encoding="utf-8").splitlines()
    if not lines:
        raise ValueError("Empty native resource table")
    columns = lines[0].split("\t")
    if not columns or columns[0] != "partition" or not {"clb_luts", "flip_flops"}.issubset(columns):
        raise ValueError("Invalid native resource table header")
    actual = set()
    for line in lines[1:]:
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) != len(columns) or fields[0] in actual:
            raise ValueError("Malformed/duplicate native resource table row")
        actual.add(fields[0])
        for column, value in zip(columns[1:], fields[1:]):
            if value == "NA" and column not in ("clb_luts", "flip_flops"):
                continue
            try:
                number = Decimal(value)
            except InvalidOperation as error:
                raise ValueError("Invalid native resource number") from error
            if not number.is_finite() or number < 0:
                raise ValueError("Invalid native resource number")
    if "DESIGN" not in actual or actual - {"DESIGN"} != expected:
        raise ValueError("Native table partition labels differ from source mapping")
    return {"schema": SCHEMA, "status": "SEALED_NATIVE_REQUEST", "directory": str(directory),
            "caller_java_oracle_exit_code": oracle_exit_code, "checkpoint": str(checkpoint.resolve()),
            "checkpoint_sha256": checkpoint_hash, "mapping_sha256": mapping_hash,
            "table_sha256": sha256(table), "protected_plain_files_sha256": protected_hashes(directory),
            "helper_sha256": sha256(Path(__file__)),
            "scope": "Original per-mapping native request; archive only human reports and generated native cell name lists"}


def roundtrip(archive: Path) -> tuple[str, int]:
    value, count = hashlib.sha256(), 0
    with gzip.open(archive, "rb") as stream:
        for block in iter(lambda: stream.read(CHUNK), b""):
            value.update(block)
            count += len(block)
    return value.hexdigest(), count


def receipt_fields(record: dict) -> tuple[Path, str, int, Path, str, int]:
    # Also restore proven completed-case reports produced by the earlier local helper.
    return (Path(record.get("source_file", record.get("report", ""))),
            record.get("source_sha256", record.get("original_sha256", "")),
            record.get("source_bytes", record.get("original_bytes", -1)),
            Path(record["archive"]), record["archive_sha256"], record["archive_bytes"])


def archive_file(source: Path, directory: Path, seal_hash: str) -> dict:
    source = bounded(source, directory)
    if not eligible(source):
        raise ValueError("File is outside the explicit archive whitelist")
    archive = bounded(source.with_name(source.name + ".gz"), directory)
    receipt = bounded(source.with_name(source.name + ".archive.json"), directory)
    source_hash, source_size = sha256(source), source.stat().st_size
    if archive.exists() or receipt.exists():
        if not archive.is_file() or not receipt.is_file():
            raise ValueError(f"Refuse incomplete or unrelated existing archive: {archive}")
        record = json.loads(receipt.read_text(encoding="utf-8-sig"))
        old_source, old_hash, old_size, old_archive, compressed_hash, compressed_size = receipt_fields(record)
        if bounded(old_source, directory) != source or bounded(old_archive, directory) != archive or old_hash != source_hash or old_size != source_size:
            raise ValueError("Refuse to overwrite a different existing archive/receipt")
        if sha256(archive) != compressed_hash or archive.stat().st_size != compressed_size or roundtrip(archive) != (source_hash, source_size):
            raise ValueError("Existing archive fails exact round-trip verification")
        if sha256(source) != source_hash:
            raise ValueError("Source changed during existing archive validation")
        source.unlink()
        return dict(record, reuse_status="REUSED_VERIFIED_ARCHIVE")
    temporary = bounded(archive.with_name(archive.name + "." + uuid.uuid4().hex + ".tmp"), directory)
    try:
        with source.open("rb") as incoming, temporary.open("xb") as outgoing:
            with gzip.GzipFile(filename=source.name, mode="wb", fileobj=outgoing, compresslevel=3, mtime=0) as compressed:
                for block in iter(lambda: incoming.read(CHUNK), b""):
                    compressed.write(block)
        decompressed_hash, decompressed_size = roundtrip(temporary)
        if (decompressed_hash, decompressed_size) != (source_hash, source_size) or sha256(source) != source_hash:
            raise ValueError("Archive round-trip/source stability validation failed")
        os.link(temporary, archive)  # Atomic publication refuses an existing/different archive.
        temporary.unlink()
        record = {"schema": SCHEMA, "status": "VERIFIED_LOSSLESS_ARCHIVE", "source_file": str(source),
                  "source_sha256": source_hash, "source_bytes": source_size, "archive": str(archive),
                  "archive_sha256": sha256(archive), "archive_bytes": archive.stat().st_size,
                  "roundtrip_sha256": decompressed_hash, "roundtrip_bytes": decompressed_size,
                  "native_request_seal_sha256": seal_hash,
                  "kind": "human_report" if source.suffix.lower() == ".rpt" else "generated_native_cell_name_list"}
        # Preserve field aliases consumed by the existing completed-case coordinator.
        record.update(original_sha256=source_hash, original_bytes=source_size)
        if source.suffix.lower() == ".rpt":
            record["report"] = str(source)
        atomic_json(receipt, record)  # Recoverable proof is durable before exact original removal.
        bounded(source, directory).unlink()
        return record
    finally:
        if temporary.exists():
            bounded(temporary, directory).unlink()


def restore_file(receipt: Path, directory: Path, apply: bool) -> dict:
    record = json.loads(bounded(receipt, directory).read_text(encoding="utf-8-sig"))
    source, source_hash, source_size, archive, compressed_hash, compressed_size = receipt_fields(record)
    source, archive = bounded(source, directory), bounded(archive, directory)
    if not eligible(source) or archive != source.with_name(source.name + ".gz") or receipt.name != source.name + ".archive.json":
        raise ValueError("Restore receipt is outside the exact archive whitelist")
    if sha256(archive) != compressed_hash or archive.stat().st_size != compressed_size:
        raise ValueError("Compressed archive fingerprint/size mismatch")
    if source.exists():
        if sha256(source) != source_hash or source.stat().st_size != source_size:
            raise ValueError("Refuse to overwrite changed existing original")
        return dict(record, restore_status="ALREADY_RESTORED")
    if not apply:
        return dict(record, restore_status="PLAN_ONLY")
    temporary = bounded(source.with_name(source.name + "." + uuid.uuid4().hex + ".restore.tmp"), directory)
    try:
        value, size = hashlib.sha256(), 0
        with gzip.open(archive, "rb") as incoming, temporary.open("xb") as outgoing:
            for block in iter(lambda: incoming.read(CHUNK), b""):
                outgoing.write(block)
                value.update(block)
                size += len(block)
        if (value.hexdigest(), size) != (source_hash, source_size):
            raise ValueError("Restored original fingerprint/size mismatch")
        os.link(temporary, source)  # Never overwrite an original created during restoration.
        temporary.unlink()
        return dict(record, restore_status="RESTORED_VERIFIED", restored_sha256=sha256(source))
    finally:
        if temporary.exists():
            bounded(temporary, directory).unlink()


def process_request(directory: Path, workspace_root: Path, *, mapping: Path | None = None,
                    oracle_exit_code: int | None = None, minimum_bytes: int = 1024 * 1024,
                    apply: bool = False, restore: bool = False) -> dict:
    directory = request_directory(directory, workspace_root)
    if minimum_bytes < 0:
        raise ValueError("Minimum bytes must be nonnegative")
    with directory_lock(directory):
        if restore:
            receipts = [path for path in directory.glob("*.archive.json")
                        if eligible(Path(path.name.removesuffix(".archive.json")))]
            records = [restore_file(path, directory, apply) for path in sorted(receipts)]
            result = {"status": "NATIVE_REQUEST_RESTORE_PASS" if apply else "PLAN_ONLY", "directory": str(directory), "files": records}
        else:
            seal = seal_native_request(directory, mapping, oracle_exit_code, workspace_root)
            candidates = [path for path in directory.iterdir() if path.is_file() and eligible(path) and path.stat().st_size >= minimum_bytes]
            if apply:
                seal_path = directory / "native_request_archive_seal.json"
                atomic_json(seal_path, seal)
                seal_hash = sha256(seal_path)
                records = [archive_file(path, directory, seal_hash) for path in sorted(candidates)]
                if protected_hashes(directory) != seal["protected_plain_files_sha256"]:
                    raise ValueError("Plain native metadata/assignment anchor changed during archive")
            else:
                records = [{"source_file": str(bounded(path, directory)), "source_bytes": path.stat().st_size} for path in sorted(candidates)]
            def original_size(record):
                return record.get("source_bytes", record.get("original_bytes", 0))
            result = {"status": "SEALED_NATIVE_REQUEST_ARCHIVE_PASS" if apply else "PLAN_ONLY", "directory": str(directory),
                      "seal": seal, "files": records, "source_bytes": sum(original_size(record) for record in records),
                      "archive_bytes": sum(record.get("archive_bytes", 0) for record in records),
                      "scope": "Human .rpt and native_cells_FPGA_*.txt only; native tables/receipts/manifests and all mapping/frontier/assignment anchors remain plain"}
        if apply:
            atomic_json(directory / ("native_request_restore.json" if restore else "native_request_archive.json"), result)
        return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", "--request-directory", type=Path, required=True)
    parser.add_argument("--workspace-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--oracle-exit-code", type=int)
    parser.add_argument("--minimum-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--restore", action="store_true")
    args = parser.parse_args()
    result = process_request(args.directory, args.workspace_root, mapping=args.mapping, oracle_exit_code=args.oracle_exit_code,
                             minimum_bytes=args.minimum_bytes, apply=args.apply, restore=args.restore)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
