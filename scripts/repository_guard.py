#!/usr/bin/env python3
"""Conservative pre-publication hygiene checks, not a full secret scanner or audit.

Never prints a matched secret. Runtime secrets must live outside the repository.
The Git-history option also checks reachable blobs, not just the current tree.
"""
from __future__ import annotations
import argparse
import hashlib
from dataclasses import dataclass
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {'.git', '.venv', 'venv', '__pycache__', '.pytest_cache', '.gradle',
             '.umbra-tools', 'build', 'node_modules', '.idea', '.vscode', '.run', '.codex-log'}
MAX_BYTES = 8_000_000
# One reviewed source-built dependency, not a global size-limit increase. All
# current/history instances must match BOTH exact size and SHA-256; no wildcard.
APPROVED_NATIVE = {
    'android/vendor/webrtc-150.7871.01-umbra.5.aar':
        (23742824, '25f2abebc99e2e109cff83a428080408843fda51a9cdadb5c081d694c92b7620'),
    'android/vendor/webrtc-150.7871.01-umbra.3.aar':
        (23728885, '5743b0e47574a7d8bad047b00fdef8f49e56e41c944a12542282e2b91ccf9433'),
    'android/vendor/webrtc-150.7871.01-umbra.1.aar':
        (23728073, 'bbc5675f91b31f901e1a482b00991a36ac2b3d912d2782b80e1cc1b756b1c413'),
}

def permitted_size(name: str, size: int) -> bool:
    return size <= MAX_BYTES or (name in APPROVED_NATIVE and size == APPROVED_NATIVE[name][0])
PATTERNS = (
    ('private key', re.compile(rb'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----')),
    ('GitHub credential', re.compile(rb'\bgh[pousr]_[A-Za-z0-9]{20,}\b')),
    ('GitHub fine-grained credential', re.compile(rb'\bgithub_pat_[A-Za-z0-9_]{30,}\b')),
    ('AWS access key', re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b')),
    ('OpenAI project credential', re.compile(rb'\bsk-proj-[A-Za-z0-9_-]{24,}\b')),
    ('credential-bearing URL', re.compile(rb'https?://[^\s/"\'<>:]+:[^\s/"\'<>@]+@')),
)

@dataclass(frozen=True)
class Finding:
    path: str
    reason: str


def prohibited_name(name: str) -> bool:
    p = Path(name)
    n = p.name.lower()
    if n == '.env' or (n.startswith('.env.') and not n.endswith('.example')):
        return True
    if n in {'credentials.json', 'service-account.json', 'id_rsa', 'id_ed25519', 'local.properties'}:
        return True
    if any(n.endswith(s) for s in ('.pem', '.key', '.jks', '.keystore', '.p12', '.pfx',
                                  '.apk', '.aab', '.db', '.db-wal', '.db-shm', '.sqlite3',
                                  '.sqlite3-wal', '.sqlite3-shm')):
        return True
    return False


def check_bytes(name: str, data: bytes) -> list[Finding]:
    results = []
    if prohibited_name(name):
        results.append(Finding(name, 'sensitive/operational filename is not allowed'))
    if not permitted_size(name,len(data)):
        results.append(Finding(name, 'file exceeds source delivery size limit'))
    if name in APPROVED_NATIVE and (len(data),hashlib.sha256(data).hexdigest()) != APPROVED_NATIVE[name]:
        results.append(Finding(name, 'reviewed native artifact integrity mismatch'))
    for reason, pattern in PATTERNS:
        if pattern.search(data):
            results.append(Finding(name, reason + ' pattern found; value withheld'))
    return results


def scan(root: Path) -> tuple[int, list[Finding]]:
    root = root.resolve()
    count, findings = 0, []
    for folder, dirs, files in os.walk(root, followlinks=False):
        # Report links even if their name would otherwise be excluded.
        for d in dirs:
            p = Path(folder) / d
            if p.is_symlink() and d not in SKIP_DIRS:
                findings.append(Finding(str(p.relative_to(root)), 'symbolic directory link not allowed'))
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS and not (Path(folder)/d).is_symlink())
        for f in sorted(files):
            p = Path(folder) / f
            name = p.relative_to(root).as_posix()
            if f in {'.DS_Store', 'Thumbs.db'} or f.endswith('.pyc'):
                continue
            count += 1
            if p.is_symlink():
                findings.append(Finding(name, 'symbolic file link not allowed'))
                continue
            if not permitted_size(name,p.stat().st_size):
                findings.append(Finding(name, 'file exceeds source delivery size limit'))
                continue
            findings.extend(check_bytes(name, p.read_bytes()))
    return count, findings


def scan_history(root: Path) -> tuple[int, list[Finding]]:
    def git(*args: str) -> bytes:
        return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL)
    rows = git('rev-list', '--objects', '--all').splitlines()
    if len(rows) > 20_000:
        raise RuntimeError('History too large for this guard; use a dedicated audited secret scanner.')
    findings: list[Finding] = []
    count = 0
    for row in rows:
        fields = row.split(b' ', 1)
        if len(fields) != 2:
            continue
        sha = fields[0].decode('ascii')
        name = fields[1].decode('utf-8', errors='replace')
        if git('cat-file', '-t', sha).strip() != b'blob':
            continue
        count += 1
        label = 'history:' + sha[:12] + ':' + name
        if not permitted_size(name,int(git('cat-file', '-s', sha))):
            findings.append(Finding(label, 'historical blob exceeds size limit'))
            continue
        for issue in check_bytes(name, git('cat-file', 'blob', sha)):
            findings.append(Finding(label, issue.reason))
    return count, findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--git-history', action='store_true')
    args = parser.parse_args()
    count, findings = scan(args.root)
    print(f'Current source files checked: {count}')
    if args.git_history:
        n, previous = scan_history(args.root)
        findings.extend(previous)
        print(f'Reachable historical blobs checked: {n}')
    for issue in findings:
        print(f'BLOCKED {issue.path}: {issue.reason}', file=sys.stderr)
    if findings:
        return 1
    print('No prohibited filenames or supported credential patterns found. Not a security audit.')
    return 0

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, subprocess.SubprocessError, RuntimeError) as exc:
        print(f'Guard could not complete: {type(exc).__name__}. No publication approved.', file=sys.stderr)
        raise SystemExit(2)
