# PubChat Android

Native Android client for PubChat.

## Foundation

- Kotlin + Jetpack Compose
- package `ru.pubchat.app`
- minSdk 26
- native login against `/api/identity/v2/login`
- stable `X-PubChat-Device-ID` per installation
- access token + refresh cookie encrypted with Android Keystore (AES/GCM)
- automatic access-token refresh through `/api/identity/v2/refresh`
- native spaces list from `/api/spaces/v1`
- production API base defaults to `https://pubchat.ru/api`
- CI runs unit tests, Android lint and `assembleDebug`

## API endpoint override

For another environment pass a Gradle property:

```bash
./gradlew assembleDebug -PPUBCHAT_API_BASE_URL=https://example.test/api
```

Cleartext traffic is intentionally disabled. Local development should use HTTPS or an explicit debug-only network security policy in a later development task.

## Current vertical slice

`Login -> secure native session -> load Spaces -> logout`

Next slices: space detail/chat realtime, registration, push/FCM, device/session management, attachments and calls.
