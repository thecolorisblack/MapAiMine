# MapAiMine Bridge (plugin)

Paper/Spigot plugin that exposes **Bridge Protocol v1** (`docs/PROTOCOL.md`) over HTTP so the
MapAiMine MCP server — and the model behind it — can read the world and build in it.

* Java 21, Bukkit API only (runs on Paper, Spigot and Purpur 1.21.x)
* **Zero runtime dependencies.** HTTP is `com.sun.net.httpserver`, JSON is the Gson already
  shipped inside the server jar. Nothing is shaded; the jar is ~200 KB.

---

## Build

```bash
cd plugin
mvn -B -DskipTests package
# -> target/mapaimine-bridge-0.1.0.jar
```

Drop the jar into your server's `plugins/` folder and restart. On first start the plugin writes
`plugins/MapAiMine/config.yml`, generates an API token and prints it:

```
================================================================
  MapAiMine bridge is up.
  URL   : http://127.0.0.1:25599/api/v1
  Token : Kx3f...  (256-bit, url-safe base64)
  Copy both into your MCP server config, e.g.:
    MAPAIMINE_URL=http://127.0.0.1:25599/api/v1
    MAPAIMINE_TOKEN=Kx3f...
================================================================
```

Quick check:

```bash
curl -s http://127.0.0.1:25599/api/v1/health | jq          # reduced, no auth needed
curl -s -H "Authorization: Bearer $TOKEN" \
     http://127.0.0.1:25599/api/v1/health | jq             # full
```

---

## Commands

| Command | What it does |
|---|---|
| `/mapaimine status` | port, capabilities, running jobs, undo history |
| `/mapaimine token` | show the URL and token |
| `/mapaimine token regenerate` | rotate the token (invalidates the old one) and restart the bridge |
| `/mapaimine undo [undoId]` | roll back the last (or a named) operation |
| `/mapaimine reload` | re-read `config.yml` and restart the HTTP server |
| `/mapaimine pos1` / `pos2` | mark a region corner, readable via `GET /selection?player=<you>` |

Permission: `mapaimine.admin` (default: op).

---

## Configuration

`plugins/MapAiMine/config.yml`

| Key | Default | Meaning |
|---|---|---|
| `host` | `127.0.0.1` | bind address — do **not** expose publicly, the API can rewrite your world |
| `port` | `25599` | listen port |
| `token` | *generated* | bearer token; empty on first run, filled in and saved automatically |
| `httpThreads` | `4` | HTTP worker threads (handlers never touch the world directly) |
| `maxRequestBytes` | `33554432` | request body cap (32 MiB) |
| `limits.maxOpsPerRequest` | `4096` | entries allowed in one `POST /ops` |
| `limits.maxBlocksPerOp` | `4000000` | estimated block writes allowed for a single op |
| `limits.maxRegionVolume` | `8000000` | bounding-box volume allowed for a region op |
| `limits.blocksPerTick` | `20000` | **the anti-lag knob**: main-thread work budget per tick |
| `limits.maxUndoHistory` | `20` | snapshots kept for `POST /undo` |
| `limits.surveyMaxPoints` | `16384` | sample points `GET /survey` may return |
| `limits.maxUndoBlocks` | `2000000` | per job; beyond this undo is dropped with a warning |
| `limits.maxUndoMemoryMb` | `256` | per job memory budget for the undo snapshot |
| `limits.maxProbePoints` | `1024` | points allowed in `POST /probe` |
| `limits.maxSyncBlocks` | `200000` | largest job allowed with `"async": false` |
| `limits.surveyMaxChunks` | `4096` | refuse surveys that would generate more chunks than this |
| `limits.syncTimeoutMs` | `60000` | how long a synchronous `POST /ops` or `/undo` may wait |
| `limits.readTimeoutMs` | `15000` | how long a world read may wait for the main thread |
| `limits.finishedJobsKept` | `64` | finished jobs retained for `GET /jobs/{id}` |
| `limits.maxCaptureVolume` | `2000000` | blocks allowed in one `POST /capture` |
| `capabilities.*` | see below | feature switches, mirrored into `GET /health` |
| `allowedWorlds` | `[]` | empty = all worlds writable; otherwise a whitelist |
| `protectedRegions` | `[]` | boxes the bridge never modifies (`world`, `from`, `to`; `world: "*"` = all) |
| `physics` | `false` | default for `POST /ops` `physics` |
| `lightUpdate` | `true` | default for `POST /ops` `lightUpdate` |
| `logLevel` | `INFO` | |

Capabilities: `survey`, `undo`, `jobs`, `worldCreate`, `schematic`, `entities`, `containers`,
`commands`, `trees`. All default to **on** except `commands`, which runs arbitrary console
commands and is opt-in.

---

## Endpoints

Base URL `http://<host>:<port>/api/v1`.
Auth: `Authorization: Bearer <token>` or `X-MapAiMine-Token: <token>` on every request except the
reduced form of `/health`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | protocol version, worlds, capabilities, limits (reduced without a token) |
| GET | `/worlds` | `{worlds: [...]}` |
| POST | `/worlds` | create a world (`normal｜flat｜large_biomes｜amplified｜void`) |
| POST | `/worlds/{name}/settings` | time, weather, locks, difficulty, pvp, gamerules, `keepLoaded` |
| POST | `/worlds/{name}/spawn` | set spawn, `safe:true` finds/creates a landing spot |
| POST | `/worlds/{name}/border` | world border centre, size, warning distance |
| GET | `/survey` | down-sampled heightmap / surface / biome / water / light + stats |
| POST | `/probe` | exact block state, light and biome at up to 1024 points |
| GET | `/materials` | every block id (`filter=`, `solidOnly=`) |
| POST | `/validate` | validate block ids, normalise them, suggest fixes |
| POST | `/ops` | **the worker**: every op type of PROTOCOL.md §2 |
| GET | `/jobs` | `{jobs: [...]}` |
| GET | `/jobs/{id}` | status, progress, checkpoint, ETA, warnings |
| DELETE | `/jobs/{id}` | cancel (placed blocks stay, `undoId` stays valid) |
| GET | `/undo` | `{history: [{undoId, label, blocks, at, world}]}` |
| POST | `/undo` | roll back `{undoId}`, or the newest snapshot when omitted |
| POST | `/capture` | snapshot a region into a `RawStructure` (SCHEMAS.md §3) |
| GET | `/captures` | `{captures: [{name, bytes, modifiedAt}]}` |
| GET | `/captures/{name}` | read a saved capture |
| GET | `/players` | `{players: [...]}` |
| POST | `/players/{name}/teleport` | |
| POST | `/players/{name}/gamemode` | |
| POST | `/broadcast` | chat message to everyone or ops only |
| GET | `/selection?player=` | the region a player marked with `/mapaimine pos1｜pos2` |

Op types accepted by `POST /ops`:
`set`, `blocks`, `fill`, `sphere`, `cylinder`, `pyramid`, `cone`, `line`, `walls`, `torus`,
`replace`, `paint`, `scatter`, `smooth`, `flatten`, `raise`, `terrace`, `clear`, `sign`,
`container`, `head`, `spawner`, `entity`, `command`, `checkpoint`.

---

## How it stays fast

* **HTTP handlers never run on the main thread.** World reads are marshalled with
  `Bukkit.getScheduler().callSyncMethod(...)` and a timeout; world writes never run inline at all —
  they become a job.
* **Tick budgeting.** `JobRunner` gives the active job `limits.blocksPerTick` positions per tick and
  suspends it mid-op when the budget runs out. A four-million-block build costs a steady, bounded
  slice of every tick instead of one multi-second freeze.
* **Undo is compact.** Snapshots store 12 bytes per block (packed `x:26|y:12|z:26` position + palette
  index) instead of a `HashMap<Location, BlockData>`. When a job exceeds `maxUndoBlocks` or the
  memory budget the snapshot disables itself, the job keeps going, and a warning says so.
* **Shapes are streamed.** A hollow 500³ sphere iterates its shell, not its volume.

---

## Notes and limitations

* `void` worlds are created with an empty-layer flat preset *and* a custom `ChunkGenerator`. The
  preset is what persists in `level.dat`, so the world still reads as void if the plugin is ever
  removed.
* `entity.snbt` is applied through the vanilla `data merge entity <uuid>` command and therefore
  requires the `commands` capability.
* Undo restores block states always, and block-entity NBT (sign text, chest contents) for up to
  8192 block entities per job.
* Folia is not supported (the tick-budget model assumes a single main thread).
