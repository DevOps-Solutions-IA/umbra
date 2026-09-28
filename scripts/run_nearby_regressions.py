#!/usr/bin/env python3
"""Three bounded runs per flavor, preserving failures; never TCP substitution."""
import argparse
import json
from pathlib import Path
import sys
from run_video_matrix import execute

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emergency',action='store_true');parser.add_argument('--pairing',type=Path,required=True);parser.add_argument('--reports',type=Path,required=True)
    args=parser.parse_args();paired=json.loads(args.pairing.read_text());args.reports.mkdir(parents=True,exist_ok=True)
    commands=[]
    if args.emergency:
        for flavor in ('connected','offline'):
            name=f'emergency-nearby-{flavor}'
            commands.append((name,[sys.executable,'scripts/run_bluetooth_emulation.py','--emergency',
                '--serial-a',paired['serial_a'],'--serial-b',paired['serial_b'],
                '--address-a',paired['address_a'],'--address-b',paired['address_b'],
                '--flavor',flavor,'--log-dir',str(args.reports/name)]))
        return execute(commands,args.reports)
    for repetition in range(1,4):
        for flavor in ('connected','offline'):
            name=f'nearby-{flavor}-{repetition}'
            commands.append((name,[sys.executable,'scripts/run_bluetooth_emulation.py',
                '--serial-a',paired['serial_a'],'--serial-b',paired['serial_b'],
                '--address-a',paired['address_a'],'--address-b',paired['address_b'],
                '--flavor',flavor,'--log-dir',str(args.reports/name)]))
    for flavor in ('connected','offline'):
        name=f'nearby-{flavor}-unadmitted'
        commands.append((name,[sys.executable,'scripts/run_bluetooth_emulation.py',
            '--serial-a',paired['serial_a'],'--serial-b',paired['serial_b'],
            '--address-a',paired['address_a'],'--address-b',paired['address_b'],
            '--flavor',flavor,'--unadmitted-dialer','--log-dir',str(args.reports/name)]))
    return execute(commands,args.reports)

if __name__=='__main__':sys.exit(main())
