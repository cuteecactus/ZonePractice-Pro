package dev.nandi0813.practice.manager.fight.match.bot;

import dev.nandi0813.practice.manager.backend.ConfigManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the PvP Bot inference REST API.
 *
 * <p>Uses plain {@link HttpURLConnection} — no connection pooling, no HTTP/2,
 * no IPv6 preference issues on macOS.
 *
 * <p>The sliding window is managed by the caller ({@link BotMatch}); this class
 * only handles serialisation and the HTTP call.
 *
 * <h3>API contract (POST /predict)</h3>
 * Supports both named-value mode ({@code "values":{...}}) and array mode ({@code "continuous":[...]}).
 */
public class PvPBotClient {

    public static final int SEQUENCE_LENGTH = 20;
    public static final int NUM_CONTINUOUS  = GameFrame.NUM_CONTINUOUS;
    public static final int NUM_CATEGORICAL = GameFrame.NUM_CATEGORICAL;

    // -----------------------------------------------------------------------
    // Response
    // -----------------------------------------------------------------------

    public static class PredictResponse {
        public float   deltaYaw;
        public float   deltaPitch;
        public boolean jump;
        public boolean sprint;
        public float   velocityX;
        public float   velocityZ;
        public int     selectedSlot;
        public boolean attack;
        public boolean useItem;
        public String  invAction   = "NONE";
        public int     invFromSlot;
        public int     invToSlot;
        public String  rawJson;

        @Override
        public String toString() {
            return String.format(
                "PredictResponse{dYaw=%.3f dPitch=%.3f jump=%b sprint=%b " +
                "vx=%.3f vz=%.3f slot=%d attack=%b useItem=%b inv=%s[%d->%d]}",
                deltaYaw, deltaPitch, jump, sprint,
                velocityX, velocityZ, selectedSlot, attack, useItem,
                invAction, invFromSlot, invToSlot);
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final Logger logger = Logger.getLogger("PvPBotClient");
    private final String baseUrl;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param baseUrl  base URL of the inference server, e.g. {@code "http://127.0.0.1:8000"}
     *                 — no trailing slash. When {@code null} or empty the value is read lazily
     *                 from {@link ConfigManager} each call.
     */
    public PvPBotClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Convenience no-arg constructor — reads base URL from config each call. */
    public PvPBotClient() {
        this(null);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Run one inference step.
     *
     * @param frames     exactly {@value #SEQUENCE_LENGTH} frames, oldest first
     * @param includeRaw when {@code true}, raw logits JSON is stored in {@link PredictResponse#rawJson}
     * @return decoded action, or a no-op on any error
     */
    public PredictResponse predict(GameFrame[] frames, boolean includeRaw) {
        if (frames.length != SEQUENCE_LENGTH) {
            logger.warning("[PvPBotClient] predict() requires exactly " + SEQUENCE_LENGTH
                    + " frames, got " + frames.length);
            return noOpResponse();
        }
        try {
            String body     = buildPredictJson(frames);
            String response = post("/predict" + (includeRaw ? "?include_raw=true" : ""), body);
            return parsePredict(response, includeRaw);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[PvPBotClient] Prediction call failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return noOpResponse();
        }
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String resolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isEmpty()) return baseUrl;
        String cfg = ConfigManager.getString("MATCH-SETTINGS.BOT-DUEL.AI-SERVER-URL");
        if (cfg != null && cfg.startsWith("http")) {
            return cfg.endsWith("/") ? cfg.substring(0, cfg.length() - 1) : cfg;
        }
        return "http://127.0.0.1:8000";
    }

    private String post(String path, String body) throws Exception {
        int connectMs = ConfigManager.getInt("MATCH-SETTINGS.BOT-DUEL.CONNECT-TIMEOUT-MS");
        int readMs    = ConfigManager.getInt("MATCH-SETTINGS.BOT-DUEL.READ-TIMEOUT-MS");
        if (connectMs <= 0) connectMs = 500;
        if (readMs    <= 0) readMs    = 5000;

        String fullUrl = resolvedBaseUrl() + path;

        URL url = new URL(fullUrl);
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
        InputStream is = (status >= 200 && status < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        if (status < 200 || status >= 300)
            throw new Exception("HTTP " + status + ": " + response);
        return response;
    }

    // -----------------------------------------------------------------------
    // JSON serialisation — supports both named-value and array modes
    // -----------------------------------------------------------------------

    private static String buildPredictJson(GameFrame[] frames) {
        StringBuilder sb = new StringBuilder("{\"frames\":[");
        for (int i = 0; i < frames.length; i++) {
            if (i > 0) sb.append(',');
            GameFrame f = frames[i];
            sb.append('{');
            if (f.values != null) {
                // Named dict mode (recommended)
                sb.append("\"values\":{");
                boolean first = true;
                for (Map.Entry<String, Double> e : f.values.entrySet()) {
                    if (!first) sb.append(',');
                    sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                    first = false;
                }
                sb.append('}');
            } else {
                // Array mode
                sb.append("\"continuous\":");
                appendFloats(sb, f.continuous);
            }
            sb.append(",\"categorical\":");
            appendInts(sb, f.categorical);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendFloats(StringBuilder sb, float[] arr) {
        sb.append('[');
        for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
        sb.append(']');
    }

    private static void appendInts(StringBuilder sb, int[] arr) {
        sb.append('[');
        for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
        sb.append(']');
    }

    // -----------------------------------------------------------------------
    // JSON deserialisation
    // -----------------------------------------------------------------------

    private static PredictResponse parsePredict(String json, boolean includeRaw) {
        PredictResponse r = new PredictResponse();
        r.deltaYaw     = jsonFloat(json,  "delta_yaw");
        r.deltaPitch   = jsonFloat(json,  "delta_pitch");
        r.jump         = jsonBool(json,   "jump");
        r.sprint       = jsonBool(json,   "sprint");
        r.velocityX    = jsonFloat(json,  "velocity_x");
        r.velocityZ    = jsonFloat(json,  "velocity_z");
        r.selectedSlot = jsonInt(json,    "selected_slot");
        r.attack       = jsonBool(json,   "attack");
        r.useItem      = jsonBool(json,   "use_item");
        r.invAction    = jsonString(json, "inv_action");
        r.invFromSlot  = jsonInt(json,    "inv_from_slot");
        r.invToSlot    = jsonInt(json,    "inv_to_slot");
        if (includeRaw) r.rawJson = json;
        return r;
    }

    private static float  jsonFloat(String j, String k)  { String v = rawVal(j,k); return v==null?0f:Float.parseFloat(v.trim()); }
    private static int    jsonInt(String j, String k)     { String v = rawVal(j,k); return v==null?0:Integer.parseInt(v.trim()); }
    private static boolean jsonBool(String j, String k)  { String v = rawVal(j,k); return "true".equalsIgnoreCase(v==null?"":v.trim()); }

    private static String jsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int s = json.indexOf(marker); if (s < 0) return "NONE";
        s += marker.length(); int e = json.indexOf('"', s);
        return e < 0 ? "NONE" : json.substring(s, e);
    }

    private static String rawVal(String json, String key) {
        String marker = "\"" + key + "\":";
        int s = json.indexOf(marker); if (s < 0) return null;
        s += marker.length();
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return null;
        int e = s;
        while (e < json.length() && json.charAt(e)!=',' && json.charAt(e)!='}' && json.charAt(e)!=']') e++;
        return json.substring(s, e);
    }

    private static PredictResponse noOpResponse() {
        PredictResponse r = new PredictResponse(); r.invAction = "NONE"; return r;
    }
}
