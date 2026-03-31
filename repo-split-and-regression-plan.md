# Three-Repo Refactor And Regression Plan

## Purpose

Split the current monorepo into three descriptive repos while preserving the working deployment model, keeping failure loud, and preventing silent coupling during the transition.

Final repo names:

- `stack-generator`
- `web-services`
- `trading`

The current `Datamancy` repo becomes a deprecated transition reference, then read-only, then disposable.

## Top-Level Decisions

1. `stack-generator` is upstream.
2. `web-services` and `trading` are downstream of `stack-generator`.
3. `web-services` and `trading` do not depend on each other at source-code level.
4. Site-specific config stays outside all repos on disk.
5. Phase 1 preserves current template semantics, deploy artifact shape, script/image naming, and local build -> remote deploy behavior.
6. Trading auth is wallet-native and is the identity source of truth for the trading stack.
7. Web-services auth remains LDAP-native and is the identity source of truth for the generic platform.
8. Wallet-to-LDAP linking is an optional federation feature, not a trading prerequisite.
9. Grafana stays in `web-services`.
10. Trading ships its own edge/Caddy so it can run standalone.

## Frozen Phase-1 Compatibility Contract

This is the stable contract that refactoring must not break in the first pass.

### Template Semantics

- Config templates keep `{{VAR}}`.
- Compose env interpolation keeps `${VAR}`.
- Schema-driven transforms keep current behavior.

### Artifact Shape

The generated output remains:

- `dist/docker-compose.yml`
- `dist/configs/`
- `dist/stack.containers/`
- `dist/stack.kotlin/`
- `dist/scripts/`
- `dist/.env`

### Runtime Model

- Build locally.
- Deploy artifacts remotely.
- Server runtime does not need to know which repo produced the artifact.

### Naming Stability

- Keep `:local-build` image tags in phase 1.
- Keep deploy script entrypoints stable where operations currently depend on them.

## Repo Ownership

### `stack-generator`

Owns:

- build/generate engine
- template contract
- compose merge logic
- config rendering
- validation helpers
- federation contract docs
- test runner generation logic

Does not own:

- app business logic
- trading logic
- platform services
- live site config

### `web-services`

Owns:

- public Caddy
- Authelia
- LDAP
- Grafana
- JupyterHub
- Qdrant
- search/model-context/knowledge stack
- generic web apps
- LDAP <-> wallet linking UI/service
- generic platform docs

Does not own:

- market-data ingest
- alpha engine
- trading auth source of truth
- tx-gateway
- trading execution/risk

### `trading`

Owns:

- market-data ingest
- market Postgres
- trading edge/Caddy
- wallet auth
- tx-gateway
- alpha services
- execution/risk
- trading docs and research docs
- trading deploy scripts

Does not own:

- generic platform apps
- LDAP as primary auth
- Grafana service
- generic public edge ownership in federated mode

## Runtime Federation Model

Cross-stack compatibility must be a small runtime contract, not source-sharing.

### Supported Modes

- `standalone`
- `federated`

### Shared External Networks

- `edge-shared`
- `identity-shared`
- `observability-shared`

### Integration Export Contract

Each repo may export integration assets under:

- `dist/integrations/edge/`
- `dist/integrations/identity/`
- `dist/integrations/grafana/`

### Stable Ownership In Federated Mode

- `web-services` owns public `:80/:443`
- `trading` owns trading route behavior and wallet auth behavior
- `web-services` owns generic Grafana
- `web-services` owns LDAP/Authelia
- `trading` exports dashboards, datasource snippets, and metrics/db contracts

### Stable Host Roles

- `auth.<domain>` from `web-services`
- `grafana.<domain>` from `web-services`
- `trade.<domain>` from `trading`

## Edge And Auth Design

### Trading Edge

Trading must be self-sufficient, including wallet-authenticated UI flows.

- `trading` ships `trading-edge`
- in `standalone` mode, `trading-edge` binds public ports
- in `federated` mode, `trading-edge` stays internal and `web-services` public Caddy proxies `trade.<domain>` to it

This preserves:

- standalone trading deployment
- wallet-auth UI support
- route ownership in the trading repo
- shared public ingress in federated mode

### Trading Identity

Trading does not use LDAP as its primary auth system.

Trading identity model:

- wallet challenge/signature login
- `wallet_principal` abstraction
- EVM-first implementation
- local principal/session store in Postgres
- trading-issued session/JWT trusted by trading services

Required trading auth tables:

- `wallet_principal`
- `wallet_nonce`
- `wallet_session`
- `wallet_role_grant`
- `wallet_risk_profile`
- `wallet_linked_identity`

### Web-Services Identity

Web-services remains:

- LDAP-native for human/platform identity
- Authelia-backed for generic platform auth

### LDAP <-> Wallet Linking

Implemented as optional federation:

1. user proves active LDAP-authenticated session
2. user proves fresh wallet signature
3. system stores a verified, revocable mapping

Trading remains fully usable without the link.

## Site Config Separation

Site-specific config stays outside all repos.

Recommended layout:

```text
~/.config/stack/sites/<site>/common.yaml
~/.config/stack/sites/<site>/web-services.yaml
~/.config/stack/sites/<site>/trading.yaml
~/.config/stack/credentials/<site>/web-services.env
~/.config/stack/credentials/<site>/trading.env
```

Pure repos contain only:

- source
- compose fragments
- config templates
- containers
- tests
- docs
- examples/defaults

No live secrets or live mount paths in repo.

## Generator Consumption Model

Phase-1 default: vendor `stack-generator` into each downstream repo at a pinned SHA.

Recommended layout in downstream repos:

- `tools/stack-generator/`
- `tools/stack-generator/UPSTREAM.yml`

Why this is the right first move:

- deterministic
- easy to patch during migration
- no package publishing overhead
- no submodule pain
- no containerized build awkwardness

Do not dockerize the generator first. Add a container wrapper later only if CI/reproducibility needs it.

## Required Generator Refactor

The current build script is a good starting point, but it must be split into explicit commands/modules.

### Target Commands

1. `generate`
   - read repo layout
   - read site config
   - render templates
   - merge compose structurally
   - emit `dist/`

2. `validate`
   - config/schema checks
   - required variable checks
   - compose validation
   - federation contract validation

3. `package`
   - build jars/images/artifacts

4. `test`
   - run repo-declared suites
   - no repo-specific hardcoding in generator internals

5. `bundle-source`
   - optional
   - not part of the minimal shared contract

### Repo-Specific Assumptions To Move Out Of Generator Code

- hardcoded Python service paths
- hardcoded TypeScript test directories
- hardcoded notebook image behavior
- repo-specific source ownership lists
- repo-specific bundling assumptions

These move into downstream repo manifests/config.

## Target Repo Layouts

### `trading`

```text
AGENTS.md
docs/
global.settings/
stack.compose/
stack.config/
stack.containers/
stack.kotlin/
tests.compose/
tests.config/
tests.containers/
tests.kotlin/
scripts/
tools/stack-generator/
build-trading.main.kts
```

### `web-services`

```text
AGENTS.md
docs/
global.settings/
stack.compose/
stack.config/
stack.containers/
stack.kotlin/
tests.compose/
tests.config/
tests.containers/
tests.kotlin/
scripts/
tools/stack-generator/
build-web-services.main.kts
```

### `stack-generator`

```text
AGENTS.md
docs/
contracts/
cli/
lib/
examples/
tests/
```

## Documentation Migration

The root prompts and research reports must be trimmed, verified, and redistributed by purpose.

### Into `trading`

- `AGENTS.md`
  - concise operator rules
  - repo boundaries
  - alpha-specific do/don't rules
  - build/deploy reality
  - no experiment diary

- `docs/operator-control.md`
  - maintained control prompt equivalent
  - champion/challenger/dead-surface state
  - trimmed, current, accuracy-checked

- `docs/research/`
  - trend surface
  - validation
  - execution/risk separation
  - promotion/multiplicity

### Into `web-services`

- `AGENTS.md`
  - generic platform ownership
  - federation boundaries with trading

### Into `stack-generator`

- `AGENTS.md`
  - template contract
  - layout contract
  - site-config rules
  - downstream consumption rules

No stale findings or displaced control-branch notes should be copied forward blindly.

## Migration Phases

### Phase 0: Freeze Contracts And Inventory

1. Write contract docs:
   - template semantics
   - deploy artifact contract
   - federation contract
2. Inventory current paths into:
   - generator-owned
   - web-services-owned
   - trading-owned
   - deprecated/throwaway
3. Record the minimum deploy contract currently required by the live server.

Acceptance:

- no code moved yet
- ownership map exists
- contract docs exist

### Phase 1: Extract `stack-generator`

1. Create `stack-generator` from current build/generate logic.
2. Preserve current semantics first.
3. Do not redesign template syntax.
4. Do not redesign `dist/`.
5. Refactor internals behind equivalence checks.
6. Replace line-based compose concatenation with structured YAML merge while reproducing current behavior.
7. Move repo-specific build/test selections into manifests.

Acceptance:

- generator reproduces valid `dist/`
- same template semantics
- same compose validation outcome
- same artifact contract

### Phase 2: Create `trading`

1. Move all trading-owned services/fragments:
   - trading Kotlin services
   - market-data services
   - market Postgres
   - tx-gateway
   - trading containers
   - trading scripts
   - trading docs
2. Vendor `stack-generator`.
3. Add `trading-edge`.
4. Implement `standalone` and `federated` edge modes.
5. Add wallet-native auth scaffolding.
6. Remove platform services from the repo.
7. Rename prototype-specific names to descriptive trading names.

Acceptance:

- trading builds its own deployable `dist/`
- no source dependency on `web-services`
- trading can boot standalone
- hidden platform coupling breaks loudly

### Phase 3: Create `web-services`

1. Move platform-owned services/fragments:
   - public Caddy
   - Authelia
   - LDAP
   - Grafana
   - JupyterHub
   - Qdrant
   - search/model-context/knowledge stack
   - generic web apps
2. Vendor `stack-generator`.
3. Add federation import support for trading edge/Grafana/identity integration assets.
4. Keep trading logic out of this repo.

Acceptance:

- web-services builds its own deployable `dist/`
- no source dependency on `trading`
- platform boots standalone

### Phase 4: Federation Contracts

1. Define shared external networks.
2. Define trading integration exports:
   - edge upstream contract
   - Grafana dashboards/datasource snippets
   - identity-link API contract
3. Define web-services imports.
4. Implement LDAP <-> wallet linking.
5. Expose trading read-only observability contract.

Acceptance:

- both repos can run standalone
- both repos can run federated
- no source-sharing required

### Phase 5: Artifact-Only Deployment Roots

1. Server stops caring about repo checkouts.
2. Deploy only generated artifacts.
3. Create runtime roots for each product stack.
4. Keep current monolith runtime untouched until parallel validation is complete.

Recommended runtime layout:

```text
~/stacks/web-services/current
~/stacks/trading/current
~/releases/web-services/<release-id>/
~/releases/trading/<release-id>/
```

Acceptance:

- deploy scripts sync `dist/` only
- server runtime is repo-agnostic
- rollback is artifact-based

### Phase 6: Parallel Validation

1. Validate web-services standalone.
2. Validate trading standalone.
3. Validate federated mode.
4. Validate absence of accidental source/runtime dependency.

Acceptance:

- standalone web-services works
- standalone trading works
- federated co-deploy works
- server runtime does not know repo identity

### Phase 7: Deprecate Old Monorepo

1. Mark old repo deprecated.
2. Stop feature work there.
3. Leave compatibility wrappers only if briefly required.
4. Make it read-only.
5. Dispose of it once new repos are authoritative.

## Regression Test Strategy

The new repos must preserve the strongest properties of the current test system:

- explicit suite names
- network-scoped test runners
- domain-specific integration suites
- separate browser coverage
- staged trading execution tests
- generated test-runner compose from suite metadata

The existing system already demonstrates the right pattern:

- `foundation`
- `data-pipeline`
- `monitoring`
- `trading`
- `trading-staged`
- `playwright-e2e`

Those patterns should survive the split.

## Testing Principles

1. Do not replace one big monorepo `all` test with one new giant mega-suite.
2. Preserve domain suites plus narrow smoke gates.
3. Browser tests remain separate from normal Kotlin integration suites.
4. Staged trading execution tests remain separate from generic trading health tests.
5. Standalone and federated modes must both be first-class test environments.
6. Every migration phase has a minimum gating suite before any cutover.
7. The generator itself gets contract tests and output-equivalence tests.

## Test Environments

Four test environments are required.

### 1. Repo-Local Unit Environment

Used for:

- Kotlin unit tests
- generator contract tests
- parser/render/merge tests
- auth flow unit tests

### 2. Repo-Local Compose Integration Environment

Used for:

- standalone `web-services`
- standalone `trading`
- generated test-runner suites

### 3. Federated Compose Integration Environment

Used for:

- co-deployed `web-services` + `trading`
- shared edge/identity/observability tests

### 4. Live Remote Validation Environment

Used for:

- targeted `trading` deployment to `latium`
- readiness validation
- discovery defaults checks
- staged or smoke alpha validation

Trading continues to use live remote validation for the parts that must match the real stack.

## Suite Taxonomy For `stack-generator`

`stack-generator` must have its own suite family.

### `generator-contract`

Checks:

- current `{{VAR}}` semantics preserved
- current `${VAR}` compose interpolation preserved
- required env detection preserved

### `generator-compose-merge`

Checks:

- structured YAML merge reproduces expected compose output
- extension sections (`x-*`) preserved
- top-level services/volumes/networks merged correctly

### `generator-artifact`

Checks:

- `dist/` shape exactly as contracted
- required files emitted
- executable bits preserved where required

### `generator-site-config`

Checks:

- external site config loading
- credentials store handling
- no live-secret leakage into repo

### `generator-federation-contract`

Checks:

- expected `dist/integrations/*` exports
- shared network declarations
- import/export schema compatibility

### `generator-compat-smoke`

Checks:

- old-repo-equivalent sample input produces compose/config output that passes validation

## Suite Taxonomy For `trading`

### `trading-foundation`

Checks:

- trading edge health
- tx-gateway health
- market Postgres reachability
- core service health endpoints

### `trading-wallet-auth`

Checks:

- nonce issue/consume flow
- signature verification
- session/JWT issuance
- replay protection
- wallet principal provisioning

### `trading-market-data`

Checks:

- ingest health
- persist health
- minute/execution context materialization
- daily panel materialization
- no hidden fallback dependency in normal path

### `trading-alpha-readiness`

Checks:

- readiness script equivalent for the 1d/72h lead architecture
- defaults endpoint
- data coverage gating
- fail loud if canonical panel is sparse

### `trading-alpha-offline`

Checks:

- offline planner/search/run behavior
- parameter defaults
- purged validation engine
- multiplicity gate plumbing

### `trading-staged`

Preserve the spirit of current `trading-staged`.

Checks:

- staged execution fixtures
- paper full fill
- paper partial fill
- degraded worker handling
- retry/ack behavior where applicable

### `trading-web3-wallet-ui`

Browser suite.

Checks:

- wallet login UI
- domain/origin correctness
- session establishment
- protected route behavior

### `trading-observability-export`

Checks:

- dashboard JSON export exists
- datasource snippets exist
- metrics endpoints reachable
- Grafana read-only DB contract valid

### `trading-standalone`

Compose suite for standalone trading stack.

Checks:

- trading edge public mode
- wallet auth
- tx-gateway
- market-data path
- alpha readiness

### `trading-federation-export`

Checks:

- internal edge mode
- identity integration export
- observability integration export

## Suite Taxonomy For `web-services`

### `web-foundation`

Checks:

- public Caddy health
- Authelia health
- LDAP health
- Grafana health

### `web-authentication`

Checks:

- LDAP auth
- Authelia flows
- expected forward-auth behavior

### `web-observability`

Checks:

- Grafana provisioning
- datasource health
- dashboard loading

### `web-platform-services`

Checks:

- JupyterHub
- Qdrant
- search/model-context
- generic app UIs

### `web-wallet-link`

Checks:

- LDAP-authenticated user can link a wallet
- wallet proof required
- link revocation works
- audit record written

### `web-playwright`

Browser suite for:

- public auth flows
- Grafana login
- JupyterHub login
- generic UI smoke paths

### `web-standalone`

Compose suite for standalone web-services stack.

## Federated Integration Suites

These are the most important non-regression suites for the split itself.

### `federated-edge`

Checks:

- `web-services` public edge routes `trade.<domain>` correctly to internal `trading-edge`
- headers, cookies, websocket support, and origin-sensitive flows remain intact

### `federated-identity`

Checks:

- trading standalone wallet auth still works
- optional LDAP <-> wallet link works when both stacks are present
- no hidden LDAP dependency in trading login

### `federated-grafana`

Checks:

- Grafana in `web-services` sees trading dashboards
- datasource provisioning from trading import succeeds
- trading read-only DB/metrics contract works

### `federated-observability`

Checks:

- shared observability network
- Prometheus/Grafana reachability to trading services

### `federated-ui`

Browser suite.

Checks:

- user reaches trading UI through public edge
- wallet auth works through federated ingress
- Grafana and generic UIs remain unaffected

## Reusing The Current Test-Runner Pattern

The current repo already demonstrates several useful ideas that should be preserved.

### Preserve

1. `tests.config/suites.yml`
   - declarative suite catalog

2. `tests.config/components.yml`
   - explicit component/source mapping

3. generated `tests.compose/test-runners.yml`
   - one test-runner service per suite
   - suite-specific network attachment

4. separate `playwright-e2e`
   - browser coverage isolated from normal Kotlin suite runs

5. staged trading suite
   - keep fixture-backed execution realism tests separate from generic service smoke tests

### Adapt

Each new repo gets its own:

- `tests.config/suites.yml`
- `tests.config/components.yml`
- `tests.compose/test-runners.yml`

And the federated environment gets a separate integration test repo or test workspace generated from both products' exported contracts.

Phase-1 simplest version:

- keep a federated test workspace in `web-services` or a temporary transition workspace
- long-term, optionally create a tiny dedicated `integration-tests` workspace if federation complexity grows

## Phase Gates

Every migration phase has explicit gating suites.

### Phase 0 Gate

- current repo baseline captured
- existing important suites pass:
  - `foundation`
  - `monitoring`
  - `trading`
  - `trading-staged`
  - `playwright-e2e` where relevant

### Phase 1 Gate: `stack-generator`

- `generator-contract`
- `generator-compose-merge`
- `generator-artifact`
- `generator-compat-smoke`

### Phase 2 Gate: `trading`

- `trading-foundation`
- `trading-wallet-auth`
- `trading-market-data`
- `trading-alpha-readiness`
- `trading-staged`
- `trading-standalone`

### Phase 3 Gate: `web-services`

- `web-foundation`
- `web-authentication`
- `web-observability`
- `web-platform-services`
- `web-standalone`

### Phase 4 Gate: Federation

- `federated-edge`
- `federated-identity`
- `federated-grafana`
- `federated-ui`

### Phase 5 Gate: Live Remote Trading Validation

- deploy artifact-only trading stack to target host
- run trading readiness
- verify defaults
- run targeted smoke checks without broad search

## Live Trading Validation Requirements

For trading, remote validation remains mandatory for the parts that depend on the real target environment.

Minimum remote validation gates:

1. readiness passes
2. market-data ingest path healthy
3. canonical daily signal panel path healthy
4. wallet-auth UI reachable on real host/origin
5. no fallback-to-public-candles in normal discovery path
6. target control-branch smoke run can execute or fail loudly for a real reason

## No-Regression Checklist

The split is not complete until all of these stay true.

### Generator

- template semantics unchanged
- artifact layout unchanged
- compose validation still passes

### Web-services

- public auth still works
- Grafana still works
- JupyterHub still works
- generic services do not need trading present

### Trading

- wallet auth is self-sufficient
- market-data path is self-sufficient
- alpha services do not need web-services present
- trading UI works standalone
- readiness centered around the 1d/72h architecture works

### Federation

- public edge can route to trading
- Grafana can see trading
- LDAP <-> wallet linking works
- no hidden source-code dependency exists

### Deployment

- server runtime only sees artifacts
- server path does not depend on repo identity
- rollback remains artifact-based

## Success Criteria

The refactor and regression program is successful when:

1. `stack-generator` is authoritative for build/generate contracts.
2. `trading` builds and deploys independently.
3. `web-services` builds and deploys independently.
4. Both repos consume pinned `stack-generator`.
5. Both repos run standalone.
6. Both repos run federated.
7. Trading wallet auth is the source of truth for trading identity.
8. LDAP remains the source of truth for platform identity.
9. Grafana remains in `web-services` and cleanly observes trading.
10. The old monorepo is no longer authoritative.

## Immediate Next Steps

1. Freeze the phase-1 compatibility contract in docs.
2. Extract `stack-generator` with no semantic changes.
3. Define repo ownership map from current paths.
4. Create `trading` first.
5. Create `web-services` second.
6. Recreate the current suite model in each repo.
7. Add federated integration suites before any production cutover.
