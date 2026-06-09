let dateLine = null;
let timeMain = null;
let timeSeconds = null;
let timePeriod = null;

function formatMirrorDate(now) {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "long",
    month: "long",
    day: "numeric",
    year: "numeric",
  }).format(now);
}

export function updateClock() {
  if (!dateLine) dateLine = document.querySelector("#dateLine");
  if (!timeMain) timeMain = document.querySelector("#timeMain");
  if (!timeSeconds) timeSeconds = document.querySelector("#timeSeconds");
  if (!timePeriod) timePeriod = document.querySelector("#timePeriod");

  if (!dateLine || !timeMain || !timeSeconds) return;

  const now = new Date();
  const hours = String(now.getHours()).padStart(2, "0");
  const minutes = String(now.getMinutes()).padStart(2, "0");
  const seconds = String(now.getSeconds()).padStart(2, "0");

  dateLine.textContent = formatMirrorDate(now);
  timeMain.textContent = `${hours}:${minutes}`;
  timeSeconds.textContent = seconds;
  timePeriod.textContent = "";
}

export function startClock() {
  updateClock();
  window.setInterval(updateClock, 1000);
}
