# Android E2EE session storage (foundation)

This milestone adds a fail-closed encrypted, transaction-like local snapshot. It **does not** enable sending or receiving E2EE Messenger traffic.

## Invariants

- Each Account UID + cryptographic device UID has a distinct file in Android `noBackupFilesDir`; the file is AES-256-GCM wrapped with an Android Keystore key. AAD binds both IDs.
- The entire outbound chain and its newly generated pending ciphertext packet are written as **one** `AtomicFile` snapshot. The caller receives the packet only *after* `finishWrite` returns.
- A restart loads the same pending ciphertext and its original nonce, session UUID and counter. An ambiguous network failure **must not encrypt again**; retry the identical pending packet and reconcile server acknowledgment by `frontId`.
- Only an authenticated acknowledgment allows deleting the saved pending packet. This does not decrease the chain counter.
- Inbound AES-GCM authentication happens before ratchet advancement, and the wrapper commits advancement before returning plaintext to the caller.
- Missing/corrupt/undecryptable local state is an error, not an excuse to regenerate an existing E2EE identity or session. The `initialize` operation is intentionally explicit.
- The store is synchronized **within one Android process**. Do not share the same E2EE ledger with a secondary process without an additional interprocess lock or transactional database.

## Deliberate constraints / not yet safe for release

- This is an **offline building block**. Nothing calls it to transmit Messenger ciphertext yet; Android remains fail-closed for E2EE conversations.
- Inbound currently requires exactly the next expected counter (no delayed/out-of-order decrypt). History per-message key caching and Web-compatible 64-gap recovery remain future work. Do not claim full receiving parity.
- The pending queue holds individual per-device packets, not a durable atomic **all-device fan-out batch**. Encrypted POST must only be enabled after one complete fan-out/ack workflow is designed.
- The store deliberately does not retain outbound historical decryption keys after ack. Android E2EE history rendering remains a separate milestone.
- `AtomicFile` guards ordinary interrupted writes, but secure rollback resistance against full filesystem rollback/restoration requires stronger hardware-attested monotonic or externally witnessed state. Local Keystore encryption alone is not an anti-rollback counter.
- Identity revocation should be confirmed by the server before `clearAfterServerRevocation` is called. The new store is not integrated with revocation in this slice.
- CI/JVM tests establish state transitions; physical-device crash/reinstall, Web↔Android protocol interoperability, replay and multi-device negative-path tests are still required.

Tracking: [Android E2EE phase 2 #61](https://github.com/cyrox007/PubChat-Android/issues/61).


## Atomic multi-device fan-out milestone (#73)

- `E2eeFanoutBatch.prepare` stages envelopes for **every supplied active own device and peer device** in a single state image. It requires unique devices, the current sender device, at least one device on each side, no more than 40 envelopes (the server maximum), and pre-existing outbound sessions.
- The caller must load and authenticate current own and peer directories, check peer continuity and device changes, and verify signed-prekey bindings. This layer does not derive or authenticate that discovery itself; passing an incomplete target list would be unsafe. Until those dependencies are wired, the send path is **not available from Messenger UI**.
- Each device has a distinct deterministic packet UUID bound to the message `front_id` and target UID. The encrypted nonce/ciphertext/counter and optional initial header are saved together with the advanced session chains and the single `front_id`. A crash can retry only the same packet set.
- `E2eeBatchDelivery` submits the committed batch to authenticated `POST /crypto/v1/messages` only after its caller's fresh trusted-directory check. An explicit `status=ok, duplicate=false` response with matching `message.frontId` and `content_type=e2ee` triggers atomic deletion of all pending packets.
- `duplicate=true`, HTTP timeout, authentication failure, an unexpected ACK or failure to persist local ACK is ambiguous: do **not** delete the batch or generate a fresh ciphertext under the same `front_id`. Recover by checking server history and exact message identity in a future milestone.
- Encrypted packet creation and state commit are synchronous inside the same process lock and `AtomicFile` write. No code should publish a packet before commit returns.
- Old single-device packet APIs are preserved for compatibility, but cannot delete a packet owned by an all-device batch.
- Initial session headers and key provenance still require authenticated 3DH/prekey bootstrap; automatic fan-out discovery, encrypted history and recovery, device revocation, and signed-device QA are outstanding.

This prevents **local** partial fan-out commits, but it does not yet implement an end-to-end production send. Server membership validation remains the final authority at POST. 


## Device-directory preflight (next milestone)

- `E2eeDevicePreflight.verify` requires checked own+peer directory inspections with no unacknowledged membership changes, a locally stored cryptographic identity matching the active own device, and a signed peer bundle with exactly the same device/key identities.
- It rejects substituted signing/DH/signed-prekey material, a revoked/missing sender, duplicate devices across accounts, and fan-out larger than the server 40-envelope limit.
- `E2eeBatchDelivery` no longer accepts an arbitrary `Unit` success callback. It requires typed `VerifiedTargets`, obtains fresh checked own+peer snapshots, and compares checkpoint identities, key material and complete envelope recipients before POST.
- Signed-prekey verification and peer directory history checking are performed by earlier standalone components; callers MUST obtain trusted, persisted inspections before constructing a preflight.
- **Not ready for real transmission:** the inspected snapshots are not yet automatically fetched or joined to session bootstrap / durable initial headers; do not call this from Messenger UI. Current server-side directory proofs do not provide external witness protection. Tests in this milestone are offline contract checks, not live server/Web↔Android integration.


## Authenticated own/peer directory acquisition

- `E2eeTrustedDirectoryLoader.load` obtains the durable local Account-scoped device identity, the current **own and peer** directory snapshots and any bounded history required to verify continuity from stored checkpoints.
- An unacknowledged changed directory is blocked; a first-seen directory is TOFU rather than safety-number verified.
- Only after both checks does it claim a peer prekey bundle, validate Ed25519 signed-prekeys and exact device identity bindings, and re-fetch both directories to catch changes during claim.
- After the final consistency check it durably saves the continuity checkpoints. Keystore read/write failures are fatal, not an opportunity to silently reinitialize.
- `refreshForSend` compares fresh checked directories with the prepared immutable preflight immediately before a pending ciphertext batch is submitted. The server still performs final active-device membership validation at POST.
- **Still no UI send integration or automatic session creation.** Initial 3DH headers, all-device session creation in a crash-safe transaction, handling prekey consumption on failures, and real device roundtrip tests are the next milestones.


## Atomic first-message 3DH bootstrap (follow-up to #75)

- `E2eeAtomicBootstrap.prepare` consumes **only** verified own/peer target keys from `E2eeDevicePreflight`; it rechecks the local identity against the own signed directory and verifies the claimed peer signed-prekeys against pinned peer keys.
- Each missing outbound target receives a fresh X25519 ephemeral key and documented 3DH/HKDF root. The first-envelope `initial_header` contains `ephemeral_key`, `target_signed_prekey_id`, optional `target_one_time_prekey_id`, `sender_identity_dh_key`, and `sender_identity_signing_key`, matching Web's wire contract.
- New session roots, exact first headers, outbound counter advancements, and **all-device ciphertext** are committed as ONE encrypted `AtomicFile` image. On interruption the existing `frontId` can only replay the stored ciphertext; it cannot regenerate an ephemeral session at the same counter.
- Existing outbound sessions are bound to pinned peer Ed25519/X25519 identity. Unconfirmed initial headers are included on retries and subsequent pending sends until the server acknowledges a full batch; successful server ACK updates header status atomically with clearing the pending batch.
- Includes independent sender/recipient 3DH decryption checks for first ciphertexts and negative tests for identity substitution and duplicate front IDs.
- **Not enabled in Messenger UI.** Receiving/bootstrap for inbound sessions, key rotation/revocation, authenticated server history and crash recovery of lost ACK, multi-device Web↔Android physical QA and message rendering remain blocking. This is a protocol foundation, not security acceptance of full E2EE.


## Inbound first-message 3DH (next milestone)

- `E2eeInboundBootstrap` rejects unknown sender devices or unacknowledged directory changes; sender identity DH and signing keys from the initial header must exactly match the continuity-checked sender directory.
- The recipient UID is bound to its stored device identity. The initial message requires a valid target signed prekey ID, optional locally available one-time prekey ID, and counter zero; there is **no fallback** to a different private key.
- An inbound session and consumed-one-time-prekey marker are staged with AEAD decryption and receive counter advancement; `E2eeSessionStore.decryptInbound` commits the entire encrypted AtomicFile state **before** returning plaintext. Authentication failure leaves the prior on-disk state unchanged.
- Existing inbound sessions pin sender identity keys, forbid silent replacement, and reject duplicate/old or missing counters. First-message replay cannot burn a second one-time prekey.
- JVM tests verify real sender/recipient 3DH agreement, first ciphertext decryption, the following message, replay rejection, invalid headers, tampering and missing keys.
- **Important release limitations:** this milestone intentionally does not wire automatic envelope retrieval/history or Messenger UI rendering, and it currently refuses out-of-order counters rather than caching skipped keys. A signed-prekey private-key archive and precise one-time-prekey lifecycle on rotation/revocation still require an end-to-end design. Historical receive deduplication and physical Web↔Android interoperability QA are outstanding. These gaps block marking E2EE production-ready.


## Read-only encrypted Messenger receive surface

- `PubChatApi.e2eeMessageEnvelopes` requests at most 100 unique message UIDs using authenticated `POST /crypto/v1/messages/envelopes`; the server returns only Account-accessible ciphertext envelopes.
- `AppController.decryptMessengerHistory` fetches and continuity-checks the peer device directory, rejects unacknowledged device-key changes, selects exactly one envelope for the current cryptographic device per message, and calls the crash-atomic inbound 3DH/AEAD store. The message is displayed only after the durable store commit succeeds and the decrypted JSON has `type=text` with a valid UTF-8 text field.
- `PubChatApp` renders authenticated plaintext in the active conversation with a lock marker. Missing, mismatched, corrupted, replayed or out-of-order ciphertext stays locked; server `message.content` is never substituted as plaintext. The reply/edit/forward controls remain unavailable for E2EE messages.
- Received plaintext exists only in current in-memory UI state and is not logged or written to Preferences. Already-decrypted items may remain visible across a subsequent history refresh within the same signed-in shell.
- Outgoing encrypted sending remains blocked until its release gates have been met. This milestone does **not** enable a plaintext fallback.
- **Known limitations:** sender-own echo history is still locked; out-of-order message counters and previously-consumed messages after process restart need a key cache; session initialization/recovery and signed-prekey rotation archives are not automatic; physical-device Web↔Android interoperability and full error-path QA remain pending. Do not label the Messenger implementation E2EE-production-ready.


## Bounded authenticated inbound history recovery

- Each inbound session maintains a Keystore/AtomicFile-wrapped receive-key cache (up to 128 keys per session and 512 overall). No decrypted plaintext is persisted.
- A gap of at most 64 messages may advance the receive chain: derived skipped keys are stored atomically. Late-arriving envelopes authenticate using a saved key without rolling the chain backward.
- Once a ciphertext has authenticated at a counter, the cache pins SHA-256(nonce || ciphertext). Exact historical envelopes can be re-decrypted after process restart to restore the Messenger view. A changed nonce or ciphertext at the same counter fails closed.
- Decryption failures do not commit any new ratchet state; the caller still validates plaintext type before committing a receive transaction.
- Old preexisting session state without cache fields remains readable. Cache exhaustion is a hard error (no unsafe key eviction), so **long-running conversations with more than the current bounded history window need a follow-up retention/backup design**.
- Replay for an identical authenticated historical envelope is a read-only history recovery, not a second delivery event or new server acknowledgement; UI deduplicates by message UID.
- This is still not a full double-ratchet protocol: symmetric per-session chain keys, identity rotation handling, anti-rollback across filesystem restoration, sender own-device echo and physical Web↔Android tests remain outstanding.
