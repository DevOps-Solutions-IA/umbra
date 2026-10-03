#!/usr/bin/env python3
"""Accept network evidence only after the CI-owned capture daemon exited cleanly.

Scenario staging is not PASS. Raw captures remain outside report artifacts and
are removed by the owning CI shell. This tool publishes counts/closure metadata.
"""
import argparse
import json
import math
import os
from pathlib import Path
import shutil
import tempfile
from pcap_integrity import inspect_pcap
from voice_network_evidence import summarize, summarize_tls, summarize_ipv6_turn, count


def require_closed(owner):
    if (owner.get('state') != 'CLOSED' or type(owner.get('pid')) is not int or owner['pid'] <= 1
            or type(owner.get('exitCode')) is not int or owner['exitCode'] != 0
            or type(owner.get('captureErrorCount')) is not int or owner['captureErrorCount'] != 0
            or type(owner.get('stopRequestedMonotonicNs')) is not int
            or type(owner.get('closedMonotonicNs')) is not int
            or owner['closedMonotonicNs'] < owner['stopRequestedMonotonicNs']):
        raise RuntimeError('Capture owner has no successful closure receipt')
    try:
        os.kill(owner['pid'], 0)
    except ProcessLookupError:
        return
    raise RuntimeError('Capture owner is still active; evidence not finalized')


def immutable_snapshot(source, target, owner):
    require_closed(owner)
    root = Path(owner['captureRoot']).resolve(strict=True)
    source = source.resolve(strict=True)
    if not source.is_relative_to(root) or not source.is_file():
        raise RuntimeError('Capture is outside the owned laboratory')
    before = source.stat()
    shutil.copyfile(source, target)
    after = source.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise RuntimeError('Closed capture changed during snapshot')
    details = inspect_pcap(target)
    target.chmod(0o400)
    return details


def analyze(snapshot, plan, index):
    scenario, address = plan['scenario'], plan['turnAddress']
    since, until = plan['since'], plan['until']
    rejection = plan['rejection']
    if plan['ipv6']:
        if scenario in ('unauthorized-redirect', 'unreachable'):
            raise RuntimeError('Unsupported IPv6 evidence scenario')
        return summarize_ipv6_turn(snapshot, address, plan['addresses'][index], plan['addresses6'][index],
                                   plan['relayPort'], since, until, tls=plan['tls'], require_turn=index == 0 or not rejection)
    if scenario == 'unauthorized-redirect':
        redirected = count(snapshot, f"ip and src host {plan['addresses'][index]} and (udp or tcp) and dst host {address} and dst port 3479", since, until)
        if redirected != 0:
            raise RuntimeError('Native allocator contacted an unauthorized TURN redirect')
        if plan['tls']:
            checked = summarize_tls(snapshot, address, plan['relayPort'], since, until,
                                    require_turn=index == 0, source_address=plan['addresses'][index])
        else:
            checked = summarize(snapshot, address, since=since, until=until, require_turn=index == 0)
        return {**checked, 'unapprovedTurnPackets': redirected, 'redirectPolicy': 'REJECTED_BEFORE_IO'}
    if plan['tls']:
        return summarize_tls(snapshot, address, plan['relayPort'], since, until,
                             require_turn=index == 0 or not rejection, source_address=plan['addresses'][index],
                             turn_port=5348 if scenario == 'unreachable' else 5349)
    return summarize(snapshot, address, turn_port=3479 if scenario == 'unreachable' else 3478,
                     since=since, until=until,
                     require_turn=index == 0 or scenario not in ('expired-auth', 'invalid-auth', 'unreachable'))


def finalize(reports, owner_path):
    from run_voice_integration import require_completed_run
    reports = reports.resolve(strict=True)
    owner = json.loads(owner_path.read_text())
    require_closed(owner)
    pending = []
    for candidate in sorted(reports.rglob('network-pending.json')):
        status = json.loads(candidate.read_text()).get('status')
        if status == 'FINALIZED':
            require_completed_run(candidate.parent / 'voice-evidence.json')
        elif status == 'PENDING_CAPTURE_FINALIZATION':
            pending.append(candidate)
        else:
            raise RuntimeError('Unknown capture evidence status')
    if not pending:
        raise RuntimeError('No staged capture evidence; empty run cannot pass')
    completed = []
    for path in pending:
        plan = json.loads(path.read_text())
        if (plan.get('status') != 'PENDING_CAPTURE_FINALIZATION' or plan.get('ownerPid') != owner['pid']
                or len(plan.get('capturePaths', [])) != 2
                or not all(type(plan.get(k)) in (int, float) and math.isfinite(plan[k]) for k in ('since', 'until'))
                or not plan['since'] < plan['until'] <= owner['stoppedWallTime']):
            raise RuntimeError('Invalid or mismatched capture staging receipt')
        output = path.parent / 'voice-evidence.json'
        if output.exists():
            raise RuntimeError('Refusing to overwrite existing acceptance evidence')
        network, framing = [], []
        try:
            with tempfile.TemporaryDirectory(prefix='umbra-closed-capture-') as temporary:
                for index, source in enumerate(plan['capturePaths']):
                    snapshot = Path(temporary) / f'endpoint-{index}.pcap'
                    framing.append(immutable_snapshot(Path(source), snapshot, owner))
                    network.append(analyze(snapshot, plan, index))
            voice = plan['voice']
            voice['network'] = network
            voice['captureFinalization'] = {'ownerPid': owner['pid'], 'exitCode': owner['exitCode'],
                'stopRequestedMonotonicNs': owner['stopRequestedMonotonicNs'],
                'closedMonotonicNs': owner['closedMonotonicNs'], 'immutableSnapshots': framing}
            candidate = output.with_name('voice-evidence-unaccepted.json')
            candidate.write_text(json.dumps(voice, indent=2) + '\n')
            require_completed_run(candidate)
            candidate.replace(output)
        except Exception as failure:
            # Never publish packet text, paths or unknown exception messages.
            (path.parent / 'capture-failure.json').write_text(json.dumps({
                'status': 'FAIL', 'layer': 'CAPTURE_FINALIZATION', 'errorType': type(failure).__name__,
                'reason': getattr(failure, 'reason', 'PARSER_OR_CLOSURE_FAILURE')}) + '\n')
            raise
        plan['status'] = 'FINALIZED'
        path.write_text(json.dumps(plan, indent=2) + '\n')
        completed.append(path.parent.relative_to(reports).as_posix())
    for matrix_path in reports.rglob('matrix.json'):
        rows = json.loads(matrix_path.read_text())
        for row in rows:
            if row.get('status') == 'PENDING_CAPTURE_FINALIZATION':
                require_completed_run(matrix_path.parent / row['case'] / 'voice-evidence.json')
                row['status'] = 'PASS'
        matrix_path.write_text(json.dumps(rows, indent=2) + '\n')
        if not rows or any(row.get('status') != 'PASS' or row.get('exitCode') != 0 for row in rows):
            raise RuntimeError('Media matrix has failed or incomplete cases')
    receipt = {'status': 'PASS', 'cases': completed, 'ownerPid': owner['pid'], 'captureExitCode': 0}
    (reports / 'capture-finalization.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('PASS finalized network evidence for ' + str(len(completed)) + ' scenarios')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--owner', type=Path, required=True)
    args = parser.parse_args()
    finalize(args.reports, args.owner)


if __name__ == '__main__': main()
