import { randomUUID } from "node:crypto";

import { Bonjour } from "bonjour-service";

export const MIRROR_SERVICE_TYPE = "magicmirror";
export const MIRROR_SERVICE_PROTOCOL = "tcp";

function safeTxtValue(value) {
  return String(value ?? "").slice(0, 255);
}

export function createMdnsAdvertiser({
  port,
  version,
  serviceName = process.env.MIRROR_SERVICE_NAME ?? "Magic Mirror",
  instanceId = process.env.MIRROR_INSTANCE_ID ?? randomUUID().slice(0, 12),
  setupMode = false,
  logger = console,
} = {}) {
  if (!port) {
    return {
      serviceName,
      instanceId,
      stop: async () => {},
    };
  }

  let bonjour;
  let service;

  try {
    bonjour = new Bonjour();
    service = bonjour.publish({
      name: serviceName,
      type: MIRROR_SERVICE_TYPE,
      protocol: MIRROR_SERVICE_PROTOCOL,
      port,
      txt: {
        api: "1",
        version: safeTxtValue(version),
        instance: safeTxtValue(instanceId),
        setup: setupMode ? "1" : "0",
      },
    });
  } catch (error) {
    logger.warn?.(`mDNS publication failed: ${error.message}`);
  }

  return {
    serviceName,
    instanceId,
    stop() {
      return new Promise((resolve) => {
        if (!bonjour || !service) {
          resolve();
          return;
        }

        service.stop(() => {
          bonjour.destroy();
          resolve();
        });
      });
    },
  };
}
