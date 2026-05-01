import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export class SetupError extends Error {
  constructor(error, message, statusCode = 400) {
    super(error);
    this.statusCode = statusCode;
    this.publicMessage = message;
  }
}

function setupDisabled() {
  return new SetupError(
    "SETUP_MODE_DISABLED",
    "Setup mode is disabled. Start the server with MIRROR_SETUP_MODE=1.",
    404,
  );
}

function assertSetupEnabled(enabled) {
  if (!enabled) {
    throw setupDisabled();
  }
}

function normalizeWifiBody(body) {
  const ssid = String(body.ssid ?? "").trim();
  const password = String(body.password ?? "");

  if (!ssid) {
    throw new SetupError("SSID_REQUIRED", "Wi-Fi SSID is required.");
  }

  return { ssid, password };
}

export function createSetupService({
  enabled = process.env.MIRROR_SETUP_MODE === "1",
  autoStartAccessPoint = process.env.MIRROR_SETUP_AP === "1",
  accessPointSsid = process.env.MIRROR_SETUP_AP_SSID ?? `MagicMirror-Setup-${process.env.MIRROR_INSTANCE_ID?.slice(0, 4) ?? "LAN"}`,
  accessPointPassword = process.env.MIRROR_SETUP_AP_PASSWORD ?? "magicmirror",
  accessPointInterface = process.env.MIRROR_SETUP_AP_IFACE,
  accessPointConnectionName = "MagicMirror-Setup",
  platform = process.platform,
  commandRunner = execFileAsync,
  setToken = () => {},
  getToken = () => "",
} = {}) {
  const supported = platform === "linux";
  let accessPointStarted = false;

  async function hasNmcli() {
    if (!supported) {
      return false;
    }

    try {
      await commandRunner("nmcli", ["--version"]);
      return true;
    } catch (error) {
      return false;
    }
  }

  return {
    enabled,
    supported,

    async status() {
      return {
        enabled,
        supported,
        platform,
        mode: enabled ? "setup" : "normal",
        networkManagerAvailable: await hasNmcli(),
        accessPoint: {
          requested: enabled && autoStartAccessPoint,
          started: accessPointStarted,
          ssid: accessPointSsid,
        },
        tokenConfigured: Boolean(getToken()),
      };
    },

    async startAccessPoint() {
      if (!enabled || !autoStartAccessPoint || !supported) {
        return {
          started: false,
          ssid: accessPointSsid,
        };
      }

      const args = ["device", "wifi", "hotspot"];

      if (accessPointInterface) {
        args.push("ifname", accessPointInterface);
      }

      args.push(
        "con-name",
        accessPointConnectionName,
        "ssid",
        accessPointSsid,
        "password",
        accessPointPassword,
      );

      try {
        await commandRunner("nmcli", args);
        accessPointStarted = true;
      } catch (error) {
        throw new SetupError(
          "SETUP_AP_FAILED",
          "Failed to start setup access point with nmcli. Check Wi-Fi adapter and NetworkManager permissions.",
          500,
        );
      }

      return {
        started: true,
        ssid: accessPointSsid,
      };
    },

    async stop() {
      if (!accessPointStarted) {
        return;
      }

      await commandRunner("nmcli", ["connection", "down", accessPointConnectionName]).catch(() => {});
      accessPointStarted = false;
    },

    async applyWifi(body) {
      assertSetupEnabled(enabled);

      if (!supported) {
        throw new SetupError(
          "SETUP_PLATFORM_UNSUPPORTED",
          "Wi-Fi provisioning is only supported on Raspberry/Linux with NetworkManager.",
          400,
        );
      }

      const { ssid, password } = normalizeWifiBody(body);
      const args = ["device", "wifi", "connect", ssid];

      if (password) {
        args.push("password", password);
      }

      try {
        await commandRunner("nmcli", args);
      } catch (error) {
        throw new SetupError(
          "WIFI_PROVISIONING_FAILED",
          "Failed to apply Wi-Fi settings with nmcli. Check NetworkManager permissions and the SSID/password.",
          500,
        );
      }

      return {
        status: "ok",
        message: "Wi-Fi settings were sent to NetworkManager.",
      };
    },

    async updateToken(body) {
      assertSetupEnabled(enabled);

      const nextToken = String(body.token ?? "").trim();
      if (nextToken.length < 8) {
        throw new SetupError(
          "TOKEN_TOO_SHORT",
          "Token must contain at least 8 characters.",
        );
      }

      setToken(nextToken);

      return {
        status: "ok",
        persisted: false,
        message: "Token was updated for the current server process. Set MIRROR_TOKEN before restart to keep it permanently.",
      };
    },
  };
}
