package com.niftyautotrader.service.replay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory state for a single market-replay session (one per browser).
 *
 * This is a fully self-contained demo book — it deliberately does NOT touch the
 * RiskEngine, PaperBroker, or any live-trading code. It only tracks virtual
 * capital, open positions, and a trade log so a user can manually backtest by
 * buying and selling Nifty 50 at the replayed price.
 *
 * Lot size = 75 units. A directional position is sized in units (lots × 75); since
 * the entry/exit prices are in Nifty index points, P&L in rupees is simply
 * (priceDiff × units) — 1 point = ₹75 per lot.
 */
public class ReplaySession {

    public static final int LOT_SIZE = 75;

    /** A LONG (buy CE-style) or SHORT (buy PE-style) directional position. */
    public static class Position {
        public long id;
        public String side;          // "LONG" or "SHORT"
        public int lots;
        public int units;            // lots × LOT_SIZE
        public BigDecimal entryPrice;
        public String entryTime;     // IST wall-clock string from the replay candle
    }

    /** A closed (realized) trade in the session log. */
    public static class TradeLog {
        public long id;
        public String side;
        public int lots;
        public BigDecimal entryPrice;
        public BigDecimal exitPrice;
        public String entryTime;
        public String exitTime;
        public BigDecimal pnl;
    }

    private LocalDate date;
    private String timeframe;
    private BigDecimal startingCapital;
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    private final List<Position> positions = new ArrayList<>();
    private final List<TradeLog> trades = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    public ReplaySession(LocalDate date, String timeframe, BigDecimal startingCapital) {
        this.date = date;
        this.timeframe = timeframe;
        this.startingCapital = startingCapital;
    }

    public synchronized Position open(String side, int lots, BigDecimal price, String time) {
        Position p = new Position();
        p.id = seq.getAndIncrement();
        p.side = side;
        p.lots = lots;
        p.units = lots * LOT_SIZE;
        p.entryPrice = price;
        p.entryTime = time;
        positions.add(p);
        return p;
    }

    public synchronized TradeLog close(long posId, BigDecimal price, String time) {
        Position p = positions.stream().filter(x -> x.id == posId).findFirst().orElse(null);
        if (p == null) return null;
        positions.remove(p);
        return realize(p, price, time);
    }

    public synchronized List<TradeLog> squareOffAll(BigDecimal price, String time) {
        List<TradeLog> closed = new ArrayList<>();
        for (Position p : new ArrayList<>(positions)) {
            closed.add(realize(p, price, time));
        }
        positions.clear();
        return closed;
    }

    private TradeLog realize(Position p, BigDecimal price, String time) {
        BigDecimal diff = "LONG".equals(p.side)
            ? price.subtract(p.entryPrice)
            : p.entryPrice.subtract(price);
        BigDecimal pnl = diff.multiply(BigDecimal.valueOf(p.units)).setScale(2, RoundingMode.HALF_UP);
        realizedPnl = realizedPnl.add(pnl);

        TradeLog t = new TradeLog();
        t.id = p.id;
        t.side = p.side;
        t.lots = p.lots;
        t.entryPrice = p.entryPrice;
        t.exitPrice = price;
        t.entryTime = p.entryTime;
        t.exitTime = time;
        t.pnl = pnl;
        trades.add(t);
        return t;
    }

    /** Unrealized P&L for all open positions at the given mark price. */
    public synchronized BigDecimal unrealized(BigDecimal markPrice) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Position p : positions) {
            BigDecimal diff = "LONG".equals(p.side)
                ? markPrice.subtract(p.entryPrice)
                : p.entryPrice.subtract(markPrice);
            sum = sum.add(diff.multiply(BigDecimal.valueOf(p.units)));
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    public LocalDate getDate() { return date; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String tf) { this.timeframe = tf; }
    public BigDecimal getStartingCapital() { return startingCapital; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public synchronized List<Position> getPositions() { return new ArrayList<>(positions); }
    public synchronized List<TradeLog> getTrades() { return new ArrayList<>(trades); }
    public synchronized int getOpenLots() { return positions.stream().mapToInt(p -> p.lots).sum(); }
}
