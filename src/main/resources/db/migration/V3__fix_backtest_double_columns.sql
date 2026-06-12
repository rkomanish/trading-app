-- Align numeric columns with their Java types.
-- win_rate, profit_factor and optimizer_score are mapped as Java double/Double,
-- which Hibernate validates against SQL `double precision` (float8), not DECIMAL.
ALTER TABLE backtest_runs
    ALTER COLUMN win_rate        TYPE double precision USING win_rate::double precision,
    ALTER COLUMN profit_factor   TYPE double precision USING profit_factor::double precision,
    ALTER COLUMN optimizer_score TYPE double precision USING optimizer_score::double precision;
