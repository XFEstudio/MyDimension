package com.xfestudio.mydimension;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xfestudio.mydimension.item.RiftAction;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.registry.ModEntities;
import com.xfestudio.mydimension.registry.ModItems;
import com.xfestudio.mydimension.world.ModDimensions;
import com.xfestudio.mydimension.world.entity.RiftAnchorEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.tags.FluidTags;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.SleepFinishedTimeEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.Locale;

public class MyDimensionEvents {
    private static final int CLEAR_WEATHER_DURATION = 6000;
    private int anchorParticleTicker;

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void sendMobToEtherealMind(PlayerInteractEvent.EntityInteract event) {
        sendTargetToEtherealMind(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void sendSpecificMobToEtherealMind(PlayerInteractEvent.EntityInteractSpecific event) {
        sendTargetToEtherealMind(event, event.getTarget());
    }

    @SubscribeEvent
    public void preventSpawnPlacementInEtherealMind(MobSpawnEvent.PositionCheck event) {
        if (ModDimensions.isMindDimension(event.getLevel().getLevel().dimension())
                && shouldBlockSpawn(event.getEntity(), event.getSpawnType())) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public void preventNaturalSpawnsInEtherealMind(MobSpawnEvent.FinalizeSpawn event) {
        Mob entity = event.getEntity();
        if (ModDimensions.isMindDimension(event.getLevel().getLevel().dimension())
                && shouldBlockSpawn(entity, event.getSpawnType())) {
            event.setSpawnCancelled(true);
        }
    }

    @SubscribeEvent
    public void protectPlayersFromOtherPlayerTeleports(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer sourcePlayer)) {
            return;
        }

        String input = event.getParseResults().getReader().getString().trim();
        String command = firstWord(input).toLowerCase(Locale.ROOT);
        if (!command.equals("tp") && !command.equals("teleport")) {
            return;
        }

        for (ServerPlayer mentionedPlayer : parseMentionedPlayers(source, input.substring(command.length()).trim())) {
            if (!mentionedPlayer.getUUID().equals(sourcePlayer.getUUID()) && ModDimensions.isMindDimension(mentionedPlayer.level().dimension())) {
                source.sendFailure(Component.translatable("message.mydimension.protected_tp", mentionedPlayer.getDisplayName()));
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent
    public void finishSleepInEtherealMind(SleepFinishedTimeEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !ModDimensions.isMindDimension(level.dimension())) {
            return;
        }

        makeMindMorning(level, event.getNewTime());
    }

    @SubscribeEvent
    public void spawnAnchorParticles(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++anchorParticleTicker % 10 != 0) {
            return;
        }

        MinecraftServer server = event.getServer();
        for (ServerPlayer owner : server.getPlayerList().getPlayers()) {
            refreshAnchorEntities(server, owner);
            for (RiftItem.AnchorPoint anchor : RiftItem.readAnchors(owner)) {
                ServerLevel level = server.getLevel(anchor.dimension());
                if (level == null || !ModDimensions.isMindDimension(level.dimension())) {
                    continue;
                }

                level.sendParticles(ParticleTypes.END_ROD, anchor.x(), anchor.y() + 0.75D, anchor.z(), 2, 0.25D, 0.45D, 0.25D, 0.01D);
                level.sendParticles(ParticleTypes.ENCHANT, anchor.x(), anchor.y() + 1.0D, anchor.z(), 6, 0.55D, 0.65D, 0.55D, 0.02D);
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, anchor.x(), anchor.y() + 0.15D, anchor.z(), 1, 0.18D, 0.08D, 0.18D, 0.0D);
            }
        }
    }

    @SubscribeEvent
    public void keepMindPlayersBreathing(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide() || !ModDimensions.isMindDimension(event.player.level().dimension())) {
            return;
        }

        event.player.setAirSupply(event.player.getMaxAirSupply());
    }

    @SubscribeEvent
    public void keepUnderwaterMiningFastInMind(PlayerEvent.BreakSpeed event) {
        if (!ModDimensions.isMindDimension(event.getEntity().level().dimension()) || !event.getEntity().isEyeInFluid(FluidTags.WATER)) {
            return;
        }

        event.setNewSpeed(event.getNewSpeed() * 5.0F);
    }

    private static void refreshAnchorEntities(MinecraftServer server, ServerPlayer owner) {
        java.util.List<RiftItem.AnchorPoint> anchors = RiftItem.readAnchors(owner);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof RiftAnchorEntity anchorEntity && owner.getUUID().equals(anchorEntity.owner()) && !matchesAnyAnchor(anchorEntity, anchors)) {
                    anchorEntity.discard();
                }
            }
        }

        for (RiftItem.AnchorPoint anchor : anchors) {
            ServerLevel level = server.getLevel(anchor.dimension());
            if (level != null && ModDimensions.isMindDimension(level.dimension()) && !hasAnchorEntity(level, owner, anchor)) {
                level.addFreshEntity(new RiftAnchorEntity(level, owner.getUUID(), anchor.x(), anchor.y(), anchor.z()));
            }
        }
    }

    private static boolean hasAnchorEntity(ServerLevel level, ServerPlayer owner, RiftItem.AnchorPoint anchor) {
        for (RiftAnchorEntity entity : level.getEntities(ModEntities.RIFT_ANCHOR.get(), entity -> owner.getUUID().equals(entity.owner()) && samePosition(entity, anchor))) {
            return true;
        }
        return false;
    }

    private static boolean matchesAnyAnchor(RiftAnchorEntity entity, java.util.List<RiftItem.AnchorPoint> anchors) {
        for (RiftItem.AnchorPoint anchor : anchors) {
            if (entity.level().dimension().equals(anchor.dimension()) && samePosition(entity, anchor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean samePosition(Entity entity, RiftItem.AnchorPoint anchor) {
        return entity.distanceToSqr(anchor.x(), anchor.y(), anchor.z()) < 0.04D;
    }

    private static String firstWord(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? input : input.substring(0, space);
    }

    private static void makeMindMorning(ServerLevel level, long morning) {
        level.setDayTime(morning);
        level.setWeatherParameters(CLEAR_WEATHER_DURATION, 0, false, false);
        syncMindWeather(level);
        syncMindTime(level);
    }

    private static void syncMindWeather(ServerLevel level) {
        level.setRainLevel(0.0F);
        level.setThunderLevel(0.0F);
        level.getServer().getPlayerList().broadcastAll(
                new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F),
                level.dimension()
        );
        level.getServer().getPlayerList().broadcastAll(
                new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.0F),
                level.dimension()
        );
        level.getServer().getPlayerList().broadcastAll(
                new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0F),
                level.dimension()
        );
    }

    private static void syncMindTime(ServerLevel level) {
        level.getServer().getPlayerList().broadcastAll(
                new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)),
                level.dimension()
        );
    }

    private static void sendTargetToEtherealMind(PlayerInteractEvent event, Entity targetEntity) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getEntity().isShiftKeyDown()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player) || !(targetEntity instanceof LivingEntity target) || target instanceof ServerPlayer) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        RiftAction action = RiftItem.getSelectedAction(stack);
        if (!stack.is(ModItems.RIFT.get()) || !action.sendsMob()) {
            return;
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        if (!player.level().isClientSide()) {
            RiftItem.sendToMind(player, target, stack, action);
        }
    }

    private static boolean shouldBlockSpawn(Mob entity, MobSpawnType spawnType) {
        return !entity.getPersistentData().getBoolean(RiftItem.IMPORTED_TO_ETHEREAL_MIND) && !isPlayerCreatedSpawn(spawnType);
    }

    private static boolean isPlayerCreatedSpawn(MobSpawnType spawnType) {
        return spawnType == MobSpawnType.SPAWN_EGG || spawnType == MobSpawnType.BUCKET;
    }

    private static Collection<ServerPlayer> parseMentionedPlayers(CommandSourceStack source, String arguments) {
        java.util.Set<ServerPlayer> players = new java.util.HashSet<>();
        if (arguments.isBlank()) {
            return players;
        }

        for (String argument : arguments.split("\\s+")) {
            try {
                StringReader reader = new StringReader(argument);
                EntitySelector selector = EntityArgument.players().parse(reader);
                players.addAll(selector.findPlayers(source));
            } catch (CommandSyntaxException ignored) {
            }
        }
        return players;
    }
}
