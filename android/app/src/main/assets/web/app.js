"use strict";

const $ = (id) => document.getElementById(id);
const PHASES = { IDLE: "ожидание", COUNTDOWN: "отсчёт", RUNNING: "движение", PAUSED: "пауза", STOPPING: "торможение", FINISHED: "остановлена" };
const CONN = { CONNECTED: "дорожка подключена", CONNECTING: "подключение…", SCANNING: "поиск дорожки…", DISCONNECTED: "нет связи с дорожкой" };

let last = null;
let toastTimer = 0;

function fmtTime(s) {
  s = Math.floor(s || 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(sec).padStart(2, "0");
}

function render(snap) {
  last = snap;
  const t = snap.treadmill, s = snap.session, hub = snap.hub;

  const connected = t.connection === "CONNECTED";
  $("conn").textContent = CONN[t.connection] || t.connection;
  $("conn").className = "pill " + (connected ? "ok" : "bad");
  $("phase").textContent = PHASES[t.phase] || t.phase;

  $("speed").textContent = t.speedKmh.toFixed(1);
  $("incline").textContent = Math.round(t.inclinePct);
  $("inclineDeg").textContent = "% · " + (Math.atan(t.inclinePct / 100) * 180 / Math.PI).toFixed(1) + "°";
  $("time").textContent = fmtTime(s.movingS || t.elapsedS);
  $("distance").textContent = (s.distanceM / 1000).toFixed(2);
  $("kcalCalc").textContent = Math.round(s.kcalCalc);
  $("kcalActive").textContent = "активные " + Math.round(s.kcalActiveCalc);
  $("kcalTm").textContent = t.kcal == null ? "—" : t.kcal.toFixed(1);
  $("hr").textContent = "пульс " + (t.heartRate || "—");

  const cd = $("countdown");
  cd.classList.toggle("hidden", t.phase !== "COUNTDOWN");
  if (t.phase === "COUNTDOWN") cd.textContent = t.countdown ?? "";

  document.querySelectorAll("[data-act]").forEach((b) => {
    if (b.dataset.act !== "stop") b.disabled = !connected;
  });

  renderPresets(hub.maxSpeedKmh);
  $("bucketRows").innerHTML = (s.buckets || [])
    .filter((b) => b.seconds >= 1)
    .sort((a, b) => a.speedKmh - b.speedKmh || a.inclinePct - b.inclinePct)
    .map((b) => `<tr><td>${b.speedKmh.toFixed(1)}</td><td>${b.inclinePct}</td><td>${fmtTime(b.seconds)}</td><td>${Math.round(b.meters)}</td></tr>`)
    .join("");
  $("hubInfo").textContent =
    `Хаб ${hub.version} · ${hub.backend === "sim" ? "симулятор" : "FTMS"} · батарея ${hub.batteryPct ?? "?"}%` +
    (hub.batteryTempC != null ? `, ${hub.batteryTempC.toFixed(1)} °C` : "");
}

let presetsFor = null;
function renderPresets(max) {
  if (presetsFor === max) return;
  presetsFor = max;
  const values = [3, 4, 5, 6, 8, 10, 12, 14, 16].filter((v) => v <= max);
  $("speedPresets").innerHTML = '<span class="rowLabel"></span>' +
    values.map((v) => `<button data-act="speed" data-v="${v}">${v}</button>`).join("");
}

function toast(msg) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 3500);
}

async function control(action, value) {
  try {
    const r = await fetch("/api/control", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(value == null ? { action } : { action, value }),
    });
    const body = await r.json();
    if (!body.ok) toast(body.message);
  } catch (e) {
    toast("хаб недоступен");
  }
}

document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-act]");
  if (!b || b.disabled) return;
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

$("settingsBtn").onclick = async () => {
  const cfg = await (await fetch("/api/config")).json();
  $("weight").value = cfg.weightKg;
  $("maxSpeed").value = cfg.maxSpeedKmh;
  $("settings").showModal();
};
$("saveSettings").onclick = async () => {
  const r = await fetch("/api/config", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ weightKg: Number($("weight").value), maxSpeedKmh: Number($("maxSpeed").value) }),
  });
  if (!r.ok) toast((await r.json()).error || "не сохранено");
};

connectLive();
