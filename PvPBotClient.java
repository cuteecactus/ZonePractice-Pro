import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * PvPBotClient — Java client for the PvP Bot Inference REST API.
 *
 * <p>Uses only the standard-library {@code java.net.http.HttpClient} (Java 11+).
 * No external dependencies required.
 *
 * <h2>Quick-start</h2>
 * <pre>{@code
 *   PvPBotClient client = new PvPBotClient("http://localhost:8000");
 *
 *   // Build a sliding window of the last 20 ticks
 *   PvPBotClient.GameFrame[] frames = new PvPBotClient.GameFrame[20];
 *   for (int i = 0; i < 20; i++) {
 *       frames[i] = new PvPBotClient.GameFrame(
 *           continuousFeatures[i],   // float[52]
 *           new float[0],            // booleans — always empty (NUM_BOOLEANS = 0)
 *           categoricalIds[i]        // int[46]
 *       );
 *   }
 *
 *   PvPBotClient.PredictResponse action = client.predict(frames, false);
 *   System.out.println("Attack? " + action.attack);
 *   System.out.println("dYaw: "   + action.deltaYaw);
 * }</pre>
 *
 * <h2>API endpoints</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/health</td><td>Liveness check — returns {@code {"status":"ok","model_loaded":true}}</td></tr>
 *   <tr><td>POST</td><td>/predict</td><td>Main inference endpoint</td></tr>
 *   <tr><td>GET</td><td>/vocab_size</td><td>Item vocabulary size used during training</td></tr>
 *   <tr><td>GET</td><td>/sequence_length</td><td>Required number of frames per request (20)</td></tr>
 * </table>
 *
 * <h2>Feature layout</h2>
 * <p>Each {@link GameFrame} carries three arrays that must match the training feature order:
 * <ul>
 *   <li><b>continuous[52]</b> — normalized floats (health, velocity, look angles, armor values, potion durations, target info, …)</li>
 *   <li><b>booleans[0]</b>   — always an empty array; pandas loads CSV booleans as float64 so training treats them all as continuous</li>
 *   <li><b>categorical[46]</b> — integer item-vocabulary IDs in this order:
 *       helmet, chestplate, leggings, boots, main_hand, off_hand,
 *       block_below, block_looking_at, last_inv_item, last_inv_action,
 *       inv_0 … inv_35</li>
 * </ul>
 *
 * <h2>Response fields</h2>
 * <ul>
 *   <li>{@code deltaYaw}, {@code deltaPitch} — raw camera rotation deltas (regression)</li>
 *   <li>{@code jump}, {@code sprint}, {@code attack}, {@code useItem} — boolean actions</li>
 *   <li>{@code velocityX}, {@code velocityZ} — movement velocity regression</li>
 *   <li>{@code selectedSlot} — hotbar slot 0–8 (argmax over 9 logits)</li>
 *   <li>{@code invAction} — one of: NONE / SWAP / PICKUP / DROP / PLACE / CLICK /
 *       PICKUP_ALL / PLACE_SOME / SWAP_WITH_CURSOR / PLACE_ALL /
 *       MOVE_TO_OTHER_INVENTORY / NOTHING / HOTBAR_SWAP /
 *       DROP_ALL_CURSOR / DROP_ALL_SLOT / PICKUP_HALF</li>
 *   <li>{@code invFromSlot}, {@code invToSlot} — inventory slot indices 0–35</li>
 * </ul>
 */
public class PvPBotClient {

    // ─────────────────────────────────────────────────────────────────
    // Configuration
    // ─────────────────────────────────────────────────────────────────

    /** Number of consecutive frames required per inference request. */
    public static final int SEQUENCE_LENGTH = 20;

    /** Number of continuous features per frame. */
    public static final int NUM_CONTINUOUS = 52;

    /** Number of boolean features per frame (0 — all booleans are loaded as floats from CSV). */
    public static final int NUM_BOOLEANS = 0;

    /** Number of categorical (item-ID) features per frame. */
    public static final int NUM_CATEGORICAL = 46;

    // ─────────────────────────────────────────────────────────────────
    // Data structures
    // ─────────────────────────────────────────────────────────────────

    /**
     * One game tick of observation data sent to the API.
     *
     * <p>Pass RAW (un-normalized) game values — the server normalizes automatically.
     *
     * <p>Two modes:
     * <ul>
     *   <li><b>Named mode (recommended)</b>: populate {@link #values} with column name → raw value pairs.
     *       Missing columns default to 0. Use {@code GET /col_order} to see all 52 column names.</li>
     *   <li><b>Array mode</b>: populate {@link #continuous} with exactly 52 raw floats in col_order.</li>
     * </ul>
     * {@link #categorical} is always required: 46 item-vocabulary IDs.
     */
    public static class GameFrame {
        /** Named raw values — recommended mode. Keys are column names from GET /col_order. */
        public final java.util.Map<String, Double> values;
        /** 52 raw floats in canonical col_order — alternative to values map. May be null. */
        public final float[] continuous;
        /** 46 item vocab IDs: helmet, chestplate, leggings, boots, main_hand, off_hand,
         *  block_below, block_looking_at, last_inv_item, last_inv_action, inv_0…inv_35 */
        public final int[] categorical;  // length 46

        /** Named-value constructor (recommended). */
        public GameFrame(java.util.Map<String, Double> values, int[] categorical) {
            if (categorical.length != NUM_CATEGORICAL)
                throw new IllegalArgumentException("categorical must have " + NUM_CATEGORICAL + " elements");
            this.values     = values;
            this.continuous = null;
            this.categorical = categorical;
        }

        /** Array constructor — must supply exactly NUM_CONTINUOUS (52) raw floats. */
        public GameFrame(float[] continuous, int[] categorical) {
            if (continuous.length != NUM_CONTINUOUS)
                throw new IllegalArgumentException("continuous must have " + NUM_CONTINUOUS + " elements");
            if (categorical.length != NUM_CATEGORICAL)
                throw new IllegalArgumentException("categorical must have " + NUM_CATEGORICAL + " elements");
            this.values     = null;
            this.continuous = continuous;
            this.categorical = categorical;
        }

        /** @deprecated Use {@link #GameFrame(java.util.Map, int[])} or {@link #GameFrame(float[], int[])} */
        @Deprecated
        public GameFrame(float[] continuous, float[] booleans, int[] categorical) {
            this(continuous, categorical);
        }
    }

    /**
     * Decoded action prediction returned by {@link #predict(GameFrame[], boolean)}.
     */
    public static class PredictResponse {
        /** Camera rotation delta — horizontal (radians or raw model units). */
        public float   deltaYaw;
        /** Camera rotation delta — vertical. */
        public float   deltaPitch;
        /** Whether to press the jump key this tick. */
        public boolean jump;
        /** Whether to hold sprint this tick. */
        public boolean sprint;
        /** Predicted velocity on the X axis. */
        public float   velocityX;
        /** Predicted velocity on the Z axis. */
        public float   velocityZ;
        /** Selected hotbar slot (0–8). */
        public int     selectedSlot;
        /** Whether to left-click (attack). */
        public boolean attack;
        /** Whether to right-click (use item / block). */
        public boolean useItem;
        /** Inventory action: NONE / SWAP / PICKUP / DROP / PLACE / CLICK. */
        public String  invAction;
        /** Source inventory slot (0–35) for invAction. */
        public int     invFromSlot;
        /** Destination inventory slot (0–35) for invAction. */
        public int     invToSlot;
        /** Raw logits map — only populated when includeRaw=true. */
        public String  rawJson;

        @Override
        public String toString() {
            return String.format(
                "PredictResponse{dYaw=%.3f, dPitch=%.3f, jump=%b, sprint=%b, " +
                "vx=%.3f, vz=%.3f, slot=%d, attack=%b, useItem=%b, inv=%s [%d->%d]}",
                deltaYaw, deltaPitch, jump, sprint,
                velocityX, velocityZ, selectedSlot, attack, useItem,
                invAction, invFromSlot, invToSlot
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Client internals
    // ─────────────────────────────────────────────────────────────────

    private final String     baseUrl;
    private final HttpClient http;

    /**
     * Create a client pointing to a running pvp_api server.
     *
     * @param baseUrl  e.g. {@code "http://localhost:8000"} — no trailing slash
     */
    public PvPBotClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // Public API methods
    // ─────────────────────────────────────────────────────────────────

    /**
     * Check whether the server is up and the model is loaded.
     *
     * <p>Example response body: {@code {"status":"ok","model_loaded":true}}
     *
     * @return the raw JSON string
     * @throws Exception on network or HTTP error
     */
    public String health() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp);
        return resp.body();
    }

    /**
     * Run one inference step through the PvP model.
     *
     * @param frames     exactly {@value #SEQUENCE_LENGTH} consecutive game frames
     *                   (oldest first, latest last)
     * @param includeRaw when {@code true}, the response will also contain raw
     *                   logits in {@link PredictResponse#rawJson}
     * @return decoded action prediction
     * @throws IllegalArgumentException if frames.length != {@value #SEQUENCE_LENGTH}
     * @throws Exception                on network or HTTP error
     */
    public PredictResponse predict(GameFrame[] frames, boolean includeRaw) throws Exception {
        if (frames.length != SEQUENCE_LENGTH) {
            throw new IllegalArgumentException(
                "predict() requires exactly " + SEQUENCE_LENGTH + " frames, got " + frames.length);
        }

        String body = buildPredictJson(frames);

        String url = baseUrl + "/predict" + (includeRaw ? "?include_raw=true" : "");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp);
        return parsePredict(resp.body(), includeRaw);
    }

    /**
     * Return the item vocabulary size that was used during training.
     *
     * @return the raw JSON string, e.g. {@code {"vocab_size":1647}}
     * @throws Exception on network or HTTP error
     */
    public String vocabSize() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/vocab_size"))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp);
        return resp.body();
    }

    /**
     * Return the required sequence length (always 20).
     *
     * @return the raw JSON string, e.g. {@code {"sequence_length":20}}
     * @throws Exception on network or HTTP error
     */
    public String sequenceLength() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sequence_length"))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp);
        return resp.body();
    }

    // ─────────────────────────────────────────────────────────────────
    // JSON helpers (no external library — manual but clear)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Manually builds the JSON body for a /predict request.
     *
     * <p>If your project already uses Gson or Jackson, replace this method
     * with {@code new Gson().toJson(requestObject)} for brevity.
     */
    private static String buildPredictJson(GameFrame[] frames) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"frames\":[");
        for (int i = 0; i < frames.length; i++) {
            if (i > 0) sb.append(',');
            GameFrame f = frames[i];
            sb.append('{');

            if (f.values != null) {
                // Named dict mode
                sb.append("\"values\":{");
                boolean first = true;
                for (java.util.Map.Entry<String, Double> e : f.values.entrySet()) {
                    if (!first) sb.append(',');
                    sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                    first = false;
                }
                sb.append('}');
            } else {
                // Array mode
                sb.append("\"continuous\":");
                appendFloatArray(sb, f.continuous);
            }

            sb.append(",\"categorical\":");
            appendIntArray(sb, f.categorical);

            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendFloatArray(StringBuilder sb, float[] arr) {
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
    }

    private static void appendIntArray(StringBuilder sb, int[] arr) {
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
    }

    /**
     * Minimal JSON parser for the /predict response.
     *
     * <p>Reads each field by key-search so it is independent of field order.
     * Replace with {@code new Gson().fromJson(json, PredictResponse.class)}
     * if Gson/Jackson is available.
     */
    private static PredictResponse parsePredict(String json, boolean includeRaw) {
        PredictResponse r = new PredictResponse();
        r.deltaYaw     = jsonFloat(json, "delta_yaw");
        r.deltaPitch   = jsonFloat(json, "delta_pitch");
        r.jump         = jsonBool(json,  "jump");
        r.sprint       = jsonBool(json,  "sprint");
        r.velocityX    = jsonFloat(json, "velocity_x");
        r.velocityZ    = jsonFloat(json, "velocity_z");
        r.selectedSlot = jsonInt(json,   "selected_slot");
        r.attack       = jsonBool(json,  "attack");
        r.useItem      = jsonBool(json,  "use_item");
        r.invAction    = jsonString(json,"inv_action");
        r.invFromSlot  = jsonInt(json,   "inv_from_slot");
        r.invToSlot    = jsonInt(json,   "inv_to_slot");
        if (includeRaw) r.rawJson = json;
        return r;
    }

    // ── tiny JSON value extractors ──────────────────────────────────

    private static float jsonFloat(String json, String key) {
        String val = jsonRawValue(json, key);
        return val == null ? 0f : Float.parseFloat(val.trim());
    }

    private static int jsonInt(String json, String key) {
        String val = jsonRawValue(json, key);
        return val == null ? 0 : Integer.parseInt(val.trim());
    }

    private static boolean jsonBool(String json, String key) {
        String val = jsonRawValue(json, key);
        return "true".equalsIgnoreCase(val == null ? "" : val.trim());
    }

    private static String jsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    /**
     * Returns the raw (un-quoted) JSON value for a primitive key,
     * or {@code null} if not found.
     */
    private static String jsonRawValue(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        // skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        // read until comma, } or ]
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') end++;
        return json.substring(start, end);
    }

    private static void assertOk(HttpResponse<String> resp) throws Exception {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new Exception("HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo main — shows typical usage in a game loop
    // ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        PvPBotClient client = new PvPBotClient("http://localhost:8000");

        // 1. Health check
        System.out.println("Health: " + client.health());

        // 2. Build 20 dummy frames using the named-value mode (recommended)
        GameFrame[] frames = new GameFrame[SEQUENCE_LENGTH];
        for (int i = 0; i < SEQUENCE_LENGTH; i++) {
            // Populate with real game-state values — all missing keys default to 0
            java.util.Map<String, Double> vals = new java.util.LinkedHashMap<>();
            vals.put("health",          20.0);
            vals.put("max_health",      20.0);
            vals.put("yaw",              0.0);
            vals.put("pitch",            0.0);
            vals.put("vel_x",            0.0);
            vals.put("vel_y",            0.0);
            vals.put("vel_z",            0.0);
            vals.put("food_level",      20.0);
            vals.put("saturation",       5.0);
            vals.put("target_distance", 10.0);
            vals.put("target_rel_x",     5.0);
            vals.put("target_rel_y",     0.0);
            vals.put("target_rel_z",     5.0);
            vals.put("target_health",   20.0);
            vals.put("attack_cooldown", 100.0);
            vals.put("is_on_ground",     1.0); // bool → 1.0 / 0.0
            vals.put("is_sprinting",     0.0);
            vals.put("total_armor",     20.0);
            // ... add remaining columns from GET /col_order as needed

            // Categorical: all 0 = AIR/NONE — replace with real item vocab IDs
            int[] cats = new int[NUM_CATEGORICAL];
            // Example: wearing netherite (look up IDs from GET /vocab)
            // cats[0] = NETHERITE_HELMET_ID;
            // cats[1] = NETHERITE_CHESTPLATE_ID;
            // cats[2] = NETHERITE_LEGGINGS_ID;
            // cats[3] = NETHERITE_BOOTS_ID;
            // cats[4] = NETHERITE_SWORD_ID;  // main_hand

            frames[i] = new GameFrame(vals, cats);
        }

        // 3. Run inference
        PredictResponse action = client.predict(frames, false);
        System.out.println(action);

        // ── Typical game-loop integration ───────────────────────────
        //
        // Maintain a circular buffer of the last 20 GameFrames.
        // Each tick:
        //   1. Read current game state → build a GameFrame
        //   2. Push frame into the circular buffer (evict oldest)
        //   3. Call client.predict(buffer, false)
        //   4. Apply the returned actions to the bot
        //
        // Example circular buffer:
        //
        //   GameFrame[] window  = new GameFrame[SEQUENCE_LENGTH];
        //   int         head    = 0;          // next write position
        //   boolean     ready   = false;
        //   int         filled  = 0;
        //
        //   // Per tick:
        //   window[head] = buildFrameFromGameState();
        //   head = (head + 1) % SEQUENCE_LENGTH;
        //   if (++filled >= SEQUENCE_LENGTH) ready = true;
        //
        //   if (ready) {
        //       // Re-order so oldest frame is first
        //       GameFrame[] ordered = new GameFrame[SEQUENCE_LENGTH];
        //       for (int i = 0; i < SEQUENCE_LENGTH; i++) {
        //           ordered[i] = window[(head + i) % SEQUENCE_LENGTH];
        //       }
        //       PredictResponse action = client.predict(ordered, false);
        //       applyActions(action);
        //   }
    }
}

