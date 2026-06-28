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

    /**
     * A position. Two flavours:
     *  - directional (option=false): LONG/SHORT on the index; entryPrice is the spot.
     *  - option (option=true): buying/selling a CE/PE; entryPrice is the premium paid,
     *    re-priced live from spot via Black-Scholes.
     */
    public static class Position {
        public long id;
        public String side;          // "LONG" (buy) or "SHORT" (sell)
        public int lots;
        public int units;            // lots × LOT_SIZE
        public BigDecimal entryPrice;// spot (directional) or premium (option)
        public String entryTime;
        // bracket levels (nullable) — directional: index price; option: premium
        public BigDecimal sl;
        public BigDecimal target;
        // option fields
        public boolean option;
        public String optType;       // "CE" or "PE"
        public double strike;
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
        public boolean option;
        public String optType;
        public double strike;
    }

    private LocalDate date;
    private String timeframe;
    private BigDecimal startingCapital;
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    // Synthetic option-chain parameters for this session
    private double iv = 0.12;          // implied volatility (fraction)
    private double tteYears = 7.0 / 365.0; // time to expiry in years

    private final List<Position> positions = new ArrayList<>();
    private final List<TradeLog> trades = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    public ReplaySession(LocalDate date, String timeframe, BigDecimal startingCapital) {
        this.date = date;
        this.timeframe = timeframe;
        this.startingCapital = startingCapital;
    }

    public synchronized Position open(String side, int lots, BigDecimal price, String time,
                                      BigDecimal sl, BigDecimal target) {
        Position p = new Position();
        p.id = seq.getAndIncrement();
        p.side = side;
        p.lots = lots;
        p.units = lots * LOT_SIZE;
        p.entryPrice = price;
        p.entryTime = time;
        p.sl = sl;
        p.target = target;
        positions.add(p);
        return p;
    }

    /** Open an option position (CE/PE). entryPremium is computed from spot by the caller. */
    public synchronized Position openOption(String optType, double strike, String side, int lots,
                                            BigDecimal entryPremium, String time,
                                            BigDecimal sl, BigDecimal target) {
        Position p = new Position();
        p.id = seq.getAndIncrement();
        p.side = side;
        p.lots = lots;
        p.units = lots * LOT_SIZE;
        p.entryPrice = entryPremium;
        p.entryTime = time;
        p.sl = sl;
        p.target = target;
        p.option = true;
        p.optType = optType;
        p.strike = strike;
        positions.add(p);
        return p;
    }

    /** Current premium of an option position at the given spot (Black-Scholes). */
    private BigDecimal premiumAt(Position p, double spot) {
        double prem = BlackScholes.price(spot, p.strike, tteYears, iv, "CE".equals(p.optType)).price();
        return BigDecimal.valueOf(prem);
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

    private TradeLog realize(Position p, BigDecimal spot, String time) {
        // For options the "exit price" is the current premium; for directional it's the spot.
        BigDecimal exit = p.option ? premiumAt(p, spot.doubleValue()) : spot;
        BigDecimal diff = "LONG".equals(p.side)
            ? exit.subtract(p.entryPrice)
            : p.entryPrice.subtract(exit);
        BigDecimal pnl = diff.multiply(BigDecimal.valueOf(p.units)).setScale(2, RoundingMode.HALF_UP);
        realizedPnl = realizedPnl.add(pnl);

        TradeLog t = new TradeLog();
        t.id = p.id;
        t.side = p.side;
        t.lots = p.lots;
        t.entryPrice = p.entryPrice;
        t.exitPrice = exit;
        t.entryTime = p.entryTime;
        t.exitTime = time;
        t.pnl = pnl;
        t.option = p.option;
        t.optType = p.optType;
        t.strike = p.strike;
        trades.add(t);
        return t;
    }

    /** Unrealized P&L for all open positions at the given spot. */
    public synchronized BigDecimal unrealized(BigDecimal spot) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Position p : positions) {
            BigDecimal mark = p.option ? premiumAt(p, spot.doubleValue()) : spot;
            BigDecimal diff = "LONG".equals(p.side)
                ? mark.subtract(p.entryPrice)
                : p.entryPrice.subtract(mark);
            sum = sum.add(diff.multiply(BigDecimal.valueOf(p.units)));
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    public double getIv() { return iv; }
    public void setIv(double iv) { this.iv = iv; }
    public double getTteYears() { return tteYears; }
    public void setTteYears(double tteYears) { this.tteYears = tteYears; }

    public LocalDate getDate() { return date; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String tf) { this.timeframe = tf; }
    public BigDecimal getStartingCapital() { return startingCapital; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public synchronized List<Position> getPositions() { return new ArrayList<>(positions); }
    public synchronized List<TradeLog> getTrades() { return new ArrayList<>(trades); }
    public synchronized int getOpenLots() { return positions.stream().mapToInt(p -> p.lots).sum(); }
}
