# Style Packs — библиотека стилей / style library

Формат описан в [`docs/SCHEMAS.md`, раздел 1](../../docs/SCHEMAS.md).
Файл = один стиль, имя файла = `<id>.json`.

Каждый пак определяет **все обязательные роли** плюс почти все опциональные,
а также `environment`, `ground`, `flora`, `building`, `settlement`, `lighting` и `signs`.

> **Тексты табличек.** В `signs` значение — объект `{ "ru": [...], "en": [...] }`
> (в примере схемы показан плоский массив; здесь используется двуязычная форма —
> резолвер должен выбирать список по текущей локали и падать обратно на `en`).

---

## Таблица стилей

| id | Название (ru / en) | Настроение | Ключевые блоки | Подходящие биомы | Когда выбирать (для агента) |
|---|---|---|---|---|---|
| `medieval` | Средневековье / Medieval | Тёплая ремесленная деревня, фахверк и дым из труб | `cobblestone`, `oak_planks`, `dark_oak_log`, `spruce_stairs`, `white_terracotta` | plains, forest, meadow, birch_forest, river | Дефолт для «обычной деревни», квестового хаба, стартового поселения без сезонного акцента. |
| `winter` | Зима / Winter | Синие сумерки, снег по колено, огонь в очаге | `snow_block`, `packed_ice`, `blue_ice`, `spruce_planks`, `campfire`, `soul_lantern` | snowy_taiga, snowy_plains, grove, frozen_river, snowy_slopes | Нужен сезон «зима», ледяное озеро, полярная база, уютная заснеженная деревня. Единственный стиль со `snowLayer: true` и `weather: "snow"`. |
| `spring` | Весна / Spring | Розовое цветение, пастель, всё в цветах | `cherry_planks`, `birch_planks`, `pink_petals`, `flowering_azalea_leaves`, `pink_terracotta` | cherry_grove, flower_forest, meadow, plains, birch_forest | Нужен сезон «весна», праздничная/свадебная локация, светлая доброжелательная деревня. |
| `summer` | Лето / Summer | Полдень, яркая зелень, вода и тень | `oak_planks`, `bamboo_planks`, `smooth_sandstone`, `sunflower`, `sea_lantern` | plains, sunflower_plains, beach, savanna, sparse_jungle, river | Нужен сезон «лето», курорт/побережье, фонтаны и рынки. Единственный стиль с `timeLock: true` (вечный полдень). |
| `autumn` | Осень / Autumn | Золотая осень, урожай, тыквы и сено | `mangrove_planks`, `mangrove_roots`, `orange_terracotta`, `podzol`, `jack_o_lantern`, `hay_block`, `bricks` | old_growth_birch_forest, forest, taiga, savanna, dark_forest | Нужен сезон «осень», ярмарка урожая, Хэллоуин. Оранжевой листвы в ваниле нет — осень собрана из дерева, керамики, земли и `biomePaint: minecraft:savanna` (оливково-бурый тинт травы и листвы). |
| `nordic` | Север / Nordic | Суровые фьорды, длинные дома, дым и мох | `spruce_log`, `dark_oak_log`, `mossy_stone_bricks`, `grass_block` + `moss_block` (дерновая кровля), `campfire` | taiga, old_growth_spruce_taiga, windswept_hills, stony_shore, snowy_taiga | Викинги, суровая пограничная застава, порт с драккарами. Мало построек, но крупные: `density 0.45`, `plotPadding 5`. |
| `japanese` | Япония / Japanese | Спокойствие, сад камней, бумажные фонари | `white_concrete`, `smooth_quartz`, `dark_oak_log`, `bamboo_mosaic`, `deepslate_tiles`, `lantern` | cherry_grove, bamboo_jungle, forest, meadow, river | Пагоды, храм, чайный сад, азиатский квартал. `roofShape: pagoda`, большой `overhang`. |
| `desert` | Пустыня / Desert | Пекло, саман, тени и базар | `smooth_sandstone`, `cut_sandstone`, `packed_mud`, `mud_bricks`, `terracotta`, `jungle_planks` | desert, badlands, savanna, wooded_badlands, beach | Караванный город, оазис, руины в песках. Плоские крыши (`roofShape: flat`), широкие улицы (`roadWidth 5`) и большая площадь `19×19`. |
| `fantasy_elven` | Эльфы / Elven | Светящийся лес, мягкие изгибы, тишина | `birch_planks`, `smooth_quartz`, `prismarine_bricks`, `amethyst_block`, `sea_lantern`, `shroomlight` | flower_forest, old_growth_birch_forest, lush_caves, jungle, meadow | Высокое фэнтези, магический город, эльфийский анклав. Конические крыши, высокие этажи, много света и мха. |
| `steampunk` | Стимпанк / Steampunk | Копоть, пар, латунь и дождь | `bricks`, `copper_block` → `oxidized_cut_copper` (все стадии), `dark_oak_log`, `iron_bars`, `copper_bulb`, `blast_furnace` | forest, dark_forest, windswept_hills, swamp, river | Индустриальный город, шахта, часовая башня. Самая плотная застройка: `density 0.85`, `plotPadding 1`, фонари каждые 5 блоков. |
| `modern` | Модерн / Modern | Чисто, холодно, много стекла | `white_concrete`, `smooth_quartz`, `smooth_stone`, `glass_pane`, `tinted_glass`, `sea_lantern` | plains, meadow, beach, river, forest | Современный город, вилла, офисный квартал. Плоские крыши, широчайшие улицы (`roadWidth 6`) и площадь `21×21`. |

---

## Быстрый выбор стиля агентом

1. **Пользователь назвал сезон** («сделай зимнюю деревню», «осенний посёлок») →
   `winter` / `spring` / `summer` / `autumn`. Это полноценные стили, а не модификаторы:
   у каждого своя палитра, свои постройки и своя площадь.
2. **Пользователь назвал эпоху/культуру** → `medieval`, `nordic`, `japanese`, `desert`,
   `steampunk`, `modern`, `fantasy_elven`.
3. **Ничего не назвал** → смотри биом под курсором игрока:
   пустыня → `desert`, снежный → `winter`, тайга → `nordic`,
   вишнёвая роща → `spring` или `japanese`, всё остальное → `medieval`.

## Отличия, важные для генератора поселений

| id | roofShape | wallStyle | roadWidth | plazaSize | density | стена | landmark |
|---|---|---|---|---|---|---|---|
| `medieval` | gable | timber_frame | 3 | 13×13 | 0.70 | да (6) | church |
| `winter` | gable (pitch 2) | stone_base | 3 | 13×13 | 0.60 | нет | well |
| `spring` | gable | timber_frame | 3 | 15×15 | 0.65 | нет | well |
| `summer` | gable | solid | 4 | 17×17 | 0.70 | нет | well |
| `autumn` | gable | timber_frame | 3 | 13×13 | 0.68 | нет | mill |
| `nordic` | gable (pitch 2) | log_cabin | 4 | 15×15 | 0.45 | да (5) | statue |
| `japanese` | pagoda | timber_frame | 3 | 15×15 | 0.60 | да (4) | tower |
| `desert` | flat | solid | 5 | 19×19 | 0.55 | да (5) | well |
| `fantasy_elven` | conical | solid | 3 | 17×17 | 0.50 | нет | tower |
| `steampunk` | hip | solid | 5 | 15×15 | 0.85 | да (7) | tower |
| `modern` | flat | solid | 6 | 21×21 | 0.75 | нет | statue |

## Зависимости от другого контента

Все паки ссылаются в `flora.rocks` на блюпринт **`rock_small`** — его нужно
завести в `content/blueprints/` (или `content/props/`), иначе scatter камней
просто ничего не поставит.
