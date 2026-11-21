package app.harry.portfolio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home-screen widget. The layout follows the widget's size:
 *  - 1 row:   ticker tile, total value, today's change
 *  - 2 rows:  total, today, total gain, a value chart and the top holding
 *  - 3+ rows: the same with up to 4 holdings
 * Tap anywhere to open the app. Android refreshes it about every 30 minutes by reading
 * the published Sheet, and the app refreshes it whenever it's open.
 */
public class PortfolioWidget extends AppWidgetProvider {

    static final int GREEN = Color.parseColor("#2EE6A6");
    static final int RED = Color.parseColor("#F87171");
    static final int WHITE = Color.parseColor("#EFF7F7");
    static final int MUTED = Color.parseColor("#96A1A1");

    private static final int MEDIUM_FROM_DP = 100;
    private static final int LARGE_FROM_DP = 200;
    private static final int[][] ROWS = {
        { R.id.row1, R.id.row1_tile, R.id.row1_name, R.id.row1_sub, R.id.row1_value, R.id.row1_change },
        { R.id.row2, R.id.row2_tile, R.id.row2_name, R.id.row2_sub, R.id.row2_value, R.id.row2_change },
        { R.id.row3, R.id.row3_tile, R.id.row3_name, R.id.row3_sub, R.id.row3_value, R.id.row3_change },
        { R.id.row4, R.id.row4_tile, R.id.row4_name, R.id.row4_sub, R.id.row4_value, R.id.row4_change },
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) safeDraw(context, manager, id);
        if (PortfolioStore.url(context).isEmpty()) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (PortfolioStore.fetch(app) == null) redrawAll(app);
            } finally {
                pending.finish();
            }
        }).start();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int widgetId, Bundle options) {
        safeDraw(context, manager, widgetId);
    }

    /** Redraw every Portfolio widget from the saved copy of the Sheet. */
    static void redrawAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, PortfolioWidget.class));
        for (int id : ids) safeDraw(context, manager, id);
    }

    static void safeDraw(Context context, AppWidgetManager manager, int widgetId) {
        try {
            draw(context, manager, widgetId);
        } catch (Exception e) {
            android.util.Log.w("PortfolioWidget", "Could not draw widget " + widgetId, e);
        }
    }

    private static void draw(Context context, AppWidgetManager manager, int widgetId) {
        PortfolioCore.Summary s = PortfolioStore.summary(context);
        Bitmap chart = s == null ? null : chartBitmap(PortfolioStore.history(context));
        RemoteViews compact = compact(context, s);
        RemoteViews medium = full(context, s, chart, R.layout.widget_medium, 1);
        RemoteViews large = full(context, s, chart, R.layout.widget_large, 4);
        RemoteViews views;
        if (Build.VERSION.SDK_INT >= 31) {
            // The home screen picks the biggest layout that fits the widget's current size.
            Map<SizeF, RemoteViews> bySize = new HashMap<>();
            bySize.put(new SizeF(110f, 40f), compact);
            bySize.put(new SizeF(110f, MEDIUM_FROM_DP), medium);
            bySize.put(new SizeF(110f, LARGE_FROM_DP), large);
            views = new RemoteViews(bySize);
        } else {
            Bundle options = manager.getAppWidgetOptions(widgetId);
            int h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, MEDIUM_FROM_DP);
            views = h < MEDIUM_FROM_DP ? compact : h < LARGE_FROM_DP ? medium : large;
        }
        manager.updateAppWidget(widgetId, views);
    }

    // ---- Layouts ----------------------------------------------------------------------

    private static RemoteViews compact(Context context, PortfolioCore.Summary s) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_compact);
        if (!start(context, v, s)) return v;
        boolean hide = PortfolioStore.hideValues(context);
        if (s.holdings.size() == 1) {
            PortfolioCore.Holding h = s.holdings.get(0);
            v.setTextViewText(R.id.tile, h.shortTicker());
            v.setTextViewText(R.id.title, (h.name.isEmpty() ? h.shortTicker() : h.name).toUpperCase(Locale.ROOT));
        } else {
            v.setTextViewText(R.id.tile, String.valueOf(s.holdings.size()));
            v.setTextViewText(R.id.title, "PORTFOLIO");
        }
        v.setTextViewText(R.id.amount, hide ? "$•••••" : money(s.value, false));
        dayChip(v, s.dayPct);
        return v;
    }

    private static RemoteViews full(Context context, PortfolioCore.Summary s, Bitmap chart, int layout, int maxRows) {
        RemoteViews v = new RemoteViews(context.getPackageName(), layout);
        if (!start(context, v, s)) return v;
        boolean hide = PortfolioStore.hideValues(context);

        int count = s.holdings.size();
        v.setTextViewText(R.id.title, "PORTFOLIO  ·  " + count + (count == 1 ? " HOLDING" : " HOLDINGS"));
        long ms = PortfolioStore.fetchedMs(context);
        v.setTextViewText(R.id.updated, ms > 0 ? DateFormat.getTimeFormat(context).format(new Date(ms)) : "");
        v.setTextViewText(R.id.amount, hide ? "$•••••" : money(s.value, false));
        dayChip(v, s.dayPct);

        SpannableStringBuilder gain = new SpannableStringBuilder();
        if (s.gain != null) {
            gain.append("Total gain  ");
            colored(gain, hide ? pct(s.gainPct) : money(s.gain, true) + "  (" + pct(s.gainPct) + ")", s.gain);
        } else if (s.dayChange != null && !hide) {
            gain.append("Today  ");
            colored(gain, money(s.dayChange, true), s.dayChange);
        }
        v.setTextViewText(R.id.gain, gain);

        if (chart != null) {
            v.setImageViewBitmap(R.id.chart, chart);
            v.setViewVisibility(R.id.chart, View.VISIBLE);
            v.setViewVisibility(R.id.chart_hint, View.GONE);
        } else {
            v.setViewVisibility(R.id.chart, View.GONE);
            v.setViewVisibility(R.id.chart_hint, View.VISIBLE);
        }

        int shown = 0;
        if (PortfolioStore.widgetHoldings(context)) {
            for (PortfolioCore.Holding h : s.holdings) {
                if (shown >= maxRows) break;
                int[] ids = ROWS[shown++];
                v.setViewVisibility(ids[0], View.VISIBLE);
                v.setTextViewText(ids[1], h.shortTicker());
                v.setTextViewText(ids[2], h.name.isEmpty() ? h.shortTicker() : h.name);
                String price = h.price == null ? "no price" : price(h.price);
                v.setTextViewText(ids[3], hide ? price : units(h.units) + " × " + price);
                v.setTextViewText(ids[4], h.price == null ? "—" : hide ? pct(h.gainPct) : money(h.value, false));
                Double change = h.dayPct;
                v.setTextViewText(ids[5], change == null ? (h.gainPct == null ? "" : pct(h.gainPct)) : arrow(change) + " " + pctAbs(change));
                Double sign = change != null ? change : h.gainPct;
                v.setTextColor(ids[5], sign == null ? MUTED : sign >= 0 ? GREEN : RED);
            }
        }
        for (int i = shown; i < maxRows; i++) v.setViewVisibility(ROWS[i][0], View.GONE);
        return v;
    }

    /** Shared setup. Returns false (showing the "open the app" message) if there's no data yet. */
    private static boolean start(Context context, RemoteViews v, PortfolioCore.Summary s) {
        PendingIntent open = openAppIntent(context);
        v.setOnClickPendingIntent(R.id.widget_root, open);
        v.setOnClickPendingIntent(R.id.open_app, open);
        boolean ready = s != null && !s.holdings.isEmpty();
        v.setViewVisibility(R.id.card_content, ready ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.widget_empty, ready ? View.GONE : View.VISIBLE);
        return ready;
    }

    private static void dayChip(RemoteViews v, Double dayPct) {
        if (dayPct == null) {
            v.setViewVisibility(R.id.day_chip, View.GONE);
            return;
        }
        v.setViewVisibility(R.id.day_chip, View.VISIBLE);
        v.setTextViewText(R.id.day_chip, arrow(dayPct) + " " + pctAbs(dayPct));
        boolean flat = Math.abs(dayPct) < 0.00005;
        v.setTextColor(R.id.day_chip, flat ? MUTED : dayPct > 0 ? GREEN : RED);
        v.setInt(R.id.day_chip, "setBackgroundResource", flat ? R.drawable.chip_flat : dayPct > 0 ? R.drawable.chip_pos : R.drawable.chip_neg);
    }

    // ---- Value chart ------------------------------------------------------------------

    /** A smooth line of portfolio value over time with a soft fill, or null if there's not enough yet. */
    static Bitmap chartBitmap(List<double[]> points) {
        if (points.size() < 2) return null;
        final int w = 720, h = 180, pad = 10;
        double t0 = points.get(0)[0], t1 = points.get(points.size() - 1)[0];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double[] p : points) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
        double span = hi - lo;
        if (span < Math.max(0.01, hi * 0.002)) { // nearly flat: keep it in the middle
            double mid = (hi + lo) / 2, half = Math.max(0.01, hi * 0.001);
            lo = mid - half; hi = mid + half; span = hi - lo;
        }
        double tSpan = Math.max(1, t1 - t0);

        float[] xs = new float[points.size()], ys = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = (float) (pad + (points.get(i)[0] - t0) / tSpan * (w - 2 * pad));
            ys[i] = (float) (pad + (1 - (points.get(i)[1] - lo) / span) * (h - 2 * pad));
        }
        boolean up = points.get(points.size() - 1)[1] >= points.get(0)[1];
        int color = up ? GREEN : RED;

        Path line = new Path();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) {
            float cx = (xs[i - 1] + xs[i]) / 2;
            line.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i]);
        }
        Path fill = new Path(line);
        fill.lineTo(xs[xs.length - 1], h);
        fill.lineTo(xs[0], h);
        fill.close();

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
        base.setStyle(Paint.Style.STROKE);
        base.setStrokeWidth(2);
        base.setColor(Color.argb(40, 255, 255, 255));
        base.setPathEffect(new DashPathEffect(new float[] { 8, 10 }, 0));
        c.drawLine(pad, ys[0], w - pad, ys[0], base); // where the chart started

        Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        area.setShader(new LinearGradient(0, 0, 0, h, Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP));
        c.drawPath(fill, area);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(5);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        c.drawPath(line, stroke);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)));
        c.drawCircle(xs[xs.length - 1], ys[ys.length - 1], 14, dot);
        dot.setColor(WHITE);
        c.drawCircle(xs[xs.length - 1], ys[ys.length - 1], 7, dot);
        return bmp;
    }

    // ---- Formatting -------------------------------------------------------------------

    private static void colored(SpannableStringBuilder sb, String text, Double sign) {
        int start = sb.length();
        sb.append(text);
        int color = sign == null ? MUTED : sign >= 0 ? GREEN : RED;
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** $12,345 from $1,000 up, $123.45 below that; signed adds + or −. */
    static String money(Double v, boolean signed) {
        if (v == null) return "—";
        double a = Math.abs(v);
        String body = a >= 1000 ? String.format(Locale.US, "%,.0f", a) : String.format(Locale.US, "%,.2f", a);
        String sign = v < 0 ? "−" : signed ? "+" : "";
        return sign + "$" + body;
    }

    static String price(double v) {
        return "$" + (v >= 1000 ? String.format(Locale.US, "%,.2f", v) : v >= 1 ? String.format(Locale.US, "%.2f", v) : String.format(Locale.US, "%.4f", v));
    }

    static String units(double u) {
        return u == Math.rint(u) ? String.format(Locale.US, "%,.0f", u) : String.format(Locale.US, "%,.4f", u).replaceAll("0+$", "");
    }

    static String pct(Double p) {
        if (p == null) return "—";
        return (p < 0 ? "−" : "+") + pctAbs(p);
    }

    static String pctAbs(double p) {
        return String.format(Locale.US, "%.2f", Math.abs(p * 100)) + "%";
    }

    static String arrow(double p) {
        return Math.abs(p) < 0.00005 ? "•" : p > 0 ? "▲" : "▼";
    }

    private static PendingIntent openAppIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
