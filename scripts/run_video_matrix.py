#!/usr/bin/env python3
"""Run every media case, retain individual failures, and fail the job if any fails."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

SCENARIOS=('audio','direct-blocked','expired-auth','allocation-expiry','invalid-auth','unreachable',
           'turn-loss','trust-loss','lock','credential-expiry','device-revoked','storage-failure',
           'force-stop','permission-revoked','unauthorized-redirect','wrong-fingerprint','receive-only','degraded-network','camera-denied','camera-permission-revoked')

def cases():
    return ([(name,['--scenario',name]) for name in SCENARIOS]
            + [('tls-'+name,['--turn-tls',name]) for name in ('valid','wrong-name','expired','untrusted')]
            + [('tls-'+name,['--turn-tls','valid','--scenario',name]) for name in ('invalid-auth','unreachable','unauthorized-redirect','turn-loss')]
            + [('ipv6-udp',['--turn-ipv6']),('ipv6-tls',['--turn-ipv6','--turn-tls','valid'])])

def execute(commands,report):
    results=[]
    for name,command in commands:
        start=time.monotonic()
        with (report/(name+'-driver.log')).open('w') as log:
            result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
        results.append({'case':name,'exitCode':result.returncode,'seconds':round(time.monotonic()-start,3),
                        'status':'PASS' if result.returncode==0 else 'FAIL'})
        (report/'matrix.json').write_text(json.dumps(results,indent=2)+'\n')
        print(name+': '+results[-1]['status'],flush=True)
    return 1 if not results or any(row['exitCode']!=0 for row in results) else 0

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--a',required=True);parser.add_argument('--b',required=True)
    parser.add_argument('--reports',type=Path,required=True);parser.add_argument('--optimized',action='store_true')
    args=parser.parse_args();args.reports.mkdir(parents=True,exist_ok=True)
    optimized=['--optimized'] if args.optimized else []
    commands=[('camera-provider',[sys.executable,'scripts/run_camera_provider.py','--serial',args.a,*optimized,'--reports',str(args.reports/'camera-provider')])]
    for name,options in cases():
        commands.append((name,[sys.executable,'scripts/run_voice_integration.py','--a',args.a,'--b',args.b,*optimized,'--video',*options,'--reports',str(args.reports/name)]))
    return execute(commands,args.reports)

if __name__=='__main__':sys.exit(main())
