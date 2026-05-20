package com.xfestudio.mydimension;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.registry.ModItems;
import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.SleepFinishedTimeEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.Locale;

public class MyDimensionEvents {
    private static final int CLEAR_WEATHER_DURATION = 6000;

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
        if (event.getLevel().getLevel().dimension().equals(ModDimensions.ETHEREAL_MIND)
                && shouldBlockSpawn(event.getEntity(), event.getSpawnType())) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public void preventNaturalSpawnsInEtherealMind(MobSpawnEvent.FinalizeSpawn event) {
        Mob entity = event.getEntity();
        if (event.getLevel().getLevel().dimension().equals(ModDimensions.ETHEREAL_MIND)
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
            if (!mentionedPlayer.getUUID().equals(sourcePlayer.getUUID()) && mentionedPlayer.level().dimension().equals(ModDimensions.ETHEREAL_MIND)) {
                source.sendFailure(Component.translatable("message.mydimension.protected_tp", mentionedPlayer.getDisplayName()));
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent
    public void finishSleepInEtherealMind(SleepFinishedTimeEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(ModDimensions.ETHEREAL_MIND)) {
            return;
        }

        makeEtherealMorning(level, event.getNewTime());
    }

    private static String firstWord(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? input : input.substring(0, space);
    }

    private static void makeEtherealMorning(ServerLevel etherealMind, long morning) {
        ServerLevel overworld = etherealMind.getServer().overworld();
        overworld.setDayTime(morning);
        overworld.setWeatherParameters(CLEAR_WEATHER_DURATION, 0, false, false);
        syncEtherealMindWeather(etherealMind);
        syncEtherealMindTime(etherealMind);
    }

    private static void syncEtherealMindWeather(ServerLevel level) {
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

    private static void syncEtherealMindTime(ServerLevel level) {
        level.getServer().getPlayerList().broadcastAll(
                new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)),
                level.dimension()
        );
    }

    private static void sendTargetToEtherealMind(PlayerInteractEvent event, Entity targetEntity) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isShiftKeyDown()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player) || !(targetEntity instanceof LivingEntity target) || target instanceof ServerPlayer) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!stack.is(ModItems.RIFT.get())) {
            return;
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        if (!player.level().isClientSide()) {
            RiftItem.sendToEtherealMind(player, target, stack);
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
