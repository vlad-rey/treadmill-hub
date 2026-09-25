"use strict";

const $ = (id) => document.getElementById(id);
const PHASES = { IDLE: "ожидание", COUNTDOWN: "отсчёт", RUNNING: "движение", PAUSED: "пауза", STOPPING: "торможение", FINISHED: "остановлена" };
const CONN = { CONNECTED: "дорожка на связи", CONNECTING: "подключение…", SCANNING: "поиск дорожки…", DISCONNECTED: "нет связи с дорожкой" };
const ACTIVE_PHASES = ["COUNTDOWN", "RUNNING", "PAUSED", "STOPPING"];
const SPEED_PRESETS = [3, 4, 5, 6, 7, 8, 10, 12, 14, 16];

let toastTimer = 0;
let lastSnap = null;

// --- Профиль: хранится на хабе, телефон помнит только свой id -------------------------
let profiles = [];
let me = null;
const store = {
  get: () => { try { return localStorage.getItem("profileId"); } catch (_) { return null; } },
  set: (id) => { try { localStorage.setItem("profileId", id); } catch (_) {} },
};

async function loadProfiles() {
  profiles = await (await fetch("/api/profiles")).json();
  me = profiles.find((p) => p.id === store.get()) || null;
  $("profileBtn").textContent = me ? me.name : "кто вы?";
  presetsFor = null;
  if (!me) openProfileDialog(); else loadStats();
  if (window.onProfileChanged) window.onProfileChanged();
}

function openProfileDialog() {
  $("profileList").innerHTML = profiles
    .map((p) => `<button class="btn${me && me.id === p.id ? " current" : ""}" data-profile="${p.id}" value="pick">${p.name}</button>`)
    .join("");
  $("profileDlg").showModal();
}

$("profileList").addEventListener("click", (e) => {
  const b = e.target.closest("[data-profile]");
  if (!b) return;
  store.set(b.dataset.profile);
  loadProfiles();
});

$("createProfile").onclick = async (e) => {
  const name = $("newName").value.trim();
  if (!name) { e.preventDefault(); $("newName").focus(); return; }
  const r = await fetch("/api/profiles", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, weightKg: Number($("newWeight").value), maxSpeedKmh: Number($("newMax").value) }),
  });
  const body = await r.json();
  if (!r.ok) { toast(body.error || "не создано"); return; }
  store.set(body.id);
  loadProfiles();
};

$("profileBtn").onclick = openProfileDialog;

// --- Итоги: сегодня / неделя / месяц / всё время ---------------------------------------
async function loadStats() {
  if (!me) return;
  const st = await (await fetch("/api/stats?profile=" + encodeURIComponent(me.id))).json();
  const card = (label, t) => `<div class="stat"><label>${label}</label>
    <b>${(t.distanceM / 1000).toFixed(2)} км</b>
    <small>${fmtTime(t.movingS)} · ${Math.round(t.kcalCalc)} ккал (дорожка ${Math.round(t.kcalTreadmill)})</small></div>`;
  $("stats").innerHTML = card("Сегодня", st.today) + card("Неделя", st.week) + card("Месяц", st.month) + card("Всё время", st.all);
}
setInterval(loadStats, 60_000);

function fmtTime(s) {
  s = Math.floor(s || 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(sec).padStart(2, "0");
}

function render(snap) {
  const t = snap.treadmill, s = snap.session, hub = snap.hub;
  const connected = t.connection === "CONNECTED";

  $("conn").textContent = CONN[t.connection] || t.connection;
  $("conn").className = "pill " + (connected ? "ok" : "bad");
  const others = snap.ownerName && (!me || snap.ownerProfileId !== me.id) && s.active;
  $("phase").textContent = (PHASES[t.phase] || t.phase) + (others ? " · " + snap.ownerName : "");

  // Тренировка закончилась — обновить итоги
  if (wasActive && !s.active) { loadStats(); if (window.loadHistory) window.loadHistory(); }
  wasActive = s.active;
  $("hr").classList.toggle("hidden", !t.heartRate);
  $("hr").textContent = "♥ " + (t.heartRate || "");

  $("speed").textContent = t.speedKmh.toFixed(1);
  $("incline").firstChild.nodeValue = Math.round(t.inclinePct);
  $("inclineDeg").textContent = (Math.atan(t.inclinePct / 100) * 180 / Math.PI).toFixed(1) + "°";
  $("time").textContent = fmtTime(s.movingS || t.elapsedS);
  $("distance").textContent = (s.distanceM / 1000).toFixed(2);
  $("kcalCalc").textContent = Math.round(s.kcalCalc);
  $("kcalTm").textContent = "дорожка " + (t.kcal == null ? "—" : t.kcal.toFixed(1));

  $("countdown").classList.toggle("hidden", t.phase !== "COUNTDOWN");
  if (t.phase === "COUNTDOWN") $("countdown").textContent = t.countdown ?? "";

  renderPresets(me ? me.maxSpeedKmh : hub.maxSpeedKmh);
  document.querySelectorAll(".pad .btn").forEach((b) => {
    b.disabled = !connected;
    const v = Number(b.dataset.v);
    b.classList.toggle("current",
      (b.dataset.act === "speed" && t.phase === "RUNNING" && Math.abs(t.speedKmh - v) < 0.05) ||
      (b.dataset.act === "incline" && Math.round(t.inclinePct) === v));
  });
  renderActions(t, connected);
  keepScreenOn(ACTIVE_PHASES.includes(t.phase));
  if (window.renderProgram) window.renderProgram(snap.program);
  lastSnap = snap;
  if (window.renderHub) window.renderHub(snap);

  $("bucketRows").innerHTML = (s.buckets || [])
    .filter((b) => b.seconds >= 1)
    .sort((a, b) => a.speedKmh - b.speedKmh || a.inclinePct - b.inclinePct)
    .map((b) => `<tr><td>${b.speedKmh.toFixed(1)}</td><td>${b.inclinePct}</td><td>${fmtTime(b.seconds)}</td><td>${Math.round(b.meters)}</td></tr>`)
    .join("");
  $("hubInfo").textContent =
    `Хаб ${hub.version} · ${hub.backend === "sim" ? "симулятор" : "FTMS"} · батарея ${hub.batteryPct ?? "?"}%` +
    (hub.batteryTempC != null ? `, ${hub.batteryTempC.toFixed(1)} °C` : "") +
    ` · ккал: расчёт активные ${Math.round(s.kcalActiveCalc)}`;
}

let wasActive = false;
let presetsFor = null;
function renderPresets(max) {
  if (presetsFor === max) return;
  presetsFor = max;
  // Один ряд из 4 кнопок: самые ходовые скорости в пределах лимита
  const values = SPEED_PRESETS.filter((v) => v <= max).slice(0, 4);
  $("speedPresets").innerHTML = values.map((v) => `<button class="btn" data-act="speed" data-v="${v}">${v}</button>`).join("");
}

// --- Нижняя панель: СТАРТ или ПАУЗА|СТОП ---------------------------------------------
let mainMode = null;
let mainLockedUntil = 0;

function renderActions(t, connected) {
  const active = ACTIVE_PHASES.includes(t.phase);
  const mode = active ? "stop" : "start";
  const main = $("mainBtn"), pause = $("pauseBtn");
  if (mode !== mainMode) {
    // смена СТАРТ ↔ СТОП: секунда блокировки от случайного двойного нажатия
    if (mainMode !== null) mainLockedUntil = Date.now() + 1000;
    mainMode = mode;
    main.dataset.act = mode;
    main.textContent = active ? "СТОП" : "СТАРТ";
  }
  const showPause = t.phase === "RUNNING" || t.phase === "PAUSED";
  main.className = "action " + mode + (active && !showPause ? " solo" : "");
  main.disabled = mode === "start" && !connected; // СТОП доступен всегда

  pause.classList.toggle("hidden", !showPause);
  pause.dataset.act = t.phase === "PAUSED" ? "start" : "pause";
  pause.textContent = t.phase === "PAUSED" ? "ДАЛЬШЕ" : "ПАУЗА";
}

// --- Экран: полный экран и запрет засыпания -------------------------------------------
function inFullscreen() {
  return document.fullscreenElement || matchMedia("(display-mode: fullscreen), (display-mode: standalone)").matches;
}
function enterFullscreen() {
  if (inFullscreen() || !document.documentElement.requestFullscreen) return;
  document.documentElement.requestFullscreen({ navigationUI: "hide" }).catch(() => {});
}
function syncFullscreenBtn() { $("fullscreenBtn").classList.toggle("hidden", !!inFullscreen()); }
document.addEventListener("fullscreenchange", syncFullscreenBtn);
$("fullscreenBtn").onclick = enterFullscreen;
syncFullscreenBtn();

let wakeLock = null;
async function keepScreenOn(on) {
  // Screen Wake Lock работает только в защищённом контексте (HTTPS или флаг Chrome, см. docs)
  if (!("wakeLock" in navigator)) return;
  try {
    if (on && !wakeLock) {
      wakeLock = await navigator.wakeLock.request("screen");
      wakeLock.addEventListener("release", () => { wakeLock = null; });
    } else if (!on && wakeLock) {
      await wakeLock.release();
    }
  } catch (_) { /* нет разрешения — экран может погаснуть */ }
}

// --- Команды ----------------------------------------------------------------------
function toast(msg) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 3500);
}

async function control(action, value, extra = {}) {
  // Тренировка записывается на того, кто нажал СТАРТ — без профиля не начинаем
  if ((action === "start" || action === "program") && !me) { openProfileDialog(); return false; }
  try {
    const r = await fetch("/api/control", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action, value, profileId: me ? me.id : null, ...extra }),
    });
    const body = await r.json();
    if (!body.ok) toast(body.message);
    return body.ok;
  } catch (e) {
    toast("хаб недоступен");
    return false;
  }
}

document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-act]");
  if (b && b.closest("dialog")) return; // кнопки в диалогах обрабатываются отдельно
  if (!b || b.disabled) return;
  if (b.id === "mainBtn" && Date.now() < mainLockedUntil) return;
  enterFullscreen(); // первое же нажатие убирает адресную строку
  if (navigator.vibrate) navigator.vibrate(15);
  control(b.dataset.act, b.dataset.v == null ? null : Number(b.dataset.v));
});

function connectLive() {
  const ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws/live");
  ws.onmessage = (m) => render(JSON.parse(m.data));
  ws.onclose = () => {
    $("conn").textContent = "нет связи с хабом";
    $("conn").className = "pill bad";
    setTimeout(connectLive, 2000);
  };
}

$("settingsBtn").onclick = () => {
  if (!me) { openProfileDialog(); return; }
  $("name").value = me.name;
  $("weight").value = me.weightKg;
  $("maxSpeed").value = me.maxSpeedKmh;
  $("settings").showModal();
};
$("saveSettings").onclick = async () => {
  const r = await fetch("/api/profiles/" + encodeURIComponent(me.id), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: $("name").value.trim(), weightKg: Number($("weight").value), maxSpeedKmh: Number($("maxSpeed").value) }),
  });
  if (!r.ok) { toast((await r.json()).error || "не сохранено"); return; }
  loadProfiles();
};
$("switchProfile").onclick = () => setTimeout(openProfileDialog, 0);

if ("serviceWorker" in navigator && window.isSecureContext) navigator.serviceWorker.register("/sw.js").catch(() => {});

loadProfiles().catch(() => toast("хаб недоступен"));
connectLive();
