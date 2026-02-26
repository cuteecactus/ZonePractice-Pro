package dev.nandi0813.practice.manager.fight.match.bot;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Citizens {@link Trait} that translates the neural-network's action prediction
 * into native Citizens/NMS inputs every tick.
 *
 * <h3>Design principle</h3>
 * Citizens and the Minecraft server own all physics. This trait only drives
 * six high-level <em>inputs</em>; it never touches velocity, HP, or damage directly:
 *
 * <ol>
 *   <li><b>Movement</b> — {@code Navigator.setTarget(player, aggressive)} is called
 *       whenever any WASD key is active. Citizens' pathfinder handles the rest:
 *       velocity, collision, gravity, step height. When no movement is requested
 *       the navigator is paused.</li>
 *   <li><b>Sprint</b> — {@link Player#setSprinting(boolean)}</li>
 *   <li><b>Jump</b> — Citizens' native jump via {@code Navigator} speed modifier</li>
 *   <li><b>Attack</b> — NMS {@code EntityHuman.attack(Entity)} so the full
 *       vanilla damage + knockback pipeline fires with the NPC as attacker, but
 *       the NPC entity itself has a valid NMS handle so no Profile is needed
 *       in the Bukkit listener (the damage originates from NMS, not from
 *       {@code CraftPlayer.damage()}).</li>
 *   <li><b>Use item</b> — reflection to {@code startUsingItem} / NMS fallback</li>
 *   <li><b>Hotbar slot</b> — {@link PlayerInventory#setHeldItemSlot(int)}</li>
 * </ol>
 */
@TraitName("neuralbottrait")
public final class NeuralBotTrait extends Trait {

    private static final double WALK_SPEED   = 0.4;  // Navigator speed units (not Bukkit velocity)
    private static final double SPRINT_SPEED = 0.6;
    private static final double MELEE_RANGE  = 3.0;
    private static final int    ATTACK_CD    = 20;    // ticks

    private static final Logger LOG = Logger.getLogger("NeuralBotTrait");

    // -----------------------------------------------------------------------
    // State set by BotMatch on the main thread
    // -----------------------------------------------------------------------

    private PvPBotClient.PredictResponse currentAction = null;
    private Player target = null;
    private int attackCooldown = 0;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public NeuralBotTrait() {
        super("neuralbottrait");
    }

    // -----------------------------------------------------------------------
    // External setters
    // -----------------------------------------------------------------------

    public void setCurrentAction(PvPBotClient.PredictResponse action) {
        if (action != null) this.currentAction = action;
    }

    public void setTarget(Player target) {
        this.target = target;
    }

    // -----------------------------------------------------------------------
    // Citizens tick loop (main thread, every tick)
    // -----------------------------------------------------------------------

    @Override
    public void run() {
        PvPBotClient.PredictResponse action = this.currentAction;
        if (action == null || target == null) return;

        Entity entity = npc.getEntity();
        if (!(entity instanceof Player botPlayer)) return;

        // ── 1. Sprint ─────────────────────────────────────────────────────────
        botPlayer.setSprinting(action.sprint);

        // ── 2. Movement via Citizens Navigator ────────────────────────────────
        applyMovement(action);

        // ── 3. Attack cooldown ────────────────────────────────────────────────
        if (attackCooldown > 0) attackCooldown--;

        // ── 4. Attack via NMS ─────────────────────────────────────────────────
        if (action.attack && attackCooldown == 0) {
            applyAttack(botPlayer);
        }

        // ── 5. Use item ───────────────────────────────────────────────────────
        if (action.useItem) {
            simulateItemUse(botPlayer);
        }

        // ── 6. Hotbar slot ────────────────────────────────────────────────────
        applyHotbarSlot(botPlayer, action.hotbarSlot);
    }

    // -----------------------------------------------------------------------
    // Movement — Citizens Navigator
    // -----------------------------------------------------------------------

    /**
     * Routes movement through Citizens' own {@link net.citizensnpcs.api.ai.Navigator}.
     * When any WASD key is active the NPC pursues the target at the configured
     * speed; when all movement keys are off the navigator is paused so the NPC
     * stands still. Citizens handles velocity, gravity, step-height, and collision.
     */
    private void applyMovement(PvPBotClient.PredictResponse action) {
        boolean wantsToMove = action.moveForward || action.moveBack
                || action.moveLeft || action.moveRight;

        NavigatorParameters params = npc.getNavigator().getLocalParameters();
        params.speedModifier((float) (action.sprint ? SPRINT_SPEED : WALK_SPEED));

        if (wantsToMove) {
            // setTarget with aggressive=false: pathfind toward target's location.
            // Citizens re-evaluates the path every tick so direction changes are instant.
            if (!npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(target, false);
            }
        } else {
            // No movement input — stop the navigator
            if (npc.getNavigator().isNavigating()) {
                npc.getNavigator().cancelNavigation();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Attack — NMS EntityHuman.attack()
    // -----------------------------------------------------------------------

    /**
     * Performs a melee attack using the NMS-level {@code EntityHuman.attack(Entity)}
     * method. This triggers the full vanilla damage + knockback pipeline (including
     * enchantments, armour, sound effects) with the NPC as the attacker. Because
     * the call originates from NMS rather than {@code CraftPlayer.damage()}, the
     * Bukkit {@code EntityDamageByEntityEvent} is fired with a valid attacker
     * — but the attacker has no {@code Profile}, so {@link BotMatchListener}
     * handles the kill detection instead of the regular MatchListener.
     */
    private void applyAttack(Player botPlayer) {
        if (botPlayer.getLocation().distanceSquared(target.getLocation()) > MELEE_RANGE * MELEE_RANGE) return;

        try {
            Object nmsAttacker = botPlayer.getClass().getMethod("getHandle").invoke(botPlayer);
            Object nmsTarget   = target.getClass().getMethod("getHandle").invoke(target);

            // Walk the full superclass chain of the attacker's NMS class to find attack().
            // Citizens' EntityHumanNPC does not declare attack() itself — it lives on a
            // parent class several levels up (net.minecraft.world.entity.player.Player or
            // net.minecraft.world.entity.LivingEntity depending on MC version).
            java.lang.reflect.Method attackMethod = findAttackMethod(
                    nmsAttacker.getClass(), nmsTarget.getClass());

            if (attackMethod == null) {
                LOG.warning("[NeuralBotTrait] Could not find NMS attack() in hierarchy of "
                        + nmsAttacker.getClass().getName());
                return;
            }

            attackMethod.setAccessible(true);
            attackMethod.invoke(nmsAttacker, nmsTarget);
            attackCooldown = ATTACK_CD;

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[NeuralBotTrait] NMS attack failed", e);
        }
    }

    /**
     * Walks the superclass chain of {@code attackerClass} looking for a public
     * method named {@code "attack"} that accepts a single parameter assignable
     * from {@code targetClass} or any of its superclasses.
     * Returns the first match found, or {@code null}.
     */
    private static java.lang.reflect.Method findAttackMethod(Class<?> attackerClass, Class<?> targetClass) {
        // Collect all candidate parameter types: the target's class and all its superclasses
        java.util.List<Class<?>> targetTypes = new java.util.ArrayList<>();
        for (Class<?> c = targetClass; c != null; c = c.getSuperclass()) {
            targetTypes.add(c);
        }

        // Walk the attacker's class hierarchy
        for (Class<?> cls = attackerClass; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Class<?> paramType : targetTypes) {
                try {
                    java.lang.reflect.Method m = cls.getDeclaredMethod("attack", paramType);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Item use
    // -----------------------------------------------------------------------

    private void simulateItemUse(Player botPlayer) {
        // Modern Paper
        try {
            botPlayer.getClass()
                    .getMethod("startUsingItem", org.bukkit.inventory.EquipmentSlot.class)
                    .invoke(botPlayer, org.bukkit.inventory.EquipmentSlot.HAND);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[NeuralBotTrait] startUsingItem failed", e);
        }
        // Legacy 1.8.8 NMS
        try {
            Object handle    = botPlayer.getClass().getMethod("getHandle").invoke(botPlayer);
            Object itemStack = handle.getClass().getMethod("getItemInHand").invoke(handle);
            if (itemStack != null) {
                handle.getClass().getMethod("c", itemStack.getClass()).invoke(handle, itemStack);
            }
        } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Hotbar
    // -----------------------------------------------------------------------

    private void applyHotbarSlot(Player botPlayer, int slot) {
        int clamped = Math.max(0, Math.min(8, slot));
        PlayerInventory inv = botPlayer.getInventory();
        if (inv.getHeldItemSlot() != clamped) inv.setHeldItemSlot(clamped);
    }
}
