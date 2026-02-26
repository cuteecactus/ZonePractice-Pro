package dev.nandi0813.practice.manager.fight.match.bot;

import dev.nandi0813.practice.manager.backend.ConfigManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the PvP Bot inference REST API (MODEL_DESCRIPTION.md v2.1.0).
 *
 * <p>Uses plain {@link HttpURLConnection} — no external dependencies.
 *
 * <h3>API contract (POST /predict)</h3>
 * <pre>
 * Request  — 10 frames, each with "values" (27 raw game keys) and "hotbar" (10 vocab IDs).
 * Response — 8 booleans (move_forward, move_back, move_left, move_right,
 *             jump, sprint, attack, use_item) + hotbar_slot (int 0-8).
 * </pre>
 *
 * <h3>Vocabulary</h3>
 * Call {@link #fetchVocab()} once at startup to populate the internal item-name → ID cache.
 * Use {@link #itemToId(String)} to convert an uppercase item name (e.g. {@code "NETHERITE_SWORD"})
 * to its integer ID (0 = AIR / unknown).
 */
public class PvPBotClient {

    /** Number of consecutive frames required per inference request (GRU window size). */
    public static final int SEQUENCE_LENGTH = 10;

    // -----------------------------------------------------------------------
    // Response
    // -----------------------------------------------------------------------

    /**
     * Decoded action prediction returned by {@link #predict(GameFrame[], boolean)}.
     * All fields default to {@code false} / {@code 0} (safe no-op state).
     */
    public static class PredictResponse {
        /** Hold the W key (move forward). */
        public boolean moveForward;
        /** Hold the S key (move backward). */
        public boolean moveBack;
        /** Hold the A key (strafe left). */
        public boolean moveLeft;
        /** Hold the D key (strafe right). */
        public boolean moveRight;
        /** Press the Space bar (jump) this tick. */
        public boolean jump;
        /** Toggle/hold sprint. */
        public boolean sprint;
        /** Left-click (attack / swing weapon). */
        public boolean attack;
        /** Right-click (use item — eat, drink, shoot bow, etc.). */
        public boolean useItem;
        /** Hotbar slot to activate this tick (0-8). */
        public int     hotbarSlot;
        /** Raw JSON from the server — only populated when {@code includeConfidences=true}. */
        public String  rawJson;

        @Override
        public String toString() {
            return String.format(
                "PredictResponse{fwd=%b bck=%b lft=%b rgt=%b jump=%b sprint=%b attack=%b useItem=%b slot=%d}",
                moveForward, moveBack, moveLeft, moveRight, jump, sprint, attack, useItem, hotbarSlot);
        }
    }

    // -----------------------------------------------------------------------
    // Vocabulary cache
    // -----------------------------------------------------------------------

    /** Maps uppercase item name (e.g. {@code "NETHERITE_SWORD"}) to its vocabulary ID. */
    private final AtomicReference<Map<String, Integer>> vocabCache =
            new AtomicReference<>(Collections.emptyMap());

    private final Logger logger = Logger.getLogger("PvPBotClient");
    private final String baseUrl;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * @param baseUrl base URL of the inference server, e.g. {@code "http://127.0.0.1:8000"}
     *                — no trailing slash. Pass {@code null} to read from config each call.
     */
    public PvPBotClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** No-arg constructor — reads base URL from {@link ConfigManager} each call. */
    public PvPBotClient() {
        this(null);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Checks whether the inference server is reachable and the model is loaded.
     *
     * @return {@code true} if {@code {"status":"ok","model_loaded":true}}, {@code false} otherwise
     */
    public boolean isHealthy() {
        try {
            String resp = get("/health");
            return resp.contains("\"model_loaded\":true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetches the item vocabulary from {@code GET /vocab} and stores it in the internal cache.
     *
     * <p>Should be called once at startup (and again if {@link #getVocabSize()} changes).
     * Safe to call from any thread.
     *
     * @return number of entries loaded, or {@code -1} on error
     */
    public int fetchVocab() {
        try {
            String json = get("/vocab");
            Map<String, Integer> map = parseVocabJson(json);
            vocabCache.set(map);
            logger.info("[PvPBotClient] Loaded vocabulary: " + map.size() + " items");
            return map.size();
        } catch (Exception e) {
            logger.log(Level.WARNING, "[PvPBotClient] Failed to fetch vocabulary: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Returns the vocabulary size reported by the server ({@code GET /vocab_size}).
     * Use this to detect when the model has been updated and the vocab should be re-fetched.
     *
     * @return vocab_size, or {@code -1} on error
     */
    public int getVocabSize() {
        try {
            String json = get("/vocab_size");
            String val = rawVal(json, "vocab_size");
            return val == null ? -1 : Integer.parseInt(val.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Converts an item name to its vocabulary ID using the cached vocabulary.
     *
     * <p>The name should already be uppercased and have the {@code "minecraft:"} namespace
     * stripped (e.g. {@code "NETHERITE_SWORD"}).  Strip any {@code :count} suffix before calling.
     *
     * @param upperCaseName item name in upper case without namespace, e.g. {@code "NETHERITE_SWORD"}
     * @return vocabulary ID, or {@code 0} if not found (AIR / unknown)
     */
    public int itemToId(String upperCaseName) {
        if (upperCaseName == null || upperCaseName.isEmpty()) return 0;
        return vocabCache.get().getOrDefault(upperCaseName, 0);
    }

    /**
     * Run one inference step.
     *
     * @param frames             exactly {@value #SEQUENCE_LENGTH} frames, oldest first, newest last
     * @param includeConfidences when {@code true}, the raw JSON (including confidence scores)
     *                           is stored in {@link PredictResponse#rawJson}
     * @return decoded action, or a no-op response on any error
     */
    public PredictResponse predict(GameFrame[] frames, boolean includeConfidences) {
        if (frames.length != SEQUENCE_LENGTH) {
            logger.warning("[PvPBotClient] predict() requires exactly " + SEQUENCE_LENGTH
                    + " frames, got " + frames.length);
            return noOpResponse();
        }
        try {
            String body     = buildPredictJson(frames);
            String suffix   = includeConfidences ? "?include_confidences=true" : "";
            String response = post("/predict" + suffix, body);
            return parsePredict(response, includeConfidences);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[PvPBotClient] Prediction call failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return noOpResponse();
        }
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    private String resolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isEmpty()) return baseUrl;
        String cfg = ConfigManager.getString("MATCH-SETTINGS.BOT-DUEL.AI-SERVER-URL");
        if (cfg != null && cfg.startsWith("http")) {
            return cfg.endsWith("/") ? cfg.substring(0, cfg.length() - 1) : cfg;
        }
        return "http://127.0.0.1:8000";
    }

    private String get(String path) throws Exception {
        int connectMs = ConfigManager.getInt("MATCH-SETTINGS.BOT-DUEL.CONNECT-TIMEOUT-MS");
        int readMs    = ConfigManager.getInt("MATCH-SETTINGS.BOT-DUEL.READ-TIMEOUT-MS");
        if (connectMs <= 0) connectMs = 500;
        if (readMs    <= 0) readMs    = 5000;

        URL url = new URL(resolvedBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        if (status < 200 || status >= 300)
            throw new Exception("HTTP " + status + ": " + response);
        return response;
    }

    private String post(String path, String body) throws Exception {
        int connectMs = ConfigManager.getInt("MATCH-SETTINGS.BOT-DUEL.CONNECT-TIMEOUT-MS");
        int readMs    = ConfigManager.getInt("MATCH-SETTINGS.BOT-DUEL.READ-TIMEOUT-MS");
        if (connectMs <= 0) connectMs = 500;
        if (readMs    <= 0) readMs    = 5000;

        URL url = new URL(resolvedBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        if (status < 200 || status >= 300)
            throw new Exception("HTTP " + status + ": " + response);
        return response;
    }

    // -----------------------------------------------------------------------
    // JSON serialisation — MODEL_DESCRIPTION.md v2.1.0 wire format
    // -----------------------------------------------------------------------

    /**
     * Builds the POST /predict request body.
     *
     * <pre>
     * {
     *   "frames": [
     *     { "values": { "health": 18.0, ... }, "hotbar": [1, 0, 0, 0, 0, 0, 0, 0, 0, 1] },
     *     ...  (10 frames total)
     *   ]
     * }
     * </pre>
     */
    private static String buildPredictJson(GameFrame[] frames) {
        StringBuilder sb = new StringBuilder("{\"frames\":[");
        for (int i = 0; i < frames.length; i++) {
            if (i > 0) sb.append(',');
            GameFrame f = frames[i];
            sb.append("{\"values\":{");

            boolean first = true;
            for (Map.Entry<String, Object> e : f.values.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(escapeJsonKey(e.getKey())).append("\":");
                Object val = e.getValue();
                if (val instanceof Boolean) {
                    sb.append(val); // "true" / "false"
                } else {
                    // Number — write as-is
                    sb.append(val);
                }
                first = false;
            }
            sb.append("},\"hotbar\":");
            appendIntArray(sb, f.hotbar);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendIntArray(StringBuilder sb, int[] arr) {
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        sb.append(']');
    }

    /** Minimal key escaping — only escapes backslash and double-quote. */
    private static String escapeJsonKey(String key) {
        return key.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -----------------------------------------------------------------------
    // JSON deserialisation
    // -----------------------------------------------------------------------

    /**
     * Parses the POST /predict response body into a {@link PredictResponse}.
     *
     * <p>Expected response shape (MODEL_DESCRIPTION.md §Response Format):
     * <pre>
     * {
     *   "move_forward": true,  "move_back": false, "move_left": false, "move_right": false,
     *   "jump": false, "sprint": true, "attack": true, "use_item": false,
     *   "hotbar_slot": 0, "confidences": null
     * }
     * </pre>
     */
    private static PredictResponse parsePredict(String json, boolean includeConfidences) {
        PredictResponse r = new PredictResponse();
        r.moveForward = jsonBool(json, "move_forward");
        r.moveBack    = jsonBool(json, "move_back");
        r.moveLeft    = jsonBool(json, "move_left");
        r.moveRight   = jsonBool(json, "move_right");
        r.jump        = jsonBool(json, "jump");
        r.sprint      = jsonBool(json, "sprint");
        r.attack      = jsonBool(json, "attack");
        r.useItem     = jsonBool(json, "use_item");
        r.hotbarSlot  = jsonInt(json,  "hotbar_slot");
        if (includeConfidences) r.rawJson = json;
        return r;
    }

    /**
     * Parses the GET /vocab response body.
     *
     * <p>Expected shape: {@code {"vocab": {"AIR": 0, "NETHERITE_SWORD": 1, ...}, "vocab_size": N}}
     */
    private static Map<String, Integer> parseVocabJson(String json) {
        Map<String, Integer> map = new HashMap<>();

        // Find the inner "vocab" object
        String vocabMarker = "\"vocab\":{";
        int start = json.indexOf(vocabMarker);
        if (start < 0) {
            // Try without the "vocab" wrapper (some servers return the map directly)
            start = json.indexOf('{');
        } else {
            start += vocabMarker.length() - 1; // point at '{'
        }
        if (start < 0) return map;

        int depth = 0;
        int i = start;
        StringBuilder key = new StringBuilder();
        StringBuilder val = new StringBuilder();
        boolean inKey = false, inVal = false, inStr = false;

        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
                if (depth > 1) break; // nested — stop
            } else if (c == '}') {
                if (inVal && val.length() > 0) {
                    try { map.put(key.toString(), Integer.parseInt(val.toString().trim())); } catch (NumberFormatException ignored) {}
                }
                depth--;
                if (depth <= 0) break;
            } else if (c == '"' && !inVal) {
                if (!inStr) {
                    inStr = true; inKey = true; key.setLength(0);
                } else {
                    inStr = false; inKey = false;
                }
            } else if (inStr && inKey) {
                key.append(c);
            } else if (c == ':' && !inStr) {
                inVal = true; val.setLength(0);
            } else if (c == ',' && !inStr && inVal) {
                try { map.put(key.toString(), Integer.parseInt(val.toString().trim())); } catch (NumberFormatException ignored) {}
                inVal = false; key.setLength(0); val.setLength(0);
            } else if (inVal) {
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') val.append(c);
            }
            i++;
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Tiny JSON extractors (no dependency on external library)
    // -----------------------------------------------------------------------

    private static boolean jsonBool(String json, String key) {
        String val = rawVal(json, key);
        return "true".equalsIgnoreCase(val == null ? "" : val.trim());
    }

    private static int jsonInt(String json, String key) {
        String val = rawVal(json, key);
        if (val == null) return 0;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Returns the raw (unquoted) JSON value for a primitive key, or {@code null} if absent. */
    private static String rawVal(String json, String key) {
        String marker = "\"" + key + "\":";
        int s = json.indexOf(marker);
        if (s < 0) return null;
        s += marker.length();
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return null;
        int e = s;
        while (e < json.length() && json.charAt(e) != ',' && json.charAt(e) != '}' && json.charAt(e) != ']') e++;
        return json.substring(s, e);
    }

    private static PredictResponse noOpResponse() {
        return new PredictResponse(); // all fields default to false / 0
    }
}
