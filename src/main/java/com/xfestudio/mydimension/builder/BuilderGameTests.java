package com.xfestudio.mydimension.builder;

import com.mojang.authlib.GameProfile;
import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.builder.anchor.AnchorContainerResolver;
import com.xfestudio.mydimension.builder.blueprint.BlueprintCapture;
import com.xfestudio.mydimension.builder.blueprint.BlueprintData;
import com.xfestudio.mydimension.builder.blueprint.BlueprintIo;
import com.xfestudio.mydimension.builder.blueprint.BlueprintPlacementPlan;
import com.xfestudio.mydimension.builder.blueprint.BlueprintSaveMode;
import com.xfestudio.mydimension.builder.blueprint.BlueprintTransform;
import com.xfestudio.mydimension.builder.history.BuilderTransaction;
import com.xfestudio.mydimension.builder.history.WorldDelta;
import com.xfestudio.mydimension.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@GameTestHolder(MyDimension.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuilderGameTests {
    private BuilderGameTests() { }

    @GameTest(template = "empty")
    public static void diagonalEightNeighborSurface(GameTestHelper helper) {
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos diagonal = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlock(first, Blocks.STONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(diagonal, Blocks.STONE.defaultBlockState(), 3);
        SurfacePlanner.Plan plan = SurfacePlanner.plan(helper.getLevel(), first, Direction.UP,
                BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, 16, null);
        helper.assertTrue(plan.candidates().size() == 2,
                "Eight-neighbor traversal must include a diagonal block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void grassAndDirtShareOnlyTheirExplicitSurfaceGroup(GameTestHelper helper) {
        BlockPos grass = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos dirt = grass.east();
        BlockPos podzol = grass.west();
        BlockPos mycelium = grass.north();
        helper.getLevel().setBlock(grass, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(dirt, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(podzol, Blocks.PODZOL.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(mycelium, Blocks.MYCELIUM.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan plan = SurfacePlanner.plan(helper.getLevel(), grass, Direction.UP,
                BuilderMode.DEMOLISH, SurfaceMatchMode.SAME_BLOCK, 16, null);

        helper.assertTrue(plan.candidates().size() == 2
                        && plan.candidates().stream().anyMatch(candidate -> candidate.reference().equals(grass))
                        && plan.candidates().stream().anyMatch(candidate -> candidate.reference().equals(dirt)),
                "Same-block traversal did not treat grass and ordinary dirt as one surface type");
        helper.assertTrue(plan.candidates().stream().noneMatch(candidate ->
                        candidate.reference().equals(podzol) || candidate.reference().equals(mycelium)),
                "The explicit grass/dirt surface group accidentally included another dirt-like block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void verticalWallUsesOnlyExposedSelectedFace(GameTestHelper helper) {
        BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 3));
        BlockPos upper = helper.absolutePos(new BlockPos(2, 3, 3));
        BlockPos diagonal = helper.absolutePos(new BlockPos(3, 3, 3));
        helper.getLevel().setBlock(lower, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(upper, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(diagonal, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan plan = SurfacePlanner.plan(helper.getLevel(), lower, Direction.NORTH,
                BuilderMode.DEMOLISH, SurfaceMatchMode.SAME_BLOCK, 16, null);

        helper.assertTrue(plan.candidates().size() == 3,
                "An exposed vertical wall must retain four-way and diagonal connectivity");
        helper.assertTrue(plan.candidates().stream()
                        .allMatch(candidate -> candidate.reference().getZ() == lower.getZ()),
                "Vertical face traversal left its selected reference plane");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void groundEdgeDoesNotIncludeLowerTerrace(GameTestHelper helper) {
        BlockPos top = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos topEdge = helper.absolutePos(new BlockPos(2, 3, 1));
        BlockPos lowerTerrace = helper.absolutePos(new BlockPos(3, 2, 1));
        helper.getLevel().setBlock(top, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(topEdge, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(lowerTerrace, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan plan = SurfacePlanner.plan(helper.getLevel(), top, Direction.UP,
                BuilderMode.DEMOLISH, SurfaceMatchMode.SAME_BLOCK, 16, null);

        helper.assertTrue(plan.candidates().size() == 2,
                "A horizontal selected face must stop at the ground edge");
        helper.assertTrue(plan.candidates().stream()
                        .noneMatch(candidate -> candidate.reference().equals(lowerTerrace)),
                "Horizontal face traversal wrapped down onto a lower terrace");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void solidGroundBackingCutsOffBuriedWallForBothModes(GameTestHelper helper) {
        BlockPos visibleLower = helper.absolutePos(new BlockPos(2, 2, 3));
        BlockPos visibleUpper = helper.absolutePos(new BlockPos(2, 3, 3));
        BlockPos buried = helper.absolutePos(new BlockPos(2, 1, 3));
        BlockPos solidBacking = buried.north();
        BlockPos outwardGround = solidBacking.north();
        helper.getLevel().setBlock(visibleLower, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(visibleUpper, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(buried, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(solidBacking, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(outwardGround, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan build = SurfacePlanner.plan(helper.getLevel(), visibleLower, Direction.NORTH,
                BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, 16, null);
        SurfacePlanner.Plan replacementBuild = SurfacePlanner.plan(helper.getLevel(), visibleLower,
                Direction.NORTH, BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, 16, null, true);
        SurfacePlanner.Plan demolish = SurfacePlanner.plan(helper.getLevel(), visibleLower, Direction.NORTH,
                BuilderMode.DEMOLISH, SurfaceMatchMode.SAME_BLOCK, 16, null);

        helper.assertTrue(build.candidates().stream()
                        .noneMatch(candidate -> candidate.reference().equals(buried)),
                "Build planning crossed a solid ground backing into the buried wall");
        helper.assertTrue(demolish.candidates().stream()
                        .noneMatch(candidate -> candidate.reference().equals(buried)),
                "Demolish planning crossed a solid ground backing into the buried wall");
        helper.assertTrue(replacementBuild.candidates().stream()
                        .noneMatch(candidate -> candidate.reference().equals(buried)),
                "Replacement-enabled build planning crossed a solid ground backing into the buried wall");
        helper.assertTrue(build.candidates().size() == 2 && replacementBuild.candidates().size() == 2
                        && demolish.candidates().size() == 2,
                "Visible wall cells were lost while excluding the buried continuation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void vanillaContainerFallbackResolves(GameTestHelper helper) {
        BlockPos chest = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        helper.assertTrue(AnchorContainerResolver.resolveTarget(helper.getLevel(), chest, Direction.UP).isPresent(),
                "A vanilla chest must resolve through capability/container fallback");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mindbpRoundTrip(GameTestHelper helper) {
        try {
            BlueprintData source = new BlueprintData(UUID.randomUUID(), "GameTest", "server", null,
                    System.currentTimeMillis(), BlueprintSaveMode.BLOCKS_ONLY, 1, 1, 1, BlockPos.ZERO,
                    List.of(Blocks.STONE.defaultBlockState()),
                    List.of(new BlueprintData.BlockEntry(BlockPos.ZERO, 0, null)));
            BlueprintData decoded = BlueprintIo.decode(BlueprintIo.encode(source));
            helper.assertTrue(decoded.blocks().size() == 1 && decoded.state(decoded.blocks().get(0)).is(Blocks.STONE),
                    "mindbp GZIP NBT round trip changed its palette");
            helper.succeed();
        } catch (Exception exception) {
            helper.fail(exception.getMessage());
        }
    }

    @GameTest(template = "empty")
    public static void blueprintPreservesWaterCauldronLevel(GameTestHelper helper) {
        BlockPos sourcePosition = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos targetPosition = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos protectedPosition = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockState sourceState = Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3);
        helper.getLevel().setBlock(sourcePosition, sourceState, Block.UPDATE_ALL);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "blueprint-test-player"));
        player.getInventory().setItem(9, new ItemStack(Items.CAULDRON, 2));
        AtomicBoolean placementEventObserved = new AtomicBoolean();
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() != player) return;
            if (event.getPos().equals(targetPosition)) placementEventObserved.set(true);
            if (event.getPos().equals(protectedPosition)) event.setCanceled(true);
        };

        try {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                    BlockEvent.EntityPlaceEvent.class, listener);
            BlueprintData captured = BlueprintCapture.capture(helper.getLevel(), player,
                    sourcePosition, sourcePosition, BlueprintSaveMode.BLOCKS_ONLY,
                    "Water cauldron state");
            BlueprintData decoded = BlueprintIo.decode(BlueprintIo.encode(captured));
            BlockState decodedState = decoded.state(decoded.blocks().get(0));
            helper.assertTrue(decodedState.equals(sourceState),
                    "Blueprint capture/transfer changed the water-cauldron level");

            ItemStack cost = BuilderOperationManager.constructionCost(decodedState);
            helper.assertTrue(cost.is(Items.CAULDRON) && cost.getCount() == 1,
                    "A water cauldron must consume one base cauldron item");

            BlueprintPlacementPlan plan = BlueprintPlacementPlan.create(decoded,
                    BlueprintTransform.NONE, targetPosition);
            BuilderOperationManager.BlueprintBatchResult result =
                    BuilderOperationManager.executeBlueprintBatch(player, ItemStack.EMPTY,
                            plan.blocks(), UUID.randomUUID(), false);
            helper.assertTrue(result.changed() == 1 && result.blocked() == 0
                            && result.missing().isEmpty() && result.committed(),
                    "Blueprint execution rejected a legal water-cauldron state");
            helper.assertTrue(helper.getLevel().getBlockState(targetPosition).equals(sourceState),
                    "Blueprint placement did not retain the exact water-cauldron level");
            helper.assertTrue(player.getInventory().getItem(9).is(Items.CAULDRON)
                            && player.getInventory().getItem(9).getCount() == 1,
                    "Blueprint placement did not debit exactly one base cauldron item");
            helper.assertTrue(placementEventObserved.get(),
                    "Blueprint placement bypassed the Forge entity-place event");

            BlueprintPlacementPlan protectedPlan = BlueprintPlacementPlan.create(decoded,
                    BlueprintTransform.NONE, protectedPosition);
            BuilderOperationManager.BlueprintBatchResult protectedResult =
                    BuilderOperationManager.executeBlueprintBatch(player, ItemStack.EMPTY,
                            protectedPlan.blocks(), UUID.randomUUID(), false);
            helper.assertTrue(protectedResult.changed() == 0 && protectedResult.blocked() == 1
                            && helper.getLevel().getBlockState(protectedPosition).isAir(),
                    "A cancelled Forge placement event did not protect the blueprint target");
            helper.assertTrue(player.getInventory().getItem(9).is(Items.CAULDRON)
                            && player.getInventory().getItem(9).getCount() == 1,
                    "A cancelled Forge placement event did not refund its reserved material");
            helper.succeed();
        } catch (Exception exception) {
            helper.fail(exception.getMessage());
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
        }
    }

    @GameTest(template = "empty")
    public static void grassConstructionPrefersGrassThenFallsBackToDirt(GameTestHelper helper) {
        BlockPos exactTarget = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos fallbackTarget = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos disallowedTarget = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        // The final coordinate may sit just outside a small GameTest template's structure-void
        // envelope, so make every material-policy target deterministic instead of inheriting the
        // flat test world's grass surface.
        helper.getLevel().setBlock(exactTarget, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(fallbackTarget, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(disallowedTarget, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "grass-material-test-player"));
        player.getInventory().setItem(9, new ItemStack(Items.GRASS_BLOCK));
        player.getInventory().setItem(10, new ItemStack(Items.DIRT));

        BuilderOperationManager.BlueprintBatchResult exact = BuilderOperationManager.executeBlueprintBatch(
                player, ItemStack.EMPTY,
                List.of(new BlueprintPlacementPlan.PlannedBlock(
                        BlockPos.ZERO, exactTarget, grass, null)), UUID.randomUUID(), false);
        helper.assertTrue(exact.changed() == 1 && exact.missing().isEmpty()
                        && helper.getLevel().getBlockState(exactTarget).equals(grass),
                "Grass construction did not consume its exact grass-block material first");
        helper.assertTrue(player.getInventory().getItem(9).isEmpty()
                        && player.getInventory().getItem(10).is(Items.DIRT),
                "Grass construction consumed dirt while an exact grass block was available");

        BuilderOperationManager.BlueprintBatchResult fallback = BuilderOperationManager.executeBlueprintBatch(
                player, ItemStack.EMPTY,
                List.of(new BlueprintPlacementPlan.PlannedBlock(
                        BlockPos.ZERO, fallbackTarget, grass, null)), UUID.randomUUID(), false);
        helper.assertTrue(fallback.changed() == 1 && fallback.missing().isEmpty()
                        && helper.getLevel().getBlockState(fallbackTarget).equals(grass),
                "Grass construction reported missing material instead of falling back to ordinary dirt");
        helper.assertTrue(player.getInventory().getItem(10).isEmpty(),
                "Grass construction did not debit its dirt fallback");

        player.getInventory().setItem(11, new ItemStack(Items.PODZOL));
        BuilderOperationManager.BlueprintBatchResult disallowed = BuilderOperationManager.executeBlueprintBatch(
                player, ItemStack.EMPTY,
                List.of(new BlueprintPlacementPlan.PlannedBlock(
                        BlockPos.ZERO, disallowedTarget, grass, null)), UUID.randomUUID(), false);
        helper.assertTrue(disallowed.changed() == 0,
                "Podzol-only supply unexpectedly placed " + disallowed.changed() + " grass blocks");
        helper.assertTrue(disallowed.missing().size() == 1,
                "Podzol-only supply produced " + disallowed.missing().size()
                        + " missing entries instead of one");
        helper.assertTrue(helper.getLevel().getBlockState(disallowedTarget).isAir(),
                "Podzol-only supply changed the missing target to "
                        + helper.getLevel().getBlockState(disallowedTarget));
        helper.assertTrue(player.getInventory().getItem(11).is(Items.PODZOL),
                "A dirt-like block outside the explicit rule was consumed as a grass substitute");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementSettingBreaksOnlySurvivalBreakableObstacles(GameTestHelper helper) {
        BlockPos disabled = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos enabled = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos unbreakable = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockPos piston = helper.absolutePos(new BlockPos(7, 1, 1));
        helper.getLevel().setBlock(disabled, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(enabled, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(unbreakable, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(piston, Blocks.PISTON.defaultBlockState(), Block.UPDATE_ALL);

        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-survival-test-player"));
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 3));

        BuilderOperationManager.BlueprintBatchResult disabledResult =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(disabled, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), false);
        helper.assertTrue(disabledResult.changed() == 0 && disabledResult.blocked() == 1
                        && helper.getLevel().getBlockState(disabled).is(Blocks.DIRT)
                        && countItem(player, Items.STONE) == 3,
                "Replacement-off changed an obstacle or consumed its reserved material");

        RealmwrightData.setAllowsReplacement(scepter, true);
        BuilderOperationManager.BlueprintBatchResult enabledResult =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(enabled, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), false);
        helper.assertTrue(enabledResult.changed() == 1 && enabledResult.blocked() == 0
                        && helper.getLevel().getBlockState(enabled).is(Blocks.STONE)
                        && countItem(player, Items.STONE) == 2 && countItem(player, Items.DIRT) == 1,
                "Enabled survival replacement did not settle its real block drop exactly once");

        BuilderOperationManager.BlueprintBatchResult unbreakableResult =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(unbreakable, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), false);
        helper.assertTrue(unbreakableResult.changed() == 0 && unbreakableResult.blocked() == 1
                        && helper.getLevel().getBlockState(unbreakable).is(Blocks.BEDROCK)
                        && countItem(player, Items.STONE) == 2,
                "Survival replacement bypassed unbreakable hardness or lost material");

        BuilderOperationManager.BlueprintBatchResult pistonResult =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(piston, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), false);
        helper.assertTrue(pistonResult.changed() == 1 && pistonResult.blocked() == 0
                        && helper.getLevel().getBlockState(piston).is(Blocks.STONE)
                        && countItem(player, Items.STONE) == 1
                        && countItem(player, Items.PISTON) == 1,
                "A survival-breakable obstacle was rejected solely because of its block tag");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementSurfaceCanStartBehindObstruction(GameTestHelper helper) {
        BlockPos reference = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos obstruction = reference.above();
        helper.getLevel().setBlock(reference, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(obstruction, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);

        SurfacePlanner.Plan disabled = SurfacePlanner.plan(helper.getLevel(), reference, Direction.UP,
                BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, 8,
                Blocks.OAK_PLANKS.defaultBlockState(), false);
        SurfacePlanner.Plan enabled = SurfacePlanner.plan(helper.getLevel(), reference, Direction.UP,
                BuilderMode.BUILD, SurfaceMatchMode.SAME_BLOCK, 8,
                Blocks.OAK_PLANKS.defaultBlockState(), true);
        helper.assertTrue(disabled.candidates().isEmpty()
                        && enabled.candidates().size() == 1
                        && enabled.candidates().get(0).target().equals(obstruction),
                "Replacement-enabled surface planning could not start behind an obstruction");

        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-surface-test-player"));
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.setAllowsReplacement(scepter, true);
        RealmwrightData.setRecordsHistory(scepter, false);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.OAK_PLANKS));
        player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(reference), Direction.UP,
                reference, false);
        BuilderOperationManager.Result result =
                BuilderOperationManager.executeValidatedSurface(player, scepter, hit);
        helper.assertTrue(result.changed() == 1
                        && helper.getLevel().getBlockState(obstruction).is(Blocks.OAK_PLANKS)
                        && countItem(player, Items.DIRT) == 1,
                "Ordinary surface construction did not apply replacement semantics");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementNeverBreaksBeforeMaterialIsAvailable(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlock(target, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-missing-material-test-player"));
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.setAllowsReplacement(scepter, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);

        BuilderOperationManager.BlueprintBatchResult result =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(target, Blocks.OAK_PLANKS.defaultBlockState())),
                        UUID.randomUUID(), false);
        helper.assertTrue(result.changed() == 0 && result.missing().size() == 1
                        && helper.getLevel().getBlockState(target).is(Blocks.DIRT)
                        && countItem(player, Items.DIRT) == 0,
                "Replacement broke its obstacle before construction material was available");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementHarvestsToolGatedAndContainerDrops(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        Container contents = (Container) helper.getLevel().getBlockEntity(chest);
        contents.setItem(0, new ItemStack(Items.DIAMOND, 4));

        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-drops-test-player"));
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.setAllowsReplacement(scepter, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);
        player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 2));

        BuilderOperationManager.BlueprintBatchResult result =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(stone, Blocks.OAK_PLANKS.defaultBlockState()),
                                planned(chest, Blocks.OAK_PLANKS.defaultBlockState())),
                        UUID.randomUUID(), false);
        helper.assertTrue(result.changed() == 2 && result.blocked() == 0
                        && helper.getLevel().getBlockState(stone).is(Blocks.OAK_PLANKS)
                        && helper.getLevel().getBlockState(chest).is(Blocks.OAK_PLANKS)
                        && countItem(player, Items.OAK_PLANKS) == 0
                        && countItem(player, Items.COBBLESTONE) == 1
                        && countItem(player, Items.CHEST) == 1
                        && countItem(player, Items.DIAMOND) == 4,
                "Replacement did not preserve tool-gated loot and container lifecycle drops");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeReplacementBypassesHardnessWithoutDrops(GameTestHelper helper) {
        BlockPos unbreakable = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos ordinary = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlock(unbreakable, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(ordinary, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);

        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-creative-test-player"));
        player.setGameMode(GameType.CREATIVE);
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.setAllowsReplacement(scepter, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);

        BuilderOperationManager.BlueprintBatchResult result =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(unbreakable, Blocks.STONE.defaultBlockState()),
                                planned(ordinary, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), false);
        helper.assertTrue(result.changed() == 2 && result.blocked() == 0
                        && helper.getLevel().getBlockState(unbreakable).is(Blocks.STONE)
                        && helper.getLevel().getBlockState(ordinary).is(Blocks.STONE)
                        && countItem(player, Items.DIRT) == 0,
                "Creative replacement did not bypass hardness or incorrectly produced drops");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementHonorsBreakAndPlaceProtectionWithoutPartialMutation(GameTestHelper helper) {
        BlockPos breakDenied = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos placeDenied = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlock(breakDenied, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(placeDenied, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-protection-test-player"));
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.setAllowsReplacement(scepter, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 2));

        int[] breakEvents = {0};
        int[] placeEvents = {0};

        Consumer<BlockEvent.BreakEvent> breakListener = event -> {
            if (event.getPlayer() != player) return;
            breakEvents[0]++;
            if (event.getPos().equals(breakDenied)) event.setCanceled(true);
        };
        Consumer<BlockEvent.EntityPlaceEvent> placeListener = event -> {
            if (event.getEntity() != player) return;
            placeEvents[0]++;
            if (event.getPos().equals(placeDenied)) event.setCanceled(true);
        };
        try {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                    BlockEvent.BreakEvent.class, breakListener);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                    BlockEvent.EntityPlaceEvent.class, placeListener);
            BuilderOperationManager.BlueprintBatchResult result =
                    BuilderOperationManager.executeBlueprintBatch(player, scepter,
                            List.of(planned(breakDenied, Blocks.STONE.defaultBlockState()),
                                    planned(placeDenied, Blocks.STONE.defaultBlockState())),
                            UUID.randomUUID(), false);
            helper.assertTrue(result.changed() == 0 && result.blocked() == 2
                            && helper.getLevel().getBlockState(breakDenied).is(Blocks.DIRT)
                            && helper.getLevel().getBlockState(placeDenied).is(Blocks.DIRT)
                            && countItem(player, Items.STONE) == 2 && countItem(player, Items.DIRT) == 0
                            && breakEvents[0] == 2 && placeEvents[0] == 1,
                    "Protection cancellation left a partial replacement, drop, or material debit");
            helper.succeed();
        } catch (RuntimeException exception) {
            helper.fail(exception.getMessage());
        } finally {
            MinecraftForge.EVENT_BUS.unregister(breakListener);
            MinecraftForge.EVENT_BUS.unregister(placeListener);
        }
    }

    @GameTest(template = "empty")
    public static void replacementHistoryReclaimsAndReissuesDrops(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlock(target, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-history-test-player"));
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.ensureId(scepter);
        RealmwrightData.setAllowsReplacement(scepter, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);
        player.getInventory().setItem(9, new ItemStack(Items.STONE));

        BuilderOperationManager.BlueprintBatchResult built =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(target, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), true);
        helper.assertTrue(built.changed() == 1 && built.committed()
                        && helper.getLevel().getBlockState(target).is(Blocks.STONE)
                        && countItem(player, Items.DIRT) == 1 && countItem(player, Items.STONE) == 0,
                "History-enabled replacement did not commit its item ledger");

        helper.assertTrue(BuilderHistoryService.undo(player, scepter)
                        && helper.getLevel().getBlockState(target).is(Blocks.DIRT)
                        && countItem(player, Items.DIRT) == 0 && countItem(player, Items.STONE) == 1,
                "Undo did not reclaim the replacement drop and refund construction material");
        helper.assertTrue(BuilderHistoryService.redo(player, scepter)
                        && helper.getLevel().getBlockState(target).is(Blocks.STONE)
                        && countItem(player, Items.DIRT) == 1 && countItem(player, Items.STONE) == 0,
                "Redo did not debit construction material and reissue the replacement drop");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementHistoryCannotRedoUnbreakableBlockAfterLeavingCreative(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlock(target, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-history-hardness-test-player"));
        player.setGameMode(GameType.CREATIVE);
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.ensureId(scepter);
        RealmwrightData.setAllowsReplacement(scepter, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);

        BuilderOperationManager.BlueprintBatchResult built =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(target, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), true);
        helper.assertTrue(built.changed() == 1 && built.committed()
                        && BuilderHistoryService.undo(player, scepter)
                        && helper.getLevel().getBlockState(target).is(Blocks.BEDROCK),
                "Creative replacement history was not prepared for the cross-mode redo test");

        player.setGameMode(GameType.SURVIVAL);
        helper.assertTrue(!BuilderHistoryService.redo(player, scepter)
                        && helper.getLevel().getBlockState(target).is(Blocks.BEDROCK),
                "Survival redo bypassed the unbreakable-block replacement rule");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacementHistoryCannotRestoreGameMasterBlockAfterLosingPermission(
            GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlock(target, Blocks.COMMAND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "replacement-history-op-test-player"));
        player.setGameMode(GameType.CREATIVE);
        player.getServer().getPlayerList().getOps().add(
                new ServerOpListEntry(player.getGameProfile(), 4, false));
        helper.assertTrue(player.canUseGameMasterBlocks(),
                "GameTest fixture could not grant command-block permission");

        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        RealmwrightData.ensureId(scepter);
        RealmwrightData.setAllowsReplacement(scepter, true);
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);
        BuilderOperationManager.BlueprintBatchResult built =
                BuilderOperationManager.executeBlueprintBatch(player, scepter,
                        List.of(planned(target, Blocks.STONE.defaultBlockState())),
                        UUID.randomUUID(), true);
        helper.assertTrue(built.changed() == 1 && built.committed()
                        && helper.getLevel().getBlockState(target).is(Blocks.STONE),
                "Permitted creative replacement did not create command-block history");

        player.getServer().getPlayerList().deop(player.getGameProfile());
        helper.assertTrue(!player.canUseGameMasterBlocks()
                        && !BuilderHistoryService.undo(player, scepter)
                        && helper.getLevel().getBlockState(target).is(Blocks.STONE),
                "History restored a game-master block after the player lost permission");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void dropFreeRemovalSuppressesContainerContentsAndRestoresFluid(GameTestHelper helper) {
        BlockPos chest = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (!(helper.getLevel().getBlockEntity(chest) instanceof Container container)) {
            helper.fail("Test chest did not create a container block entity");
            return;
        }
        container.setItem(0, new ItemStack(Items.DIAMOND, 3));
        helper.assertTrue(BuilderOperationManager.removeWithoutBreakEffect(helper.getLevel(), chest),
                "Drop-free removal rejected a valid chest");
        helper.assertTrue(helper.getLevel().getBlockState(chest).isAir(),
                "Drop-free removal did not clear the chest");
        // Other GameTests in the same parallel batch intentionally drop anchor
        // items. Assert only against the chest payload so test layout changes
        // cannot create a false positive from a neighbouring structure.
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(chest).inflate(2.0D)).stream()
                        .noneMatch(entity -> entity.getItem().is(Items.DIAMOND)),
                "Drop-free removal leaked container item entities");

        BlockPos waterlogged = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlock(waterlogged, Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
        helper.assertTrue(BuilderOperationManager.removeWithoutBreakEffect(helper.getLevel(), waterlogged),
                "Drop-free removal rejected a waterlogged block");
        helper.assertTrue(helper.getLevel().getFluidState(waterlogged).is(Fluids.WATER),
                "Drop-free removal did not restore the contained fluid");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameBatchSupportDependencyPlacesOnSecondPass(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos dependent = support.above();
        List<TestPlacement> reversed = List.of(
                new TestPlacement(dependent, Blocks.TORCH.defaultBlockState()),
                new TestPlacement(support, Blocks.STONE.defaultBlockState()));

        List<TestPlacement> unresolved = BuilderOperationManager.processWithSingleRetry(reversed, attempt -> {
            if (!attempt.state().canSurvive(helper.getLevel(), attempt.pos())) return false;
            return helper.getLevel().setBlock(attempt.pos(), attempt.state(), Block.UPDATE_ALL);
        });

        helper.assertTrue(unresolved.isEmpty(), "Support-dependent placement remained unresolved");
        helper.assertTrue(helper.getLevel().getBlockState(support).is(Blocks.STONE),
                "The lower support was not placed");
        helper.assertTrue(helper.getLevel().getBlockState(dependent).is(Blocks.TORCH),
                "The upper dependent block was not placed on its bounded retry");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void continuationPrefixRejectsExternalWorldChanges(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState before = helper.getLevel().getBlockState(position);
        helper.getLevel().setBlock(position, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BuilderTransaction transaction = new BuilderTransaction(UUID.randomUUID(), UUID.randomUUID(),
                helper.getLevel().dimension(), BuilderTransaction.Type.BUILD, System.currentTimeMillis(),
                List.of(new WorldDelta(position, before, null,
                        Blocks.STONE.defaultBlockState(), null)),
                List.of(), List.of(), ItemStack.EMPTY, ItemStack.EMPTY,
                BuilderTransaction.State.APPLIED);

        helper.assertTrue(transaction.matchesAppliedAfter(helper.getLevel()),
                "Unmodified applied transaction should match its continuation prefix");
        helper.getLevel().setBlock(position, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(!transaction.matchesAppliedAfter(helper.getLevel()),
                "External edits must not be absorbed into a resumed transaction");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void transactionContinuationMergesRepeatedPositions(GameTestHelper helper) {
        UUID transactionId = UUID.randomUUID();
        UUID scepterId = UUID.randomUUID();
        BlockPos firstPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos secondPos = firstPos.east();
        CompoundTag earliestBefore = new CompoundTag();
        earliestBefore.putString("Marker", "earliest-before");
        CompoundTag intermediate = new CompoundTag();
        intermediate.putString("Marker", "intermediate");
        CompoundTag latestAfter = new CompoundTag();
        latestAfter.putString("Marker", "latest-after");

        BuilderTransaction first = new BuilderTransaction(transactionId, scepterId,
                helper.getLevel().dimension(), BuilderTransaction.Type.BLUEPRINT, 1L,
                List.of(
                        new WorldDelta(firstPos, Blocks.STONE.defaultBlockState(), earliestBefore,
                                Blocks.DIRT.defaultBlockState(), intermediate),
                        new WorldDelta(secondPos, Blocks.AIR.defaultBlockState(), null,
                                Blocks.OAK_PLANKS.defaultBlockState(), null)),
                List.of(), List.of(), ItemStack.EMPTY, ItemStack.EMPTY,
                BuilderTransaction.State.APPLIED);
        BuilderTransaction continuation = new BuilderTransaction(transactionId, scepterId,
                helper.getLevel().dimension(), BuilderTransaction.Type.BLUEPRINT, 1L,
                List.of(new WorldDelta(firstPos, Blocks.DIRT.defaultBlockState(), intermediate,
                        Blocks.GOLD_BLOCK.defaultBlockState(), latestAfter)),
                List.of(), List.of(), ItemStack.EMPTY, ItemStack.EMPTY,
                BuilderTransaction.State.APPLIED);

        List<WorldDelta> merged = first.append(continuation).worldDeltas();
        helper.assertTrue(merged.size() == 2, "Repeated positions were not merged");
        helper.assertTrue(merged.get(0).pos().equals(firstPos) && merged.get(1).pos().equals(secondPos),
                "Continuation changed the first-observed position order");
        helper.assertTrue(merged.get(0).beforeState().is(Blocks.STONE)
                        && earliestBefore.equals(merged.get(0).beforeBlockEntity()),
                "Continuation discarded the earliest before-image");
        helper.assertTrue(merged.get(0).afterState().is(Blocks.GOLD_BLOCK)
                        && latestAfter.equals(merged.get(0).afterBlockEntity()),
                "Continuation discarded the latest after-image");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void historyStabilizationDoesNotAdvanceUnknownBlockEntities(GameTestHelper helper) {
        BlockPos settling = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(settling, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        BlockState moving = Blocks.MOVING_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST);
        helper.getLevel().setBlock(settling, moving, Block.UPDATE_ALL);
        PistonMovingBlockEntity delayed = new PistonMovingBlockEntity(settling, moving,
                Blocks.STONE.defaultBlockState(), Direction.EAST, true, false);
        helper.getLevel().setBlockEntity(delayed);
        // Leave the moving block one invocation short of completion. A blanket "first tick" pass
        // would complete it, proving that stabilization had advanced an unrelated block entity.
        PistonMovingBlockEntity.tick(helper.getLevel(), settling, moving, delayed);
        PistonMovingBlockEntity.tick(helper.getLevel(), settling, moving, delayed);
        helper.assertTrue(helper.getLevel().getBlockState(settling).is(Blocks.MOVING_PISTON),
                "Test fixture settled before the builder initialization pass");

        BuilderOperationManager.stabilizeBuildBatch(helper.getLevel(), List.of(settling), true);
        helper.assertTrue(helper.getLevel().getBlockState(settling).is(Blocks.MOVING_PISTON),
                "History stabilization advanced an unknown block entity ticker");
        helper.assertTrue(helper.getLevel().getBlockEntity(settling) instanceof PistonMovingBlockEntity,
                "History stabilization removed an unknown block entity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void surfaceBatchesUseOneModeSpecificBlockSound(GameTestHelper helper) {
        BlockPos firstBase = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos secondBase = firstBase.east();
        BlockPos firstTarget = firstBase.above();
        BlockPos secondTarget = secondBase.above();
        helper.getLevel().setBlock(firstBase, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(secondBase, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "builder-sound-test-player"));
        ItemStack scepter = new ItemStack(ModItems.REALMWRIGHT_SCEPTER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, scepter);
        RealmwrightData.setBuildLimit(scepter, 2, BuilderRuntime.settings().maxBuildLimit());
        RealmwrightData.setDemolishLimit(scepter, 2, BuilderRuntime.settings().maxDemolishLimit());

        Set<BlockPos> soundPositions = Set.of(firstTarget, secondTarget);
        List<SoundEvent> sounds = new ArrayList<>();
        Consumer<PlayLevelSoundEvent.AtPosition> listener = event -> {
            if (event.getLevel() == helper.getLevel()
                    && event.getSource() == SoundSource.BLOCKS
                    && soundPositions.contains(BlockPos.containing(event.getPosition()))) {
                sounds.add(event.getSound().value());
            }
        };

        try {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                    PlayLevelSoundEvent.AtPosition.class, listener);

            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STONE, 2));
            BlockHitResult buildHit = new BlockHitResult(Vec3.atCenterOf(firstBase).add(0.0D, 0.5D, 0.0D),
                    Direction.UP, firstBase, false);
            BuilderOperationManager.Result build = BuilderOperationManager.executeValidatedSurface(
                    player, scepter, buildHit);
            helper.assertTrue(build.changed() == 2,
                    "Two-block build changed " + build.changed() + " blocks instead of two");
            helper.assertTrue(sounds.equals(List.of(Blocks.STONE.defaultBlockState().getSoundType(
                            helper.getLevel(), firstTarget, player).getPlaceSound())),
                    "Two-block build did not emit exactly one stone placement sound: " + sounds);

            sounds.clear();
            RealmwrightData.setMode(scepter, BuilderMode.DEMOLISH);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            BlockHitResult demolishHit = new BlockHitResult(Vec3.atCenterOf(firstTarget).add(0.0D, 0.5D, 0.0D),
                    Direction.UP, firstTarget, false);
            BuilderOperationManager.Result demolish = BuilderOperationManager.executeValidatedSurface(
                    player, scepter, demolishHit);
            helper.assertTrue(demolish.changed() == 2,
                    "Two-block demolition changed " + demolish.changed() + " blocks instead of two");
            helper.assertTrue(sounds.equals(List.of(Blocks.STONE.defaultBlockState().getSoundType(
                            helper.getLevel(), firstTarget, player).getBreakSound())),
                    "Two-block demolition did not emit exactly one stone break sound: " + sounds);
            helper.succeed();
        } catch (RuntimeException exception) {
            helper.fail(exception.getMessage());
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
        }
    }

    @GameTest(template = "empty")
    public static void blueprintPlanExceedsFormerBlockLimit(GameTestHelper helper) {
        int blockCount = 65_537;
        List<BlueprintData.BlockEntry> blocks = new ArrayList<>(blockCount);
        for (int x = 0; x < blockCount; x++) {
            blocks.add(new BlueprintData.BlockEntry(new BlockPos(x, 0, 0), 0, null));
        }
        try {
            BlueprintData source = new BlueprintData(UUID.randomUUID(), "Unbounded", "gametest", null, 0L,
                    BlueprintSaveMode.BLOCKS_ONLY, blockCount, 1, 1, BlockPos.ZERO,
                    List.of(Blocks.STONE.defaultBlockState()), blocks);
            BlockPos target = helper.absolutePos(new BlockPos(1, 1, 1));
            BlueprintPlacementPlan plan = BlueprintPlacementPlan.create(source, BlueprintTransform.NONE, target);

            helper.assertTrue(source.blocks().size() == blockCount,
                    "The data model rejected a blueprint above the former 65,536-block cap");
            helper.assertTrue(plan.blocks().size() == blockCount,
                    "A blueprint above the former 65,536-block cap did not produce a complete placement plan");
            helper.assertTrue(plan.blocks().get(blockCount - 1).worldPos().equals(target.offset(blockCount - 1, 0, 0)),
                    "The unbounded placement plan lost its final block");
            helper.succeed();
        } catch (Exception exception) {
            helper.fail("Large blueprint placement plan failed: " + exception.getMessage());
        }
    }

    private static BlueprintPlacementPlan.PlannedBlock planned(BlockPos pos, BlockState state) {
        return new BlueprintPlacementPlan.PlannedBlock(BlockPos.ZERO, pos, state, null);
    }

    private static int countItem(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) count += stack.getCount();
        }
        if (player.getOffhandItem().is(item)) count += player.getOffhandItem().getCount();
        return count;
    }

    private record TestPlacement(BlockPos pos, BlockState state) {
    }
}
