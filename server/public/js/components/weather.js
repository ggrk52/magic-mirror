const moscowWeatherUrl = new URL("https://api.open-meteo.com/v1/forecast");
moscowWeatherUrl.search = new URLSearchParams({
  latitude: "55.7558",
  longitude: "37.6173",
  current: "temperature_2m,weather_code",
  daily: "weather_code,temperature_2m_max,temperature_2m_min",
  timezone: "Europe/Moscow",
  forecast_days: "5",
  temperature_unit: "celsius",
}).toString();

const weatherCodeLabels = new Map([
  [0, "ясно"],
  [1, "почти ясно"],
  [2, "переменная облачность"],
  [3, "пасмурно"],
  [45, "туман"],
  [48, "изморозь"],
  [51, "морось"],
  [53, "морось"],
  [55, "сильная морось"],
  [56, "ледяная морось"],
  [57, "ледяная морось"],
  [61, "дождь"],
  [63, "дождь"],
  [65, "сильный дождь"],
  [66, "ледяной дождь"],
  [67, "ледяной дождь"],
  [71, "снег"],
  [73, "снег"],
  [75, "сильный снег"],
  [77, "снежные зерна"],
  [80, "ливень"],
  [81, "ливень"],
  [82, "сильный ливень"],
  [85, "снегопад"],
  [86, "сильный снегопад"],
  [95, "гроза"],
  [96, "гроза с градом"],
  [99, "гроза с градом"],
]);

let weatherTemp = null;
let weatherPlace = null;
let forecastList = null;

function formatTemperature(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "--";
  }
  return Math.round(value).toString();
}

function formatForecastTemperature(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "--°C";
  }
  return `${Math.round(value)}°C`;
}

function formatForecastDay(value) {
  const date = new Date(`${value}T12:00:00+03:00`);
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "short",
  })
    .format(date)
    .replace(".", "");
}

function weatherCodeLabel(code) {
  return weatherCodeLabels.get(code) ?? "погода";
}

function renderWeather(weather, onRender) {
  if (!weatherTemp) weatherTemp = document.querySelector("#weatherTemp");
  if (!weatherPlace) weatherPlace = document.querySelector("#weatherPlace");
  if (!forecastList) forecastList = document.querySelector("#forecastList");

  if (!weatherTemp || !weatherPlace || !forecastList) return;

  const currentTemperature = weather.current?.temperature_2m;
  const currentCode = weather.current?.weather_code;
  const daily = weather.daily ?? {};

  weatherTemp.innerHTML = `${formatTemperature(currentTemperature)}<span>&deg;C</span>`;
  weatherPlace.textContent = `Москва · ${weatherCodeLabel(currentCode)}`;
  forecastList.replaceChildren();

  for (let index = 0; index < Math.min(daily.time?.length ?? 0, 5); index += 1) {
    const item = document.createElement("li");
    const day = document.createElement("span");
    const max = document.createElement("strong");
    const min = document.createElement("em");

    day.textContent = formatForecastDay(daily.time[index]);
    max.textContent = formatForecastTemperature(daily.temperature_2m_max?.[index]);
    min.textContent = formatForecastTemperature(daily.temperature_2m_min?.[index]);
    item.title = weatherCodeLabel(daily.weather_code?.[index]);
    item.append(day, max, min);
    forecastList.append(item);
  }

  onRender?.();
}

export async function loadMoscowWeather(onRender) {
  if (!weatherPlace) weatherPlace = document.querySelector("#weatherPlace");

  try {
    const response = await fetch(moscowWeatherUrl, {
      cache: "no-store",
    });

    if (!response.ok) {
      throw new Error("Weather request failed");
    }

    renderWeather(await response.json(), onRender);
  } catch (error) {
    console.error("Weather load failed:", error);
    if (weatherPlace) {
      weatherPlace.textContent = "Москва · нет данных";
    }
    onRender?.();
  }
}

export function startWeatherPolling(onRender) {
  loadMoscowWeather(onRender);
  window.setInterval(() => loadMoscowWeather(onRender), 10 * 60 * 1000);
}
