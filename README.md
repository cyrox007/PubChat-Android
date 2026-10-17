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
- initial message history from realtime bootstrap
- live room messages and native send composer
- People discovery through `/social/v1/discover`
- public Persona profile opening
- own Persona summary

## Site parity target

Android is considered ready only when normal user flows available on the web client are available natively as well. Remaining parity blocks include:

1. Messenger: DM history, realtime, reply/edit/delete/reactions/forward, media.
2. Notifications + FCM + deep links.
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