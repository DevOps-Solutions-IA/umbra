#!/usr/bin/env python3
"""Bounded boot supervision: a live emulator AND connected adb AND boot completion."""
import argparse
import os
from pathlib import Path
import subprocess
import time


def wait_boot(pid, serial, adb, log, timeout, *, alive=None, run=subprocess.run,
              now=time.monotonic, sleep=time.sleep):
    def process_alive():
        try:
            os.kill(pid, 0)
            stat = Path(f'/proc/{pid}/stat')
            return not stat.exists() or stat.read_text().split(') ', 1)[1][0] != 'Z'
        except ProcessLookupError:
            return False
    alive = alive or process_alive
    deadline = now() + timeout
    last = 'adb not connected'
    try:
        while now() < deadline:
            if not alive():
                raise RuntimeError('emulator process died before boot')
            try:
                devices = run([adb, 'devices'], capture_output=True, text=True, timeout=5)
                print(devices.stdout, flush=True)
                connected = devices.returncode == 0 and any(line.split() == [serial, 'device'] for line in devices.stdout.splitlines())
                if connected:
                    result = run([adb, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=5)
                    if result.returncode == 0 and result.stdout.strip() == '1' and alive():
                        print(f'PASS boot: {serial}, live PID {pid}', flush=True)
                        return
                    last = 'adb connected, sys.boot_completed not 1'
                else:
                    last = 'adb not connected'
            except subprocess.TimeoutExpired:
                last = 'adb command timed out'
            sleep(min(2, max(0, deadline - now())))
        raise RuntimeError(f'boot timeout ({timeout}s): {last}')
    except Exception:
        print(f'Emulator diagnostics: {log}', flush=True)
        print(Path(log).read_text(errors='replace') if Path(log).exists() else 'emulator.log missing', flush=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--log', required=True)
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 300:
        parser.error('timeout must be 1..300 seconds')
    wait_boot(args.pid, args.serial, str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), args.log, args.timeout)
