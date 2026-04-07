package dev.nandi0813.practice.manager.fight.match.bot;

import dev.nandi0813.practice.manager.backend.LanguageManager;
import dev.nandi0813.practice.manager.fight.match.Match;
import dev.nandi0813.practice.manager.fight.match.Round;
import dev.nandi0813.practice.manager.fight.match.interfaces.PlayerWinner;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.entity.Player;

@Getter
@Setter
public class BotMatchRound extends Round implements PlayerWinner {

    private Player roundWinner;

    public BotMatchRound(Match match, int roundNumber) {
        super(match, roundNumber);
    }

    @Override
    public void sendEndMessage(boolean endMatch) {
        BotMatch botMatch = getBotMatch();

        if (endMatch) {
            Object winner = botMatch.getMatchWinner();
            if (winner != null) {
                String winnerName = winner instanceof Player player ? player.getName() : BotMatch.BOT_DISPLAY_NAME;
                for (String line : LanguageManager.getList("MATCH.BOT-DUEL.MATCH-END-WIN")) {
                    botMatch.sendMessage(
                            line.replace("%player%", winnerName),
                            true);
                }
            } else {
                for (String line : LanguageManager.getList("MATCH.BOT-DUEL.MATCH-END-LOSS")) {
                    botMatch.sendMessage(line, true);
                }
            }
        } else {
            String winnerName = roundWinner != null ? roundWinner.getName() : BotMatch.BOT_DISPLAY_NAME;
            int winnerRounds = roundWinner != null ? botMatch.getWonRounds(roundWinner) : getBotWonRounds(botMatch);
            int roundsRemaining = botMatch.getWinsNeeded() - winnerRounds;

            for (String line : LanguageManager.getList("MATCH.BOT-DUEL.ROUND-END")) {
                botMatch.sendMessage(
                        line.replace("%player%", winnerName)
                                .replace("%round%", String.valueOf(Math.max(roundsRemaining, 0))),
                        true);
            }
        }
    }

    private static int getBotWonRounds(BotMatch botMatch) {
        int botWins = 0;
        for (Round round : botMatch.getRounds().values()) {
            if (round instanceof BotMatchRound botRound && botRound.getRoundWinner() == null) {
                botWins++;
            }
        }
        return botWins;
    }

    @Override
    public Match getMatch() {
        return this.match;
    }

    public BotMatch getBotMatch() {
        return (BotMatch) this.match;
    }
}



