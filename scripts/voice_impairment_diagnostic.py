"""Failure-only synthetic queue metadata; never a media acceptance receipt."""
import json
import re
import time


def summarize(raw):
    if not isinstance(raw, str) or len(raw) > 4096:
        return {'status': 'UNAVAILABLE'}
    sent = re.search(r'\bSent (\d+) bytes (\d+) pkt \(dropped (\d+), overlimits (\d+) requeues (\d+)\)', raw)
    backlog = re.search(r'\bbacklog (\d+)b (\d+)p\b', raw)
    if sent is None or backlog is None:
        return {'status': 'UNAVAILABLE'}
    values = [int(value) for value in (*sent.groups(), *backlog.groups())]
    if any(value > 2**63 - 1 for value in values):
        return {'status': 'UNAVAILABLE'}
    configured = all(re.search(pattern, raw) for pattern in
        (r'\bnetem\b', r'\blimit 20\b', r'\bdelay 80(?:\.0)?ms\b',
         r'\bloss 2%(?:\s|$)', r'\brate 128Kbit\b'))
    return {'status': 'OBSERVED', 'configuredProfile': configured,
            **dict(zip(('sentBytes', 'sentPackets', 'droppedPackets', 'overlimits',
                        'requeues', 'backlogBytes', 'backlogPackets'), values))}


def record(reports, serials, collect, *, clock=time.monotonic_ns):
    """Observe both endpoints before cleanup; callers must re-raise their failure.

    Collection exceptions are never serialized. Missing diagnostics stay explicitly
    unavailable and cannot replace or suppress the scenario's original failure.
    """
    observed = clock()
    rows = []
    for role, serial in zip(('A', 'B'), serials):
        try:
            value = summarize(collect(serial))
        except Exception:
            value = {'status': 'UNAVAILABLE'}
        rows.append({'role': role, **value})
    (reports / 'impairment-failure.json').write_text(json.dumps({
        'version': 1, 'observedMonotonicNanos': observed,
        'scope': 'synthetic wlan0 qdisc before teardown; NOT acceptance',
        'endpoints': rows}, indent=2) + '\n')
