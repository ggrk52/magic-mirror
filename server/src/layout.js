import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

export const DEFAULT_MODULE_LAYOUTS = Object.freeze({
  clock: Object.freeze({ x: 6, y: 4, w: 52, h: 10 }),
  weather: Object.freeze({ x: 52, y: 6, w: 42, h: 14 }),
  calendar: Object.freeze({ x: 6, y: 18, w: 52, h: 26 }),
  markets: Object.freeze({ x: 52, y: 25, w: 42, h: 28 }),
  news: Object.freeze({ x: 8, y: 88, w: 84, h: 8 }),
});

const MIN_LAYOUT_WIDTH = 16;
const MIN_LAYOUT_HEIGHT = 5;

function roundPercent(value) {
  return Math.round(value * 100) / 100;
}

function finiteNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export function defaultModuleLayout(moduleId) {
  const fallback = DEFAULT_MODULE_LAYOUTS[moduleId] ?? { x: 6, y: 6, w: 42, h: 12 };
  return { ...fallback };
}

export function validateModulePosition(moduleId, candidate, baseLayout = defaultModuleLayout(moduleId)) {
  const x = finiteNumber(candidate?.x);
  const y = finiteNumber(candidate?.y);
  const w = finiteNumber(candidate?.w ?? baseLayout?.w);
  const h = finiteNumber(candidate?.h ?? baseLayout?.h);

  if (x === null || y === null || w === null || h === null || w <= 0 || h <= 0) {
    throw new Error("INVALID_LAYOUT");
  }

  if (w < MIN_LAYOUT_WIDTH || h < MIN_LAYOUT_HEIGHT || w > 100 || h > 100) {
    throw new Error("LAYOUT_SIZE_OUT_OF_BOUNDS");
  }

  if (x < 0 || y < 0 || x + w > 100 || y + h > 100) {
    throw new Error("LAYOUT_OUT_OF_BOUNDS");
  }

  return {
    x: roundPercent(x),
    y: roundPercent(y),
    w: roundPercent(w),
    h: roundPercent(h),
  };
}

export function safeModuleLayout(moduleId, candidate) {
  const fallback = defaultModuleLayout(moduleId);

  try {
    return validateModulePosition(moduleId, candidate, fallback);
  } catch (error) {
    return fallback;
  }
}

export function layoutMapFromModules(modules) {
  return Object.fromEntries(
    modules.map((module) => [
      module.id,
      {
        x: module.layout.x,
        y: module.layout.y,
        w: module.layout.w,
        h: module.layout.h,
      },
    ]),
  );
}

export function createFileLayoutStorage(filePath) {
  return {
    async load() {
      try {
        const payload = JSON.parse(await readFile(filePath, "utf8"));
        return payload?.modules && typeof payload.modules === "object" ? payload.modules : {};
      } catch (error) {
        return {};
      }
    },
    async save(layoutById) {
      await mkdir(dirname(filePath), { recursive: true });
      const body = JSON.stringify(
        {
          version: 1,
          modules: layoutById,
        },
        null,
        2,
      );
      const tempPath = `${filePath}.tmp`;
      await writeFile(tempPath, body, "utf8");
      await rename(tempPath, filePath);
    },
  };
}
