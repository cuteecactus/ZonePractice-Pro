package dev.nandi0813.practice.manager.fight.match.bot;

import java.util.Map;

/**
 * One game tick of observation data sent to the PvP Bot inference API.
 *
 * <p>Matches the wire-format defined in MODEL_DESCRIPTION.md v2.1.0.
 *
 * <h3>Frame structure</h3>
 * <pre>
 * {
 *   "values": {
 *     "health": 18.0,
 *     "max_health": 20.0,
 *     "vel_x": -0.18,
 *     ...  (27 keys total — see MODEL_DESCRIPTION.md for the full list)
 *   },
 *   "hotbar": [1, 2, 0, 0, 0, 0, 0, 0, 0, 1]   // 10 vocab IDs: inv_0..inv_8 + main_hand
 * }
 * </pre>
 *
 * <h3>Hotbar layout</h3>
 * Indices 0-8 = hotbar slots left-to-right (inv_0 … inv_8).
 * Index 9 = the item currently in the <em>main hand</em> (same item as the active slot,
 * kept separately so the model can cross-reference).
 * Empty slot → {@code 0} (AIR always maps to ID 0).
 */
public final class GameFrame {

    /** Number of hotbar IDs per frame (9 slots + 1 main-hand cross-reference). */
    public static final int HOTBAR_SIZE = 10;

    /**
     * Raw game-state values keyed by field name (see MODEL_DESCRIPTION.md).
     * The server performs all normalisation; pass raw Minecraft values directly.
     *
     * <p>Required keys:
     * <pre>
     * Numeric (17):  health, max_health, vel_x, vel_y, vel_z, yaw, pitch,
     *                food_level, total_armor, target_distance, target_rel_x,
     *                target_rel_y, target_rel_z, target_health,
     *                attack_cooldown, item_use_duration, selected_slot
     * Boolean (10):  is_on_ground, is_jumping, is_sprinting, is_sneaking,
     *                has_speed, has_strength, has_regeneration, has_poison,
     *                is_using_item, target_is_blocking
     * </pre>
     * Pass boolean fields as {@code true}/{@code false} (JSON booleans).
     */
    public final Map<String, Object> values;

    /**
     * Item vocabulary IDs for the 10 hotbar-related slots.
     * Positions 0-8 correspond to inventory slots 0-8; position 9 is the main hand.
     * Empty / unknown items use {@code 0} (AIR).
     */
    public final int[] hotbar;

    /**
     * Constructs a frame with a named-value map and a hotbar ID array.
     *
     * @param values raw game-state values (27 keys — see field javadoc)
     * @param hotbar exactly {@value #HOTBAR_SIZE} item-vocabulary IDs
     * @throws IllegalArgumentException if {@code hotbar} does not have exactly
     *                                  {@value #HOTBAR_SIZE} elements
     */
    public GameFrame(Map<String, Object> values, int[] hotbar) {
        if (hotbar.length != HOTBAR_SIZE)
            throw new IllegalArgumentException(
                    "hotbar must have exactly " + HOTBAR_SIZE + " elements, got " + hotbar.length);
        this.values = values;
        this.hotbar = hotbar;
    }
}
