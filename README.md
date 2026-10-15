# XRay & Freecam AntiCheat

[![Build](https://github.com/YOUR_USERNAME/XRayFreecamAntiCheat/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/XRayFreecamAntiCheat/actions/workflows/build.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-green)
![Platform](https://img.shields.io/badge/platform-Paper-orange)
![License](https://img.shields.io/badge/license-MIT-blue)

Античит-плагин для **Paper 1.21.11**, защищающий от читов **X-Ray** (текстурпаки со «сквозными» текстурами) и **Freecam** (полёт-наблюдатель вне тела игрока).

## Принцип работы

Плагин не меняет реальный мир — подмена блоков происходит **только в данных чанков, отправляемых конкретному клиенту**:

- **Freecam**: игрок с freecam не видит сквозь стены. Всё, что находится вне его реального обзора (line-of-sight), заменяется для него камнем. Пример: Игрок2 стоит в бункере под землёй — для Игрока1 снаружи он буквально «стоит в блоках», а все подземные пустоты и руды за стенками для клиента Игрока1 — сплошной камень.
- **X-Ray**: текстурпак делает камень/сланец прозрачными, но античит заранее подменяет скрытые руды за стенами на *другой* блок камня в пакете ChunkData — «видеть» через стену становится нечего.
- **Прозрачные блоки** (стекло, листва, лёд и т.д.) остаются видимыми только если клиент реально может видеть, что за ними. Стекло в стене без обзора сквозь него тоже становится камнем.
- Камень **не появляется там, где игрок уже был** (посещённые места запоминаются).
- Камень **не появляется за листвой/деревом на поверхности**.
- Защита действует дальше **30 блоков** от игрока; если блок окружён сплошными стенами — уже с **5 блоков по горизонтали и 10 вниз**.
- Кэш замен сбрасывается при перезаходе (прогруженные чанки пересылаются заново).

## Спектаторы

**Спектаторы никогда не видят камень античита** (`SpectatorManager`):

- Игроки в режиме `GameMode.SPECTATOR` полностью исключены из санитизации — они легально пролетают сквозь блоки, подмена сломала бы им обзор.
- Исключение применяется мгновенно при смене геймода (`PlayerGameModeChangeEvent`): кэш замен очищается, чанки вокруг игрока пересылаются без камня (hide/showChunk через Paper API).
- Право `anticheat.spectate` помечает спектатора даже в креативе (для сторонних spectate-плагинов); отключается в конфиге `spectator.permission-exempt`.
- Ручное управление: `/anticheat spectator add|remove|list [игрок] [секунды]`.

## Сборка

Требования: JDK 21+, Maven 3.8+.

```bash
mvn clean package
```

Готовый jar: `target/xray-freecam-anticheat-1.0.0.jar`.

## Скачать готовый плагин

Собранный JAR включён в репозиторий — скачивайте без сборки:

| Файл | Описание |
|---|---|
| [`download/xray-freecam-anticheat-1.0.0.jar`](download/xray-freecam-anticheat-1.0.0.jar) | Готовый плагин — положить в папку `plugins/` сервера Paper 1.21.x |
| [`download/xray-freecam-anticheat-v1.0.0.zip`](download/xray-freecam-anticheat-v1.0.0.zip) | Архив релиза (JAR + config.yml) |

После загрузки на GitHub эти файлы также будут доступны как **Release Assets**
(CI-workflow `release.yml` автоматически публикует тег `v*` с JAR).

## Установка

Скопируйте jar в папку `plugins/` сервера **Paper 1.21.11** и перезапустите сервер.

## Команды

| Команда | Описание | Право |
|---|---|---|
| `/anticheat enable` / `disable` | Вкл/выкл защиту | `anticheat.admin` |
| `/anticheat status` | Состояние плагина | `anticheat.admin` |
| `/anticheat reload` | Перезагрузка конфига | `anticheat.admin` |
| `/anticheat spectator add <игрок> [сек]` | Пометить спектатора | `anticheat.admin` |
| `/anticheat spectator remove <игрок>` | Снять пометку | `anticheat.admin` |
| `/anticheat spectator list` | Список пометок | `anticheat.admin` |

## Права

- `anticheat.admin` — управление плагином
- `anticheat.bypass` — полный обход защиты
- `anticheat.spectate` — игрок считается спектатором, камень античита ему не показывается

## Конфигурация (`plugins/XRayFreecamAntiCheat/config.yml`)

- `protection.far-distance` — базовая дистанция защиты (по умолчанию 30)
- `protection.wall-horizontal` / `protection.wall-vertical-down` — дистанции внутри сплошных стен (5 / 10)
- `protection.visited-radius` — радиус «посещённых мест», где камень не появляется
- `spectator.permission-exempt` — считать держателей `anticheat.spectate` спектаторами

## Примечание об архитектуре

Решения о подмене блоков кэшируются в `playerReplacements`; финальная отправка изменённых чанк-пакетов выполняется packet-слоем (PacketEvents / ProtocolLib или Netty-инъекция) — стандартная схема анти-Xray плагинов (Orebfuscator). Без packet-слоя плагин работает в режиме расчёта/подготовки правил.

## Лицензия

[MIT](LICENSE)
