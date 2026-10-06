# ADR: private startup strict

Status: implementation design, not acceptance evidence. Base a78a3ff / PR13.

## Audit before changes

- MainActivity.onCreate shows locked UI, installs a local Handler tick. onResume
  starts Android authentication. completeAuthentication builds Vault/Engine.
  refresh restores persisted profile.online; tick calls syncNow every8s. This
  incorrectly conflates unlock with renewed consent to online I/O.
- RelayClient is the only production Java HTTPS entry (URI/openConnection);
  private methods require admission, but public provisioning methods have only
  the caller BooleanSupplier. URI validation does not itself resolve DNS.
- Engine/SignalStore/DeviceService/AdmissionService operate local Records; relay
  capabilities and public provisioning are later transported by RelayClient.
- BluetoothLink constructor obtains adapter and starts a watchdog. listen/connect
  create RFCOMM sockets; no production discovery/scan/advertising implementation.
  Existing buttons are explicit but there is no independent domain nearby lease.
- CallService grants native media leases; NativeVoiceSession.open creates native
  WebRTC after consent, with periodic checks. A TURN configuration contains local
  authorized servers; no production TURN credential HTTP issuer exists.
- AndroidLocationCapture constructor obtains LocationManager; start registers
  updates only after local LocationService consent. Runtime IDs prevent restore.
- Main manifest exposes only MainActivity. No service/boot receiver, WorkManager,
  JobScheduler, alarm, analytics, remote crash reporter or update checker exists.
  Native media/capture timers exist only inside an explicitly created session.
- Vault starts password-locked and invalidates AccessGate leases on lock. Admission
  does not confer connectivity. PR12/Claude UI is not the base and is untouched.

## Decision

PRIVATE_STARTUP_STRICT is the only production policy. Process-local connectivity
state starts LOCKED_PRIVATE; explicit vault-unlocked notification yields
UNLOCKED_OFFLINE. Explicit connect validates vault, admission, edition and HTTPS
configuration before CONNECTING/CONNECTED. CONNECTED means local permission to
use configured transports, not a claim of relay reachability or active media.
Connections are opened on demand; transport failure ends the local session and
requires another explicit connect. No periodic reconnect or persisted grant.

Independent nearby authorization requires a deliberate local action; online
connect never starts Bluetooth. Every transport retains its original generation.
Disconnect invalidates online grants and closes registered work; vault lock also
invalidates nearby. Native media remains subject to CallService consent and its
existing identity/TURN/certificate checks. Disconnect cannot recall bytes already
sent. Sensor capture still requires its own existing explicit consent.

The central gate must cover public and private RelayClient requests before URL
connection/DNS, and calls before creating media or requesting TURN. Unadmitted
provisioning is offline in strict v1; public relay provisioning remains useful to
an already admitted administrator, not an automatic network exception.

No wire/crypto/vault migration is required. Legacy profile.online is ignored as
an authorization and must not recreate a session. APIs expose state, connect,
disconnect and nearby consent without exposing sockets to future UI. Minimal
legacy MainActivity wiring may remove persisted-online restoration; no visual
redesign or edits to Claude's UI branch.

Without explicit connection there are no instant incoming messages/calls. This
policy controls UMBRA, not OS traffic, keyboard networking, privileged code or
other apps. Future network features must use the gate. AVD evidence must separate
UID-attributed traffic/DNS from Android system traffic and synthetic sensors from
physical hardware. No acceptance claim until actual tests and final CI run.

## Cancellation and lifecycle ordering

A DISCONNECTING barrier stays in place until registered cancellation callbacks
finish. Vault invalidation during that barrier changes its destination to
LOCKED_PRIVATE; it cannot expose a new unlocked epoch ahead of old cleanup.
Old transport failures are scoped to the original generation. A failed cleanup
is retained as a diagnostic and disables new consent for that controller rather
than claiming that a possibly orphaned resource was closed successfully.
Callbacks must not enter a Records transaction. Relay ownership uses atomic
references rather than a monitor held while checking the Vault lease, avoiding
an AccessGate/transport monitor inversion during lock.

The Android binding observes the default network only after explicit consent,
using `registerDefaultNetworkCallback` rather than `requestNetwork`. Loss,
blocking or replacement of that default ends the original generation; `onAvailable`
never reconnects. The callback is unregistered on disconnect, including a race
with its registration. Android source contract:
https://developer.android.com/develop/connectivity/network-ops/reading-network-state
https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
No capability/route query is made inside callbacks. Transport failures also end
the grant, including on platforms using the pure-domain integration.
