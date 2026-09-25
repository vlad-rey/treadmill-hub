"use strict";
// Награды: реальные награды, прогресс близких, ачивки; праздничные окна со звуком и фейерверком.
// Использует общие функции из app.js / programs.js: $, me, profiles, esc, showTab.

const GRADE = { BRONZE: "Бронза", SILVER: "Серебро", GOLD: "Золото", PLATINUM: "Платина", LEGEND: "Легенда" };
const PERIOD = { WEEK: "за неделю", MONTH: "за месяц" };
const GRADE_ORDER = ["LEGEND", "PLATINUM", "GOLD", "SILVER", "BRONZE"];

// --- Вкладка «Награды» -------------------------------------------------------------------
async function loadAwards() {
  if (!me) return;
  const mine = await (await fetch("/api/game/" + encodeURIComponent(me.id))).json();
  $("myRewards").innerHTML = mine.rewards.length ? "<h3>Мои награды</h3>" + mine.rewards.map((r) => rewardCard(r, false)).join("") : "";

  // Реальные награды других профилей: видно прогресс и что пора вручать (без всплывающих окон)
  const others = await Promise.all(profiles.filter((p) => p.id !== me.id).map((p) =>
    fetch("/api/game/" + encodeURIComponent(p.id)).then((r) => r.json()).then((g) => ({ p, g }))));
  $("othersRewards").innerHTML = others.filter((o) => o.g.rewards && o.g.rewards.length)
    .map((o) => `<h3>Прогресс: ${esc(o.p.name)}</h3>` + o.g.rewards.map((r) => rewardCard(r, true)).join("")).join("");

  renderAchievements(mine.achievements);
}
window.loadAwards = loadAwards;

function rewardCard(r, canDeliver) {
  const pct = Math.min(1, r.currentKm / r.def.km) * 100;
  const left = Math.max(0, r.def.km - r.currentKm);
  const state = r.earned ? (r.delivered ? "✅ вручено" : "🎉 заработано!") : `осталось ${left.toFixed(1)} км`;
  const deliver = canDeliver && r.earned
    ? `<button type="button" class="btn deliver" data-deliver="${esc(r.def.id)}|${esc(r.periodKey)}|${!r.delivered}">${r.delivered ? "Отменить «вручено»" : "Отметить: вручено"}</button>`
    : "";
  return `<div class="rewardCard ${r.earned ? "earned" : ""}">
    <div class="rIcon">${r.def.icon}</div>
    <div class="grow">
      <b>${esc(r.def.title)}</b>
      <small>${r.def.km} км ${PERIOD[r.def.period]} · пройдено ${r.currentKm.toFixed(1)} км · ${state}</small>
      <div class="bar"><i style="width:${pct}%"></i></div>
      <small>всего получено: ${r.earnedTotal}</small>
      ${deliver}
    </div>
  </div>`;
}

function renderAchievements(list) {
  const got = list.filter((a) => a.earnedAtMs).length;
  $("achTitle").textContent = `Ачивки · ${got} из ${list.length}`;
  const sorted = [...list].sort((a, b) =>
    (b.earnedAtMs ? 1 : 0) - (a.earnedAtMs ? 1 : 0) ||
    GRADE_ORDER.indexOf(a.grade) - GRADE_ORDER.indexOf(b.grade) ||
    b.progress - a.progress);
  $("achGrid").innerHTML = sorted.map((a) => {
    const hidden = a.secret && !a.earnedAtMs;
    const bar = !a.earnedAtMs && !hidden && a.progress > 0 ? `<div class="bar"><i style="width:${a.progress * 100}%"></i></div>` : "";
    return `<div class="ach g-${a.grade} ${a.earnedAtMs ? "earned" : "locked"}">
      <span class="gradeTag">${GRADE[a.grade]}</span>
      <div class="aIcon">${hidden ? "❓" : a.icon}</div>
      <b>${hidden ? "???" : esc(a.title)}</b>
      <small>${hidden ? "Секретная ачивка" : esc(a.description)}</small>
      ${bar}
    </div>`;
  }).join("");
}

document.addEventListener("click", async (e) => {
  const b = e.target.closest("[data-deliver]");
  if (!b) return;
  const [id, key, val] = b.dataset.deliver.split("|");
  await fetch(`/api/game/rewards/${encodeURIComponent(id)}/${encodeURIComponent(key)}/delivered`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ delivered: val === "true" }),
  });
  loadAwards();
});

// --- Звук: синтез в браузере, без файлов. Контекст открывается первым касанием экрана ----------
let audioCtx = null;
document.addEventListener("pointerdown", () => {
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === "suspended") audioCtx.resume();
  } catch (_) { /* без звука */ }
}, { capture: true });

function playNotes(notes) {
  if (!audioCtx) return;
  const t0 = audioCtx.currentTime + 0.05;
  for (const [freq, start, dur, type = "triangle", vol = 0.22] of notes) {
    const o = audioCtx.createOscillator(), g = audioCtx.createGain();
    o.type = type;
    o.frequency.value = freq;
    g.gain.setValueAtTime(0.0001, t0 + start);
    g.gain.exponentialRampToValueAtTime(vol, t0 + start + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + start + dur);
    o.connect(g).connect(audioCtx.destination);
    o.start(t0 + start);
    o.stop(t0 + start + dur + 0.05);
  }
}

const C5 = 523.25, E5 = 659.25, G5 = 783.99, C6 = 1046.5, E6 = 1318.5, G6 = 1568;
const SOUNDS = {
  chime: [[880, 0, 0.3], [E6, 0.12, 0.6]],
  sound: [[C5, 0, 0.16], [E5, 0.16, 0.16], [G5, 0.32, 0.16], [C6, 0.48, 0.5], [G5, 0.9, 0.14], [C6, 1.05, 1.0],
    [C5 / 2, 0.48, 0.5, "square", 0.05], [C5 / 2, 1.05, 1.0, "square", 0.05]],
  fireworks: [[C5, 0, 0.14], [E5, 0.14, 0.14], [G5, 0.28, 0.14], [C6, 0.42, 0.3], [E6, 0.72, 0.3], [G6, 1.02, 1.2],
    [G5, 1.02, 1.2, "square", 0.05], [C6, 1.9, 0.2], [E6, 2.1, 0.2], [G6, 2.3, 1.2]],
};

// --- Фейерверк на canvas ---------------------------------------------------------------------
let fw = null;
function startFireworks(seconds = 7) {
  const cv = $("fireworks"), ctx = cv.getContext("2d");
  cv.width = innerWidth * devicePixelRatio; cv.height = innerHeight * devicePixelRatio;
  ctx.scale(devicePixelRatio, devicePixelRatio);
  const parts = [], colors = ["#ff5f6d", "#ffc371", "#7ff0ff", "#d86bff", "#9be15d", "#ffd86b", "#ff9ff3"];
  const until = performance.now() + seconds * 1000;
  let nextBurst = 0;
  const burst = () => {
    const x = innerWidth * (0.15 + Math.random() * 0.7), y = innerHeight * (0.12 + Math.random() * 0.4);
    const c = colors[Math.floor(Math.random() * colors.length)];
    for (let i = 0; i < 70; i++) {
      const a = Math.random() * Math.PI * 2, s = 1.5 + Math.random() * 4.5;
      parts.push({ x, y, vx: Math.cos(a) * s, vy: Math.sin(a) * s, life: 1, c });
    }
  };
  const step = (now) => {
    ctx.clearRect(0, 0, innerWidth, innerHeight);
    if (now < until && now > nextBurst) { burst(); nextBurst = now + 350 + Math.random() * 400; }
    for (const p of parts) {
      p.x += p.vx; p.y += p.vy; p.vy += 0.06; p.vx *= 0.99; p.life -= 0.012;
      if (p.life > 0) { ctx.globalAlpha = p.life; ctx.fillStyle = p.c; ctx.fillRect(p.x, p.y, 3, 3); }
    }
    for (let i = parts.length - 1; i >= 0; i--) if (parts[i].life <= 0) parts.splice(i, 1);
    ctx.globalAlpha = 1;
    if (now < until || parts.length) fw = requestAnimationFrame(step); else fw = null;
  };
  fw = requestAnimationFrame(step);
}
function stopFireworks() {
  if (fw) cancelAnimationFrame(fw);
  fw = null;
  const cv = $("fireworks");
  cv.getContext("2d").clearRect(0, 0, cv.width, cv.height);
}

// --- Очередь праздничных окон: только для своего профиля ------------------------------------
const celebrationQueue = [];
const celebrationSeen = new Set();
let celebrationShowing = null;

window.onCelebrations = function (list) {
  if (!me) return;
  for (const c of list) {
    if (c.profileId === me.id && !celebrationSeen.has(c.id)) { celebrationSeen.add(c.id); celebrationQueue.push(c); }
  }
  showNextCelebration();
};

function showNextCelebration() {
  if (celebrationShowing || !celebrationQueue.length) return;
  const c = celebrationShowing = celebrationQueue.shift();
  const card = document.querySelector(".celebrateCard");
  card.className = "celebrateCard" + (c.kind === "achievement" ? ` achievement g-${c.grade}` : "");
  $("ceIcon").textContent = c.icon;
  $("ceKind").textContent = c.kind === "reward" ? "🎁 Реальная награда" : `Ачивка · ${GRADE[c.grade] || ""}`;
  $("ceTitle").textContent = c.title;
  $("ceText").textContent = c.text;
  $("celebrate").classList.remove("hidden");
  playNotes(SOUNDS[c.effect] || SOUNDS.chime);
  if (c.effect === "fireworks") startFireworks();
  if (navigator.vibrate) navigator.vibrate(c.kind === "reward" ? [200, 100, 200, 100, 400] : [80]);
}

$("ceOk").onclick = async () => {
  const c = celebrationShowing;
  $("celebrate").classList.add("hidden");
  stopFireworks();
  celebrationShowing = null;
  if (c) await fetch(`/api/game/celebrations/${encodeURIComponent(c.id)}/ack`, { method: "POST" });
  if (!$("tab-awards").classList.contains("hidden")) loadAwards();
  showNextCelebration();
};
