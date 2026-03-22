# Trading Research Scripts

## `alpha_proof.py`

Runs a walk-forward alpha proof against Datamancy market data and persists results into:

- `strategy_backtest_runs`
- `strategy_walkforward_runs`
- `strategy_sensitivity_sweeps`

Current behavior:

- canonicalizes Hyperliquid history across `hyperliquid` and `hyperliquid_mainnet`
- uses `candle_1m` bars plus signed trade flow and order-book depth
- consumes persisted funding rows when present and safely falls back to zero carry when they are absent
- converts top-of-book depth from base units into quote notional before impact modelling
- applies explicit fee/slippage/impact/latency stress scenarios
- supports `--family microstructure_v1` and the lower-turnover `--family tail_short_v2`

Remote run example on `latium.local`:

```bash
ssh gerald@latium.local 'cd ~/datamancy && export POSTGRES_PASSWORD=$(docker compose exec -T postgres env </dev/null | sed -n "s/^POSTGRES_PIPELINE_PASSWORD=//p") && docker run --rm --network datamancy_litellm -v "$PWD":/workspace -w /workspace -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=datamancy -e POSTGRES_USER=pipeline_user -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" datamancy-jupyter-notebook:5.4.3 python scripts/trading/alpha_proof.py --exchange hyperliquid_mainnet --symbols BTC,ETH,SOL --lookback "5 days"'
```

Tail-short proof example on `latium.local`:

```bash
ssh gerald@latium.local 'cd ~/datamancy && export POSTGRES_PASSWORD=$(docker compose exec -T postgres env </dev/null | sed -n "s/^POSTGRES_PIPELINE_PASSWORD=//p") && docker run --rm --network datamancy_litellm -v "$PWD":/workspace -w /workspace -e PYTHONPATH=/workspace -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=datamancy -e POSTGRES_USER=pipeline_user -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" datamancy-jupyter-notebook:5.4.3 python scripts/trading/alpha_proof.py --family tail_short_v2 --strategy-prefix alpha_proof_tail_short_v2 --exchange hyperliquid_mainnet --symbols SOL --lookback "10 days" --train-hours 16 --test-hours 4 --step-hours 4 --use-trade-flow'
```
