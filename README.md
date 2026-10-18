# PubChat Android

Native Android client for PubChat.

## Foundation

- Kotlin + Jetpack Compose
- package `ru.pubchat.app`
- minSdk 26
- stable `X-PubChat-Device-ID` per installation
- access token + refresh cookie encrypted with Android Keystore (AES/GCM)
- automatic access-token refresh through `/api/identity/v2/refresh`
- production API base defaults to `https://pubchat.ru/api`
- CI runs unit tests, Android lint and `assembleDebug`

## Current working slice

- native login and registration through `/identity/v2`
- secure multi-device session foundation
- Spaces list and Space detail/open-join flow
- native Space WebSocket through `/realtime/v2/tickets`
- initial Space message history, live room messages and native send composer
- People discovery through `/social/v1/discover`
- public Persona profile opening and own Persona summary
- Messenger dialog list with unread counters
- open DM directly from a Persona profile
- native Messenger realtime ticket/WebSocket authentication
- DM history, live messages, read receipts and presence subscription
- DM send composer
- reply, edit, delete, reaction and forward message actions

## Site parity target

Android is considered ready only when normal user flows available on the web client are available natively as well. Remaining parity blocks include:

1. Messenger media/attachments and E2EE-aware delivery UX.
2. Production FCM notification dispatch requires a dedicated authenticated Android-token registration/delivery API, Firebase setup, and push delivery tests; existing backend Web Push (VAPID) is not FCM. HTTPS app-link verification also requires an Android assetlinks.json hosted by pubchat.ru.
3. Native voice/video WebRTC calls.
4. Full Space sections: Community, Life, Support and moderation.
5. Invitations, events, games, tasks and membership management.
6. Persona editing, privacy and style.
7. Device/session management, Safety Center, blocks/reports/restrictions/appeals.
8. Achievements and Trust & Safety moderator flows.
9. Admin flows for admin/superadmin roles.
10. E2EE device registration/verification and local key storage.
11. Offline cache, reconnect/sync and release hardening.

## API endpoint override

For another environment pass a Gradle property:

```bash
./gradlew assembleDebug -PPUBCHAT_API_BASE_URL=https://example.test/api
```

Cleartext traffic is intentionally disabled. Local development should use HTTPS or an explicit debug-only network security policy.
## Media and push constraints

Space uploads use the site's existing realtime data-URL media envelope, limited to 2 MiB on Android to bound memory and WebSocket frames. They do not yet persist attachments offline or provide a native media viewer. Do not send DM attachments in plaintext: native E2EE device identity/key agreement is required first. The notifications tab is an authenticated foreground inbox; no background FCM push or Android deep-link dispatch is claimed until server contracts and Firebase provisioning are implemented.

## Deep links

Authenticated routes: `pubchat://spaces/<uuid>`, `pubchat://messenger/<uuid>`, `https://pubchat.ru/spaces/<uuid>` and `https://pubchat.ru/messenger/<uuid>`. Invalid UUIDs or untrusted domains are ignored. Pending links survive the login screen. Android OS verification of HTTPS App Links is not enabled until the public domain hosts its matching Digital Asset Links statement; otherwise Android may show a chooser.

## Android Firebase setup (required before push works)

1. Create a Firebase Android app with package `ru.pubchat.app` for release. The debug variant uses `ru.pubchat.app.debug`, so register that variant separately for local testing.
2. Place `google-services.json` in `app/` locally (never commit it). The Google Services Gradle plugin is activated only when the file is present.
3. On the PubChat server configure `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_EMAIL` and `FCM_SERVICE_ACCOUNT_PRIVATE_KEY` (private key with escaped newlines), using the matching Firebase project. Keep these private.
4. Enable `native_push_messenger` via the existing account notification preference endpoint. Ensure the existing scheduled native-push delivery worker actually runs.
5. Rebuild/reinstall Android; after login it registers/updates the device FCM token via `POST /notifications/v1/native-push/devices` and revokes registration on logout.

FCM is inactive without Firebase configuration. UI notification center still works without it. Push payloads are generic and never carry private message plaintext. Production delivery must be smoke-tested on a physical device. Android app links require matching domain assetlinks.json separately.

## Android foreground calls (in progress)

Uses a separate ticket-authenticated `/ws/v2/calls` socket with `call_offer`, `call_answer`, `call_ice`, `call_decline`, `call_end`; ICE/TURN settings are read from `/calls/v1/ice-config` with strict relay mode honored. Call media uses native WebRTC DTLS-SRTP, not application-verified E2EE. The present UI covers call setup/incoming consent, mute and hangup while the process is foregrounded. Video track rendering, Android audio routing, background incoming-call wakeup and foreground service/notification integration still require follow-up before production call parity. Never describe this slice as finished calling parity until tested end-to-end between Android and web devices.

## Call audio routing increment

Foreground WebRTC calls acquire transient communication audio focus, switch to MODE_IN_COMMUNICATION and offer speaker/earpiece routing (Android 12+ communication device APIs, legacy speakerphone routing for older devices). Microphone and camera toggles remain available. Call teardown restores audio mode and focus. Bluetooth selection and headset device-change monitoring require real-device verification; do not claim background ringing or Telecom call integration from this change.

**Background calling remains blocked on a server-side call-specific push event with a scoped, short-lived wakeup/offer lookup mechanism.** The existing FCM notification payload is a generic unread-message reminder; it must not be interpreted as an incoming call or carry private SDP/ICE details.

## Active calls in background (foreground-service slice)

An outgoing or accepted call started in the visible Activity now starts an ongoing Android foreground service with microphone/camera service types and a persistent call notification. This helps preserve an ongoing call when the Activity is backgrounded. It does not wake a killed app for incoming calls. Call termination, disconnect and logout stop the service. Android 14+ restrictions mean microphone/camera FGS must start while the app is foregrounded and permissions are already granted. Battery policies and headset routing require physical-device testing.

**Not implemented:** background incoming call push, call offer persistence/claim with expiry, notification answer/decline actions, Telecom integration, cross-device WebRTC smoke. Current WebSocket-only offers cannot be answered after process death; do not treat a generic FCM message as an offer.
