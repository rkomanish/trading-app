package com.niftyautotrader.service.replay;

/**
 * Black-Scholes option pricing for the replay simulator's synthetic option chain.
 *
 * IMPORTANT: these are MODELLED premiums, not real historical option prices. We only
 * store spot OHLC, so the chain is reconstructed from spot using Black-Scholes with an
 * assumed implied volatility. The behaviour (delta, theta decay, how a CE gains as spot
 * rises) is realistic for practising option buying/selling, but premiums won't match the
 * exact prices the live market printed on that day.
 */
public final class BlackScholes {

    /** India risk-free rate (~6.5%) — only a small influence on short-dated premiums. */
    public static final double RATE = 0.065;

    private BlackScholes() {}

    public record Greeks(double price, double delta, double theta, double vega) {}

    /**
     * @param spot     current underlying (Nifty spot)
     * @param strike   option strike
     * @param tteYears time to expiry in years (floored to avoid div-by-zero)
     * @param iv       implied volatility as a fraction (e.g. 0.12 = 12%)
     * @param isCall   true for CE, false for PE
     */
    public static Greeks price(double spot, double strike, double tteYears, double iv, boolean isCall) {
        double t = Math.max(tteYears, 1.0 / (365.0 * 24.0)); // floor at ~1 hour
        double vol = Math.max(iv, 0.001);
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(spot / strike) + (RATE + 0.5 * vol * vol) * t) / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;
        double disc = Math.exp(-RATE * t);

        double price, delta;
        if (isCall) {
            price = spot * cdf(d1) - strike * disc * cdf(d2);
            delta = cdf(d1);
        } else {
            price = strike * disc * cdf(-d2) - spot * cdf(-d1);
            delta = cdf(d1) - 1.0;
        }

        double pdfD1 = pdf(d1);
        // Vega per 1% change in IV
        double vega = spot * pdfD1 * sqrtT / 100.0;
        // Theta per calendar day
        double thetaAnnual = isCall
            ? (-(spot * pdfD1 * vol) / (2 * sqrtT) - RATE * strike * disc * cdf(d2))
            : (-(spot * pdfD1 * vol) / (2 * sqrtT) + RATE * strike * disc * cdf(-d2));
        double theta = thetaAnnual / 365.0;

        return new Greeks(Math.max(price, 0.0), delta, theta, vega);
    }

    /** Standard normal PDF. */
    private static double pdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    /** Standard normal CDF via the Abramowitz-Stegun erf approximation. */
    private static double cdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private static double erf(double z) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(z));
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                        - 0.284496736) * t + 0.254829592) * t * Math.exp(-z * z);
        return z >= 0 ? y : -y;
    }
}
