package app.harry.portfolio;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Saved settings and the last copy of the Sheet, shared by the app and the widget.
 * The only network request is reading the published Sheet CSV: no Google script runs.
 */
final class PortfolioStore {

    private static final String PREFS = "portfolio";
    private static final String KEY_URL = "csvUrl";
    private static final String KEY_CSV = "lastCsv";
    private static final String KEY_FETCHED = "fetchedMs";
    private static final String KEY_ERROR = "lastError";
    private static final String KEY_WIDGET_HOLDINGS = "widgetHoldings";
    private static final String KEY_HIDE = "hideValues";

    private PortfolioStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String url(Context c) { return prefs(c).getString(KEY_URL, ""); }

    static boolean widgetHoldings(Context c) { return prefs(c).getBoolean(KEY_WIDGET_HOLDINGS, true); }

    static boolean hideValues(Context c) { return prefs(c).getBoolean(KEY_HIDE, false); }

    static void saveSettings(Context c, String url, boolean widgetHoldings, boolean hideValues) {
        String old = url(c);
        SharedPreferences.Editor e = prefs(c).edit()
            .putString(KEY_URL, url == null ? "" : url.trim())
            .putBoolean(KEY_WIDGET_HOLDINGS, widgetHoldings)
            .putBoolean(KEY_HIDE, hideValues);
        if (!old.equals(url == null ? "" : url.trim())) e.remove(KEY_CSV).remove(KEY_FETCHED).remove(KEY_ERROR);
        e.apply();
    }

    /** Last good copy of the Sheet, or null. */
    static PortfolioCore.Summary summary(Context c) {
        String csv = prefs(c).getString(KEY_CSV, null);
        if (csv == null) return null;
        try {
            return PortfolioCore.fromCsv(csv);
        } catch (PortfolioCore.FormatException e) {
            return null;
        }
    }

    static long fetchedMs(Context c) { return prefs(c).getLong(KEY_FETCHED, 0); }

    /**
     * Download the Sheet now. Returns null on success, or a short message to show.
     * On failure the last good copy is kept, so the app and widget keep showing it.
     * Runs on a background thread only.
     */
    static String fetch(Context c) {
        String url = url(c);
        if (url.isEmpty()) return "Add your Sheet link in Settings.";
        String error;
        try {
            String csv = download(url);
            PortfolioCore.fromCsv(csv); // check it's the Holdings tab before saving it
            prefs(c).edit()
                .putString(KEY_CSV, csv)
                .putLong(KEY_FETCHED, System.currentTimeMillis())
                .remove(KEY_ERROR)
                .apply();
            return null;
        } catch (PortfolioCore.FormatException e) {
            error = e.getMessage();
        } catch (java.net.UnknownHostException e) {
            error = "No internet connection.";
        } catch (Exception e) {
            error = "Couldn't load the Sheet (" + e.getClass().getSimpleName() + ").";
        }
        prefs(c).edit().putString(KEY_ERROR, error).apply();
        return error;
    }

    private static String download(String address) throws Exception {
        // Google sends published CSVs via a redirect; follow up to 5 of them.
        for (int hop = 0; hop < 5; hop++) {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol())) throw new Exception("not https");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "text/csv,*/*");
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = conn.getHeaderField("Location");
                    if (next == null) throw new Exception("redirect");
                    address = new URL(url, next).toString();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                return readAll(conn.getInputStream());
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 2_000_000) throw new Exception("too big");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- For the app screen -----------------------------------------------------------

    /** Everything the app screen needs, as JSON. The screen only formats; it never calculates. */
    static String stateJson(Context c) {
        try {
            JSONObject out = new JSONObject();
            out.put("url", url(c));
            out.put("widgetHoldings", widgetHoldings(c));
            out.put("hideValues", hideValues(c));
            out.put("fetchedMs", fetchedMs(c));
            out.put("error", prefs(c).getString(KEY_ERROR, ""));
            PortfolioCore.Summary s = summary(c);
            if (s != null) out.put("summary", summaryJson(s));
            return out.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static JSONObject summaryJson(PortfolioCore.Summary s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("value", s.value);
        put(o, "cost", s.cost);
        put(o, "gain", s.gain);
        put(o, "gainPct", s.gainPct);
        put(o, "dayChange", s.dayChange);
        put(o, "dayPct", s.dayPct);
        o.put("missingPrices", s.missingPrices);
        JSONArray list = new JSONArray();
        for (PortfolioCore.Holding h : s.holdings) {
            JSONObject j = new JSONObject();
            j.put("ticker", h.ticker);
            j.put("short", h.shortTicker());
            j.put("name", h.name);
            j.put("units", h.units);
            put(j, "avgCost", h.avgCost);
            put(j, "price", h.price);
            put(j, "prevClose", h.prevClose);
            j.put("value", h.value);
            put(j, "cost", h.cost);
            put(j, "gain", h.gain);
            put(j, "gainPct", h.gainPct);
            put(j, "dayChange", h.dayChange);
            put(j, "dayPct", h.dayPct);
            j.put("weight", h.weight);
            list.put(j);
        }
        o.put("holdings", list);
        return o;
    }

    private static void put(JSONObject o, String key, Double value) throws JSONException {
        o.put(key, value == null ? JSONObject.NULL : value);
    }
}
