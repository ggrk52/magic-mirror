import { networkInterfaces } from "node:os";

import { startServer } from "./server.js";

const runtime = await startServer();

const host = typeof runtime.address === "object" ? runtime.address.address : "127.0.0.1";
const port = typeof runtime.address === "object" ? runtime.address.port : process.env.MIRROR_PORT;
const lanUrls = Object.values(networkInterfaces())
  .flat()
  .filter((address) => address && address.family === "IPv4" && !address.internal)
  .map((address) => `http://${address.address}:${port}/`);

console.log(`Magic mirror LAN server is running at http://${host}:${port}`);
console.log(`Mirror UI on this device: http://127.0.0.1:${port}/`);

if ((host === "0.0.0.0" || host === "::") && lanUrls.length > 0) {
  for (const url of lanUrls) {
    console.log(`LAN server address: ${url}`);
  }
} else {
  console.log(`Mirror UI: http://${host}:${port}/`);
}

console.log("Use MIRROR_TOKEN to override the default bearer token.");
