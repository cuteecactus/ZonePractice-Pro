package dev.nandi0813.practice.manager.fight.event.events.duel.brackets;

import dev.nandi0813.practice.manager.arena.arenas.ArenaCopy;
import dev.nandi0813.practice.manager.fight.event.enums.EventStatus;
import dev.nandi0813.practice.manager.fight.event.events.duel.interfaces.DuelFight;
import dev.nandi0813.practice.manager.fight.event.events.duel.interfaces.DuelListener;
import dev.nandi0813.practice.manager.fight.event.interfaces.Event;
import dev.nandi0813.practice.module.util.ClassImport;
import dev.nandi0813.practice.util.Cuboid;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public class BracketsListener extends DuelListener {

    @Override
    public void onEntityDamage(Event event, EntityDamageEvent e) {
        if (event instanceof Brackets brackets) {
            Player player = (Player) e.getEntity();

            if (!brackets.getStatus().equals(EventStatus.LIVE)) {
                e.setCancelled(true);
                return;
            }

            if (!brackets.isInFight(player)) {
                e.setCancelled(true);
                return;
            }

            if (e.getCause().equals(EntityDamageEvent.DamageCause.VOID)) {
                brackets.killPlayer(player, true);
                return;
            }

            if (player.getHealth() - e.getFinalDamage() <= 0) {
                e.setDamage(0);
                player.setHealth(player.getMaxHealth());

                brackets.killPlayer(player, true);
            }
        }
    }

    @Override
    public void onPlayerMove(Event event, PlayerMoveEvent e) {
        if (event instanceof Brackets brackets) {
            if (brackets.getStatus().equals(EventStatus.LIVE)) {
                Player player = e.getPlayer();

                // In build mode, check if player is in their assigned arena copy
                if (brackets.getEventData().isBuildMode() && !brackets.getGeneratedArenas().isEmpty()) {
                    if (brackets.isInFight(player)) {
                        DuelFight fight = brackets.getFight(player);
                        if (fight != null) {
                            // Find the arena assigned to this fight
                            ArenaCopy assignedArena = getArenaForFight(brackets, fight);
                            if (assignedArena != null && assignedArena.getCuboid() != null) {
                                if (!assignedArena.getCuboid().contains(player.getLocation())) {
                                    // Player left their arena - kill them
                                    brackets.killPlayer(player, true);
                                }
                                return; // Don't check main cuboid in build mode
                            }
                        }
                    }
                    // Spectators in build mode - don't enforce main cuboid
                    return;
                }

                // Standard mode - use parent's behavior (check main event cuboid)
                Cuboid cuboid = brackets.getEventData().getCuboid();
                if (!cuboid.contains(player.getLocation())) {
                    if (brackets.isInFight(player)) {
                        brackets.killPlayer(player, true);
                    } else {
                        player.teleport(cuboid.getCenter());
                    }
                }
            }
        }
    }

    /**
     * Helper method to get the arena assigned to a specific fight
     * Mirrors the logic from Brackets.getArenaForFight()
     */
    private ArenaCopy getArenaForFight(Brackets brackets, DuelFight fight) {
        // Try to find in existing assignments
        int fightIndex = brackets.getFights().indexOf(fight);
        if (fightIndex == -1) return null;

        int roundOffset = calculateRoundOffset(brackets, brackets.getRound() - 1);
        int arenaIndex = roundOffset + fightIndex;

        if (arenaIndex >= 0 && arenaIndex < brackets.getGeneratedArenas().size()) {
            return brackets.getGeneratedArenas().get(arenaIndex);
        }

        return null;
    }

    /**
     * Helper method to calculate round offset
     * Mirrors the logic from Brackets.calculateRoundOffset()
     */
    private int calculateRoundOffset(Brackets brackets, int roundNumber) {
        int offset = 0;
        int playersInRound = brackets.getPlayers().size();

        for (int i = 0; i < roundNumber; i++) {
            offset += playersInRound / 2;
            playersInRound = (playersInRound / 2) + (playersInRound % 2);
        }

        return offset;
    }

    @Override
    public void onProjectileLaunch(Event event, ProjectileLaunchEvent e) {
        if (event instanceof Brackets brackets) {
            Player player = (Player) e.getEntity().getShooter();

            DuelFight duelFight = brackets.getFight(player);
            if (duelFight == null) {
                e.setCancelled(true);
                return;
            }

            for (Player eventPlayer : brackets.getPlayers()) {
                if (duelFight.getPlayers().contains(eventPlayer)) return;

                ClassImport.getClasses().getEntityHider().hideEntity(eventPlayer, e.getEntity());
            }

            for (Player eventSpectator : brackets.getSpectators()) {
                if (duelFight.getSpectators().contains(eventSpectator)) return;

                ClassImport.getClasses().getEntityHider().hideEntity(eventSpectator, e.getEntity());
            }
        }
    }

    @Override
    public void onPlayerEggThrow(Event event, PlayerEggThrowEvent e) {
    }

}


