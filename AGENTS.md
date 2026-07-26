# AGENTS.md — для ИИ-агентов, которые правят этот репозиторий

> Это инструкции по **разработке MapAiMine**. Если ты подключён к серверу через MCP и должен
> что-то **построить в Minecraft** — тебе нужен `skills/prompts/universal-system-prompt.md`.

## Структура

```
plugin/       Java (Maven), Paper API 1.21.4, Java 21 — HTTP-мост внутри Minecraft-сервера
mcp-server/   TypeScript (ESM, Node 20+) — MCP-сервер, генераторы, резолвер стилей
content/      JSON: styles/ (палитры), blueprints/ (постройки), props/ (мебель)
skills/       скилл и системный промт для нейросетей
docs/         PROTOCOL.md (контракт плагин↔MCP), SCHEMAS.md (форматы контента), TOOLS.md
```

## Команды

```bash
# плагин
cd plugin && mvn -B package            # → target/mapaimine-bridge-0.1.0.jar

# MCP-сервер
cd mcp-server
npm install
npm run typecheck
npm run build
npm run validate:content               # ОБЯЗАТЕЛЬНО после правок content/
npm run doctor                         # проверка связи с живым сервером
npm test

# против ЖИВОГО сервера (меняет мир — запускай на тестовом):
MAPAIMINE_TOKEN=... npm run e2e                     # прогон всех инструментов через MCP
MAPAIMINE_TOKEN=... node scripts/verify-blocks.mjs  # все блоки всех построек × стилей
```

## Правила

1. **`docs/PROTOCOL.md` — контракт.** Плагин и MCP-сервер пишутся по нему.
   Меняешь одну сторону — меняй документ и вторую сторону в том же PR.
2. **`docs/SCHEMAS.md` — формат контента.** Новые роли/варианты сначала в документ,
   потом в `content/` и в `src/content/types.ts`.
3. **Стили и постройки — это JSON, а не код.** Не хардкодь блоки в генераторах:
   всё через роли палитры (`wall_primary`, `roof_primary`, …).
4. **Никаких новых runtime-зависимостей** без веской причины: плагин — чистый Bukkit + JDK,
   MCP-сервер — только MCP SDK и zod.
5. **Главный поток сервера священен.** Всё, что трогает мир, идёт через `JobRunner`
   с тик-бюджетом. HTTP-обработчики не блокируют главный поток.
6. **Каждая запись в мир должна откатываться.** Если добавляешь операцию — добавь и
   запись снимка в `UndoManager`.
7. Комментарии в коде — на английском, тексты для пользователя и описания инструментов — на русском
   (язык ответов переключается `MAPAIMINE_LANG`).

## Частые задачи

**Добавить стиль:** один файл `content/styles/<id>.json` по `docs/SCHEMAS.md` §1,
затем `npm run validate:content`. Кода трогать не надо.

**Добавить постройку:** `content/blueprints/<id>.json` по §2. Следи за инвариантом:
слоёв ровно `size[1]`, строк в слое ровно `size[2]`, символов в строке ровно `size[0]`,
каждый символ есть в `legend`. `" "` — не трогать, `"."` — воздух.

**Добавить операцию протокола:** `docs/PROTOCOL.md` → `mcp-server/src/protocol.ts` →
класс операции в `plugin/.../ops/` → регистрация в `OpParser` → при необходимости
клиентский растеризатор в `mcp-server/src/build/raster.ts` (для RCON и dry-run).

**Добавить MCP-инструмент:** файл в `mcp-server/src/tools/`, регистрация в `tools/index.ts`,
строка в `docs/TOOLS.md`. Описание инструмента пиши так, чтобы модель поняла **когда**
его звать, а не только что он делает.
