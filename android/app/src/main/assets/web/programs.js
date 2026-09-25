"use strict";
// Вкладки «Программы» и «История», панель выполняемой программы, график профиля, редактор.
// Использует общие функции из app.js: $, me, control, toast, fmtTime.

// --- Вкладки -----------------------------------------------------------------------
const TAB_TITLES = { workout: "Тренировка", programs: "Программы", history: "История", awards: "Награды" };
function showTab(name) {
  if (name === "hub") { location.replace("/hub/"); return; } // старые ссылки на вкладку «Хаб»
  if (!TAB_TITLES[name]) name = "workout";
  document.title = `${TAB_TITLES[name]} · Дорожка`;
  history.replaceState(null, "", name === "workout" ? location.pathname : "#" + name);
  document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("hidden", t.id !== "tab-" + name));
  if (name === "programs") loadPrograms();
  if (name === "history") { loadHistory(); loadWeights(); }
  if (name === "awards" && window.loadAwards) window.loadAwards();
  window.scrollTo(0, 0);
}
document.querySelectorAll(".tabs button").forEach((b) => (b.onclick = () => showTab(b.dataset.tab)));
window.addEventListener("hashchange", () => showTab(location.hash.slice(1)));

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
  $("pdCopy").classList.toggle("hidden", !p.builtin);
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
const sessionRow = (s, claim) => `<div class="row" data-session="${s.id}"><span class="grow"><b>${fmtDate(s.id)}</b>
  <small>${fmtTime(s.movingS)} · ${(s.distanceM / 1000).toFixed(2)} км · ${Math.round(s.kcalCalc)} ккал (дорожка ${s.kcalTreadmill == null ? "—" : Math.round(s.kcalTreadmill)})</small></span>
  ${claim ? `<button type="button" class="btn" data-claim="${s.id}">Это моя</button>` : ""}›</div>`;

async function loadHistory() {
  if (!me) return;
  const [mine, orphans] = await Promise.all([
    fetch("/api/sessions?profile=" + encodeURIComponent(me.id)).then((r) => r.json()),
    fetch("/api/sessions?profile=").then((r) => r.json()),
  ]);
  $("exportAll").href = "/api/export/sessions.csv?profile=" + encodeURIComponent(me.id);
  $("exportAll").classList.toggle("hidden", !mine.length);
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

const clock = (ms) => new Date(ms).toLocaleString("ru-RU", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

// --- Своя программа на основе встроенной: одинаковые подряд отрезки склеиваются -------------
$("pdCopy").onclick = () => {
  const p = pd.program;
  const steps = [];
  for (const s of pd.segments) {
    const last = steps[steps.length - 1];
    if (last && last.speedKmh === s.speedKmh && last.inclinePct === s.inclinePct) last.durationS += s.durationS;
    else steps.push({ ...s });
  }
  $("programDlg").close();
  openEditor({ id: null, name: `${p.id} ${p.name} · ур. ${pd.level} (моя)`, blocks: [{ repeat: 1, steps }] });
};

// --- Подробности тренировки ---------------------------------------------------------------
let sdSession = null;

document.addEventListener("click", async (e) => {
  if (e.target.closest("[data-claim]")) return; // «Это моя» обрабатывается отдельно
  const row = e.target.closest("[data-session]");
  if (row) openSession(Number(row.dataset.session));
});

async function openSession(id) {
  const s = await (await fetch("/api/sessions/" + id)).json();
  sdSession = s;
  const st = s.stats, samples = s.samples || [];
  const moving = samples.filter((x) => x.speedKmh > 0);
  const maxV = Math.max(0, ...moving.map((x) => x.speedKmh));
  const maxI = Math.max(0, ...samples.map((x) => x.inclinePct));
  const avgV = st.movingS > 0 ? (st.distanceM / st.movingS) * 3.6 : 0;
  $("sdTitle").textContent = fmtDate(s.id);
  const card = (label, value, sub) => `<div class="stat"><label>${label}</label><b>${value}</b><small>${sub || ""}</small></div>`;
  $("sdCards").innerHTML = [
    card("Время", fmtTime(st.movingS), `вес при расчёте ${s.weightKg} кг`),
    card("Дистанция", (st.distanceM / 1000).toFixed(2) + " км", st.distanceCalcM ? `по скорости ${(st.distanceCalcM / 1000).toFixed(2)} км` : ""),
    card("Калории", Math.round(st.kcalCalc) + " ккал", `активные ${Math.round(st.kcalActiveCalc)} · дорожка ${st.kcalTreadmill == null ? "—" : Math.round(st.kcalTreadmill)}`),
    card("Скорость", avgV.toFixed(1) + " км/ч", `средняя · макс ${maxV.toFixed(1)} · наклон до ${Math.round(maxI)} %`),
  ].join("");

  // График: подряд идущие одинаковые сэмплы склеиваются в отрезки
  const segs = [];
  for (let i = 0; i < samples.length; i++) {
    const cur = samples[i], next = samples[i + 1];
    const d = next ? Math.max(0, (next.t - cur.t) / 1000) : 1;
    const last = segs[segs.length - 1];
    if (last && last.speedKmh === cur.speedKmh && last.inclinePct === cur.inclinePct) last.durationS += d;
    else segs.push({ durationS: d, speedKmh: cur.speedKmh, inclinePct: cur.inclinePct });
  }
  drawChart($("sdChart"), segs, null);

  $("sdBuckets").innerHTML = (st.buckets || [])
    .filter((b) => b.seconds >= 1)
    .sort((a, b) => a.speedKmh - b.speedKmh || a.inclinePct - b.inclinePct)
    .map((b) => `<tr><td>${b.speedKmh.toFixed(1)}</td><td>${b.inclinePct}</td><td>${fmtTime(b.seconds)}</td><td>${Math.round(b.meters)}</td></tr>`)
    .join("");

  $("sdOwner").innerHTML = profiles.map((p) => `<option value="${esc(p.id)}">${esc(p.name)}</option>`).join("") +
    `<option value="">Без владельца (запуск с пульта)</option>`;
  $("sdOwner").value = s.profileId || "";
  $("sdTcx").href = `/api/sessions/${s.id}/export?format=tcx`;
  $("sdCsv").href = `/api/sessions/${s.id}/export?format=csv`;
  $("sessionDlg").showModal();
}

$("sdOwner").onchange = async () => {
  const profileId = $("sdOwner").value || null;
  await fetch(`/api/sessions/${sdSession.id}/profile`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ profileId }),
  });
  loadHistory();
  loadStats();
};

$("sdDelete").onclick = async () => {
  if (!confirm("Удалить эту тренировку из истории? Отменить нельзя.")) return;
  await fetch("/api/sessions/" + sdSession.id, { method: "DELETE" });
  $("sessionDlg").close();
  loadHistory();
  loadStats();
};

// --- Вес: история, график, еженедельный вопрос --------------------------------------------
const WEEK = 7 * 86400e3;
let weights = [];

async function loadWeights() {
  if (!me) return;
  weights = await (await fetch(`/api/profiles/${encodeURIComponent(me.id)}/weights`)).json();
  const last = weights[weights.length - 1];
  $("wNow").textContent = last ? `${last.kg} кг` : "—";
  const signed = (d) => (d > 0 ? "+" : d < 0 ? "−" : "±") + Math.abs(d).toFixed(1);
  const monthAgo = [...weights].reverse().find((w) => w.atMs <= Date.now() - 30 * 86400e3);
  const parts = [];
  if (last && monthAgo) parts.push(`${signed(last.kg - monthAgo.kg)} кг за месяц`);
  if (last && weights.length > 1) parts.push(`${signed(last.kg - weights[0].kg)} кг с ${clock(weights[0].atMs).split(",")[0]}`);
  $("wDelta").textContent = parts.join(" · ") || (last ? `записан ${clock(last.atMs)}` : "");
  drawWeights();
}

function drawWeights() {
  const svg = $("wChart");
  if (weights.length < 2) { svg.innerHTML = ""; return; }
  const W = 1000, H = 120, t0 = weights[0].atMs, t1 = weights[weights.length - 1].atMs || t0 + 1;
  const kgs = weights.map((w) => w.kg), lo = Math.min(...kgs) - 0.5, hi = Math.max(...kgs) + 0.5;
  const pt = (w) => [((w.atMs - t0) / Math.max(1, t1 - t0)) * (W - 20) + 10, H - 10 - ((w.kg - lo) / (hi - lo)) * (H - 20)];
  svg.innerHTML = `<path class="wline" d="${weights.map((w, i) => (i ? "L" : "M") + pt(w).join(",")).join(" ")}"/>` +
    weights.map((w) => { const [x, y] = pt(w); return `<circle class="wdot" cx="${x}" cy="${y}" r="5"/>`; }).join("");
}

function openWeight(weekly) {
  const last = weights[weights.length - 1];
  $("wdText").textContent = weekly && last ? `Прошла неделя с последней записи (${clock(last.atMs)}: ${last.kg} кг). Вес изменился?` : "";
  $("wdKg").value = last ? last.kg : me ? me.weightKg : "";
  $("wdSame").classList.toggle("hidden", !weekly);
  $("wdLater").classList.toggle("hidden", !weekly);
  $("weightDlg").showModal();
}

async function saveWeight(kg) {
  const r = await fetch(`/api/profiles/${encodeURIComponent(me.id)}/weights`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ kg }),
  });
  if (!r.ok) { toast((await r.json()).error || "не сохранено"); return; }
  $("weightDlg").close();
  await loadProfiles(); // вес профиля обновился — по нему считаются калории
}

$("wAdd").onclick = () => openWeight(false);
$("wdSave").onclick = () => { const kg = Number($("wdKg").value); if (kg) saveWeight(kg); };
$("wdSame").onclick = () => { const last = weights[weights.length - 1]; saveWeight(last ? last.kg : me.weightKg); };
$("wdLater").onclick = () => { try { localStorage.setItem("weightSnooze:" + me.id, Date.now() + 86400e3); } catch (_) {} };

async function weeklyWeightCheck() {
  await loadWeights();
  const last = weights[weights.length - 1];
  let snooze = 0;
  try { snooze = Number(localStorage.getItem("weightSnooze:" + me.id)) || 0; } catch (_) {}
  if (last && Date.now() - last.atMs > WEEK && Date.now() > snooze && !document.querySelector("dialog[open]")) openWeight(true);
}

window.onProfileChanged = () => {
  if (me) weeklyWeightCheck();
  const active = document.querySelector(".tabs button.active");
  if (active && active.dataset.tab !== "workout") showTab(active.dataset.tab);
};

// Ссылка вида /treadmill/#hub открывает нужную вкладку (все скрипты уже загружены)
window.addEventListener("load", () => { if (location.hash) showTab(location.hash.slice(1)); });
