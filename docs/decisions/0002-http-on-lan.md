# 0002. HTTP on the LAN and a Chrome flag instead of HTTPS

Status: accepted, 2026-09-24

## Context

Installing a PWA and running a service worker requires a secure context (HTTPS or localhost). The hub is reachable at an address like `http://192.168.x.x:8080`. Both clients are Pixels running Chrome. Access is needed only from home.

## Options considered

1. Tailscale + `tailscale cert`: requires Tailscale on every device. Overkill for home-only access.
2. Our own domain + Let's Encrypt (DNS-01) + an A record pointing to the local IP: needs a domain and certificate renewal. Some routers block DNS responses with private addresses.
3. HTTP with a home-screen shortcut: works immediately, but without a service worker.
4. HTTP + the `chrome://flags/#unsafely-treat-insecure-origin-as-secure` flag with the hub's address on each Pixel: Chrome treats the address as secure, and the PWA installs fully.

## Decision

Start with option 3, then move to option 4. The PWA is built from the start with a manifest and a service worker, so a later move to HTTPS (option 2) won't require rework.

## Consequences

- The hub needs a stable IP (DHCP reservation on the router).
- The flag sometimes gets reset when Chrome updates and needs to be set again.
