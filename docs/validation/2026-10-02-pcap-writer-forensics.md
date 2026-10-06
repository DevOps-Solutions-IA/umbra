# PCAP writer forensics — 2026-10-02

## Scope and conclusion

VERIFIED: a bounded local experiment with the exact emulator distribution used by CI reproduced truncated PCAP snapshots while its native netsimd writer remained active. Three of ten fixed live snapshots failed tcpdump parsing; the final snapshot after graceful daemon termination parsed successfully. This demonstrates a real writer/copy race in the laboratory. It does **not** establish the exact cause of the historical CI failures, whose tcpdump stderr and raw PCAP were not retained.

This is synthetic Bluetooth HCI traffic through netsimd's loopback HCI interface, without an Android emulator, KVM, Wi-Fi media, or physical Bluetooth hardware. It exercises the shipped native PCAP writer; it is not a voice/video or Bluetooth transport acceptance test.

## Distribution provenance

VERIFIED: CI emulator logs identify emulator 37.2.12.0, build 16428233. The official Google repository manifest at <https://dl.google.com/android/repository/repository2-3.xml> identified the Linux archive <https://dl.google.com/android/repository/emulator-linux_x64-16428233.zip>, size 349654171 bytes, published SHA-1 `cd7362ea55dfb86a418958138dc396e74165dd01`. The downloaded archive matched that checksum. Its locally calculated SHA-256 is `b08fc43d8608d2955f607f1b287beb525041e730086bdca3af152accac3af9c1`.

The archive and isolated extraction are under `/tmp/umbra-ci-emulator-16428233-1vpzxby3/`; the installed SDK was not replaced. The extracted executable reports Netsim 1.0.23. The older installed Netsim 0.3.114 was unsuitable as proof of the current CI control API.

## Native fixed-sample experiment

VERIFIED: `/tmp/umbra-native-capture-bounded-probe.py` started the isolated executable with `--pcap --no-test-beacons --no-shutdown --logtostderr` and an FD startup descriptor. The emulator build did not create a chip from that descriptor. The experiment therefore opened its owned loopback HCI port 6402, drained responses, and supplied synthetic HCI Reset commands in throttled batches of 256 with a 10 ms delay. It copied and parsed exactly ten snapshots while the writer was active, then requested SIGTERM, waited for exit 0, and copied and parsed the final file.

All ten samples and the final file are retained under `/tmp/umbra-native-capture-race-lcwxlgim/`. No sample was discarded or retried until successful.

| Snapshot | Bytes | tcpdump exit | Result |
| --- | ---: | ---: | --- |
| 0 | 114606 | 1 | Truncated record: 7 captured bytes expected, 0 available |
| 1 | 275224 | 0 | Complete parse |
| 2 | 438947 | 0 | Complete parse |
| 3 | 627213 | 0 | Complete parse |
| 4 | 830996 | 0 | Complete parse |
| 5 | 1060179 | 1 | Truncated record: 7 captured bytes expected, 0 available |
| 6 | 1330340 | 0 | Complete parse |
| 7 | 1607787 | 1 | Truncated record: 4 captured bytes expected, 0 available |
| 8 | 1941488 | 0 | Complete parse |
| 9 | 2309880 | 0 | Complete parse |
| After graceful exit | 2639065 | 0 | Complete parse |

Local evidence receipts:

- `facts.json`: SHA-256 `a858cdefee7f24c4a417bcdfcac1d9f682e924c2112ea3d9fdf757b7b295040d`.
- `/tmp/umbra-native-capture-bounded-probe.py`: SHA-256 `01d897e68f29a7035413f65ff29a7921b911b4be6ca6ae8099f76172dc03e89c`.
- `final-0.pcap`: SHA-256 `423ea98384b6cbb7573a9d2efa4b289bb381c6504d1b1767b59437bf124bf7f1`.

The preliminary FD-only experiment produced no captures. A first unthrottled HCI experiment was stopped when continuous traffic made snapshots grow during sequential parsing; its files and explicit stopped-experiment receipt remain at `/tmp/umbra-native-capture-race-f0odcfbs/bounded-stop.json`. No acceptance result is claimed for that incomplete experiment. The separate deterministic synthetic header/body-copy reproducer demonstrates susceptibility too, but the table above is native binary evidence.

## Capture control and finalization

VERIFIED: the exact shipped binary documents `--no-web-ui` as a no-op because a web UI is not implemented. An isolated default-instance launch, without either disable-UI flag, logged `no_cli_ui=true`. Direct HTTP/2 gRPC calls to `netsim.frontend.FrontendService/{ListCapture,PatchCapture,GetVersion}` each returned gRPC status 12 (UNIMPLEMENTED). Evidence: `/tmp/umbra-netsim-grpc-proof-if2hac6d/default/facts.json`. The default-instance socket/argv receipt is `/tmp/umbra-netsim-default-proof-e1gknaac/facts.json`. These owned probes terminated without touching another daemon or emulator. A separate `--dev` launch still logged `no_cli_ui=true`; its client transport errors are not evidence of a successful RPC.

No working capture-only stop/flush acknowledgment was demonstrated for this distribution. The older daemon's HTTP capture endpoints must not be assumed available. A viable laboratory design is to defer network verdicts, preserve each scenario's time bounds, stop owned producers and the owned daemon once at matrix completion, require graceful termination, and analyze immutable complete captures before issuing acceptance. A pending receipt is not a pass; no malformed tail may be clipped or reinterpreted as zero packets.

Current upstream source corroborates the mechanism:

- [PCAP writer](https://android.googlesource.com/platform/tools/netsim/+/refs/heads/emu-main-dev/next/capture-actor/src/writer.rs): a Tokio buffered file writer receives record header and data in separate awaited writes.
- [Capture lifecycle](https://android.googlesource.com/platform/tools/netsim/+/refs/heads/emu-main-dev/next/capture-actor/src/lifecycle.rs): periodic flush and shutdown flush followed by clearing writers; write failures are logged.
- [Daemon lifecycle](https://android.googlesource.com/platform/tools/netsim/+/refs/heads/emu-main-dev/next/daemon/src/netsimd.rs): frontend registration is gated by `no_cli_ui`; FD listener initialization is conditional on the Cuttlefish build feature.

LIMITATION: these links identify the current upstream branch, not a source commit proven to match build 16428233. The binary experiment supplies the direct evidence. Daemon exit 0 alone does not prove all writes succeeded: finalization must also reject capture errors and structurally malformed files and retain diagnostic evidence.

## Historical CI classification

VERIFIED: on candidate 95aabb6, jobs 111005150044 and 111005150225 failed PCAP postprocessing for `expired-auth` and `ipv6-tls`, respectively. The Python subprocess exception reports tcpdump exit 1. Neither its captured stderr nor raw PCAP was uploaded, so classification as truncation remains INFERRED, not VERIFIED.

Preserved artifact archives:

- `/tmp/umbra-ci-95aabb-11249717881-7gojbe3z/artifact.zip`, SHA-256 `49dc37f637c2427ab6e5fa91cd76bbd3fcb0b8e02ac82a9d3972f57b9443d27c`.
- `/tmp/umbra-ci-95aabb-11249819312-wzpw_fky/artifact.zip`, SHA-256 `0c55c11b441ed55c125fdd06c647879b2f75214461eb391b09843c426dbbd4f8`.

No raw captures, full stderr, application secrets, or transcripts are included in this report. Temporary local receipts are evidence locations for this session, not durable CI artifacts or a claim that the proposed finalizer has passed CI.
