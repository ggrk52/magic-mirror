import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { createApp } from "./http.js";
import { createMdnsAdvertiser } from "./discovery.js";
import { createMarketService } from "./markets.js";
import { createTassNewsService } from "./news.js";
import { createSetupService } from "./setup.js";
import { MirrorStore, createFileStateStorage } from "./state.js";
import { createFileLayoutStorage } from "./layout.js";

const VERSION = "1.0.0";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const publicDir = join(__dirname, "..", "public");
const layoutFile = join(__dirname, "..", "data", "layout.json");
const stateFile = join(__dirname, "..", "data", "state.json");

export async function startServer({
  port = Number(process.env.MIRROR_PORT ?? 8080),
  host = process.env.MIRROR_HOST ?? "0.0.0.0",
  token = process.env.MIRROR_TOKEN ?? "magic-mirror-local-token",
  store,
  layoutStorage = createFileLayoutStorage(layoutFile),
  stateStorage = createFileStateStorage(stateFile),
  marketService = createMarketService(),
  newsService = createTassNewsService(),
  mdnsPublisher = createMdnsAdvertiser,
  setupMode = process.env.MIRROR_SETUP_MODE === "1",
  setupService,
  enablePolling = false,
} = {}) {
  const mirrorStore = store ?? new MirrorStore(undefined, {
    initialLayout: await layoutStorage.load(),
    initialState: await stateStorage.load(),
    layoutStorage,
    stateStorage,
  });
  let activeToken = token;
  const getToken = () => activeToken;
  const setToken = (nextToken) => {
    activeToken = nextToken;
  };
  const setup = setupService ?? createSetupService({ enabled: setupMode, getToken, setToken });

  const app = createApp({
    store: mirrorStore,
    publicDir,
    token: getToken,
    version: VERSION,
    marketService,
    newsService,
    setupService: setup,
  });

  const server = createServer(app.handleRequest);
  server.on("upgrade", app.handleUpgrade);

  await new Promise((resolve) => {
    server.listen(port, host, resolve);
  });

  const address = server.address();
  const actualPort = typeof address === "object" ? address.port : port;

  if (enablePolling) {
    marketService.startPolling?.();
    newsService.startPolling?.();
  }

  await setup.startAccessPoint?.().catch((error) => {
    console.warn(`Setup access point was not started: ${error.publicMessage ?? error.message}`);
  });
  const mdns = await mdnsPublisher({
    port: actualPort,
    version: VERSION,
    setupMode: setup.enabled,
  });

  return {
    server,
    store: mirrorStore,
    get token() {
      return getToken();
    },
    address,
    mdns,
    close: async () => {
      await setup.stop?.();
      await mdns.stop();
      marketService.stop?.();
      newsService.stop?.();

      return new Promise((resolve, reject) => {
        app.closeSockets();
        server.close((error) => {
          if (error) {
            reject(error);
            return;
          }
          resolve();
        });
      });
    },
  };
}
