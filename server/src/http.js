import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { extname, join } from "node:path";

import { buildPairingPayload, buildPairingQrSvg } from "./pairing.js";
import { SetupError } from "./setup.js";

const textEncoder = new TextEncoder();
const JSON_BODY_LIMIT_BYTES = 1024 * 64;
const PHOTO_BODY_LIMIT_BYTES = 1024 * 1024 * 9;
const PHOTO_DATA_LIMIT_BYTES = 1024 * 1024 * 6;
const PHOTO_DURATION_MIN_SECONDS = 1;
const PHOTO_DURATION_MAX_SECONDS = 60 * 60;
const PHOTO_MIME_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

const MIME_TYPES = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".otf": "font/otf",
  ".svg": "image/svg+xml; charset=utf-8",
  ".ttf": "font/ttf",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
};
const STATIC_CACHE_CONTROL = "public, max-age=300";
const STATIC_CACHE_MAX_BYTES = 1024 * 1024 * 2;
const STATIC_NO_MEMORY_CACHE_EXTENSIONS = new Set([".css", ".js", ".html"]);
const staticFileCache = new Map();

function json(response, statusCode, payload) {
  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
  });
  response.end(JSON.stringify(payload));
}

function binary(response, statusCode, body, mimeType) {
  response.writeHead(statusCode, {
    "Content-Type": mimeType,
    "Content-Length": body.length,
    "Cache-Control": "no-store",
  });
  response.end(body);
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
  if (!remoteAddress) {
    return false;
  }

  return (
    remoteAddress === "127.0.0.1" ||
    remoteAddress === "::1" ||
    remoteAddress === "::ffff:127.0.0.1"
  );
}

function formatBytes(bytes) {
  if (bytes >= 1024 * 1024) {
    return `${Math.round((bytes / 1024 / 1024) * 10) / 10} MB`;
  }

  return `${Math.round(bytes / 1024)} KB`;
}

function bodyTooLargeError(limitBytes) {
  const error = new Error("BODY_TOO_LARGE");
  error.limitBytes = limitBytes;
  return error;
}

function readJsonBody(request, { maxBytes = JSON_BODY_LIMIT_BYTES } = {}) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let totalBytes = 0;
    let bodyTooLarge = false;

    request.on("data", (chunk) => {
      if (bodyTooLarge) {
        return;
      }

      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      totalBytes += buffer.length;

      if (totalBytes > maxBytes) {
        bodyTooLarge = true;
        reject(bodyTooLargeError(maxBytes));
        return;
      }

      chunks.push(buffer);
    });

    request.on("end", () => {
      if (bodyTooLarge) {
        return;
      }

      const rawBody = Buffer.concat(chunks).toString("utf8");

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

function parsePhotoPayload(body) {
  const durationSeconds = Number(body.durationSeconds ?? 300);

  if (!Number.isFinite(durationSeconds)) {
    throw new Error("INVALID_PHOTO_DURATION");
  }

  const normalizedDurationSeconds = Math.floor(durationSeconds);
  if (
    normalizedDurationSeconds < PHOTO_DURATION_MIN_SECONDS ||
    normalizedDurationSeconds > PHOTO_DURATION_MAX_SECONDS
  ) {
    throw new Error("INVALID_PHOTO_DURATION");
  }

  if (typeof body.imageData !== "string") {
    throw new Error("INVALID_PHOTO_DATA");
  }

  const match = body.imageData.match(/^data:(image\/(?:jpeg|png|webp));base64,([A-Za-z0-9+/=\s]+)$/);
  if (!match) {
    throw new Error("INVALID_PHOTO_DATA");
  }

  const mimeType = match[1].toLowerCase();
  if (!PHOTO_MIME_TYPES.has(mimeType)) {
    throw new Error("INVALID_PHOTO_TYPE");
  }

  const data = Buffer.from(match[2].replace(/\s/g, ""), "base64");
  if (data.length === 0 || data.length > PHOTO_DATA_LIMIT_BYTES) {
    throw new Error("PHOTO_TOO_LARGE");
  }

  return {
    data,
    mimeType,
    durationSeconds: normalizedDurationSeconds,
  };
}

async function serveStaticFile(response, publicDir, pathname) {
  const safePath = pathname === "/" ? "/index.html" : pathname;
  const absolutePath = join(publicDir, safePath);
  const extension = extname(absolutePath);
  const canUseMemoryCache = !STATIC_NO_MEMORY_CACHE_EXTENSIONS.has(extension);
  const cached = canUseMemoryCache ? staticFileCache.get(absolutePath) : null;
  let body;

  if (cached) {
    body = cached.body;
  } else {
    body = await readFile(absolutePath);

    if (canUseMemoryCache && body.length <= STATIC_CACHE_MAX_BYTES) {
      staticFileCache.set(absolutePath, { body });
    }
  }

  response.writeHead(200, {
    "Content-Type": MIME_TYPES[extension] ?? "application/octet-stream",
    "Content-Length": body.length,
    "Cache-Control": canUseMemoryCache ? STATIC_CACHE_CONTROL : "no-store",
  });
  response.end(body);
}

async function serveIndexHtml(response, publicDir, token) {
  const absolutePath = join(publicDir, "index.html");
  const template = await readFile(absolutePath, "utf8");
  const body = template.replace("__MIRROR_WS_TOKEN__", escapeScriptValue(token));

  response.writeHead(200, {
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store",
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
    ["/api/diagnostics", ["GET"]],
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
    ["/api/mirror/layout/edit", ["POST"]],
    ["/api/mirror/photo", ["POST", "DELETE"]],
    ["/api/mirror/photo/current", ["GET"]],
    ["/api/modules", ["GET"]],
    ["/api/modules/layout", ["POST"]],
    ["/api/modules/layout/reset", ["POST"]],
    ["/api/modules/refresh-all", ["POST"]],
  ]);
  const pairingStatus = {
    controllerConnected: false,
    controllerConnectedAt: null,
  };
  let photoOverlayTimer = null;

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
    expirePhotoOverlayIfNeeded();

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

  function clearPhotoOverlayTimer() {
    if (photoOverlayTimer) {
      clearTimeout(photoOverlayTimer);
      photoOverlayTimer = null;
    }
  }

  function expirePhotoOverlayIfNeeded({ broadcast = false } = {}) {
    if (!store.clearExpiredPhotoOverlay()) {
      return false;
    }

    clearPhotoOverlayTimer();

    if (broadcast) {
      broadcastState();
    }

    return true;
  }

  function schedulePhotoOverlayExpiry() {
    clearPhotoOverlayTimer();

    const photo = store.getPhotoOverlay();
    if (!photo) {
      return;
    }

    const delayMs = Math.max(0, new Date(photo.expiresAt).getTime() - Date.now());
    photoOverlayTimer = setTimeout(() => {
      photoOverlayTimer = null;
      if (store.clearExpiredPhotoOverlay()) {
        broadcastState();
      }
    }, delayMs + 25);
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

    if (pathname.startsWith("/api/setup/")) {
      if (await handleSetup(request, response, pathname)) {
        return;
      }
    }

    if (pathname.startsWith("/api/") && !requireAuth(request, response)) {
      return;
    }

    if (request.method === "GET" && pathname === "/api/diagnostics") {
      const state = store.getState();
      const memory = process.memoryUsage();

      json(response, 200, {
        status: "ok",
        version,
        uptimeSeconds: Math.round(process.uptime()),
        socketCount: sockets.size,
        staticCacheEntries: staticFileCache.size,
        memory: {
          rss: memory.rss,
          heapUsed: memory.heapUsed,
          heapTotal: memory.heapTotal,
        },
        mirror: {
          displayState: state.displayState,
          displayMode: state.displayMode,
          layoutEditMode: state.layoutEditMode,
          moduleCount: state.modules.length,
          visibleModuleCount: state.modules.filter((module) => module.visible).length,
          photoOverlayActive: Boolean(state.photoOverlay),
        },
        pairing: pairingStatus,
      });
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

    expirePhotoOverlayIfNeeded({ broadcast: true });

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

    if (request.method === "POST" && pathname === "/api/mirror/layout/edit") {
      const body = await readJsonBody(request);

      try {
        const state = store.setLayoutEditMode(body.active);
        json(response, 200, state);
        broadcastState();
      } catch (error) {
        json(response, 400, {
          error: error.message,
          message: "Active must be a boolean value.",
        });
      }
      return;
    }

    if (request.method === "POST" && pathname === "/api/mirror/photo") {
      try {
        const body = await readJsonBody(request, { maxBytes: PHOTO_BODY_LIMIT_BYTES });
        const state = store.setPhotoOverlay(parsePhotoPayload(body));
        schedulePhotoOverlayExpiry();
        json(response, 200, state);
        broadcastState();
      } catch (error) {
        if (error.message === "PHOTO_TOO_LARGE") {
          json(response, 413, {
            error: error.message,
            message: `Photo must be ${formatBytes(PHOTO_DATA_LIMIT_BYTES)} or smaller after processing.`,
          });
          return;
        }

        if (
          [
            "INVALID_PHOTO_DATA",
            "INVALID_PHOTO_TYPE",
            "INVALID_PHOTO_DURATION",
          ].includes(error.message)
        ) {
          json(response, 400, {
            error: error.message,
            message: "Photo must be a JPEG, PNG, or WebP data URL. Duration must be 1 to 3600 seconds.",
          });
          return;
        }

        throw error;
      }
      return;
    }

    if (request.method === "DELETE" && pathname === "/api/mirror/photo") {
      clearPhotoOverlayTimer();
      const state = store.clearPhotoOverlay();
      json(response, 200, state);
      broadcastState();
      return;
    }

    if (request.method === "GET" && pathname === "/api/mirror/photo/current") {
      const photo = store.getPhotoOverlay();

      if (!photo) {
        json(response, 404, {
          error: "PHOTO_NOT_FOUND",
          message: "No temporary photo is active.",
        });
        return;
      }

      binary(response, 200, photo.data, photo.mimeType);
      return;
    }

    if (request.method === "POST" && pathname === "/api/modules/layout") {
      const body = await readJsonBody(request);

      try {
        const state = await store.setModuleLayout(body.modules);
        json(response, 200, state);
        broadcastState();
      } catch (error) {
        const statusCode = error.message === "MODULE_NOT_FOUND" ? 404 : 400;
        json(response, statusCode, {
          error: error.message,
          message:
            error.message === "MODULE_NOT_FOUND"
              ? "Module was not found."
              : "Layout coordinates and size must keep every widget inside the screen.",
        });
      }
      return;
    }

    if (request.method === "POST" && pathname === "/api/modules/layout/reset") {
      const state = await store.resetModuleLayout();
      json(response, 200, state);
      broadcastState();
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
          message: `Request body must be ${formatBytes(error.limitBytes ?? JSON_BODY_LIMIT_BYTES)} or smaller.`,
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

    socket.setNoDelay?.(true);
    socket.setKeepAlive?.(true, 30000);
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
      clearPhotoOverlayTimer();

      for (const socket of sockets) {
        socket.destroy();
      }

      sockets.clear();
    },
    handleRequest,
    handleUpgrade,
  };
}
