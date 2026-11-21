package app.harry.portfolio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home-screen widget: one card at a time. Tap the card for the next one; tap ↗ to open
 * the app. Cards: Total value, Today, Total gain, then each holding (optional).
 * Android refreshes it about every 30 minutes by reading the published Sheet CSV,
 * and the app refreshes it whenever it's open.
 */
public class PortfolioWidget extends AppWidgetProvider {

    static final String ACTION_NEXT_CARD = "app.harry.portfolio.widget.NEXT_CARD";

    static final int GREEN = Color.parseColor("#2EE6A6");
    static final int RED = Color.parseColor("#F87171");
    static final int WHITE = Color.parseColor("#EFF7F7");
    static final int MUTED = Color.parseColor("#96A1A1");

    private static final int COMPACT_BELOW_DP = 90;

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
    public void onReceive(Context context, Intent intent) {
        if (ACTION_NEXT_CARD.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                saveIndex(context, id, index(context, id) + 1);
                safeDraw(context, AppWidgetManager.getInstance(context), id);
            }
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor e = widgetPrefs(context).edit();
        for (int id : ids) e.remove("index_" + id);
        e.apply();
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
        List<Card> cards = cards(context);
        RemoteViews tall = fill(context, widgetId, R.layout.widget, cards, true);
        RemoteViews compact = fill(context, widgetId, R.layout.widget_compact, cards, false);
        RemoteViews views;
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> bySize = new HashMap<>();
            bySize.put(new SizeF(110f, 40f), compact);
            bySize.put(new SizeF(110f, COMPACT_BELOW_DP), tall);
            views = new RemoteViews(bySize);
        } else {
            Bundle options = manager.getAppWidgetOptions(widgetId);
            int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, COMPACT_BELOW_DP);
            views = minHeight < COMPACT_BELOW_DP ? compact : tall;
        }
        manager.updateAppWidget(widgetId, views);
    }

    // ---- Cards ------------------------------------------------------------------------

    static final class Card {
        String label;
        CharSequence amount;
        int amountColor;
        CharSequence footer;
    }

    private static List<Card> cards(Context context) {
        List<Card> out = new ArrayList<>();
        PortfolioCore.Summary s = PortfolioStore.summary(context);
        if (s == null) return out;
        boolean hide = PortfolioStore.hideValues(context);

        Card total = new Card();
        total.label = "PORTFOLIO";
        total.amount = hide ? "$•••••" : money(s.value, false);
        total.amountColor = WHITE;
        SpannableStringBuilder f = new SpannableStringBuilder();
        if (s.dayChange != null) {
            f.append("Today ");
            colored(f, (hide ? "" : money(s.dayChange, true) + " ") + "(" + pct(s.dayPct) + ")", s.dayChange);
        }
        total.footer = f;
        out.add(total);

        if (s.dayChange != null) {
            Card today = new Card();
            today.label = "TODAY";
            today.amount = hide ? pct(s.dayPct) : money(s.dayChange, true);
            today.amountColor = s.dayChange >= 0 ? GREEN : RED;
            today.footer = hide ? "" : pct(s.dayPct);
            out.add(today);
        }

        if (s.gain != null) {
            Card gain = new Card();
            gain.label = "TOTAL GAIN";
            gain.amount = hide ? pct(s.gainPct) : money(s.gain, true);
            gain.amountColor = s.gain >= 0 ? GREEN : RED;
            gain.footer = hide ? "" : pct(s.gainPct) + " on " + money(s.cost, false);
            out.add(gain);
        }

        if (PortfolioStore.widgetHoldings(context)) {
            for (PortfolioCore.Holding h : s.holdings) {
                if (h.price == null) continue;
                Card c = new Card();
                c.label = h.shortTicker() + (h.name.isEmpty() ? "" : "  ·  " + h.name.toUpperCase(Locale.ROOT));
                c.amount = hide ? price(h.price) : money(h.value, false);
                c.amountColor = WHITE;
                SpannableStringBuilder hf = new SpannableStringBuilder();
                if (h.dayPct != null) {
                    hf.append("Today ");
                    colored(hf, pct(h.dayPct), h.dayPct);
                }
                if (h.gain != null) {
                    if (hf.length() > 0) hf.append("   ");
                    hf.append("Gain ");
                    colored(hf, hide ? pct(h.gainPct) : money(h.gain, true), h.gain);
                }
                c.footer = hf;
                out.add(c);
            }
        }
        return out;
    }

    private static RemoteViews fill(Context context, int widgetId, int layout, List<Card> cards, boolean tall) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        PendingIntent openApp = openAppIntent(context);
        views.setOnClickPendingIntent(R.id.open_app, openApp);

        if (cards.isEmpty()) {
            views.setViewVisibility(R.id.card_content, View.GONE);
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
            views.setOnClickPendingIntent(R.id.widget_root, openApp);
            return views;
        }

        int index = Math.floorMod(index(context, widgetId), cards.size());
        Card card = cards.get(index);
        views.setViewVisibility(R.id.widget_empty, View.GONE);
        views.setViewVisibility(R.id.card_content, View.VISIBLE);
        views.setTextViewText(R.id.card_label, card.label);
        views.setTextViewText(R.id.card_amount, card.amount);
        views.setTextColor(R.id.card_amount, card.amountColor);
        if (tall) {
            SpannableStringBuilder footer = new SpannableStringBuilder(card.footer == null ? "" : card.footer);
            String time = updatedTime(context);
            if (!time.isEmpty()) footer.append(footer.length() > 0 ? "  ·  " : "").append(time);
            views.setTextViewText(R.id.card_footer, footer);
        }
        views.setTextViewText(R.id.card_dots, dots(index, cards.size()));
        views.setOnClickPendingIntent(R.id.widget_root, cards.size() > 1 ? nextCardIntent(context, widgetId) : openApp);
        return views;
    }

    private static String dots(int index, int count) {
        if (count <= 1) return "";
        if (count > 6) return (index + 1) + " / " + count;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append("  ");
            out.append(i == index ? "●" : "○");
        }
        return out.toString();
    }

    private static String updatedTime(Context context) {
        long ms = PortfolioStore.fetchedMs(context);
        return ms > 0 ? DateFormat.getTimeFormat(context).format(new Date(ms)) : "";
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

    static String pct(Double p) {
        if (p == null) return "—";
        double v = p * 100;
        return (v < 0 ? "−" : "+") + String.format(Locale.US, "%.2f", Math.abs(v)) + "%";
    }

    // ---- Per-widget card position ----------------------------------------------------

    private static SharedPreferences widgetPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("widget", Context.MODE_PRIVATE);
    }

    private static int index(Context c, int id) { return widgetPrefs(c).getInt("index_" + id, 0); }

    private static void saveIndex(Context c, int id, int index) {
        widgetPrefs(c).edit().putInt("index_" + id, index).apply();
    }

    private static PendingIntent openAppIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent nextCardIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, PortfolioWidget.class)
            .setAction(ACTION_NEXT_CARD)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
