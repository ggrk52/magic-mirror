const TASS_RSS_URL = "https://tass.ru/rss/v2.xml";
const CACHE_TTL_MS = 5 * 60 * 1000;
const FETCH_TIMEOUT_MS = 3500;

function decodeXmlEntities(value) {
  return value
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&quot;/g, "\"")
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .trim();
}

function readTag(xml, tagName) {
  const match = xml.match(new RegExp(`<${tagName}[^>]*>([\\s\\S]*?)<\\/${tagName}>`, "i"));
  return match ? decodeXmlEntities(match[1]) : "";
}

export function parseRssItems(xml, limit = 12) {
  return [...xml.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)]
    .slice(0, limit)
    .map((match) => {
      const itemXml = match[1];

      return {
        title: readTag(itemXml, "title"),
        link: readTag(itemXml, "link"),
        publishedAt: readTag(itemXml, "pubDate"),
      };
    })
    .filter((item) => item.title);
}

export function createTassNewsService({
  fetchImpl = globalThis.fetch,
  feedUrl = TASS_RSS_URL,
  cacheTtlMs = CACHE_TTL_MS,
  fetchTimeoutMs = FETCH_TIMEOUT_MS,
} = {}) {
  let cache = null;
  let cacheFetchedAtMs = 0;
  let inFlight = null;
  let intervalId = null;

  async function fetchLatest() {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), fetchTimeoutMs);

    const response = await fetchImpl(feedUrl, {
      signal: controller.signal,
      headers: {
        Accept: "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
        "User-Agent": "MagicMirrorLAN/1.0 (+local mirror display)",
      },
    }).finally(() => {
      clearTimeout(timeout);
    });

    if (!response.ok) {
      throw new Error(`TASS_RSS_HTTP_${response.status}`);
    }

    const xml = await response.text();
    const items = parseRssItems(xml);

    if (items.length === 0) {
      throw new Error("TASS_RSS_EMPTY");
    }

    cache = {
      source: "ТАСС",
      feedUrl,
      fetchedAt: new Date().toISOString(),
      items,
    };
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
