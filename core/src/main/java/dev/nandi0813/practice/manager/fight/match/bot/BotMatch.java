package dev.nandi0813.practice.manager.fight.match.bot;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import dev.nandi0813.practice.ZonePractice;
import dev.nandi0813.practice.manager.arena.arenas.Arena;
import dev.nandi0813.practice.manager.fight.match.Match;
import dev.nandi0813.practice.manager.fight.match.MatchManager;
import dev.nandi0813.practice.manager.fight.match.Round;
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
import dev.nandi0813.practice.manager.backend.ConfigManager;
import dev.nandi0813.practice.manager.backend.LanguageManager;
import dev.nandi0813.practice.manager.server.sound.SoundManager;
import dev.nandi0813.practice.manager.server.sound.SoundType;
import dev.nandi0813.practice.module.util.ClassImport;
import dev.nandi0813.practice.util.playerutil.PlayerUtil;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A 1-vs-bot duel where one human player fights against a Citizens NPC
 * driven by a neural-network model running on an external Python server.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li><b>Physics</b>: the NPC is a real {@code PLAYER} entity with full
 *       Minecraft physics — gravity, knockback, collision, HP, and death are
 *       all handled by the server and Citizens.  No manual simulation.</li>
 *   <li><b>Facing</b>: each tick the NPC is made to look at the human player
 *       via {@link NPC#faceLocation}.</li>
 *   <li><b>Input application</b>: {@link NeuralBotTrait#run()} is called by
 *       Citizens every tick and translates the latest
 *       {@link PvPBotClient.PredictResponse} into sprint, velocity, jump,
 *       attack, item-use, and hotbar-slot actions on the backing Bukkit Player.</li>
 *   <li><b>Inference</b>: a 10-frame sliding window is built each tick and an
 *       async HTTP POST to the Python server fires when the window is full.
 *       The response is stored in {@link #lastAction} and consumed next tick.</li>
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
 * Round start → {@link #spawnBot} → {@link #startBotLoop} → per-tick:
 * face player + extract frame + async predict → round end → {@link #stopBotLoop}
 * → {@link #despawnBot}.
 */
@Getter
public class BotMatch extends Match implements Team {

    // -----------------------------------------------------------------------
    // NPC / trait state
    // -----------------------------------------------------------------------

    /** The Citizens NPC backing the bot; null until spawned. */
    private NPC botNpc;

    /**
     * The {@link NeuralBotTrait} attached to {@link #botNpc}.
     * Null until spawned; used to push new actions each tick.
     */
    private NeuralBotTrait neuralTrait;

    // -----------------------------------------------------------------------
    // Inference state
    // -----------------------------------------------------------------------

    /** HTTP client for the Python inference server. */
    private final PvPBotClient botClient = new PvPBotClient();

    /** Circular frame buffer — 10 frames for the GRU window. */
    private final GameFrame[] window = new GameFrame[PvPBotClient.SEQUENCE_LENGTH];
    private int windowHead   = 0;
    private int windowFilled = 0;

    /** Per-tick task: faces NPC toward player + drives the inference loop. */
    private BukkitTask botTickTask;

    /** True while an async predict HTTP call is in-flight. */
    private final AtomicBoolean predicting = new AtomicBoolean(false);

    /** Latest model output; pushed to the trait at the start of each tick. */
    private volatile PvPBotClient.PredictResponse lastAction = null;

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

        // Prefetch item vocabulary in the background so the first frame is ready
        Bukkit.getScheduler().runTaskAsynchronously(ZonePractice.getInstance(),
                botClient::fetchVocab);
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
                ClassImport.getClasses().getPlayerUtil().clearInventory(player);
                player.setHealth(20);
                SoundManager.getInstance().getSound(SoundType.MATCH_PLAYER_TEMP_DEATH).play(this.getPeople());
                break;

            case ELIMINATED:
            default:
                this.getCurrentStat(player).end(true);
                PlayerUtil.setFightPlayer(player);
                ClassImport.getClasses().getPlayerUtil().clearInventory(player);
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
     *       the existing {@link ClassImport} EntityHider after spawn.</li>
     *   <li>{@link Equipment} equips the ladder kit.</li>
     *   <li>{@link NeuralBotTrait} drives per-tick inputs.</li>
     * </ul>
     */
    private void spawnBot(Location spawnLoc) {
        NPC npc = CitizensAPI.createAnonymousNPCRegistry(
                        new net.citizensnpcs.api.npc.MemoryNPCDataStore())
                .createNPC(EntityType.PLAYER, "PvpBot");

        // Do NOT call setProtected(true) — we want real HP and physics.
        // Citizens' default is unprotected, so no call needed.


        // ── Equipment: full ladder kit ────────────────────────────────────────
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        ItemStack[] storage = ladder.getKitData().getStorage();
        if (storage != null) {
            for (ItemStack item : storage) {
                if (item != null && item.getType() != Material.AIR) {
                    equipment.set(Equipment.EquipmentSlot.HAND, item);
                    break;
                }
            }
        }
        ItemStack[] armor = ladder.getKitData().getArmor();
        if (armor != null) {
            if (armor.length > 0 && armor[0] != null && armor[0].getType() != Material.AIR)
                equipment.set(Equipment.EquipmentSlot.BOOTS,      armor[0]);
            if (armor.length > 1 && armor[1] != null && armor[1].getType() != Material.AIR)
                equipment.set(Equipment.EquipmentSlot.LEGGINGS,   armor[1]);
            if (armor.length > 2 && armor[2] != null && armor[2].getType() != Material.AIR)
                equipment.set(Equipment.EquipmentSlot.CHESTPLATE, armor[2]);
            if (armor.length > 3 && armor[3] != null && armor[3].getType() != Material.AIR)
                equipment.set(Equipment.EquipmentSlot.HELMET,     armor[3]);
        }

        // ── NeuralBotTrait: drives per-tick player inputs ─────────────────────
        NeuralBotTrait trait = npc.getOrAddTrait(NeuralBotTrait.class);
        trait.setTarget(player);
        this.neuralTrait = trait;

        npc.spawn(spawnLoc);
        this.botNpc = npc;

        // ── Visibility: hide the NPC from everyone except the duelling player ─
        // PlayerFilter in Citizens 2 is a blocklist — addPlayer() hides FROM that player.
        // Instead we use the existing EntityHider after spawn so the entity is real.
        if (npc.getEntity() != null) {
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    ClassImport.getClasses().getEntityHider().hideEntity(online, npc.getEntity());
                }
            }
        }

        startBotLoop();
    }

    /** Despawns and destroys the Citizens NPC safely (no-op if already null). */
    private void despawnBot() {
        if (botNpc == null) return;
        final NPC npc = botNpc;
        botNpc      = null;
        neuralTrait = null;
        Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
            if (npc.isSpawned()) npc.despawn();
            npc.destroy();
            npc.getOwningRegistry().deregisterAll();
        });
    }

    // -----------------------------------------------------------------------
    // Bot loop — inference + facing
    // -----------------------------------------------------------------------

    /**
     * Per-tick task that:
     * <ol>
     *   <li>Makes the NPC face the human player.</li>
     *   <li>Pushes the latest action to {@link NeuralBotTrait}.</li>
     *   <li>Builds a {@link GameFrame} and appends it to the sliding window.</li>
     *   <li>Fires an async predict call when the window is full.</li>
     * </ol>
     */
    private void startBotLoop() {
        botTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (botNpc == null || !botNpc.isSpawned()
                        || status.equals(MatchStatus.END)
                        || status.equals(MatchStatus.OVER)) {
                    cancel();
                    return;
                }

                // ── 1. Push latest action to the trait ───────────────────────
                if (neuralTrait != null && lastAction != null) {
                    neuralTrait.setCurrentAction(lastAction);
                }

                // ── 3. Build and append the game-state frame ─────────────────
                GameFrame frame = extractGameFrame();
                window[windowHead] = frame;
                windowHead = (windowHead + 1) % PvPBotClient.SEQUENCE_LENGTH;
                if (windowFilled < PvPBotClient.SEQUENCE_LENGTH) windowFilled++;
                if (windowFilled < PvPBotClient.SEQUENCE_LENGTH) return;

                // ── 4. Ordered window snapshot ────────────────────────────────
                final GameFrame[] ordered = new GameFrame[PvPBotClient.SEQUENCE_LENGTH];
                for (int i = 0; i < PvPBotClient.SEQUENCE_LENGTH; i++)
                    ordered[i] = window[(windowHead + i) % PvPBotClient.SEQUENCE_LENGTH];

                // ── 5. Fire async predict (skip if one is already in-flight) ─
                if (!predicting.compareAndSet(false, true)) return;

                Bukkit.getScheduler().runTaskAsynchronously(ZonePractice.getInstance(), () -> {
                    try {
                        PvPBotClient.PredictResponse response = botClient.predict(ordered, false);
                        if (response != null) lastAction = response;
                    } finally {
                        predicting.set(false);
                    }
                });
            }
        }.runTaskTimer(ZonePractice.getInstance(), 1L, 1L);
    }

    private void stopBotLoop() {
        if (botTickTask != null) { botTickTask.cancel(); botTickTask = null; }
        predicting.set(false);
        lastAction   = null;
        windowHead   = 0;
        windowFilled = 0;
        Arrays.fill(window, null);
    }

    // -----------------------------------------------------------------------
    // Game-state extraction — MODEL_DESCRIPTION.md v2.1.0
    // -----------------------------------------------------------------------

    private GameFrame extractGameFrame() {
        Map<String, Object> values = new LinkedHashMap<>();

        double health    = player.getHealth();
        double maxHealth = Math.max(player.getMaxHealth(), 1.0);
        values.put("health",     health);
        values.put("max_health", maxHealth);

        Vector vel = player.getVelocity();
        values.put("vel_x", vel.getX());
        values.put("vel_y", vel.getY());
        values.put("vel_z", vel.getZ());

        values.put("yaw",        (double) player.getLocation().getYaw());
        values.put("pitch",      (double) player.getLocation().getPitch());
        values.put("food_level", (double) player.getFoodLevel());

        double totalArmor = 0;
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        if (armorContents != null) {
            for (ItemStack piece : armorContents) {
                if (piece != null && piece.getType() != Material.AIR)
                    totalArmor += getArmorPoints(piece);
            }
        }
        values.put("total_armor", totalArmor);

        // Bot position from the NPC entity itself — accurate since no manual simulation
        Location botLoc = botNpc.isSpawned() && botNpc.getEntity() != null
                ? botNpc.getEntity().getLocation()
                : player.getLocation(); // fallback if not yet spawned

        Location pLoc = player.getLocation();
        double relX = botLoc.getX() - pLoc.getX();
        double relY = botLoc.getY() - pLoc.getY();
        double relZ = botLoc.getZ() - pLoc.getZ();
        double dist = Math.sqrt(relX * relX + relZ * relZ);

        values.put("target_distance", dist);
        values.put("target_rel_x",    relX);
        values.put("target_rel_y",    relY);
        values.put("target_rel_z",    relZ);

        // Bot HP from the real entity
        double botHealth = (botNpc.isSpawned() && botNpc.getEntity() instanceof Player botEntity)
                ? botEntity.getHealth()
                : 20.0;
        values.put("target_health", botHealth);

        int attackCooldown = 100;
        try {
            java.lang.reflect.Method m = player.getClass()
                    .getMethod("getAttackCooldownProgress", float.class);
            attackCooldown = Math.round((float) m.invoke(player, 0f) * 100f);
        } catch (Exception ignored) {}
        values.put("attack_cooldown",   (double) attackCooldown);
        values.put("item_use_duration", 0.0);
        values.put("selected_slot",     (double) player.getInventory().getHeldItemSlot());

        boolean onGround = player.isOnGround();
        boolean jumping  = !onGround && vel.getY() > 0;
        values.put("is_on_ground",  onGround);
        values.put("is_jumping",    jumping);
        values.put("is_sprinting",  player.isSprinting());
        values.put("is_sneaking",   player.isSneaking());

        values.put("has_speed",        player.hasPotionEffect(PotionEffectType.SPEED));
        values.put("has_strength",     player.hasPotionEffect(PotionEffectType.INCREASE_DAMAGE));
        values.put("has_regeneration", player.hasPotionEffect(PotionEffectType.REGENERATION));
        values.put("has_poison",       player.hasPotionEffect(PotionEffectType.POISON));

        boolean usingItem = false;
        try {
            usingItem = (boolean) player.getClass().getMethod("isHandRaised").invoke(player);
        } catch (Exception ignored) {
            ItemStack held = player.getInventory().getItemInHand();
            if (held != null && held.getType() != Material.AIR)
                usingItem = held.getType().isEdible() || held.getType().name().contains("POTION");
        }
        values.put("is_using_item",      usingItem);
        values.put("target_is_blocking", false);

        int[] hotbar = new int[GameFrame.HOTBAR_SIZE];
        for (int slot = 0; slot < 9; slot++)
            hotbar[slot] = itemToVocabId(player.getInventory().getItem(slot));

        ItemStack mainHand;
        try {
            mainHand = (ItemStack) player.getInventory().getClass()
                    .getMethod("getItemInMainHand").invoke(player.getInventory());
        } catch (Exception ignored) {
            mainHand = player.getInventory().getItemInHand();
        }
        hotbar[9] = itemToVocabId(mainHand);

        return new GameFrame(values, hotbar);
    }

    private int itemToVocabId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0;
        return botClient.itemToId(item.getType().name());
    }

    private static double getArmorPoints(ItemStack piece) {
        String n = piece.getType().name();
        if (n.startsWith("DIAMOND") || n.startsWith("NETHERITE")) {
            if (n.contains("HELMET"))     return 3;
            if (n.contains("CHESTPLATE")) return 8;
            if (n.contains("LEGGINGS"))   return 6;
            if (n.contains("BOOTS"))      return 3;
        } else if (n.startsWith("IRON")) {
            if (n.contains("HELMET"))     return 2;
            if (n.contains("CHESTPLATE")) return 6;
            if (n.contains("LEGGINGS"))   return 5;
            if (n.contains("BOOTS"))      return 2;
        } else if (n.startsWith("CHAIN") || n.startsWith("GOLD")) {
            if (n.contains("HELMET"))     return 2;
            if (n.contains("CHESTPLATE")) return 5;
            if (n.contains("LEGGINGS"))   return 4;
            if (n.contains("BOOTS"))      return 1;
        } else if (n.startsWith("LEATHER")) {
            if (n.contains("HELMET"))     return 1;
            if (n.contains("CHESTPLATE")) return 3;
            if (n.contains("LEGGINGS"))   return 2;
            if (n.contains("BOOTS"))      return 1;
        }
        return 0;
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

