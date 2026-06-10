let fiatRates = null;
let cryptoRates = null;

function formatRub(value, fractionDigits = 2) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "--";
  }

  return new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: fractionDigits,
    minimumFractionDigits: fractionDigits,
  }).format(value);
}

function formatMarketDelta(value, previous) {
  if (typeof value !== "number" || typeof previous !== "number") {
    return "";
  }

  const delta = value - previous;
  if (Math.abs(delta) < 0.0001) {
    return "0.00";
  }

  return `${delta > 0 ? "+" : ""}${formatRub(delta, 2)}`;
}

function formatCryptoDelta(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "";
  }

  return `${value > 0 ? "+" : ""}${formatRub(value, 2)}%`;
}

function marketDeltaClass(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "";
  }

  if (value > 0) {
    return "up";
  }

  if (value < 0) {
    return "down";
  }

  return "";
}

function renderMarketRows(container, items, type) {
  container.replaceChildren();

  for (const item of items) {
    const row = document.createElement("div");
    const label = document.createElement("span");
    const value = document.createElement("strong");
    const meta = document.createElement("em");
    const deltaValue =
      type === "crypto"
        ? item.change24hPct
        : item.valueRub - item.previousRub;

    row.className = "market-row";
    label.textContent = item.label;
    value.textContent = `${formatRub(item.valueRub, item.valueRub > 1000 ? 0 : 2)} ₽`;
    meta.textContent =
      type === "crypto"
        ? `${item.code} ${formatCryptoDelta(item.change24hPct)}`
        : `${item.code} ${formatMarketDelta(item.valueRub, item.previousRub)}`;
    meta.classList.toggle("up", marketDeltaClass(deltaValue) === "up");
    meta.classList.toggle("down", marketDeltaClass(deltaValue) === "down");

    row.append(label, value, meta);
    container.append(row);
  }
}

export function renderMarkets(payload, onRender) {
  if (!fiatRates) fiatRates = document.querySelector("#fiatRates");
  if (!cryptoRates) cryptoRates = document.querySelector("#cryptoRates");

  if (!fiatRates || !cryptoRates) return;

  renderMarketRows(fiatRates, payload.fiat ?? [], "fiat");
  renderMarketRows(cryptoRates, payload.crypto ?? [], "crypto");
  onRender?.();
}

export async function loadMarkets(wsToken, onRender) {
  if (!fiatRates) fiatRates = document.querySelector("#fiatRates");
  if (!cryptoRates) cryptoRates = document.querySelector("#cryptoRates");

  try {
    const response = await fetch("/api/markets", {
      headers: {
        Authorization: `Bearer ${wsToken}`,
      },
      cache: "no-store",
    });

    if (!response.ok) {
      throw new Error("Market request failed");
    }

    renderMarkets(await response.json(), onRender);
  } catch (error) {
    console.error("Markets load failed:", error);
    if (fiatRates) {
      const strongVal = fiatRates.querySelector("strong");
      if (strongVal) strongVal.textContent = "нет данных";
    }
    if (cryptoRates) {
      const strongVal = cryptoRates.querySelector("strong");
      if (strongVal) strongVal.textContent = "нет данных";
    }
    onRender?.();
  }
}

export function startMarketsPolling(wsToken, onRender) {
  loadMarkets(wsToken, onRender);
  window.setInterval(() => loadMarkets(wsToken, onRender), 2 * 60 * 1000);
}
