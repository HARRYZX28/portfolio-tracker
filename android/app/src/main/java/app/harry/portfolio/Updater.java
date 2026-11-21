package app.harry.portfolio;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * In-app updates from this repo's GitHub Releases (public, so no sign-in needed).
 * check() looks at the latest release; install() downloads its APK and hands it to
 * Android's installer, which asks you to confirm. Updates keep all settings.
 */
final class Updater {

    static final String LATEST = "https://api.github.com/repos/HARRYZX28/portfolio-tracker/releases/latest";

    private Updater() {}

    /** {available, version, notes, apk} as JSON, or {available:false, error}. Background thread only. */
    static JSONObject check(Context c) {
        JSONObject out = new JSONObject();
        try {
            out.put("available", false);
            out.put("current", currentVersion(c));
            HttpURLConnection conn = (HttpURLConnection) new URL(LATEST).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "portfolio-app");
            try {
                if (conn.getResponseCode() != 200) {
                    out.put("error", "HTTP " + conn.getResponseCode());
                    return out;
                }
                JSONObject release = new JSONObject(readAll(conn.getInputStream()));
                String version = release.optString("tag_name", "").replaceFirst("^v", "");
                String apk = "";
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
                    }
                }
                out.put("version", version);
                out.put("notes", release.optString("body", ""));
                out.put("apk", apk);
                out.put("available", !apk.isEmpty() && newer(version, currentVersion(c)));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            try { out.put("error", e.getClass().getSimpleName()); } catch (Exception ignored) { }
        }
        return out;
    }

    /** Downloads the APK and starts Android's installer. Returns null, or a message if it failed. */
    static String install(Context c, String apkUrl) {
        if (apkUrl == null || !apkUrl.startsWith("https://github.com/HARRYZX28/portfolio-tracker/releases/download/")) {
            return "Unexpected download link.";
        }
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int sessionId = -1;
        try {
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                String address = apkUrl;
                boolean written = false;
                for (int hop = 0; hop < 5; hop++) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("User-Agent", "portfolio-app");
                    try {
                        int code = conn.getResponseCode();
                        if (code >= 300 && code < 400) {
                            address = new URL(new URL(address), conn.getHeaderField("Location")).toString();
                            if (!address.startsWith("https://")) return "Unexpected download link.";
                            continue;
                        }
                        if (code != 200) return "Download failed (HTTP " + code + ").";
                        try (InputStream in = conn.getInputStream();
                             OutputStream out = session.openWrite("portfolio.apk", 0, conn.getContentLengthLong())) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            session.fsync(out);
                        }
                        written = true;
                        break;
                    } finally {
                        conn.disconnect();
                    }
                }
                if (!written) {
                    installer.abandonSession(sessionId);
                    return "Couldn't download the update.";
                }
                Intent done = new Intent(c, InstallResultReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(c, sessionId, done, flags);
                session.commit(pi.getIntentSender());
            }
            return null;
        } catch (Exception e) {
            if (sessionId != -1) {
                try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
            }
            return "Couldn't download the update (" + e.getClass().getSimpleName() + ").";
        }
    }

    /** Android reports back here: usually "needs your confirmation", so we show its install screen. */
    public static class InstallResultReceiver extends BroadcastReceiver {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.util.Log.w("Updater", "Install failed: " + status + " " + msg);
                MainActivity.notifyUpdateFailed(msg == null ? "Install cancelled." : msg);
            }
        }
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /** "1.10.0" is newer than "1.9.2". */
    static boolean newer(String latest, String current) {
        String[] a = latest.split("\\."), b = current.split("[.-]");
        for (int i = 0; i < 3; i++) {
            int x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream stream = in; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
