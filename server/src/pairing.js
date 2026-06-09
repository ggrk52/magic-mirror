import { networkInterfaces } from "node:os";

import QRCode from "qrcode";

export const PAIRING_TYPE = "magic-mirror-pair";

function hostPriority(host) {
  if (host.startsWith("192.168.")) {
    return 0;
  }

  if (host.startsWith("10.")) {
    return 1;
  }

  if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(host)) {
    return 2;
  }

  return 3;
}

export function getLanHosts() {
  return Object.values(networkInterfaces())
    .flat()
    .filter((address) => address && address.family === "IPv4" && !address.internal)
    .map((address) => address.address)
    .sort((left, right) => hostPriority(left) - hostPriority(right) || left.localeCompare(right));
}

export function buildPairingPayload({
  token,
  port,
  hosts = getLanHosts(),
  service,
} = {}) {
  return {
    type: PAIRING_TYPE,
    version: 1,
    token,
    port,
    hosts,
    service,
  };
}

export async function buildPairingQrSvg(payload) {
  return QRCode.toString(JSON.stringify(payload), {
    type: "svg",
    errorCorrectionLevel: "M",
    margin: 2,
    width: 420,
    color: {
      dark: "#000000ff",
      light: "#ffffffff",
    },
  });
}
