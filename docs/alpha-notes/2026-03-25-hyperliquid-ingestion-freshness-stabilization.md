# Hyperliquid Ingestion Freshness Stabilization

## Hypothesis

Universe-wide candle freshness was being degraded by three coupled issues:

- sparse-symbol false positives in the continuity watchdog
- targeted candle repair using non-trade channels as candle evidence
- initial recent-repair bursts hitting Hyperliquid backfill rate limits

## Experiment

- Changed candle continuity to require a genuinely missing trade-implied candle, not just any recent trade.
- Added a localized continuity threshold so a few stale symbols do not restart the full universe session.
- Aligned targeted repair eligibility with trade-derived candle relevance instead of orderbook/funding activity.
- Reduced targeted repair cadence to `30s`.
- Reduced initial recent-repair concurrency and added shared 429 cooldown propagation across candle backfill requests.
- Deployed the updated `market-data-ingestion` service to `latium.local`.

## Result

- Post-deploy, continuity-driven full-session restarts stopped in the observed windows.
- Initial recent repair completed with `permits=1` and no observed `429 Too Many Requests` errors.
- Follow-up targeted repairs stayed bounded (`18`, `12`, `14` streams in the observed post-bootstrap cycles).
- Live freshness check reached:
  - `0` symbols where `trade_latest_raw_time < 180s` but `candle_1m_latest_raw_time > 180s`

## Remaining Risk

- `postgres-datamancy-reconcile` can hold the compose dependency chain open longer than expected; this is a control-plane issue separate from the ingestion binary.
- Historical backfill still emits noisy `returned no data` warnings for symbols with little or no archival history.

## Next Step

1. Split or tone down the historical backfill no-data warning path so operator logs focus on live ingestion defects.
2. Fix the reconcile dependency behavior so `docker compose up -d market-data-ingestion` does not require a manual `--no-deps` bypass.
