-- Nifty AutoTrader baseline schema
-- All timestamps stored with time zone; application always uses Asia/Kolkata

CREATE TABLE candles (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(30)    NOT NULL,
    timeframe   VARCHAR(5)     NOT NULL,
    open_time   TIMESTAMPTZ    NOT NULL,
    open        NUMERIC(12,2)  NOT NULL,
    high        NUMERIC(12,2)  NOT NULL,
    low         NUMERIC(12,2)  NOT NULL,
    close       NUMERIC(12,2)  NOT NULL,
    volume      BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_candle UNIQUE (symbol, timeframe, open_time)
);
CREATE INDEX idx_candle_symbol_tf_time ON candles (symbol, timeframe, open_time);

CREATE TABLE signals (
    id                      BIGSERIAL PRIMARY KEY,
    generated_at            TIMESTAMPTZ    NOT NULL,
    strategy_name           VARCHAR(50)    NOT NULL,
    symbol                  VARCHAR(30)    NOT NULL,
    direction               VARCHAR(10)    NOT NULL,
    suggested_entry         NUMERIC(12,2),
    suggested_stop_loss     NUMERIC(12,2),
    suggested_target        NUMERIC(12,2),
    lots                    INT            NOT NULL DEFAULT 1,
    reasoning               TEXT,
    vetoed_by_sentiment     BOOLEAN        NOT NULL DEFAULT FALSE,
    veto_reason             VARCHAR(500),
    risk_approved           BOOLEAN        NOT NULL DEFAULT FALSE,
    risk_rejection_reason   VARCHAR(500)
);
CREATE INDEX idx_signal_generated_at ON signals (generated_at DESC);

CREATE TABLE trades (
    id              BIGSERIAL PRIMARY KEY,
    strategy_name   VARCHAR(50)    NOT NULL,
    symbol          VARCHAR(30)    NOT NULL,
    option_type     VARCHAR(2)     NOT NULL,
    entry_price     NUMERIC(12,2)  NOT NULL,
    exit_price      NUMERIC(12,2),
    stop_loss_price NUMERIC(12,2)  NOT NULL,
    target_price    NUMERIC(12,2),
    lots            INT            NOT NULL,
    quantity        INT            NOT NULL,
    entry_time      TIMESTAMPTZ    NOT NULL,
    exit_time       TIMESTAMPTZ,
    realized_pnl    NUMERIC(12,2),
    total_costs     NUMERIC(12,2),
    paper           BOOLEAN        NOT NULL DEFAULT TRUE,
    exit_reason     TEXT,
    signal_id       BIGINT         REFERENCES signals(id)
);
CREATE INDEX idx_trade_entry_time ON trades (entry_time DESC);
CREATE INDEX idx_trade_open ON trades (exit_time) WHERE exit_time IS NULL;

CREATE TABLE orders (
    id               BIGSERIAL PRIMARY KEY,
    broker_order_id  VARCHAR(50),
    symbol           VARCHAR(30)    NOT NULL,
    side             VARCHAR(4)     NOT NULL,
    quantity         INT            NOT NULL,
    price            NUMERIC(12,2),
    status           VARCHAR(20)    NOT NULL,
    avg_fill_price   NUMERIC(12,2),
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ,
    paper            BOOLEAN        NOT NULL DEFAULT TRUE,
    reject_reason    VARCHAR(500),
    trade_id         BIGINT         REFERENCES trades(id)
);
CREATE INDEX idx_order_status ON orders (status);
CREATE INDEX idx_order_trade_id ON orders (trade_id);

CREATE TABLE order_state_transitions (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT         NOT NULL REFERENCES orders(id),
    from_status      VARCHAR(20)    NOT NULL,
    to_status        VARCHAR(20)    NOT NULL,
    transitioned_at  TIMESTAMPTZ    NOT NULL,
    reason           VARCHAR(500)
);
CREATE INDEX idx_ost_order_id ON order_state_transitions (order_id);

CREATE TABLE risk_events (
    id           BIGSERIAL PRIMARY KEY,
    occurred_at  TIMESTAMPTZ    NOT NULL,
    event_type   VARCHAR(40)    NOT NULL,
    description  TEXT           NOT NULL,
    symbol       VARCHAR(50)
);
CREATE INDEX idx_risk_event_occurred_at ON risk_events (occurred_at DESC);

CREATE TABLE sentiment_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    captured_at     TIMESTAMPTZ    NOT NULL,
    sentiment       VARCHAR(10)    NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL,
    key_risks       TEXT,
    reasoning       TEXT,
    raw_headlines   TEXT,
    fallback        BOOLEAN        NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_sentiment_captured_at ON sentiment_snapshots (captured_at DESC);

CREATE TABLE daily_pnl (
    id                       BIGSERIAL PRIMARY KEY,
    trading_date             DATE           NOT NULL UNIQUE,
    realized_pnl             NUMERIC(12,2)  NOT NULL DEFAULT 0,
    total_costs              NUMERIC(12,2)  NOT NULL DEFAULT 0,
    net_pnl                  NUMERIC(12,2)  NOT NULL DEFAULT 0,
    total_trades             INT            NOT NULL DEFAULT 0,
    winning_trades           INT            NOT NULL DEFAULT 0,
    losing_trades            INT            NOT NULL DEFAULT 0,
    daily_loss_limit_breached BOOLEAN       NOT NULL DEFAULT FALSE,
    auto_trading_halted      BOOLEAN        NOT NULL DEFAULT FALSE
);
