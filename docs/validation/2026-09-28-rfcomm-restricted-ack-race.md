# Restricted RFCOMM duplicate/ACK ordering — 2026-09-28

At 5c40c24f81e7525382447a67766cf544a2e6f859, focused run 36508324723 retained
failures in connected repetitions 1/3 and offline repetition 3. Artifact
11008391567, integration checkout 1945d1917042afd02bf26300a7bea94f30d38574.
Emergency run 36508324734 passed all five jobs on that checkpoint, including
real RFCOMM; that success does not erase the focused failures.

The failing stack points to NearbyFixtureListener:193, its SECOND sendAsync,
then Engine.authorizeEnvelope:267: `Delivery cancelled or unknown`. Restricted
messages do not enter ordinary history, and a valid encrypted ACK removes their
outbox row. If ACK processing wins before the fixture's deliberate duplicate,
the production gate correctly rejects it. The test had assumed it could always
retransmit after acknowledgement. SQLite timing exposed this ordering; it is not
evidence of a failed Bluetooth challenge or a broken Signal session.

The fixture now opens a bounded encrypted inbound-delivery window around both
writes. It holds at most eight envelopes and applies ALL of them through the
real Engine immediately when the window closes, including on a failed write.
It does not hold the Records monitor while waiting for a writer. No cipher,
authorization or receipt check is bypassed. It represents an intentional network
scheduling condition, not a new production queue. The existing write-retirement
interval/deadlines remain unchanged. Buffer overflow is a test failure.

New real-libsignal JVM regression proves duplicate-before-ACK is idempotent,
consumption stays terminal, and a write AFTER the real ACK is rejected. Both
flavors passed all 17 RestrictedContentTest methods. Both debug instrumentation
APKs and lint built (exit 0). Native repeated RFCOMM acceptance of this correction
remains pending its own CI; no retry of the old commit is presented as a fix.
