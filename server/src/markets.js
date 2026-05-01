const CBR_DAILY_URL = "https://www.cbr-xml-daily.ru/daily_json.js";
const COINGECKO_PRICE_URL =
  "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=rub&include_24hr_change=true";
const CACHE_TTL_MS = 2 * 60 * 1000;

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
} = {}) {
  let cache = null;

  async function fetchJson(url) {
    const response = await fetchImpl(url, {
      headers: {
        Accept: "application/json",
        "User-Agent": "MagicMirrorLAN/1.0 (+local mirror display)",
      },
    });

    if (!response.ok) {
      throw new Error(`MARKET_HTTP_${response.status}`);
    }

    return response.json();
  }

  async function fetchLatest() {
    const [cbrPayload, cryptoPayload] = await Promise.all([
      fetchJson(cbrUrl),
      fetchJson(coinGeckoUrl),
    ]);

    cache = buildMarketSnapshot(cbrPayload, cryptoPayload);
    return cache;
  }

  return {
    async getLatest() {
      if (cache && Date.now() - Date.parse(cache.fetchedAt) < cacheTtlMs) {
        return {
          ...cache,
          cached: true,
        };
      }

      try {
        return {
          ...(await fetchLatest()),
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
