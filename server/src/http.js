import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";

import { buildPairingPayload, buildPairingQrSvg } from "./pairing.js";
import { SetupError } from "./setup.js";

const textEncoder = new TextEncoder();

const MIME_TYPES = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml; charset=utf-8",
};

function json(response, statusCode, payload) {
  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
  });
  response.end(JSON.stringify(payload));
}

function svg(response, statusCode, body) {
  response.writeHead(statusCode, {
    "Content-Type": "image/svg+xml; charset=utf-8",
    "Cache-Control": "no-store",
  });
  response.end(body);
}

function getBearerToken(request) {
  const value = request.headers.authorization ?? "";

  if (!value.startsWith("Bearer ")) {
    return null;
  }

  return value.slice("Bearer ".length).trim();
}

function escapeScriptValue(value) {
  return JSON.stringify(String(value)).slice(1, -1);
}

export function shouldEmbedUiToken(remoteAddress) {
  return [
    "127.0.0.1",
    "::1",
    "::ffff:127.0.0.1",
  ].includes(remoteAddress ?? "");
}

function readJsonBody(request) {
  return new Promise((resolve, reject) => {
    let rawBody = "";
    let bodyTooLarge = false;

    request.on("data", (chunk) => {
      if (bodyTooLarge) {
        return;
      }

      rawBody += chunk;

      if (rawBody.length > 1024 * 64) {
        bodyTooLarge = true;
        reject(new Error("BODY_TOO_LARGE"));
      }
    });

    request.on("end", () => {
      if (bodyTooLarge) {
        return;
      }

      if (!rawBody) {
        resolve({});
        return;
      }

      try {
        resolve(JSON.parse(rawBody));
      } catch (error) {
        reject(new Error("INVALID_JSON"));
      }
    });

    request.on("error", reject);
  });
}

async function serveStaticFile(response, publicDir, pathname) {
  const relativePath = pathname === "/" ? "/index.html" : pathname;
  const safePath = normalize(relativePath)
    .replace(/^(\.\.[\\/])+/, "")
    .replace(/^[/\\]+/, "");
  const absolutePath = join(publicDir, safePath);
  const body = await readFile(absolutePath);
  const extension = extname(absolutePath);

  response.writeHead(200, {
    "Content-Type": MIME_TYPES[extension] ?? "application/octet-stream",
  });
  response.end(body);
}

async function serveIndexHtml(response, publicDir, token) {
  const absolutePath = join(publicDir, "index.html");
  const template = await readFile(absolutePath, "utf8");
  const body = template.replace("__MIRROR_WS_TOKEN__", escapeScriptValue(token));

  response.writeHead(200, {
    "Content-Type": "text/html; charset=utf-8",
  });
  response.end(body);
}

function createWebSocketFrame(payload) {
  const payloadBytes = textEncoder.encode(payload);
  let header;

  if (payloadBytes.length <= 125) {
    header = Uint8Array.of(0x81, payloadBytes.length);
  } else if (payloadBytes.length <= 65535) {
    header = Uint8Array.of(0x81, 126, payloadBytes.length >> 8, payloadBytes.length & 0xff);
  } else {
    throw new Error("FRAME_TOO_LARGE");
  }

  const frame = new Uint8Array(header.length + payloadBytes.length);
  frame.set(header, 0);
  frame.set(payloadBytes, header.length);

  return frame;
}

function acceptWebSocketKey(key) {
  return createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64");
}

export function createApp({
  store,
  publicDir,
  token,
  version,
  marketService,
  newsService,
  setupService,
  serviceName = process.env.MIRROR_SERVICE_NAME ?? "Magic Mirror",
}) {
  const sockets = new Set();
  const getToken = typeof token === "function" ? token : () => token;
  const apiRouteMethods = new Map([
    ["/api/health", ["GET"]],
    ["/api/pairing", ["GET"]],
    ["/api/pairing/qr.svg", ["GET"]],
    ["/api/pairing/status", ["GET"]],
    ["/api/pairing/complete", ["POST"]],
    ["/api/setup/status", ["GET"]],
    ["/api/setup/wifi", ["POST"]],
    ["/api/setup/token", ["POST"]],
    ["/api/markets", ["GET"]],
    ["/api/news/tass", ["GET"]],
    ["/api/mirror/state", ["GET"]],
    ["/api/mirror/display", ["POST"]],
    ["/api/mirror/mode", ["POST"]],
    ["/api/modules", ["GET"]],
    ["/api/modules/refresh-all", ["POST"]],
  ]);
  const pairingStatus = {
    controllerConnected: false,
    controllerConnectedAt: null,
  };

  function getWebSocketToken(request) {
    const url = new URL(request.url, "http://localhost");
    return url.searchParams.get("token")?.trim() || null;
  }

  function isAuthorizedToken(candidate) {
    return Boolean(candidate) && candidate === getToken();
  }

  function sendMethodNotAllowed(response, methods) {
    response.writeHead(405, {
      Allow: methods.join(", "),
      "Content-Type": "application/json; charset=utf-8",
    });
    response.end(
      JSON.stringify({
        error: "METHOD_NOT_ALLOWED",
        message: `Method must be one of: ${methods.join(", ")}.`,
      }),
    );
  }

  function broadcastState() {
    const payload = JSON.stringify({
      type: "mirror_state_changed",
      payload: store.getState(),
    });

    for (const socket of sockets) {
      try {
        socket.write(createWebSocketFrame(payload));
      } catch (error) {
        socket.destroy();
        sockets.delete(socket);
      }
    }
  }

  function broadcastPairingStatus() {
    const payload = JSON.stringify({
      type: "pairing_status_changed",
      payload: pairingStatus,
    });

    for (const socket of sockets) {
      try {
        socket.write(createWebSocketFrame(payload));
      } catch (error) {
        socket.destroy();
        sockets.delete(socket);
      }
    }
  }

  function requireAuth(request, response) {
    const bearerToken = getBearerToken(request);

    if (!isAuthorizedToken(bearerToken)) {
      json(response, 401, {
        error: "UNAUTHORIZED",
        message: "A valid bearer token is required.",
      });
      return false;
    }

    return true;
  }

  function requireLoopback(request, response) {
    if (shouldEmbedUiToken(request.socket.remoteAddress)) {
      return true;
    }

    json(response, 403, {
      error: "PAIRING_FORBIDDEN",
      message: "Pairing QR with token is only available from the mirror device itself.",
    });
    return false;
  }

  async function handlePairing(request, response, pathname, serverPort) {
    if (!requireLoopback(request, response)) {
      return true;
    }

    const payload = buildPairingPayload({
      token: getToken(),
      port: serverPort,
      service: serviceName,
    });

    if (pathname === "/api/pairing/qr.svg") {
      svg(response, 200, await buildPairingQrSvg(payload));
      return true;
    }

    json(response, 200, payload);
    return true;
  }

  async function handleSetup(request, response, pathname) {
    if (!setupService) {
      json(response, 404, {
        error: "SETUP_MODE_DISABLED",
        message: "Setup mode is disabled.",
      });
      return true;
    }

    try {
      if (request.method === "GET" && pathname === "/api/setup/status") {
        json(response, setupService.enabled ? 200 : 404, await setupService.status());
        return true;
      }

      if (request.method === "POST" && pathname === "/api/setup/wifi") {
        const body = await readJsonBody(request);
        json(response, 200, await setupService.applyWifi(body));
        return true;
      }

      if (request.method === "POST" && pathname === "/api/setup/token") {
        const body = await readJsonBody(request);
        json(response, 200, await setupService.updateToken(body));
        return true;
      }
    } catch (error) {
      if (error instanceof SetupError) {
        json(response, error.statusCode, {
          error: error.message,
          message: error.publicMessage,
        });
        return true;
      }

      throw error;
    }

    return false;
  }

  async function handleApi(request, response, pathname) {
    const routeMethods = apiRouteMethods.get(pathname);
    if (routeMethods && !routeMethods.includes(request.method)) {
      sendMethodNotAllowed(response, routeMethods);
      return;
    }

    if (request.method === "GET" && pathname === "/api/health") {
      json(response, 200, {
        status: "ok",
        version,
        name: serviceName,
        service: "_magicmirror._tcp",
        setupMode: Boolean(setupService?.enabled),
        serverTime: new Date().toISOString(),
      });
      return;
    }

    if (pathname === "/api/pairing" || pathname === "/api/pairing/qr.svg") {
      const address = request.socket.localPort ?? 8080;
      await handlePairing(request, response, pathname, address);
      return;
    }

    if (pathname.startsWith("/api/pairing/") && !requireAuth(request, response)) {
      return;
    }

    if (request.method === "GET" && pathname === "/api/pairing/status") {
      json(response, 200, pairingStatus);
      return;
    }

    if (request.method === "POST" && pathname === "/api/pairing/complete") {
      pairingStatus.controllerConnected = true;
      pairingStatus.controllerConnectedAt = new Date().toISOString();
      json(response, 200, pairingStatus);
      broadcastPairingStatus();
      return;
    }

    if (pathname.startsWith("/api/setup/")) {
      if (await handleSetup(request, response, pathname)) {
        return;
      }
    }

    if (pathname.startsWith("/api/") && !requireAuth(request, response)) {
      return;
    }

    if (request.method === "GET" && pathname === "/api/mirror/state") {
      json(response, 200, store.getState());
      return;
    }

    if (request.method === "GET" && pathname === "/api/modules") {
      json(response, 200, {
        modules: store.getState().modules,
      });
      return;
    }

    if (request.method === "GET" && pathname === "/api/markets") {
      try {
        json(response, 200, await marketService.getLatest());
      } catch (error) {
        json(response, 502, {
          error: "MARKET_DATA_UNAVAILABLE",
          message: "Market data is unavailable.",
        });
      }
      return;
    }

    if (request.method === "GET" && pathname === "/api/news/tass") {
      try {
        json(response, 200, await newsService.getLatest());
      } catch (error) {
        json(response, 502, {
          error: "NEWS_FEED_UNAVAILABLE",
          message: "TASS RSS feed is unavailable.",
        });
      }
      return;
    }

    if (request.method === "POST" && pathname === "/api/mirror/display") {
      const body = await readJsonBody(request);

      try {
        const state = store.setDisplayAction(body.action);
        json(response, 200, state);
        broadcastState();
      } catch (error) {
        json(response, 400, {
          error: error.message,
          message: "Action must be one of on, off, or reload.",
        });
      }
      return;
    }

    if (request.method === "POST" && pathname === "/api/mirror/mode") {
      const body = await readJsonBody(request);

      try {
        const state = store.setDisplayMode(body.mode);
        json(response, 200, state);
        broadcastState();
      } catch (error) {
        json(response, 400, {
          error: error.message,
          message: "Mode must be one of mirror, gallery, or ar.",
        });
      }
      return;
    }

    const visibilityMatch = pathname.match(/^\/api\/modules\/([^/]+)\/visibility$/);
    if (visibilityMatch && request.method !== "POST") {
      sendMethodNotAllowed(response, ["POST"]);
      return;
    }

    if (request.method === "POST" && visibilityMatch) {
      const body = await readJsonBody(request);

      try {
        const state = store.setModuleVisibility(
          decodeURIComponent(visibilityMatch[1]),
          body.visible,
        );
        json(response, 200, state);
        broadcastState();
      } catch (error) {
        const statusCode = error.message === "MODULE_NOT_FOUND" ? 404 : 400;
        json(response, statusCode, {
          error: error.message,
          message:
            error.message === "MODULE_NOT_FOUND"
              ? "Module was not found."
              : "Visible must be a boolean value.",
        });
      }
      return;
    }

    const refreshMatch = pathname.match(/^\/api\/modules\/([^/]+)\/refresh$/);
    if (refreshMatch && request.method !== "POST") {
      sendMethodNotAllowed(response, ["POST"]);
      return;
    }

    if (request.method === "POST" && refreshMatch) {
      try {
        const state = store.refreshModule(decodeURIComponent(refreshMatch[1]));
        json(response, 200, state);
        broadcastState();
      } catch (error) {
        json(response, 404, {
          error: error.message,
          message: "Module was not found.",
        });
      }
      return;
    }

    if (request.method === "POST" && pathname === "/api/modules/refresh-all") {
      const state = store.refreshAll();
      json(response, 200, state);
      broadcastState();
      return;
    }

    json(response, 404, {
      error: "NOT_FOUND",
      message: "Route not found.",
    });
  }

  async function handleRequest(request, response) {
    try {
      const url = new URL(request.url, "http://localhost");

      if (url.pathname.startsWith("/api/")) {
        await handleApi(request, response, url.pathname);
        return;
      }

      if (request.method !== "GET" && request.method !== "HEAD") {
        json(response, 405, {
          error: "METHOD_NOT_ALLOWED",
          message: "Static assets only support GET and HEAD.",
        });
        return;
      }

      if (url.pathname === "/") {
        const uiToken = shouldEmbedUiToken(request.socket.remoteAddress) ? getToken() : "";
        await serveIndexHtml(response, publicDir, uiToken);
        return;
      }

      await serveStaticFile(response, publicDir, url.pathname);
    } catch (error) {
      if (error.message === "BODY_TOO_LARGE") {
        json(response, 413, {
          error: "BODY_TOO_LARGE",
          message: "Request body must be 64 KB or smaller.",
        });
        return;
      }

      if (error.message === "INVALID_JSON") {
        json(response, 400, {
          error: "INVALID_JSON",
          message: "Body must be valid JSON.",
        });
        return;
      }

      if (error.code === "ENOENT") {
        json(response, 404, {
          error: "NOT_FOUND",
          message: "File not found.",
        });
        return;
      }

      json(response, 500, {
        error: "INTERNAL_SERVER_ERROR",
        message: "Unexpected server error.",
      });
    }
  }

  function handleUpgrade(request, socket) {
    const url = new URL(request.url, "http://localhost");

    if (url.pathname !== "/ws") {
      socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
      socket.destroy();
      return;
    }

    const webSocketToken = getWebSocketToken(request);
    if (!isAuthorizedToken(webSocketToken)) {
      socket.write(
        "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json; charset=utf-8\r\n\r\n",
      );
      socket.destroy();
      return;
    }

    const webSocketKey = request.headers["sec-websocket-key"];

    if (!webSocketKey) {
      socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
      socket.destroy();
      return;
    }

    socket.write(
      [
        "HTTP/1.1 101 Switching Protocols",
        "Upgrade: websocket",
        "Connection: Upgrade",
        `Sec-WebSocket-Accept: ${acceptWebSocketKey(webSocketKey)}`,
        "",
        "",
      ].join("\r\n"),
    );

    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    socket.on("error", () => sockets.delete(socket));
    socket.on("end", () => sockets.delete(socket));

    try {
      socket.write(
        createWebSocketFrame(
          JSON.stringify({
            type: "mirror_state_changed",
            payload: store.getState(),
          }),
        ),
      );
      socket.write(
        createWebSocketFrame(
          JSON.stringify({
            type: "pairing_status_changed",
            payload: pairingStatus,
          }),
        ),
      );
    } catch (error) {
      socket.destroy();
      sockets.delete(socket);
    }
  }

  return {
    closeSockets() {
      for (const socket of sockets) {
        socket.destroy();
      }

      sockets.clear();
    },
    handleRequest,
    handleUpgrade,
  };
}
