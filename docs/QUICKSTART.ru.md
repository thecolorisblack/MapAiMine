# Быстрый старт MapAiMine

От нуля до «нейросеть построила деревню» — примерно 15 минут.

## Что понадобится

* Minecraft-сервер **Paper / Spigot / Purpur 1.20.6+** (проверено на 1.21.x), Java 21
* **Node.js 20+**
* Нейросеть с поддержкой MCP: Claude Code, Claude Desktop, Codex CLI, Antigravity,
  Cursor, Windsurf, Cline, VS Code Copilot, Gemini CLI — любая

Нет своего сервера? Подойдёт локальный Paper на своей машине:
скачай `paper-1.21.x.jar` с papermc.io, положи в отдельную папку, запусти
`java -Xmx4G -jar paper.jar nogui`, прими EULA в `eula.txt`.

---

## Шаг 1. Плагин-мост

```bash
git clone https://github.com/thecolorisblack/MapAiMine
cd MapAiMine/plugin
mvn -B package
cp target/mapaimine-bridge-0.1.0.jar /путь/к/серверу/plugins/
```

Не хочешь собирать — возьми готовый `.jar` из
[GitHub Releases](https://github.com/thecolorisblack/MapAiMine/releases)
или из артефактов сборки в Actions.

Перезапусти сервер. В логе появится примерно такое:

```
[MapAiMine] Bridge listening on http://127.0.0.1:25599
[MapAiMine] Token: 7f3a9c1e0b5d4a28...   (скопируй его)
```

Токен также лежит в `plugins/MapAiMine/config.yml`. Там же можно поменять порт,
лимиты и отключить лишние возможности.

> **Если плагин поставить нельзя** — пропусти этот шаг и включи RCON в `server.properties`:
> `enable-rcon=true`, `rcon.port=25575`, `rcon.password=...`.
> MapAiMine будет строить, но не сможет «видеть» мир и откатывать. Подробности —
> `docs/PROTOCOL.md` §3.

## Шаг 2. MCP-сервер

```bash
cd ../mcp-server
npm install
npm run build
```

Проверка связи:

```bash
MAPAIMINE_TOKEN=твой_токен npm run doctor
```

Ожидаемо:
```
✔ Подключено: HTTP bridge http://127.0.0.1:25599 — MapAiMine 0.1.0 on Paper 1.21.4
  возможности: survey, undo, jobs, worldCreate, schematic, entities, containers, commands
  миры: world, world_nether, world_the_end
```

Если не подключается — `doctor` печатает, что именно проверить.

## Шаг 3. Подключить нейросеть

**Claude Code:**
```bash
claude mcp add mapaimine \
  --env MAPAIMINE_TOKEN=твой_токен \
  -- node /абсолютный/путь/MapAiMine/mcp-server/dist/index.js

# скилл — чтобы модель понимала, в каком она режиме
cp -r /абсолютный/путь/MapAiMine/skills/mapaimine ~/.claude/skills/
```

Остальные клиенты (Codex, Antigravity, Cursor, Windsurf, Cline, VS Code, Gemini) —
готовые конфиги в [AGENT_SETUP.md](AGENT_SETUP.md).

## Шаг 4. Первая стройка

Напиши нейросети:

> Проверь связь с Minecraft, покажи доступные стили и разведай участок 100×100 вокруг спавна.

Затем:

> Сделай там средневековую деревню 96×96, назови «Ольхово», и озелени всё вокруг.

Модель сама пройдёт цепочку: `mapaimine_status` → `survey_area` → `generate_settlement`
→ `decorate_area` → `configure_world`. В игре стройка идёт фоном, без лагов.

Не понравилось:

> Откати и сделай то же самое в зимнем стиле.

---

## Что попробовать дальше

**Выбрать вайб глазами:**
> Построй один и тот же дом в стилях medieval, winter, japanese, autumn и fantasy_elven, рядом, с табличками.

**Спавн сервера:**
> Сделай спавн: зимняя деревня 120×120 в точке 0,0, поставь точку спавна на площади,
> зафиксируй утро и ясную погоду, отключи мобов и поставь границу мира 500 блоков.

**Отдельный мир под стройку:**
> Создай пустой мир buildworld и построй там замок.

**Замок на горе:**
> Найди рядом со мной холм повыше, выровняй вершину и поставь замок с крепостной стеной.

**Скопировать свою постройку:**
> Сними мою постройку с координат … по … в файл «my_house» и поставь пять её копий вдоль дороги.

---

## Типичные проблемы

| Симптом | Причина и решение |
|---|---|
| `doctor` пишет «Cannot reach the bridge» | сервер не запущен, плагин не загрузился, или порт занят — проверь лог сервера |
| `401 UNAUTHORIZED` | не тот токен: возьми свежий из `plugins/MapAiMine/config.yml` |
| Модель не видит инструменты | MCP-сервер не подключён к клиенту — проверь путь до `dist/index.js` и перезапусти клиент |
| «Feature … not available on the current transport» | ты в режиме RCON, нужен плагин |
| Постройка висит в воздухе | указан неверный `y` — попроси модель взять `suggestedBuildY` из `survey_area` |
| Сервер лагает при стройке | уменьши `blocksPerTick` в `plugins/MapAiMine/config.yml` |
| Хочу свои стили | скопируй JSON из `content/styles/`, поправь блоки, проверь `npm run validate:content` |
