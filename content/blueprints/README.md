# Каталог блюпринтов / Blueprint catalogue

Файлы формата **Blueprint** (см. `docs/SCHEMAS.md`, раздел 2). Постройки описаны
ролями (`wall_primary`, `roof_primary`…), конкретные блоки подставляет Style Pack,
поэтому один и тот же файл собирается в любом стиле.

Blueprints are style-agnostic: they reference **roles**, and the active style pack
resolves them to blocks. `size` is `[x, y, z]`, `footprint` is the plot `[x, z]`
a settlement generator should reserve. Every blueprint faces **north** (`-Z`) in its
source orientation; the resolver rotates coordinates and `facing` values.

## Дома и общественные здания / Houses & civic

| id | Название / Name | Категория | size (x,y,z) | footprint | Описание | Description | Стили / styles |
|---|---|---|---|---|---|---|---|
| `house_small` | Домик, малый / Small house | house | 7×8×9 | 7×9 | Одноэтажный домик на одну семью: кровать, очаг, верстак, двускатная крыша. | One-room cottage with bed, hearth, crafting table and a gable roof. | medieval, fantasy, rustic, winter |
| `house_medium` | Дом, средний / Medium house | house | 9×10×11 | 9×11 | Дом в два уровня: общая комната внизу, две кровати под крышей, лестница. | Two-level home: living room downstairs, two beds in the attic, wooden stair. | medieval, fantasy, rustic, winter |
| `house_large` | Дом, большой / Large house | house | 11×12×13 | 11×13 | Просторный двухэтажный дом зажиточной семьи: два входа, спальни наверху. | Roomy two-storey home for a well-off family, two entrances, bedrooms upstairs. | medieval, fantasy, rustic |
| `tavern` | Таверна / Tavern | tavern | 13×11×15 | 13×15 | Трактир с барной стойкой, столами, очагом и четырьмя комнатами на чердаке. | Inn with a bar, common-room tables, a hearth and four attic guest beds. | medieval, fantasy, rustic |
| `shop` | Лавка / Shop | shop | 9×9×9 | 9×9 | Торговая лавка: витрина на фасаде, прилавок, полки и торговец за стойкой. | Trading shop with a shopfront window, counter, shelves and a resident trader. | medieval, fantasy, rustic |
| `smithy` | Кузница / Smithy | smithy | 11×9×11 | 11×11 | Кузница с открытым фасадом, горном, дымоходом, наковальней и бочкой для закалки. | Open-fronted forge with furnaces, chimney, anvil bench and a quenching trough. | medieval, fantasy, rustic |
| `church` | Церковь / Church | church | 11×16×17 | 11×17 | Церковь с нефом, высокими окнами, скамьями, алтарём и колокольней над входом. | Church with a nave, tall windows, pews, an altar and a bell tower over the porch. | medieval, fantasy, gothic |
| `town_hall` | Ратуша / Town hall | house | 13×13×15 | 13×15 | Ратуша с крытой аркадой, залом собраний, помостом и кабинетами наверху. | Town hall with a covered arcade, assembly hall, dais and upstairs offices. | medieval, fantasy, gothic |
| `library` | Библиотека / Library | house | 11×11×13 | 11×13 | Библиотека: стены из книжных полок, читальный стол и кафедра с книгой. | Library lined with bookshelves, a long reading table and a lectern. | medieval, fantasy, gothic |

## Хозяйство и село / Utility & rural

| id | Название / Name | Категория | size (x,y,z) | footprint | Описание | Description | Стили / styles |
|---|---|---|---|---|---|---|---|
| `well` | Колодец / Well | well | 5×6×5 | 5×5 | Каменный колодец с воротом, навесом и фонарём над шахтой. | Stone well with a winch, small roof and a lantern over the shaft. | medieval, fantasy, rustic, winter |
| `market_stall` | Торговый лоток / Market stall | market | 5×5×5 | 5×5 | Открытый лоток с прилавком и навесом — для рынка и площади. | Open counter with a cloth awning, for market squares. | medieval, fantasy, rustic |
| `farm_field` | Поле / Farm field | farm | 9×3×9 | 9×9 | Возделанное поле 9×9 с водяным каналом посередине и дорожкой по краю. | A 9×9 tilled field with a central water channel and a path border. | medieval, fantasy, rustic |
| `barn` | Амбар / Barn | farm | 11×9×13 | 11×13 | Большой амбар: воротный проём, стойла, сеновал под крышей и телега с сеном. | Large barn with wagon doors, animal pens, a hay loft and a loaded cart. | medieval, fantasy, rustic |
| `windmill` | Мельница / Windmill | mill | 9×16×9 | 9×9 | Ветряная мельница: башня в три яруса, крылья на фасаде, мешки и зерно внутри. | Windmill with a three-level tower, sail cross on the facade and grain inside. | medieval, fantasy, rustic |
| `watchtower` | Дозорная башня / Watchtower | watchtower | 7×16×7 | 7×7 | Каменная дозорная башня в четыре яруса с лестницей, бойницами и жаровней наверху. | Four-level stone watchtower with a ladder, arrow slits and a brazier on top. | medieval, fantasy, gothic |
| `stable` | Конюшня / Stable | farm | 11×8×13 | 11×13 | Открытая конюшня на четыре денника с поилками, сеном и проездом посередине. | Open-fronted stable with four stalls, water troughs, hay and a central aisle. | medieval, fantasy, rustic |

## Укрепления / Fortification

| id | Название / Name | Категория | size (x,y,z) | footprint | Описание | Description | Стили / styles |
|---|---|---|---|---|---|---|---|
| `wall_segment` | Секция стены / Wall segment | wall | 8×7×3 | 8×3 | Прямая секция крепостной стены с боевым ходом и зубцами; стыкуется по оси X. | Straight curtain-wall section with a wall-walk and crenellations; tiles along X. | medieval, fantasy, gothic |
| `wall_corner` | Угол стены / Wall corner | wall | 7×7×7 | 7×7 | Г-образный угол крепостной стены: боевой ход поворачивает, зубцы снаружи. | L-shaped curtain-wall corner; the wall-walk turns, crenellations face outward. | medieval, fantasy, gothic |
| `gatehouse` | Надвратная башня / Gatehouse | gate | 13×12×9 | 13×9 | Ворота с двумя башнями, проездом, решёткой и боевым ходом наверху. | Twin-tower gatehouse with a road passage, portcullis and a fighting deck on top. | medieval, fantasy, gothic |
| `keep` | Донжон / Keep | castle | 15×20×15 | 15×15 | Замковый донжон в три этажа: пиршественный зал, покои, оружейная, зубчатая крыша с башенками. | Three-storey castle keep: great hall, solar with beds, armoury, crenellated roof with corner turrets. | medieval, fantasy, gothic |

## Инфраструктура и декор / Infrastructure & decor

| id | Название / Name | Категория | size (x,y,z) | footprint | Описание | Description | Стили / styles |
|---|---|---|---|---|---|---|---|
| `bridge_segment` | Секция моста / Bridge segment | bridge | 7×6×5 | 7×5 | Секция каменного моста с перилами и фонарями; стыкуется по оси Z. | Stone bridge section with railings and lanterns; tiles along Z. | medieval, fantasy, rustic |
| `dock_segment` | Секция причала / Dock segment | dock | 7×4×7 | 7×7 | Деревянный причал на сваях с перилами, тумбой и фонарём; стыкуется по оси Z. | Timber pier on piles with railings, a bollard and a lantern; tiles along Z. | medieval, fantasy, rustic |
| `statue_plinth` | Статуя на постаменте / Statue on a plinth | statue | 5×8×5 | 5×5 | Постамент со ступенями, фонарями по углам и каменной фигурой наверху. | Stepped plinth with corner lanterns and a stone figure on top. | medieval, fantasy, gothic |
| `fountain` | Фонтан / Fountain | statue | 7×5×7 | 7×7 | Фонтан с каменной чашей, водой и фонарями по углам мощёной площадки. | Fountain with a stone basin, running water and lanterns at the paved corners. | medieval, fantasy, gothic |
| `lamp_post` | Фонарный столб / Lamp post | prop | 1×5×1 | 1×1 | Уличный фонарь на столбе — ставится вдоль дорог с шагом 8 блоков. | Street lamp on a post, meant to be repeated along roads every 8 blocks. | medieval, fantasy, rustic, winter |
| `tree_medieval_oak` | Дуб / Oak tree | tree | 7×9×7 | 7×7 | Раскидистый дуб для рассадки по ландшафту; листва берётся из роли foliage. | Broad oak for scattering across the landscape; canopy uses the foliage role. | medieval, fantasy, rustic, autumn, winter |
| `rock_small` | Валун / Small rock | rock | 3×3×3 | 3×3 | Небольшой валун неправильной формы для рассадки по ландшафту. | Small irregular boulder for landscape scattering. | medieval, fantasy, rustic, winter |
| `campsite` | Стоянка / Campsite | prop | 7×4×7 | 7×7 | Походная стоянка: палатка, костёр, брёвна вместо скамеек и фонарь на шесте. | Traveller camp: a tent, a campfire, log seats and a lantern on a pole. | medieval, fantasy, rustic |
| `graveyard_plot` | Кладбищенский участок / Graveyard plot | prop | 5×3×7 | 5×7 | Огороженный участок с четырьмя могилами, дорожкой и калиткой — ставится у церкви. | Fenced plot with four graves, a path and a gate; goes next to a church. | medieval, fantasy, gothic |
| `arena_small` | Малая арена / Small arena | arena | 21×12×21 | 21×21 | Небольшая арена: песчаное поле, три ряда каменных трибун, четыре прохода и стена с зубцами. | Small arena: sand floor, three tiers of stone seating, four aisles and a crenellated outer wall. | medieval, fantasy, gothic |

## Пропы / Props (`content/props/`)

Мелкие детали интерьера. Тот же формат, но без `prepare` и `clearance`; ставятся из
легенды блюпринта записью `{ "prop": "<id>", "facing": "north" }`.

| id | Название / Name | size (x,y,z) | Описание / Description |
|---|---|---|---|
| `bed_red` | Кровать красная / Red bed | 1×1×2 | Односпальная кровать, изголовье на север. — Single bed, head to the north. |
| `double_chest` | Двойной сундук / Double chest | 2×1×1 | Двойной сундук, дверцы на север. — Double chest opening to the north. |
| `bookshelf_wall` | Стена из книжных полок / Bookshelf wall | 3×3×1 | Секция книжных полок 3×3. — A 3×3 block of bookshelves. |
| `table_small` | Столик / Small table | 1×2×1 | Круглый столик: столб и плита сверху. — Round table: a post with a slab top. |
| `table_long` | Длинный стол / Long table | 3×2×1 | Длинный обеденный стол на три места. — Long dining table, three seats wide. |
| `chair` | Стул / Chair | 1×1×1 | Стул из ступеньки, спинка на север. — Stair chair, backrest to the north. |
| `barrel_stack` | Штабель бочек / Barrel stack | 2×2×1 | Три бочки, поставленные горкой. — Three barrels stacked in a corner. |
| `hay_stack` | Стог сена / Hay stack | 3×2×3 | Копна сена 3×3 с верхним снопом. — A 3×3 stack of hay bales with a top bale. |
| `cauldron_stand` | Котёл на подставке / Cauldron stand | 1×2×1 | Котёл с водой на каменной подставке. — Water cauldron raised on a stone base. |
| `anvil_station` | Наковальня с верстаком / Anvil station | 3×1×1 | Наковальня и стол кузнеца. — Anvil paired with a smithing table. |
| `brewing_corner` | Уголок зельевара / Brewing corner | 2×1×1 | Варочная стойка и котёл с водой. — Brewing stand next to a water cauldron. |
| `flower_pot` | Горшок с цветком / Flower pot | 1×1×1 | Цветок в горшке. — A potted flower. |
| `well_head` | Навес колодца / Well head | 3×3×3 | Четыре столба, ворот и навес — верх колодца. — Four posts, a winch beam and a small roof. |
| `market_stall_prop` | Прилавок / Market stall (prop) | 3×3×2 | Маленький прилавок с навесом на двух столбах. — Tiny counter with an awning on two posts. |
| `haybale_cart` | Телега с сеном / Haybale cart | 3×3×3 | Крестьянская телега, гружёная сеном. — Farm cart loaded with hay bales. |

## Как выбирать постройку / How to pick a building

* **Деревня с нуля** — `house_small` ×N, `house_medium`, `well`, `farm_field`,
  `barn`, `smithy`, `shop`, `tavern`, `church` в центре, `watchtower` на въезде.
* **Город** — добавьте `town_hall`, `library`, `market_stall` ×N, `fountain`,
  `statue_plinth`, `lamp_post` вдоль дорог, `wall_segment` + `wall_corner` +
  `gatehouse` по периметру.
* **Замок** — `keep` в центре, `wall_segment`/`wall_corner` по контуру, `gatehouse`
  на входе, `arena_small` рядом для турниров.
* **Порт** — `dock_segment` ×N вдоль берега, `bridge_segment` ×N через реку.
* **Природа** — `tree_medieval_oak`, `rock_small`, `campsite` для рассадки
  (`flora.trees` / `flora.rocks` в стиле).

### Тайловые части / Tileable pieces

| id | Ось / axis | Шаг / step |
|---|---|---|
| `wall_segment` | X | 8 |
| `bridge_segment` | Z | 5 |
| `dock_segment` | Z | 7 |

Стыкуются без швов: повторяйте с шагом, равным размеру по соответствующей оси.
