package app.harry.portfolio;

import static org.junit.Assert.*;

import org.junit.Test;

public class PortfolioCoreTest {

    private static final String CSV = "﻿Ticker,Name,Units,Avg cost,Price,Prev close\r\n"
        + "ASX:VAS,Vanguard Aus Shares,120,98.50,\"$101.20\",100.00\r\n"
        + "ASX:NDQ,\"Betashares, Nasdaq\",\"1,000\",40,45.5,46\r\n"
        + "CURRENCY:BTCAUD,Bitcoin,0.05,60000,150000,#N/A\r\n"
        + "ASX:XYZ,Broken,10,5,Loading...,\r\n"
        + ",,,,,\r\n";

    @Test
    public void readsTheSheetAndDoesTheMaths() throws Exception {
        PortfolioCore.Summary s = PortfolioCore.fromCsv(CSV);
        assertEquals(4, s.holdings.size());
        assertEquals(1, s.missingPrices);

        double value = 120 * 101.2 + 1000 * 45.5 + 0.05 * 150000;
        assertEquals(value, s.value, 1e-6);

        // Biggest first, short tickers, quoted names with commas.
        assertEquals("NDQ", s.holdings.get(0).shortTicker());
        assertEquals("Betashares, Nasdaq", s.holdings.get(0).name);

        double day = 120 * 1.2 + 1000 * -0.5;
        assertEquals(day, s.dayChange, 1e-6);
        assertEquals(day / (120 * 100.0 + 1000 * 46.0), s.dayPct, 1e-9);

        // A holding with no price is left out of cost and gain as well as value.
        double cost = 120 * 98.5 + 1000 * 40 + 0.05 * 60000;
        assertEquals(cost, s.cost, 1e-6);
        assertEquals(value - cost, s.gain, 1e-6);
    }

    @Test
    public void cryptoHasNoDayChange() throws Exception {
        PortfolioCore.Summary s = PortfolioCore.fromCsv(CSV);
        for (PortfolioCore.Holding h : s.holdings) {
            if (h.ticker.startsWith("CURRENCY")) {
                assertEquals("BTC", h.shortTicker());
                assertNull(h.dayChange);
                return;
            }
        }
        fail("no BTC row");
    }

    @Test
    public void rejectsWrongLinks() {
        try { PortfolioCore.fromCsv("<!DOCTYPE html><html>"); fail(); } catch (PortfolioCore.FormatException expected) { }
        try { PortfolioCore.fromCsv("A,B\n1,2"); fail(); } catch (PortfolioCore.FormatException expected) { }
    }

    @Test
    public void monthChangeFromMonthAgoColumn() throws Exception {
        PortfolioCore.Summary s = PortfolioCore.fromCsv(
            "Ticker,Name,Units,Avg cost,Price,Prev close,Month ago,https://link\n"
            + "ASX:QAN,Qantas,10,9.66,9.64,9.68,8.45\n"
            + "CURRENCY:BTCAUD,Bitcoin,1,1,2,,\n");
        assertEquals(10 * (9.64 - 8.45), s.monthChange, 1e-9);
        assertEquals((9.64 - 8.45) / 8.45, s.monthPct, 1e-9);
        assertNull(s.holdings.get(1).monthPct); // crypto: no month-ago price
    }

    @Test
    public void noMonthColumnMeansNoMonthChange() throws Exception {
        assertNull(PortfolioCore.fromCsv(CSV).monthChange);
    }

    @Test
    public void emptySheetIsFine() throws Exception {
        PortfolioCore.Summary s = PortfolioCore.fromCsv("Ticker,Units,Price\n");
        assertTrue(s.holdings.isEmpty());
        assertNull(s.cost);
        assertNull(s.dayChange);
    }
}
