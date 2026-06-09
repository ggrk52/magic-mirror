import { startClock } from "./components/clock.js";
import { startWeatherPolling } from "./components/weather.js";
import { startMarketsPolling, renderMarkets } from "./components/markets.js";
import { startNewsPolling, renderNews, rotateNewsHeadline } from "./components/news.js";
import { initWs } from "./services/ws.js";

// DOM Elements
const mirrorRoot = document.querySelector("#mirrorRoot");
const photoScreen = document.querySelector("#photoScreen");
const photoImage = document.querySelector("#photoImage");
const photoTimer = document.querySelector("#photoTimer");
const galleryImage = document.querySelector("#galleryImage");
const galleryTitle = document.querySelector("#galleryTitle");
const galleryArtist = document.querySelector("#galleryArtist");
const connectionState = document.querySelector("#connectionState");
const mirrorMeta = document.querySelector("#mirrorMeta");
const pairingPanel = document.querySelector("#pairingPanel");
const pairingQr = document.querySelector("#pairingQr");
const pairingHint = document.querySelector("#pairingHint");
const displayBlackout = document.querySelector("#displayBlackout");

// Configurations
const wsToken = window.__MIRROR_CONFIG__?.wsToken ?? "";

const artworks = [
  { title: "The Bedroom", artist: "Винсент ван Гог", file: "bedroom.jpg" },
  { title: "Self-Portrait", artist: "Винсент ван Гог", file: "self-portrait.jpg" },
  { title: "Water Lilies", artist: "Клод Моне", file: "water-lilies.jpg" },
  { title: "Arrival of the Normandy Train", artist: "Клод Моне", file: "normandy-train.jpg" },
  { title: "Two Sisters (On the Terrace)", artist: "Пьер-Огюст Ренуар", file: "two-sisters.jpg" },
  { title: "Woman at the Piano", artist: "Пьер-Огюст Ренуар", file: "woman-piano.jpg" },
  { title: "The Basket of Apples", artist: "Поль Сезанн", file: "basket-apples.jpg" },
  { title: "The Bay of Marseille", artist: "Поль Сезанн", file: "marseille-bay.jpg" },
];

let currentArtworkIndex = -1;
let currentPhonePhotoId = null;
let currentPhonePhotoExpiresAt = null;
let phonePhotoObjectUrl = null;

// Module Fitting Logic
let moduleFitFrame = 0;
const pendingModuleFitIds = new Set();
const moduleWidthFit = {
  clock: { factor: 0.026, min: 10, max: 34 },
  weather: { factor: 0.024, min: 9, max: 28 },
  calendar: { factor: 0.026, min: 9, max: 24 },
  markets: { factor: 0.025, min: 9, max: 23 },
  news: { factor: 0.032, min: 10, max: 32 },
};

const moduleResizeObserver =
  "ResizeObserver" in window
    ? new ResizeObserver((entries) => {
        for (const entry of entries) {
          const moduleId = entry.target.dataset.moduleId;
          if (moduleId) {
            scheduleModuleFit(moduleId);
          }
        }
      })
    : null;

function fitModuleContent(element) {
  const content = element.querySelector(".module-content");
  const boxWidth = element.clientWidth;

  if (!content || boxWidth <= 0) {
    return;
  }

  const rule = moduleWidthFit[element.dataset.moduleId] ?? { factor: 0.026, min: 9, max: 26 };
  let size = Math.min(Math.max(boxWidth * rule.factor, rule.min), rule.max);
  setModuleFitSize(content, size);

  for (let index = 0; index < 6 && content.scrollWidth > boxWidth + 1; index += 1) {
    size = Math.max(rule.min, size * 0.9);
    setModuleFitSize(content, size);
  }
}

function setModuleFitSize(content, size) {
  const nextSize = `${size.toFixed(2)}px`;

  if (content.dataset.fitSize === nextSize) {
    return;
  }

  content.dataset.fitSize = nextSize;
  content.style.setProperty("--module-fit-size", nextSize);
}

function flushModuleFits() {
  moduleFitFrame = 0;

  for (const moduleId of pendingModuleFitIds) {
    const element = document.querySelector(`[data-module-id="${moduleId}"]`);
    if (element) {
      fitModuleContent(element);
    }
  }

  pendingModuleFitIds.clear();
}

function scheduleModuleFit(moduleId) {
  pendingModuleFitIds.add(moduleId);

  if (!moduleFitFrame) {
    moduleFitFrame = window.requestAnimationFrame(flushModuleFits);
  }
}

function scheduleAllModuleFits() {
  for (const element of document.querySelectorAll("[data-module-id]")) {
    scheduleModuleFit(element.dataset.moduleId);
  }
}

function observeModuleFrames() {
  if (!moduleResizeObserver) {
    return;
  }

  for (const element of document.querySelectorAll("[data-module-id]")) {
    moduleResizeObserver.observe(element);
  }
}

// Artwork Display — local offline gallery with crossfade
const galleryImageB = document.querySelector("#galleryImageB");
let galleryActiveSlot = "a";

function showRandomArtwork() {
  if (artworks.length === 0) {
    return;
  }

  let nextIndex = Math.floor(Math.random() * artworks.length);
  if (artworks.length > 1 && nextIndex === currentArtworkIndex) {
    nextIndex = (nextIndex + 1) % artworks.length;
  }

  currentArtworkIndex = nextIndex;
  const artwork = artworks[currentArtworkIndex];
  const src = `/gallery/${artwork.file}`;
  const alt = `${artwork.title}, ${artwork.artist}`;

  // Crossfade: load into the hidden slot, then swap
  if (galleryImageB) {
    const incoming = galleryActiveSlot === "a" ? galleryImageB : galleryImage;
    const outgoing = galleryActiveSlot === "a" ? galleryImage : galleryImageB;

    incoming.src = src;
    incoming.alt = alt;
    incoming.classList.add("gallery-img-active");
    outgoing.classList.remove("gallery-img-active");
    galleryActiveSlot = galleryActiveSlot === "a" ? "b" : "a";
  } else {
    galleryImage.src = src;
    galleryImage.alt = alt;
  }

  galleryTitle.textContent = artwork.title;
  galleryArtist.textContent = artwork.artist;
}

// Phone Photo Handling
function clearPhonePhoto() {
  currentPhonePhotoId = null;
  currentPhonePhotoExpiresAt = null;

  if (phonePhotoObjectUrl) {
    URL.revokeObjectURL(phonePhotoObjectUrl);
    phonePhotoObjectUrl = null;
  }

  if (photoImage) {
    photoImage.removeAttribute("src");
  }
}

function formatPhotoRemaining(expiresAt) {
  const remainingSeconds = Math.max(0, Math.ceil((new Date(expiresAt).getTime() - Date.now()) / 1000));

  if (remainingSeconds >= 60) {
    const minutes = Math.ceil(remainingSeconds / 60);
    return `${minutes} мин`;
  }

  return `${remainingSeconds} сек`;
}

async function loadPhonePhoto(photoOverlay) {
  if (!photoOverlay || !photoImage || currentPhonePhotoId === photoOverlay.id) {
    return;
  }

  currentPhonePhotoId = photoOverlay.id;
  photoTimer.textContent = "загрузка";

  try {
    const response = await fetch(`/api/mirror/photo/current?id=${encodeURIComponent(photoOverlay.id)}`, {
      headers: {
        Authorization: `Bearer ${wsToken}`,
      },
      cache: "no-store",
    });

    if (!response.ok) {
      throw new Error("Photo request failed");
    }

    const objectUrl = URL.createObjectURL(await response.blob());
    if (phonePhotoObjectUrl) {
      URL.revokeObjectURL(phonePhotoObjectUrl);
    }

    phonePhotoObjectUrl = objectUrl;
    photoImage.src = objectUrl;
  } catch (error) {
    clearPhonePhoto();
    if (photoTimer) {
      photoTimer.textContent = "фото недоступно";
    }
  }
}

function renderPhonePhoto(photoOverlay) {
  const hasPhoto = Boolean(photoOverlay);
  mirrorRoot.classList.toggle("photo-mode", hasPhoto);

  if (!hasPhoto) {
    clearPhonePhoto();
    return;
  }

  if (photoTimer) {
    currentPhonePhotoExpiresAt = photoOverlay.expiresAt;
    photoTimer.textContent = formatPhotoRemaining(photoOverlay.expiresAt);
  }

  loadPhonePhoto(photoOverlay);
}

function formatMetaTime(value) {
  if (!value) {
    return "Без перезагрузок";
  }

  return `Перезагрузка ${new Intl.DateTimeFormat("ru-RU", {
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value))}`;
}

// Rendering state changes
function setModuleVisibility(state) {
  const modulesById = new Map(state.modules.map((module) => [module.id, module]));

  for (const element of document.querySelectorAll("[data-module-id]")) {
    const module = modulesById.get(element.dataset.moduleId);
    element.classList.toggle("module-hidden", module?.visible === false);
  }
}

function setModuleLayout(state) {
  const modulesById = new Map(state.modules.map((module) => [module.id, module]));

  mirrorRoot.classList.toggle("layout-edit-mode", Boolean(state.layoutEditMode));

  for (const element of document.querySelectorAll("[data-module-id]")) {
    const module = modulesById.get(element.dataset.moduleId);
    const layout = module?.layout;

    if (!layout) {
      continue;
    }

    element.style.setProperty("--layout-x", layout.x);
    element.style.setProperty("--layout-y", layout.y);
    element.style.setProperty("--layout-w", layout.w);
    element.style.setProperty("--layout-h", layout.h);
    element.dataset.layoutLabel = module.title;
    element.classList.toggle("align-right", layout.x >= 48);
    element.classList.toggle("align-center", layout.w >= 70 || module.id === "news");
    scheduleModuleFit(module.id);
  }
}

// Notification toast
const toastContainer = document.querySelector("#toastContainer");
let activeToastTimer = null;

function renderNotification(notification) {
  if (!toastContainer) return;

  if (!notification) {
    toastContainer.classList.remove("toast-visible");
    if (activeToastTimer) { clearTimeout(activeToastTimer); activeToastTimer = null; }
    return;
  }

  const toastText = toastContainer.querySelector(".toast-text");
  if (toastText) toastText.textContent = notification.text ?? "";
  toastContainer.classList.add("toast-visible");

  if (activeToastTimer) clearTimeout(activeToastTimer);
  const duration = (notification.durationSeconds ?? 15) * 1000;
  activeToastTimer = setTimeout(() => {
    toastContainer.classList.remove("toast-visible");
    activeToastTimer = null;
  }, duration);
}

function renderState(state) {
  const displayMode = state.displayMode ?? "mirror";
  const photoOverlay = state.photoOverlay ?? null;
  const displayOff = state.displayState === "off";

  mirrorRoot.classList.toggle("display-off", displayOff);
  document.documentElement.classList.toggle("display-off", displayOff);
  document.body.classList.toggle("display-off", displayOff);
  if (displayBlackout) {
    displayBlackout.hidden = !displayOff;
  }
  mirrorRoot.classList.toggle("gallery-mode", displayMode === "gallery");
  renderPhonePhoto(photoOverlay);
  setModuleLayout(state);
  setModuleVisibility(state);

  // Notification toast
  renderNotification(state.notification ?? null);

  if (!photoOverlay && displayMode === "gallery" && currentArtworkIndex === -1) {
    showRandomArtwork();
  }

  const visibleCount = state.modules.filter((module) => module.visible).length;
  const modeLabel =
    photoOverlay
      ? "фото с телефона"
      : displayMode === "gallery"
      ? "экран ожидания"
      : "зеркало";
  mirrorMeta.textContent = `${modeLabel} - видно модулей: ${visibleCount}/${state.modules.length} - ${formatMetaTime(
    state.lastReloadedAt,
  )}`;
}

function setConnection(label, connected = false) {
  connectionState.textContent = label;
  connectionState.classList.toggle("connected", connected);
}

function renderPairingStatus(status) {
  if (!pairingPanel || !wsToken) {
    return;
  }

  pairingPanel.hidden = Boolean(status?.controllerConnected);
}

async function loadPairingQr() {
  if (!wsToken || !pairingPanel || !pairingQr) {
    return;
  }

  try {
    const statusResponse = await fetch("/api/pairing/status", {
      headers: {
        Authorization: `Bearer ${wsToken}`,
      },
      cache: "no-store",
    });

    if (statusResponse.ok) {
      const status = await statusResponse.json();
      renderPairingStatus(status);

      if (status.controllerConnected) {
        return;
      }
    }

    pairingPanel.hidden = false;
    const response = await fetch("/api/pairing/qr.svg", { cache: "no-store" });

    if (!response.ok) {
      throw new Error(`QR endpoint returned ${response.status}`);
    }

    pairingQr.classList.remove("error");
    pairingQr.innerHTML = await response.text();
    pairingHint.textContent = "Открой приложение и нажми «Сканировать QR»";
  } catch (error) {
    pairingQr.classList.add("error");
    pairingQr.textContent = "QR недоступен. Перезапусти сервер после обновления кода.";
    pairingHint.textContent = "После перезапуска обнови страницу зеркала.";
  }
}

// Initializations
pairingPanel.hidden = true;
loadPairingQr();
showRandomArtwork();

observeModuleFrames();
scheduleAllModuleFits();

window.addEventListener("resize", scheduleAllModuleFits);

window.setInterval(() => {
  if (currentPhonePhotoExpiresAt && photoTimer) {
    photoTimer.textContent = formatPhotoRemaining(currentPhonePhotoExpiresAt);
  }
}, 1000);

window.setInterval(() => {
  if (mirrorRoot.classList.contains("gallery-mode")) {
    showRandomArtwork();
  }
}, 45 * 1000);

// Start Polls & Component loops
startClock();
startWeatherPolling(() => scheduleModuleFit("weather"));
startMarketsPolling(wsToken, () => scheduleModuleFit("markets"));
startNewsPolling(wsToken, () => scheduleModuleFit("news"));

// Initialize WebSocket
initWs({
  wsToken,
  onStateChange: renderState,
  onPairingStatusChange: renderPairingStatus,
  onConnectionChange: setConnection,
});
