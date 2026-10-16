"""Capture bounded, already-completed J4 reports; never read a netlist or mapping payload.

Run from any directory with Python 3.  --workspace identifies the parent containing
RapidWright, LSUT_2, work, topologies, and RESULTS FOR J4.  This is an evidence
extractor, not a validator or a substitute for the original execution receipts.
"""
import argparse
import hashlib
import json
import pathlib
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[4])
    parser.add_argument('--output', type=pathlib.Path,
                        default=pathlib.Path(__file__).with_name('validation_evidence.json'))
    args = parser.parse_args()
    root = args.workspace.resolve()
    records = {}

    def alias(value):
        if isinstance(value, dict):
            return {alias(k): alias(v) for k, v in value.items()}
        if isinstance(value, list):
            return [alias(v) for v in value]
        if not isinstance(value, str):
            return value
        forms = [str(root), root.as_posix()]
        if re.match(r'^[A-Za-z]:', str(root)):
            forms += ['/mnt/' + str(root)[0].lower() + '/' + root.as_posix()[3:]]
        for prefix in forms:
            value = re.sub(re.escape(prefix), 'workspace', value, flags=re.I)
        value = re.sub(r'C:[/\\]Xilinx[/\\]2025\.1', 'VIVADO_INSTALL', value, flags=re.I)
        value = re.sub(r'C:[/\\]Python313[/\\]python\.exe', 'PYTHON', value, flags=re.I)
        # Report aliases are relative identifiers, not new claims about relocated bytes.
        return value.replace('\\', '/') if 'workspace' in value else value

    def read(relative, kind='json'):
        path = root / relative
        raw = path.read_bytes()
        if len(raw) > 2 * 1024 * 1024:
            raise ValueError('Not a bounded evidence input: ' + str(relative))
        key = pathlib.Path(relative).as_posix()
        records[key] = {'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw),
                        'scope': 'original completed report bytes; no original payload revalidation'}
        txt = raw.decode('utf-8-sig')
        return alias(json.loads(txt)) if kind == 'json' else alias(txt)

    summary = read('RESULTS FOR J4/SUMMARY.json')
    assert summary['status'] == 'SIXTEEN_CURRENT_QUALIFIED_J4_PACKAGE_ARTIFACTS_PASS'
    assert len(summary['cases']) == 16
    evidence = {
        'schema': 'rapidwright_j4_validation_evidence_20261015_v1',
        'capture_date': '2026-10-15',
        'scope': 'Portable extracts of completed reports, not a new solver, Native, STA, or payload run.',
        'path_convention': 'workspace/ aliases refer to the original common parent; hashes always identify original bytes before path normalization.',
        'benchmark_engine_manifest_sha256': summary['approved_engine_manifest_sha256'],
        'release_lineage': {
            'branch': 'Partitioner_Extreme_Overhaul', 'starting_commit': '5bc91b48',
            'validated_source_commit': 'f9f2cada5b8151df08414188e6bdfc554f6ce695',
            'large_results_scope': 'Recorded benchmark engine b12; later portable launch/environment changes were not followed by sixteen large-design reruns.',
            'fresh_release_build_tests_and_smoke': 'Actual clean-clone and unpacked-runtime checks are preserved separately in release_validation; no new large-design experiment is implied.'
        },
        'summary': summary,
        'cases': [],
        'controlled_comparisons': {},
        'historical_source_reviews': [],
        'input_reports': records,
    }
    # Small case reports are copied semantically; mappings and source design payloads
    # are referenced exclusively through the hashes recorded by actual qualification.
    for case in summary['cases']:
        k = case['fpga_count']
        base = pathlib.Path('RESULTS FOR J4') / case['design'] / ('1 FPGA' if k == 1 else f'{k} FPGAS')
        item = {'case': case['case'], 'package_alias': 'workspace/' + base.as_posix()}
        for name in ['identity.json', 'QUALIFICATION.json', 'STATUS.json']:
            item[name] = read(base / name)
        for name in ['hardware_quality.txt', 'native_candidates.tsv', 'refinement_passes.tsv',
                     'swap_refinement_passes.tsv', 'fpga_devices.tsv', 'vivado_resources.tsv',
                     'metric_definitions.txt']:
            q = root / base / name
            if q.exists():
                item[name] = read(base / name, 'text')
        for name in ['run_provenance.json', 'target_capacity.json']:
            item[name] = read(base / name)
        q = item['QUALIFICATION.json']['qualification']
        assert q['status'] == 'PASS_FULL_MAP_BOUND_ENDPOINT_AND_CATEGORY_PARITY'
        assert q['control_categories']['passed']
        assert not q['control_categories']['missing_count']
        assert not q['control_categories']['category_mismatch_count']
        # Final proof files are small copied receipts, excluding all bulk streams.
        item['parity_receipts'] = {}
        for p in sorted((root / base / 'parity_audit_final').iterdir()):
            if p.suffix == '.json' and p.stat().st_size <= 2 * 1024 * 1024:
                receipt = read(p.relative_to(root))
                # Joint completion progress and prepared per-net group arrays are
                # reproducible from the original hash-pinned reports but add no
                # new paper result. Retain identity and actual phase outcomes.
                if 'prepared_plan' in p.name:
                    receipt = {k: v for k, v in receipt.items() if not isinstance(v, (dict, list))}
                    receipt['extraction_scope'] = 'scalar identity fields; original full report hash retained'
                elif 'phases' in receipt:
                    short = {k: v for k, v in receipt.items() if not isinstance(v, (dict, list))}
                    short['phases'] = {
                        phase: {k: v for k, v in monitor.items()
                                if k not in ('progress', 'owned_native')}
                        for phase, monitor in receipt['phases'].items()
                    }
                    short['extraction_scope'] = 'scalar identity and actual owned phase exits/resources; original full report hash retained'
                    receipt = short
                item['parity_receipts'][p.name] = receipt
        evidence['cases'].append(item)

    mesh = pathlib.Path('work/final_j4_matrix_controller_v3/evidence/RISCV_MESH_3072_k3/installed_serial_default_20261014_329ddf1b')
    status = read(mesh / 'STATUS.json')
    selected = []
    for c in status['candidates']:
        if c.get('iteration') in (0, 1) and 'native_clb_luts' in c:
            d = pathlib.Path(str(c['directory']).removeprefix('workspace/'))
            entry = {'candidate_record': c}
            for name in ['hardware_quality.txt', 'native_candidates.tsv', 'refinement_passes.tsv',
                         'swap_refinement_passes.tsv', 'native_complete.txt', 'vivado_resources.tsv']:
                entry[name] = read(d / name, 'text')
            entry['run_provenance.json'] = read(d / 'run_provenance.json')
            selected.append(entry)
    assert len(selected) == 2
    evidence['controlled_comparisons']['mesh3_feedback_candidates'] = {
        'source_status': 'workspace/' + (mesh / 'STATUS.json').as_posix(),
        'source_status_sha256': records[(mesh / 'STATUS.json').as_posix()]['sha256'],
        'selected_iteration': status['selected_iteration'],
        'same_source_topology_engine': True,
        'scope': 'Both iterations have actual Native subset resource validation. Only selected iteration 1 carries the final exact boundary proof; this is not a controlled causal ablation of feedback versus seed.',
        'candidates': selected,
    }
    evidence['controlled_comparisons']['aes_recorded_tradeoffs'] = read('work/final_aes_v5_cut_category_tradeoff_digest_20261015.json')
    evidence['controlled_comparisons']['aes_tradeoff_notes'] = read('RESULTS FOR J4/AES_SELECTED_MAPS_AND_ALTERNATIVES.txt', 'text')
    evidence['release_source_tests'] = read('RapidWright/build/partition-python-tests/portability_test_receipt.json')
    evidence['release_validation'] = read('RapidWright/doc/partition/evidence/release_validation.json')
    for relative in [
        'work/native_canary_export_only_peer_20261015/independent_review.json',
        'work/new_policy_j4_publisher_joint_leaf_parent_peer_v1/publisher_canary_export_independent_review.json',
        'work/new_policy_j4_publisher_joint_leaf_parent_peer_v1/data_canary_export_independent_review.json',
    ]:
        evidence['historical_source_reviews'].append({'report_alias': 'workspace/' + relative, 'record': read(relative)})
    # This assertion catches accidental disclosure of workstation-specific user paths.
    text = json.dumps(evidence, indent=2, ensure_ascii=True, sort_keys=False) + '\n'
    if re.search(r'(?i)(?:[A-Z]:[/\\]+Users[/\\]|/mnt/[a-z]/Users/)', text):
        raise ValueError('Unnormalized absolute user path in evidence')
    args.output.write_text(text, encoding='utf-8', newline='\n')
    print(json.dumps({'output': str(args.output), 'cases': len(evidence['cases']),
                      'bounded_report_count': len(records), 'bytes': len(text.encode()),
                      'sha256': hashlib.sha256(text.encode()).hexdigest()}))


if __name__ == '__main__':
    main()
