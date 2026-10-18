# PubChat — утверждённый визуальный референс (18.10.2026)

**Статус:** дизайн-эталон редизайна Android, iOS и Web/PWA. Комплект из восьми PNG-досок сохранён как `PubChat_UI_Reference_2026-10-18.zip` в результатах беседы; исходные изображения являются визуальными макетами, **не** исходниками Figma. Не заявлять, что нарисованные функции уже реализованы.

## Доски
1. Mobile Design System: темы, нижняя навигация, кнопки, состояния.
2. Вход и онбординг: splash, формы, permissions, success.
3. Главная, Spaces, Discovery: каталог, поиск, события.
4. Чаты: входящие, чат, вложения, голос, реакции, ошибки отправки.
5. Звонки, голосовые комнаты и совместная активность.
6. Профиль, Persona, социальные связи, приватность, блокировка.
7. Уведомления, безопасность, настройки и модерация.
8. Состояния: skeleton, pull-to-refresh, empty, offline, reconnecting, error, denied, update, success.

## Единые токены v1

| Назначение | Light | Dark |
| --- | --- | --- |
| Background | `#F6F7FF` | `#0D1125` |
| Surface | `#FFFFFF` | `#171D34` |
| Elevated | `#FFFFFF` | `#222B45` |
| Primary | `#6347F5` | `#9A86FF` |
| Primary pressed | `#4C33D6` | `#B5A7FF` |
| Primary soft | `#ECE9FF` | `#2B2854` |
| Accent cyan | `#12BCD5` | `#54D7E9` |
| Text | `#18203D` | `#F5F6FF` |
| Text secondary | `#69738F` | `#B4BFDB` |
| Border | `#E2E6F5` | `#343D59` |
| Success | `#119968` | `#56D3A2` |
| Danger | `#DF4B64` | `#FF8296` |

Semantic radii: controls 12px, cards 18px, sheets 24px, pill 999px. Minimum touch target 44px iOS / 48dp Android. Body readable at 16sp and scalable Dynamic Type/font scale. Elevation via soft neutral shadows; never use blur behind long text. Gradient for hero / primary CTAs: `#7149FF → #4D54F7`. No permanent spinners for recoverable network errors.

## UI state acceptance matrix (every applicable screen)
- `idle`, `loading` (skeleton in place, no layout shift), `refreshing` (preserve content).
- `empty` with contextual CTA; `offline` with cached data label; `reconnecting` with progress and non-destructive retry.
- `error` with reason and safe retry; `forbidden` / `blocked` / `deleted` without leaking content.
- `success` feedback; `disabled`, `pressed`, `focused`, `permission-required`.
- For send actions show queued/sending/sent/delivered/read/failed, never claim delivery without server acknowledgement.
- Respect reduced motion, screen reader labels, keyboard/safe areas, contrast, Android edge-to-edge/iOS Dynamic Type.

## Navigation and implementation truth
Target visual bottom navigation: Spaces · Люди · Чаты · Активность · Профиль. Native clients show only implemented destinations or explain unavailable functionality; never ship a dead tab. Features in concepts such as full calls, E2EE and public multi-Persona require their own product/security gates. Match visual hierarchy across clients while retaining native gestures and accessibility.

**Delivery:** land shared tokens first, then components and actual screens in separate verified increments; compare screenshots (including loading/error/offline) to these eight boards. Do not weaken existing CI or replace functional controls with static mockups.
