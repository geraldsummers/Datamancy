ALTER TABLE IF EXISTS strategy_universe_profiles
    ADD COLUMN IF NOT EXISTS candidate_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS strategy_universe_profiles
    ADD COLUMN IF NOT EXISTS selected_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS strategy_portfolio_profiles
    ADD COLUMN IF NOT EXISTS policy_max_concurrent_positions INTEGER NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS strategy_portfolio_profiles
    ADD COLUMN IF NOT EXISTS policy_max_concurrent_longs INTEGER NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS strategy_portfolio_profiles
    ADD COLUMN IF NOT EXISTS policy_max_concurrent_shorts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS strategy_portfolio_profiles
    ADD COLUMN IF NOT EXISTS policy_max_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS strategy_portfolio_profiles
    ADD COLUMN IF NOT EXISTS policy_max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE IF EXISTS strategy_portfolio_profiles
    ADD COLUMN IF NOT EXISTS policy_max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0;
