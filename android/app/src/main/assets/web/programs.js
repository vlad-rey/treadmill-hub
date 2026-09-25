"use strict";
// Вкладки «Программы» и «История», панель выполняемой программы, график профиля, редактор.
// Использует общие функции из app.js: $, me, control, toast, fmtTime.

// --- Вкладки -----------------------------------------------------------------------
function showTab(name) {
  document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("hidden", t.id !== "tab-" + name));
  if (name === "programs") loadPrograms();
  if (name === "history") loadHistory();
  if (name === "hub" && lastSnap) window.renderHub(lastSnap);
  window.scrollTo(0, 0);
}
document.querySelectorAll(".tabs button").forEach((b) => (b.onclick = () => showTab(b.dataset.tab)));

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

// --- График профиля: столбцы — скорость (ширина ∝ длительности), линия — наклон ----------
function drawChart(svg, segments, elapsedS) {
  if (!segments || !segments.length) { svg.innerHTML = ""; return; }
  const W = 1000, H = 120;
  const total = segments.reduce((a, s) => a + s.durationS, 0);
  const vmax = Math.max(6, ...segments.map((s) => s.speedKmh));
  let x = 0, bars = "", inc = "", cursor = 0;
  segments.forEach((s, i) => {
    const w = (s.durationS / total) * W;
    const h = (s.speedKmh / vmax) * (H - 6);
    const end = cursor + s.durationS;
    const cls = elapsedS == null ? "spd" : elapsedS >= end ? "spd past" : elapsedS >= cursor ? "cur" : "spd";
    bars += `<rect class="${cls}" x="${x + 1}" y="${H - h}" width="${Math.max(1, w - 2)}" height="${h}" rx="2"/>`;
    if (s.inclinePct != null) {
      const y = H - 4 - (s.inclinePct / 15) * (H - 10);
      inc += `${i === 0 || inc === "" ? "M" : "L"}${x},${y} L${x + w},${y} `;
    }
    x += w;
    cursor = end;
  });
  const now = elapsedS == null ? "" : `<line class="now" x1="${(elapsedS / total) * W}" y1="0" x2="${(elapsedS / total) * W}" y2="${H}"/>`;
  svg.innerHTML = bars + (inc ? `<path class="inc" d="${inc}"/>` : "") + now;
}

function summarize(segments) {
  const total = segments.reduce((a, s) => a + s.durationS, 0);
  const vmax = Math.max(...segments.map((s) => s.speedKmh));
  const incs = segments.filter((s) => s.inclinePct != null).map((s) => s.inclinePct);
  const km = segments.reduce((a, s) => a + (s.speedKmh * s.durationS) / 3600, 0);
  return `${fmtTime(total)} · до ${vmax} км/ч` + (incs.length ? ` · наклон до ${Math.max(...incs)} %` : " · наклон не меняется") + ` · ≈ ${km.toFixed(1)} км`;
}

// --- Панель выполняемой программы на вкладке «Тренировка» ------------------------------
window.renderProgram = function (p) {
  const panel = $("programPanel");
  if (!p) { panel.classList.add("hidden"); return; }
  panel.classList.remove("hidden");
  const stateText = { WAITING: " · ждём старта", DONE: " · завершена", CANCELLED: " · прервана" }[p.state] || "";
  $("progName").textContent = p.name + stateText;
  $("progEnd").classList.toggle("hidden", p.state !== "RUNNING" && p.state !== "WAITING");
  drawChart($("progChart"), p.segments, p.elapsedS);
  const i = Math.max(0, p.index);
  $("progSeg").textContent = `${i + 1}/${p.segments.length}`;
  $("progSegLeft").textContent = fmtTime(p.segmentRemainingS);
  $("progLeft").textContent = fmtTime(Math.max(0, p.totalS - p.elapsedS));
  const next = p.segments[i + 1];
  $("progNext").textContent = next ? `${next.speedKmh}` + (next.inclinePct != null ? ` · ${next.inclinePct}%` : "") : "—";
};
$("progEnd").onclick = () => control("programEnd");

// --- Список программ ----------------------------------------------------------------
let programList = [];
async function loadPrograms() {
  programList = await (await fetch("/api/programs?profile=" + encodeURIComponent(me ? me.id : ""))).json();
  const row = (p) => `<button type="button" class="row" data-program="${esc(p.id)}">
      <span class="grow"><b>${p.builtin ? esc(p.id) + " · " : ""}${esc(p.name)}</b>
      <small>${p.builtin ? p.levels + " уровней · 18 отрезков" : fmtTime(p.totalS) + (p.profileId ? "" : " · общая")}</small></span>›</button>`;
  $("builtinList").innerHTML = programList.filter((p) => p.builtin).map(row).join("");
  const mine = programList.filter((p) => !p.builtin);
  $("customList").innerHTML = mine.length ? mine.map(row).join("") : '<p class="muted">Пока нет. Создайте свою — например, интервалы.</p>';
}
document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-program]");
  if (b) openProgram(programList.find((p) => p.id === b.dataset.program));
});

// --- Диалог запуска программы -----------------------------------------------------------
const pd = { program: null, level: 1, minutes: 30, segments: [] };
const remembered = (key, def) => { try { return Number(localStorage.getItem(key)) || def; } catch (_) { return def; } };
const remember = (key, v) => { try { localStorage.setItem(key, v); } catch (_) {} };

async function openProgram(p) {
  if (!p) return;
  pd.program = p;
  pd.level = remembered("level:" + p.id, 1);
  pd.minutes = remembered("minutes:" + p.id, 30);
  $("pdName").textContent = (p.builtin ? p.id + " · " : "") + p.name;
  $("pdLevelBox").classList.toggle("hidden", !p.builtin);
  $("pdMinutesBox").classList.toggle("hidden", !p.builtin);
  $("pdCustomActions").classList.toggle("hidden", p.builtin);
  $("pdLevels").innerHTML = Array.from({ length: p.levels }, (_, i) => `<button type="button" class="btn" data-level="${i + 1}">${i + 1}</button>`).join("");
  await refreshPreview();
  $("programDlg").showModal();
}

async function refreshPreview() {
  const p = pd.program;
  document.querySelectorAll("#pdLevels .btn").forEach((b) => b.classList.toggle("current", Number(b.dataset.level) === pd.level));
  $("pdMinutes").textContent = pd.minutes;
  const q = new URLSearchParams({ level: pd.level, minutes: pd.minutes, profile: me ? me.id : "" });
  pd.segments = await (await fetch(`/api/programs/${encodeURIComponent(p.id)}/segments?${q}`)).json();
  drawChart($("pdChart"), pd.segments, null);
  $("pdSummary").textContent = summarize(pd.segments) + (me ? ` · скорость не выше ${me.maxSpeedKmh} км/ч (ваш лимит)` : "");
}

$("pdLevels").addEventListener("click", (e) => {
  const b = e.target.closest("[data-level]");
  if (!b) return;
  pd.level = Number(b.dataset.level);
  refreshPreview();
});
document.querySelectorAll("[data-min]").forEach((b) => (b.onclick = () => {
  const d = Number(b.dataset.min);
  pd.minutes = Math.min(99, Math.max(5, pd.minutes + d));
  refreshPreview();
}));

$("pdStart").onclick = async () => {
  const p = pd.program;
  remember("level:" + p.id, pd.level);
  remember("minutes:" + p.id, pd.minutes);
  const ok = await control("program", null, { programId: p.id, level: pd.level, minutes: pd.minutes });
  if (ok) { $("programDlg").close(); showTab("workout"); }
};
$("pdEdit").onclick = async () => {
  const full = await (await fetch("/api/programs/" + encodeURIComponent(pd.program.id))).json();
  $("programDlg").close();
  openEditor(full);
};
$("pdDelete").onclick = async () => {
  if (!confirm(`Удалить программу «${pd.program.name}»?`)) return;
  await fetch("/api/programs/" + encodeURIComponent(pd.program.id), { method: "DELETE" });
  $("programDlg").close();
  loadPrograms();
};

// --- Редактор своей программы: блоки шагов с повторами ---------------------------------
let editing = null; // { id|null, name, blocks: [{repeat, steps:[{durationS, speedKmh, inclinePct}]}] }

function openEditor(program) {
  editing = program
    ? JSON.parse(JSON.stringify(program))
    : { id: null, name: "", blocks: [
        { repeat: 1, steps: [{ durationS: 300, speedKmh: 4, inclinePct: 0 }] },
        { repeat: 6, steps: [{ durationS: 60, speedKmh: 8, inclinePct: 0 }, { durationS: 120, speedKmh: 4.5, inclinePct: 0 }] },
        { repeat: 1, steps: [{ durationS: 300, speedKmh: 3.5, inclinePct: 0 }] },
      ] };
  $("edName").value = editing.name;
  renderEditor();
  $("editorDlg").showModal();
}
$("newProgram").onclick = () => openEditor(null);

function renderEditor() {
  $("edBlocks").innerHTML = editing.blocks.map((b, bi) => `
    <div class="block">
      <div class="blockHead">
        <span class="grow">Блок ${bi + 1}</span>
        повторить
        <button type="button" class="btn" data-ed="rep-" data-b="${bi}" style="height:36px;width:44px">−</button>
        <b>×${b.repeat}</b>
        <button type="button" class="btn" data-ed="rep+" data-b="${bi}" style="height:36px;width:44px">+</button>
        <button type="button" class="link" data-ed="delBlock" data-b="${bi}">удалить</button>
      </div>
      ${b.steps.map((s, si) => `
        <div class="step">
          <label>мин <input type="number" min="0" max="60" data-f="min" data-b="${bi}" data-s="${si}" value="${Math.floor(s.durationS / 60)}"></label>
          <label>сек <input type="number" min="0" max="59" step="5" data-f="sec" data-b="${bi}" data-s="${si}" value="${s.durationS % 60}"></label>
          <label>км/ч <input type="number" min="1" max="16" step="0.1" data-f="speed" data-b="${bi}" data-s="${si}" value="${s.speedKmh}"></label>
          <label>наклон % <input type="number" min="0" max="15" step="1" data-f="incline" data-b="${bi}" data-s="${si}" value="${s.inclinePct ?? ""}" placeholder="—"></label>
          <button type="button" class="x" data-ed="delStep" data-b="${bi}" data-s="${si}" aria-label="Удалить шаг">×</button>
        </div>`).join("")}
      <button type="button" class="link" data-ed="addStep" data-b="${bi}">+ шаг</button>
    </div>`).join("");
  updateEditorPreview();
}

function editorSegments() {
  return editing.blocks.flatMap((b) => Array.from({ length: b.repeat }, () => b.steps).flat());
}
function updateEditorPreview() {
  const segs = editorSegments();
  drawChart($("edChart"), segs, null);
  $("edSummary").textContent = segs.length ? summarize(segs) : "";
}

$("edBlocks").addEventListener("input", (e) => {
  const el = e.target;
  if (!el.dataset.f) return;
  const s = editing.blocks[el.dataset.b].steps[el.dataset.s];
  const v = el.value === "" ? null : Number(el.value);
  if (el.dataset.f === "min") s.durationS = (v || 0) * 60 + (s.durationS % 60);
  if (el.dataset.f === "sec") s.durationS = Math.floor(s.durationS / 60) * 60 + Math.min(59, v || 0);
  if (el.dataset.f === "speed") s.speedKmh = v || 1;
  if (el.dataset.f === "incline") s.inclinePct = v;
  updateEditorPreview();
});
$("edBlocks").addEventListener("click", (e) => {
  const b = e.target.closest("[data-ed]");
  if (!b) return;
  const bi = Number(b.dataset.b), block = editing.blocks[bi];
  const act = b.dataset.ed;
  if (act === "rep+") block.repeat = Math.min(50, block.repeat + 1);
  if (act === "rep-") block.repeat = Math.max(1, block.repeat - 1);
  if (act === "delBlock") editing.blocks.splice(bi, 1);
  if (act === "addStep") block.steps.push({ ...block.steps[block.steps.length - 1] });
  if (act === "delStep") { block.steps.splice(Number(b.dataset.s), 1); if (!block.steps.length) editing.blocks.splice(bi, 1); }
  renderEditor();
});
$("edAddBlock").onclick = () => { editing.blocks.push({ repeat: 1, steps: [{ durationS: 60, speedKmh: 5, inclinePct: 0 }] }); renderEditor(); };

$("edSave").onclick = async () => {
  const name = $("edName").value.trim();
  if (!name) { $("edName").focus(); return; }
  const body = { profileId: me ? me.id : null, name, blocks: editing.blocks };
  const url = editing.id ? "/api/programs/" + encodeURIComponent(editing.id) : "/api/programs";
  const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const res = await r.json();
  if (!r.ok) { toast(res.error || "не сохранено"); return; }
  $("editorDlg").close();
  loadPrograms();
};

// --- История -------------------------------------------------------------------------
const fmtDate = (ms) => new Date(ms).toLocaleString("ru-RU", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const sessionRow = (s, claim) => `<div class="row"><span class="grow"><b>${fmtDate(s.id)}</b>
  <small>${fmtTime(s.movingS)} · ${(s.distanceM / 1000).toFixed(2)} км · ${Math.round(s.kcalCalc)} ккал (дорожка ${s.kcalTreadmill == null ? "—" : Math.round(s.kcalTreadmill)})</small></span>
  ${claim ? `<button type="button" class="btn" data-claim="${s.id}">Это моя</button>` : ""}
  <button type="button" class="x" data-del="${s.id}" aria-label="Удалить тренировку">×</button></div>`;

async function loadHistory() {
  if (!me) return;
  const [mine, orphans] = await Promise.all([
    fetch("/api/sessions?profile=" + encodeURIComponent(me.id)).then((r) => r.json()),
    fetch("/api/sessions?profile=").then((r) => r.json()),
  ]);
  $("historyList").innerHTML = mine.length ? mine.slice(0, 100).map((s) => sessionRow(s, false)).join("") : '<p class="muted">Тренировок пока нет.</p>';
  $("orphansTitle").classList.toggle("hidden", !orphans.length);
  $("orphanList").innerHTML = orphans.slice(0, 30).map((s) => sessionRow(s, true)).join("");
}
window.loadHistory = loadHistory;

document.addEventListener("click", async (e) => {
  const b = e.target.closest("[data-del]");
  if (!b) return;
  if (!confirm("Удалить эту тренировку из истории? Отменить нельзя.")) return;
  await fetch("/api/sessions/" + b.dataset.del, { method: "DELETE" });
  loadHistory();
  loadStats();
});

$("orphanList").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-claim]");
  if (!b || !me) return;
  await fetch(`/api/sessions/${b.dataset.claim}/profile`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ profileId: me.id }),
  });
  loadHistory();
  loadStats();
});

// --- Хаб: состояние сервера и связи; предупреждения только здесь, без уведомлений -------------
const ago = (ms) => {
  if (!ms) return "—";
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 90) return "только что";
  if (s < 5400) return Math.round(s / 60) + " мин назад";
  if (s < 172800) return Math.round(s / 3600) + " ч назад";
  return Math.round(s / 86400) + " дн назад";
};
const clock = (ms) => new Date(ms).toLocaleString("ru-RU", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const upTime = (s) => (s >= 86400 ? Math.floor(s / 86400) + " дн " : "") + Math.floor((s % 86400) / 3600) + " ч " + Math.floor((s % 3600) / 60) + " мин";

window.renderHub = function (snap) {
  if ($("tab-hub").classList.contains("hidden")) return;
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
  const card = (label, value, sub) => `<div class="stat"><label>${label}</label><b>${value}</b><small>${sub || ""}</small></div>`;
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
};

window.onProfileChanged = () => {
  const active = document.querySelector(".tabs button.active");
  if (active && active.dataset.tab !== "workout") showTab(active.dataset.tab);
};
