async function pingServer() {
  const response = await fetch("/api/health");
  if (!response.ok) {
    throw new Error("Server is not healthy");
  }
}

export function initWs({ wsToken, onStateChange, onPairingStatusChange, onConnectionChange }) {
  function connect() {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const socketUrl = new URL(`${protocol}//${window.location.host}/ws`);

    if (wsToken) {
      socketUrl.searchParams.set("token", wsToken);
    }

    const socket = new WebSocket(socketUrl);

    socket.addEventListener("open", async () => {
      onConnectionChange?.("Подключено", true);
      try {
        await pingServer();
      } catch (error) {
        onConnectionChange?.("Ошибка сервера", false);
      }
    });

    socket.addEventListener("message", (event) => {
      const message = JSON.parse(event.data);

      if (message.type === "mirror_state_changed") {
        onStateChange?.(message.payload);
      }

      if (message.type === "pairing_status_changed") {
        onPairingStatusChange?.(message.payload);
      }
    });

    socket.addEventListener("close", () => {
      onConnectionChange?.(wsToken ? "Переподключение..." : "Нет токена", false);

      if (wsToken) {
        window.setTimeout(connect, 1500);
      }
    });

    socket.addEventListener("error", () => {
      socket.close();
    });
  }

  connect();
}
