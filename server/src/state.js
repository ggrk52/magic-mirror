import defaultModules from "../config/defaultModules.json" with { type: "json" };

function nowIso() {
  return new Date().toISOString();
}

function cloneModule(module) {
  return {
    id: module.id,
    title: module.title,
    visible: Boolean(module.visible),
    refreshable: Boolean(module.refreshable),
    lastUpdatedAt: module.lastUpdatedAt ?? nowIso(),
  };
}

export class MirrorStore {
  constructor(initialModules = defaultModules) {
    this.displayState = "on";
    this.displayMode = "mirror";
    this.lastReloadedAt = null;
    this.modules = initialModules.map(cloneModule);
  }

  getState() {
    return {
      displayState: this.displayState,
      displayMode: this.displayMode,
      lastReloadedAt: this.lastReloadedAt,
      modules: this.modules.map((module) => ({ ...module })),
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

    this.displayMode = mode;
    return this.getState();
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
