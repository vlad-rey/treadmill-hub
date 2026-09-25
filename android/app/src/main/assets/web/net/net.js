"use strict";
// Network: router and internet (checked on the hub every 20 s), outage log.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const clock = (ms) => new Date(ms).toLocaleString("ru-RU", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const hm = (ms) => new Date(ms).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
const card = (label, value, sub) => `<div class="stat"><label>${label}</label><b>${value}</b><small>${sub || ""}</small></div>`;
function dur(ms) {
  const m = Math.round(ms / 60e3);
  return m < 1 ? "меньше минуты" : m < 60 ? `${m} мин` : `${Math.floor(m / 60)} ч ${m % 60} мин`;
}

function render(snap) {
  const net = snap.hub.net || {};
  const warns = [];
  if (net.outage) warns.push(["bad", (net.outage.kind === "ROUTER" ? "Хаб не видит роутер" : "Нет интернета (роутер работает)") +
    ` с ${clock(net.outage.startMs)}. Сообщения в Telegram уйдут, когда связь вернётся.`]);
  $("netWarnings").innerHTML = (warns.length ? warns : [["ok", net.routerOk == null ? "Первая проверка…" : "Роутер и интернет работают"]])
    .map(([cls, text]) => `<div class="warn ${cls}">${esc(text)}</div>`).join("");
  $("netCards").innerHTML = [
    card("Роутер", net.routerOk == null ? "—" : net.routerOk ? "на связи" : "не отвечает", net.routerMs != null ? `пинг ${net.routerMs} мс` : ""),
    card("Интернет", net.internetOk == null ? "—" : net.internetOk ? "есть" : "нет", net.internetMs != null ? `подключение ${net.internetMs} мс` : ""),
    card("Проверено", net.checkedAtMs ? hm(net.checkedAtMs) : "—", "каждые 20 с"),
  ].join("");
}

async function loadLog() {
  try {
    const list = await (await fetch("/api/net/outages")).json();
    $("netLog").innerHTML = list.length ? list.slice(0, 50).map((o) => {
      const now = o.endMs == null;
      const what = o.kind === "ROUTER" ? "📶 Роутер недоступен" : "🌐 Нет интернета";
      return `<div class="warn ${now ? "bad" : ""}">${what}: ${clock(o.startMs)} – ${now ? "сейчас" : hm(o.endMs)} · ${dur((now ? Date.now() : o.endMs) - o.startMs)}</div>`;
    }).join("") : `<p class="muted">Сбоев не было. Сбой засчитывается после трёх неудачных проверок подряд.</p>`;
  } catch (_) { /* no connection to the hub */ }
}

// --- ASUS router ---------------------------------------------------------------------------
async function loadRouter() {
  try {
    const r = await (await fetch("/api/router")).json();
    let html;
    if (!r.configured) html = `<div class="warn">Роутер не подключён — введите логин и пароль администратора ниже.</div>`;
    else if (r.connected) html = `<div class="warn ok">✅ ${esc(r.model || "Роутер ASUS")} · клиентов ${r.clients}, в сети ${r.online}` +
      (r.wanDownMbps != null ? ` · интернет за 5 мин: ↓ ${r.wanDownMbps} ↑ ${r.wanUpMbps} Мбит/с` : "") + ` · опрос в ${hm(r.lastOkMs)}</div>`;
    else if (r.error) html = `<div class="warn bad">${esc(r.error)}${r.waitingForPassword ? " — введите пароль заново." : ""}</div>`;
    else html = `<div class="warn">Подключаюсь к роутеру…</div>`;
    $("routerStatus").innerHTML = html;
    if (r.user && document.activeElement !== $("rtUser")) $("rtUser").value = r.user;
    $("routerSummary").textContent = r.configured ? `Логин и пароль (сейчас: ${r.user || "admin"})` : "Логин и пароль администратора роутера";
    if (!r.configured || r.waitingForPassword) $("routerForm").open = true;
  } catch (_) { /* no connection to the hub */ }
}
async function saveRouter(password) {
  const r = await fetch("/api/router/credentials", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ user: $("rtUser").value.trim() || "admin", password }),
  });
  $("rtPass").value = "";
  if (!r.ok) { alert((await r.json()).error || "не сохранено"); return; }
  $("routerStatus").innerHTML = `<div class="warn">${password ? "Подключаюсь к роутеру…" : "Роутер отключён."}</div>`;
  $("routerForm").open = false;
  setTimeout(() => { loadRouter(); loadDevices(); }, 4000);
}
$("routerCreds").addEventListener("submit", (e) => {
  e.preventDefault();
  if (!$("rtPass").value) { $("rtPass").focus(); return; }
  saveRouter($("rtPass").value);
});
$("rtClear").onclick = () => { if (confirm("Отключить роутер? Хаб забудет пароль.")) saveRouter(null); };

// --- Internet speed: router measurements --------------------------------------------------
const f0 = (v) => v == null ? "—" : Math.round(v).toLocaleString("ru-RU");
let speedPoll = 0;
async function loadSpeed() {
  try {
    const d = await (await fetch("/api/net/speed")).json();
    const ok = d.results.filter((r) => r.downMbps != null);
    const last = d.results[d.results.length - 1];
    const week = ok.filter((r) => Date.now() - r.atMs < 7 * 86400e3);
    const avg = (a) => a.length ? a.reduce((x, y) => x + y, 0) / a.length : null;
    $("speedCards").innerHTML = [
      card("Приём", last && last.downMbps != null ? `${f0(last.downMbps)} Мбит/с` : "—", last ? (last.error ? "ошибка: " + esc(last.error) : clock(last.atMs)) : "замеров ещё не было"),
      card("Отдача", last && last.upMbps != null ? `${f0(last.upMbps)} Мбит/с` : "—", last && last.pingMs != null ? `пинг ${last.pingMs} мс` : ""),
      card("Среднее за неделю", week.length ? `${f0(avg(week.map((r) => r.downMbps)))} ↓` : "—", week.length ? `${f0(avg(week.map((r) => r.upMbps).filter((v) => v != null)))} ↑ · замеров ${week.length}` : ""),
      card("Минимум за неделю", week.length ? `${f0(Math.min(...week.map((r) => r.downMbps)))} ↓` : "—", ""),
    ].join("");
    drawSpeed(ok.slice(-60));
    $("speedRun").disabled = d.running;
    $("speedRun").textContent = d.running ? "Идёт замер… (около минуты)" : "Замерить сейчас";
    clearTimeout(speedPoll);
    if (d.running) speedPoll = setTimeout(loadSpeed, 4000);
  } catch (_) { /* no connection to the hub */ }
}
function drawSpeed(list) {
  const svg = $("speedChart");
  if (list.length < 2) { svg.innerHTML = `<text x="500" y="105" text-anchor="middle" class="chartEmpty">график появится после двух замеров</text>`; $("speedRange").textContent = ""; return; }
  const max = Math.max(...list.map((r) => Math.max(r.downMbps, r.upMbps || 0))) * 1.1;
  const x = (i) => (i / (list.length - 1)) * 1000, y = (v) => 195 - (v / max) * 185;
  const line = (k) => list.map((r, i) => r[k] == null ? "" : `${i ? "L" : "M"}${x(i).toFixed(1)},${y(r[k]).toFixed(1)}`).join(" ");
  const grid = [0.25, 0.5, 0.75].map((f) => `<line class="grid" x1="0" x2="1000" y1="${y(max * f)}" y2="${y(max * f)}"/><text class="gridLbl" x="4" y="${y(max * f) - 4}">${Math.round(max * f)}</text>`).join("");
  svg.innerHTML = grid + `<path class="spDown" d="${line("downMbps")}"/><path class="spUp" d="${line("upMbps")}"/>`;
  $("speedRange").textContent = `${clock(list[0].atMs)} – ${clock(list[list.length - 1].atMs)}, Мбит/с`;
}
$("speedRun").onclick = async () => {
  const r = await fetch("/api/net/speed/run", { method: "POST" });
  if (!r.ok) alert((await r.json()).error || "не удалось");
  loadSpeed();
};

// --- Wi-Fi devices: the hub polls the network every 5 min; a new unknown device — message in Telegram ---
const isRandom = (mac) => (parseInt(mac.slice(0, 2), 16) & 2) === 2;
function seen(ms) {
  const m = Math.round((Date.now() - ms) / 60e3);
  return m < 10 ? "в сети" : m < 60 ? `${m} мин назад` : m < 1440 ? `${Math.round(m / 60)} ч назад` : `${Math.round(m / 1440)} дн назад`;
}
let editingDevice = false;
async function loadDevices() {
  if (editingDevice) return;
  try {
    const d = await (await fetch("/api/net/devices")).json();
    const online = d.devices.filter((x) => Date.now() - x.lastSeenMs < 10 * 60e3).length;
    $("devTitle").textContent = `Устройства в сети · ${online} сейчас, ${d.devices.length} всего`;
    $("devNote").textContent = (d.learnUntilMs > Date.now()
      ? `Хаб запоминает свои устройства до ${clock(d.learnUntilMs)} — всё, что появится до этого, считается своим. `
      : "Новое незнакомое устройство — сообщение в Telegram. ") +
      (d.lastScanMs ? `Проверка раз в 5 мин, последняя в ${hm(d.lastScanMs)}.` : "Первая проверка — через минуту после запуска хаба.");
    $("devices").innerHTML = d.devices.map((x) => `<div class="device ${x.known ? "" : "new"} ${Date.now() - x.lastSeenMs < 10 * 60e3 ? "" : "off"}">
        <div class="dMain">
          <input data-mac="${esc(x.mac)}" value="${esc(x.name || "")}" placeholder="${esc(x.hostname || "без имени")}" maxlength="40" autocomplete="off">
          <small>${[x.hostname && x.name ? esc(x.hostname) : "", x.vendor ? esc(x.vendor) : "", x.link ? esc(x.link) : "", esc(x.ip), esc(x.mac) + (isRandom(x.mac) ? " (случайный)" : "")].filter(Boolean).join(" · ")}</small>
        </div>
        <div class="dSide">
          <span class="pill ${Date.now() - x.lastSeenMs < 10 * 60e3 ? "ok" : ""}">${seen(x.lastSeenMs)}</span>
          ${x.known ? "" : `<button type="button" class="btn small" data-known="${esc(x.mac)}">Своё</button>`}
        </div>
      </div>`).join("") || `<p class="muted">Пока никого не видно.</p>`;
  } catch (_) { /* no connection to the hub */ }
}
async function patchDevice(mac, body) {
  const r = await fetch(`/api/net/devices/${encodeURIComponent(mac)}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  if (!r.ok) alert((await r.json()).error || "не сохранено");
}
$("devices").addEventListener("focusin", (e) => { if (e.target.dataset.mac) editingDevice = true; });
$("devices").addEventListener("focusout", async (e) => {
  const el = e.target;
  if (!el.dataset.mac) return;
  await patchDevice(el.dataset.mac, { name: el.value.trim() });
  editingDevice = false;
  loadDevices();
});
$("devices").addEventListener("keydown", (e) => { if (e.key === "Enter" && e.target.dataset.mac) e.target.blur(); });
$("devices").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-known]");
  if (!b) return;
  await patchDevice(b.dataset.known, { known: true });
  loadDevices();
});

async function load() {
  try { render(await (await fetch("/api/state")).json()); }
  catch (_) { $("netWarnings").innerHTML = `<div class="warn bad">Хаб недоступен</div>`; }
}
load();
loadLog();
loadDevices();
loadRouter();
loadSpeed();
setInterval(loadSpeed, 60e3);
setInterval(loadDevices, 60e3);
setInterval(loadRouter, 30e3);
setInterval(load, 5e3);
setInterval(loadLog, 30e3);
