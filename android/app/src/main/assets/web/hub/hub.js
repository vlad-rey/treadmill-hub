"use strict";
// Hub: phone-server status and treadmill connection. Warnings shown only here, no notifications.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const clock = (ms) => new Date(ms).toLocaleString("ru-RU", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const ago = (ms) => {
  if (!ms) return "—";
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 90) return "только что";
  if (s < 5400) return Math.round(s / 60) + " мин назад";
  if (s < 172800) return Math.round(s / 3600) + " ч назад";
  return Math.round(s / 86400) + " дн назад";
};
const upTime = (s) => (s >= 86400 ? Math.floor(s / 86400) + " дн " : "") + Math.floor((s % 86400) / 3600) + " ч " + Math.floor((s % 3600) / 60) + " мин";
const card = (label, value, sub) => `<div class="stat"><label>${label}</label><b>${value}</b><small>${sub || ""}</small></div>`;

function render(snap) {
  const h = snap.hub, t = snap.treadmill, link = h.link || {};
  const warns = [];
  if (h.batteryPct != null && h.batteryPct < 25 && !h.charging) warns.push(["bad", `Батарея хаба ${h.batteryPct} % и не заряжается — проверьте кабель зарядки`]);
  if (h.plugged === false) warns.push(["bad", "Зарядка хаба отключена — телефон работает от батареи"]);
  if (h.batteryTempC != null && h.batteryTempC >= 45) warns.push(["bad", `Хаб перегревается: ${h.batteryTempC.toFixed(1)} °C`]);
  if (h.storageFreeMb != null && h.storageFreeMb < 1024) warns.push(["", `Мало места: ${h.storageFreeMb} МБ`]);
  if (h.memAvailMb != null && h.memAvailMb < 200) warns.push(["", `Мало свободной памяти: ${h.memAvailMb} МБ`]);
  if (h.wifiRssi != null && h.wifiRssi < -80) warns.push(["", `Слабый Wi-Fi у хаба: ${h.wifiRssi} dBm`]);
  if (!h.lastBackupMs || Date.now() - h.lastBackupMs > 3 * 86400e3) warns.push(["", "Бэкап на PC не делался больше 3 дней" + (h.lastBackupMs ? ` (последний ${clock(h.lastBackupMs)})` : "")]);
  if (t.connection !== "CONNECTED") warns.push(["", "Дорожка не на связи" + (link.lastDisconnectMs ? ` с ${clock(link.lastDisconnectMs)}` : "") + " — вероятно, выключена из сети. Хаб подключится сам после включения."]);
  $("hubWarnings").innerHTML = (warns.length ? warns : [["ok", "Всё в порядке"]])
    .map(([cls, text]) => `<div class="warn ${cls}">${esc(text)}</div>`).join("");

  const battery = h.batteryPct == null ? "—" : `${h.batteryPct} %`;
  const charge = h.charging ? "заряжается" : h.plugged ? "на зарядке, заряд держится 40–80 %" : "от батареи";
  $("hubCards").innerHTML = [
    card("Дорожка", t.connection === "CONNECTED" ? "на связи" : "нет связи",
      t.connection === "CONNECTED" ? `с ${clock(link.connectedSinceMs)} · подключений ${link.connects}` : `последняя связь ${ago(link.lastDisconnectMs)}`),
    card("Батарея", battery, `${charge}` + (h.batteryTempC != null ? ` · ${h.batteryTempC.toFixed(1)} °C` : "")),
    card("Работает", upTime(h.uptimeS), `версия ${h.version} · ${h.backend === "sim" ? "симулятор" : "FTMS"}`),
    card("Wi-Fi", h.wifiRssi != null ? `${h.wifiRssi} dBm` : "—", h.wifiRssi == null ? "" : h.wifiRssi > -60 ? "отличный сигнал" : h.wifiRssi > -75 ? "нормальный сигнал" : "слабый сигнал"),
    card("Память", h.memAvailMb != null ? `${h.memAvailMb} МБ` : "—", h.memTotalMb ? `свободно из ${h.memTotalMb} МБ` : ""),
    card("Хранилище", h.storageFreeMb != null ? `${(h.storageFreeMb / 1024).toFixed(1)} ГБ` : "—", "свободно"),
    card("История", `${h.sessions}`, "тренировок сохранено"),
    card("Бэкап на PC", h.lastBackupMs ? ago(h.lastBackupMs) : "не было", h.lastBackupMs ? clock(h.lastBackupMs) : "ежедневно в 23:00"),
  ].join("");
}

async function load() {
  try { render(await (await fetch("/api/state")).json()); }
  catch (_) { $("hubWarnings").innerHTML = `<div class="warn bad">Хаб недоступен</div>`; }
}
load();
setInterval(load, 5e3);
