// Service worker: makes the UI installable as an app. Data always comes from the hub;
// the cache only serves the page shell if the hub is briefly unavailable.
const CACHE = "treadmill-shell-v15";
const SHELL = ["/", "/treadmill/", "/power/", "/static/home/home.css", "/static/home/home.js", "/static/favicon.svg", "/static/sitebar.js", "/static/style.css", "/static/app.js", "/static/programs.js", "/static/awards.js", "/static/manifest.webmanifest", "/static/icon-192.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.pathname.startsWith("/api/") || url.pathname.startsWith("/ws/")) return;
  e.respondWith(
    fetch(e.request)
      .then((r) => { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); return r; })
      .catch(() => caches.match(e.request)),
  );
});
