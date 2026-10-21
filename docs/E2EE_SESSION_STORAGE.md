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
