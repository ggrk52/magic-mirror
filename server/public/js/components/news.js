let newsHeadline = null;
let headlineIndex = 0;
let newsItems = ["ТАСС: загружаем свежие новости"];

export function rotateNewsHeadline(onRender) {
  if (!newsHeadline) newsHeadline = document.querySelector("#newsHeadline");
  if (!newsHeadline) return;

  newsHeadline.classList.add("news-hidden");
  setTimeout(() => {
    headlineIndex = (headlineIndex + 1) % newsItems.length;
    newsHeadline.textContent = newsItems[headlineIndex];
    onRender?.();
    newsHeadline.classList.remove("news-hidden");
  }, 300);
}

export function renderNews(items, onRender) {
  if (!newsHeadline) newsHeadline = document.querySelector("#newsHeadline");
  if (!newsHeadline) return;

  newsItems = items.map((item) => `ТАСС: ${item.title}`).filter(Boolean);

  if (newsItems.length === 0) {
    newsItems = ["ТАСС: новости временно недоступны"];
  }

  newsHeadline.classList.add("news-hidden");
  setTimeout(() => {
    headlineIndex = 0;
    newsHeadline.textContent = newsItems[headlineIndex];
    onRender?.();
    newsHeadline.classList.remove("news-hidden");
  }, 300);
}

export async function loadTassNews(wsToken, onRender) {
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
    renderNews(payload.items ?? [], onRender);
  } catch (error) {
    console.error("News load failed:", error);
    renderNews([{ title: "новости временно недоступны" }], onRender);
  }
}

export function startNewsPolling(wsToken, onRender) {
  loadTassNews(wsToken, onRender);
  window.setInterval(() => loadTassNews(wsToken, onRender), 5 * 60 * 1000);
  window.setInterval(() => rotateNewsHeadline(onRender), 14000);
}
