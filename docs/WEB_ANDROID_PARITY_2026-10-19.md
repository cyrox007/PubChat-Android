# Web ↔ Android: проверенный функциональный паритет (19.10.2026)

> Срез актуализирован по слитым PR **#51 и #53** и состоянию `master`. **Код / CI / реальные устройства / релизная приёмка — разные этапы.** Централизованный журнал всех трёх клиентов: [PubChat — delivery status](https://github.com/cyrox007/PubChat/blob/main/docs/cross-client-delivery-status.md). Планы и очередность Android — [issue #52](https://github.com/cyrox007/PubChat-Android/issues/52).

## Подтверждено кодом

| Сценарий | Web / backend | Android `master` | Что осталось |
| --- | --- | --- | --- |
| Identity v2, Persona, session refresh | API/UI | API/UI | Проверка устройств, 401/revocation |
| Private Multi-Persona (до 3, выбор для сессии) | [PubChat PR #108](https://github.com/cyrox007/PubChat/pull/108) | [Android PR #35](https://github.com/cyrox007/PubChat-Android/pull/35) | Публичная адресация Persona и device QA |
| Friendship hub, requests, block | Web [#109](https://github.com/cyrox007/PubChat/pull/109) | Android [#36](https://github.com/cyrox007/PubChat-Android/pull/36) | End-to-end 401/403/409 и revocation |
| People discovery, intent и live availability TTL | Web + `/social/v1` | Код/UI | QA pagination, offline/reconnect |
| **Создание Space (name/description/purpose/visibility/join policy/categories/cap/tags)** | `POST /spaces/v1` | **Готово в коде: [PR #51](https://github.com/cyrox007/PubChat-Android/pull/51), merge `05653df`**, обе CI-конфигурации green | **Реальный Android: create → сервер → открыть Space**, private/invite-only, errors |
| **DM consent policy** | `PATCH /identity/v2/privacy`, `dm_policy` | **Готово в коде: [PR #53](https://github.com/cyrox007/PubChat-Android/pull/53), merge `7160c5d`**, enabled/disabled CI green | Device QA; запреты и revoke на каждом клиенте |
| Space каталог/чат/управление участниками | Web + API | Код/UI | Device/network QA, parity owner permissions |
| **Space invitations: inbox → принять/отклонить → открыть** | Web + API | **[PR #56](https://github.com/cyrox007/PubChat-Android/pull/56), merge `07cae34`: код слит, обе конфигурации CI green**; физическая приёмка отдельно | Требуется реальный Android QA и Web↔Android revoked/expired/blocked parity |
| Space Activities, повторы, напоминания | Web + API | Часть событий room panel | Нативный полноценный сценарий, server timezone |
| Messenger текст и действия | Web + API | Базовый Messenger | Поведение отказов и network QA |
| **Messenger native E2EE** | Web security policy + `crypto/v1` | **Device identity foundation** (this PR): Ed25519/X25519/signed prekeys, Android Keystore persistence, explicit registration/list/revoke. **No native encrypted send/decrypt or peer verification**. Legacy no-downgrade [#58](https://github.com/cyrox007/PubChat-Android/pull/58) merged | **P0 security:** sessions/envelopes, peer trust, multi-device, decrypt, attachments, physical interoperability and security review |
| **Safety Center / reports / appeals** | Web + Trust & Safety API | **Нет полноценного сценария** | **P0 safety** |
| Устройства, отзыв сессий | Web + Identity API | Нет полного editor | P1 |
| Уведомления | Web + preferences | Notification center, FCM foundation | P1 preference editor + реальный FCM |
| Calls/WebRTC | Web + signaling | Частичный native WebRTC | Реальные 2×OEM, Wi-Fi/LTE, фон, ICE/TURN |

## Статус приёмки отдельно от реализации

- **E2EE gate Android:** этот PR добавляет защиту от plaintext downgrade и объяснение режима переписки. Нельзя считать реализованными E2EE криптографию, клиентские ключи и расшифровку, пока они не будут реализованы и проверены на устройствах. Сервер остается окончательным enforcement.

- **Space creation:** code merged, CI confirmed in PR #51; **physical Android QA ещё не подтверждён**. Не планировать создание формы/API повторно.
- **DM consent:** code merged, обе конфигурации Android CI run [39315301247](https://github.com/cyrox007/PubChat-Android/actions/runs/39315301247) successful; **device/cross-client privacy QA остаётся**. Не создавать второй редактор policy.
- **Friendships / Multi-Persona / live availability:** code exists across clients в соответствующих срезах, но публичный Multi-Persona, cross-account leakage QA и acceptance ещё не закрыты.
- **Messenger E2EE:** наличие plaintext composer не означает безопасный DM в условиях `required`. Не маркировать безопасность Messenger зелёной до криптографической реализации и проверок.

## Запланированный Social Content / Media (не реализовано)

Новый [PubChat Epic #134](https://github.com/cyrox007/PubChat/issues/134) и [Social Content & Media v1](https://github.com/cyrox007/PubChat/blob/main/docs/social-content-media-platform-v1.md) вводят личную стену/ленту (текст, фото, смешанные посты), встроенное **граффити с рисовалкой**, видео/музыку, **24-часовые Stories** и **Reels/клипы**. Обязательная база — **S3-compatible MediaService с глобальной и per-Account квотой**, которой разделяют все Persona: [storage #135](https://github.com/cyrox007/PubChat/issues/135) → [posts #136](https://github.com/cyrox007/PubChat/issues/136) → [feed #137](https://github.com/cyrox007/PubChat/issues/137), затем [graffiti #138](https://github.com/cyrox007/PubChat/issues/138), [video/music #139](https://github.com/cyrox007/PubChat/issues/139), [Stories #140](https://github.com/cyrox007/PubChat/issues/140), [Reels #141](https://github.com/cyrox007/PubChat/issues/141).

**Состояние на 20.10.2026: PLANNED, не native code.** Android должен использовать единый backend API и серверную приватность/quota, без собственных несогласованных реализаций и без повторного написания уже готовой регистрации криптоустройств. UI — Compose, touch/stylus graffiti, mobile reels/player, upload progress, retry/offline, реальные устройства. [Паритет и QA #142](https://github.com/cyrox007/PubChat/issues/142). Security P0 E2EE/Safety Center сохраняет приоритет.

## Следующая функциональная очередь

1. **Space invitations acceptance (P0):** [Android #56](https://github.com/cyrox007/PubChat-Android/pull/56) реализует inbox, page, accept/decline и подтверждённый вход. Осталось: Android physical QA (expired/revoked/block/private/invite-only), Web↔Android parity, затем iOS клиент. Не начинать код UI/API заново.
2. **Messenger E2EE (P0 security):** устройства/ключи, доверие, отказ при required E2EE, no silent plaintext downgrade, Web↔Android tests.
3. **Safety Center (P0 safety):** жалоба на Persona/Space/message, своя история, ограничения и апелляция без утечек модераторских полей.
4. **P1:** полноценные Activities, управление сессиями, уведомления/FCM и согласие на контакты с физической QA матрицей.

## Правило следующего PR

Перед любой реализацией проверить реальные merged PR и `master`; после каждого merge обновить здесь строку и GitHub issue #52 с отдельными состояниями **код, CI, физическое устройство**. Работа над экраном не закрывает весь пользовательский сценарий. Backend владеет permission/privacy/DM/E2EE; Android выполняет действия только с подтверждённым API и не имитирует серверные решения.

Android release-ready только после проверенных пользовательских сценариев и реальной device-матрицы; зелёный Gradle не равен 1.0 parity.
