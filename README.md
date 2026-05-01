# Magic Mirror LAN Control

Локальный MVP-проект для управления Magic Mirror по сети.

В проект входят:

- Node.js сервер для локальной сети с небольшим REST API
- браузерный интерфейс зеркала с обновлениями в реальном времени через WebSocket
- полноэкранные режимы: зеркало, картины в ожидании и AR-примерка одежды
- Android-приложение-контроллер в папке `android/`

## Что нужно для запуска

### Сервер и веб-интерфейс

- Node.js 20 или новее
- компьютер в той же локальной сети, что и клиенты зеркала

### Android-приложение

- Android Studio
- локально установленный Android SDK
- реальное Android-устройство или эмулятор

## Быстрый старт

### 1. Открой проект

Из корня проекта:

```powershell
cd "C:\path\to\magic-mirror"
```

### 2. Запусти сервер

Минимальный запуск:

```powershell
node server/src/index.js
```

Запуск со своими host, port и token:

```powershell
$env:MIRROR_HOST = "0.0.0.0"
$env:MIRROR_PORT = "8080"
$env:MIRROR_TOKEN = "change-this-token"
node server/src/index.js
```

Значения по умолчанию:

- `MIRROR_HOST`: `0.0.0.0`
- `MIRROR_PORT`: `8080`
- `MIRROR_TOKEN`: `magic-mirror-local-token`

После запуска сервер выведет локальный адрес и все найденные LAN-адреса в терминал. Для Android и других устройств в Wi‑Fi используй строку `LAN server address`.

### 3. Открой браузерный интерфейс

На той же машине, где запущен сервер, открой:

```text
http://127.0.0.1:8080/
```

Если ты менял порт, используй свой порт.

Этот интерфейс считается доверенной локальной страницей, которую раздаёт сам сервер. Сервер автоматически внедряет токен только для loopback-подключений (`127.0.0.1`/`::1`), чтобы не раздавать токен всем устройствам в Wi‑Fi.

### 4. Подключись с другого устройства в локальной сети

После запуска сервер сам покажет адреса для локальной сети, например:

```text
LAN server address: http://192.168.1.75:8080/
```

Если нужно проверить адрес вручную, на Windows можно использовать:

```powershell
ipconfig
```

В Android-контроллере указывай именно LAN IP машины с сервером, например `192.168.1.75`. Удалённый браузер по LAN не получает токен автоматически; для управления используй Android-приложение или локальный браузер на машине зеркала.

## Запуск тестов

Запуск всех серверных тестов:

```powershell
node --test
```

Текущий набор тестов покрывает:

- доступ к health endpoint
- авторизацию защищённых endpoint
- обновления по WebSocket при корректной авторизации
- отклонение WebSocket-подключений без токена или с неверным токеном
- обработку некорректного JSON
- обработку слишком большого request body

## REST API

Базовый адрес:

```text
http://HOST:PORT
```

Доступные endpoint:

- `GET /api/health`
- `GET /api/mirror/state`
- `POST /api/mirror/display`
- `POST /api/mirror/mode`
- `GET /api/modules`
- `POST /api/modules/:id/visibility`
- `POST /api/modules/:id/refresh`
- `POST /api/modules/refresh-all`

Режимы зеркала для `POST /api/mirror/mode`:

- `mirror`: обычный Magic Mirror интерфейс
- `gallery`: экран ожидания со случайными картинами
- `ar`: AR-примерка одежды через камеру браузера и MediaPipe Pose Landmarker

AR-режим отслеживает плечи, бёдра и руки, после чего подгоняет слой одежды по масштабу, наклону и положению тела. Для загрузки модели трекинга браузерному зеркалу нужен доступ к интернету.

Все маршруты `/api/*`, кроме `/api/health`, требуют заголовок:

```text
Authorization: Bearer YOUR_TOKEN
```

### Пример: проверка health

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/api/health" `
  -Method GET
```

### Пример: получить состояние зеркала

```powershell
$headers = @{ Authorization = "Bearer magic-mirror-local-token" }
Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/api/mirror/state" `
  -Headers $headers `
  -Method GET
```

### Пример: выключить дисплей

```powershell
$headers = @{
  Authorization = "Bearer magic-mirror-local-token"
  "Content-Type" = "application/json"
}

Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/api/mirror/display" `
  -Headers $headers `
  -Method POST `
  -Body '{"action":"off"}'
```

### Пример: включить экран ожидания с картинами

```powershell
$headers = @{
  Authorization = "Bearer magic-mirror-local-token"
  "Content-Type" = "application/json"
}

Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/api/mirror/mode" `
  -Headers $headers `
  -Method POST `
  -Body '{"mode":"gallery"}'
```

### Пример: включить AR-примерку одежды

```powershell
$headers = @{
  Authorization = "Bearer magic-mirror-local-token"
  "Content-Type" = "application/json"
}

Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/api/mirror/mode" `
  -Headers $headers `
  -Method POST `
  -Body '{"mode":"ar"}'
```

Чтобы вернуться к обычному зеркалу, отправь `{"mode":"mirror"}` на тот же endpoint.

### Пример: скрыть модуль

```powershell
$headers = @{
  Authorization = "Bearer magic-mirror-local-token"
  "Content-Type" = "application/json"
}

Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/api/modules/weather/visibility" `
  -Headers $headers `
  -Method POST `
  -Body '{"visible":false}'
```

## Android-приложение

Android-проект находится в папке `android/`.

Возможности:

- ручной ввод host, port и token
- сохранение только host и port
- после перезапуска token нужно вводить заново
- получение состояния зеркала
- управление дисплеем
- включение экрана ожидания со случайными картинами известных художников
- включение AR-примерки одежды на браузерном зеркале
- управление видимостью модулей и их обновлением

### Запуск в Android Studio

1. Открой Android Studio.
2. Выбери `Open` и укажи папку `android/`.
3. Дождись завершения Gradle Sync.
4. Подключи устройство или запусти эмулятор.
5. Нажми `Run`.

### Подключение приложения к серверу

Введи:

- `Host`: LAN IP компьютера с сервером, например `192.168.1.25`
- `Port`: обычно `8080`
- `Bearer token`: то же значение, что и у `MIRROR_TOKEN`

После этого нажми `Проверить подключение`.

## Рекомендуемый первый запуск

Для быстрой локальной проверки:

1. Запусти сервер с токеном по умолчанию.
2. Открой `http://127.0.0.1:8080/` в браузере.
3. В отдельном терминале запусти `node --test`.
4. Если используешь Android, подключи телефон к той же Wi‑Fi сети и укажи LAN IP сервера и тот же токен.

## Решение проблем

### Браузерный интерфейс не подключается

- Убедись, что Node-сервер всё ещё запущен.
- Проверь, что браузер использует тот же host и port, которые сервер вывел в терминале.
- Если ты менял `MIRROR_TOKEN`, перезапусти сервер и обнови страницу.

### Другое устройство в локальной сети не открывает интерфейс

- Убедись, что сервер слушает `0.0.0.0`.
- Проверь правила Windows Firewall.
- Убедись, что оба устройства находятся в одной локальной сети.
- Используй LAN IP машины с сервером, а не `127.0.0.1`.

### Android-приложение не подключается

- Проверь host, port и token без опечаток.
- Убедись, что телефон и сервер находятся в одной Wi‑Fi сети.
- Сначала проверь доступность сервера из обычного браузера.
- В поле `Host` можно вводить просто `192.168.1.75`, `192.168.1.75:8080` или `http://192.168.1.75:8080/` — приложение нормализует адрес само.
- Если сервер запущен на Linux/Raspberry Pi и включён firewall, открой порт: `sudo ufw allow 8080/tcp`.

### API возвращает `401`

- Bearer token отсутствует или неверный.
- Используй тот же токен, с которым был запущен сервер.

### AR-примерка не показывает камеру

- Проверь, что у устройства с браузерным зеркалом есть камера.
- Разреши доступ к камере в браузере.
- Для локальной проверки открывай `http://127.0.0.1:8080/`; на удалённом `http://192.168.x.x:8080/` некоторые браузеры могут блокировать камеру без HTTPS.
- Проверь, что устройство может загрузить MediaPipe с `cdn.jsdelivr.net` и модель с `storage.googleapis.com`.
- Встань в кадр по пояс: для посадки одежды должны быть видны плечи и бёдра.

### API возвращает `413`

- JSON body слишком большой.
- Держи размер request payload меньше 64 KB.
