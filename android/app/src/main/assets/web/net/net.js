"use strict";
// Сеть: роутер и интернет (проверка на хабе каждые 20 с), журнал сбоев.

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
  } catch (_) { /* нет связи с хабом */ }
}

async function load() {
  try { render(await (await fetch("/api/state")).json()); }
  catch (_) { $("netWarnings").innerHTML = `<div class="warn bad">Хаб недоступен</div>`; }
}
load();
loadLog();
setInterval(load, 5e3);
setInterval(loadLog, 30e3);
