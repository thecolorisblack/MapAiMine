---
name: mapaimine
description: Строительство и генерация миров Minecraft через MCP-сервер MapAiMine — карты, спавны, деревни, города, замки, отдельные здания в готовых стилях (средневековье, зима, весна, лето, осень, япония, пустыня, эльфы, стимпанк, модерн). Используй, когда пользователь просит построить, сгенерировать, оформить или спроектировать что-либо в Minecraft, настроить спавн сервера, создать мир или изменить рельеф. Триггеры — «построй», «сгенерируй деревню», «сделай спавн», «зимний вайб», «замок», «minecraft», «карта для сервера».
---

# MapAiMine — режим билдера Minecraft

Ты подключён к живому Minecraft-серверу через MCP-сервер `mapaimine`.
Вызовы инструментов **реально меняют мир** на сервере пользователя. Работай как архитектор,
а не как генератор текста: сначала смотри участок, потом планируй, потом строй, потом проверяй.

## Как понять, что ты в этом режиме
Если среди инструментов есть `mapaimine_status`, `build_blueprint`, `generate_settlement` — да,
ты в режиме билдера. Первым делом вызови `mapaimine_status`.

## Обязательный порядок действий

### 1. Осмотреться
```
mapaimine_status
```
Смотри на транспорт:
* **плагин (http)** — доступно всё;
* **RCON** — НЕТ разведки, отмены, создания миров, покраски, рассева. Проси у пользователя
  явные координаты и Y, предупреди об ограничениях.

### 2. Подобрать стиль
```
list_styles          # каталог вайбов
get_style <id>       # если нужно понять палитру
```
Соответствие слов пользователя стилям:

| Пользователь говорит | style |
|---|---|
| средневековье, деревня, фэнтези-village | `medieval` |
| зима, снег, новый год, холодно | `winter` |
| весна, сакура, цветение, пастель | `spring` |
| лето, ярко, курорт, море | `summer` |
| осень, листопад, тыквы, урожай | `autumn` |
| викинги, север, скандинавия | `nordic` |
| япония, пагода, самураи, аниме | `japanese` |
| пустыня, египет, оазис, песок | `desert` |
| эльфы, магия, светящееся, лес | `fantasy_elven` |
| стимпанк, медь, шестерёнки, викторианство | `steampunk` |
| современность, город, стекло, минимализм | `modern` |

Не уверен, какой вайб хочет пользователь → `build_showcase` с 4–6 стилями и `teleport_player`.
Пусть выберет глазами.

### 3. Разведать место
```
survey_area { x1, z1, x2, z2 }
```
Используй из ответа:
* `suggestedBuildY` → это твой `y` для построек;
* `flatnessScore < 0.35` → сначала `terraform { action: "flatten" }` или другое место;
* `waterFraction` высокий → сдвинься или строй порт/мост;
* ASCII-карта высот → выбирай ровное «плато».

**Никогда не строй вслепую**, если `survey_area` доступна.

### 4. Согласовать план
Одним абзацем: где, что, размер, стиль, ориентировочный объём.
Для поселений сначала `plan_settlement` — вернёт карту застройки сверху в ASCII и состав
построек, **не меняя мир**. Понравилось — вызывай `generate_settlement` с теми же
параметрами и тем же `seed`. Для остальных строек есть `dryRun: true`.

Ориентиры:
| Задача | Инструмент | Параметры |
|---|---|---|
| одно здание | `build_blueprint` | размер из `list_blueprints` |
| деревня | `generate_settlement` | `size: 96–128`, `kind: "village"` |
| город | `generate_settlement` | `size: 160–220`, `kind: "town"`, `wall: true` |
| замок | `generate_settlement` | `size: 96–128`, `kind: "castle"` |
| ферма / лагерь / порт | `generate_settlement` | `kind: "farmstead" / "camp" / "harbor"` |
| пейзаж вокруг | `decorate_area` | тот же прямоугольник |

### 5. Построить
Хороший порядок:
1. `terraform` — выровнять площадку (если нужно);
2. `generate_settlement` **или** серия `build_blueprint`;
3. `decorate_area` — трава, цветы, деревья, снег. **Не пропускай**: именно это отличает
   «коробки на плоскости» от живой локации;
4. `configure_world { applyStyleEnvironment: "<style>" }` — время и погода под вайб;
5. `set_spawn` — если это спавн сервера;
6. `place_sign` / `spawn_entity` — таблички, жители;
7. `teleport_player` — показать результат.

### 6. Проверить и при необходимости откатить
* `job_status` — прогресс фоновой стройки;
* не понравилось → `undo_last` (если в отчёте несколько undo-id — вызови столько же раз);
* тот же `seed` даёт тот же результат; меняй `seed`, чтобы «перебросить кости».

## Правила
* Не ставь блоки поштучно через `place_blocks`, если есть `fill_region`, `draw_shape`,
  `build_blueprint` или `generate_settlement`.
* Не выдумывай id блоков — `validate_blocks` / `list_materials`.
* Не строй поверх чужих построек — сначала `survey_area` / `probe_blocks`.
* На людном сервере предупреждай через `broadcast_message`.
* Стройки идут по тик-бюджету и не лагают сервер — но не запускай десяток крупных задач разом.
* `run_command` — только когда специализированного инструмента нет: команды **не откатываются**.

## Готовые сценарии

**Спавн сервера, зимний вайб**
```
mapaimine_status
survey_area { x1: -80, z1: -80, x2: 80, z2: 80 }
generate_settlement { centerX: 0, centerZ: 0, size: 112, style: "winter",
                      kind: "village", name: "Зимний спавн" }
decorate_area { from: [-80,-80], to: [80,80], style: "winter" }
configure_world { applyStyleEnvironment: "winter", difficulty: "peaceful",
                  gameRules: { doMobSpawning: false, mobGriefing: false } }
set_spawn { x: 0, z: 0 }
set_world_border { centerX: 0, centerZ: 0, size: 400 }
broadcast_message { message: "Новый спавн готов!" }
```

**Отдельный мир-холст под стройку**
```
create_world { name: "buildworld", worldType: "void" }
configure_world { world: "buildworld", time: 6000, timeLock: true, weather: "clear", weatherLock: true }
build_blueprint { world: "buildworld", blueprint: "keep", style: "medieval", x: 0, y: 64, z: 0 }
```

**Замок на горе**
```
survey_area вокруг горы → найти вершину и её Y
terraform { action: "flatten", from: [...], to: [...], y: <вершина> }
generate_settlement { kind: "castle", style: "medieval", wall: true, y: <вершина> }
```

**Сравнить вайбы**
```
build_showcase { x, z, styles: ["medieval","winter","japanese","fantasy_elven"] }
teleport_player { player: "<ник>", x, z }
```

## Если что-то пошло не так
| Симптом | Что делать |
|---|---|
| «Нет связи с Minecraft-сервером» | попросить запустить сервер, проверить `MAPAIMINE_TOKEN` / `MAPAIMINE_BRIDGE_URL`, затем `reconnect` |
| «Feature … not available on the current transport» | это RCON-режим: нужен плагин, либо обойтись явными координатами |
| постройка висит в воздухе / утонула | неверный `y`; взять `suggestedBuildY` из `survey_area` |
| дома стоят, но пусто и голо | забыл `decorate_area` |
| стиль не подошёл | `undo_last`, затем тот же вызов с другим `style` |
| «превышает лимит блоков» | разбить на этапы или поднять `MAPAIMINE_MAX_BLOCKS` |

Подробный регламент доступен и внутри сервера: инструмент `mapaimine_guide`.
