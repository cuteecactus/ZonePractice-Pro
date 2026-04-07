package dev.nandi0813.practice.manager.fight.match.bot;

import dev.nandi0813.practice.ZonePractice;
import dev.nandi0813.practice.manager.arena.arenas.Arena;
import dev.nandi0813.practice.manager.backend.LanguageManager;
import dev.nandi0813.practice.manager.fight.match.Match;
import dev.nandi0813.practice.manager.fight.match.MatchManager;
import dev.nandi0813.practice.manager.fight.match.Round;
import dev.nandi0813.practice.manager.fight.match.bot.neural.BotSpawnerUtil;
import dev.nandi0813.practice.manager.fight.match.enums.MatchStatus;
import dev.nandi0813.practice.manager.fight.match.enums.MatchType;
import dev.nandi0813.practice.manager.fight.match.enums.TeamEnum;
import dev.nandi0813.practice.manager.fight.match.interfaces.Team;
import dev.nandi0813.practice.manager.fight.match.util.TeamUtil;
import dev.nandi0813.practice.manager.inventory.InventoryManager;
import dev.nandi0813.practice.manager.ladder.abstraction.Ladder;
import dev.nandi0813.practice.manager.ladder.abstraction.interfaces.DeathResult;
import dev.nandi0813.practice.manager.nametag.NametagManager;
import dev.nandi0813.practice.manager.profile.ProfileManager;
import dev.nandi0813.practice.manager.server.sound.SoundManager;
import dev.nandi0813.practice.manager.server.sound.SoundType;
import dev.nandi0813.practice.util.playerutil.PlayerUtil;
import lombok.Getter;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;

/**
 * A 1-vs-bot duel where one human player fights against a Citizens NPC
 * driven by a neural-network model running on an external Python server.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li><b>Physics</b>: the NPC is a real {@code PLAYER} entity with normal
 *       Minecraft gravity, knockback, collision, HP, and death.</li>
 *   <li><b>Input + inference</b>: {@code neural_bot} trait ({@code PvPBotTrait})
 *       runs every tick, pushes raw bot/target/inventory state to the Python
 *       server, and applies returned keyboard/mouse predictions on the main thread.</li>
 *   <li><b>Lifecycle</b>: this class is responsible for spawning/despawning the NPC,
 *       applying ladder loadout, and round/match transitions.</li>
 * </ul>
 *
 * <h3>Kill detection</h3>
 * <ul>
 *   <li><b>Human dies</b>: the standard MatchListener handles HP → death → killPlayer.</li>
 *   <li><b>Bot dies</b>: {@link BotMatchListener} catches the NPC's
 *       {@code PlayerDeathEvent} (or the {@code EntityDamageByEntityEvent} where
 *       final damage ≥ NPC HP) and calls {@link #onBotDied(Player)}.</li>
 *   <li><b>Bot attacks human</b>: {@link NeuralBotTrait} calls
 *       {@code target.damage(amount, npcEntity)}, which fires a normal
 *       {@code EntityDamageByEntityEvent}.  {@link BotMatchListener} intercepts
 *       this to bypass the missing-Profile guard on the NPC entity and then
 *       lets the normal damage + kill pipeline proceed.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * Round start → {@link #spawnBot} → trait tick loop handles inference/actions
 * → round end → {@link #stopBotLoop} (no-op compatibility hook) → {@link #despawnBot}.
 */
@Getter
public class BotMatch extends Match implements Team {

    // -----------------------------------------------------------------------
    // NPC / trait state
    // -----------------------------------------------------------------------

    /** The Citizens NPC backing the bot; null until spawned. */
    private NPC botNpc;

    // -----------------------------------------------------------------------
    // Match player
    // -----------------------------------------------------------------------

    private final Player player;

    /** Who won the whole match; null = bot won (human lost all rounds). */
    private Player matchWinner;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public BotMatch(Ladder ladder, Arena arena, Player player, int winsNeeded) {
        super(ladder, arena, Collections.singletonList(player), winsNeeded);

        this.type   = MatchType.BOT_DUEL;
        this.player = player;

        NametagManager.getInstance().setNametag(
                player,
                TeamEnum.TEAM1.getPrefix(),
                TeamEnum.TEAM1.getNameColor(),
                TeamEnum.TEAM1.getSuffix(),
                20);

    }

    // -----------------------------------------------------------------------
    // Match / Round lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void startNextRound() {
        BotMatchRound round = new BotMatchRound(this, this.rounds.size() + 1);
        this.rounds.put(round.getRoundNumber(), round);

        stopBotLoop();
        despawnBot();

        for (String line : LanguageManager.getList("MATCH.BOT-DUEL.MATCH-START")) {
            this.sendMessage(
                    line.replace("%ladder%", ladder.getDisplayName())
                        .replace("%arena%",  arena.getDisplayName())
                        .replace("%player%", player.getName()),
                    false);
        }

        round.startRound();
        spawnBot(arena.getPosition2() != null ? arena.getPosition2() : arena.getPosition1());
    }

    @Override
    public BotMatchRound getCurrentRound() {
        return (BotMatchRound) this.rounds.get(this.rounds.size());
    }

    @Override
    public int getWonRounds(Player player) {
        int won = 0;
        for (Round r : this.rounds.values()) {
            if (((BotMatchRound) r).getRoundWinner() == player) won++;
        }
        return won;
    }

    @Override
    public void teleportPlayer(Player player) {
        player.teleport(arena.getPosition1());
    }

    // -----------------------------------------------------------------------
    // Kill / remove / end
    // -----------------------------------------------------------------------

    @Override
    protected void killPlayer(Player player, String deathMessage) {
        DeathResult result = handleLadderDeath(player);

        switch (result) {
            case TEMPORARY_DEATH:
                asRespawnableLadder().ifPresent(r ->
                        new dev.nandi0813.practice.manager.fight.match.util.TempKillPlayer(
                                getCurrentRound(), player, r.getRespawnTime()));
                dev.nandi0813.practice.manager.fight.util.PlayerUtil.clearInventory(player);
                player.setHealth(20);
                SoundManager.getInstance().getSound(SoundType.MATCH_PLAYER_TEMP_DEATH).play(this.getPeople());
                break;

            case ELIMINATED:
            default:
                this.getCurrentStat(player).end(true);
                PlayerUtil.setFightPlayer(player);
                dev.nandi0813.practice.manager.fight.util.PlayerUtil.clearInventory(player);
                player.setHealth(20);
                SoundManager.getInstance().getSound(SoundType.MATCH_PLAYER_DEATH).play(this.getPeople());
                getCurrentRound().endRound();
                break;
        }
    }

    @Override
    public void removePlayer(Player player, boolean quit) {
        if (!players.contains(player)) return;

        players.remove(player);
        MatchManager.getInstance().getPlayerMatches().remove(player);

        if (quit && !this.status.equals(MatchStatus.END) && !this.status.equals(MatchStatus.OVER)) {
            this.getCurrentStat(player).end(true);
            this.sendMessage(
                    TeamUtil.replaceTeamNames(
                            LanguageManager.getString("MATCH.BOT-DUEL.PLAYER-LEFT"),
                            player, TeamEnum.TEAM1),
                    true);
            getCurrentRound().endRound();
        }

        this.removePlayerFromBelowName(player);

        if (ZonePractice.getInstance().isEnabled() && player.isOnline()) {
            dev.nandi0813.practice.manager.profile.Profile profile =
                    ProfileManager.getInstance().getProfile(player);
            profile.setUnrankedLeft(profile.getUnrankedLeft() - 1);
            InventoryManager.getInstance().setLobbyInventory(player, true);
        }
    }

    @Override
    public Object getMatchWinner() { return matchWinner; }

    public Player getMatchWinnerPlayer() { return matchWinner; }

    @Override
    public boolean isEndMatch() {
        if (this.status.equals(MatchStatus.END)) return true;
        if (this.players.isEmpty()) { this.matchWinner = null; return true; }
        if (getWonRounds(player) >= winsNeeded) { this.matchWinner = player; return true; }

        int botWins = 0;
        for (Round r : rounds.values()) {
            if (((BotMatchRound) r).getRoundWinner() == null) botWins++;
        }
        if (botWins >= winsNeeded) { this.matchWinner = null; return true; }

        return false;
    }

    @Override
    public void endMatch() {
        stopBotLoop();
        despawnBot();
        super.endMatch();
    }

    // -----------------------------------------------------------------------
    // Team interface
    // -----------------------------------------------------------------------

    @Override
    public TeamEnum getTeam(Player player) { return TeamEnum.TEAM1; }

    // -----------------------------------------------------------------------
    // NPC – spawn / despawn
    // -----------------------------------------------------------------------

    /**
     * Spawns the Citizens NPC at {@code spawnLoc} and attaches all traits.
     *
     * <ul>
     *   <li>NPC is a real {@code PLAYER} entity — not protected, so Minecraft
     *       physics (gravity, knockback, HP depletion) apply normally.</li>
     *   <li>The NPC is hidden from all players except the duelling player via
     *       the plugin EntityHider after spawn.</li>
     *   <li>The bot receives the ladder kit inventory and armor immediately after spawn.</li>
     *   <li>{@link NeuralBotTrait} drives per-tick inputs.</li>
     * </ul>
     */
    private void spawnBot(Location spawnLoc) {
        NPC npc = BotSpawnerUtil.spawnNeuralBot(ZonePractice.getInstance(), spawnLoc, player);
        this.botNpc = npc;

        if (npc.getEntity() instanceof Player botPlayer) {
            ItemStack[] storage = ladder.getKitData().getStorage();
            if (storage != null) {
                for (int i = 0; i < Math.min(storage.length, 36); i++) {
                    ItemStack item = storage[i];
                    botPlayer.getInventory().setItem(i, item == null ? null : item.clone());
                }
            }

            ItemStack[] armor = ladder.getKitData().getArmor();
            if (armor != null) {
                ItemStack[] clonedArmor = new ItemStack[4];
                for (int i = 0; i < Math.min(armor.length, 4); i++) {
                    clonedArmor[i] = armor[i] == null ? null : armor[i].clone();
                }
                botPlayer.getInventory().setArmorContents(clonedArmor);
            }
        }

        // ── Visibility: hide the NPC from everyone except the duelling player ─
        // PlayerFilter in Citizens 2 is a blocklist — addPlayer() hides FROM that player.
        // Instead we use the existing EntityHider after spawn so the entity is real.
        if (npc.getEntity() != null) {
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    ZonePractice.getEntityHider().hideEntity(online, npc.getEntity());
                }
            }
        }
    }

    /** Despawns and destroys the Citizens NPC safely (no-op if already null). */
    private void despawnBot() {
        if (botNpc == null) return;
        final NPC npc = botNpc;
        botNpc      = null;
        Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
            if (npc.isSpawned()) npc.despawn();
            npc.destroy();
        });
    }

    private void stopBotLoop() {
        // Legacy no-op: inference now runs entirely inside PvPBotTrait.run().
    }

    // -----------------------------------------------------------------------
    // Bot death — called from BotMatchListener
    // -----------------------------------------------------------------------

    /**
     * Called by {@link BotMatchListener} when the NPC entity dies (HP reaches zero).
     * Ends the current round with the human player as the winner.
     *
     * @param humanWinner the human player who killed the bot
     */
    public void onBotDied(Player humanWinner) {
        if (!status.equals(MatchStatus.LIVE)) return;
        SoundManager.getInstance().getSound(SoundType.MATCH_PLAYER_DEATH).play(this.getPeople());
        getCurrentRound().setRoundWinner(humanWinner);
        getCurrentRound().endRound();
    }

    /** Returns the Citizens NPC; used by {@link BotMatchListener} for identity checks. */
    public NPC getBotNpc() {
        return botNpc;
    }
}

