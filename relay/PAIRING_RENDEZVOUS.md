# Encrypted pairing rendezvous v1 (candidate)

The additive v6 relay schema stores only opaque encrypted pairing blobs, hashes
of random locators/capabilities/request identifiers, expiry and routing state.
Existing pairing-invite endpoints retain their original contract. All new routes
require existing signed private admission in addition to the bearer shown below.
The code endpoint is **not** a pre-admission or enrollment bypass.

Tokens and request IDs are canonical unpadded base64url encodings of 32 random
bytes. Blobs are canonical padded standard base64 representing 28..65536 bytes.
Clients must encrypt and authenticate the signed transcript before publishing;
the relay does not decrypt blobs, inspect signatures, or certify identity.
`request_hash` below is lowercase hex SHA-256 of the ASCII `request_id`.

| Method and path | Bearer | JSON input / successful result |
| --- | --- | --- |
| POST `/v1/boxes/{box}/pairing-rendezvous` | Mailbox read | `{id,owner_token,request_token,expires}` with optional **all three** `{code_locator,code_read_token,invite_blob}`; 201 `{registered:true}`, exact retry 200 |
| POST `/v1/pairing-codes/{locator}/claim` | Code read | `{request_id}`; 200 `{invite_blob,expires}` |
| POST `/v1/pairing-rendezvous/{id}/requests` | Request write | `{request_id,ack_token,request_blob}`; 201 `{request_hash}`, exact retry 200 |
| GET `/v1/pairing-rendezvous/{id}/requests` | Owner | 200 `{requests:[{request_hash,request_blob}]}` (digest order) |
| POST `/v1/pairing-rendezvous/{id}/ack` | Owner | `{request_hash,ack_blob}`; 200 `{selected:true}` |
| GET `/v1/pairing-rendezvous/{id}/requests/{request_hash}/ack` | Candidate ack read | 200 `{ack_blob}`, 204 pending, 409 another candidate selected |
| DELETE `/v1/pairing-rendezvous/{id}` | Owner | 204 (including exact revoked retry before expiry) |

The owner must decrypt and validate candidate signatures locally through the
existing pairing service before selecting one. Invalid queued ciphertext does
not select a winner. Up to eight immutable candidates may coexist; selection and
ack persistence share one SQLite write transaction, and competing selections
have exactly one winner. An exact selection retry requires identical ciphertext.
A server queue/selection receipt never means contact verification or pairing
success. Clients must finish their existing local atomic contact/ack processing.

Code claim binds one request ID hash until expiry; only the same claimant can
read again. This reservation is an availability boundary: possession of the code
can exhaust it without supplying a valid pairing request. A client cannot prove
signed-request validity to the opaque server before decrypting the invite. A
compromised code holder can likewise occupy the bounded candidate queue.

Expiry is a Unix timestamp strictly after transaction-time `now`, at most 600
seconds later. Clients also bound it by signed-invitation validity. Capabilities,
expiry and revocation are checked for exact delivery retries. Revoked rows and
candidate quota consumption remain until expiry; owner revocation denies further
delivery. Mailbox deletion and expiry cleanup cascade to candidate rows. Limits
are 32 rendezvous per mailbox and 4096 globally, including live tombstones. The
existing ingress body/deadline/rate limits and admission checks remain in force.

The schema migration adds two tables only after validating the entire existing
schema. Missing/foreign/future tables or malformed columns, primary keys, unique
keys and foreign keys fail closed. Older supported schemas retain their existing
migration path before the additive v6 migration. Failed migration is transactional.
No production deployment, physical Bluetooth/QR/code acceptance, or new user
interface is implied by SQLite and HTTP tests.
