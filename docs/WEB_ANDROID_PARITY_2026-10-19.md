# Web ↔ Android: функциональный паритет (19.10.2026)

Источник: реальный код `cyrox007/PubChat` (`main`), `cyrox007/PubChat-Android` (`master` до этого PR) и [единый roadmap](https://github.com/cyrox007/PubChat/blob/main/docs/roadmap.md). Здесь отмечается **наличие пользовательского сценария в коде**, а не доказанная готовность production. Web не является автоматически эталоном качества: слабые сценарии нужно улучшать в общей модели API/продукта.

| Сквозной сценарий | Web | Android до этой задачи | Приоритет следующей реализации |
| --- | --- | --- | --- |
| Вход/регистрация/сессия | UI + API | UI + API | Проверить обновление токена на устройстве |
| Spaces: каталог/категории/сообщения | UI + API | UI + API | Реальный offline/reconnect QA |
| **Создать Space с выбором доступа, категорий и правил вступления** | `CreateSpaceModal.vue` | **Нет формы/API** | **P0 — этот PR** |
| Space invites/входящие приглашения | `InvitationsView.vue` и Space API | Нет единого inbox-потока | P1 после создания |
| Space Activities/повторы/напоминания | `SpaceLifeView`, Activity UI | Часть событий в room panel | P1 |
| Профиль/Persona: базовые данные/несколько образов | UI + API | `ProfilePanel`/API | Реальные профили друзей, видимость/приватность |
| Messenger: чат/отправка/редактирование/реакции | UI + API | UI + API | Confirm/retry + network QA |
| **E2EE и политика приватных сообщений** | Web security UI + policy enforcement | Нативных ключей/verified E2EE нет | **P0 — отдельный security epic; fail-closed** |
| Safety Center/жалобы/апелляции | UI + moderation API | Нет полноценного user safety flow | P0 после базовых действий |
| Device sessions/отзыв сессий | `DeviceSessionsView.vue` | Нет user-management UI | P1 |
| Уведомления/предпочтения доставки | `NotificationsView.vue`, preferences | Центр + FCM-клиент, нет полного editor | P1 |
| Репутация/достижения | `AchievementsView.vue` | Неполный нативный сценарий | P2 |
| Звонки/медиа/мультиустройство | WebRTC + media controls | Частичная нативная реализация | P0 physical-device QA, прежде расширения |

## Как делаем следующие задачи

1. **Один пользовательский сценарий = серверный контракт + работающие Android экран и API + отказ/повтор + unit/CI + реальное устройство.**
2. Privacy, owner-only, block, moderation и E2EE **не имитируются интерфейсом**: backend — источник прав. Никакой автоматической замены encrypted DM на plaintext.
3. Не путать UI-наличие с feature-complete, особенно без реального устройства, FCM, TURN и WebRTC.
4. Новые API должны использовать текущую модель `AppController`, CSRF proof, `PubChatApi` и существующие backend routes, а не отдельный обход или новый auth-контракт.
5. Исправления Web продвигаются вместе с Android, когда исходный продуктовый сценарий слаб или противоречив; число экранов не является метрикой готовности.

### Очередь ближайших поставок

- [x] Создание Spaces (этот PR: `POST /spaces/v1`, full request, категории, приватность, открытие созданного Space).
- [ ] Приглашения: единый inbox / принять / отклонить / ошибочная или устаревшая заявка.
- [ ] Safety Center: жалоба из Persona/Space/DM, свои обращения, ограничения, апелляции.
- [ ] Messenger E2EE: device registration/key trust, reply/forward policy, never downgrade protection.
- [ ] Уведомления: реальные политики по категориям доставки и проверка FCM.
- [ ] Сквозной QA новых сценариев на физическом Android; задачи не закрывать одним зелёным Gradle.

Продуктовый приоритет нужно подтвердить результатами QA и фактической частотой использования. Внешние release/push функции не обещаются до проверки сервера и реального устройства.
