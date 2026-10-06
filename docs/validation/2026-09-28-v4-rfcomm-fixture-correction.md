# Master v4: RFCOMM fixture correction (2026-09-28)

Read the complete local `UMBRA_MASTER_CODEX_v4_MOVIL_FISICO.md` (548 lines,
SHA-256 59af8c3e3ff5d0291b7f1a244559c54dcbc693bcad64d0227000443a374187ed).
It supersedes v3; A/B/C remain open. No source master or Claude UI was modified.

Remote/local checkpoint cb2df354fced19ad5d6721df4964b80c4a383375 was confirmed.
Its CI tested integration checkout 4fd17ddb515fe40465882bf83ea32dfed6153fdc.
Seven workflows passed: privacy, vault, admission, startup, voice R8, video,
modulation. Verify, focused and emergency failed. These are checkpoint results,
not evidence for this correction or completion of the master.

Downloaded failing artifacts 10989725515, 10991240044, 10989076818.
Both regular RFCOMM endpoints reached PDF preparation and failed with
`SecurityException: Coordinated lock unavailable`: the memory fixture did not
supply the emergency coordinator required by the isolated PDF parser. The
existing SQLite lab adapter already supplies this coordinator. Both paths now
use that adapter, with disposable synthetic records, and close it in finally.
This does not change the production Vault, Bluetooth or parser authorization.

Emergency RFCOMM instead reached the obsolete two-object inventory assertion.
The fixture now sends PNG, AAC and PDF. It checks the exact allowed formats,
rejects duplicate objects of EACH format, and requires all three at completion.
It does not merely raise a count ceiling. Existing decoding, consume/reopen,
challenge, trust, duplicate delivery and receipt checks remain mandatory.
The 45-second delivery deadline is unchanged. Both debug instrumentation APKs
and lint built successfully locally; actual RFCOMM rerun remains pending CI.

Emergency R8 job 109070148966 failed BEFORE tests while sdkmanager prepared
Android Emulator: `Error on ZipFile unknown archive`. Its source/download cause
is not established; no dependency pin, timeout or check was relaxed.

ADB preflight: WSL2, official SDK platform-tools 36.0.0-13206524 on Linux and
Windows. Both `adb devices -l` returned an empty list. No server was killed,
no USB configuration changed and nothing installed. Physical execution awaits
a connected, RSA-authorized phone. One phone will not validate two physical peers.
The prior recoverable cb2df35 backup in the user's Downloads is preserved.
