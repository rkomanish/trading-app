-- Stores every backtest run (manual + optimizer) for history and comparison
CREATE TABLE backtest_runs (
    id                   BIGSERIAL PRIMARY KEY,
    strategy_name        VARCHAR(100)      NOT NULL,
    symbol               VARCHAR(20)       NOT NULL DEFAULT 'NIFTY',
    from_date            DATE,
    to_date              DATE,
    trading_days         INT               NOT NULL DEFAULT 0,
    total_trades         INT               NOT NULL DEFAULT 0,
    winning_trades       INT               NOT NULL DEFAULT 0,
    losing_trades        INT               NOT NULL DEFAULT 0,
    win_rate             DECIMAL(6,4)      NOT NULL DEFAULT 0,
    total_gross_pnl      DECIMAL(14,2)     NOT NULL DEFAULT 0,
    total_costs          DECIMAL(14,2)     NOT NULL DEFAULT 0,
    total_net_pnl        DECIMAL(14,2)     NOT NULL DEFAULT 0,
    avg_win              DECIMAL(14,2)     NOT NULL DEFAULT 0,
    avg_loss             DECIMAL(14,2)     NOT NULL DEFAULT 0,
    expectancy_per_trade DECIMAL(14,2)     NOT NULL DEFAULT 0,
    max_drawdown         DECIMAL(14,2)     NOT NULL DEFAULT 0,
    profit_factor        DECIMAL(10,4)     NOT NULL DEFAULT 0,
    promotable           BOOLEAN           NOT NULL DEFAULT FALSE,
    verdict              TEXT,
    -- JSON map of parameter values used (null = default strategy params)
    params_json          TEXT,
    -- true when this row was created by the optimizer, not a manual run
    is_optimized         BOOLEAN           NOT NULL DEFAULT FALSE,
    -- score used by optimizer: expectancy × winRate × profitFactor
    optimizer_score      DECIMAL(14,4),
    run_at               TIMESTAMPTZ       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bt_strategy   ON backtest_runs (strategy_name, run_at DESC);
CREATE INDEX idx_bt_promotable ON backtest_runs (promotable, total_net_pnl DESC);
CREATE INDEX idx_bt_optimized  ON backtest_runs (strategy_name, is_optimized, optimizer_score DESC);
