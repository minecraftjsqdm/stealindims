package com.homemine.stealindims.command;

import com.homemine.stealindims.ModAttachments;
import com.homemine.stealindims.raid.RaidDifficulty;
import com.homemine.stealindims.raid.RaidProgress;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /mojinunlock <a|b|c|s>} — force a difficulty open on the ladder.
 *
 * <p>The ladder is a single per-player chain (design §17): clearing a map on one difficulty unlocks the
 * next one for every map at once. That is fine for play, but it makes reaching S for a test run a chore,
 * so this command jumps the ladder straight to the difficulty named on the command line.
 *
 * <p>It never lowers progress (see {@link RaidProgress#unlockUpTo}), and it needs permission level 2
 * because it is a shortcut past the progression, not a gameplay action.
 *
 * <p>The map select screen is drawn from {@code RaidSelectInfoPayload}, which the server sends when the
 * player opens a warp device — so the newly unlocked row only shows up the next time that screen opens.
 */
public final class MojinUnlockCommand {

    /** The four difficulties, spelled the way the command line spells them. */
    private static final String[] LEVELS = {"a", "b", "c", "s"};

    private MojinUnlockCommand() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("mojinunlock")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("level", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(LEVELS, builder))
                                .executes(MojinUnlockCommand::run)));
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String raw = StringArgumentType.getString(context, "level");
        RaidDifficulty target = parse(raw);
        if (target == null) {
            source.sendFailure(Component.translatable("commands.stealindims.mojinunlock.invalid", raw));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        RaidProgress progress = player.getData(ModAttachments.RAID_PROGRESS);
        RaidDifficulty unlocked = progress.unlockUpTo(target);
        if (unlocked == null) {
            source.sendSuccess(() -> Component.translatable(
                    "commands.stealindims.mojinunlock.already", target.displayName()), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "commands.stealindims.mojinunlock.unlocked", unlocked.displayName()), true);
        return 1;
    }

    /** Case-insensitive {@code a|b|c|s} → {@link RaidDifficulty}, or {@code null} for anything else. */
    private static RaidDifficulty parse(String raw) {
        for (RaidDifficulty difficulty : RaidDifficulty.values()) {
            if (difficulty.name().equalsIgnoreCase(raw)) {
                return difficulty;
            }
        }
        return null;
    }
}
