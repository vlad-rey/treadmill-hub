"use strict";
// Home: tiles with live status of the treadmill, power stations, network and hub. Refreshes every 5 s.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const pill = (id, text, cls) => { const el = $(id); el.textContent = text; el.className = "pill " + (cls || ""); };
const row = (label, value) => `<div class="row"><span>${esc(label)}</span><b>${esc(value)}</b></div>`;
const num = (v, d = 1) => v.toFixed(d).replace(".", ",");

function fmtTime(s) {
  s = Math.round(s || 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(sec).padStart(2, "0");
}
function upTime(s) {
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600);
  return d ? `${d} д ${h} ч` : `${h} ч ${Math.floor((s % 3600) / 60)} мин`;
}
function since(ms) {
  const m = Math.round((Date.now() - ms) / 60e3);
  return m < 60 ? `${m} мин` : `${Math.floor(m / 60)} ч ${m % 60} мин`;
}

function renderState(snap) {
  const t = snap.treadmill, s = snap.session, h = snap.hub, net = h.net || {};

  // Treadmill
  const connected = t.connection === "CONNECTED";
  if (s.active) {
    pill("trState", "идёт тренировка", "ok");
    $("trBody").innerHTML = `<div class="big">${num(t.speedKmh || 0)} км/ч</div>` +
      row("Время", fmtTime(s.movingS || t.elapsedS)) + row("Дистанция", num((s.distanceM || 0) / 1000, 2) + " км") +
      (snap.ownerName ? row("Тренируется", snap.ownerName) : "");
  } else {
    pill("trState", connected ? "на связи" : "выключена", connected ? "ok" : "");
    $("trBody").innerHTML = connected ? "<span>Готова к тренировке</span>" : "<span>Хаб подключится сам, когда дорожку включат в сеть</span>";
  }
  $("tTreadmill").classList.toggle("live", !!s.active);

  // Network
  const netBad = !!net.outage || net.routerOk === false || net.internetOk === false;
  pill("netState", net.routerOk == null ? "…" : netBad ? (net.routerOk === false ? "нет роутера" : "нет интернета") : "всё работает", netBad ? "bad" : "ok");
  $("netBody").innerHTML = row("Роутер", net.routerOk == null ? "—" : net.routerOk ? `${net.routerMs ?? "?"} мс` : "не отвечает") +
    row("Интернет", net.internetOk == null ? "—" : net.internetOk ? `${net.internetMs ?? "?"} мс` : "нет") +
    (net.outage ? row("Сбой идёт", since(net.outage.startMs)) : "");
  $("tNet").classList.toggle("bad", netBad);

  // Hub
  const hot = h.batteryTempC != null && h.batteryTempC >= 45;
  const hubBad = h.plugged === false || hot;
  pill("hubState", h.plugged === false ? "без зарядки" : hot ? "перегрев" : "работает", hubBad ? "bad" : "ok");
  $("hubBody").innerHTML = row("Батарея", h.batteryPct == null ? "—" : `${h.batteryPct} %` + (h.batteryTempC != null ? ` · ${num(h.batteryTempC)} °C` : "")) +
    row("Работает", upTime(h.uptimeS)) + row("Wi-Fi", h.wifiRssi != null ? `${h.wifiRssi} dBm` : "—");
  $("tHub").classList.toggle("bad", hubBad);
}

function renderPower(list) {
  if (!list.length) { pill("pwState", "не добавлены"); $("pwBody").innerHTML = "<span>Добавьте станции в «Список станций»</span>"; return; }
  const off = list.some((s) => s.state.gridOn === false);
  const lost = list.filter((s) => !s.state.connected).length;
  pill("pwState", off ? "нет света" : lost ? `нет связи: ${lost}` : "свет есть", off ? "bad" : lost ? "" : "ok");
  $("pwBody").innerHTML = list.map((s) => {
    const st = s.state;
    const soc = st.socPct == null ? "—" : Math.round(st.socPct) + " %";
    const flow = !st.connected ? "нет связи" : st.gridOn === false ? `от батареи, ${st.outputW} Вт` : st.acChargeW > 0 ? `заряжается ${st.acChargeW} Вт` : `выход ${st.outputW} Вт`;
    return row(`${s.name} · ${flow}`, soc);
  }).join("");
  $("tPower").classList.toggle("bad", off);
}

async function load() {
  try {
    const [snap, power] = await Promise.all([fetch("/api/state").then((r) => r.json()), fetch("/api/power").then((r) => r.json())]);
    renderState(snap);
    renderPower(power);
  } catch (_) {
    ["trState", "pwState", "netState", "hubState"].forEach((id) => pill(id, "хаб недоступен", "bad"));
  }
}

load();
setInterval(load, 5e3);
if ("serviceWorker" in navigator && window.isSecureContext) navigator.serviceWorker.register("/sw.js").catch(() => {});
