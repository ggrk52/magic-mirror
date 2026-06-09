import defaultModules from "../config/defaultModules.json" with { type: "json" };
import {
  defaultModuleLayout,
  layoutMapFromModules,
  safeModuleLayout,
  validateModulePosition,
} from "./layout.js";

function nowIso() {
  return new Date().toISOString();
}

function photoMeta(photoOverlay) {
  if (!photoOverlay) {
    return null;
  }

  return {
    id: photoOverlay.id,
    mimeType: photoOverlay.mimeType,
    sizeBytes: photoOverlay.data.length,
    uploadedAt: photoOverlay.uploadedAt,
    expiresAt: photoOverlay.expiresAt,
    durationSeconds: photoOverlay.durationSeconds,
  };
}

function cloneLayout(layout) {
  return {
    x: layout.x,
    y: layout.y,
    w: layout.w,
    h: layout.h,
  };
}

function cloneModule(module, initialLayout) {
  return {
    id: module.id,
    title: module.title,
    visible: Boolean(module.visible),
    refreshable: Boolean(module.refreshable),
    lastUpdatedAt: module.lastUpdatedAt ?? nowIso(),
    layout: safeModuleLayout(module.id, initialLayout?.[module.id]),
  };
}

export class MirrorStore {
  constructor(initialModules = defaultModules, { initialLayout = {}, layoutStorage = null } = {}) {
    this.displayState = "on";
    this.displayMode = "mirror";
    this.layoutEditMode = false;
    this.lastReloadedAt = null;
    this.modules = initialModules.map((module) => cloneModule(module, initialLayout));
    this.photoOverlay = null;
    this.layoutStorage = layoutStorage;
  }

  getState() {
    return {
      displayState: this.displayState,
      displayMode: this.displayMode,
      layoutEditMode: this.layoutEditMode,
      lastReloadedAt: this.lastReloadedAt,
      photoOverlay: photoMeta(this.photoOverlay),
      modules: this.modules.map((module) => ({
        ...module,
        layout: cloneLayout(module.layout),
      })),
    };
  }

  getModule(moduleId) {
    return this.modules.find((module) => module.id === moduleId) ?? null;
  }

  setDisplayAction(action) {
    if (!["on", "off", "reload"].includes(action)) {
      throw new Error("INVALID_DISPLAY_ACTION");
    }

    if (action === "reload") {
      this.lastReloadedAt = nowIso();
      return this.getState();
    }

    this.displayState = action;
    return this.getState();
  }

  setDisplayMode(mode) {
    if (!["mirror", "gallery", "ar"].includes(mode)) {
      throw new Error("INVALID_DISPLAY_MODE");
    }

    this.photoOverlay = null;
    this.displayMode = mode;
    return this.getState();
  }

  setLayoutEditMode(active) {
    if (typeof active !== "boolean") {
      throw new Error("INVALID_LAYOUT_EDIT_MODE");
    }

    this.layoutEditMode = active;
    return this.getState();
  }

  async saveLayout() {
    if (!this.layoutStorage) {
      return;
    }

    await this.layoutStorage.save(layoutMapFromModules(this.modules));
  }

  async setModuleLayout(updates) {
    if (!Array.isArray(updates)) {
      throw new Error("INVALID_LAYOUT");
    }

    const nextLayouts = new Map();

    for (const update of updates) {
      const module = this.getModule(update?.id);
      if (!module) {
        throw new Error("MODULE_NOT_FOUND");
      }

      nextLayouts.set(
        module.id,
        validateModulePosition(module.id, update, module.layout),
      );
    }

    for (const [moduleId, layout] of nextLayouts) {
      this.getModule(moduleId).layout = layout;
    }

    await this.saveLayout();
    return this.getState();
  }

  async resetModuleLayout() {
    for (const module of this.modules) {
      module.layout = defaultModuleLayout(module.id);
    }

    await this.saveLayout();
    return this.getState();
  }

  setPhotoOverlay({ data, mimeType, durationSeconds }) {
    const uploadedAt = new Date();
    const expiresAt = new Date(uploadedAt.getTime() + durationSeconds * 1000);

    this.displayState = "on";
    this.displayMode = "mirror";
    this.photoOverlay = {
      id: `${uploadedAt.getTime().toString(36)}-${Math.random().toString(36).slice(2, 10)}`,
      data,
      mimeType,
      uploadedAt: uploadedAt.toISOString(),
      expiresAt: expiresAt.toISOString(),
      durationSeconds,
    };

    return this.getState();
  }

  getPhotoOverlay() {
    if (!this.photoOverlay) {
      return null;
    }

    return {
      ...photoMeta(this.photoOverlay),
      data: this.photoOverlay.data,
    };
  }

  clearPhotoOverlay() {
    this.photoOverlay = null;
    return this.getState();
  }

  clearExpiredPhotoOverlay(now = Date.now()) {
    if (!this.photoOverlay) {
      return false;
    }

    if (new Date(this.photoOverlay.expiresAt).getTime() > now) {
      return false;
    }

    this.photoOverlay = null;
    return true;
  }

  setModuleVisibility(moduleId, visible) {
    const module = this.getModule(moduleId);

    if (!module) {
      throw new Error("MODULE_NOT_FOUND");
    }

    if (typeof visible !== "boolean") {
      throw new Error("INVALID_VISIBILITY");
    }

    module.visible = visible;
    module.lastUpdatedAt = nowIso();

    return this.getState();
  }

  refreshModule(moduleId) {
    const module = this.getModule(moduleId);

    if (!module) {
      throw new Error("MODULE_NOT_FOUND");
    }

    if (module.refreshable) {
      module.lastUpdatedAt = nowIso();
    }

    return this.getState();
  }

  refreshAll() {
    const updatedAt = nowIso();

    for (const module of this.modules) {
      if (module.refreshable) {
        module.lastUpdatedAt = updatedAt;
      }
    }

    return this.getState();
  }
}
