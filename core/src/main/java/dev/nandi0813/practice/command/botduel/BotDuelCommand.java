package dev.nandi0813.practice.command.botduel;

import dev.nandi0813.practice.manager.arena.arenas.Arena;
import dev.nandi0813.practice.manager.backend.LanguageManager;
import dev.nandi0813.practice.manager.fight.match.bot.BotMatch;
import dev.nandi0813.practice.manager.ladder.LadderManager;
import dev.nandi0813.practice.manager.ladder.abstraction.normal.NormalLadder;
import dev.nandi0813.practice.manager.ladder.util.LadderUtil;
import dev.nandi0813.practice.manager.profile.Profile;
import dev.nandi0813.practice.manager.profile.ProfileManager;
import dev.nandi0813.practice.manager.profile.enums.ProfileStatus;
import dev.nandi0813.practice.util.Common;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /botduel [ladder]
 *
 * Starts a 1-vs-bot match for the issuing player.
 * If no ladder argument is given it defaults to "GAPPLE".
 * Requires permission {@code zpp.botduel}.
 *
 * Examples:
 *   /botduel           → uses GAPPLE
 *   /botduel NODEBUFF  → uses NODEBUFF
 */
public class BotDuelCommand implements CommandExecutor, TabCompleter {

    private static final String DEFAULT_LADDER = "GAPPLE";

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Common.sendConsoleMMMessage(LanguageManager.getString("CANT-USE-CONSOLE"));
            return false;
        }

        if (!player.hasPermission("zpp.botduel")) {
            Common.sendMMMessage(player, LanguageManager.getString("COMMAND.DUEL.NO-PERMISSION"));
            return false;
        }

        Profile profile = ProfileManager.getInstance().getProfile(player);
        if (!profile.getStatus().equals(ProfileStatus.LOBBY) || profile.isParty()) {
            Common.sendMMMessage(player, LanguageManager.getString("CANT-USE-COMMAND"));
            return false;
        }

        // Resolve ladder — arg[0] or default
        String ladderName = args.length >= 1 ? args[0] : DEFAULT_LADDER;
        NormalLadder ladder = LadderManager.getInstance().getLadder(ladderName);

        if (ladder == null || !ladder.isEnabled()) {
            Common.sendMMMessage(player,
                    LanguageManager.getString("COMMAND.QUEUES.UNRANKED.LADDER-NOT-FOUND")
                            .replace("%ladder%", ladderName));
            return false;
        }

        // Pick a random available arena for this ladder
        Arena arena = LadderUtil.getAvailableArena(ladder);
        if (arena == null) {
            Common.sendMMMessage(player, LanguageManager.getString("COMMAND.DUEL.NO-AVAILABLE-ARENA"));
            return false;
        }

        // Start the bot match
        BotMatch match = new BotMatch(ladder, arena, player, ladder.getRounds());
        match.startMatch();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            StringUtil.copyPartialMatches(
                    args[0],
                    LadderUtil.getLadderNames(LadderManager.getInstance().getEnabledLadders()),
                    names);
            Collections.sort(names);
            return names;
        }
        return Collections.emptyList();
    }
}

