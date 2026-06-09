import { exec } from "node:child_process";
import { promisify } from "node:util";
import { readFile } from "node:fs/promises";

const execAsync = promisify(exec);

export class HardwareManager {
  constructor(store, options = {}) {
    this.store = store;
    this.options = options;
    this.isLinux = process.platform === "linux";
  }

  async init() {
    if (!this.isLinux) {
      console.warn("HardwareManager: Hardware control is only supported on Linux.");
      return;
    }

    // Subscribe to store events
    this.store.on("displayStateChanged", (state) => this.handleDisplayStateChange(state));
    this.store.on("displayModeChanged", (mode) => this.handleDisplayModeChange(mode));

    console.log("HardwareManager: Initialized and listening for store events.");
  }

  async handleDisplayStateChange(state) {
    console.log(`HardwareManager: Display state changed to ${state}`);
    try {
      if (state === "off") {
        await this.setScreenPower(false);
      } else if (state === "on") {
        await this.setScreenPower(true);
      }
    } catch (error) {
      console.error(`HardwareManager: Failed to change screen power: ${error.message}`);
    }
  }

  async handleDisplayModeChange(mode) {
    console.log(`HardwareManager: Display mode changed to ${mode}`);
    // Future: handle specific hardware configs for different modes (e.g. brightness)
  }

  async setScreenPower(on) {
    if (!this.isLinux) return;

    const cmd = on 
      ? "xset dpms force on" 
      : "xset dpms force off";

    try {
      // We try xset first (X11). If it fails, we could fallback to sysfs/fbset.
      await execAsync(cmd);
    } catch (error) {
      // Fallback for non-X11 environments (Framebuffer)
      try {
        const fbCmd = on 
          ? "echo 0 > /sys/class/graphics/fb0/blank" 
          : "echo 1 > /sys/class/graphics/fb0/blank";
        await execAsync(`sudo ${fbCmd}`);
      } catch (fbError) {
        throw new Error(`Screen control failed: ${error.message} | FB fallback: ${fbError.message}`);
      }
    }
  }

  async getTemperature() {
    if (!this.isLinux) return null;

    try {
      // Allwinner usually has thermal zones in /sys/class/thermal/
      // We search for the first available thermal zone
      const temp = await readFile("/sys/class/thermal/thermal_zone0/temp", "utf8");
      return parseFloat(temp) / 1000; // Convert millidegrees to degrees
    } catch (error) {
      console.error(`HardwareManager: Could not read temperature: ${error.message}`);
      return null;
    }
  }

  async setGpioValue(pin, value) {
    if (!this.isLinux) return;

    try {
      // Using gpiod (standard for Allwinner/Rockchip)
      // gpioset <chip> <offset>=<value>
      await execAsync(`gpioset gpiochip1 ${pin}=${value ? 1 : 0}`);
    } catch (error) {
      throw new Error(`GPIO error on pin ${pin}: ${error.message}`);
    }
  }

  async getGpioValue(pin) {
    if (!this.isLinux) return null;

    try {
      const { stdout } = await execAsync(`gpioget gpiochip1 ${pin}`);
      return stdout.trim() === "1";
    } catch (error) {
      throw new Error(`GPIO read error on pin ${pin}: ${error.message}`);
    }
  }
}

export function createHardwareManager(store) {
  return new HardwareManager(store);
}
