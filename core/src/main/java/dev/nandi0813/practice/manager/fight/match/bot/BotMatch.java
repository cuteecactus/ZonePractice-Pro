package dev.nandi0813.practice.manager.fight.match.bot;

import com.github.juliarn.npclib.api.Npc;
import com.github.juliarn.npclib.api.Platform;
import com.github.juliarn.npclib.api.Position;
import com.github.juliarn.npclib.api.protocol.enums.ItemSlot;
import com.github.juliarn.npclib.api.protocol.meta.EntityMetadataFactory;
import com.github.juliarn.npclib.bukkit.util.BukkitPlatformUtil;
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
import dev.nandi0813.practice.manager.backend.LanguageManager;
import dev.nandi0813.practice.manager.server.sound.SoundManager;
import dev.nandi0813.practice.manager.server.sound.SoundType;
import dev.nandi0813.practice.module.util.ClassImport;
import dev.nandi0813.practice.util.playerutil.PlayerUtil;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A 1-vs-bot duel where one human player fights against an NPC
 * driven by a neural-network model running on an external Python server.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Spawned via {@link #startMatch()} (inherited).</li>
 *   <li>Every tick the bot loop samples the game state, sends it to the
 *       AI over HTTP (async), and applies the returned {@link PvPBotClient.PredictResponse}
 *       back on the main thread.</li>
 *   <li>When the player dies OR calls {@link #endMatch()} the NPC is
 *       despawned and the bot loop is stopped.</li>
 * </ol>
 */
@Getter
public class BotMatch extends Match implements Team {

    // -----------------------------------------------------------------------
    // NPC Platform (shared, initialised in ZonePractice.onEnable)
    // -----------------------------------------------------------------------

    private static Platform<World, Player, ItemStack, Plugin> getNpcPlatform() {
        return ZonePractice.getNpcPlatform();
    }

    // -----------------------------------------------------------------------
    // Bot state
    // -----------------------------------------------------------------------

    /** Simulated HP of the bot (not enforced by Bukkit, purely logical). */
    private float botHp = 20f;

    /** The spawned NPC; null until the platform future resolves. */
    private volatile Npc<World, Player, ItemStack, Plugin> botNpc;

    /** HTTP client — sliding window is managed here in BotMatch. */
    private final PvPBotClient botClient = new PvPBotClient();

    // Sliding window — circular buffer of the last SEQUENCE_LENGTH frames
    private final GameFrame[] window  = new GameFrame[PvPBotClient.SEQUENCE_LENGTH];
    private int  windowHead   = 0;
    private int  windowFilled = 0;

    /** The per-tick bot AI runnable (non-null while a round is LIVE). */
    private BukkitTask botTickTask;

    /** Separate every-tick movement task — runs independently of the HTTP loop. */
    private BukkitTask botMoveTask;

    /** True while an async HTTP predict call is in-flight — prevents pile-up. */
    private final AtomicBoolean predicting = new AtomicBoolean(false);

    /** Latest action from the model — consumed by the movement tick every tick. */
    private volatile PvPBotClient.PredictResponse lastAction = null;

    /** Tick counter — attack animation is suppressed until this reaches 0. */
    private int attackCooldownTick = 0;


    /** Melee range in blocks — attack only fires when the player is closer than this. */
    private static final double MELEE_RANGE_SQ = 3.2 * 3.2; // squared for cheap comparison

    /** Minimum ticks between bot attack swings (~10 ticks ≈ 0.5 s = vanilla sword base speed). */
    private static final int ATTACK_COOLDOWN_TICKS = 10;

    /** While > 0 the model velocity is skipped so knockback can play out uninterrupted. */
    private int knockbackTicks = 0;
    private static final int KNOCKBACK_DURATION = 6; // ticks to let knockback fly before resuming steering

    // -----------------------------------------------------------------------
    // Logical bot position & velocity (server-side simulation)
    // -----------------------------------------------------------------------

    /**
     * Logical world position of the bot — updated every tick.
     * {@code npc.position()} stays at the spawn point forever (the lib's
     * tracked Position is immutable), so we maintain our own copy.
     */
    private double botX, botY, botZ;

    /** Current velocity — simulated with gravity and friction each tick. */
    private double velX = 0, velY = 0, velZ = 0;

    private static final double GRAVITY            = 0.08;
    private static final double DRAG               = 0.91;   // horizontal friction per tick

    // -----------------------------------------------------------------------
    // Match players
    // -----------------------------------------------------------------------

    private final Player player;

    /** Who won the whole match; null = bot won / draw. */
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
    // Kill / remove / end logic
    // -----------------------------------------------------------------------

    @Override
    protected void killPlayer(Player player, String deathMessage) {
        DeathResult result = handleLadderDeath(player);

        switch (result) {
            case TEMPORARY_DEATH:
                asRespawnableLadder().ifPresent(r ->
                        new dev.nandi0813.practice.manager.fight.match.util.TempKillPlayer(getCurrentRound(), player, r.getRespawnTime()));
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

                // Bot wins this round
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
                            player,
                            TeamEnum.TEAM1),
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
    public Object getMatchWinner() {
        return matchWinner;
    }

    /** Typed convenience accessor used by {@link BotMatchRound}. */
    public Player getMatchWinnerPlayer() {
        return matchWinner;
    }

    @Override
    public boolean isEndMatch() {
        if (this.status.equals(MatchStatus.END)) return true;

        // Player left the match
        if (this.players.isEmpty()) {
            this.matchWinner = null; // bot wins
            return true;
        }

        // Human has accumulated enough round wins
        if (getWonRounds(player) >= winsNeeded) {
            this.matchWinner = player;
            return true;
        }

        // Bot has enough virtual round wins
        int botWins = 0;
        for (Round r : rounds.values()) {
            if (((BotMatchRound) r).getRoundWinner() == null) botWins++;
        }
        if (botWins >= winsNeeded) {
            this.matchWinner = null; // bot wins
            return true;
        }

        return false;
    }

    @Override
    public void endMatch() {
        stopBotLoop();  // also resets the sliding window
        despawnBot();
        super.endMatch();
    }

    // -----------------------------------------------------------------------
    // Team interface (player is always TEAM1)
    // -----------------------------------------------------------------------

    @Override
    public TeamEnum getTeam(Player player) {
        return TeamEnum.TEAM1;
    }

    // -----------------------------------------------------------------------
    // NPC – spawn / despawn
    // -----------------------------------------------------------------------

    /**
     * Spawns the bot NPC at {@code spawnLoc} and — after the platform
     * resolves the profile — equips it and starts the AI tick loop.
     */
    private void spawnBot(Location spawnLoc) {
        // Initialise logical position from the spawn location
        botX = spawnLoc.getX();
        botY = spawnLoc.getY();
        botZ = spawnLoc.getZ();
        velX = 0; velY = 0; velZ = 0;

        getNpcPlatform()
                .newNpcBuilder()
                .position(BukkitPlatformUtil.positionFromBukkitLegacy(spawnLoc))
                .npcSettings(s -> s.trackingRule((npc, p) -> p.equals(player)))
                .profile(com.github.juliarn.npclib.api.profile.Profile.unresolved("PvpBot"))
                .thenAccept(builder -> {
                    Npc<World, Player, ItemStack, Plugin> npc = builder.buildAndTrack();
                    this.botNpc = npc;

                    // Equip the bot with the full ladder kit on the main thread
                    Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
                        equipBotKit(npc);
                        startBotLoop(npc);
                    });
                });
    }

    /** Completely removes the NPC from the world for all viewers. */
    private void despawnBot() {
        if (botNpc != null) {
            Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
                if (botNpc != null) {
                    botNpc.unlink();
                    botNpc = null;
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    // AI tick loop
    // -----------------------------------------------------------------------

    /**
     * Runs every tick.  The game-state snapshot is built on the main thread,
     * the HTTP call is made asynchronously, and the resulting
     * {@link PvPBotClient.PredictResponse} is applied back on the main thread.
     */
    private void startBotLoop(Npc<World, Player, ItemStack, Plugin> npc) {

        // ── Movement loop: every tick ──────────────────────────────────────────
        // Runs independently of HTTP latency — uses whatever lastAction was last
        // received from the model, or a default "walk toward player" if none yet.
        botMoveTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (botNpc == null
                        || status.equals(MatchStatus.END)
                        || status.equals(MatchStatus.OVER)) {
                    cancel();
                    return;
                }
                tickMovement(npc, lastAction);
            }
        }.runTaskTimer(ZonePractice.getInstance(), 0L, 1L);

        // ── AI / HTTP loop: every tick, skips if a request is in-flight ───────
        botTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (botNpc == null
                        || status.equals(MatchStatus.END)
                        || status.equals(MatchStatus.OVER)) {
                    cancel();
                    return;
                }

                // Build frame on main thread
                GameFrame frame = extractGameFrame(npc);

                // Push into circular window
                window[windowHead] = frame;
                windowHead = (windowHead + 1) % PvPBotClient.SEQUENCE_LENGTH;
                if (windowFilled < PvPBotClient.SEQUENCE_LENGTH) windowFilled++;
                if (windowFilled < PvPBotClient.SEQUENCE_LENGTH) return;

                final GameFrame[] ordered = new GameFrame[PvPBotClient.SEQUENCE_LENGTH];
                for (int i = 0; i < PvPBotClient.SEQUENCE_LENGTH; i++)
                    ordered[i] = window[(windowHead + i) % PvPBotClient.SEQUENCE_LENGTH];

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
        }.runTaskTimer(ZonePractice.getInstance(), 0L, 1L);
    }

    /**
     * Equips the bot NPC with the full ladder kit.
     * Bukkit armor array order: [0]=boots, [1]=leggings, [2]=chestplate, [3]=helmet.
     * Must be called on the main thread.
     */
    private void equipBotKit(Npc<World, Player, ItemStack, Plugin> npc) {
        // --- main hand: first non-null item in storage ---
        ItemStack[] storage = ladder.getKitData().getStorage();
        if (storage != null) {
            for (ItemStack item : storage) {
                if (item != null && item.getType() != Material.AIR) {
                    npc.changeItem(ItemSlot.MAIN_HAND, item).scheduleForTracked();
                    break;
                }
            }
        }

        // --- armor ---
        ItemStack[] armor = ladder.getKitData().getArmor();
        if (armor != null) {
            // Bukkit: [0]=boots [1]=leggings [2]=chestplate [3]=helmet
            if (armor.length > 0 && armor[0] != null && armor[0].getType() != Material.AIR)
                npc.changeItem(ItemSlot.FEET,       armor[0]).scheduleForTracked();
            if (armor.length > 1 && armor[1] != null && armor[1].getType() != Material.AIR)
                npc.changeItem(ItemSlot.LEGS,    armor[1]).scheduleForTracked();
            if (armor.length > 2 && armor[2] != null && armor[2].getType() != Material.AIR)
                npc.changeItem(ItemSlot.CHEST,  armor[2]).scheduleForTracked();
            if (armor.length > 3 && armor[3] != null && armor[3].getType() != Material.AIR)
                npc.changeItem(ItemSlot.HEAD,      armor[3]).scheduleForTracked();
        }
    }

    /**
     * Called every tick by the movement loop.
     * {@code action} may be null before the first HTTP response arrives.
     */
    private void tickMovement(Npc<World, Player, ItemStack, Plugin> npc,
                              PvPBotClient.PredictResponse action) {

        double groundY = arena.getPosition2() != null
                ? arena.getPosition2().getY() : arena.getPosition1().getY();
        Location pLoc = player.getLocation();
        boolean onGround = (botY <= groundY + 0.05);

        // ── 1. Steering ───────────────────────────────────────────────────────
        if (knockbackTicks > 0) {
            knockbackTicks--;
            // Decay knockback velocity horizontally each tick
            velX *= 0.6;
            velZ *= 0.6;
        } else {
            double toPlayerDx = pLoc.getX() - botX;
            double toPlayerDz = pLoc.getZ() - botZ;
            double dist = Math.sqrt(toPlayerDx * toPlayerDx + toPlayerDz * toPlayerDz);

            if (dist > 0.5) {
                boolean sprint = action != null && action.sprint;
                double speed = sprint ? 0.25 : 0.15;
                // Set directly — no drag so the NPC actually moves at consistent speed
                velX = (toPlayerDx / dist) * speed;
                velZ = (toPlayerDz / dist) * speed;
            } else {
                velX = 0;
                velZ = 0;
            }

            boolean jump = action != null && action.jump;
            if (jump && onGround) {
                velY = 0.42;
            }
        }

        // ── 2. Gravity — only when airborne ──────────────────────────────────
        if (!onGround || velY > 0) {
            velY -= GRAVITY;
        }

        // ── 3. Update logical position + ground clamp ────────────────────────
        double newX = botX + velX;
        double newY = botY + velY;
        double newZ = botZ + velZ;

        if (newY <= groundY) {
            newY = groundY;
            velY = 0;
        }

        double dx = newX - botX;
        double dy = newY - botY;
        double dz = newZ - botZ;
        botX = newX;
        botY = newY;
        botZ = newZ;

        // ── 4. Facing yaw toward player ──────────────────────────────────────
        double yawRad = Math.atan2(pLoc.getZ() - botZ, pLoc.getX() - botX);
        float  bodyYaw = (float) Math.toDegrees(yawRad) - 90f;

        // ── 5. Send movement packet ───────────────────────────────────────────
        // moveRelative handles the walking leg animation + incremental position.
        npc.moveRelative(dx, dy, dz, bodyYaw, 0f).scheduleForTracked();

        // ── 6. Head looks at player eye level ────────────────────────────────
        Position playerPos = Position.position(
                pLoc.getX(), pLoc.getY() + 1.6, pLoc.getZ(),
                npc.position().worldId());
        npc.lookAt(playerPos).scheduleForTracked();

        // ── 7. Sprint metadata ────────────────────────────────────────────────
        boolean isSprinting = (Math.abs(velX) > 0.01 || Math.abs(velZ) > 0.01)
                && (action == null || action.sprint);
        npc.changeMetadata(EntityMetadataFactory.sprintingMetaFactory(), isSprinting)
           .scheduleForTracked();

        if (action == null) return; // no model output yet — skip combat

        // ── 8. Attack: animation + damage + player knockback ─────────────────
        if (attackCooldownTick > 0) attackCooldownTick--;

        if (action.attack && attackCooldownTick == 0) {
            double ddx = botX - pLoc.getX();
            double ddy = botY - pLoc.getY();
            double ddz = botZ - pLoc.getZ();
            if (ddx * ddx + ddy * ddy + ddz * ddz <= MELEE_RANGE_SQ) {
                npc.attack().scheduleForTracked();
                attackCooldownTick = ATTACK_COOLDOWN_TICKS;

                // Damage
                double botDamage = dev.nandi0813.practice.manager.backend.ConfigManager
                        .getDouble("MATCH-SETTINGS.BOT-DUEL.BOT-ATTACK-DAMAGE");
                if (botDamage <= 0) botDamage = 5.0;
                player.damage(botDamage);

                // Knockback — push player away from bot position
                double kbYaw = Math.atan2(pLoc.getZ() - botZ, pLoc.getX() - botX);
                double kbX = Math.cos(kbYaw) * 0.45;
                double kbZ = Math.sin(kbYaw) * 0.45;
                org.bukkit.util.Vector kb = new org.bukkit.util.Vector(kbX, 0.35, kbZ);
                player.setVelocity(kb);
            }
        }

        // ── 9. Use-item / blocking ────────────────────────────────────────────
        npc.changeMetadata(EntityMetadataFactory.blockingMetaFactory(), action.useItem)
           .scheduleForTracked();
    }

    /** Stops and nullifies the bot tick task. */
    private void stopBotLoop() {
        if (botTickTask != null) { botTickTask.cancel(); botTickTask = null; }
        if (botMoveTask != null) { botMoveTask.cancel(); botMoveTask = null; }
        predicting.set(false);
        lastAction         = null;
        attackCooldownTick = 0;
        knockbackTicks     = 0;
        windowHead         = 0;
        windowFilled       = 0;
        java.util.Arrays.fill(window, null);
    }

    // -----------------------------------------------------------------------
    // Game-state extraction (placeholder — fill with real normalisation later)
    // -----------------------------------------------------------------------

    /**
     * Builds a {@link GameFrame} from the current server state using the
     * recommended <b>named-value map mode</b> so that missing/future columns
     * default to 0 on the server side.
     *
     * <p><b>TODO:</b> Add remaining columns from {@code GET /col_order} as
     * real feature extraction is implemented.
     */
    private GameFrame extractGameFrame(Npc<World, Player, ItemStack, Plugin> npc) {
        java.util.Map<String, Double> values = new java.util.LinkedHashMap<>();

        // --- player state ---
        values.put("health",     player.getHealth());
        values.put("max_health", player.getMaxHealth());
        values.put("food_level", (double) player.getFoodLevel());
        values.put("saturation", (double) player.getSaturation());

        org.bukkit.util.Vector vel = player.getVelocity();
        values.put("vel_x", vel.getX());
        values.put("vel_y", vel.getY());
        values.put("vel_z", vel.getZ());

        values.put("yaw",   (double) player.getLocation().getYaw());
        values.put("pitch", (double) player.getLocation().getPitch());

        values.put("is_on_ground", player.isOnGround() ? 1.0 : 0.0);
        values.put("is_sprinting", player.isSprinting() ? 1.0 : 0.0);

        // --- relative bot position (use logical position, not stale npc.position()) ---
        values.put("target_rel_x",  botX - player.getLocation().getX());
        values.put("target_rel_y",  botY - player.getLocation().getY());
        values.put("target_rel_z",  botZ - player.getLocation().getZ());
        double dx = botX - player.getLocation().getX();
        double dz = botZ - player.getLocation().getZ();
        values.put("target_distance", Math.sqrt(dx * dx + dz * dz));
        values.put("target_health", (double) botHp);

        // --- categorical (all 0 = AIR/NONE until item-vocab lookup is implemented) ---
        int[] categorical = new int[GameFrame.NUM_CATEGORICAL];

        return new GameFrame(values, categorical);
    }

    // -----------------------------------------------------------------------
    // Bot damage handling (called from BotMatchListener on AttackNpcEvent)
    // -----------------------------------------------------------------------

    /**
     * Called when the human player successfully hits the bot NPC.
     * Applies logical damage, knockback, and ends the round when the bot dies.
     *
     * @param attacker the player who attacked the bot
     * @param damage   damage amount (use 0 to apply default of 1.5 hearts)
     */
    public void onBotHit(Player attacker, float damage) {
        if (damage <= 0)
            damage = (float) dev.nandi0813.practice.manager.backend.ConfigManager
                    .getDouble("MATCH-SETTINGS.BOT-DUEL.BOT-DEFAULT-DAMAGE");

        botHp -= damage;
        SoundManager.getInstance().getSound(SoundType.MATCH_PLAYER_TEMP_DEATH).play(this.getPeople());

        // Apply knockback into our logical velocity — physics loop will move the NPC naturally
        if (botNpc != null) {
            double yaw = attacker.getLocation().getYaw();
            double kbX = -Math.cos(Math.toRadians(yaw + 90)) * 0.4;
            double kbZ = -Math.sin(Math.toRadians(yaw + 90)) * 0.4;

            // Set velocity directly and freeze steering for KNOCKBACK_DURATION ticks
            velX = kbX;
            velY = 0.35;
            velZ = kbZ;
            knockbackTicks = KNOCKBACK_DURATION;

            // Send impulse packet immediately for the visual arc
            final double fkbX = kbX, fkbZ = kbZ;
            Bukkit.getScheduler().runTask(ZonePractice.getInstance(),
                    () -> { if (botNpc != null) botNpc.applyVelocity(fkbX, 0.35, fkbZ).scheduleForTracked(); });
        }

        if (botHp <= 0) {
            botHp = 0;
            Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
                if (status.equals(MatchStatus.LIVE)) {
                    // Player wins this round
                    getCurrentRound().setRoundWinner(attacker);
                    getCurrentRound().endRound();
                }
            });
        }
    }

    /**
     * Resets the bot's logical HP for the next round.
     * Call this at the start of every new round.
     */
    public void resetBotHp() {
        botHp = 20f;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------
}
















