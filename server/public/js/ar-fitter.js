const mediaPipeVersion = "0.10.22-rc.20250304";
const mediaPipeVisionUrl = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${mediaPipeVersion}/vision_bundle.mjs`;
const mediaPipeWasmUrl = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${mediaPipeVersion}/wasm`;
const poseModelUrl =
  "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task";

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

let arStream = null;
let arAnimationFrame = 0;
let arPoseLandmarker = null;
let arPoseInitPromise = null;
let arLastVideoTime = -1;
let arLastPoseTimestamp = 0;
let arSmoothedBody = null;
let activeGarment = "hoodie";

let arVideo = null;
let arCanvas = null;
let arTracking = null;
let arMessage = null;
let arControls = null;

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

export async function startArCamera() {
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

export function stopArCamera() {
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

export function setArGarment(garment) {
  if (!garmentStyles[garment]) {
    return;
  }

  activeGarment = garment;
  arTracking.textContent = `${garmentStyles[activeGarment].label}: выбранный слой`;

  for (const button of arControls.querySelectorAll("button")) {
    button.classList.toggle("active", button.dataset.garment === garment);
  }
}

export function initAr(elements) {
  arVideo = elements.video;
  arCanvas = elements.canvas;
  arTracking = elements.tracking;
  arMessage = elements.message;
  arControls = elements.controls;
}
