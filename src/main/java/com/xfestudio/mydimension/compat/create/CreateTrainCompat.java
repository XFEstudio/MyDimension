package com.xfestudio.mydimension.compat.create;

import com.xfestudio.mydimension.MyDimension;
import com.xfestudio.mydimension.registry.ModBlocks;
import com.xfestudio.mydimension.world.portal.MindPortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Create integration kept free of static references to Create classes.
 * This lets the base mod load normally when Create is not installed and also bridges
 * the portal-track API used by both the Create 0.5.1 and 6.x lines for Minecraft 1.20.1.
 */
public final class CreateTrainCompat {
    private static final String CREATE_MOD_ID = "create";
    private static final TagKey<Block> CREATE_TRACKS = BlockTags.create(
            new ResourceLocation(CREATE_MOD_ID, "tracks"));
    private static final AtomicBoolean PROVIDER_FAILURE_LOGGED = new AtomicBoolean();

    private static boolean registered;

    private CreateTrainCompat() {
    }

    public static void register() {
        if (registered || !ModList.get().isLoaded(CREATE_MOD_ID)) {
            return;
        }

        try {
            if (registerModernApi()) {
                registered = true;
                MyDimension.LOGGER.info("Enabled Create 6.x train portal compatibility");
                return;
            }
            if (registerLegacyApi()) {
                registered = true;
                MyDimension.LOGGER.info("Enabled Create 0.5.1 train portal compatibility");
                return;
            }
            MyDimension.LOGGER.warn("Create is installed, but its portal-track API is not supported");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            MyDimension.LOGGER.error("Failed to enable Create train portal compatibility", exception);
        }
    }

    /**
     * Create normally probes for portals on the first tick after a track is placed. Scheduling
     * neighboring tracks here also supports activating a portal after the tracks are already laid.
     */
    public static void onPortalActivated(ServerLevel level, MindPortalManager.PortalEndpoint endpoint) {
        if (!registered) {
            return;
        }

        Direction[] normalDirections = endpoint.axis() == Direction.Axis.X
                ? new Direction[] {Direction.NORTH, Direction.SOUTH}
                : new Direction[] {Direction.WEST, Direction.EAST};
        for (int horizontal = 0; horizontal < MindPortalManager.INNER_WIDTH; horizontal++) {
            for (int vertical = 0; vertical < MindPortalManager.INNER_HEIGHT; vertical++) {
                BlockPos portalPos = endpoint.axis() == Direction.Axis.X
                        ? endpoint.core().offset(horizontal, vertical, 0)
                        : endpoint.core().offset(0, vertical, horizontal);
                for (Direction direction : normalDirections) {
                    BlockPos trackPos = portalPos.relative(direction);
                    BlockState trackState = level.getBlockState(trackPos);
                    if (trackState.is(CREATE_TRACKS)
                            && !level.getBlockTicks().hasScheduledTick(trackPos, trackState.getBlock())) {
                        level.scheduleTick(trackPos, trackState.getBlock(), 1);
                    }
                }
            }
        }
    }

    private static boolean registerModernApi() throws ReflectiveOperationException {
        Class<?> providerClass = tryLoad("com.simibubi.create.api.contraption.train.PortalTrackProvider");
        if (providerClass == null) {
            return false;
        }

        Class<?> blockFaceClass = Class.forName("net.createmod.catnip.math.BlockFace");
        Method findExit = providerClass.getMethod("findExit", ServerLevel.class, blockFaceClass);
        Method getConnectedPos = blockFaceClass.getMethod("getConnectedPos");
        Method getFace = blockFaceClass.getMethod("getFace");
        Constructor<?> blockFaceConstructor = blockFaceClass.getConstructor(BlockPos.class, Direction.class);
        Constructor<?> exitConstructor = findExit.getReturnType()
                .getConstructor(ServerLevel.class, blockFaceClass);

        InvocationHandler handler = (proxy, method, arguments) -> {
            Object objectMethodResult = handleObjectMethod(proxy, method, arguments);
            if (objectMethodResult != Unhandled.INSTANCE) {
                return objectMethodResult;
            }
            if (!method.getName().equals("findExit") || arguments == null || arguments.length != 2) {
                throw new UnsupportedOperationException("Unsupported Create portal provider method: " + method);
            }
            return resolveModernExit(arguments, getConnectedPos, getFace, blockFaceConstructor, exitConstructor);
        };
        Object provider = Proxy.newProxyInstance(
                providerClass.getClassLoader(), new Class<?>[] {providerClass}, handler);

        Object registry = providerClass.getField("REGISTRY").get(null);
        Class<?> registryClass = Class.forName("com.simibubi.create.api.registry.SimpleRegistry");
        registryClass.getMethod("register", Object.class, Object.class)
                .invoke(registry, ModBlocks.MIND_PORTAL.get(), provider);
        return true;
    }

    private static boolean registerLegacyApi() throws ReflectiveOperationException {
        Class<?> allPortalTracksClass = tryLoad("com.simibubi.create.content.trains.track.AllPortalTracks");
        Class<?> providerClass = tryLoad(
                "com.simibubi.create.content.trains.track.AllPortalTracks$PortalTrackProvider");
        if (allPortalTracksClass == null || providerClass == null) {
            return false;
        }

        Class<?> pairClass = Class.forName("com.simibubi.create.foundation.utility.Pair");
        Class<?> blockFaceClass = Class.forName("com.simibubi.create.foundation.utility.BlockFace");
        Method getFirst = pairClass.getMethod("getFirst");
        Method getSecond = pairClass.getMethod("getSecond");
        Method pairFactory = pairClass.getMethod("of", Object.class, Object.class);
        Method getConnectedPos = blockFaceClass.getMethod("getConnectedPos");
        Method getFace = blockFaceClass.getMethod("getFace");
        Constructor<?> blockFaceConstructor = blockFaceClass.getConstructor(BlockPos.class, Direction.class);

        InvocationHandler handler = (proxy, method, arguments) -> {
            Object objectMethodResult = handleObjectMethod(proxy, method, arguments);
            if (objectMethodResult != Unhandled.INSTANCE) {
                return objectMethodResult;
            }
            if (!method.getName().equals("apply") || arguments == null || arguments.length != 1) {
                throw new UnsupportedOperationException("Unsupported Create portal provider method: " + method);
            }
            return resolveLegacyExit(arguments[0], getFirst, getSecond, getConnectedPos, getFace,
                    blockFaceConstructor, pairFactory);
        };
        Object provider = Proxy.newProxyInstance(
                providerClass.getClassLoader(), new Class<?>[] {providerClass}, handler);

        allPortalTracksClass.getMethod("registerIntegration", Block.class, providerClass)
                .invoke(null, ModBlocks.MIND_PORTAL.get(), provider);
        return true;
    }

    private static Object resolveModernExit(Object[] arguments, Method getConnectedPos, Method getFace,
                                            Constructor<?> blockFaceConstructor, Constructor<?> exitConstructor) {
        try {
            ServerLevel level = (ServerLevel) arguments[0];
            MindPortalManager.TrainPortalExit exit = resolve(level, arguments[1], getConnectedPos, getFace);
            if (exit == null) {
                return null;
            }
            Object outboundFace = blockFaceConstructor.newInstance(exit.trackPos(), exit.trackFace());
            return exitConstructor.newInstance(exit.level(), outboundFace);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            logProviderFailure(exception);
            return null;
        }
    }

    private static Object resolveLegacyExit(Object inboundPair, Method getFirst, Method getSecond,
                                            Method getConnectedPos, Method getFace,
                                            Constructor<?> blockFaceConstructor, Method pairFactory) {
        try {
            ServerLevel level = (ServerLevel) getFirst.invoke(inboundPair);
            Object inboundFace = getSecond.invoke(inboundPair);
            MindPortalManager.TrainPortalExit exit = resolve(level, inboundFace, getConnectedPos, getFace);
            if (exit == null) {
                return null;
            }
            Object outboundFace = blockFaceConstructor.newInstance(exit.trackPos(), exit.trackFace());
            return pairFactory.invoke(null, exit.level(), outboundFace);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            logProviderFailure(exception);
            return null;
        }
    }

    private static MindPortalManager.TrainPortalExit resolve(ServerLevel level, Object inboundFace,
                                                              Method getConnectedPos, Method getFace)
            throws ReflectiveOperationException {
        BlockPos portalPos = (BlockPos) getConnectedPos.invoke(inboundFace);
        Direction inboundDirection = (Direction) getFace.invoke(inboundFace);
        return MindPortalManager.findTrainExit(level, portalPos, inboundDirection);
    }

    private static Object handleObjectMethod(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() != Object.class) {
            return Unhandled.INSTANCE;
        }
        return switch (method.getName()) {
            case "toString" -> "MyDimension Create train portal provider";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
            default -> Unhandled.INSTANCE;
        };
    }

    private static Class<?> tryLoad(String className) throws LinkageError {
        try {
            return Class.forName(className, true, CreateTrainCompat.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static void logProviderFailure(Exception exception) {
        if (PROVIDER_FAILURE_LOGGED.compareAndSet(false, true)) {
            MyDimension.LOGGER.error("Create failed to resolve a MyDimension train portal exit", exception);
        }
    }

    private enum Unhandled {
        INSTANCE
    }
}
