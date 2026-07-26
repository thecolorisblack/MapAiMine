# MapAiMine Bridge Protocol v1

Контракт между **MCP-сервером** (Node/TypeScript, говорит с нейросетью) и **плагином**
(Java/Paper, живёт внутри Minecraft-сервера).

Всё, что требует творчества и геометрии, делает MCP-сервер.
Плагин — «тупой, но быстрый» исполнитель примитивов + глаза (чтение мира).

```
LLM ──MCP(stdio)──> mcp-server ──HTTP/JSON──> MapAiMineBridge (plugin) ──> Bukkit World
                         └────────RCON (fallback, урезанный режим)────────┘
```

---

## 1. Транспорт

* HTTP/1.1, JSON (UTF-8).
* База: `http://<host>:<port>/api/v1`
* По умолчанию слушает `127.0.0.1:25599`.
* Авторизация: заголовок `Authorization: Bearer <token>` **или** `X-MapAiMine-Token: <token>`.
  Токен генерируется плагином при первом запуске и пишется в `plugins/MapAiMine/config.yml`.
* Любой запрос без валидного токена → `401`.

### Формат ответа

Успех:
```json
{ "ok": true, "data": { } }
```
Ошибка:
```json
{ "ok": false, "error": { "code": "BAD_BLOCK", "message": "Unknown block id: minecraft:oak_plank", "hint": "did you mean minecraft:oak_planks?" } }
```

Коды ошибок: `UNAUTHORIZED`, `BAD_REQUEST`, `BAD_BLOCK`, `NO_WORLD`, `LIMIT_EXCEEDED`,
`REGION_PROTECTED`, `JOB_NOT_FOUND`, `UNDO_EMPTY`, `DISABLED`, `INTERNAL`.

HTTP-коды: 200 ok, 202 job принят, 400, 401, 403, 404, 409, 413 (слишком большая операция), 500.

### Общие типы

| Тип | JSON | Пример |
|---|---|---|
| `Pos` | `[x, y, z]` целые | `[100, 64, -220]` |
| `Pos2` | `[x, z]` | `[100, -220]` |
| `Block` | строка block-state | `"minecraft:oak_stairs[facing=north,half=bottom]"` |
| `BlockRef` | `Block` \| `{"choices":[{"block":Block,"weight":n}]}` | взвешенный выбор |

* Префикс `minecraft:` можно опускать — плагин добавит.
* Пустой блок = `"minecraft:air"`.
* Тег блоков в фильтрах: `"#minecraft:logs"`.

---

## 2. Эндпоинты

### `GET /health`
Без авторизации отдаёт только `{ok, data:{protocol, plugin}}`; с токеном — полностью.

```json
{ "ok": true, "data": {
  "protocol": 1,
  "plugin": "MapAiMine 0.1.0",
  "server": "Paper",
  "minecraftVersion": "1.21.4",
  "worlds": ["world", "world_nether", "world_the_end"],
  "capabilities": ["survey","undo","jobs","worldCreate","schematic","entities","containers","commands"],
  "limits": {
    "maxOpsPerRequest": 4096,
    "maxBlocksPerOp": 4000000,
    "maxRegionVolume": 8000000,
    "blocksPerTick": 20000,
    "maxUndoHistory": 20,
    "surveyMaxPoints": 16384
  }
} }
```

`capabilities` — какие фичи включены в конфиге. MCP-сервер обязан их уважать.

---

### `GET /worlds`
```json
{ "ok": true, "data": { "worlds": [
  { "name":"world", "environment":"NORMAL", "seed":123456789, "worldType":"NORMAL",
    "spawn":[0,64,0], "time":1200, "weather":"clear", "difficulty":"NORMAL",
    "players":2, "loadedChunks":441, "minY":-64, "maxY":320 }
] } }
```

### `POST /worlds`
Создать и загрузить новый мир.
```json
{ "name":"aicity", "environment":"normal", "worldType":"flat",
  "seed": 42, "generateStructures": false,
  "flatPreset": "minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains",
  "biome": "minecraft:plains" }
```
* `environment`: `normal|nether|the_end`
* `worldType`: `normal|flat|large_biomes|amplified|void`
* `void` — плоский мир из воздуха (используется как чистый холст для билдов).

Ответ: объект мира как в `/worlds`. Операция долгая — выполняется синхронно, до 60 c.

### `POST /worlds/{name}/settings`
Все поля опциональны.
```json
{ "time": 6000, "timeLock": true, "weather":"clear", "weatherLock": true,
  "difficulty":"peaceful", "pvp": false, "spawnRadius": 8,
  "gameRules": { "doMobSpawning": false, "mobGriefing": false, "doFireTick": false },
  "keepLoaded": true }
```
`keepLoaded: true` — держать спавн-чанки загруженными (нужно для долгих строек).

### `POST /worlds/{name}/spawn`
```json
{ "x":100, "y":72, "z":-40, "yaw":180, "pitch":0, "safe": true }
```
`safe:true` — плагин поднимет Y до первой безопасной точки и поставит платформу, если под точкой пусто.

### `POST /worlds/{name}/border`
```json
{ "center":[0,0], "size": 2000, "warningDistance": 16 }
```

---

### `GET /survey`
**Глаза нейросети.** Возвращает даунсэмплированную карту участка.

Query: `world`, `x1`, `z1`, `x2`, `z2`, `step` (по умолчанию подбирается так, чтобы точек было ≤ `surveyMaxPoints`), `include` (csv из `height,surface,biome,water,light`, по умолчанию `height,surface,biome`).

```json
{ "ok": true, "data": {
  "world":"world", "origin":[100,-200], "size":[64,64], "step":2, "cols":32, "rows":32,
  "heightmap": [[71,71,72, ...], ...],
  "surface":   [["grass_block","grass_block","stone", ...], ...],
  "biome":     [["plains","plains","forest", ...], ...],
  "water":     [[0,0,1, ...], ...],
  "legend": { "note":"rows идут по Z, колонки по X, значения — от origin с шагом step" },
  "stats": { "minY":63, "maxY":98, "avgY":74.2, "slopeMax":9, "waterFraction":0.12,
             "surfaceHistogram": {"grass_block":712,"stone":180,"water":132},
             "biomeHistogram": {"plains":600,"forest":424},
             "flatnessScore": 0.62,
             "suggestedBuildY": 74 }
} }
```
`heightmap[row][col]` — Y верхнего непрозрачного блока (`getHighestBlockYAt`, игнорируя листву/воду при `include=height`).
`flatnessScore` 0..1 — насколько участок ровный (1 = идеально плоско).

### `POST /probe`
Точечное чтение.
```json
{ "world":"world", "points": [[10,64,10],[11,64,10]] }
```
```json
{ "ok": true, "data": { "blocks": [
  {"pos":[10,64,10],"block":"minecraft:grass_block[snowy=false]","light":15,"biome":"plains"},
  {"pos":[11,64,10],"block":"minecraft:air","light":15,"biome":"plains"} ] } }
```
Максимум 1024 точки.

### `GET /materials`
```json
{ "ok": true, "data": { "count": 1084, "blocks": ["minecraft:acacia_button", ...] } }
```
Query `filter=` — подстрока, `solidOnly=true`. Нужен, чтобы модель не изобретала несуществующие блоки.

### `POST /validate`
```json
{ "blocks": ["minecraft:oak_planks", "minecraft:oak_plank", "cherry_stairs[facing=north]"] }
```
```json
{ "ok": true, "data": { "results": [
  {"input":"minecraft:oak_planks","valid":true,"normalized":"minecraft:oak_planks"},
  {"input":"minecraft:oak_plank","valid":false,"suggestions":["minecraft:oak_planks"]},
  {"input":"cherry_stairs[facing=north]","valid":true,"normalized":"minecraft:cherry_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"} ] } }
```

---

### `POST /ops` — главный рабочий эндпоинт

```json
{
  "world": "world",
  "label": "Средневековая деревня — фаза 1: дороги",
  "undo": true,
  "dryRun": false,
  "async": true,
  "physics": false,
  "ops": [ /* см. ниже */ ]
}
```

| Поле | По умолчанию | Смысл |
|---|---|---|
| `undo` | `true` | сохранить снимок для отката |
| `dryRun` | `false` | ничего не менять, вернуть только статистику и предупреждения |
| `async` | `true` | вернуть `202` + `jobId` и выполнять по тик-бюджету |
| `physics` | `false` | применять физику блоков (падение песка, вода). Для строек — `false` |
| `lightUpdate` | `true` | пересчитать освещение по завершении |

Ответ (async):
```json
{ "ok": true, "data": { "jobId":"j_7f3a", "status":"queued", "estimatedBlocks": 18400, "warnings": [] } }
```
Ответ (sync, `async:false` — разрешено только если `estimatedBlocks <= 200000`):
```json
{ "ok": true, "data": { "jobId":"j_7f3a", "status":"done", "blocksChanged": 18342,
  "undoId":"u_12", "elapsedMs": 640, "warnings": ["op[4]: 12 blocks were outside world height, skipped"] } }
```

#### Типы операций

Все координаты — абсолютные. Регионы `from`/`to` включительные, порядок углов любой.

```jsonc
// 1. один блок
{ "type":"set", "pos":[10,64,10], "block":"minecraft:stone" }

// 2. массив блоков (самый частый способ поставить блюпринт)
{ "type":"blocks", "blocks":[ {"pos":[10,64,10],"block":"minecraft:oak_planks"}, ... ] }

// 2b. компактная форма: общий origin + плоский список
{ "type":"blocks", "origin":[10,64,10],
  "palette":["minecraft:air","minecraft:oak_planks","minecraft:cobblestone"],
  "size":[5,4,7],
  "data":"0,0,1,1,2,..." }   // индексы палитры, порядок y→z→x, RLE: "3x1" = три раза индекс 1

// 3. заливка региона
{ "type":"fill", "from":[0,64,0], "to":[15,64,15], "block":"minecraft:stone_bricks",
  "mode":"replace",           // replace | keep(только в воздух) | hollow | outline | destroy
  "filter":["minecraft:air","#minecraft:leaves"] }   // менять только эти блоки

// 4. примитивы
{ "type":"sphere",   "center":[0,80,0], "radius":8,          "block":"...", "hollow":true }
{ "type":"sphere",   "center":[0,80,0], "radius":[10,4,10],  "block":"...", "hollow":false }  // эллипсоид
{ "type":"cylinder", "base":[0,64,0], "radius":5, "height":12, "axis":"y", "block":"...", "hollow":true }
{ "type":"pyramid",  "base":[0,64,0], "size":9, "block":"...", "hollow":false, "inverted":false }
{ "type":"cone",     "base":[0,64,0], "radius":6, "height":10, "block":"...", "hollow":true }
{ "type":"line",     "from":[0,64,0], "to":[30,70,12], "block":"...", "thickness":2 }
{ "type":"walls",    "from":[0,64,0], "to":[10,70,10], "block":"..." }          // 4 стены без пола/потолка
{ "type":"torus",    "center":[0,80,0], "radius":12, "tube":3, "block":"..." }

// 5. замена
{ "type":"replace", "from":[..], "to":[..], "find":["minecraft:grass_block"], "block":"minecraft:podzol" }

// 6. покраска поверхности (верхний непрозрачный блок в колонке)
{ "type":"paint", "from":[0,0,0], "to":[63,0,63], "block":{"choices":[
      {"block":"minecraft:grass_block","weight":8},{"block":"minecraft:podzol","weight":2}]},
  "depth":1, "onlyOn":["#minecraft:dirt"], "seed":1234 }
// Y в from/to игнорируется, работа идёт по колонкам.

// 7. рассев объектов (деревья, цветы, камни, фонари)
{ "type":"scatter", "from":[0,0,0], "to":[63,0,63], "density":0.04, "seed":99,
  "minSpacing":2,
  "entries":[
    {"weight":6, "block":"minecraft:poppy"},
    {"weight":3, "structure":{ "size":[1,2,1], "palette":["minecraft:air","minecraft:sugar_cane"], "data":"1,1" }},
    {"weight":1, "tree":"OAK"}          // тип из Bukkit TreeType, если capability "trees"
  ],
  "onlyOn":["minecraft:grass_block","minecraft:dirt"], "needsAir":true, "avoidWater":true }

// 8. рельеф
{ "type":"smooth",  "from":[..], "to":[..], "iterations":2, "strength":1.0 }
{ "type":"flatten", "from":[..], "to":[..], "y":72, "surface":"minecraft:grass_block",
  "fill":"minecraft:dirt", "clearAbove":8 }
{ "type":"raise",   "from":[..], "to":[..], "amount":4, "falloff":"smooth" }
{ "type":"terrace", "from":[..], "to":[..], "step":3 }
{ "type":"clear",   "from":[..], "to":[..], "keepGround":true }   // всё в воздух

// 9. блок-сущности
{ "type":"sign", "pos":[..], "block":"minecraft:oak_wall_sign[facing=north]",
  "front":["Таверна","«Три Кирки»"], "back":[], "glowing":true, "color":"black" }
{ "type":"container", "pos":[..], "block":"minecraft:chest[facing=north]",
  "items":[ {"slot":0,"id":"minecraft:bread","count":5},
            {"slot":13,"id":"minecraft:iron_sword","count":1,"name":"Меч стражника","enchants":{"sharpness":2}} ],
  "lootTable":"minecraft:chests/village/village_weaponsmith" }
{ "type":"head", "pos":[..], "texture":"<base64>", "owner":"Notch" }
{ "type":"spawner", "pos":[..], "entity":"minecraft:zombie", "delay":200, "maxNearby":4 }

// 10. сущности
{ "type":"entity", "pos":[10.5,65,10.5], "entity":"minecraft:villager",
  "name":"Кузнец", "nameVisible":true, "profession":"weaponsmith", "noAI":false,
  "persistent":true, "tags":["mapaimine"], "snbt":"{Silent:1b}" }

// 11. escape hatch (только если capability "commands")
{ "type":"command", "command":"gamerule doDaylightCycle false" }

// 12. ожидание/маркер фазы (для async-джоб, чтобы прогресс был читаемым)
{ "type":"checkpoint", "name":"дороги готовы" }
```

#### Порядок исполнения
Операции выполняются строго последовательно. Внутри `blocks` порядок — как в массиве
(важно для дверей/кроватей: сначала нижняя половина, потом верхняя).

#### Правила безопасности плагина
* Суммарный объём региона операции > `maxRegionVolume` → `413 LIMIT_EXCEEDED`.
* Координаты вне `[minY, maxY]` мира — блок пропускается, в `warnings` попадает счётчик.
* Если в конфиге заданы защищённые регионы/миры — `403 REGION_PROTECTED`.
* `dryRun` возвращает те же `warnings` + `estimatedBlocks`, ничего не меняя.

---

### `GET /jobs` / `GET /jobs/{id}`
```json
{ "ok": true, "data": {
  "jobId":"j_7f3a", "label":"Средневековая деревня — фаза 1",
  "status":"running",          // queued | running | done | failed | cancelled
  "progress": { "opsDone": 12, "opsTotal": 40, "blocksChanged": 8400, "estimatedBlocks": 18400,
                "percent": 45.6, "checkpoint":"дороги готовы" },
  "undoId":"u_12", "startedAt": 1730000000000, "elapsedMs": 3400, "etaMs": 4100,
  "warnings": [], "error": null } }
```
`DELETE /jobs/{id}` — отменить (уже поставленные блоки остаются, но `undoId` валиден).

### `POST /undo`
```json
{ "undoId": "u_12" }
```
Без `undoId` — откатывает последнюю операцию. Ответ: `{ "blocksRestored": 18342 }`.
`GET /undo` — история: `[{undoId, label, blocks, at}]`.

---

### `POST /capture` — снять постройку игрока в блюпринт
```json
{ "world":"world", "from":[0,64,0], "to":[20,80,20], "name":"my_castle", "trimAir": true }
```
Ответ — объект `RawStructure` (см. `SCHEMAS.md`), плюс сохранение в `plugins/MapAiMine/captures/<name>.json`.

### `GET /captures` / `GET /captures/{name}`

---

### Игроки и чат
```
POST /players/{name}/teleport   { "world":"world", "x":.., "y":.., "z":.., "yaw":0, "pitch":0 }
POST /players/{name}/gamemode   { "mode":"creative" }
POST /broadcast                 { "message":"§6[MapAiMine] §fДеревня построена!", "toOps": false }
GET  /players
```

---

## 3. RCON-режим (fallback без плагина)

MCP-сервер умеет работать через RCON — тогда плагин не нужен, подходит для ванильных
серверов, но часть возможностей недоступна.

| Возможность | Плагин | RCON |
|---|:--:|:--:|
| `set`, `fill`, `walls`, примитивы | ✅ | ✅ (разворачиваются в `/setblock` и `/fill` по 32768 блоков) |
| `blocks` (блюпринты) | ✅ | ✅ (медленно: пакеты `/setblock`) |
| `sign`, `container`, `entity`, `command` | ✅ | ✅ |
| Спавн мира, время, погода, gamerules | ✅ | ✅ |
| `survey`, `probe`, `/materials` | ✅ | ❌ (мир «вслепую») |
| `undo` | ✅ | ❌ |
| `paint`, `scatter`, `smooth`, `flatten` | ✅ (на сервере) | ⚠️ считается на клиенте, требует `survey` → недоступно |
| Создание миров | ✅ | ❌ |
| `capture` | ✅ | ❌ |
| Асинхронные джобы / тик-бюджет | ✅ | ⚠️ эмулируется троттлингом |

MCP-сервер в RCON-режиме честно сообщает об урезанных возможностях в `mapaimine_status`
и отказывает в неподдерживаемых инструментах с понятным объяснением.

---

## 4. Версионирование
`protocol: 1`. Плагин обязан отвергать запросы с неизвестными `type` операций
(`BAD_REQUEST` с перечислением поддерживаемых), а MCP-сервер — проверять `protocol`
и `capabilities` на старте и адаптироваться.
