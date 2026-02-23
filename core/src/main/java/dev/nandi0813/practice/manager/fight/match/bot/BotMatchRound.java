package dev.nandi0813.practice.manager.fight.match.bot;

import dev.nandi0813.practice.manager.backend.LanguageManager;
import dev.nandi0813.practice.manager.fight.match.Match;
import dev.nandi0813.practice.manager.fight.match.Round;
import dev.nandi0813.practice.manager.fight.match.interfaces.PlayerWinner;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.entity.Player;

/**
 * Round implementation for a {@link BotMatch}.
 * The only winner that can ever be set is the human player —
 * the bot is not a real {@link Player}.
 */
@Getter
@Setter
public class BotMatchRound extends Round implements PlayerWinner {

    private Player roundWinner;

    public BotMatchRound(Match match, int roundNumber) {
        super(match, roundNumber);
    }

    // -----------------------------------------------------------------
    // End-of-round message
    // -----------------------------------------------------------------

    @Override
    public void sendEndMessage(boolean endMatch) {
        BotMatch botMatch = getBotMatch();

        if (endMatch) {
            Player winner = botMatch.getMatchWinnerPlayer();
            if (winner != null) {
                // Human won
                for (String line : LanguageManager.getList("MATCH.BOT-DUEL.MATCH-END-WIN")) {
                    botMatch.sendMessage(
                            line.replace("%player%", winner.getName()),
                            true);
                }
            } else {
                // Bot won / draw
                for (String line : LanguageManager.getList("MATCH.BOT-DUEL.MATCH-END-LOSS")) {
                    botMatch.sendMessage(line, true);
                }
            }
        } else {
            // Multi-round scenario (winsNeeded > 1)
            if (roundWinner != null) {
                for (String line : LanguageManager.getList("MATCH.BOT-DUEL.ROUND-END")) {
                    botMatch.sendMessage(
                            line.replace("%player%", roundWinner.getName())
                                .replace("%round%", String.valueOf(
                                        botMatch.getWinsNeeded() - botMatch.getWonRounds(roundWinner))),
                            true);
                }
            }
        }
    }

    @Override
    public Match getMatch() {
        return this.match;
    }

    public BotMatch getBotMatch() {
        return (BotMatch) this.match;
    }
}



