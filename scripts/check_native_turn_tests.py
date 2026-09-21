#!/usr/bin/env python3
"""Validate actual upstream TURN results, including every redirect regression."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(path: Path) -> int:
    root = ET.parse(path).getroot()
    cases = root.findall('.//testcase')
    names = {(case.get('classname'), case.get('name')) for case in cases}
    required = {
        ('TurnPortTest', 'TestTurnAlternateServer' + suffix)
        for suffix in ('UDP', 'TCP', 'TLS', 'V4toV6UDP', 'V4toV6TCP', 'V4toV6TLS',
                       'PingPongUDP', 'PingPongTCP', 'PingPongTLS',
                       'DetectRepetitionUDP', 'DetectRepetitionTCP', 'DetectRepetitionTLS',
                       'LoopbackUdpIpv4', 'LoopbackUdpIpv6', 'LoopbackTcpIpv4',
                       'LoopbackTcpIpv6', 'LoopbackTlsIpv4', 'LoopbackTlsIpv6')
    }
    if not cases or not required <= names or len(names) != len(cases):
        raise ValueError('Missing or duplicate TURN regression results')
    if int(root.get('tests', '0')) != len(cases):
        raise ValueError('Inconsistent native test count')
    for node in (root, *root.findall('testsuite')):
        if any(int(node.get(key, '0')) for key in ('failures', 'errors')):
            raise ValueError('Native suite failed')
    for case in cases:
        if (case.get('status') != 'run' or case.get('result') != 'completed'
                or any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))):
            raise ValueError('Native test failed or did not execute')
    return len(cases)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    print(f'PASS {validate(args.report)} executed native TURN tests; not the full upstream suite')
