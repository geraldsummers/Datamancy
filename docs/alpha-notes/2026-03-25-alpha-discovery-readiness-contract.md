# Alpha Discovery Readiness Contract

## Hypothesis

Alpha discovery can run continuously without degenerating into repeated infra triage if the engine treats data completeness, cache readiness, and universe-wide portfolio realism as hard preconditions instead of soft preferences.

## Experiment

- Audited the current cross-sectional research path, coverage gates, cache layer, and policy contract.
- Reduced the readiness problem to a small set of hard blockers and explicit non-blockers.
- Added a repeatable `latium.local` preflight script so research runs can be gated the same way every time.

## Result

- The platform already has the right top-level contract:
  - `research_features_1m` is the canonical research layer.
  - coverage is computed before cross-sectional runs.
  - insufficient coverage aborts the run.
  - raw fallback is disabled by default.
  - full-universe scan is enabled by default with `maxSymbols=0` and `discoveryMaxSymbols=0`.
- The real bottlenecks are narrower than they looked:
  - whole-universe raw capture stability
  - incremental feature materialization completeness
  - repeated Postgres work in the research inner loop
  - universe-wide portfolio construction and capacity control
- The following are hard blockers for serious alpha discovery:
  - data-health critical symbols on the active universe
  - eligible symbol count below the policy minimum
  - stale or missing `research_features_1m` coverage
  - cache load failures on the cross-sectional RAM layer
  - effective history depth shorter than the requested hypothesis horizon
- The following are not blockers right now:
  - notebook integration
  - valkey
  - multi-venue expansion
  - broad stack cleanup
  - blanket ORM migration of hot paths

## Remaining Risk

- The JVM cache is still a snapshot cache, not yet the full factor-ready RAM layer needed for high-throughput continuous search.
- Aggregated higher-horizon research windows still need to be persisted and warmed cheaply enough for repeated discovery runs.
- Universe-level portfolio/risk logic exists in policy and simulation, but still needs to become a more explicit first-class research object.

## Next Step

1. Keep using `latium.local` as the only execution surface.
2. Run the readiness preflight before meaningful search or promotion attempts.
3. Prioritize buildout in this order:
   - full-universe raw sync stability
   - feature materializer completeness and finalized coverage
   - persisted higher-horizon aggregates
   - larger JVM RAM research layer
   - autonomous search scheduling
   - stronger universe-level portfolio/risk simulation
4. Only stop and ask for help on real blockers:
   - exchange-side data cannot be kept continuous for the active universe
   - feature materialization cannot satisfy the declared coverage contract
   - host resource ceilings make the required RAM layer impossible
   - live service interfaces on `latium.local` are unavailable or inconsistent

## Standing Operator Notes

- Use `latium.local` only. Do not run the stack locally.
- Execute remote commands via `ssh gerald@latium.local "command"`.
- Use `~/datamancy` as the server stack path.
- Wait in `120s` intervals when polling long-running remote work.
- Wait in `300s` intervals only for the test runner.
