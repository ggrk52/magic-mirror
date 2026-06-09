let dateLine = null;
let timeMain = null;
let timeSeconds = null;
let timePeriod = null;
let greetingLine = null;

function formatMirrorDate(now) {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "long",
    month: "long",
    day: "numeric",
    year: "numeric",
  }).format(now);
}

function getGreeting(hour) {
  if (hour >= 4 && hour < 12) return "Доброе утро";
  if (hour >= 12 && hour < 17) return "Добрый день";
  if (hour >= 17 && hour < 23) return "Добрый вечер";
  return "Спокойной ночи";
}

export function updateClock() {
  if (!dateLine) dateLine = document.querySelector("#dateLine");
  if (!timeMain) timeMain = document.querySelector("#timeMain");
  if (!timeSeconds) timeSeconds = document.querySelector("#timeSeconds");
  if (!timePeriod) timePeriod = document.querySelector("#timePeriod");
  if (!greetingLine) greetingLine = document.querySelector("#greetingLine");

  if (!dateLine || !timeMain || !timeSeconds) return;

  const now = new Date();
  const hourNum = now.getHours();
  const hours = String(hourNum).padStart(2, "0");
  const minutes = String(now.getMinutes()).padStart(2, "0");
  const seconds = String(now.getSeconds()).padStart(2, "0");

  dateLine.textContent = formatMirrorDate(now);
  // Blinking colon via CSS class .time-colon
  timeMain.innerHTML = `${hours}<span class="time-colon">:</span>${minutes}`;
  timeSeconds.textContent = seconds;
  timePeriod.textContent = "";

  if (greetingLine) {
    greetingLine.textContent = getGreeting(hourNum);
  }
}

export function startClock() {
  updateClock();
  window.setInterval(updateClock, 1000);
}
