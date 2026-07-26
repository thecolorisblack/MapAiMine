package dev.mapaimine.bridge.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.block.BlockParser;
import dev.mapaimine.bridge.json.J;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.profile.PlayerProfile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Block entities, entities and the escape hatches — PROTOCOL.md §2 groups 9-12:
 * sign / container / head / spawner / entity / command / checkpoint.
 *
 * <p>These are "instant" ops: they do a bounded amount of work in a single tick and charge a flat
 * cost to the tick budget instead of streaming placements.
 */
public final class EntityOps {

    private EntityOps() {
    }

    /** Base class for one-shot ops that finish within a single {@code execute} call. */
    abstract static class InstantOp implements Op {
        private boolean done;

        @Override
        public final boolean execute(ExecutionContext ctx) {
            if (done) return true;
            done = true;
            try {
                run(ctx);
            } catch (ApiException ex) {
                ctx.warn(type() + ": " + ex.getMessage());
            } catch (RuntimeException ex) {
                ctx.warn(type() + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            return true;
        }

        abstract void run(ExecutionContext ctx);
    }

    // ── sign ─────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"sign","pos":[..],"block":"..","front":[..],"back":[..],"glowing":bool,"color":".."}}
     *
     * <p>Both sign sides are supported. Lines longer than the vanilla limit are passed through
     * unchanged — the client truncates them visually, which is friendlier than failing the job.
     */
    public static final class SignOp extends InstantOp {
        private final int x, y, z;
        private final BlockData block;
        private final List<String> front;
        private final List<String> back;
        private final boolean glowing;
        private final String color;

        public SignOp(BlockParser parser, JsonObject o) {
            int[] p = J.pos(o, "pos");
            x = p[0];
            y = p[1];
            z = p[2];
            block = J.has(o, "block") ? parser.parse(J.reqStr(o, "block")) : null;
            front = J.strList(o, "front");
            back = J.strList(o, "back");
            glowing = J.bool(o, "glowing", false);
            color = J.str(o, "color", null);
        }

        @Override
        public String type() {
            return "sign";
        }

        @Override
        public long estimate() {
            return 1;
        }

        @Override
        void run(ExecutionContext ctx) {
            Block b = placeBase(ctx, x, y, z, block);
            if (b == null) return;
            BlockState state = b.getState();
            if (!(state instanceof Sign sign)) {
                ctx.warn("sign: block at " + x + "," + y + "," + z + " is not a sign");
                return;
            }
            applySide(sign.getSide(Side.FRONT), front, glowing, color);
            applySide(sign.getSide(Side.BACK), back, glowing, color);
            sign.setWaxed(false);
            sign.update(true, false);
        }

        private static void applySide(SignSide side, List<String> lines, boolean glowing, String color) {
            if (lines != null) {
                for (int i = 0; i < 4; i++) {
                    side.setLine(i, i < lines.size() ? lines.get(i) : "");
                }
            }
            side.setGlowingText(glowing);
            if (color != null) {
                try {
                    side.setColor(DyeColor.valueOf(color.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown colour: keep the default rather than failing the whole job.
                }
            }
        }
    }

    // ── container ────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"container","pos":[..],"block":"..","items":[..],"lootTable":".."}}
     *
     * <p>{@code items} entries are {@code {slot,id,count,name,enchants,lore}}. A {@code lootTable}
     * is applied after the explicit items, matching vanilla behaviour where the loot table wins
     * and is rolled when a player first opens the container.
     */
    public static final class ContainerOp extends InstantOp {
        private final int x, y, z;
        private final BlockData block;
        private final JsonArray items;
        private final String lootTable;

        public ContainerOp(BlockParser parser, JsonObject o) {
            int[] p = J.pos(o, "pos");
            x = p[0];
            y = p[1];
            z = p[2];
            block = J.has(o, "block") ? parser.parse(J.reqStr(o, "block")) : null;
            items = J.arr(o, "items");
            lootTable = J.str(o, "lootTable", null);
        }

        @Override
        public String type() {
            return "container";
        }

        @Override
        public long estimate() {
            return 1;
        }

        @Override
        void run(ExecutionContext ctx) {
            Block b = placeBase(ctx, x, y, z, block);
            if (b == null) return;
            BlockState state = b.getState();
            if (!(state instanceof Container container)) {
                ctx.warn("container: block at " + x + "," + y + "," + z + " has no inventory");
                return;
            }
            if (items != null) {
                for (JsonElement el : items) {
                    JsonObject e = J.asObject(el, "items[]");
                    int slot = J.i(e, "slot", -1);
                    ItemStack stack = buildItem(e);
                    if (stack == null) continue;
                    if (slot < 0) {
                        container.getInventory().addItem(stack);
                    } else if (slot < container.getInventory().getSize()) {
                        container.getInventory().setItem(slot, stack);
                    } else {
                        ctx.warn("container: slot " + slot + " is out of range");
                    }
                }
            }
            if (lootTable != null && state instanceof Lootable lootable) {
                LootTable table = resolveLootTable(lootTable);
                if (table == null) {
                    ctx.warn("container: unknown lootTable '" + lootTable + "'");
                } else {
                    lootable.setLootTable(table);
                }
            }
            state.update(true, false);
        }
    }

    // ── head ─────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"head","pos":[..],"texture":"<base64>","owner":"Notch"}}
     *
     * <p>The base64 blob is the vanilla {@code textures} property; the skin URL is extracted from
     * it and applied through the Bukkit {@link PlayerProfile} API, so no NMS is required.
     */
    public static final class HeadOp extends InstantOp {
        private final int x, y, z;
        private final BlockData block;
        private final String texture;
        private final String owner;

        public HeadOp(BlockParser parser, JsonObject o) {
            int[] p = J.pos(o, "pos");
            x = p[0];
            y = p[1];
            z = p[2];
            block = J.has(o, "block") ? parser.parse(J.reqStr(o, "block"))
                    : parser.parse("minecraft:player_head");
            texture = J.str(o, "texture", null);
            owner = J.str(o, "owner", null);
        }

        @Override
        public String type() {
            return "head";
        }

        @Override
        public long estimate() {
            return 1;
        }

        @Override
        void run(ExecutionContext ctx) {
            Block b = placeBase(ctx, x, y, z, block);
            if (b == null) return;
            BlockState state = b.getState();
            if (!(state instanceof Skull skull)) {
                ctx.warn("head: block at " + x + "," + y + "," + z + " is not a skull");
                return;
            }
            if (texture != null && !texture.isEmpty()) {
                String url = extractSkinUrl(texture);
                if (url == null) {
                    ctx.warn("head: could not read a skin URL out of 'texture'");
                } else {
                    try {
                        PlayerProfile profile = Bukkit.createPlayerProfile(
                                UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)),
                                owner != null ? trimName(owner) : "MapAiMine");
                        profile.getTextures().setSkin(URI.create(url).toURL());
                        skull.setOwnerProfile(profile);
                    } catch (Exception ex) {
                        ctx.warn("head: failed to apply texture (" + ex.getMessage() + ")");
                    }
                }
            } else if (owner != null && !owner.isEmpty()) {
                skull.setOwnerProfile(Bukkit.createPlayerProfile(trimName(owner)));
            }
            skull.update(true, false);
        }

        private static String trimName(String name) {
            return name.length() > 16 ? name.substring(0, 16) : name;
        }

        /** Pulls {@code textures.SKIN.url} out of the vanilla base64 property. */
        static String extractSkinUrl(String base64) {
            try {
                String json = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
                int idx = json.indexOf("\"url\"");
                if (idx < 0) return null;
                int start = json.indexOf('"', json.indexOf(':', idx) + 1);
                int end = json.indexOf('"', start + 1);
                if (start < 0 || end < 0) return null;
                return json.substring(start + 1, end);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    // ── spawner ──────────────────────────────────────────────────────────────────────────────

    /** {@code {"type":"spawner","pos":[..],"entity":"minecraft:zombie","delay":200,"maxNearby":4}} */
    public static final class SpawnerOp extends InstantOp {
        private final int x, y, z;
        private final BlockData block;
        private final String entity;
        private final Integer delay;
        private final Integer maxNearby;
        private final Integer spawnCount;
        private final Integer requiredPlayerRange;

        public SpawnerOp(BlockParser parser, JsonObject o) {
            int[] p = J.pos(o, "pos");
            x = p[0];
            y = p[1];
            z = p[2];
            block = J.has(o, "block") ? parser.parse(J.reqStr(o, "block")) : parser.parse("minecraft:spawner");
            entity = J.str(o, "entity", null);
            delay = J.has(o, "delay") ? J.i(o, "delay", 200) : null;
            maxNearby = J.has(o, "maxNearby") ? J.i(o, "maxNearby", 6) : null;
            spawnCount = J.has(o, "spawnCount") ? J.i(o, "spawnCount", 4) : null;
            requiredPlayerRange = J.has(o, "requiredPlayerRange") ? J.i(o, "requiredPlayerRange", 16) : null;
        }

        @Override
        public String type() {
            return "spawner";
        }

        @Override
        public long estimate() {
            return 1;
        }

        @Override
        void run(ExecutionContext ctx) {
            Block b = placeBase(ctx, x, y, z, block);
            if (b == null) return;
            BlockState state = b.getState();
            if (!(state instanceof CreatureSpawner spawner)) {
                ctx.warn("spawner: block at " + x + "," + y + "," + z + " is not a spawner");
                return;
            }
            if (entity != null) {
                EntityType type = resolveEntityType(entity);
                if (type == null) {
                    ctx.warn("spawner: unknown entity type '" + entity + "'");
                } else {
                    spawner.setSpawnedType(type);
                }
            }
            if (delay != null) spawner.setDelay(delay);
            if (maxNearby != null) spawner.setMaxNearbyEntities(maxNearby);
            if (spawnCount != null) spawner.setSpawnCount(spawnCount);
            if (requiredPlayerRange != null) spawner.setRequiredPlayerRange(requiredPlayerRange);
            state.update(true, false);
        }
    }

    // ── entity ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"entity","pos":[..],"entity":"..","name":"..","profession":"..", ...}}
     *
     * <p>{@code snbt} is applied via the vanilla {@code /data merge entity <uuid>} command, which
     * is the only Bukkit-API-only way to reach arbitrary NBT. It therefore requires the
     * {@code commands} capability; without it the field is ignored with a warning.
     */
    public static final class EntityOp extends InstantOp {
        private final double x, y, z;
        private final float yaw;
        private final float pitch;
        private final String entity;
        private final String name;
        private final boolean nameVisible;
        private final String profession;
        private final boolean noAi;
        private final boolean persistent;
        private final List<String> tags;
        private final String snbt;

        public EntityOp(JsonObject o) {
            double[] p = J.posD(o, "pos");
            x = p[0];
            y = p[1];
            z = p[2];
            yaw = (float) J.dbl(o, "yaw", 0);
            pitch = (float) J.dbl(o, "pitch", 0);
            entity = J.reqStr(o, "entity");
            name = J.str(o, "name", null);
            nameVisible = J.bool(o, "nameVisible", false);
            profession = J.str(o, "profession", null);
            noAi = J.bool(o, "noAI", false);
            persistent = J.bool(o, "persistent", true);
            tags = J.strList(o, "tags");
            snbt = J.str(o, "snbt", null);
        }

        @Override
        public String type() {
            return "entity";
        }

        @Override
        public long estimate() {
            return 1;
        }

        @Override
        public void validate(BridgeConfig config) {
            if (!config.has("entities")) throw ApiException.disabled("entities");
        }

        @Override
        void run(ExecutionContext ctx) {
            EntityType type = resolveEntityType(entity);
            if (type == null) {
                ctx.warn("entity: unknown entity type '" + entity + "'");
                return;
            }
            World world = ctx.world();
            Location loc = new Location(world, x, y, z, yaw, pitch);
            Entity spawned;
            try {
                spawned = world.spawnEntity(loc, type);
            } catch (RuntimeException ex) {
                ctx.warn("entity: cannot spawn " + entity + " (" + ex.getMessage() + ")");
                return;
            }
            ctx.spend(4);
            if (name != null) {
                spawned.setCustomName(name);
                spawned.setCustomNameVisible(nameVisible);
            }
            spawned.setPersistent(persistent);
            if (tags != null) {
                for (String tag : tags) spawned.addScoreboardTag(tag);
            }
            spawned.addScoreboardTag("mapaimine");
            if (noAi && spawned instanceof LivingEntity living) {
                living.setAI(false);
            }
            if (profession != null && spawned instanceof Villager villager) {
                Villager.Profession prof = Registry.VILLAGER_PROFESSION.get(key(profession));
                if (prof == null) {
                    ctx.warn("entity: unknown villager profession '" + profession + "'");
                } else {
                    villager.setProfession(prof);
                }
            }
            if (snbt != null && !snbt.isBlank()) {
                if (!ctx.config().has("commands")) {
                    ctx.warn("entity: 'snbt' ignored because the 'commands' capability is disabled");
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "data merge entity " + spawned.getUniqueId() + " " + snbt);
                }
            }
        }
    }

    // ── command ──────────────────────────────────────────────────────────────────────────────

    /** {@code {"type":"command","command":"gamerule doDaylightCycle false"}} — capability gated. */
    public static final class CommandOp extends InstantOp {
        private final String command;

        public CommandOp(JsonObject o) {
            String raw = J.reqStr(o, "command").trim();
            this.command = raw.startsWith("/") ? raw.substring(1) : raw;
        }

        @Override
        public String type() {
            return "command";
        }

        @Override
        public long estimate() {
            return 1;
        }

        @Override
        public void validate(BridgeConfig config) {
            if (!config.has("commands")) throw ApiException.disabled("commands");
            if (command.isEmpty()) throw ApiException.badRequest("'command' must not be empty");
        }

        @Override
        void run(ExecutionContext ctx) {
            ctx.spend(16);
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (!ok) ctx.warn("command: '" + command + "' returned false");
        }
    }

    // ── checkpoint ───────────────────────────────────────────────────────────────────────────

    /**
     * {@code {"type":"checkpoint","name":"roads done"}} — a zero-cost marker whose name is
     * surfaced in {@code GET /jobs/{id}} so long jobs report human-readable phases.
     */
    public static final class CheckpointOp implements Op {
        private final String name;

        public CheckpointOp(JsonObject o) {
            this.name = J.str(o, "name", "");
        }

        @Override
        public String type() {
            return "checkpoint";
        }

        @Override
        public long estimate() {
            return 0;
        }

        @Override
        public boolean execute(ExecutionContext ctx) {
            return true;
        }

        @Override
        public String checkpointName() {
            return name;
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────

    /**
     * Places the carrier block for a block-entity op (when {@code block} is given) and returns the
     * block to configure, or {@code null} if the position is unusable.
     */
    static Block placeBase(ExecutionContext ctx, int x, int y, int z, BlockData block) {
        if (!ctx.inWorld(y)) {
            ctx.warn("position " + x + "," + y + "," + z + " is outside world height, skipped");
            return null;
        }
        if (ctx.config().isProtected(ctx.world().getName(), x, y, z)) {
            ctx.warn("position " + x + "," + y + "," + z + " is inside a protected region, skipped");
            return null;
        }
        Block b = ctx.world().getBlockAt(x, y, z);
        if (block != null) {
            Placement p = new Placement().set(x, y, z, block);
            ctx.place(p);
        } else {
            ctx.spend(1);
        }
        return b;
    }

    static NamespacedKey key(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        NamespacedKey k = NamespacedKey.fromString(s);
        if (k == null) throw ApiException.badRequest("malformed identifier '" + raw + "'");
        return k;
    }

    static EntityType resolveEntityType(String raw) {
        try {
            EntityType t = Registry.ENTITY_TYPE.get(key(raw));
            if (t != null) return t;
        } catch (RuntimeException ignored) {
            // fall through to the enum lookup
        }
        try {
            String s = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
            return EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static LootTable resolveLootTable(String raw) {
        try {
            return Bukkit.getLootTable(key(raw));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Builds an {@link ItemStack} from a container item entry. Returns null when unresolvable. */
    static ItemStack buildItem(JsonObject e) {
        String id = J.str(e, "id", null);
        if (id == null) return null;
        Material material = Material.matchMaterial(BlockParser.normalizeInput(id));
        if (material == null) return null;
        ItemStack stack = new ItemStack(material, Math.max(1, J.i(e, "count", 1)));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        String name = J.str(e, "name", null);
        if (name != null) meta.setDisplayName(name);
        List<String> lore = J.strList(e, "lore");
        if (lore != null) meta.setLore(new ArrayList<>(lore));
        JsonObject enchants = J.child(e, "enchants");
        if (enchants != null) {
            for (Map.Entry<String, JsonElement> en : enchants.entrySet()) {
                Enchantment ench = Registry.ENCHANTMENT.get(key(en.getKey()));
                if (ench != null) meta.addEnchant(ench, en.getValue().getAsInt(), true);
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
