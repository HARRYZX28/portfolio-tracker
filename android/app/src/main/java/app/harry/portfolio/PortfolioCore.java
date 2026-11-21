package app.harry.portfolio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * All the portfolio maths, in one place, with no Android code so it can be tested on
 * its own. Both the app screen and the home-screen widget use this, so they always agree.
 *
 * Input is the "Holdings" tab of the Google Sheet, published as CSV, with headings:
 *   Ticker | Name | Units | Avg cost | Price | Prev close | Month ago (optional)
 * Columns are found by heading, so their order (and any extra columns) doesn't matter.
 */
public final class PortfolioCore {

    private PortfolioCore() {}

    public static final class Holding {
        public String ticker = "";
        public String name = "";
        public double units;
        public Double avgCost;    // per unit; null if blank
        public Double price;      // null while GOOGLEFINANCE is loading or errors
        public Double prevClose;  // null for crypto / errors
        public Double monthAgo;   // closing price about 30 days ago; null if not in the Sheet

        // Worked out by compute()
        public double value;
        public Double cost;
        public Double gain;
        public Double gainPct;
        public Double dayChange;
        public Double dayPct;
        public Double monthChange;
        public Double monthPct;
        public double weight;

        /** "ASX:VAS" shows as "VAS"; "CURRENCY:BTCAUD" as "BTC". */
        public String shortTicker() {
            String t = ticker;
            int colon = t.indexOf(':');
            if (colon >= 0) {
                String exchange = t.substring(0, colon).toUpperCase(Locale.ROOT);
                t = t.substring(colon + 1);
                if (exchange.equals("CURRENCY") && t.length() == 6) t = t.substring(0, 3);
            }
            return t.toUpperCase(Locale.ROOT);
        }
    }

    public static final class Summary {
        public final List<Holding> holdings = new ArrayList<>();
        public double value;
        public Double cost;       // null if no holding has an avg cost
        public Double gain;
        public Double gainPct;
        public Double dayChange;  // null if no holding has a prev close
        public Double dayPct;
        public Double monthChange; // null if the Sheet has no Month ago column (or no values)
        public Double monthPct;
        public int missingPrices; // rows whose price couldn't be read (left out of totals)
    }

    /** Thrown when the CSV isn't the Holdings tab (for example the link is wrong). */
    public static final class FormatException extends Exception {
        public FormatException(String message) { super(message); }
    }

    public static Summary fromCsv(String csv) throws FormatException {
        return compute(parse(csv));
    }

    public static List<Holding> parse(String csv) throws FormatException {
        if (csv == null) throw new FormatException("Nothing came back from the Sheet link.");
        String trimmed = csv.trim();
        if (trimmed.startsWith("<")) {
            throw new FormatException("That link returned a web page, not CSV. Publish the Holdings tab as Comma-separated values (.csv).");
        }
        List<List<String>> rows = csvRows(csv);
        if (rows.isEmpty()) throw new FormatException("The Sheet is empty.");

        List<String> header = rows.get(0);
        int ticker = find(header, "ticker", "code", "symbol");
        int name = find(header, "name");
        int units = find(header, "units", "quantity", "qty", "shares");
        int avgCost = find(header, "avg cost", "average cost", "cost", "avg price", "buy price");
        int price = find(header, "price", "current price", "last price");
        int prev = find(header, "prev close", "previous close", "close yest", "closeyest", "prev");
        int month = find(header, "month ago", "1 month ago", "1m ago", "month");
        if (ticker < 0 || units < 0 || price < 0) {
            throw new FormatException("Couldn't find the Ticker, Units and Price headings in row 1 of the Holdings tab.");
        }

        List<Holding> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String t = cell(row, ticker).trim();
            Double u = number(cell(row, units));
            if (t.isEmpty() || u == null || u == 0) continue; // blank or sold-out rows
            Holding h = new Holding();
            h.ticker = t;
            h.name = name >= 0 ? cell(row, name).trim() : "";
            h.units = u;
            h.avgCost = avgCost >= 0 ? number(cell(row, avgCost)) : null;
            h.price = number(cell(row, price));
            h.prevClose = prev >= 0 ? number(cell(row, prev)) : null;
            h.monthAgo = month >= 0 ? number(cell(row, month)) : null;
            if (h.monthAgo != null && h.monthAgo <= 0) h.monthAgo = null;
            if (h.price != null && h.price <= 0) h.price = null;
            if (h.prevClose != null && h.prevClose <= 0) h.prevClose = null;
            out.add(h);
        }
        return out;
    }

    public static Summary compute(List<Holding> holdings) {
        Summary s = new Summary();
        double cost = 0, gainBase = 0, gain = 0, day = 0, dayBase = 0, mon = 0, monBase = 0;
        boolean anyCost = false, anyDay = false, anyMonth = false;

        for (Holding h : holdings) {
            s.holdings.add(h);
            if (h.price == null) {
                s.missingPrices++;
                h.value = 0;
                continue;
            }
            h.value = h.units * h.price;
            s.value += h.value;

            if (h.avgCost != null) {
                h.cost = h.units * h.avgCost;
                h.gain = h.value - h.cost;
                h.gainPct = h.cost != 0 ? h.gain / h.cost : null;
                cost += h.cost;
                gain += h.gain;
                gainBase += h.cost;
                anyCost = true;
            }
            if (h.prevClose != null) {
                h.dayChange = h.units * (h.price - h.prevClose);
                h.dayPct = (h.price - h.prevClose) / h.prevClose;
                day += h.dayChange;
                dayBase += h.units * h.prevClose;
                anyDay = true;
            }
            if (h.monthAgo != null) {
                h.monthChange = h.units * (h.price - h.monthAgo);
                h.monthPct = (h.price - h.monthAgo) / h.monthAgo;
                mon += h.monthChange;
                monBase += h.units * h.monthAgo;
                anyMonth = true;
            }
        }
        for (Holding h : s.holdings) h.weight = s.value > 0 ? h.value / s.value : 0;

        if (anyCost) {
            s.cost = cost;
            s.gain = gain;
            s.gainPct = gainBase != 0 ? gain / gainBase : null;
        }
        if (anyDay) {
            s.dayChange = day;
            s.dayPct = dayBase != 0 ? day / dayBase : null;
        }
        if (anyMonth) {
            s.monthChange = mon;
            s.monthPct = monBase != 0 ? mon / monBase : null;
        }
        // Biggest holdings first.
        Collections.sort(s.holdings, (a, b) -> Double.compare(b.value, a.value));
        return s;
    }

    // ---- CSV helpers ----------------------------------------------------------------

    /** Reads CSV with quoted fields ("1,234.50"), doubled quotes and CRLF line endings. */
    static List<List<String>> csvRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        if (text.startsWith("﻿")) i = 1; // byte-order mark
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else field.append(c);
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static int find(List<String> header, String... names) {
        // Exact match first, then "starts with", so "Price" doesn't grab "Prev close".
        for (String n : names) {
            for (int i = 0; i < header.size(); i++) {
                if (clean(header.get(i)).equals(n)) return i;
            }
        }
        for (String n : names) {
            for (int i = 0; i < header.size(); i++) {
                if (clean(header.get(i)).startsWith(n)) return i;
            }
        }
        return -1;
    }

    private static String clean(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z ]", "").replaceAll(" +", " ").trim();
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    /** "$1,234.50", "A$12", " 3 ", "(12.5)" → numbers; "#N/A", "Loading...", "" → null. */
    static Double number(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length() - 1); }
        s = s.replaceAll("[^0-9.\\-eE]", "");
        if (s.isEmpty() || s.equals("-") || s.equals(".")) return null;
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
