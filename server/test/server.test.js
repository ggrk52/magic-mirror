import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";

import { buildMarketSnapshot } from "../src/markets.js";
import { createApp, shouldEmbedUiToken } from "../src/http.js";
import { parseRssItems } from "../src/news.js";
import { startServer } from "../src/server.js";
import { MirrorStore } from "../src/state.js";

function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  };
}

function fakeNewsService(items = [{ title: "Новость ТАСС", link: "https://tass.ru/" }]) {
  return {
    async getLatest() {
      return {
        source: "ТАСС",
        feedUrl: "https://tass.ru/rss/v2.xml",
        fetchedAt: "2026-04-25T12:00:00.000Z",
        cached: false,
        items,
      };
    },
  };
}

function fakeMarketService() {
  return {
    async getLatest() {
      return buildMarketSnapshot(
        {
          Valute: {
            USD: {
              Nominal: 1,
              Value: 75.5,
              Previous: 74.9,
            },
            EUR: {
              Nominal: 1,
              Value: 88.8,
              Previous: 88.1,
            },
            CNY: {
              Nominal: 1,
              Value: 10.4,
              Previous: 10.3,
            },
          },
        },
        {
          bitcoin: {
            rub: 5800000,
            rub_24h_change: -0.42,
          },
          ethereum: {
            rub: 250000,
            rub_24h_change: 0.75,
          },
        },
      );
    },
  };
}

function noopMdnsPublisher() {
  return {
    stop: async () => {},
  };
}

function startTestServer(options = {}) {
  return startServer({
    mdnsPublisher: noopMdnsPublisher,
    ...options,
  });
}

function requestApp(app, {
  url = "/api/health",
  method = "GET",
  remoteAddress = "127.0.0.1",
  localPort = 8080,
} = {}) {
  return new Promise((resolve, reject) => {
    const request = new EventEmitter();
    request.url = url;
    request.method = method;
    request.headers = {};
    request.socket = {
      localPort,
      remoteAddress,
    };

    const response = {
      headers: {},
      statusCode: 200,
      writeHead(statusCode, headers = {}) {
        this.statusCode = statusCode;
        this.headers = headers;
      },
      end(body = "") {
        resolve({
          status: this.statusCode,
          headers: this.headers,
          body: body.toString(),
        });
      },
    };

    app.handleRequest(request, response).catch(reject);
  });
}

test("browser UI token is only embedded for loopback clients", () => {
  assert.equal(shouldEmbedUiToken("127.0.0.1"), true);
  assert.equal(shouldEmbedUiToken("::1"), true);
  assert.equal(shouldEmbedUiToken("::ffff:127.0.0.1"), true);
  assert.equal(shouldEmbedUiToken("192.168.1.75"), false);
  assert.equal(shouldEmbedUiToken(undefined), false);
});

test("mDNS advertiser starts with the actual listening port and stops on close", async () => {
  let published;
  let stopped = false;

  const runtime = await startServer({
    port: 0,
    host: "127.0.0.1",
    mdnsPublisher(options) {
      published = options;
      return {
        stop: async () => {
          stopped = true;
        },
      };
    },
  });

  assert.equal(published.port, runtime.address.port);
  assert.equal(published.version, "1.0.0");

  await runtime.close();
  assert.equal(stopped, true);
});

test("pairing payload is only available to loopback clients", async () => {
  const app = createApp({
    store: new MirrorStore(),
    publicDir: ".",
    token: "pair-token",
    version: "test",
    marketService: fakeMarketService(),
    newsService: fakeNewsService(),
    setupService: null,
    serviceName: "Test Mirror",
  });

  const loopbackResponse = await requestApp(app, {
    url: "/api/pairing",
    remoteAddress: "127.0.0.1",
    localPort: 9191,
  });
  assert.equal(loopbackResponse.status, 200);

  const payload = JSON.parse(loopbackResponse.body);
  assert.equal(payload.type, "magic-mirror-pair");
  assert.equal(payload.token, "pair-token");
  assert.equal(payload.port, 9191);
  assert.equal(payload.service, "Test Mirror");

  const lanResponse = await requestApp(app, {
    url: "/api/pairing",
    remoteAddress: "192.168.1.20",
    localPort: 9191,
  });
  assert.equal(lanResponse.status, 403);
  assert.equal(lanResponse.body.includes("pair-token"), false);
});

test("pairing status changes after authenticated controller connect", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "pair-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const initialStatusResponse = await fetch(`${baseUrl}/api/pairing/status`, {
      headers: authHeaders(runtime.token),
    });
    assert.equal(initialStatusResponse.status, 200);
    const initialStatus = await initialStatusResponse.json();
    assert.equal(initialStatus.controllerConnected, false);
    assert.equal(initialStatus.controllerConnectedAt, null);

    const unauthorizedResponse = await fetch(`${baseUrl}/api/pairing/complete`, {
      method: "POST",
      headers: authHeaders("bad-token"),
    });
    assert.equal(unauthorizedResponse.status, 401);

    const completeResponse = await fetch(`${baseUrl}/api/pairing/complete`, {
      method: "POST",
      headers: authHeaders(runtime.token),
    });
    assert.equal(completeResponse.status, 200);
    const completeStatus = await completeResponse.json();
    assert.equal(completeStatus.controllerConnected, true);
    assert.match(completeStatus.controllerConnectedAt, /^\d{4}-\d{2}-\d{2}T/);

    const latestStatusResponse = await fetch(`${baseUrl}/api/pairing/status`, {
      headers: authHeaders(runtime.token),
    });
    const latestStatus = await latestStatusResponse.json();
    assert.equal(latestStatus.controllerConnected, true);
  } finally {
    await runtime.close();
  }
});

test("setup endpoints are disabled by default", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const response = await fetch(`${baseUrl}/api/setup/status`);
    assert.equal(response.status, 404);
  } finally {
    await runtime.close();
  }
});

test("health endpoint is public", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const response = await fetch(`${baseUrl}/api/health`);
    assert.equal(response.status, 200);

    const payload = await response.json();
    assert.equal(payload.status, "ok");
  } finally {
    await runtime.close();
  }
});

test("protected endpoints reject missing and invalid tokens", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "expected-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const missingTokenResponse = await fetch(`${baseUrl}/api/mirror/state`);
    assert.equal(missingTokenResponse.status, 401);

    const invalidTokenResponse = await fetch(`${baseUrl}/api/mirror/state`, {
      headers: {
        Authorization: "Bearer bad-token",
      },
    });
    assert.equal(invalidTokenResponse.status, 401);
  } finally {
    await runtime.close();
  }
});

test("display changes broadcast over websocket", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "socket-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;
  const wsUrl = `ws://127.0.0.1:${runtime.address.port}/ws?token=${runtime.token}`;
  let socket;

  try {
    const messagePromise = new Promise((resolve, reject) => {
      socket = new WebSocket(wsUrl);

      socket.addEventListener("message", (event) => {
        const message = JSON.parse(event.data);

        if (message.payload.displayState === "off") {
          socket.close();
          resolve(message);
        }
      });

      socket.addEventListener("error", reject);
    });

    const response = await fetch(`${baseUrl}/api/mirror/display`, {
      method: "POST",
      headers: authHeaders(runtime.token),
      body: JSON.stringify({ action: "off" }),
    });

    assert.equal(response.status, 200);

    const message = await messagePromise;
    assert.equal(message.type, "mirror_state_changed");
    assert.equal(message.payload.displayState, "off");
  } finally {
    socket?.close();
    await runtime.close();
  }
});

test("websocket rejects missing and invalid tokens", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "socket-token" });
  const baseUrl = `ws://127.0.0.1:${runtime.address.port}/ws`;

  async function expectUnauthorized(url) {
    await assert.rejects(
      new Promise((resolve, reject) => {
        const socket = new WebSocket(url);

        socket.addEventListener("open", () => {
          socket.close();
          resolve();
        });

        socket.addEventListener("error", reject);
      }),
    );
  }

  try {
    await expectUnauthorized(baseUrl);
    await expectUnauthorized(`${baseUrl}?token=bad-token`);
  } finally {
    await runtime.close();
  }
});

test("module visibility and refresh endpoints update state", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "module-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const visibilityResponse = await fetch(`${baseUrl}/api/modules/weather/visibility`, {
      method: "POST",
      headers: authHeaders(runtime.token),
      body: JSON.stringify({ visible: false }),
    });

    assert.equal(visibilityResponse.status, 200);

    const refreshedResponse = await fetch(`${baseUrl}/api/modules/refresh-all`, {
      method: "POST",
      headers: authHeaders(runtime.token),
    });

    assert.equal(refreshedResponse.status, 200);

    const stateResponse = await fetch(`${baseUrl}/api/mirror/state`, {
      headers: authHeaders(runtime.token),
    });
    const state = await stateResponse.json();
    const weatherModule = state.modules.find((module) => module.id === "weather");

    assert.equal(weatherModule.visible, false);
    assert.ok(weatherModule.lastUpdatedAt);
  } finally {
    await runtime.close();
  }
});

test("mirror mode endpoint toggles gallery and AR fitting modes", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "mode-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const galleryResponse = await fetch(`${baseUrl}/api/mirror/mode`, {
      method: "POST",
      headers: authHeaders(runtime.token),
      body: JSON.stringify({ mode: "gallery" }),
    });

    assert.equal(galleryResponse.status, 200);
    const galleryState = await galleryResponse.json();
    assert.equal(galleryState.displayMode, "gallery");

    const arResponse = await fetch(`${baseUrl}/api/mirror/mode`, {
      method: "POST",
      headers: authHeaders(runtime.token),
      body: JSON.stringify({ mode: "ar" }),
    });

    assert.equal(arResponse.status, 200);
    const arState = await arResponse.json();
    assert.equal(arState.displayMode, "ar");

    const stateResponse = await fetch(`${baseUrl}/api/mirror/state`, {
      headers: authHeaders(runtime.token),
    });
    const latestState = await stateResponse.json();
    assert.equal(latestState.displayMode, "ar");
  } finally {
    await runtime.close();
  }
});

test("mirror mode endpoint rejects invalid modes", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "mode-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const response = await fetch(`${baseUrl}/api/mirror/mode`, {
      method: "POST",
      headers: authHeaders(runtime.token),
      body: JSON.stringify({ mode: "screensaver" }),
    });

    assert.equal(response.status, 400);
    const payload = await response.json();
    assert.equal(payload.error, "INVALID_DISPLAY_MODE");
  } finally {
    await runtime.close();
  }
});

test("tass news endpoint returns parsed feed items", async () => {
  const runtime = await startTestServer({
    port: 0,
    host: "127.0.0.1",
    token: "news-token",
    newsService: fakeNewsService([
      {
        title: "Заголовок из RSS",
        link: "https://tass.ru/test",
        publishedAt: "Sat, 25 Apr 2026 12:00:00 +0300",
      },
    ]),
  });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const response = await fetch(`${baseUrl}/api/news/tass`, {
      headers: authHeaders(runtime.token),
    });

    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.equal(payload.source, "ТАСС");
    assert.equal(payload.items[0].title, "Заголовок из RSS");
  } finally {
    await runtime.close();
  }
});

test("markets endpoint returns fiat and crypto rates", async () => {
  const runtime = await startTestServer({
    port: 0,
    host: "127.0.0.1",
    token: "market-token",
    marketService: fakeMarketService(),
  });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const response = await fetch(`${baseUrl}/api/markets`, {
      headers: authHeaders(runtime.token),
    });

    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.deepEqual(
      payload.fiat.map((item) => item.code),
      ["USD", "EUR", "CNY"],
    );
    assert.deepEqual(
      payload.crypto.map((item) => item.code),
      ["BTC", "ETH"],
    );
    assert.equal(payload.fiat[0].valueRub, 75.5);
    assert.equal(payload.crypto[1].valueRub, 250000);
  } finally {
    await runtime.close();
  }
});

test("market snapshot normalizes currency nominal values", () => {
  const snapshot = buildMarketSnapshot(
    {
      Valute: {
        USD: {
          Nominal: 1,
          Value: 80,
          Previous: 79,
        },
        EUR: {
          Nominal: 1,
          Value: 90,
          Previous: 89,
        },
        CNY: {
          Nominal: 10,
          Value: 110,
          Previous: 108,
        },
      },
    },
    {
      bitcoin: {
        rub: 6000000,
        rub_24h_change: 1.5,
      },
      ethereum: {
        rub: 250000,
        rub_24h_change: -2.5,
      },
    },
  );

  assert.equal(snapshot.fiat[0].valueRub, 80);
  assert.equal(snapshot.fiat[1].valueRub, 90);
  assert.equal(snapshot.fiat[2].valueRub, 11);
  assert.equal(snapshot.crypto[0].change24hPct, 1.5);
});

test("rss parser extracts cdata titles and links", () => {
  const items = parseRssItems(`
    <rss>
      <channel>
        <item>
          <title><![CDATA[ТАСС &amp; новости]]></title>
          <link>https://tass.ru/example</link>
          <pubDate>Sat, 25 Apr 2026 12:00:00 +0300</pubDate>
        </item>
      </channel>
    </rss>
  `);

  assert.deepEqual(items, [
    {
      title: "ТАСС & новости",
      link: "https://tass.ru/example",
      publishedAt: "Sat, 25 Apr 2026 12:00:00 +0300",
    },
  ]);
});

test("invalid json payload returns 400", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "json-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;

  try {
    const response = await fetch(`${baseUrl}/api/mirror/display`, {
      method: "POST",
      headers: authHeaders(runtime.token),
      body: '{"action":',
    });

    assert.equal(response.status, 400);
    const payload = await response.json();
    assert.equal(payload.error, "INVALID_JSON");
  } finally {
    await runtime.close();
  }
});

test("oversized json payload returns 413", async () => {
  const runtime = await startTestServer({ port: 0, host: "127.0.0.1", token: "body-token" });
  const baseUrl = `http://127.0.0.1:${runtime.address.port}`;
  const oversizedAction = "x".repeat(70 * 1024);

  try {
    const response = await fetch(`${baseUrl}/api/mirror/display`, {
      method: "POST",
      headers: authHeaders(runtime.token),
      body: JSON.stringify({ action: oversizedAction }),
    });

    assert.equal(response.status, 413);
    const payload = await response.json();
    assert.equal(payload.error, "BODY_TOO_LARGE");
  } finally {
    await runtime.close();
  }
});
