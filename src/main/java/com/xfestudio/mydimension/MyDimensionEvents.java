package com.xfestudio.mydimension;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xfestudio.mydimension.item.RiftItem;
import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.Locale;

public class MyDimensionEvents {
    @SubscribeEvent
    public void preventSpawnPlacementInEtherealMind(MobSpawnEvent.PositionCheck event) {
        if (event.getLevel().getLevel().dimension().equals(ModDimensions.ETHEREAL_MIND)
                && !event.getEntity().getPersistentData().getBoolean(RiftItem.IMPORTED_TO_ETHEREAL_MIND)) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public void preventNaturalSpawnsInEtherealMind(MobSpawnEvent.FinalizeSpawn event) {
        Mob entity = event.getEntity();
        if (event.getLevel().getLevel().dimension().equals(ModDimensions.ETHEREAL_MIND)
                && !entity.getPersistentData().getBoolean(RiftItem.IMPORTED_TO_ETHEREAL_MIND)) {
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

    private static String firstWord(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? input : input.substring(0, space);
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
