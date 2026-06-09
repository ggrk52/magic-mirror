async function pingServer() {
  const response = await fetch("/api/health");
  if (!response.ok) {
    throw new Error("Server is not healthy");
  }
}

export function initWs({ wsToken, onStateChange, onPairingStatusChange, onConnectionChange }) {
  let reconnectDelay = 1500;
  const MAX_RECONNECT_DELAY = 30000;
  const HEARTBEAT_INTERVAL = 30000;
  let heartbeatTimer = null;
  let missedPongs = 0;

  function resetReconnectDelay() {
    reconnectDelay = 1500;
  }

  function nextReconnectDelay() {
    const delay = reconnectDelay;
    reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY);
    return delay;
  }

  function connect() {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const socketUrl = new URL(`${protocol}//${window.location.host}/ws`);

    if (wsToken) {
      socketUrl.searchParams.set("token", wsToken);
    }

    const socket = new WebSocket(socketUrl);

    function startHeartbeat() {
      stopHeartbeat();
      missedPongs = 0;
      heartbeatTimer = window.setInterval(() => {
        if (missedPongs >= 2) {
          // Server seems dead, force reconnect
          stopHeartbeat();
          socket.close();
          return;
        }

        try {
          // Send a WebSocket ping via a small text message
          // The server can respond or we just check socket.readyState
          if (socket.readyState === WebSocket.OPEN) {
            missedPongs += 1;
          }
        } catch (error) {
          socket.close();
        }
      }, HEARTBEAT_INTERVAL);
    }

    function stopHeartbeat() {
      if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
      }
    }

    socket.addEventListener("open", async () => {
      resetReconnectDelay();
      onConnectionChange?.("Подключено", true);
      startHeartbeat();

      try {
        await pingServer();
      } catch (error) {
        onConnectionChange?.("Ошибка сервера", false);
      }
    });

    socket.addEventListener("message", (event) => {
      // Any message from server resets heartbeat counter
      missedPongs = 0;

      try {
        const message = JSON.parse(event.data);

        if (message.type === "mirror_state_changed") {
          onStateChange?.(message.payload);
        }

        if (message.type === "pairing_status_changed") {
          onPairingStatusChange?.(message.payload);
        }
      } catch (error) {
        console.warn("WS: failed to parse message", error);
      }
    });

    socket.addEventListener("close", () => {
      stopHeartbeat();

      if (!wsToken) {
        onConnectionChange?.("Нет токена", false);
        return;
      }

      const delay = nextReconnectDelay();
      const delaySec = Math.round(delay / 1000);
      onConnectionChange?.(`Переподключение через ${delaySec}с...`, false);
      window.setTimeout(connect, delay);
    });

    socket.addEventListener("error", () => {
      socket.close();
    });
  }

  connect();
}
