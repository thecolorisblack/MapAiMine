# Скилл MapAiMine для нейросетей

Скилл объясняет модели, **в каком она режиме и что от неё хотят**: что подключён живой
Minecraft-сервер, в каком порядке действовать и каких ошибок не делать.
Без него модель тоже работает, но чаще строит вслепую и забывает про озеленение.

```
skills/
├─ mapaimine/SKILL.md              ← скилл (формат Claude Code / Agent Skills)
└─ prompts/universal-system-prompt.md ← то же самое как системный промт для любой другой ИИ
```

## Claude Code / Claude Desktop / Claude Agent SDK

```bash
mkdir -p ~/.claude/skills
cp -r skills/mapaimine ~/.claude/skills/
```
Либо для одного проекта — положи в `<проект>/.claude/skills/mapaimine/`.
Проверить: `/skills` в Claude Code, скилл `mapaimine` должен быть в списке.

Скилл активируется сам, когда пользователь пишет «построй», «сгенерируй деревню»,
«сделай спавн», «зимний вайб» и т.п. Вызвать вручную — `/mapaimine`.

## OpenAI Codex CLI

Codex читает `AGENTS.md` из корня проекта:
```bash
cat skills/prompts/universal-system-prompt.md >> AGENTS.md
```
MCP-сервер прописывается в `~/.codex/config.toml` — см. `docs/AGENT_SETUP.md`.

## Google Antigravity / Gemini CLI

Положи текст из `skills/prompts/universal-system-prompt.md` в правила агента
(Antigravity: *Settings → Rules*; Gemini CLI: `GEMINI.md` в корне проекта).

## Cursor / Windsurf / Cline / Continue / VS Code Copilot

* Cursor: `.cursor/rules/mapaimine.mdc` — вставь текст промта.
* Windsurf: `.windsurfrules`.
* Cline / Continue: «Custom instructions» в настройках.
* VS Code Copilot: `.github/copilot-instructions.md`.

Во всех случаях MCP-сервер подключается отдельно (см. `docs/AGENT_SETUP.md`) —
скилл только объясняет модели, как им пользоваться.

## Свой агент на API

Возьми `skills/prompts/universal-system-prompt.md` как system prompt, подключи MCP-сервер
как обычный MCP-транспорт. Внутри самого сервера есть инструмент `mapaimine_guide` —
модель может прочитать регламент прямо во время работы, если системный промт урезан.
