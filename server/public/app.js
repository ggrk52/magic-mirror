const mirrorRoot = document.querySelector("#mirrorRoot");
const galleryImage = document.querySelector("#galleryImage");
const galleryTitle = document.querySelector("#galleryTitle");
const galleryArtist = document.querySelector("#galleryArtist");
const arVideo = document.querySelector("#arVideo");
const arCanvas = document.querySelector("#arCanvas");
const arTracking = document.querySelector("#arTracking");
const arMessage = document.querySelector("#arMessage");
const arControls = document.querySelector("#arControls");
const connectionState = document.querySelector("#connectionState");
const mirrorMeta = document.querySelector("#mirrorMeta");
const dateLine = document.querySelector("#dateLine");
const timeMain = document.querySelector("#timeMain");
const timeSeconds = document.querySelector("#timeSeconds");
const timePeriod = document.querySelector("#timePeriod");
const weatherTemp = document.querySelector("#weatherTemp");
const weatherPlace = document.querySelector("#weatherPlace");
const forecastList = document.querySelector("#forecastList");
const fiatRates = document.querySelector("#fiatRates");
const cryptoRates = document.querySelector("#cryptoRates");
const newsHeadline = document.querySelector("#newsHeadline");
const pairingPanel = document.querySelector("#pairingPanel");
const pairingQr = document.querySelector("#pairingQr");
const pairingHint = document.querySelector("#pairingHint");
const wsToken = window.__MIRROR_CONFIG__?.wsToken ?? "";
const moscowWeatherUrl = new URL("https://api.open-meteo.com/v1/forecast");
const mediaPipeVersion = "0.10.22-rc.20250304";
const mediaPipeVisionUrl = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${mediaPipeVersion}/vision_bundle.mjs`;
const mediaPipeWasmUrl = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${mediaPipeVersion}/wasm`;
const poseModelUrl =
  "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task";

moscowWeatherUrl.search = new URLSearchParams({
  latitude: "55.7558",
  longitude: "37.6173",
  current: "temperature_2m,weather_code",
  daily: "weather_code,temperature_2m_max,temperature_2m_min",
  timezone: "Europe/Moscow",
  forecast_days: "5",
  temperature_unit: "celsius",
}).toString();

const headlines = [
  "ТАСС: загружаем свежие новости",
];

let headlineIndex = 0;
let newsItems = headlines;
let currentArtworkIndex = -1;
let arStream = null;
let arAnimationFrame = 0;
let arPoseLandmarker = null;
let arPoseInitPromise = null;
let arLastVideoTime = -1;
let arLastPoseTimestamp = 0;
let arSmoothedBody = null;
let activeGarment = "hoodie";

const poseLandmarks = {
  leftShoulder: 11,
  rightShoulder: 12,
  leftElbow: 13,
  rightElbow: 14,
  leftWrist: 15,
  rightWrist: 16,
  leftHip: 23,
  rightHip: 24,
};

const garmentStyles = {
  hoodie: {
    label: "Худи",
    fill: "rgba(44, 71, 88, 0.82)",
    sleeve: "rgba(42, 68, 86, 0.76)",
    accent: "rgba(207, 236, 255, 0.8)",
    shadow: "rgba(5, 12, 18, 0.48)",
    length: 1.08,
    sleeveWidth: 0.3,
    hood: true,
    pocket: true,
  },
  sweatshirt: {
    label: "Свитшот",
    fill: "rgba(95, 103, 113, 0.78)",
    sleeve: "rgba(82, 91, 102, 0.74)",
    accent: "rgba(245, 245, 240, 0.72)",
    shadow: "rgba(12, 14, 18, 0.44)",
    length: 0.98,
    sleeveWidth: 0.28,
    hood: false,
    pocket: false,
  },
  coat: {
    label: "Куртка",
    fill: "rgba(56, 44, 38, 0.82)",
    sleeve: "rgba(50, 39, 34, 0.76)",
    accent: "rgba(255, 221, 180, 0.72)",
    shadow: "rgba(8, 5, 4, 0.48)",
    length: 1.38,
    sleeveWidth: 0.32,
    hood: false,
    pocket: true,
  },
};

const artworks = [
  {
    title: "The Bedroom",
    artist: "Винсент ван Гог",
    imageId: "6644829f-f292-c5c4-a73c-0356a6fdbf0d",
  },
  {
    title: "Self-Portrait",
    artist: "Винсент ван Гог",
    imageId: "47c5bcb8-62ef-e5d7-55e7-f5121f409a30",
  },
  {
    title: "Water Lilies",
    artist: "Клод Моне",
    imageId: "3c27b499-af56-f0d5-93b5-a7f2f1ad5813",
  },
  {
    title: "Arrival of the Normandy Train, Gare Saint-Lazare",
    artist: "Клод Моне",
    imageId: "0f1cc0e0-e42e-be16-3f71-2022da38cb93",
  },
  {
    title: "Two Sisters (On the Terrace)",
    artist: "Пьер-Огюст Ренуар",
    imageId: "3a608f55-d76e-fa96-d0b1-0789fbc48f1e",
  },
  {
    title: "Woman at the Piano",
    artist: "Пьер-Огюст Ренуар",
    imageId: "8f06717c-9ede-f22b-d13b-327a50c22f9c",
  },
  {
    title: "The Basket of Apples",
    artist: "Поль Сезанн",
    imageId: "52ac8996-3460-cf71-cb42-5c4d0aa29b74",
  },
  {
    title: "The Bay of Marseille, Seen from L'Estaque",
    artist: "Поль Сезанн",
    imageId: "d4ca6321-8656-3d3f-a362-2ee297b2b813",
  },
];

const weatherCodeLabels = new Map([
  [0, "ясно"],
  [1, "почти ясно"],
  [2, "переменная облачность"],
  [3, "пасмурно"],
  [45, "туман"],
  [48, "изморозь"],
  [51, "морось"],
  [53, "морось"],
  [55, "сильная морось"],
  [56, "ледяная морось"],
  [57, "ледяная морось"],
  [61, "дождь"],
  [63, "дождь"],
  [65, "сильный дождь"],
  [66, "ледяной дождь"],
  [67, "ледяной дождь"],
  [71, "снег"],
  [73, "снег"],
  [75, "сильный снег"],
  [77, "снежные зерна"],
  [80, "ливень"],
  [81, "ливень"],
  [82, "сильный ливень"],
  [85, "снегопад"],
  [86, "сильный снегопад"],
  [95, "гроза"],
  [96, "гроза с градом"],
  [99, "гроза с градом"],
]);

function formatMirrorDate(now) {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "long",
    month: "long",
    day: "numeric",
    year: "numeric",
  }).format(now);
}

function updateClock() {
  const now = new Date();
  const hours = String(now.getHours()).padStart(2, "0");
  const minutes = String(now.getMinutes()).padStart(2, "0");
  const seconds = String(now.getSeconds()).padStart(2, "0");

  dateLine.textContent = formatMirrorDate(now);
  timeMain.textContent = `${hours}:${minutes}`;
  timeSeconds.textContent = seconds;
  timePeriod.textContent = "";
}

function rotateNewsHeadline() {
  headlineIndex = (headlineIndex + 1) % newsItems.length;
  newsHeadline.textContent = newsItems[headlineIndex];
}

function artworkImageUrl(imageId) {
  return `https://www.artic.edu/iiif/2/${imageId}/full/1400,/0/default.jpg`;
}

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
  galleryImage.src = artworkImageUrl(artwork.imageId);
  galleryImage.alt = `${artwork.title}, ${artwork.artist}`;
  galleryTitle.textContent = artwork.title;
  galleryArtist.textContent = artwork.artist;
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function midpoint(a, b) {
  return {
    x: (a.x + b.x) / 2,
    y: (a.y + b.y) / 2,
  };
}

function normalizeVector(vector, fallback) {
  const length = Math.hypot(vector.x, vector.y);

  if (length < 0.0001) {
    return fallback;
  }

  return {
    x: vector.x / length,
    y: vector.y / length,
  };
}

function basisPoint(origin, across, down, x, y) {
  return {
    x: origin.x + across.x * x + down.x * y,
    y: origin.y + across.y * x + down.y * y,
  };
}

function moveToPoint(context, point) {
  context.moveTo(point.x, point.y);
}

function lineToPoint(context, point) {
  context.lineTo(point.x, point.y);
}

function quadraticToPoint(context, control, point) {
  context.quadraticCurveTo(control.x, control.y, point.x, point.y);
}

function bezierToPoint(context, controlA, controlB, point) {
  context.bezierCurveTo(controlA.x, controlA.y, controlB.x, controlB.y, point.x, point.y);
}

function waitForVideoFrame() {
  if (arVideo.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    arVideo.addEventListener("loadeddata", resolve, { once: true });
  });
}

async function ensurePoseLandmarker() {
  if (arPoseLandmarker) {
    return arPoseLandmarker;
  }

  if (!arPoseInitPromise) {
    arPoseInitPromise = (async () => {
      const { FilesetResolver, PoseLandmarker } = await import(mediaPipeVisionUrl);
      const vision = await FilesetResolver.forVisionTasks(mediaPipeWasmUrl);

      arPoseLandmarker = await PoseLandmarker.createFromOptions(vision, {
        baseOptions: {
          modelAssetPath: poseModelUrl,
        },
        runningMode: "VIDEO",
        numPoses: 1,
        minPoseDetectionConfidence: 0.5,
        minPosePresenceConfidence: 0.5,
        minTrackingConfidence: 0.45,
      });

      return arPoseLandmarker;
    })().catch((error) => {
      arPoseInitPromise = null;
      throw error;
    });
  }

  return arPoseInitPromise;
}

function resizeArCanvas() {
  const rect = arCanvas.getBoundingClientRect();
  const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
  const width = Math.max(1, Math.round(rect.width));
  const height = Math.max(1, Math.round(rect.height));
  const canvasWidth = Math.round(width * pixelRatio);
  const canvasHeight = Math.round(height * pixelRatio);

  if (arCanvas.width !== canvasWidth || arCanvas.height !== canvasHeight) {
    arCanvas.width = canvasWidth;
    arCanvas.height = canvasHeight;
  }

  const context = arCanvas.getContext("2d");
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);

  return {
    context,
    width,
    height,
  };
}

function clearArCanvas() {
  const { context, width, height } = resizeArCanvas();
  context.clearRect(0, 0, width, height);
}

function landmarkIsVisible(landmark, minimumVisibility = 0.42) {
  return Boolean(landmark) && (landmark.visibility ?? 1) >= minimumVisibility;
}

function landmarkToCanvasPoint(landmark, metrics) {
  const videoWidth = arVideo.videoWidth || metrics.width;
  const videoHeight = arVideo.videoHeight || metrics.height;
  const scale = Math.max(metrics.width / videoWidth, metrics.height / videoHeight);
  const renderedWidth = videoWidth * scale;
  const renderedHeight = videoHeight * scale;
  const offsetX = (metrics.width - renderedWidth) / 2;
  const offsetY = (metrics.height - renderedHeight) / 2;

  return {
    x: offsetX + (1 - landmark.x) * renderedWidth,
    y: offsetY + landmark.y * renderedHeight,
    visibility: landmark.visibility ?? 1,
  };
}

function extractBodyPoints(landmarks, metrics) {
  if (!landmarks) {
    return null;
  }

  const required = [
    "leftShoulder",
    "rightShoulder",
    "leftHip",
    "rightHip",
  ];

  for (const name of required) {
    if (!landmarkIsVisible(landmarks[poseLandmarks[name]])) {
      return null;
    }
  }

  const body = {};
  for (const [name, index] of Object.entries(poseLandmarks)) {
    const landmark = landmarks[index];
    body[name] = landmarkIsVisible(landmark, 0.35)
      ? landmarkToCanvasPoint(landmark, metrics)
      : null;
  }

  return body;
}

function smoothPoint(previous, next, alpha = 0.36) {
  if (!previous || !next) {
    return next;
  }

  return {
    x: previous.x + (next.x - previous.x) * alpha,
    y: previous.y + (next.y - previous.y) * alpha,
    visibility: next.visibility,
  };
}

function smoothBody(nextBody) {
  if (!arSmoothedBody) {
    return nextBody;
  }

  return Object.fromEntries(
    Object.entries(nextBody).map(([name, point]) => [
      name,
      smoothPoint(arSmoothedBody[name], point),
    ]),
  );
}

function drawSleeve(context, start, control, end, style, lineWidth) {
  context.save();
  context.lineCap = "round";
  context.lineJoin = "round";
  context.strokeStyle = style.shadow;
  context.lineWidth = lineWidth * 1.2;
  context.beginPath();
  moveToPoint(context, start);
  quadraticToPoint(context, control, end);
  context.stroke();

  context.strokeStyle = style.sleeve;
  context.lineWidth = lineWidth;
  context.beginPath();
  moveToPoint(context, start);
  quadraticToPoint(context, control, end);
  context.stroke();

  context.strokeStyle = "rgba(255, 255, 255, 0.24)";
  context.lineWidth = Math.max(1, lineWidth * 0.08);
  context.beginPath();
  moveToPoint(context, start);
  quadraticToPoint(context, control, end);
  context.stroke();
  context.restore();
}

function drawTrackingGuides(context, body) {
  const points = [
    body.leftShoulder,
    body.rightShoulder,
    body.rightHip,
    body.leftHip,
  ];

  context.save();
  context.strokeStyle = "rgba(255, 255, 255, 0.18)";
  context.lineWidth = 1.5;
  context.beginPath();
  moveToPoint(context, points[0]);
  for (const point of points.slice(1)) {
    lineToPoint(context, point);
  }
  context.closePath();
  context.stroke();

  context.fillStyle = "rgba(255, 255, 255, 0.72)";
  for (const point of points) {
    context.beginPath();
    context.arc(point.x, point.y, 3.5, 0, Math.PI * 2);
    context.fill();
  }
  context.restore();
}

function drawGarmentDetails(context, point, width, height, style) {
  context.save();
  context.strokeStyle = style.accent;
  context.lineCap = "round";
  context.lineJoin = "round";

  context.globalAlpha = 0.82;
  context.lineWidth = Math.max(1.4, width * 0.016);
  context.beginPath();
  moveToPoint(context, point(0, width * 0.12));
  lineToPoint(context, point(0, height * 0.9));
  context.stroke();

  context.globalAlpha = 0.34;
  context.lineWidth = Math.max(1, width * 0.012);
  for (const y of [0.3, 0.48, 0.68]) {
    context.beginPath();
    moveToPoint(context, point(-width * 0.43, height * y));
    quadraticToPoint(context, point(0, height * y + width * 0.045), point(width * 0.43, height * y));
    context.stroke();
  }

  context.globalAlpha = 0.74;
  context.beginPath();
  moveToPoint(context, point(-width * 0.22, width * 0.08));
  quadraticToPoint(context, point(0, width * 0.22), point(width * 0.22, width * 0.08));
  context.stroke();

  if (style.pocket) {
    context.globalAlpha = 0.46;
    context.fillStyle = "rgba(0, 0, 0, 0.18)";
    context.strokeStyle = style.accent;
    context.beginPath();
    moveToPoint(context, point(-width * 0.28, height * 0.58));
    lineToPoint(context, point(width * 0.28, height * 0.58));
    lineToPoint(context, point(width * 0.2, height * 0.76));
    quadraticToPoint(context, point(0, height * 0.82), point(-width * 0.2, height * 0.76));
    context.closePath();
    context.fill();
    context.stroke();
  }

  if (style.hood) {
    context.globalAlpha = 0.62;
    context.setLineDash([width * 0.04, width * 0.045]);
    context.beginPath();
    moveToPoint(context, point(-width * 0.18, width * 0.2));
    lineToPoint(context, point(-width * 0.09, height * 0.42));
    moveToPoint(context, point(width * 0.18, width * 0.2));
    lineToPoint(context, point(width * 0.09, height * 0.42));
    context.stroke();
    context.setLineDash([]);
  }

  context.restore();
}

function drawGarment(context, body, metrics) {
  const style = garmentStyles[activeGarment] ?? garmentStyles.hoodie;
  const leftShoulder = body.leftShoulder;
  const rightShoulder = body.rightShoulder;
  const leftHip = body.leftHip;
  const rightHip = body.rightHip;
  const shoulderMid = midpoint(leftShoulder, rightShoulder);
  const hipMid = midpoint(leftHip, rightHip);
  const shoulderWidth = clamp(distance(leftShoulder, rightShoulder), 90, metrics.width * 0.76);
  const torsoHeight = clamp(
    Math.max(distance(shoulderMid, hipMid) * style.length, shoulderWidth * 1.08),
    shoulderWidth,
    metrics.height * 0.74,
  );
  const across = normalizeVector(
    {
      x: rightShoulder.x - leftShoulder.x,
      y: rightShoulder.y - leftShoulder.y,
    },
    { x: 1, y: 0 },
  );
  const down = normalizeVector(
    {
      x: hipMid.x - shoulderMid.x,
      y: hipMid.y - shoulderMid.y,
    },
    { x: -across.y, y: across.x },
  );
  const point = (x, y) => basisPoint(shoulderMid, across, down, x, y);
  const topHalf = shoulderWidth * 0.73;
  const hemHalf = shoulderWidth * (activeGarment === "coat" ? 0.72 : 0.62);
  const sleeveWidth = clamp(shoulderWidth * style.sleeveWidth, 28, 112);

  const leftSleeveControl = body.leftElbow ?? point(-shoulderWidth * 0.86, torsoHeight * 0.32);
  const leftSleeveEnd = body.leftWrist ?? point(-shoulderWidth * 0.78, torsoHeight * 0.74);
  const rightSleeveControl = body.rightElbow ?? point(shoulderWidth * 0.86, torsoHeight * 0.32);
  const rightSleeveEnd = body.rightWrist ?? point(shoulderWidth * 0.78, torsoHeight * 0.74);

  drawSleeve(context, leftShoulder, leftSleeveControl, leftSleeveEnd, style, sleeveWidth);
  drawSleeve(context, rightShoulder, rightSleeveControl, rightSleeveEnd, style, sleeveWidth);

  if (style.hood) {
    context.save();
    context.fillStyle = "rgba(34, 52, 67, 0.72)";
    context.strokeStyle = "rgba(255, 255, 255, 0.28)";
    context.lineWidth = Math.max(1.5, shoulderWidth * 0.015);
    context.beginPath();
    moveToPoint(context, point(-shoulderWidth * 0.38, shoulderWidth * 0.08));
    bezierToPoint(
      context,
      point(-shoulderWidth * 0.48, -shoulderWidth * 0.36),
      point(shoulderWidth * 0.48, -shoulderWidth * 0.36),
      point(shoulderWidth * 0.38, shoulderWidth * 0.08),
    );
    quadraticToPoint(context, point(0, shoulderWidth * 0.3), point(-shoulderWidth * 0.38, shoulderWidth * 0.08));
    context.fill();
    context.stroke();
    context.restore();
  }

  const topLeft = point(-topHalf, -shoulderWidth * 0.02);
  const bottomRight = point(hemHalf, torsoHeight);
  const fill = context.createLinearGradient(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y);
  fill.addColorStop(0, "rgba(255, 255, 255, 0.18)");
  fill.addColorStop(0.18, style.fill);
  fill.addColorStop(1, "rgba(0, 0, 0, 0.42)");

  context.save();
  context.shadowColor = "rgba(0, 0, 0, 0.42)";
  context.shadowBlur = 28;
  context.shadowOffsetY = 18;
  context.fillStyle = fill;
  context.strokeStyle = "rgba(255, 255, 255, 0.42)";
  context.lineWidth = Math.max(2, shoulderWidth * 0.018);
  context.beginPath();
  moveToPoint(context, point(-topHalf, -shoulderWidth * 0.02));
  bezierToPoint(
    context,
    point(-topHalf * 0.92, torsoHeight * 0.24),
    point(-hemHalf, torsoHeight * 0.68),
    point(-hemHalf, torsoHeight),
  );
  quadraticToPoint(context, point(0, torsoHeight + shoulderWidth * 0.08), point(hemHalf, torsoHeight));
  bezierToPoint(
    context,
    point(hemHalf, torsoHeight * 0.68),
    point(topHalf * 0.92, torsoHeight * 0.24),
    point(topHalf, -shoulderWidth * 0.02),
  );
  quadraticToPoint(context, point(shoulderWidth * 0.42, -shoulderWidth * 0.14), point(shoulderWidth * 0.18, shoulderWidth * 0.08));
  quadraticToPoint(context, point(0, shoulderWidth * 0.19), point(-shoulderWidth * 0.18, shoulderWidth * 0.08));
  quadraticToPoint(context, point(-shoulderWidth * 0.42, -shoulderWidth * 0.14), point(-topHalf, -shoulderWidth * 0.02));
  context.closePath();
  context.fill();
  context.stroke();
  context.restore();

  drawGarmentDetails(context, point, shoulderWidth, torsoHeight, style);
}

function renderArTrackingFrame(timestamp) {
  arAnimationFrame = 0;

  if (!arStream || !arPoseLandmarker) {
    clearArCanvas();
    return;
  }

  arAnimationFrame = window.requestAnimationFrame(renderArTrackingFrame);

  if (
    arVideo.readyState < HTMLMediaElement.HAVE_CURRENT_DATA ||
    arVideo.currentTime === arLastVideoTime ||
    timestamp - arLastPoseTimestamp < 66
  ) {
    return;
  }

  arLastVideoTime = arVideo.currentTime;
  arLastPoseTimestamp = timestamp;

  const metrics = resizeArCanvas();
  metrics.context.clearRect(0, 0, metrics.width, metrics.height);

  let results;
  try {
    results = arPoseLandmarker.detectForVideo(arVideo, performance.now());
  } catch (error) {
    arTracking.textContent = "Ошибка трекинга тела";
    arMessage.textContent = "Не удалось обработать кадр";
    return;
  }

  const body = extractBodyPoints(results.landmarks?.[0], metrics);
  if (!body) {
    arSmoothedBody = null;
    arTracking.textContent = "Встань в кадр по пояс";
    arMessage.textContent = "Нужны видимые плечи и бёдра";
    return;
  }

  arSmoothedBody = smoothBody(body);
  arMessage.textContent = "";
  arTracking.textContent = `${garmentStyles[activeGarment].label}: тело отслеживается`;
  drawTrackingGuides(metrics.context, arSmoothedBody);
  drawGarment(metrics.context, arSmoothedBody, metrics);
}

function startArTrackingLoop() {
  if (arAnimationFrame || !arStream || !arPoseLandmarker) {
    return;
  }

  arLastVideoTime = -1;
  arLastPoseTimestamp = 0;
  arAnimationFrame = window.requestAnimationFrame(renderArTrackingFrame);
}

function stopArTrackingLoop() {
  if (arAnimationFrame) {
    window.cancelAnimationFrame(arAnimationFrame);
    arAnimationFrame = 0;
  }

  arLastVideoTime = -1;
  arLastPoseTimestamp = 0;
  arSmoothedBody = null;
  clearArCanvas();
}

async function startArCamera() {
  if (arStream) {
    startArTrackingLoop();
    return;
  }

  if (!navigator.mediaDevices?.getUserMedia) {
    arMessage.textContent = "Камера доступна на localhost или через HTTPS";
    arTracking.textContent = "Нет доступа к камере";
    return;
  }

  try {
    arMessage.textContent = "Камера запускается";
    arTracking.textContent = "Готовим камеру";
    arStream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: "user",
        width: { ideal: 1280 },
        height: { ideal: 720 },
      },
      audio: false,
    });
    arVideo.srcObject = arStream;
    await arVideo.play().catch(() => {});
    await waitForVideoFrame();

    if (!arStream) {
      return;
    }

    arMessage.textContent = "Загружаю модель тела";
    arTracking.textContent = "Загружаем MediaPipe";
    await ensurePoseLandmarker();

    if (!arStream) {
      return;
    }

    arMessage.textContent = "Встань в кадр по пояс";
    arTracking.textContent = "Ищем плечи и бёдра";
    startArTrackingLoop();
  } catch (error) {
    arMessage.textContent =
      error.name === "NotAllowedError"
        ? "Разреши доступ к камере"
        : "AR-трекинг недоступен";
    arTracking.textContent = "Проверь интернет, HTTPS и камеру";
    stopArCamera();
  }
}

function stopArCamera() {
  stopArTrackingLoop();

  if (!arStream) {
    return;
  }

  for (const track of arStream.getTracks()) {
    track.stop();
  }

  arStream = null;
  arVideo.srcObject = null;
}

function setArGarment(garment) {
  if (!garmentStyles[garment]) {
    return;
  }

  activeGarment = garment;
  arTracking.textContent = `${garmentStyles[activeGarment].label}: выбранный слой`;

  for (const button of arControls.querySelectorAll("button")) {
    button.classList.toggle("active", button.dataset.garment === garment);
  }
}

function renderNews(items) {
  newsItems = items.map((item) => `ТАСС: ${item.title}`).filter(Boolean);

  if (newsItems.length === 0) {
    newsItems = ["ТАСС: новости временно недоступны"];
  }

  headlineIndex = 0;
  newsHeadline.textContent = newsItems[headlineIndex];
}

async function loadTassNews() {
  try {
    const response = await fetch("/api/news/tass", {
      headers: {
        Authorization: `Bearer ${wsToken}`,
      },
      cache: "no-store",
    });

    if (!response.ok) {
      throw new Error("News request failed");
    }

    const payload = await response.json();
    renderNews(payload.items ?? []);
  } catch (error) {
    renderNews([{ title: "новости временно недоступны" }]);
  }
}

function formatRub(value, fractionDigits = 2) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "--";
  }

  return new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: fractionDigits,
    minimumFractionDigits: fractionDigits,
  }).format(value);
}

function formatMarketDelta(value, previous) {
  if (typeof value !== "number" || typeof previous !== "number") {
    return "";
  }

  const delta = value - previous;
  if (Math.abs(delta) < 0.0001) {
    return "0.00";
  }

  return `${delta > 0 ? "+" : ""}${formatRub(delta, 2)}`;
}

function formatCryptoDelta(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "";
  }

  return `${value > 0 ? "+" : ""}${formatRub(value, 2)}%`;
}

function marketDeltaClass(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "";
  }

  if (value > 0) {
    return "up";
  }

  if (value < 0) {
    return "down";
  }

  return "";
}

function renderMarketRows(container, items, type) {
  container.replaceChildren();

  for (const item of items) {
    const row = document.createElement("div");
    const label = document.createElement("span");
    const value = document.createElement("strong");
    const meta = document.createElement("em");
    const deltaValue =
      type === "crypto"
        ? item.change24hPct
        : item.valueRub - item.previousRub;

    row.className = "market-row";
    label.textContent = item.label;
    value.textContent = `${formatRub(item.valueRub, item.valueRub > 1000 ? 0 : 2)} ₽`;
    meta.textContent =
      type === "crypto"
        ? `${item.code} ${formatCryptoDelta(item.change24hPct)}`
        : `${item.code} ${formatMarketDelta(item.valueRub, item.previousRub)}`;
    meta.classList.toggle("up", marketDeltaClass(deltaValue) === "up");
    meta.classList.toggle("down", marketDeltaClass(deltaValue) === "down");

    row.append(label, value, meta);
    container.append(row);
  }
}

function renderMarkets(payload) {
  renderMarketRows(fiatRates, payload.fiat ?? [], "fiat");
  renderMarketRows(cryptoRates, payload.crypto ?? [], "crypto");
}

async function loadMarkets() {
  try {
    const response = await fetch("/api/markets", {
      headers: {
        Authorization: `Bearer ${wsToken}`,
      },
      cache: "no-store",
    });

    if (!response.ok) {
      throw new Error("Market request failed");
    }

    renderMarkets(await response.json());
  } catch (error) {
    fiatRates.querySelector("strong").textContent = "нет данных";
    cryptoRates.querySelector("strong").textContent = "нет данных";
  }
}

function formatTemperature(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "--";
  }

  return Math.round(value).toString();
}

function formatForecastTemperature(value) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return "--°C";
  }

  return `${Math.round(value)}°C`;
}

function formatForecastDay(value) {
  const date = new Date(`${value}T12:00:00+03:00`);

  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "short",
  })
    .format(date)
    .replace(".", "");
}

function weatherCodeLabel(code) {
  return weatherCodeLabels.get(code) ?? "погода";
}

function renderWeather(weather) {
  const currentTemperature = weather.current?.temperature_2m;
  const currentCode = weather.current?.weather_code;
  const daily = weather.daily ?? {};

  weatherTemp.innerHTML = `${formatTemperature(currentTemperature)}<span>&deg;C</span>`;
  weatherPlace.textContent = `Москва · ${weatherCodeLabel(currentCode)}`;
  forecastList.replaceChildren();

  for (let index = 0; index < Math.min(daily.time?.length ?? 0, 5); index += 1) {
    const item = document.createElement("li");
    const day = document.createElement("span");
    const max = document.createElement("strong");
    const min = document.createElement("em");

    day.textContent = formatForecastDay(daily.time[index]);
    max.textContent = formatForecastTemperature(daily.temperature_2m_max?.[index]);
    min.textContent = formatForecastTemperature(daily.temperature_2m_min?.[index]);
    item.title = weatherCodeLabel(daily.weather_code?.[index]);
    item.append(day, max, min);
    forecastList.append(item);
  }
}

async function loadMoscowWeather() {
  try {
    const response = await fetch(moscowWeatherUrl, {
      cache: "no-store",
    });

    if (!response.ok) {
      throw new Error("Weather request failed");
    }

    renderWeather(await response.json());
  } catch (error) {
    weatherPlace.textContent = "Москва · нет данных";
  }
}

function setConnection(label, connected = false) {
  connectionState.textContent = label;
  connectionState.classList.toggle("connected", connected);
}

function setModuleVisibility(state) {
  const modulesById = new Map(state.modules.map((module) => [module.id, module]));

  for (const element of document.querySelectorAll("[data-module-id]")) {
    const module = modulesById.get(element.dataset.moduleId);
    element.classList.toggle("module-hidden", module?.visible === false);
  }
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

function renderState(state) {
  const displayMode = state.displayMode ?? "mirror";

  mirrorRoot.classList.toggle("display-off", state.displayState === "off");
  mirrorRoot.classList.toggle("gallery-mode", displayMode === "gallery");
  mirrorRoot.classList.toggle("ar-mode", displayMode === "ar");
  setModuleVisibility(state);

  if (displayMode === "gallery" && currentArtworkIndex === -1) {
    showRandomArtwork();
  }

  if (displayMode === "ar") {
    startArCamera();
  } else {
    stopArCamera();
  }

  const visibleCount = state.modules.filter((module) => module.visible).length;
  const modeLabel =
    displayMode === "gallery"
      ? "экран ожидания"
      : displayMode === "ar"
        ? "AR примерка"
        : "зеркало";
  mirrorMeta.textContent = `${modeLabel} - видно модулей: ${visibleCount}/${state.modules.length} - ${formatMetaTime(
    state.lastReloadedAt,
  )}`;
}

function renderPairingStatus(status) {
  if (!pairingPanel || !wsToken) {
    return;
  }

  pairingPanel.hidden = Boolean(status?.controllerConnected);
}

async function pingServer() {
  const response = await fetch("/api/health");

  if (!response.ok) {
    throw new Error("Server is not healthy");
  }
}

function connect() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const socketUrl = new URL(`${protocol}//${window.location.host}/ws`);

  if (wsToken) {
    socketUrl.searchParams.set("token", wsToken);
  }

  const socket = new WebSocket(socketUrl);

  socket.addEventListener("open", async () => {
    setConnection("Подключено", true);

    try {
      await pingServer();
    } catch (error) {
      setConnection("Ошибка сервера");
    }
  });

  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);

    if (message.type === "mirror_state_changed") {
      renderState(message.payload);
    }

    if (message.type === "pairing_status_changed") {
      renderPairingStatus(message.payload);
    }
  });

  socket.addEventListener("close", () => {
    setConnection(wsToken ? "Переподключение..." : "Нет токена");

    if (wsToken) {
      window.setTimeout(connect, 1500);
    }
  });

  socket.addEventListener("error", () => {
    socket.close();
  });
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

updateClock();
pairingPanel.hidden = true;
loadPairingQr();
loadMoscowWeather();
loadTassNews();
loadMarkets();
showRandomArtwork();
setArGarment("hoodie");
arControls.addEventListener("click", (event) => {
  const button = event.target.closest("button[data-garment]");
  if (!button) {
    return;
  }

  setArGarment(button.dataset.garment);
});
window.setInterval(updateClock, 1000);
window.setInterval(loadMoscowWeather, 10 * 60 * 1000);
window.setInterval(loadTassNews, 5 * 60 * 1000);
window.setInterval(loadMarkets, 2 * 60 * 1000);
window.setInterval(rotateNewsHeadline, 14000);
window.setInterval(() => {
  if (mirrorRoot.classList.contains("gallery-mode")) {
    showRandomArtwork();
  }
}, 45 * 1000);
connect();
