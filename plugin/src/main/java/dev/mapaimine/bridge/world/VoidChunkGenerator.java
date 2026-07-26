package dev.mapaimine.bridge.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Generates nothing at all — the {@code worldType: "void"} of PROTOCOL.md §2.
 *
 * <p>Every generation stage is disabled, so chunks come out as pure air: a clean canvas for AI
 * builds with no terrain to demolish first and no structure generation to fight.
 *
 * <p><b>Persistence note.</b> Bukkit stores only the <em>plugin name</em> of a custom generator in
 * {@code bukkit.yml}, not the generator itself, so {@code MapAiMinePlugin#getDefaultWorldGenerator}
 * hands this class back on restart when the world was created with the {@code void} id.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return Collections.emptyList();
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        // Without a fixed spawn the server hunts for solid ground forever in an empty world.
        return new Location(world, 0.5, 64, 0.5);
    }
}
