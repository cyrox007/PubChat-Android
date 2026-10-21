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
