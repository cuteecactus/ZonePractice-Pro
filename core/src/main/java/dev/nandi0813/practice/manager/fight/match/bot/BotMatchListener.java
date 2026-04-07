package dev.nandi0813.practice.manager.fight.match.bot;

import dev.nandi0813.practice.manager.fight.match.Match;
import dev.nandi0813.practice.manager.fight.match.MatchManager;
import dev.nandi0813.practice.manager.fight.match.enums.MatchStatus;
import dev.nandi0813.practice.manager.fight.match.enums.RoundStatus;
import dev.nandi0813.practice.manager.fight.util.DeathCause;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public class BotMatchListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcHitsHuman(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player human)) return;

        BotMatch botMatch = getBotMatchByNpcEntity(e.getDamager());
        if (botMatch == null) return;
        if (!isLive(botMatch) || !botMatch.getPlayer().equals(human)) return;

        double remaining = human.getHealth() - e.getFinalDamage();
        if (remaining <= 0) {
            e.setDamage(0);
            e.setCancelled(true);
            botMatch.killPlayer(human, null,
                    DeathCause.PLAYER_ATTACK.getMessage()
                            .replace("%killer%", BotMatch.BOT_DISPLAY_NAME));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHumanHitsNpc(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player possibleNpc)) return;

        BotMatch botMatch = getBotMatchByNpcEntity(possibleNpc);
        if (botMatch == null) return;
        if (!isLive(botMatch)) return;

        Player humanAttacker = resolveDamagingPlayer(e.getDamager());
        if (humanAttacker != null && !humanAttacker.equals(botMatch.getPlayer())) return;

        double remaining = possibleNpc.getHealth() - e.getFinalDamage();
        if (remaining <= 0) {
            e.setDamage(0);
            e.setCancelled(true);
            botMatch.onBotDied(humanAttacker != null ? humanAttacker : botMatch.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcDeath(PlayerDeathEvent e) {
        Player died = e.getEntity();
        BotMatch botMatch = getBotMatchByNpcEntity(died);
        if (botMatch == null) return;
        if (!isLive(botMatch)) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setDeathMessage(null);

        Player killer = died.getKiller();
        botMatch.onBotDied(killer != null ? killer : botMatch.getPlayer());
    }

    private static BotMatch getBotMatchByNpcEntity(Entity entity) {
        for (Match match : MatchManager.getInstance().getLiveMatches()) {
            if (!(match instanceof BotMatch botMatch)) continue;
            if (botMatch.getBotNpc() == null) continue;
            Entity npcEntity = botMatch.getBotNpc().getEntity();
            if (npcEntity != null && npcEntity.equals(entity)) return botMatch;
        }
        return null;
    }

    private static boolean isLive(BotMatch match) {
        return match.getStatus() == MatchStatus.LIVE
                && match.getCurrentRound() != null
                && match.getCurrentRound().getRoundStatus() == RoundStatus.LIVE;
    }

    private static Player resolveDamagingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
