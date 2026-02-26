package dev.nandi0813.practice.manager.fight.match.bot;

import dev.nandi0813.practice.manager.fight.match.Match;
import dev.nandi0813.practice.manager.fight.match.MatchManager;
import dev.nandi0813.practice.manager.fight.match.enums.MatchStatus;
import dev.nandi0813.practice.manager.fight.match.enums.RoundStatus;
import dev.nandi0813.practice.manager.fight.util.DeathCause;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Handles the three edge cases that arise from using a Citizens NPC as a
 * real, physics-enabled player entity in a {@link BotMatch}.
 *
 * <h3>Why these handlers are needed</h3>
 * The NPC's backing entity is a Bukkit {@link Player} but has <em>no</em>
 * {@link dev.nandi0813.practice.manager.profile.Profile} and is not in
 * {@link MatchManager#getPlayerMatches()}.  The existing version-specific
 * MatchListeners guard on both of those facts, so without intervention:
 * <ul>
 *   <li>When the human hits the NPC — the MatchListener sees the NPC as
 *       target, finds no match for it, and does nothing.  The NPC takes
 *       real HP damage from vanilla, which is what we want — we just need
 *       to detect the kill.</li>
 *   <li>When the NPC hits the human — the NeuralBotTrait calls
 *       {@code human.damage(amount, npcEntity)}.  The MatchListener on the
 *       human's {@code EntityDamageByEntityEvent} calls
 *       {@code ProfileManager.getProfile(attacker)} on the NPC entity,
 *       returns null (no profile), and skips all processing including the
 *       HP-reduce → kill pipeline.</li>
 *   <li>When the NPC dies — no MatchListener handles it because the NPC has
 *       no profile / no match entry.</li>
 * </ul>
 *
 * <h3>Handled events</h3>
 * <ol>
 *   <li>{@link EntityDamageByEntityEvent} {@code HIGH} — NPC → human:
 *       the NPC has no profile so the MatchListener skips kill detection.
 *       We replicate it here: if final damage would reduce the human to ≤ 0
 *       HP we cancel the event, set damage to 0, and call
 *       {@code match.killPlayer} ourselves.</li>
 *   <li>{@link EntityDamageByEntityEvent} {@code HIGHEST} — human → NPC:
 *       vanilla damage is already being applied to the NPC (real physics).
 *       We only need to detect when the hit is lethal and call
 *       {@link BotMatch#onBotDied}.</li>
 *   <li>{@link PlayerDeathEvent} {@code NORMAL} — NPC entity death:
 *       cancel it (we don't want a death screen), determine the killer,
 *       and call {@link BotMatch#onBotDied}.</li>
 * </ol>
 */
public class BotMatchListener implements Listener {

    // -----------------------------------------------------------------------
    // NPC → Human: replicate kill pipeline that MatchListener would normally do
    // -----------------------------------------------------------------------

    /**
     * Fired for every {@code EntityDamageByEntityEvent}.  When the damager is
     * the NPC entity and the target is the human player in a BotMatch, the
     * version-specific MatchListener skips kill detection because the NPC has
     * no Profile.  We replicate only the HP → death path here.
     *
     * <p>Priority HIGH ensures we run after the MatchListener has already had
     * a chance to cancel the event (e.g. round not live), so we respect any
     * earlier cancellation.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcHitsHuman(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player human)) return;

        // Find a BotMatch where the damager is this match's NPC entity
        BotMatch botMatch = getBotMatchByNpcEntity(e.getDamager());
        if (botMatch == null) return;

        // Confirm the target is the human player in that match
        if (!botMatch.getPlayer().equals(human)) return;
        if (!botMatch.getStatus().equals(MatchStatus.LIVE)) return;
        if (!botMatch.getCurrentRound().getRoundStatus().equals(RoundStatus.LIVE)) return;

        // Let vanilla apply the damage; only intervene when it would be lethal
        double remaining = human.getHealth() - e.getFinalDamage();
        if (remaining <= 0) {
            e.setDamage(0);
            e.setCancelled(true);
            // Null killer = the "bot" — pass null; BotMatch/round handles the message
            botMatch.killPlayer(human, null,
                    DeathCause.PLAYER_ATTACK.getMessage()
                            .replace("%killer%", "PvpBot"));
        }
        // If not lethal: vanilla HP reduction, knockback, sounds all proceed normally
    }

    // -----------------------------------------------------------------------
    // Human → NPC: detect lethal hit before PlayerDeathEvent fires (1.8.8 path)
    // -----------------------------------------------------------------------

    /**
     * Fired when any entity damages the NPC's backing Player entity.
     * On 1.8.8 there is no {@link PlayerDeathEvent} cancellation; we must
     * intercept the lethal hit here.  On modern Paper the {@link PlayerDeathEvent}
     * handler below is preferred, but this provides a safety net for both versions.
     *
     * <p>Priority HIGHEST — runs last so we see the final damage value after all
     * other listeners (enchants, potions, armour) have modified it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHumanHitsNpc(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player possibleNpc)) return;

        BotMatch botMatch = getBotMatchByNpcEntity(possibleNpc);
        if (botMatch == null) return;

        // Identify the human attacker
        Player humanAttacker = null;
        if (e.getDamager() instanceof Player p) {
            humanAttacker = p;
        }
        // (Arrow / projectile cases can be handled here if needed in future)

        if (!botMatch.getStatus().equals(MatchStatus.LIVE)) return;
        if (!botMatch.getCurrentRound().getRoundStatus().equals(RoundStatus.LIVE)) return;

        double remaining = possibleNpc.getHealth() - e.getFinalDamage();
        if (remaining <= 0) {
            // Cancel so the server doesn't fire a confusing death screen for an NPC
            e.setDamage(0);
            e.setCancelled(true);
            final Player winner = humanAttacker;
            botMatch.onBotDied(winner);
        }
        // Non-lethal hits: vanilla HP loss and knockback happen naturally
    }

    // -----------------------------------------------------------------------
    // NPC entity death event (modern Paper — cancels the death screen)
    // -----------------------------------------------------------------------

    /**
     * On modern Paper, if a lethal hit slips past the HIGHEST EntityDamage handler
     * (e.g. due to absorption or other edge cases), this catches the actual
     * PlayerDeathEvent on the NPC entity.  We cancel the death screen and
     * the item drop, then call {@link BotMatch#onBotDied}.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcDeath(PlayerDeathEvent e) {
        Player died = e.getEntity();
        BotMatch botMatch = getBotMatchByNpcEntity(died);
        if (botMatch == null) return;

        // Cancel the vanilla death so the NPC doesn't drop items or show a death message
        e.getDrops().clear();
        e.setDroppedExp(0);

        // Determine who the killer was (may be null if the NPC died from fall/void)
        Player killer = died.getKiller();
        botMatch.onBotDied(killer != null ? killer : botMatch.getPlayer());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link BotMatch} whose NPC's backing entity is {@code entity},
     * or {@code null} if no match is found.
     */
    private static BotMatch getBotMatchByNpcEntity(Entity entity) {
        for (Match match : MatchManager.getInstance().getLiveMatches()) {
            if (!(match instanceof BotMatch botMatch)) continue;
            if (botMatch.getBotNpc() == null) continue;
            Entity npcEntity = botMatch.getBotNpc().getEntity();
            if (npcEntity != null && npcEntity.equals(entity)) return botMatch;
        }
        return null;
    }
}
