# 0001. Native Android hub and PWA clients

Status: accepted, 2026-09-24

## Context

We need to keep a permanent BLE connection to the treadmill and provide access to data and control from two phones (Pixel 9, Pixel 10) and from agents on the PC.

## Options considered

1. **Web Bluetooth directly from the client's browser.** The treadmill only holds one BLE connection, so clients would interfere with each other. The program would be interrupted if the phone locks. There'd be no single place for history.
2. **A web server running in Termux on the Redmi 6.** Termux doesn't have proper access to BLE GATT.
3. **A native Android app on the Redmi 6 (hub) + a PWA on the clients.** The hub is the sole owner of the BLE connection, runs programs, and stores history. Clients are thin.

## Decision

Option 3. The hub is a Kotlin foreground service with BLE, Ktor (REST + WebSocket), and SQLite. It also serves the PWA's static files. No separate mobile app for clients.

## Consequences

- Logic lives in one place; clients can be changed independently.
- The hub needs to be resilient to MIUI's background-work restrictions (addressed via root and settings, see redmi6-homeserver).
- The `TreadmillBackend` interface with a simulator makes it possible to develop and test without the treadmill.
