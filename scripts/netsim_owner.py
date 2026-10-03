#!/usr/bin/env python3
"""Identity and shutdown receipts for one shell-owned netsimd; never discovers or kills unrelated daemons."""
import argparse
import configparser
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import time


def process_identity(pid):
    if type(pid) is not int or pid <= 1:
        raise ValueError('Invalid owned PID')
    root = Path('/proc') / str(pid)
    fields = (root / 'stat').read_text().rsplit(')', 1)[1].split()
    return {'pid': pid, 'parentPid': int(fields[1]), 'startTicks': int(fields[19]), 'processState': fields[0]}


def same_process(state, *, allow_zombie=False):
    current = process_identity(state['pid'])
    if any(current[key] != state[key] for key in ('pid', 'parentPid', 'startTicks')):
        raise RuntimeError('Owned daemon identity changed')
    if current['processState'] == 'Z':
        if allow_zombie:
            return current
        raise RuntimeError('Owned daemon exited before requested shutdown')
    actual = (Path('/proc') / str(state['pid']) / 'exe').resolve(strict=True)
    if actual != Path(state['executable']):
        raise RuntimeError('Owned daemon executable changed')
    return current


def atomic_json(path, value):
    path = Path(path); temporary = path.with_name(path.name + '.tmp')
    with temporary.open('x') as stream:
        json.dump(value, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    temporary.replace(path)


def ready(pid, parent_pid, executable, capture_root, state_path, *, timeout=30):
    executable = Path(executable).resolve(strict=True)
    capture_root = Path(capture_root).resolve(strict=True)
    runtime = capture_root / 'runtime'
    if not runtime.is_dir() or runtime.is_symlink() or runtime.stat().st_uid != os.getuid() or runtime.stat().st_mode & 0o077:
        raise RuntimeError('Private netsim runtime directory required')
    deadline = time.monotonic() + timeout
    state = None
    while time.monotonic() < deadline:
        current = process_identity(pid)
        if current['parentPid'] != parent_pid or current['processState'] == 'Z':
            raise RuntimeError('Daemon is not a live child of the owning shell')
        actual = (Path('/proc') / str(pid) / 'exe').resolve(strict=True)
        if actual != executable:
            time.sleep(0.05); continue  # The just-forked shell may not have exec'd yet.
        if state is None:
            with executable.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            state = {**current, 'executable': str(executable), 'exeSha256': digest,
                     'captureRoot': str(capture_root), 'startedMonotonicNs': time.monotonic_ns()}
            atomic_json(state_path, state)
        same_process(state)
        candidates = list(runtime.glob('netsim*.ini'))
        if len(candidates) > 1:
            raise RuntimeError('Ambiguous private netsim discovery file')
        if candidates:
            path = candidates[0]
            if path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_size > 4096:
                raise RuntimeError('Unsafe private netsim discovery file')
            parser = configparser.ConfigParser(interpolation=None)
            try:
                parser.read_string('[netsim]\n' + path.read_text())
                owner = parser.getint('netsim', 'pid'); port = parser.getint('netsim', 'grpc.port')
            except (configparser.Error, ValueError):
                time.sleep(0.05); continue  # Initial INI write is not itself an atomic readiness event.
            if owner != pid or not 1 <= port <= 65535:
                raise RuntimeError('Private discovery points to a different process')
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=0.2):
                    same_process(state)
                    state['readyMonotonicNs'] = time.monotonic_ns()
                    atomic_json(state_path, state)
                    return state
            except (ConnectionError, TimeoutError, OSError):
                pass
        time.sleep(0.05)
    raise RuntimeError('Owned netsim readiness deadline exceeded')


def stop(state_path, *, force=False):
    state = json.loads(Path(state_path).read_text())
    same_process(state)
    if not force:
        state['stopRequestedMonotonicNs'] = time.monotonic_ns()
        atomic_json(state_path, state)
    os.kill(state['pid'], signal.SIGKILL if force else signal.SIGTERM)


def wait_exit(state_path, *, timeout=30):
    state = json.loads(Path(state_path).read_text())
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if same_process(state, allow_zombie=True)['processState'] == 'Z':
                return
        except FileNotFoundError:
            return
        time.sleep(0.05)
    raise RuntimeError('Owned netsim did not exit by shutdown deadline')


def capture_errors(log_path):
    """Only a fixed counter escapes the private daemon log; no raw text or packet metadata."""
    path = Path(log_path)
    if not path.is_file() or path.is_symlink() or not 0 < path.stat().st_size <= 16 * 1024 * 1024:
        raise RuntimeError('Missing or invalid private capture diagnostics')
    count = 0
    with path.open(encoding='utf-8', errors='strict') as stream:
        for line in stream:
            lower = line.lower()
            relevant = any(word in lower for word in ('pcap', 'capture', 'writer', 'flush', 'write'))
            error = any(word in lower for word in ('error', 'failed', 'failure', 'could not', "couldn't")) or bool(re.search(r'\bE\s+\d\d[-:]|\bERROR\b', line))
            if relevant and error:
                count += 1
    return count


def closed(state_path, receipt_path, exit_code, log_path, *, avds_clean=True):
    state = json.loads(Path(state_path).read_text())
    try:
        process_identity(state['pid'])
    except FileNotFoundError:
        pass
    else:
        raise RuntimeError('Owned PID must be reaped before closure receipt')
    closed_at = time.monotonic_ns()
    errors = capture_errors(log_path)
    clean = (type(exit_code) is int and exit_code == 0 and avds_clean
             and errors == 0
             and state.get('readyMonotonicNs', 0) > 0
             and state.get('stopRequestedMonotonicNs', 0) >= state.get('readyMonotonicNs', 0))
    receipt = {'state': 'CLOSED' if clean else 'FAILED', 'pid': state['pid'], 'exitCode': exit_code,
               'captureRoot': state['captureRoot'], 'stopRequestedMonotonicNs': state.get('stopRequestedMonotonicNs'),
               'closedMonotonicNs': closed_at, 'stoppedWallTime': time.time(), 'exeSha256': state['exeSha256'],
               'startTicks': state['startTicks'], 'graceful': clean, 'captureErrorCount': errors}
    atomic_json(receipt_path, receipt)
    if not clean:
        raise RuntimeError('Capture owner or dependent AVD did not close cleanly')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('ready', 'check', 'stop', 'kill', 'wait-exit', 'closed'))
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--pid', type=int); parser.add_argument('--parent', type=int)
    parser.add_argument('--executable', type=Path); parser.add_argument('--capture-root', type=Path)
    parser.add_argument('--receipt', type=Path); parser.add_argument('--exit-code', type=int)
    parser.add_argument('--log', type=Path)
    parser.add_argument('--avds-failed', action='store_true')
    parser.add_argument('--timeout', type=float, default=30)
    args = parser.parse_args()
    if args.action == 'ready':
        ready(args.pid, args.parent, args.executable, args.capture_root, args.state, timeout=args.timeout)
    elif args.action == 'check':
        same_process(json.loads(args.state.read_text()))
    elif args.action in ('stop', 'kill'):
        stop(args.state, force=args.action == 'kill')
    elif args.action == 'wait-exit':
        wait_exit(args.state, timeout=args.timeout)
    else:
        closed(args.state, args.receipt, args.exit_code, args.log, avds_clean=not args.avds_failed)


if __name__ == '__main__':
    main()
