package dev.nandi0813.practice.manager.fight.match.bot;

import java.util.Map;

/**
 * One game tick of observation data sent to the PvP Bot inference API.
 *
 * <p>Two construction modes:
 * <ul>
 *   <li><b>Named mode (recommended)</b> — populate {@link #values} with column name → raw value
 *       pairs. Missing columns default to 0 on the server side. Use {@code GET /col_order} to
 *       see all 52 column names.</li>
 *   <li><b>Array mode</b> — populate {@link #continuous} with exactly 52 raw floats in col_order.</li>
 * </ul>
 * {@link #categorical} is always required (46 item-vocabulary IDs).
 *
 * <p>Array sizes:
 * <ul>
 *   <li>{@code continuous[52]} — normalized floats (health, velocity, angles, armor, potions, …)</li>
 *   <li>{@code categorical[46]} — integer item-vocab IDs:
 *       helmet, chestplate, leggings, boots, main_hand, off_hand,
 *       block_below, block_looking_at, last_inv_item, last_inv_action, inv_0…inv_35</li>
 * </ul>
 */
public final class GameFrame {

    public static final int NUM_CONTINUOUS  = 52;
    public static final int NUM_CATEGORICAL = 46;

    /** Named raw values — keys are column names from {@code GET /col_order}. Null in array mode. */
    public final Map<String, Double> values;

    /** 52 raw floats in canonical col_order. Null in named mode. */
    public final float[] continuous;

    /** 46 item-vocab IDs. Always required. */
    public final int[] categorical;

    /** Named-value constructor (recommended). */
    public GameFrame(Map<String, Double> values, int[] categorical) {
        if (categorical.length != NUM_CATEGORICAL)
            throw new IllegalArgumentException("categorical must have " + NUM_CATEGORICAL + " elements");
        this.values      = values;
        this.continuous  = null;
        this.categorical = categorical;
    }

    /** Array constructor — supply exactly {@value #NUM_CONTINUOUS} raw floats. */
    public GameFrame(float[] continuous, int[] categorical) {
        if (continuous.length != NUM_CONTINUOUS)
            throw new IllegalArgumentException("continuous must have " + NUM_CONTINUOUS + " elements");
        if (categorical.length != NUM_CATEGORICAL)
            throw new IllegalArgumentException("categorical must have " + NUM_CATEGORICAL + " elements");
        this.values      = null;
        this.continuous  = continuous;
        this.categorical = categorical;
    }
}
