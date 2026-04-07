package dev.nandi0813.practice.command.botduel;

import dev.nandi0813.practice.manager.fight.match.bot.neural.BotSpawnerUtil;
import dev.nandi0813.practice.util.Common;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnBotCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final Map<UUID, NPC> debugNpcs = new ConcurrentHashMap<>();

    public SpawnBotCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Common.sendConsoleMMMessage("<red>This command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("zpp.spawnbot")) {
            Common.sendMMMessage(player, "<red>You do not have permission to use /spawnbot.");
            return true;
        }

        Player target = player;
        if (args.length >= 1) {
            Player resolved = Bukkit.getPlayerExact(args[0]);
            if (resolved == null) {
                Common.sendMMMessage(player, "<red>Target player is not online.");
                return true;
            }
            target = resolved;
        }

        NPC oldNpc = debugNpcs.remove(player.getUniqueId());
        if (oldNpc != null) {
            if (oldNpc.isSpawned()) {
                oldNpc.despawn();
            }
            oldNpc.destroy();
        }

        NPC npc = BotSpawnerUtil.spawnNeuralBot(plugin, player.getLocation(), target);
        debugNpcs.put(player.getUniqueId(), npc);

        Common.sendMMMessage(
                player,
                "<green>Spawned neural bot at your location targeting <white>" + target.getName() + "<green>."
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            List<String> results = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], names, results);
            Collections.sort(results);
            return results;
        }
        return Collections.emptyList();
    }
}

