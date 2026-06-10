const CBR_DAILY_URL = "https://www.cbr-xml-daily.ru/daily_json.js";
const COINGECKO_PRICE_URL =
  "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=rub&include_24hr_change=true";
const CACHE_TTL_MS = 2 * 60 * 1000;
const FETCH_TIMEOUT_MS = 3500;

function toNumber(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function fiatRate(label, code, data) {
  const nominal = toNumber(data?.Nominal) ?? 1;
  const value = toNumber(data?.Value);
  const previous = toNumber(data?.Previous);

  return {
    label,
    code,
    valueRub: value === null ? null : value / nominal,
    previousRub: previous === null ? null : previous / nominal,
  };
}

function cryptoRate(label, code, data) {
  return {
    label,
    code,
    valueRub: toNumber(data?.rub),
    change24hPct: toNumber(data?.rub_24h_change),
  };
}

export function buildMarketSnapshot(cbrPayload, cryptoPayload) {
  return {
    fetchedAt: new Date().toISOString(),
    fiat: [
      fiatRate("Доллар", "USD", cbrPayload?.Valute?.USD),
      fiatRate("Евро", "EUR", cbrPayload?.Valute?.EUR),
      fiatRate("Юань", "CNY", cbrPayload?.Valute?.CNY),
    ],
    crypto: [
      cryptoRate("Bitcoin", "BTC", cryptoPayload?.bitcoin),
      cryptoRate("Ethereum", "ETH", cryptoPayload?.ethereum),
    ],
  };
}

export function createMarketService({
  fetchImpl = globalThis.fetch,
  cbrUrl = CBR_DAILY_URL,
  coinGeckoUrl = COINGECKO_PRICE_URL,
  cacheTtlMs = CACHE_TTL_MS,
  fetchTimeoutMs = FETCH_TIMEOUT_MS,
} = {}) {
  let cache = null;
  let cacheFetchedAtMs = 0;
  let inFlight = null;
  let intervalId = null;

  async function fetchJson(url) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), fetchTimeoutMs);

    const response = await fetchImpl(url, {
      signal: controller.signal,
      headers: {
        Accept: "application/json",
        "User-Agent": "MagicMirrorLAN/1.0 (+local mirror display)",
      },
    }).finally(() => {
      clearTimeout(timeout);
    });

    if (!response.ok) {
      throw new Error(`MARKET_HTTP_${response.status}`);
    }

    return response.json();
  }

  async function fetchLatest() {
    const [cbrPayload, cryptoPayload] = await Promise.all([
      fetchJson(cbrUrl).catch((error) => {
        console.warn("Failed to fetch CBR daily rates:", error.message);
        return null;
      }),
      fetchJson(coinGeckoUrl).catch((error) => {
        console.warn("Failed to fetch CoinGecko rates:", error.message);
        return null;
      }),
    ]);

    if (!cbrPayload && !cryptoPayload) {
      throw new Error("Both market data feeds failed to load.");
    }

    cache = buildMarketSnapshot(cbrPayload, cryptoPayload);
    cacheFetchedAtMs = Date.now();
    return cache;
  }

  return {
    startPolling() {
      if (intervalId) return;
      fetchLatest().catch(() => {});
      intervalId = setInterval(() => {
        fetchLatest().catch(() => {});
      }, cacheTtlMs);
      if (intervalId && typeof intervalId.unref === "function") {
        intervalId.unref();
      }
    },
    stop() {
      if (intervalId) {
        clearInterval(intervalId);
        intervalId = null;
      }
    },
    async getLatest() {
      if (cache && Date.now() - cacheFetchedAtMs < cacheTtlMs) {
        return {
          ...cache,
          cached: true,
        };
      }

      if (cache) {
        if (!inFlight) {
          inFlight = fetchLatest()
            .catch(() => {})
            .finally(() => {
              inFlight = null;
            });
        }
        return {
          ...cache,
          cached: true,
        };
      }

      try {
        inFlight ??= fetchLatest().finally(() => {
          inFlight = null;
        });

        return {
          ...(await inFlight),
          cached: false,
        };
      } catch (error) {
        if (cache) {
          return {
            ...cache,
            cached: true,
          };
        }

        throw error;
      }
    },
  };
}
