const TASS_RSS_URL = "https://tass.ru/rss/v2.xml";
const CACHE_TTL_MS = 5 * 60 * 1000;

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
} = {}) {
  let cache = null;

  async function fetchLatest() {
    const response = await fetchImpl(feedUrl, {
      headers: {
        Accept: "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
        "User-Agent": "MagicMirrorLAN/1.0 (+local mirror display)",
      },
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
