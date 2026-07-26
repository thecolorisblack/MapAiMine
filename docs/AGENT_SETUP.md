# Подключение MapAiMine к нейросетям

MapAiMine — обычный MCP-сервер (stdio). Подключается ко всему, что умеет MCP.
Ниже — готовые конфиги. Везде подставь свой токен из `plugins/MapAiMine/config.yml`
и путь до репозитория.

Общие переменные окружения:

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `MAPAIMINE_BRIDGE_URL` | `http://127.0.0.1:25599` | адрес плагина |
| `MAPAIMINE_TOKEN` | — | токен из конфига плагина (**обязателен**) |
| `MAPAIMINE_TRANSPORT` | `auto` | `auto` / `http` / `rcon` |
| `MAPAIMINE_RCON_HOST` / `_PORT` / `_PASSWORD` | `127.0.0.1` / `25575` / — | резервный режим без плагина |
| `MAPAIMINE_DEFAULT_WORLD` | первый мир сервера | в каком мире строить по умолчанию |
| `MAPAIMINE_CONTENT_DIR` | `<репозиторий>/content` | библиотека стилей и построек |
| `MAPAIMINE_EXTRA_CONTENT` | — | доп. каталоги со своими стилями (через `:`) |
| `MAPAIMINE_MAX_BLOCKS` | `4000000` | предохранитель на один вызов |
| `MAPAIMINE_LANG` | `ru` | язык табличек и ответов (`ru` / `en`) |
| `MAPAIMINE_READ_ONLY` | `0` | `1` — запретить любые изменения мира |
| `MAPAIMINE_DEBUG` | `0` | подробный лог в stderr |

Проверить настройку до подключения к ИИ:
```bash
cd mcp-server && npm run doctor
```

---

## Claude Code (CLI)

```bash
claude mcp add mapaimine \
  --env MAPAIMINE_TOKEN=ВАШ_ТОКЕН \
  --env MAPAIMINE_BRIDGE_URL=http://127.0.0.1:25599 \
  -- node /путь/к/MapAiMine/mcp-server/dist/index.js
```
Или файлом `.mcp.json` в корне проекта:
```json
{
  "mcpServers": {
    "mapaimine": {
      "command": "node",
      "args": ["/путь/к/MapAiMine/mcp-server/dist/index.js"],
      "env": {
        "MAPAIMINE_TOKEN": "ВАШ_ТОКЕН",
        "MAPAIMINE_BRIDGE_URL": "http://127.0.0.1:25599"
      }
    }
  }
}
```
Плюс поставь скилл: `cp -r skills/mapaimine ~/.claude/skills/`.

## Claude Desktop

`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS)
или `%APPDATA%\Claude\claude_desktop_config.json` (Windows) — тот же JSON, что выше.

## OpenAI Codex CLI

`~/.codex/config.toml`:
```toml
[mcp_servers.mapaimine]
command = "node"
args = ["/путь/к/MapAiMine/mcp-server/dist/index.js"]

[mcp_servers.mapaimine.env]
MAPAIMINE_TOKEN = "ВАШ_ТОКЕН"
MAPAIMINE_BRIDGE_URL = "http://127.0.0.1:25599"
```
Правила поведения — в `AGENTS.md` проекта (см. `skills/README.md`).

## Google Antigravity / Gemini CLI

Antigravity: *Settings → MCP Servers → Add* — команда `node`, аргумент — путь до
`dist/index.js`, переменные окружения как выше. Правила — *Settings → Rules*.

Gemini CLI, `~/.gemini/settings.json`:
```json
{
  "mcpServers": {
    "mapaimine": {
      "command": "node",
      "args": ["/путь/к/MapAiMine/mcp-server/dist/index.js"],
      "env": { "MAPAIMINE_TOKEN": "ВАШ_ТОКЕН" }
    }
  }
}
```

## Cursor

`.cursor/mcp.json` в проекте (или `~/.cursor/mcp.json` глобально) — формат как у Claude Desktop.
Правила — `.cursor/rules/mapaimine.mdc`.

## Windsurf

`~/.codeium/windsurf/mcp_config.json` — тот же формат.

## Cline / Roo Code (VS Code)

Настройки расширения → *MCP Servers* → *Configure* → тот же JSON.

## VS Code (GitHub Copilot, agent mode)

`.vscode/mcp.json`:
```json
{
  "servers": {
    "mapaimine": {
      "type": "stdio",
      "command": "node",
      "args": ["/путь/к/MapAiMine/mcp-server/dist/index.js"],
      "env": { "MAPAIMINE_TOKEN": "ВАШ_ТОКЕН" }
    }
  }
}
```

## JetBrains AI Assistant / Junie

*Settings → Tools → AI Assistant → MCP* → добавить stdio-сервер с той же командой.

## Свой агент (Python / TypeScript)

```python
# pip install mcp
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

params = StdioServerParameters(
    command="node",
    args=["/путь/к/MapAiMine/mcp-server/dist/index.js"],
    env={"MAPAIMINE_TOKEN": "ВАШ_ТОКЕН"},
)
```
Системный промт возьми из `skills/prompts/universal-system-prompt.md`.

---

## Запуск без сборки

Для разработки можно не собирать TypeScript:
```json
{ "command": "npx", "args": ["tsx", "/путь/к/MapAiMine/mcp-server/src/index.ts"] }
```

## Если сервер не подключается

MCP-сервер стартует **даже без связи с Minecraft** — он не падает, а честно сообщает
о проблеме. Спроси у модели `mapaimine_status`, там будет диагноз. После запуска
Minecraft-сервера вызови `reconnect` — перезапускать ИИ-клиент не нужно.
