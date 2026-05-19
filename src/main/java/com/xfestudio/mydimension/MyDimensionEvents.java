package com.xfestudio.mydimension;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xfestudio.mydimension.world.ModDimensions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.Locale;

public class MyDimensionEvents {
    @SubscribeEvent
    public void preventMobsInEtherealMind(EntityJoinLevelEvent event) {
        if (event.getLevel().dimension().equals(ModDimensions.ETHEREAL_MIND) && event.getEntity() instanceof Mob) {
            event.setCanceled(true);
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

        Collection<ServerPlayer> targets = parseExplicitPlayerTargets(source, input.substring(command.length()).trim());
        if (targets == null) {
            return;
        }

        for (ServerPlayer target : targets) {
            if (!target.getUUID().equals(sourcePlayer.getUUID()) && target.level().dimension().equals(ModDimensions.ETHEREAL_MIND)) {
                source.sendFailure(Component.translatable("message.mydimension.protected_tp", target.getDisplayName()));
                event.setCanceled(true);
                return;
            }
        }
    }

    private static String firstWord(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? input : input.substring(0, space);
    }

    private static Collection<ServerPlayer> parseExplicitPlayerTargets(CommandSourceStack source, String arguments) {
        if (arguments.isBlank()) {
            return null;
        }

        try {
            StringReader reader = new StringReader(arguments);
            EntitySelector selector = EntityArgument.players().parse(reader);
            return selector.findPlayers(source);
        } catch (CommandSyntaxException ignored) {
            return null;
        }
    }
}
