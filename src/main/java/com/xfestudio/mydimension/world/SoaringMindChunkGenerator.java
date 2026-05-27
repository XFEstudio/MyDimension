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
    private static final int CELL_SIZE = 384;
    private static final int CELL_MARGIN = 150;
    private static final int NEIGHBOR_RADIUS = 2;
    private static final double TWO_PI = Math.PI * 2.0D;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState MOSS = Blocks.MOSS_BLOCK.defaultBlockState();
    private static final BlockState PODZOL = Blocks.PODZOL.defaultBlockState();
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();

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
                IslandSlice slice = sliceAt(x, z);
                if (slice == null) {
                    continue;
                }

                int minY = Math.max(slice.bottomY(), chunk.getMinBuildHeight());
                int maxY = Math.min(slice.topY(), chunk.getMaxBuildHeight() - 1);
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = stateForY(x, y, z, slice);
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
        IslandSlice slice = sliceAt(x, z);
        if (slice == null) {
            return heightAccessor.getMinBuildHeight();
        }

        int topY = Math.min(slice.topY(), heightAccessor.getMaxBuildHeight() - 1);
        int bottomY = Math.max(slice.bottomY(), heightAccessor.getMinBuildHeight());
        for (int y = topY; y >= bottomY; y--) {
            BlockState state = stateForY(x, y, z, slice);
            if (heightmapType.isOpaque().test(state)) {
                return y + 1;
            }
        }
        return heightAccessor.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        IslandSlice slice = sliceAt(x, z);
        for (int index = 0; index < states.length; index++) {
            int y = heightAccessor.getMinBuildHeight() + index;
            states[index] = stateForY(x, y, z, slice);
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

    public static BlockPos findNearestSurface(int centerX, int centerZ, int maxRadius) {
        BlockPos exact = surfaceAt(centerX, centerZ);
        if (exact != null) {
            return exact;
        }

        for (int radius = 4; radius <= maxRadius; radius += 4) {
            BlockPos best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (int offset = -radius; offset <= radius; offset += 4) {
                best = closerSurface(centerX, centerZ, centerX + offset, centerZ - radius, best, bestDistance);
                if (best != null) {
                    bestDistance = distanceSquared(centerX, centerZ, best.getX(), best.getZ());
                }
                best = closerSurface(centerX, centerZ, centerX + offset, centerZ + radius, best, bestDistance);
                if (best != null) {
                    bestDistance = distanceSquared(centerX, centerZ, best.getX(), best.getZ());
                }
                best = closerSurface(centerX, centerZ, centerX - radius, centerZ + offset, best, bestDistance);
                if (best != null) {
                    bestDistance = distanceSquared(centerX, centerZ, best.getX(), best.getZ());
                }
                best = closerSurface(centerX, centerZ, centerX + radius, centerZ + offset, best, bestDistance);
                if (best != null) {
                    bestDistance = distanceSquared(centerX, centerZ, best.getX(), best.getZ());
                }
            }

            if (best != null) {
                return best;
            }
        }

        return null;
    }

    private static BlockPos closerSurface(int centerX, int centerZ, int x, int z, BlockPos current, int currentDistance) {
        BlockPos candidate = surfaceAt(x, z);
        if (candidate == null) {
            return current;
        }

        int distance = distanceSquared(centerX, centerZ, x, z);
        return distance < currentDistance ? candidate : current;
    }

    private static int distanceSquared(int centerX, int centerZ, int x, int z) {
        int dx = x - centerX;
        int dz = z - centerZ;
        return dx * dx + dz * dz;
    }

    private static BlockPos surfaceAt(int x, int z) {
        IslandSlice slice = sliceAt(x, z);
        if (slice == null || slice.hasWater()) {
            return null;
        }
        return new BlockPos(x, slice.topY() + 1, z);
    }

    private static int topIslandY(int x, int z) {
        IslandSlice slice = sliceAt(x, z);
        return slice == null ? -1 : slice.topY();
    }

    private static IslandSlice sliceAt(int x, int z) {
        IslandSlice best = null;
        int topY = -1;
        int baseCellX = Math.floorDiv(x, CELL_SIZE);
        int baseCellZ = Math.floorDiv(z, CELL_SIZE);

        for (int cellX = baseCellX - NEIGHBOR_RADIUS; cellX <= baseCellX + NEIGHBOR_RADIUS; cellX++) {
            for (int cellZ = baseCellZ - NEIGHBOR_RADIUS; cellZ <= baseCellZ + NEIGHBOR_RADIUS; cellZ++) {
                Island island = islandFor(cellX, cellZ);
                if (island == null) {
                    continue;
                }

                double shape = islandShape(x, z, island);
                if (shape > 1.0D) {
                    continue;
                }

                IslandSlice slice = islandSliceAt(x, z, island, shape);
                if (slice.topY() > topY) {
                    topY = slice.topY();
                    best = slice;
                }
            }
        }

        return best;
    }

    private static IslandSlice islandSliceAt(int x, int z, Island island, double shape) {
        int landTop = islandTopAt(x, z, island, shape);
        int solidTop = landTop;
        int waterTop = -1;
        double lakeShape = lakeShape(x, z, island, shape);

        if (lakeShape < 1.0D) {
            int depression = 2 + (int) Math.round((1.0D - lakeShape) * 4.0D);
            waterTop = Math.min(island.waterY(), landTop - 1);
            solidTop = Math.min(waterTop - 1, landTop - depression);
        }

        int bottomY = islandBottomAt(x, z, island, shape, landTop);
        return new IslandSlice(waterTop >= 0 ? waterTop : landTop, bottomY, solidTop, waterTop, island);
    }

    private static BlockState stateForY(int x, int y, int z, IslandSlice slice) {
        if (slice == null || y < slice.bottomY() || y > slice.topY()) {
            return AIR;
        }
        if (slice.hasWater() && y > slice.solidTopY() && y <= slice.waterTopY()) {
            return WATER;
        }
        if (y == slice.solidTopY()) {
            return slice.hasWater() ? lakeBedState(slice.island().theme()) : surfaceState(x, z, slice.island());
        }
        if (y >= slice.solidTopY() - 4) {
            return subsurfaceState(slice.island().theme());
        }
        return fillerState(slice.island().theme());
    }

    private static double islandShape(int x, int z, Island island) {
        double rawX = x - island.x();
        double rawZ = z - island.z();
        double cos = Math.cos(island.angle());
        double sin = Math.sin(island.angle());
        double dx = (rawX * cos + rawZ * sin) / island.radiusX();
        double dz = (-rawX * sin + rawZ * cos) / island.radiusZ();
        double angle = Math.atan2(dz, dx);
        double distance = Math.sqrt(dx * dx + dz * dz);
        double boundary = 1.0D;
        boundary += 0.18D * Math.sin(angle * 2.0D + phase(island.seed(), 10));
        boundary += 0.13D * Math.sin(angle * 4.0D + phase(island.seed(), 11));
        boundary += 0.08D * Math.sin(angle * 7.0D + phase(island.seed(), 12));
        boundary += 0.05D * Math.sin(angle * 11.0D + phase(island.seed(), 13));
        boundary += smoothNoise(x, z, island.seed() ^ 0x31C5E0DADDL, 42) * 0.12D;
        boundary += smoothNoise(x, z, island.seed() ^ 0x6A09E667F3BCC909L, 22) * 0.06D;
        return distance / Math.max(0.62D, boundary);
    }

    private static int islandTopAt(int x, int z, Island island, double shape) {
        double edgeDrop = smoothStep(0.94D, 1.0D, shape);
        int surface = island.y() - (int) Math.round(edgeDrop * island.edgeDrop());
        surface += surfaceNoise(x, z, island.seed());
        return Math.min(Math.max(surface, MIN_Y + 8), HEIGHT - 16);
    }

    private static int islandBottomAt(int x, int z, Island island, double shape, int topY) {
        double cone = Math.max(0.0D, 1.0D - shape);
        double tip = 1.0D - smoothStep(0.0D, 0.20D, shape);
        int thickness = island.edgeThickness() + (int) Math.round(island.height() * cone + island.tipDepth() * tip);
        int underside = topY - thickness + undersideNoise(x, z, island.seed());
        return Math.max(MIN_Y, underside);
    }

    private static double lakeShape(int x, int z, Island island, double islandShape) {
        if (!island.hasLake() || islandShape > 0.66D) {
            return 2.0D;
        }

        double dx = (double) (x - island.lakeX()) / island.lakeRadiusX();
        double dz = (double) (z - island.lakeZ()) / island.lakeRadiusZ();
        return dx * dx + dz * dz;
    }

    private static Island islandFor(int cellX, int cellZ) {
        long seed = mix(cellX, cellZ);
        if (randomUnit(seed) > 0.86D) {
            return null;
        }

        IslandTheme theme = IslandTheme.values()[randomInt(seed, 14, IslandTheme.values().length)];
        int centerX = cellX * CELL_SIZE + CELL_MARGIN + randomInt(seed, 0, CELL_SIZE - CELL_MARGIN * 2);
        int centerZ = cellZ * CELL_SIZE + CELL_MARGIN + randomInt(seed, 1, CELL_SIZE - CELL_MARGIN * 2);
        IslandSize size = islandSize(seed);
        int centerY = 82 + randomInt(seed, 2, 72);
        int radiusX = size.minRadius() + randomInt(seed, 3, size.radiusRange());
        int radiusZ = size.minRadius() + randomInt(seed, 4, size.radiusRange());
        if (randomUnit(seed + 17) > 0.52D) {
            radiusX += size.stretch() + randomInt(seed, 18, Math.max(1, size.stretch() + 1));
        } else if (randomUnit(seed + 19) > 0.52D) {
            radiusZ += size.stretch() + randomInt(seed, 20, Math.max(1, size.stretch() + 1));
        }
        int height = size.minHeight() + randomInt(seed, 5, size.heightRange());
        int edgeDrop = randomInt(seed, 6, 3);
        int edgeThickness = 2 + randomInt(seed, 7, 2);
        double angle = phase(seed, 9);
        int tipDepth = size.tipDepth() + randomInt(seed, 8, Math.max(1, size.tipDepth() / 2));
        boolean hasLake = size.allowsLake() && theme != IslandTheme.ROCKY && randomUnit(seed + 21) < lakeChance(theme);
        int lakeX = centerX + randomInt(seed, 22, Math.max(1, radiusX)) - radiusX / 2;
        int lakeZ = centerZ + randomInt(seed, 23, Math.max(1, radiusZ)) - radiusZ / 2;
        int lakeRadiusX = Math.max(4, radiusX / 8) + randomInt(seed, 24, Math.max(1, Math.min(18, radiusX / 4)));
        int lakeRadiusZ = Math.max(4, radiusZ / 8) + randomInt(seed, 25, Math.max(1, Math.min(18, radiusZ / 4)));
        int waterY = centerY - 1 + randomInt(seed, 26, 3) - 1;
        return new Island(centerX, centerY, centerZ, radiusX, radiusZ, height, edgeDrop, edgeThickness,
                tipDepth, angle, theme, hasLake, lakeX, lakeZ, lakeRadiusX, lakeRadiusZ, waterY, seed);
    }

    private static IslandSize islandSize(long seed) {
        int roll = randomInt(seed, 27, 100);
        if (roll < 18) {
            return IslandSize.TINY;
        }
        if (roll < 46) {
            return IslandSize.SMALL;
        }
        if (roll < 80) {
            return IslandSize.MEDIUM;
        }
        return IslandSize.LARGE;
    }

    private static double lakeChance(IslandTheme theme) {
        return switch (theme) {
            case LUSH -> 0.72D;
            case PLAINS, FOREST -> 0.48D;
            case SNOWY -> 0.34D;
            case DESERT -> 0.20D;
            case ROCKY -> 0.0D;
        };
    }

    private static BlockState surfaceState(int x, int z, Island island) {
        return switch (island.theme()) {
            case LUSH -> smoothNoise(x, z, island.seed() ^ 0x94D049BB133111EBL, 18) > 0.18D ? MOSS : GRASS;
            case FOREST -> smoothNoise(x, z, island.seed() ^ 0x2545F4914F6CDD1DL, 20) > 0.25D ? PODZOL : GRASS;
            case DESERT -> SAND;
            case SNOWY -> SNOW_BLOCK;
            case ROCKY -> smoothNoise(x, z, island.seed() ^ 0xD6E8FEB86659FD93L, 16) > 0.10D ? GRAVEL : STONE;
            case PLAINS -> smoothNoise(x, z, island.seed() ^ 0xBF58476D1CE4E5B9L, 24) > 0.42D ? COARSE_DIRT : GRASS;
        };
    }

    private static BlockState lakeBedState(IslandTheme theme) {
        return switch (theme) {
            case DESERT -> SAND;
            case ROCKY -> GRAVEL;
            case SNOWY -> CLAY;
            case LUSH, FOREST, PLAINS -> CLAY;
        };
    }

    private static BlockState subsurfaceState(IslandTheme theme) {
        return switch (theme) {
            case DESERT -> SANDSTONE;
            case ROCKY -> STONE;
            case SNOWY, LUSH, FOREST, PLAINS -> DIRT;
        };
    }

    private static BlockState fillerState(IslandTheme theme) {
        return switch (theme) {
            case DESERT -> SANDSTONE;
            default -> STONE;
        };
    }

    private static int surfaceNoise(int x, int z, long seed) {
        return (int) Math.round(smoothNoise(x, z, seed ^ 0xA24BAED4963EE407L, 48) * 1.2D
                + smoothNoise(x, z, seed ^ 0x9FB21C651E98DF25L, 96) * 1.4D);
    }

    private static int undersideNoise(int x, int z, long seed) {
        return (int) Math.round(smoothNoise(x, z, seed ^ 0xC2B2AE3D27D4EB4FL, 42) * 1.4D);
    }

    private static double smoothNoise(int x, int z, long seed, int scale) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double localX = (double) (x - cellX * scale) / scale;
        double localZ = (double) (z - cellZ * scale) / scale;
        double sx = fade(localX);
        double sz = fade(localZ);

        double v00 = randomSigned(seed, cellX, cellZ);
        double v10 = randomSigned(seed, cellX + 1, cellZ);
        double v01 = randomSigned(seed, cellX, cellZ + 1);
        double v11 = randomSigned(seed, cellX + 1, cellZ + 1);
        return lerp(lerp(v00, v10, sx), lerp(v01, v11, sx), sz);
    }

    private static double randomSigned(long seed, int x, int z) {
        return randomUnit(seed + mix(x, z)) * 2.0D - 1.0D;
    }

    private static double smoothStep(double edge0, double edge1, double value) {
        double t = Math.max(0.0D, Math.min(1.0D, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0D - 2.0D * t);
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double phase(long seed, int salt) {
        return randomUnit(seed + salt * 0x632BE59BD9B4E019L) * TWO_PI;
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

    private enum IslandTheme {
        PLAINS,
        FOREST,
        LUSH,
        DESERT,
        SNOWY,
        ROCKY
    }

    private enum IslandSize {
        TINY(4, 8, 8, 10, 4, 5, false),
        SMALL(11, 15, 14, 16, 8, 8, true),
        MEDIUM(28, 34, 34, 28, 16, 18, true),
        LARGE(58, 60, 58, 42, 30, 34, true);

        private final int minRadius;
        private final int radiusRange;
        private final int minHeight;
        private final int heightRange;
        private final int stretch;
        private final int tipDepth;
        private final boolean allowsLake;

        IslandSize(int minRadius, int radiusRange, int minHeight, int heightRange, int stretch, int tipDepth, boolean allowsLake) {
            this.minRadius = minRadius;
            this.radiusRange = radiusRange;
            this.minHeight = minHeight;
            this.heightRange = heightRange;
            this.stretch = stretch;
            this.tipDepth = tipDepth;
            this.allowsLake = allowsLake;
        }

        private int minRadius() {
            return minRadius;
        }

        private int radiusRange() {
            return radiusRange;
        }

        private int minHeight() {
            return minHeight;
        }

        private int heightRange() {
            return heightRange;
        }

        private int stretch() {
            return stretch;
        }

        private int tipDepth() {
            return tipDepth;
        }

        private boolean allowsLake() {
            return allowsLake;
        }
    }

    private record Island(int x, int y, int z, int radiusX, int radiusZ, int height, int edgeDrop,
                          int edgeThickness, int tipDepth, double angle, IslandTheme theme, boolean hasLake,
                          int lakeX, int lakeZ, int lakeRadiusX, int lakeRadiusZ, int waterY, long seed) {
    }

    private record IslandSlice(int topY, int bottomY, int solidTopY, int waterTopY, Island island) {
        private boolean hasWater() {
            return waterTopY >= 0;
        }
    }
}
