"use strict";
// Станции Fossibot: состояние и настройки. Данные — с хаба (/api/power), обновление каждые 3 с.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const CAPACITY_WH = 2048; // Fossibot F2400
const SPEED_W = { 1: 300, 2: 500, 3: 700, 4: 900, 5: 1100 };
let stations = [];
let busy = false;
let editRows = null; // копия списка для редактирования: фоновое обновление её не трогает
const PERIODS = [["today", "День"], ["week", "Неделя"], ["month", "Месяц"], ["quarter", "Квартал"], ["year", "Год"], ["all", "Всё"]];
let period = "today";
try { period = localStorage.getItem("powerPeriod") || period; } catch (_) { /* без запоминания */ }
let toastTimer = 0;

function toast(msg, ok) {
  const el = $("toast");
  el.textContent = msg;
  el.style.background = ok ? "#16372a" : "";
  el.style.color = ok ? "#7ee2ad" : "";
  el.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 3500);
}

function ago(ms) {
  if (!ms) return "—";
  const s = Math.round((Date.now() - ms) / 1000);
  return s < 60 ? `${s} с назад` : `${Math.round(s / 60)} мин назад`;
}

function runtime(st) {
  if (st.socPct == null || st.outputW <= 5) return "";
  const h = (CAPACITY_WH * st.socPct / 100 * 0.9) / st.outputW;
  return h >= 1 ? `хватит примерно на ${h.toFixed(1).replace(".", ",")} ч` : `хватит примерно на ${Math.round(h * 60)} мин`;
}

const num = (v, d = 1) => v.toFixed(d).replace(".", ",");
const kwh = (wh) => wh >= 1000 ? num(wh / 1000, 2) + " кВт·ч" : Math.round(wh) + " Вт·ч";
function dur(sec) {
  const m = Math.round(sec / 60);
  if (m < 60) return m + " мин";
  const h = Math.floor(m / 60);
  return h < 24 ? `${h} ч ${m % 60} мин` : `${Math.floor(h / 24)} д ${h % 24} ч`;
}

function statsBlock(s) {
  const p = s.stats, t = p[period];
  const since = p.sinceDate ? new Date(p.sinceDate + "T00:00").toLocaleDateString("ru-RU", { day: "numeric", month: "long", year: "numeric" }) : null;
  const tabs = PERIODS.map(([k, label]) => `<button type="button" class="btn ${k === period ? "on" : ""}" data-period="${k}">${label}</button>`).join("");
  const cell = (label, value, sub = "") => `<div class="flow"><label>${label}</label><b>${value}</b>${sub ? `<small>${sub}</small>` : ""}</div>`;
  return `<div class="stats">
    <div class="seg periods">${tabs}</div>
    <div class="statGrid">
      ${cell("Циклы", num(t.chargedPct / 100, 2), `зарядок от сети: ${t.chargeSessions}`)}
      ${cell("Заряжено", num(t.chargedPct) + " %", "из сети " + kwh(t.chargedWh))}
      ${cell("Разряжено", num(t.dischargedPct) + " %", "из батареи " + kwh(t.offgridOutputWh))}
      ${cell("Отдано", kwh(t.outputWh), "всего на выходах")}
      ${cell("Отключения света", t.outages, "")}
      ${cell("Без света", dur(t.outageS), "")}
    </div>
    <div class="stFoot">${since ? "Учёт с " + since + ". " : ""}Цикл = 100 % заряда суммарно (например, 2 раза по 50 %).</div>
  </div>`;
}

const opt = (list, cur) => list.map(([v, t]) => `<option value="${v}" ${v === cur ? "selected" : ""}>${t}</option>`).join("");

function card(s) {
  const st = s.state, set = st.settings || {};
  const hasSet = st.settingsAtMs > 0;
  const grid = st.gridOn == null ? `<span class="pill">…</span>`
    : st.gridOn ? `<span class="pill ok">💡 сеть ${st.gridVoltage?.toFixed(0)} В · ${st.gridHz?.toFixed(1)} Гц</span>`
      : `<span class="pill bad">⚡ нет света</span>`;
  const conn = st.connected ? "" : `<span class="pill bad">нет связи</span>`;
  const soc = st.socPct ?? 0;
  const toggle = (key, label) => {
    const on = set[key] === 1;
    return `<button type="button" class="btn ${on ? "on" : ""}" data-set="${s.id}|${key}|${on ? 0 : 1}" ${hasSet ? "" : "disabled"}>${label} ${on ? "вкл" : "выкл"}</button>`;
  };
  const speed = [1, 2, 3, 4, 5].map((v) =>
    `<button type="button" class="btn ${set.chargeSpeed === v ? "on" : ""}" data-set="${s.id}|chargeSpeed|${v}" ${hasSet ? "" : "disabled"}>${SPEED_W[v]}</button>`).join("");
  const pct = (from, to, step) => { const r = []; for (let v = from; v <= to; v += step) r.push([v * 10, v + " %"]); return r; };
  const acStandby = [[0, "никогда"], [480, "8 мин"], [960, "16 мин"], [1440, "24 мин"]];
  const usbStandby = [[0, "никогда"], [180, "3 мин"], [300, "5 мин"], [600, "10 мин"], [1800, "30 мин"]];

  return `<section class="station ${st.gridOn === false ? "offgrid" : ""}">
    <div class="stHead"><b>${esc(s.name)}</b>${conn}${grid}</div>
    <div class="soc"><b>${st.socPct == null ? "—" : st.socPct.toFixed(1).replace(".", ",") + " %"}</b><small>${runtime(st)}</small></div>
    <div class="socBar"><i class="${soc < 20 ? "low" : ""}" style="width:${soc}%"></i></div>
    <div class="flows">
      <div class="flow"><label>Вход</label><b>${st.inputW} Вт</b></div>
      <div class="flow"><label>Выход</label><b>${st.outputW} Вт</b></div>
      <div class="flow"><label>Зарядка</label><b>${st.acChargeW} Вт</b></div>
    </div>
    ${statsBlock(s)}

    <div class="setRow"><span>Выходы</span><div class="seg">${toggle("acOutput", "AC")}${toggle("dcOutput", "DC")}${toggle("usbOutput", "USB")}</div></div>
    <div class="setRow"><span>Зарядка, Вт</span><div class="seg">${speed}</div></div>
    <div class="setRow"><span>Заряжать до</span><select data-sel="${s.id}|chargeLimit" ${hasSet ? "" : "disabled"}>${opt(pct(50, 100, 5), set.chargeLimit)}</select></div>
    <div class="setRow"><span>Разряжать до</span><select data-sel="${s.id}|dischargeLimit" ${hasSet ? "" : "disabled"}>${opt(pct(0, 50, 5), set.dischargeLimit)}</select></div>
    <div class="setRow"><span>AC выкл. без нагрузки</span><select data-sel="${s.id}|acStandby" ${hasSet ? "" : "disabled"}>${opt(acStandby, set.acStandby)}</select></div>
    <div class="setRow"><span>DC выкл. без нагрузки</span><select data-sel="${s.id}|dcStandby" ${hasSet ? "" : "disabled"}>${opt(acStandby, set.dcStandby)}</select></div>
    <div class="setRow"><span>USB выкл. без нагрузки</span><select data-sel="${s.id}|usbStandby" ${hasSet ? "" : "disabled"}>${opt(usbStandby, set.usbStandby)}</select></div>
    <div class="setRow"><span>Звук кнопок</span><div class="seg">${toggle("keySound", "Звук")}</div></div>
    <div class="stFoot">Авто-выключение станции: ${set.autoOffMin ?? "—"} мин (только просмотр) · макс. зарядка ${set.maxChargeW ?? "—"} Вт ·
      данные ${ago(st.updatedAtMs)}, настройки ${ago(st.settingsAtMs)}</div>
  </section>`;
}

async function load() {
  // Не перерисовываем, пока пользователь выбирает значение в списке или идёт запись
  const a = document.activeElement;
  if (busy || (a && a.tagName === "SELECT" && a.closest("#stations"))) return;
  try {
    stations = await (await fetch("/api/power")).json();
    $("stations").innerHTML = stations.length ? stations.map(card).join("")
      : `<p class="muted">Станций пока нет — добавьте их в «Список станций» ниже.</p>`;
  } catch (_) {
    toast("хаб недоступен");
  }
}

// --- Журнал отключений: записи станций об одном отключении (начало в пределах 3 мин) — одной строкой ---
let outageShow = 15;
function groupOutages(list) {
  const groups = [];
  for (const v of [...list].sort((a, b) => b.outage.startMs - a.outage.startMs)) {
    const g = groups.find((x) => Math.abs(x.startMs - v.outage.startMs) <= 180e3 && !x.items.some((i) => i.stationName === v.stationName));
    if (g) { g.items.push(v); g.startMs = Math.min(g.startMs, v.outage.startMs); } else groups.push({ startMs: v.outage.startMs, items: [v] });
  }
  return groups;
}
const hm = (ms) => new Date(ms).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
const day = (ms) => new Date(ms).toLocaleDateString("ru-RU", { weekday: "short", day: "numeric", month: "long" });
const pct = (v) => v == null ? "?" : Math.round(v) + " %";
function outageRow(g) {
  const ends = g.items.map((i) => i.outage.endMs);
  const ongoing = ends.some((e) => e == null);
  const endMs = ongoing ? Date.now() : Math.max(...ends);
  const approx = g.items.some((i) => i.outage.approximate) ? " ≈" : "";
  const endText = ongoing ? "света нет сейчас" : (day(endMs) === day(g.startMs) ? hm(endMs) : day(endMs) + " " + hm(endMs));
  const lines = g.items.map(({ stationName, outage: o }) =>
    `<small>${esc(stationName)}: ${pct(o.socStart)} → ${pct(o.socEnd)}` + (o.minSoc != null && o.minSoc < (o.socEnd ?? 0) - 0.5 ? ` (минимум ${pct(o.minSoc)})` : "") +
    ` · из батареи ${kwh(o.batteryWh)}` + (o.maxOutputW ? ` · пик ${o.maxOutputW} Вт` : "") + `</small>`).join("");
  return `<div class="outage ${ongoing ? "now" : ""}">
    <div class="oHead"><b>${day(g.startMs)}, ${hm(g.startMs)} – ${endText}</b><span class="pill ${ongoing ? "bad" : ""}">${dur((endMs - g.startMs) / 1000)}${approx}</span></div>
    ${lines}
  </div>`;
}
async function loadOutages() {
  try {
    const groups = groupOutages(await (await fetch("/api/power/outages")).json());
    $("outages").innerHTML = groups.length ? groups.slice(0, outageShow).map(outageRow).join("")
      : `<p class="muted">Отключений пока не было. Запись идёт с момента, как хаб начал следить за станциями.</p>`;
    $("moreOutages").classList.toggle("hidden", groups.length <= outageShow);
  } catch (_) { /* хаб недоступен — покажет load() */ }
}
$("moreOutages").onclick = () => { outageShow += 30; loadOutages(); };

async function writeSetting(id, key, value) {
  const s = stations.find((x) => x.id === id);
  if (key === "acOutput" && value === 0 && !confirm(`Выключить AC-выход станции «${s ? s.name : id}»? Всё, что в неё включено (например, компьютер), обесточится.`)) return;
  if (key === "dcOutput" && value === 0 && !confirm("Выключить DC-выход станции?")) return;
  busy = true;
  try {
    const r = await fetch(`/api/power/${encodeURIComponent(id)}/settings`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ key, value }),
    });
    const body = await r.json();
    if (r.ok) toast("сохранено на станции", true); else toast(body.error || "не сохранено");
  } catch (_) {
    toast("хаб недоступен");
  }
  busy = false;
  load();
}

document.addEventListener("click", (e) => {
  const per = e.target.closest("[data-period]");
  if (per) {
    period = per.dataset.period;
    try { localStorage.setItem("powerPeriod", period); } catch (_) { /* без запоминания */ }
    load();
    return;
  }
  const b = e.target.closest("[data-set]");
  if (!b || b.disabled) return;
  const [id, key, v] = b.dataset.set.split("|");
  writeSetting(id, key, Number(v));
});
document.addEventListener("change", (e) => {
  const sel = e.target.closest("[data-sel]");
  if (!sel) return;
  const [id, key] = sel.dataset.sel.split("|");
  writeSetting(id, key, Number(sel.value));
  sel.blur();
});

// --- Список станций: редактируется копия, на хаб уходит по «Сохранить» ---
function renderRows() {
  $("stationRows").innerHTML = editRows.map((s, i) => `<div class="stRow">
      <input data-i="${i}" data-f="name" value="${esc(s.name)}" placeholder="Имя" maxlength="30" autocomplete="off">
      <input data-i="${i}" data-f="address" value="${esc(s.address)}" placeholder="AA:BB:CC:DD:EE:FF" autocomplete="off">
      <button type="button" class="x" data-del="${i}" aria-label="Удалить">×</button>
    </div>`).join("");
}
function startEdit() {
  editRows = stations.map(({ id, name, address }) => ({ id, name, address }));
  renderRows();
}
document.querySelector(".manage").addEventListener("toggle", (e) => { if (e.target.open) startEdit(); else editRows = null; });
$("stationRows").addEventListener("input", (e) => { const el = e.target; if (el.dataset.f) editRows[el.dataset.i][el.dataset.f] = el.value; });
$("stationRows").addEventListener("click", (e) => { const b = e.target.closest("[data-del]"); if (b) { editRows.splice(Number(b.dataset.del), 1); renderRows(); } });
$("addStation").onclick = () => { editRows.push({ id: "", name: "", address: "" }); renderRows(); };
$("saveStations").onclick = async () => {
  try {
    const r = await fetch("/api/power/stations", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(editRows.map(({ id, name, address }) => ({ id, name: name.trim(), address: address.trim() }))),
    });
    const body = await r.json();
    if (!r.ok) { toast(body.error || "не сохранено"); return; }
    stations = body;
    toast("список сохранён", true);
    startEdit();
    load();
  } catch (_) {
    toast("хаб недоступен");
  }
};

load();
setInterval(load, 3000);
loadOutages();
setInterval(loadOutages, 30000);
