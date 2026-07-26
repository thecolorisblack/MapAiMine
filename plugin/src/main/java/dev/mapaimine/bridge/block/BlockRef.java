package dev.mapaimine.bridge.block;

import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Random;

/**
 * A resolved protocol {@code BlockRef}: either one fixed block state or a weighted set of them.
 *
 * <p>Weighted refs are resolved per placement with the op's seeded {@link Random}, so the same
 * {@code seed} in the request always produces the same "noise" pattern — a build can be replayed
 * or undone/redone deterministically.
 */
public final class BlockRef {

    private final BlockData single;
    private final BlockData[] choices;
    private final int[] cumulative;
    private final int total;

    private BlockRef(BlockData single, BlockData[] choices, int[] cumulative, int total) {
        this.single = single;
        this.choices = choices;
        this.cumulative = cumulative;
        this.total = total;
    }

    public static BlockRef fixed(BlockData data) {
        return new BlockRef(data, null, null, 0);
    }

    public static BlockRef weighted(List<BlockData> blocks, List<Integer> weights) {
        if (blocks.size() == 1) return fixed(blocks.get(0));
        BlockData[] arr = blocks.toArray(new BlockData[0]);
        int[] cum = new int[arr.length];
        int acc = 0;
        for (int i = 0; i < arr.length; i++) {
            acc += Math.max(1, weights.get(i));
            cum[i] = acc;
        }
        return new BlockRef(null, arr, cum, acc);
    }

    public boolean isFixed() {
        return single != null;
    }

    /** Picks a concrete block state. {@code random} is only consulted for weighted refs. */
    public BlockData pick(Random random) {
        if (single != null) return single;
        int roll = random.nextInt(total);
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) return choices[i];
        }
        return choices[choices.length - 1];
    }

    /** A representative state, used for estimates and dry-run reporting. */
    public BlockData any() {
        return single != null ? single : choices[0];
    }
}
