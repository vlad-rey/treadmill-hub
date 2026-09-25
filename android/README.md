# android — хаб

Kotlin, `minSdk 28` (Redmi 6: Android 9, 32-битный). Foreground service `HubService`:

- `treadmill/` — `TreadmillBackend`: `FtmsBleBackend` (FTMS + FitShow FFF1, библиотека Nordic BLE) и `SimulatorBackend`; разбор пакетов в `Codecs.kt`.
- `session/` — учёт тренировки: дистанция по скорости (FTMS на T12B отдаёт 0), разбивка «скорость × наклон», калории по ACSM.
- `HubServer.kt` — Ktor (CIO): REST, WebSocket, статика веб-интерфейса из `assets/web`.

## Сборка и установка

```bash
powershell -ExecutionPolicy Bypass -File tools\deploy-hub.ps1 -Serial <IP>:5555
```

Нужны JDK 17 и Android SDK. Скрипт гоняет unit-тесты, собирает APK, ставит через root (`pm install` — MIUI блокирует `adb install`), выдаёт разрешения и перезапускает сервис. Автозапуск при загрузке — Magisk-скрипт `40-treadmill-hub.sh` из [redmi6-homeserver](https://github.com/vlad-rey/redmi6-homeserver).

Версии подобраны под AGP 8.13: Kotlin 2.2.21, coroutines 1.10.2, serialization 1.9.0 (более новые собраны Kotlin 2.4, D8 из AGP 8.13 их метаданные не понимает).

## API (порт 8080)

| Метод | Путь | Что |
|---|---|---|
| GET | `/` | веб-интерфейс |
| GET | `/api/state` | снимок: `treadmill`, `session`, `hub` |
| WS | `/ws/live` | тот же снимок при каждом изменении (~1 Гц) |
| POST | `/api/control` | `{"action": "start\|stop\|pause\|speed\|incline\|speedDelta\|inclineDelta\|program\|programEnd", "value": 5.0, "profileId": "…", "programId": "P3", "level": 4, "minutes": 30}` |
| GET/POST | `/api/config` | `deviceAddress`, `backend` (`ftms`/`sim`), `weightKg`, `maxSpeedKmh` (по умолчанию 12) |
| WS | `/ws/debug/ble` | сырые BLE-пакеты в hex (для агентов) |
| GET/POST | `/api/profiles`, `/api/profiles/{id}` | профили: имя, вес, лимит скорости |
| GET/POST | `/api/profiles/{id}/weights` | история веса; POST `{kg}` — новая запись и новый текущий вес профиля |
| GET | `/api/export/sessions.csv?profile=ID`, `/api/sessions/{id}/export?format=tcx\|csv` | экспорт: таблица тренировок; TCX для Strava/Garmin; посекундный CSV |
| POST | `/api/hub/backup` | отметка скрипта бэкапа на PC |
| GET | `/api/game/{profileId}` | ачивки (прогресс, получено) и реальные награды профиля (прогресс за период, заработано, вручено) |
| POST | `/api/game/{profileId}/rewards` | задать реальные награды профиля: `[{id, title, icon, period: WEEK\|MONTH, km, effect: sound\|fireworks}]` |
| POST | `/api/game/celebrations/{id}/ack`, `/api/game/rewards/{id}/{period}/delivered`, `/api/game/telegram-test` | подтвердить окно; отметить «вручено»; пробное сообщение в Telegram |
| GET | `/api/stats?profile=ID` | итоги: сегодня / неделя / месяц / всё время |
| GET | `/api/sessions?profile=ID`, `/api/sessions/{id}` | история (пустой `profile=` — без владельца) |
| POST | `/api/sessions/{id}/profile`, `/api/sessions/{id}/console` | переназначить владельца; показания пульта для сверки |
| GET | `/api/programs?profile=ID` | встроенные P1–P8 + свои (общие и профиля) |
| GET | `/api/programs/{id}/segments?level=&minutes=&profile=` | отрезки для предпросмотра (скорость ≤ лимита профиля) |
| POST/DELETE | `/api/programs`, `/api/programs/{id}` | свои программы: `{name, profileId, blocks:[{repeat, steps:[{durationS, speedKmh, inclinePct?}]}]}` |

Лимиты проверяются на хабе: скорость 1–min(лимит, 16) км/ч, наклон 0–15 %. `stop` не ждёт в очереди за другими командами.

**Агентам:** `/api/control` двигает ленту — только с подтверждением владельца (см. `CLAUDE.md`). Для проверок без риска — `backend: "sim"` и перезапуск сервиса.
