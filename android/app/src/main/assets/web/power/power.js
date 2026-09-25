"use strict";
// Станции Fossibot: состояние и настройки. Данные — с хаба (/api/power), обновление каждые 3 с.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const CAPACITY_WH = 2048; // Fossibot F2400
const SPEED_W = { 1: 300, 2: 500, 3: 700, 4: 900, 5: 1100 };
let stations = [];
let busy = false;
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
  if (busy || (document.activeElement && document.activeElement.tagName === "SELECT")) return;
  try {
    stations = await (await fetch("/api/power")).json();
    $("stations").innerHTML = stations.length ? stations.map(card).join("")
      : `<p class="muted">Станций пока нет — добавьте их в «Список станций» ниже.</p>`;
  } catch (_) {
    toast("хаб недоступен");
  }
}

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

// --- Список станций ---
function renderRows() {
  $("stationRows").innerHTML = stations.map((s, i) => `<div class="stRow">
      <input data-i="${i}" data-f="name" value="${esc(s.name)}" placeholder="Имя">
      <input data-i="${i}" data-f="address" value="${esc(s.address)}" placeholder="AA:BB:CC:DD:EE:FF">
      <button type="button" class="x" data-del="${i}" aria-label="Удалить">×</button>
    </div>`).join("");
}
document.querySelector(".manage").addEventListener("toggle", (e) => { if (e.target.open) renderRows(); });
$("stationRows").addEventListener("input", (e) => { const el = e.target; if (el.dataset.f) stations[el.dataset.i][el.dataset.f] = el.value; });
$("stationRows").addEventListener("click", (e) => { const b = e.target.closest("[data-del]"); if (b) { stations.splice(Number(b.dataset.del), 1); renderRows(); } });
$("addStation").onclick = () => { stations.push({ id: "", name: "", address: "" }); renderRows(); };
$("saveStations").onclick = async () => {
  const r = await fetch("/api/power/stations", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify(stations.map(({ id, name, address }) => ({ id, name, address }))),
  });
  const body = await r.json();
  if (!r.ok) { toast(body.error || "не сохранено"); return; }
  stations = body;
  toast("список сохранён", true);
  renderRows();
  load();
};

load();
setInterval(load, 3000);
