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
| GET | `/` | главная: меню с состоянием дорожки, станций, сети и хаба |
| GET | `/treadmill/` | дорожка (вкладки открываются ссылкой `#programs`, `#history`, `#awards`) |
| GET | `/hub/`, `/net/` | состояние хаба (батарея, память, связь с дорожкой, бэкап); сеть (роутер, интернет, журнал сбоев) |
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
| GET | `/power/` | страница станций Fossibot F2400: заряд, мощности, настройки, статистика |
| GET | `/api/power` | станции: `{id, name, address, state, stats}`; `stats` — итоги за `today/week/month/quarter/year/all` (`chargeSessions`, `chargedPct`, `dischargedPct`, `chargedWh`, `outputWh`, `offgridOutputWh`, `outages`, `outageS`) и `sinceDate`; цикл = `chargedPct / 100` |
| GET | `/api/power/outages` | журнал отключений света: `[{stationName, outage: {stationId, startMs, endMs, socStart, socEnd, minSoc, batteryWh, maxOutputW, approximate}}]`, новые сверху |
| GET | `/api/net/outages` | журнал сбоев сети `[{kind: ROUTER\|INTERNET, startMs, endMs}]`; текущее состояние — `/api/state` → `hub.net` (проверка каждые 20 с: пинг шлюза Wi-Fi, TCP к 1.1.1.1/8.8.8.8/9.9.9.9) |
| GET | `/api/router` | роутер ASUS: `{configured, user, connected, model, error, waitingForPassword, lastOkMs, clients, online, wanDownMbps, wanUpMbps}` (пароль не отдаётся) |
| POST | `/api/router/credentials` | `{user, password}` — логин и пароль администратора роутера; `password: null` — отключить роутер |
| GET | `/api/net/speed` | замеры скорости роутером: `{running, results: [{atMs, downMbps, upMbps, pingMs, error}]}` |
| POST | `/api/net/speed/run` | запустить замер сейчас (202; 409 — уже идёт или роутер не подключён) |
| GET | `/api/router/debug?hook=…` / `?page=/…` | отладка интеграции: сырой ответ роутера; только с самого телефона (`adb forward`), из Wi-Fi — 403 |
| GET | `/api/net/devices` | устройства в Wi-Fi: `{devices: [{mac, ip, name, hostname, firstSeenMs, lastSeenMs, known}], learnUntilMs, lastScanMs}` (опрос /24 раз в 5 мин, ARP; первые сутки — обучение) |
| POST/DELETE | `/api/net/devices/{mac}` | `{name?, known?}` — имя и «своё»; DELETE — забыть устройство |
| POST | `/api/power/stations` | список станций `[{id, name, address}]` (пустой `id` — новая) |
| POST | `/api/power/{id}/settings` | `{key, value}` — только разрешённые настройки, с проверкой чтением |

Лимиты проверяются на хабе: скорость 1–min(лимит, 16) км/ч, наклон 0–15 %. `stop` не ждёт в очереди за другими командами.

**Агентам:** `/api/control` двигает ленту — только с подтверждением владельца (см. `CLAUDE.md`). Для проверок без риска — `backend: "sim"` и перезапуск сервиса.

Сообщения в Telegram идут через очередь (`telegram-outbox.json`): без интернета они ждут и уходят позже с пометкой «отправлено с задержкой».

Иконка приложения рисуется скриптом `tools/icons/make_icons.py` (SVG-фавикон и PNG 32/180/192/512).

### Telegram-бот

Команды принимаются long polling (`getUpdates`). Владелец — чат из настроек хаба; другим чатам доступ открывает владелец (`/allow ID Имя_профиля`), список — `bot.json`.
- `/status`, `/progress`, `/week`, `/help` — всем, у кого есть доступ; `/charge 100` (50–100, шаг 5, можно номер станции), `/speedtest`, `/allow`, `/deny`, `/me`, `/chats` — владельцу.
- Понедельник 09:00 — отчёт за прошлую неделю: владельцу — дом (свет, станции, интернет, тренировки всех), каждому привязанному чату — его дорожка.
- 19:00 — напоминания о незаработанных наградах: недельные — в чт и сб, месячные — за 7, 3 и 1 день до конца месяца.

### Роутер ASUS

Вход как приложение ASUS Router (`login.cgi` → `asus_token`, User-Agent `asusrouter--DUTUtil-`), данные — `appGet.cgi?hook=…`: `get_clientlist()`, `netdev(appobj)`, `nvram_get(productid)`.
Замер скорости — встроенный Ookla: `ookla_speedtest_exe.cgi` → опрос `ookla_speedtest_get_result()` до записи `type=result` (полоса в байтах/с) → `ookla_speedtest_write_history.cgi`, чтобы замер был и в истории роутера. По расписанию — 7:00 и 21:00, во время тренировки откладывается; скорость ниже половины медианы 10 прошлых замеров — сообщение в Telegram.
Wi-Fi станций Fossibot (ESP32) узнаётся по MAC = Bluetooth-адрес − 2 и подписывается именем станции.
