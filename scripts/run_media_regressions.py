#!/usr/bin/env python3
"""Bounded, retained repetitions; any failure fails the whole focused run."""
import argparse
from pathlib import Path
import sys
from run_video_matrix import execute

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--a',required=True);parser.add_argument('--b',required=True)
    parser.add_argument('--reports',type=Path,required=True);parser.add_argument('--optimized',action='store_true')
    args=parser.parse_args();args.reports.mkdir(parents=True,exist_ok=True)
    commands=[]
    for scenario in ('video-stop-race','camera-permission-revoked'):
        for repetition in range(1,4):
            name=f'{scenario}-{repetition}'
            commands.append((name,[sys.executable,'scripts/run_voice_integration.py','--a',args.a,'--b',args.b,
                *(['--optimized'] if args.optimized else []),'--video','--scenario',scenario,'--reports',str(args.reports/name)]))
    return execute(commands,args.reports)

if __name__=='__main__':sys.exit(main())
