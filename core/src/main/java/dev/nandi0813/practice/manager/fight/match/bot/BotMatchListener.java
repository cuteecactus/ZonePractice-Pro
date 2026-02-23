package dev.nandi0813.practice.manager.fight.match.bot;

import com.github.juliarn.npclib.api.event.AttackNpcEvent;
import dev.nandi0813.practice.manager.fight.match.Match;
import dev.nandi0813.practice.manager.fight.match.MatchManager;
import org.bukkit.entity.Player;

/**
 * Listens for {@link AttackNpcEvent} fired by the NPC-Lib event system
 * and routes hits to the appropriate {@link BotMatch}.
 *
 * <p>Register via the NPC platform's event manager, <em>not</em> via Bukkit's
 * plugin manager, because these events live outside the Bukkit event bus.</p>
 */
public class BotMatchListener implements com.github.juliarn.npclib.api.event.manager.NpcEventConsumer<AttackNpcEvent> {

    @Override
    public void handle(AttackNpcEvent event) {
        Player attacker = event.player();

        Match match = MatchManager.getInstance().getLiveMatchByPlayer(attacker);
        if (!(match instanceof BotMatch botMatch)) return;

        botMatch.onBotHit(attacker, 0f);
    }
}
