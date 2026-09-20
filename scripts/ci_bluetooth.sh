#!/usr/bin/env bash
# Run inside the ci_emulator.sh owning shell after single-device validation.
set -euo pipefail
free -m | tee "$UMBRA_DEVICE_REPORTS/memory-before-bluetooth.txt"
umbra_start_avd umbra-ci-b 5556
python scripts/pair_bluetooth_emulators.py --serial-a emulator-5554 --serial-b emulator-5556 \
  --log-dir "$UMBRA_DEVICE_REPORTS/pairing"
python - "$UMBRA_DEVICE_REPORTS" <<'PYTHON'
import json
from pathlib import Path
import subprocess
import sys
reports = Path(sys.argv[1])
paired = json.loads((reports / 'pairing/pairing.json').read_text())
for flavor in ('connected', 'offline'):
    subprocess.run([sys.executable, 'scripts/run_bluetooth_emulation.py',
        '--serial-a', paired['serial_a'], '--serial-b', paired['serial_b'],
        '--address-a', paired['address_a'], '--address-b', paired['address_b'],
        '--flavor', flavor, '--log-dir', str(reports / ('bluetooth-' + flavor))], check=True, timeout=360)
PYTHON
free -m | tee "$UMBRA_DEVICE_REPORTS/memory-after-bluetooth.txt"
ps -eo pid,rss,args > "$UMBRA_DEVICE_REPORTS/process-memory.txt"
