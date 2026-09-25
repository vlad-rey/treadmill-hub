"use strict";
// Shared header: clock on the right. Header markup lives in each page (to avoid a flash on load).
(function () {
  const el = document.getElementById("sbClock");
  if (!el) return;
  const tick = () => { el.textContent = new Date().toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }); };
  tick();
  setInterval(tick, 10e3);
})();
