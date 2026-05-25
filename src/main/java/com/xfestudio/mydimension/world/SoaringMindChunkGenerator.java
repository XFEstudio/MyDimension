package com.xfestudio.mydimension.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

public class SoaringMindChunkGenerator extends ChunkGenerator {
    public static final Codec<SoaringMindChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource)
    ).apply(instance, instance.stable(SoaringMindChunkGenerator::new)));

    private static final int MIN_Y = 0;
    private static final int HEIGHT = 256;
    private static final int CELL_SIZE = 144;
    private static final int CELL_MARGIN = 48;
    private static final int NEIGHBOR_RADIUS = 2;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    public SoaringMindChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource, Util.memoize(holder -> holder.value().getGenerationSettings()));
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
        return ChunkGeneratorStructureState.createForFlat(randomState, seed, this.biomeSource, Stream.empty());
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving carving) {
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int localX = 0; localX < 16; localX++) {
            int x = minX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int z = minZ + localZ;
                int topY = topIslandY(x, z);
                if (topY < MIN_Y) {
                    continue;
                }

                for (int y = MIN_Y; y <= topY; y++) {
                    BlockState state = blockStateAt(x, y, z, topY);
                    if (state.isAir()) {
                        continue;
                    }
                    chunk.setBlockState(pos.set(localX, y, localZ), state, false);
                    oceanFloor.update(localX, y, localZ, state);
                    worldSurface.update(localX, y, localZ, state);
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType, LevelHeightAccessor heightAccessor, RandomState randomState) {
        int topY = Math.min(topIslandY(x, z), heightAccessor.getMaxBuildHeight() - 1);
        for (int y = topY; y >= heightAccessor.getMinBuildHeight(); y--) {
            BlockState state = blockStateAt(x, y, z, topY);
            if (heightmapType.isOpaque().test(state)) {
                return y + 1;
            }
        }
        return heightAccessor.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        int topY = topIslandY(x, z);
        for (int index = 0; index < states.length; index++) {
            int y = heightAccessor.getMinBuildHeight() + index;
            states[index] = blockStateAt(x, y, z, topY);
        }
        return new NoiseColumn(heightAccessor.getMinBuildHeight(), states);
    }

    @Override
    public int getSeaLevel() {
        return -63;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public int getGenDepth() {
        return HEIGHT;
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Soaring Mind islands");
    }

    private static int topIslandY(int x, int z) {
        int topY = -1;
        int baseCellX = Math.floorDiv(x, CELL_SIZE);
        int baseCellZ = Math.floorDiv(z, CELL_SIZE);

        for (int cellX = baseCellX - NEIGHBOR_RADIUS; cellX <= baseCellX + NEIGHBOR_RADIUS; cellX++) {
            for (int cellZ = baseCellZ - NEIGHBOR_RADIUS; cellZ <= baseCellZ + NEIGHBOR_RADIUS; cellZ++) {
                Island island = islandFor(cellX, cellZ);
                if (island == null) {
                    continue;
                }

                double dx = (double) (x - island.x()) / island.radiusX();
                double dz = (double) (z - island.z()) / island.radiusZ();
                double horizontal = dx * dx + dz * dz;
                if (horizontal > 1.0D) {
                    continue;
                }

                double edge = 1.0D - horizontal;
                int surface = island.y() + (int) Math.round(Math.sqrt(edge) * island.height() * 0.42D);
                surface += surfaceNoise(x, z, island.seed());
                topY = Math.max(topY, surface);
            }
        }

        return Math.min(topY, HEIGHT - 16);
    }

    private static BlockState blockStateAt(int x, int y, int z, int topY) {
        if (topY < MIN_Y || y > topY || y < MIN_Y) {
            return AIR;
        }

        int baseCellX = Math.floorDiv(x, CELL_SIZE);
        int baseCellZ = Math.floorDiv(z, CELL_SIZE);
        boolean insideIsland = false;

        for (int cellX = baseCellX - NEIGHBOR_RADIUS; cellX <= baseCellX + NEIGHBOR_RADIUS && !insideIsland; cellX++) {
            for (int cellZ = baseCellZ - NEIGHBOR_RADIUS; cellZ <= baseCellZ + NEIGHBOR_RADIUS; cellZ++) {
                Island island = islandFor(cellX, cellZ);
                if (island == null) {
                    continue;
                }

                int localTop = islandTopAt(x, z, island);
                if (localTop < MIN_Y || y > localTop) {
                    continue;
                }

                double dx = (double) (x - island.x()) / island.radiusX();
                double dz = (double) (z - island.z()) / island.radiusZ();
                double dy = (double) (y - island.y()) / island.height();
                double taper = dx * dx + dz * dz + dy * dy * 1.35D;
                if (taper <= 1.0D || y >= island.y() - 3) {
                    insideIsland = true;
                    break;
                }
            }
        }

        if (!insideIsland) {
            return AIR;
        }
        if (y == topY) {
            return GRASS;
        }
        if (y >= topY - 4) {
            return DIRT;
        }
        return STONE;
    }

    private static int islandTopAt(int x, int z, Island island) {
        double dx = (double) (x - island.x()) / island.radiusX();
        double dz = (double) (z - island.z()) / island.radiusZ();
        double horizontal = dx * dx + dz * dz;
        if (horizontal > 1.0D) {
            return -1;
        }
        double edge = 1.0D - horizontal;
        return island.y() + (int) Math.round(Math.sqrt(edge) * island.height() * 0.42D) + surfaceNoise(x, z, island.seed());
    }

    private static Island islandFor(int cellX, int cellZ) {
        long seed = mix(cellX, cellZ);
        if (randomUnit(seed) > 0.72D) {
            return null;
        }

        int centerX = cellX * CELL_SIZE + CELL_MARGIN + randomInt(seed, 0, CELL_SIZE - CELL_MARGIN * 2);
        int centerZ = cellZ * CELL_SIZE + CELL_MARGIN + randomInt(seed, 1, CELL_SIZE - CELL_MARGIN * 2);
        int centerY = 78 + randomInt(seed, 2, 76);
        int radiusX = 16 + randomInt(seed, 3, 32);
        int radiusZ = 16 + randomInt(seed, 4, 32);
        int height = 10 + randomInt(seed, 5, 24);
        return new Island(centerX, centerY, centerZ, radiusX, radiusZ, height, seed);
    }

    private static int surfaceNoise(int x, int z, long seed) {
        long mixed = mix(x + (int) seed, z - (int) (seed >>> 32));
        return randomInt(mixed, 0, 5) - 2;
    }

    private static int randomInt(long seed, int salt, int bound) {
        return (int) Math.floorMod(mix(seed + salt * 0x9E3779B97F4A7C15L), bound);
    }

    private static double randomUnit(long seed) {
        return (double) (mix(seed) >>> 11) * 0x1.0p-53D;
    }

    private static long mix(int x, int z) {
        return mix((long) x * 341873128712L + (long) z * 132897987541L);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private record Island(int x, int y, int z, int radiusX, int radiusZ, int height, long seed) {
    }
}
