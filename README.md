# PubChat Android

Нативный клиент PubChat на Kotlin и Jetpack Compose. **Статус: разработка / техническое тестирование**, функциональный паритет с веб-клиентом и готовность к публичному выпуску не подтверждены.

**Репозитории**
- Android: [cyrox007/PubChat-Android](https://github.com/cyrox007/PubChat-Android), основная ветка `master`.
- Backend + веб-клиент: [cyrox007/PubChat](https://github.com/cyrox007/PubChat), основная ветка `main`. Это актуальное имя основной системы после переименования `cyrox007/chat` → `cyrox007/PubChat`.

## Архитектура и сборка

- Namespace/package: `ru.pubchat.app`; `minSdk = 26`, `targetSdk = 37`; версия из Gradle: `0.2.0`.
- `BuildConfig.API_BASE_URL` по умолчанию `https://pubchat.ru/api`; override: `./gradlew assembleDebug -PPUBCHAT_API_BASE_URL=https://example.test/api`.
- Android Keystore (AES/GCM) для локального хранения токена доступа и refresh cookie; стабильный `X-PubChat-Device-ID` для установки. Токены не должны попадать в документацию, логи или Git.
- HTTP API + отдельные WebSocket-соединения для Spaces, Messenger и calls. Сервер выдаёт одноразовые realtime tickets.
- `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, `./gradlew assembleDebug` — CI-gates; успешный CI **не** заменяет тесты на физических устройствах.

## Реализованный программный функционал

- Регистрация, вход, базовая Persona и People discovery.
- Список Spaces, вход в пространство, история и realtime-сообщения, отправка текста, небольших вложений (до 2 МиБ через data-URL).
- Панель «О комнате» внутри разговора: участники, заявки, изменение локальных ролей создателем, удаление участников, правила, события, предупреждения и ограничения с причиной. Права проверяет сервер; выход из участия отделён от возврата к списку пространств.
- Сообщения показывают автора, время, собственные сообщения справа, цитаты ответов с переходом к доступному исходнику, изображения и воспроизведение аудио. Поддерживаются оба серверных формата цитат и старые аудиосообщения со ссылкой в `content`.
- Тёмная палитра соответствует веб-клиенту; светлая тема включается в профиле и сохраняется на устройстве.
- Messenger: диалоги, непрочитанное, статусы, история и realtime-DM, текстовая отправка, реакции, редактирование, ответ, удаление, пересылка.
- Встроенный центр уведомлений, read/unread, deep links `pubchat://spaces/<uuid>` / `pubchat://messenger/<uuid>` и HTTPS-маршруты для этих разделов.
- Регистрация Android FCM-токена при настроенном Firebase, удаление токена при выходе; generic push уведомления без содержимого переписки.
- WebRTC аудио/видео: ICE config с сервера, отдельный signaling WebSocket, входящие/исходящие предложения, отображение удалённого видео, управление микрофоном/камерой/динамиком и foreground service для уже инициированного вызова.
- Входящие вызовы: короткоживущее серверное предложение (Redis, 60 секунд), отдельный data-only FCM wakeup без SDP/ICE, уведомление с действиями ответа/отказа. Приложение проверяет авторизацию, соответствие `call_uid` и runtime-разрешения до принятия.
- Переподключение signaling с ограниченным backoff, 12-секундный recovery window для временной потери ICE, экспериментальный ICE restart (Android ↔ web).

Это описание **наличия кода**, не гарантия корректной работы в production или на конкретной прошивке Android.

## Что ещё не готово

1. **E2EE для DM на Android**: нативные ключи, проверка устройств и E2EE-aware отправка. Текстовый DM composer не означает защищённую доставку; не используйте его для конфиденциальных сообщений до аудита политики plaintext/fail-closed.
2. Полный паритет Space (повторяющиеся активности, приглашения, оформление/поддержка, полный журнал модерации и апелляции) и Persona (редактирование, стиль и приватность). Базовое управление комнатой и её участниками доступно из нативной панели, но полный паритет с веб-клиентом не заявлен.
3. Safety Center, блокировки, жалобы, апелляции, админ-функции, управление устройствами/сессиями.
4. Полноценная галерея вложений и безопасные DM-вложения; offline cache/синхронизация.
5. Отработка Telecom/ConnectionService, Bluetooth/headset routing, lifecycle/revocation при убийстве процесса, поведение на экране блокировки.
6. Glare при одновременном ICE restart, истечение TURN credentials, продление/обновление ICE server config, end-to-end тест Wi-Fi ↔ LTE.
7. Публикация и проверка Android Digital Asset Links (`assetlinks.json`) для HTTPS App Links.
8. Реальные Android ↔ Android / Android ↔ браузер испытания. Verified application E2EE и привязка звонка к личности устройства **не реализованы**.

## Firebase / push: настройка

Обычный Gradle Sync и сборка не требуют `google-services.json`: без файла Google Services plugin не скачивается и не применяется. Firebase SDK остаётся зависимостью приложения, но регистрация FCM пропускается, пока Firebase не настроен.

Для локальной проверки без Firebase: `./gradlew help testDebugUnitTest lintDebug assembleDebug` (Windows: `gradlew.bat`). Нужны JDK/Android SDK и доступ к репозиториям остальных зависимостей; полностью offline-сборка требует заранее заполненного Gradle cache.

1. В Firebase зарегистрировать release `ru.pubchat.app` и отдельно debug `ru.pubchat.app.debug`.
2. Локально положить `app/google-services.json` (файл исключён из Git). При следующем Gradle Sync плагин `com.google.gms:google-services:4.4.2` загрузится из Google Maven и применится автоматически. Для сборки обеих версий файл должен содержать клиентов для обоих package name. После добавления или удаления файла повторить Gradle Sync.
3. На сервере настроить `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_EMAIL`, `FCM_SERVICE_ACCOUNT_PRIVATE_KEY` в защищённом окружении. Включить и проверить worker native push и настройку `native_push_messenger` у аккаунта.
4. После входа проверить регистрацию через `POST /notifications/v1/native-push/devices`, получение push в foreground/background, смену токена и отзыв регистрации при logout.
5. Для звонков проверить `/calls/v1/pending-offer`, срок 60 секунд, отсутствие SDP и ICE в push, запрет автоответа/доступа к камере из background, отклонение старого `call_uid`.

Без Firebase и серверных credentials Android push в реальности не доставляется. Generic Web Push/VAPID сайта не заменяет FCM.

## WebRTC: контракт

- `GET /calls/v1/ice-config`: STUN/TURN, credentials, `ice_transport_policy` (в том числе строгий `relay`).
- `/ws/v2/calls` с одноразовым Messenger ticket: `call_offer`, `call_answer`, `call_ice`, `call_decline`, `call_end`.
- Для уже активного звонка `call_offer` с `ice_restart=true` — **renegotiation**, не новый вызов и не новый FCM push. Веб-клиент и сервер должны быть совместимы с флагом.
- `GET /calls/v1/pending-offer`: только авторизованный получатель, краткоживущая запись в Redis.
- Медиатранспорт: WebRTC DTLS-SRTP; **signaling E2EE, verified E2EE и device identity binding отсутствуют**.
- При потере signaling текущий медиавызов безопасно завершается, а signaling пытается переподключиться. ICE restart применяется только к действующему peer connection во время краткого ICE-обрыва; восстановление после гибели процесса не обещается.

## Чек-лист перед beta

- Проверить release/debug Firebase, подписку, отзыв и уведомления на Android 8–16 (с учётом поддерживаемых реальных устройств).
- Проверить входящие/исходящие звонки Android ↔ Android и Android ↔ web, foreground/background/lock screen, разрешения и отказ.
- Проверить Wi-Fi ↔ LTE, TURN relay, истечение TTL, glare, звук через динамик/наушники/Bluetooth, cleanup foreground service.
- Проверить E2EE-политику личных сообщений и отсутствие утечек plaintext; дополнить тесты по всем пользовательским сценариям.
- Проверить HTTPS App Links и необходимые доменные настройки, затем отдельный release gate.

Документация основной системы: [обзор мультиклиентной архитектуры](https://github.com/cyrox007/PubChat/blob/main/docs/client-repositories.md).

## Бренд и двухтокенная сессия (октябрь 2026)

Android Compose использует семантическую светлую/тёмную палитру PubChat из `cyrox007/PubChat/frontend/src/assets/main.css`; Dynamic Material You цвета выключены, чтобы тема не зависела от обоев. Adaptive launcher icon теперь содержит самостоятельный векторный знак разговора на фирменном сливовом фоне; исходные PNG веб-favicon автоматически не копируются — для точного совпадения графики их потребуется перенести отдельным asset-commit.

Backend выдаёт access JWT в JSON и ротируемый refresh token в HttpOnly `Set-Cookie`. Android шифрует оба в Keystore-backed storage, отправляет `X-PubChat-Device-ID` (уникальный UUID установки) и использует cookie при `/identity/v2/refresh`; backend группирует сессии по device UID, но UUID устройства сам по себе не является секретом аутентификации. Новый login/register не должен посылать refresh cookie предыдущей сессии. Серверный отказ `403` не следует обходить; проверять его нужно отдельно по account-access restrictions, credentials и логам сервера. Тестирование реального устройства и серверной политики сессий обязательно.
