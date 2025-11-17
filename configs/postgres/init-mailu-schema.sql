-- Mailu database schema
-- Creates Last30dTopEndUsersSpend table if not exists

CREATE TABLE IF NOT EXISTS "Last30dTopEndUsersSpend" (
    id SERIAL PRIMARY KEY,
    user_email VARCHAR(255) NOT NULL,
    total_spend DECIMAL(10, 2) DEFAULT 0,
    period_start TIMESTAMP DEFAULT NOW(),
    period_end TIMESTAMP DEFAULT NOW(),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_email ON "Last30dTopEndUsersSpend" (user_email);
CREATE INDEX IF NOT EXISTS idx_period ON "Last30dTopEndUsersSpend" (period_start, period_end);
