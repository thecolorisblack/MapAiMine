# MapAiMine — форматы контента

Три формата, все — обычный JSON, все лежат в `content/`:

| Формат | Папка | Что это |
|---|---|---|
| **Style Pack** | `content/styles/*.json` | палитра + правила стиля («средневековье», «зима») |
| **Blueprint** | `content/blueprints/*.json` | постройка в виде ASCII-слоёв, без привязки к блокам |
| **RawStructure** | `content/captures/*.json` | сырой слепок региона (palette + RLE), результат `/capture` |

Ключевая идея: **блюпринт не знает про блоки**. Он оперирует *ролями*
(`wall_primary`, `roof_primary`, `window`…). Стиль подставляет реальные блоки.
Поэтому один дом «маленький жилой» строится и в средневековье, и в зимнем вайбе,
и в осеннем — три разных здания из одного файла.

---

## 1. Style Pack

```jsonc
{
  "id": "medieval",
  "name": { "ru": "Средневековье", "en": "Medieval" },
  "description": { "ru": "Камень, дуб, фахверк, черепица из тёмного дуба.", "en": "..." },
  "tags": ["medieval", "fantasy", "village"],
  "season": "summer",              // summer | autumn | winter | spring | none
  "author": "MapAiMine",
  "version": 1,

  // ── Палитра ────────────────────────────────────────────────────────────
  // Ключ = роль. Значение = материал.
  "materials": {
    "wall_primary": {
      "full": [                                  // взвешенный выбор, «шум» из нескольких блоков
        { "block": "minecraft:cobblestone",       "weight": 6 },
        { "block": "minecraft:mossy_cobblestone", "weight": 2 },
        { "block": "minecraft:stone_bricks",      "weight": 2 }
      ],
      "stairs": "minecraft:cobblestone_stairs",  // варианты — один блок (без веса)
      "slab":   "minecraft:cobblestone_slab",
      "wall":   "minecraft:cobblestone_wall"
    },
    "wall_secondary": { "full": [{ "block": "minecraft:oak_planks", "weight": 1 }],
                        "stairs":"minecraft:oak_stairs", "slab":"minecraft:oak_slab",
                        "fence":"minecraft:oak_fence", "door":"minecraft:oak_door",
                        "trapdoor":"minecraft:oak_trapdoor" },
    "roof_primary":  { "full": [{ "block": "minecraft:dark_oak_planks", "weight": 1 }],
                       "stairs":"minecraft:dark_oak_stairs", "slab":"minecraft:dark_oak_slab" },
    "beam":          { "full": [{ "block": "minecraft:dark_oak_log[axis=y]", "weight": 1 }],
                       "axisX":"minecraft:dark_oak_log[axis=x]",
                       "axisZ":"minecraft:dark_oak_log[axis=z]" },
    "window":        { "full": [{ "block": "minecraft:glass_pane", "weight": 1 }] },
    "light":         { "full": [{ "block": "minecraft:lantern", "weight": 1 }],
                       "hanging": "minecraft:lantern[hanging=true]" }
    // ... остальные роли
  },

  // ── Окружение и рельеф ─────────────────────────────────────────────────
  "environment": {
    "time": 6000,                    // фиксировать полдень
    "timeLock": false,
    "weather": "clear",
    "snowLayer": false,              // насыпать minecraft:snow поверх поверхности
    "biomePaint": null,              // "minecraft:snowy_taiga" — если задать, /paint красит и биом
    "fogTint": null
  },

  "ground": {
    "top":    [{ "block":"minecraft:grass_block", "weight": 8 },
               { "block":"minecraft:coarse_dirt",  "weight": 1 }],
    "under":  [{ "block":"minecraft:dirt", "weight": 1 }],
    "path":   [{ "block":"minecraft:dirt_path", "weight": 6 },
               { "block":"minecraft:cobblestone","weight": 3 },
               { "block":"minecraft:gravel",     "weight": 1 }],
    "pathEdge":[{ "block":"minecraft:cobblestone_slab", "weight": 1 }],
    "plaza":  [{ "block":"minecraft:stone_bricks", "weight": 6 },
               { "block":"minecraft:cracked_stone_bricks", "weight": 2 }]
  },

  // ── Растительность и мелочь для scatter ────────────────────────────────
  "flora": {
    "trees": [ { "tree": "OAK", "weight": 6 }, { "tree": "BIG_TREE", "weight": 2 },
               { "blueprint": "tree_medieval_oak", "weight": 1 } ],
    "treeDensity": 0.02,
    "ground": [ { "block":"minecraft:short_grass", "weight": 12 },
                { "block":"minecraft:poppy",       "weight": 2 },
                { "block":"minecraft:dandelion",   "weight": 2 } ],
    "groundDensity": 0.12,
    "rocks": [ { "blueprint": "rock_small", "weight": 1 } ],
    "rockDensity": 0.004
  },

  // ── Подсказки процедурным генераторам ──────────────────────────────────
  "building": {
    "roofShape": "gable",            // gable | hip | conical | flat | pagoda | dome
    "roofPitch": 1,                  // сколько блоков подъёма на блок ширины
    "wallStyle": "timber_frame",     // solid | timber_frame | stone_base | log_cabin
    "storyHeight": 4,
    "foundationDepth": 2,
    "overhang": 1,
    "chimney": true
  },

  "settlement": {
    "roadWidth": 3,
    "plazaSize": [13, 13],
    "plotPadding": 2,
    "density": 0.7,                  // 0..1 — насколько плотно занимать участки
    "wall": true,                    // обносить поселение стеной
    "wallHeight": 6,
    "lampSpacing": 8,
    "buildingMix": {                 // категория → вес при выборе постройки
      "house": 8, "shop": 3, "farm": 3, "tavern": 1, "smithy": 1,
      "well": 1, "church": 1, "watchtower": 2
    },
    "landmark": "church"             // что ставится в центре площади
  },

  "lighting": { "role": "light", "spacing": 8, "height": 4, "onPosts": true },

  // ── Тексты для табличек ────────────────────────────────────────────────
  "signs": {
    "tavern":  ["Таверна", "«Три Кирки»"],
    "smithy":  ["Кузница"],
    "shop":    ["Лавка"],
    "welcome": ["Добро пожаловать", "в %settlement%"]
  }
}
```

### Полный словарь ролей

Стиль **обязан** определить все роли из колонки «required» — иначе валидатор ругнётся.
Остальные при отсутствии наследуются по `fallback`.

| Роль | required | fallback | Назначение |
|---|:--:|---|---|
| `foundation` | ✅ | `wall_primary` | фундамент, цоколь |
| `wall_primary` | ✅ | — | основной материал стен |
| `wall_secondary` | ✅ | `wall_primary` | второй материал (верхние этажи, фахверк) |
| `wall_accent` | | `wall_secondary` | акценты, углы |
| `beam` | ✅ | `wall_accent` | балки, каркас, столбы |
| `pillar` | | `beam` | колонны |
| `floor` | ✅ | `wall_secondary` | пол внутри |
| `floor_accent` | | `floor` | ковры/узор пола |
| `ceiling` | | `floor` | потолок/перекрытие |
| `roof_primary` | ✅ | — | кровля |
| `roof_secondary` | | `roof_primary` | конёк, кромка |
| `roof_support` | | `beam` | стропила |
| `window` | ✅ | — | стекло |
| `window_frame` | | `wall_accent` | обрамление окна |
| `door` | ✅ | `wall_secondary` | дверь (нужен вариант `door`) |
| `gate` | | `door` | ворота |
| `fence` | ✅ | `wall_secondary` | забор (вариант `fence`) |
| `railing` | | `fence` | перила |
| `path_primary` | ✅ | — | дорожка |
| `path_secondary` | | `path_primary` | вкрапления в дорожку |
| `plaza` | | `path_primary` | площадь |
| `ground_top` | ✅ | — | верхний слой земли |
| `ground_under` | ✅ | — | под верхним слоем |
| `water` | | — | вода/лёд в декоре |
| `light` | ✅ | — | источник света |
| `light_ground` | | `light` | напольный/на столбе |
| `decor` | | `wall_accent` | горшки, бочки, книги |
| `banner` | | — | знамёна |
| `carpet` | | — | ковры |
| `plant` | | — | растения в интерьере |
| `crop` | | — | грядки |
| `log` | | `beam` | брёвна |
| `foliage` | | — | листва (для деревьев стиля) |
| `snow` | | — | снег/лёд (зимние стили) |

### Варианты материала

| Ключ | Что подставляется |
|---|---|
| `full` | обычный полный блок (взвешенный список) |
| `stairs` | ступени → плагину уходит `[facing=…,half=…,shape=…]` |
| `slab` | плита → `[type=bottom\|top\|double]` |
| `wall` | стенка (cobblestone_wall) |
| `fence` | забор |
| `gate` | калитка (fence_gate) |
| `door` | дверь (две половины ставятся автоматически) |
| `trapdoor` | люк |
| `button`, `pressure_plate`, `sign`, `wall_sign` | по необходимости |
| `axisX`, `axisY`, `axisZ` | ориентированные брёвна/колонны |
| `hanging` | подвесной вариант (фонарь) |
| `carpet`, `pane` | ковёр, панель стекла |

Если вариант не задан, резолвер пытается вывести его сам:
`minecraft:oak_planks` + `stairs` → `minecraft:oak_stairs`; `minecraft:cobblestone` + `wall`
→ `minecraft:cobblestone_wall`. Не вывелось — берётся `full` и пишется предупреждение.

---

## 2. Blueprint

```jsonc
{
  "id": "medieval_house_small",
  "name": { "ru": "Домик, малый", "en": "Small house" },
  "category": "house",              // house | shop | tavern | smithy | farm | church | tower |
                                    // watchtower | well | gate | wall | bridge | statue | tree |
                                    // rock | prop | castle | arena | dock | mill | market
  "tags": ["village", "starter"],
  "size": [7, 8, 9],                // [x, y, z] — ширина, высота, глубина
  "facing": "north",                // куда смотрит фасад в исходной ориентации (-Z)
  "groundLevel": 1,                 // слой y, который ложится на землю; y<groundLevel — фундамент
  "footprint": [7, 9],              // площадь под застройку (для генератора поселений)
  "styleHints": ["medieval", "fantasy"],
  "minStyleRoles": ["wall_primary", "roof_primary", "door", "window"],

  "legend": {
    " ": { "skip": true },                                  // не трогать существующий блок
    ".": { "block": "minecraft:air" },                      // очистить
    "#": { "role": "wall_primary" },
    "%": { "role": "wall_secondary" },
    "B": { "role": "beam", "variant": "axisY" },
    "-": { "role": "beam", "variant": "axisX" },
    "|": { "role": "beam", "variant": "axisZ" },
    "F": { "role": "floor" },
    "W": { "role": "window", "variant": "pane" },
    "D": { "role": "door", "variant": "door", "facing": "north" },   // низ двери; верх ставится сам
    "/": { "role": "roof_primary", "variant": "stairs", "facing": "east" },
    "\\":{ "role": "roof_primary", "variant": "stairs", "facing": "west" },
    "^": { "role": "roof_primary", "variant": "stairs", "facing": "north" },
    "v": { "role": "roof_primary", "variant": "stairs", "facing": "south" },
    "_": { "role": "roof_primary", "variant": "slab", "half": "bottom" },
    "=": { "role": "roof_primary", "variant": "slab", "half": "top" },
    "L": { "role": "light" },
    "l": { "role": "light", "variant": "hanging" },
    "f": { "role": "fence", "variant": "fence" },
    "c": { "role": "carpet", "chance": 0.7 },               // 70% шанс поставить
    "T": { "block": "minecraft:crafting_table" },           // сырой блок, минуя стиль
    "1": { "prop": "bed_red", "facing": "north" }           // именованный проп (см. props)
  },

  // Слои снизу вверх. rows[z] — строка длиной size[0] (ось X слева направо).
  // Первая строка = z:0 (север/фасад).
  "layers": [
    { "y": 0, "rows": [ "#######", "#######", "#######", "#######", "#######", "#######", "#######", "#######", "#######" ] },
    { "y": 1, "rows": [ "##D####", "#FFFFF#", "#FFFFF#", "#FFFFF#", "#FFFFF#", "#FFFFF#", "#FFFFF#", "#FFFFF#", "#######" ] }
  ],

  // Необязательные пост-детали
  "signs":    [ { "pos": [3,3,0], "variant": "wall_sign", "facing": "north", "textKey": "tavern" } ],
  "containers":[{ "pos": [2,2,5], "block": "minecraft:barrel[facing=up]", "lootTable": "minecraft:chests/village/village_house" } ],
  "entities": [ { "pos": [3,2,4], "entity": "minecraft:villager", "profession": "librarian" } ],
  "lights":   [ [3,4,4] ],
  "clearance": { "above": 4 },      // столько блоков воздуха расчистить над постройкой
  "prepare": {                      // подготовка площадки перед постройкой
    "flatten": true,
    "padding": 1,
    "foundationTo": "ground"        // достроить фундамент вниз до земли
  }
}
```

### Правила
* Все `rows` в слое должны иметь одинаковую длину `= size[0]`; число строк `= size[2]`.
  Слоёв должно быть `size[1]`. Валидатор (`npm run validate:content`) это проверяет.
* Символ, которого нет в `legend` → ошибка валидации.
* `" "` (пробел) = **не трогать**. `"."` = **поставить воздух**. Это разные вещи.
* Двери: ставится только нижняя половина (`half=bottom`), верхнюю резолвер добавляет сам.
* Кровати, двойные сундуки, высокие цветы — то же самое, через `prop`.
* Ротация: при повороте блюпринта на 90/180/270° резолвер поворачивает и `facing`
  в легенде, и координаты. Зеркалирование — `mirror: "x" | "z"`.

### Prop-ы (встроенный набор)
`bed_red`… `bed_white`, `double_chest`, `bookshelf_wall`, `table_small`, `table_long`,
`chair`, `barrel_stack`, `hay_stack`, `cauldron_stand`, `anvil_station`, `brewing_corner`,
`flower_pot`, `lamp_post`, `well_head`, `market_stall`, `haybale_cart`.
Определены в `content/props/*.json` тем же форматом blueprint (маленькие, без `prepare`).

---

## 3. RawStructure (слепки региона)

```jsonc
{
  "id": "my_castle",
  "kind": "raw",
  "size": [21, 17, 21],
  "palette": ["minecraft:air", "minecraft:stone_bricks", "minecraft:oak_stairs[facing=north,half=bottom]"],
  "data": "128x0,3x1,0,2,...",      // индексы палитры, порядок y → z → x, RLE ("NxV" = N раз V)
  "blockEntities": [ { "pos": [3,2,4], "type": "sign", "front": ["..."] } ],
  "entities": [],
  "capturedAt": 1730000000000,
  "sourceWorld": "world"
}
```
Ставится один в один, без подстановки стиля (`build_structure`), либо со стилем через
`restyle: true` — тогда MCP-сервер пытается сопоставить блоки ролям и перекрасить постройку
в другой стиль (эвристика: дерево→`wall_secondary`, камень→`wall_primary`, стекло→`window`…).

---

## 4. Валидация

```bash
npm run validate:content     # проверяет все стили, блюпринты и пропы
```
Проверяется: обязательные роли, размеры слоёв, неизвестные символы, неизвестные блоки
(по списку из `content/blocks-1.21.json`), корректность `facing`, ссылки блюпринтов
на несуществующие пропы.
