"""Install the exact local debug APK only when its deployed bytes differ.

Avoid accumulating repeated staged 500MB installs across native media scenarios.
A matching installed digest skips installation, never instrumentation or acceptance.
"""
import hashlib
from pathlib import Path
import re
import subprocess


def ensure_apk(adb: str, serial: str, package: str, apk: Path) -> None:
    if package not in {"app.umbra.privatechat.dev", "app.umbra.privatechat.dev.test"}:
        raise ValueError("Only the isolated connected debug UID is supported")
    with apk.open("rb") as stream:
        digest=hashlib.file_digest(stream,"sha256").hexdigest()
    result=subprocess.run([adb,"-s",serial,"shell","pm","path",package],capture_output=True,text=True,timeout=20)
    if result.returncode not in (0,1): raise RuntimeError("Cannot inspect installed debug APK")
    paths=result.stdout.strip().splitlines()
    if paths:
        if len(paths)!=1 or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_~+=/.-]+/base\.apk",paths[0]):
            raise RuntimeError("Ambiguous installed debug APK path")
        deployed=paths[0].removeprefix("package:")
        check=subprocess.run([adb,"-s",serial,"shell","sha256sum",deployed],capture_output=True,text=True,timeout=30)
        if check.returncode!=0 or not re.match(r"^[a-f0-9]{64}\s",check.stdout):
            raise RuntimeError("Cannot verify installed debug APK digest")
        if check.stdout.split()[0]==digest: return
    install=subprocess.run([adb,"-s",serial,"install","-r",str(apk)],capture_output=True,text=True,timeout=120)
    if install.returncode!=0 or "Success" not in install.stdout:
        raise RuntimeError("Exact debug APK installation failed: "+install.stdout.strip()[:300])
