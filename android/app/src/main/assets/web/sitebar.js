"use strict";
// Общая шапка: часы справа. Разметка шапки — в каждой странице (без мигания при загрузке).
(function () {
  const el = document.getElementById("sbClock");
  if (!el) return;
  const tick = () => { el.textContent = new Date().toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }); };
  tick();
  setInterval(tick, 10e3);
})();
