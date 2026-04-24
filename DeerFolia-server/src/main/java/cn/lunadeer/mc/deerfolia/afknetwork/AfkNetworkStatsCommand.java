package cn.lunadeer.mc.deerfolia.afknetwork;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class AfkNetworkStatsCommand {
    private AfkNetworkStatsCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("afknetstats")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(AfkNetworkStatsCommand::showSummary)
                .then(Commands.argument("player", EntityArgument.player()).executes(AfkNetworkStatsCommand::showPlayer))
        );
    }

    private static int showSummary(final CommandContext<CommandSourceStack> context) {
        for (final Component line : AfkNetworkManager.buildSummaryLines()) {
            context.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int showPlayer(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        final ServerPlayer player = EntityArgument.getPlayer(context, "player");
        for (final Component line : AfkNetworkManager.buildPlayerLines(player.getUUID(), player.getGameProfile().name())) {
            context.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }
}
