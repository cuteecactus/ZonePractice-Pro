package dev.nandi0813.practice.command.event.arguments.Events;

import dev.nandi0813.practice.manager.backend.LanguageManager;
import dev.nandi0813.practice.manager.fight.event.EventManager;
import dev.nandi0813.practice.manager.fight.event.enums.EventType;
import dev.nandi0813.practice.manager.fight.event.events.duel.brackets.BracketsData;
import dev.nandi0813.practice.manager.fight.event.util.EventUtil;
import dev.nandi0813.practice.module.interfaces.KitData;
import dev.nandi0813.practice.util.Common;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public enum BracketsArg {
    ;

    public static void run(Player player, String label, String[] args) {
        if (!player.hasPermission("zpp.setup")) {
            Common.sendMMMessage(player, LanguageManager.getString("COMMAND.EVENT.NO-PERMISSION"));
            return;
        }

        // Checking if the event is live, if it is, it will send a message to the player and return.
        if (EventManager.getInstance().isEventLive(EventType.BRACKETS)) {
            Common.sendMMMessage(player, LanguageManager.getString("COMMAND.EVENT.ARGUMENTS.BRACKETS.EVENT-LIVE"));
            return;
        }

        BracketsData bracketsData = (BracketsData) EventManager.getInstance().getEventData().get(EventType.BRACKETS);
        // Checking if the player is trying to enable or disable the arena.
        if (args.length == 2 && args[1].equalsIgnoreCase("enable")) {
            if (!bracketsData.isEnabled())
                EventUtil.changeStatus(bracketsData, player);
            else
                Common.sendMMMessage(player, LanguageManager.getString("COMMAND.EVENT.ARGUMENTS.BRACKETS.EVENT-ALREADY-ENABLED"));

            return;
        } else if (args.length == 2 && args[1].equalsIgnoreCase("disable")) {
            if (bracketsData.isEnabled())
                EventUtil.changeStatus(bracketsData, player);
            else
                Common.sendMMMessage(player, LanguageManager.getString("COMMAND.EVENT.ARGUMENTS.BRACKETS.EVENT-ALREADY-DISABLED"));

            return;
        } else if (args.length == 2 && args[1].equalsIgnoreCase("help")) {
            sendHelpMSG(player, label);

            return;
        }

        // Checking if the arena is enabled, if it is, it will send a message to the player and return.
        if (bracketsData.isEnabled()) {
            Common.sendMMMessage(player, LanguageManager.getString("COMMAND.EVENT.ARGUMENTS.BRACKETS.CANT-EDIT"));
            return;
        }

        // Setting the kit for the event.
        if (args.length == 2 && args[1].equalsIgnoreCase("setkit")) {
            KitData kitData = bracketsData.getKitData();
            kitData.setKitData(player, true);

            Common.sendMMMessage(player, LanguageManager.getString("COMMAND.EVENT.ARGUMENTS.BRACKETS.KIT-SET"));
        } else if (args.length == 2 && args[1].equalsIgnoreCase("buildmode")) {
            // Toggle build mode
            bracketsData.setBuildMode(!bracketsData.isBuildMode());

            if (bracketsData.isBuildMode()) {
                // Check volume limit
                if (bracketsData.getCuboid() != null) {
                    int volume = bracketsData.getCuboid().getVolume();
                    int limit = BracketsData.getBuildModeVolumeLimit();

                    if (volume > limit) {
                        bracketsData.setBuildMode(false);
                        Common.sendMMMessage(player, "<red>✗ Cannot enable Build Mode! Arena is too large.");
                        Common.sendMMMessage(player, "<red>Current volume: " + volume + " blocks | Maximum: " + limit + " blocks");
                        Common.sendMMMessage(player, "<yellow>Tip: Make the arena smaller to use Build Mode.");
                        return;
                    }
                }

                Common.sendMMMessage(player, "<green>✓ Build Mode ENABLED for Brackets event!");
                Common.sendMMMessage(player, "<yellow>Each match will get a fresh arena copy.");
            } else {
                Common.sendMMMessage(player, "<yellow>Build Mode DISABLED for Brackets event.");
            }

            try {
                bracketsData.setData();
            } catch (Exception e) {
                Common.sendMMMessage(player, "<red>Error saving data: " + e.getMessage());
            }
        } else {
            sendHelpMSG(player, label);
        }
    }

    private static void sendHelpMSG(Player player, String label) {
        for (String line : LanguageManager.getList("COMMAND.EVENT.ARGUMENTS.BRACKETS.COMMAND-HELP"))
            Common.sendMMMessage(player, line.replace("%label%", label));
    }

    public static List<String> tabComplete(Player player, String[] args) {
        List<String> arguments = new ArrayList<>();

        if (!player.hasPermission("zpp.setup")) return arguments;

        if (args.length == 2) {
            arguments.add("setkit");
            arguments.add("buildmode");
            arguments.add("enable");
            arguments.add("disable");

            return StringUtil.copyPartialMatches(args[1], arguments, new ArrayList<>());
        }

        return arguments;
    }

}
