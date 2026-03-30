# Expansion Research for a Deployable Multi-Day Cross-Sectional Trend Engine on Hyperliquid

## Executive summary

**Facts.** Your requested expansions sit squarely in the part of quant research that most often determines whether a “backtest-clearing” configuration survives live trading: (i) **selection and promotion discipline** (thresholds, calibration, multiple-testing control), (ii) **risk-model and residualisation stability** (beta estimation and smoothing choices), and (iii) the **portfolio geometry** of extreme-tail sorts (breadth vs turnover vs estimation error). These are also the points where the financial literature most strongly warns about data reuse and overfitting: selection bias and backtest overfitting can turn apparently high Sharpe into systematically disappointing out-of-sample performance, motivating tests like the Deflated Sharpe Ratio and Reality Check–type procedures. citeturn18view1turn18view2turn16view1

**Facts.** On Hyperliquid, the mechanics you must bake into *any* notion of “net edge bps” include: (a) **tiered maker/taker fees** based on rolling 14‑day volume and assessed daily in UTC, with maker rebates paid continuously; (b) **funding paid hourly** (computed from an 8‑hour rate) with a specific clamp structure and premium sampling; and (c) the fact that L2 books are **block-synchronised snapshots** in the canonical feeds (and deeper depth/L4 diffs depend on node outputs or specialised infrastructure). citeturn19view1turn19view0turn10search0turn9search0turn9search1

**Takes.** If you do only two expansions now, your own prioritisation is correct: **threshold calibration** and **residualisation-window research**. They directly control the live “18d/19d/21d pocket” decision *because* they determine (i) how much of that pocket is selection artefact versus stable plateau, and (ii) whether residualisation is actually removing unwanted common exposure or injecting noise and turnover. The other items (narrow tails, funding overlay, regime-sliced drift, stops) should be pursued, but they should be treated as downstream improvements that can’t rescue a dead core.

**What would change the decision.** If, after implementing the procedures below, your chosen pocket (a) loses its edge under walk-forward promotion thresholds that are calibrated on *prior* windows only, or (b) shows strong sensitivity to small sampling/residualisation changes (knife-edge), the rational move is to widen the search to adjacent windows and/or tighten promotion logic rather than “tweak” the pocket. That’s exactly the failure mode the data-snooping literature is concerned with. citeturn18view2turn18view1turn16view1

## Data and microstructure prerequisites for these expansions

**Facts.** Hyperliquid’s published fee schedule is tiered by rolling 14‑day volume, assessed end-of-day UTC; it has separate schedules for perps vs spot, counts spot volume double toward tier, and pays maker rebates continuously. It also includes special cases (eg aligned quote assets, and HIP‑3 growth-mode reductions). citeturn19view1

**Facts.** Hyperliquid funding is explicitly documented as: peer-to-peer (not a venue fee), **paid every hour** at one-eighth of a computed 8‑hour funding rate, with an interest-rate component and a premium component sampled every 5 seconds and averaged over the hour, and a clamp term `clamp(interest − P, −0.0005, 0.0005)` in the formula. Funding payments are computed using position size × oracle price × funding rate, and the design references impact prices and oracle prices. citeturn19view0

**Facts.** Hyperliquid’s historical archives include (i) `hyperliquid-archive` with market data (including L2 snapshots) uploaded approximately monthly with missingness caveats, and (ii) node-derived datasets like `s3://hl-mainnet-node-data/node_fills_by_block` generated via `--write-fills --batch-by-block`. citeturn9search0

**Facts.** The open-source `order_book_server` adds an optional `n_levels` for L2 (default 20, up to 100) and provides an `l4book` that sends a full snapshot and then forwards order diffs by block. This is not “core-team maintained”, and the repo’s own issue tracker shows operational failure modes you must treat as data-quality risks. citeturn9search1turn9search17

**Takes.** For the *specific expansions* you requested, the most important data “upgrades” are not new alpha features. They are: (i) **unified time alignment** (exchange timestamps vs local receipt timestamps) so you can measure promotion stability and forward drift without hidden time leakage; (ii) **cost fields in the ledger** so “net edge bps” is measured at the same abstraction layer as live PnL; and (iii) **depth-dependent slippage estimators** so thresholds and tail widths are evaluated in the after-cost space. Vendor datasets (Tardis/Dwellir) can be useful for completeness and replaying with local timestamps, but their incentives are to sell coverage—so you should treat them as *inputs to verify* against node output and the venue docs rather than as ground truth. citeturn9search0turn19view1turn19view0turn9search2

**What would change the decision.** If your “net edge bps” differs materially depending on whether you compute it from (a) execution-ledger fills with signed fees/funding versus (b) mid-price marks plus assumed costs, you likely have a modelling rather than an alpha problem; you should halt promotion-threshold tuning until that gap is understood. citeturn19view1turn19view0

## Threshold calibration methodology for minNetEdgeBps

**Facts.** Separating “search” from “evaluation/promotion” is a standard remedy for selection bias: tuning hyperparameters/thresholds on finite samples overfits the *model-selection criterion* itself, which can severely bias performance estimates. This is well-studied in machine learning evaluation and applies directly to quant strategy sweeps. citeturn16view0turn3search8

**Facts.** In finance, data reuse is an endemic problem because only one historical path exists; White’s Reality Check formalises the problem of declaring the best strategy in a search as “superior” when it may be luck, and proposes a bootstrap test against a benchmark. citeturn18view2

**Facts.** Selection-bias-aware performance metrics such as the Deflated Sharpe Ratio explicitly correct for (at least) non-normal returns and the inflation created by many trials, and the Probability of Backtest Overfitting framework directly targets the risk that in-sample winners underperform out-of-sample. citeturn18view1turn16view1

### A concrete, leakage-resistant procedure

**Facts.** The core idea is to treat `minNetEdgeBps` not as a hand-chosen constant but as a *calibrated decision threshold* derived from the mapping between “estimated edge” and “realised forward outcomes” across **prior** walk-forward windows only (a strict chronology). This enforces the same anti-leakage principle as nested cross-validation: you must not let the evaluation window influence its own promotion rule. citeturn16view0turn3search8

**Define the objects (so you can implement exactly).**

Let walk-forward windows be indexed by \(k = 1,2,\dots,K\) (chronological). For each \(k\), define:

- training segment \( \mathcal{T}_k \), test segment \( \mathcal{V}_k \) (validation), and optionally a forward “shadow-live” segment \( \mathcal{F}_k \) if you have it.
- for each candidate configuration \(c\) (signal family, residualisation window, tail width, weighting, rebalance cadence), compute an *estimated* net edge on training:
\[
\widehat{E}_{k}(c) \;\;=\;\; \text{estimated net edge in bps per rebalance (or per day) from }\mathcal{T}_k,
\]
and a *realised* net outcome on test:
\[
R_{k}(c) \;\;=\;\; \text{realised net return metric on }\mathcal{V}_k \text{ (bps/day, Sharpe, etc)}.
\]
“Net” must include fee tiers and funding assumptions consistent with venue mechanics. citeturn19view1turn19view0

**Step one: establish two thresholds (search vs promotion).**

- **Search threshold** \(\theta^{search}\): a *low* filter that prevents wasting compute on obviously dead configs but remains permissive enough to preserve model diversity.
- **Promotion threshold** \(\theta^{promo}\): a *high* filter for live eligibility, defined to control false discoveries and forward-drift risk.

This split is the strategy analogue of “model selection vs final evaluation”; collapsing them is precisely how you overfit your selection rule. citeturn16view0turn18view2

**Step two: calibrate `minNetEdgeBps` from prior windows only.**

For each window \(k\ge 2\), calibrate a threshold using only \(\{1,\dots,k-1\}\). A robust choice is to estimate the conditional probability that a config with estimated edge above \(\theta\) produces positive forward outcome:

\[
\widehat{p}_{k-1}(\theta) 
= 
\Pr\!\left(R_{j}(c) > 0 \mid \widehat{E}_{j}(c)\ge \theta,\; j\le k-1\right).
\]

Then define the smallest threshold that meets your deployment risk target:
\[
\theta^{promo}_{k}
=
\inf\left\{\theta:\widehat{p}_{k-1}(\theta)\ge p_{0}\;\;\text{and}\;\; \mathrm{Median}\big(R_{j}(c)\mid \widehat{E}_{j}(c)\ge \theta\big)\ge r_{0}\right\}.
\]
Typical choices: \(p_0\in[0.60,0.75]\) and \(r_0>0\) in “bps/day net” space, but you should set them from your drawdown tolerance and opportunity cost.

**Step three: require plateau behaviour, not single winners.**

Instead of selecting a unique \(\theta\) that maximises a metric, select a *plateau interval* \([\theta_L,\theta_U]\) such that:

1) the forward metric stays above constraints for all \(\theta\in[\theta_L,\theta_U]\), and  
2) the metric gradient is small (diminishing returns), eg:
\[
\left|\frac{d}{d\theta}\mathrm{Median}(R \mid \widehat{E}\ge\theta)\right| \le \varepsilon.
\]

This is directly motivated by the “overfitting in model selection” result: the more you optimise a noisy criterion, the more you select noise. Plateaus are a practical stability heuristic to reduce this. citeturn16view0turn18view2

![Threshold plateau schematic](sandbox:/mnt/data/threshold_plateau_schematic.png)

**Step four: add a multiplicity-aware promotion gate.**

Even with a threshold, you are still searching over many configs. Use at least one of:

- **Deflated Sharpe Ratio gate**: require DSR exceed a chosen confidence (or treat DSR-statistic as the metric you plateau over). citeturn18view1  
- **Reality Check–style test** against a simple benchmark config, to verify that your search has not simply found a lucky rule. citeturn18view2  
- **PBO (Probability of Backtest Overfitting)** via the CSCV procedure to quantify how often in-sample winners lose out-of-sample; require PBO below a ceiling. citeturn16view1  

Use classical multiple-testing corrections (Bonferroni/Holm/BH) for supporting p-values when you can define hypotheses cleanly; Holm and BH are standard references. citeturn7search0turn7search1turn3search0

### Practical definition of minNetEdgeBps for your stack

**Facts.** Because Hyperliquid fees depend on tier and maker/taker mix, and funding is a potentially large PnL component on multi-day holds, “edge” must be expressed in a unit compatible with those cashflows. citeturn19view1turn19view0

A defensible definition is:

\[
\widehat{E}(c) = \widehat{\mu}^{gross}(c) - \widehat{C}^{fees}(c) - \widehat{C}^{spread+slip}(c) - \widehat{C}^{impact}(c) + \widehat{P}^{funding}(c),
\]
reported in **bps/day** or **bps/rebalance**. Here:

- \(\widehat{C}^{fees}\) uses tiered maker/taker schedule mechanics. citeturn19view1  
- \(\widehat{P}^{funding}\) uses the hourly accrual and formula/inputs documented by Hyperliquid (or uses realised funding history if available). citeturn19view0turn15search5  
- \(\widehat{C}^{spread+slip}\) and \(\widehat{C}^{impact}\) are depth-based (L2/L4) or empirically fitted.

**Takes.** If you want plateau selection to be meaningful, \(\widehat{E}\) must be *monotone-ish* with realised net outcomes. If it isn’t, you do not have an “edge threshold” problem—you have a **miscalibrated cost/impact model** problem (or severe regime nonstationarity).

**What would change the decision.** If the “plateau” disappears when you recompute \(\widehat{E}\) using ledger-exact fees/funding (instead of assumed costs), then the threshold you were tuning was not a deployable control knob. The correct move is to fix the net-edge estimator, then re-run calibration. citeturn19view1turn19view0turn18view1

## Residualisation window research around 18d–21d

**Facts.** Crypto cross-sectional trend predictors are empirically real in large datasets, and more sophisticated “trend aggregation” over multiple horizons can be robust to transaction costs in published research. citeturn12view0  However, exposures to common components (“market”, “beta-like” co-movement) are also time-varying, and factor-model work in crypto explicitly discusses time variation and the need for rolling estimation. citeturn17view1turn2search2

**Facts.** A concrete, relevant datapoint: one influential crypto factor-model working paper that uses **daily observations** reports **30‑day rolling-window CAPM alpha and beta estimates** among its constructed characteristics. citeturn17view1  That puts your 18–21 day battleground in the “short-to-medium” band relative to at least some crypto daily research norms.

**Facts.** Beta estimation itself is not “free”: different weighting schemes and shrinkage can materially change future beta prediction and the ability to construct truly market-neutral portfolios. citeturn17view0

### A disciplined research design for residualisation windows

Let daily (or 1h→daily aggregated) returns be \(r_{i,t}\) and your market proxy be \(r_{m,t}\) (eg equal-weight universe return, or a robust index).

Estimate beta using a window \(W_\beta\) and optional exponential weighting:

\[
\hat{\beta}_{i,t}(W_\beta)
=
\frac{\sum_{u=1}^{W_\beta} w_u (r_{m,t-u}-\bar r_m)(r_{i,t-u}-\bar r_i)}
{\sum_{u=1}^{W_\beta} w_u (r_{m,t-u}-\bar r_m)^2},
\quad w_u \propto \lambda^{u}.
\]

Then residualise:

\[
\tilde r_{i,t} = r_{i,t} - \hat{\beta}_{i,t}(W_\beta)\, r_{m,t}.
\]

You can apply residualisation at three different layers (these are not equivalent):

1) **Returns layer**: substitute \(\tilde r\) into your momentum/trend metric (most common).  
2) **Signal layer**: compute signal on raw returns, then regress signal cross-sectionally on beta/exposures and take residual.  
3) **Portfolio layer**: build portfolio, then hedge the market exposure dynamically.

**Takes.** For your “18d/19d/21d” question, you want the residualisation to reduce cluster drawdowns and unwanted market exposure **without creating a noisy, high-turnover hedge ratio**. That implies you need to study two outputs simultaneously: (i) forward alpha and (ii) stability/turnover of beta estimates. There is no single-window optimum; there is often a *Pareto set*.

![Residual window trade-off schematic](sandbox:/mnt/data/residual_window_tradeoff_schematic.png)

### Short vs medium residualisation windows

**Short windows (≈7–14d)**  
**Facts.** Short windows reduce lag but increase estimator variance; in practice this is a classic bias–variance trade-off, and the beta-estimation literature finds that ad hoc choices can fail to produce market-neutral anomaly portfolios. citeturn17view0  
**Takes.** In crypto, short beta windows can “chase” sharp rebounds and mechanically pull you out of the winners/losers exactly when momentum effects are most regime-sensitive, amplifying turnover and reducing convexity.

**Medium windows (≈18–45d)**  
**Facts.** A 30-day rolling CAPM beta/alpha is explicitly used in a major daily-crypto factor modelling context. citeturn17view1  
**Takes.** Medium windows are a plausible “sweet spot” where betas adapt within a month but avoid the worst of weekly noise. Your 18–21d pocket may be the first place where beta stabilises enough to stop harming you while still adapting quickly enough to stop leaving you exposed. That hypothesis is testable.

**Long windows (≈60–180d+)**  
**Facts.** Crypto factor exposures and co-movement structure can vary at low frequency, with some research using rolling 360-day windows for certain latent-factor diagnostics, precisely because loadings are not constant. citeturn17view1  
**Takes.** Long windows may reduce beta instability but can become stale in rapid regime shifts; you may end up “residualising the wrong world”.

### Failure modes you should explicitly test

**Under-smoothing (window too short).**  
- \(\hat{\beta}_{i,t}\) becomes unstable → rank churn → higher turnover and noisier residual returns.  
- Residualisation can accidentally remove part of your signal if market moves dominate short horizons (especially in alt clusters).  
Support: beta estimation choices affect neutrality and prediction error. citeturn17view0

**Over-smoothing (window too long).**  
- Stale betas during regime shifts → residualisation fails to reduce crisis exposure.  
- “Market-neutral on paper, not neutral in stress.”  
Support: time-varying risk and factor exposure are central in multiple momentum and factor studies; momentum crash states are conditional and partially forecastable, and risk management often hinges on conditioning on volatility regimes. citeturn2search0turn20search0turn5search3

### What would change the decision

1) If the 18–21d window region remains a plateau under: (a) alternate market proxies, (b) alternate weighting (EWMA vs equal), and (c) alternate sampling (daily vs 1h aggregated), then you have evidence it is structural rather than a single-winner artefact. citeturn17view0turn16view0  
2) If the pocket disappears once you control for multiple testing / selection bias (DSR, Reality Check, PBO), then the “battleground” is largely selection noise. citeturn18view1turn18view2turn16view1  
3) If the pocket’s advantage is explained almost entirely by turnover reduction rather than improved gross returns, you should treat it as an execution/cost optimisation rather than a signal improvement (still valuable, but changes how you promote it).

## Narrow-tail portfolio construction around 2.5% tails

**Facts.** Cross-sectional crypto return patterns show interactions between liquidity/risk and past-return measures; and studies that systematically search across interactions find that liquidity constraints and transaction costs can be central to whether anomalies persist. citeturn13view0  Trend-factor research in crypto also explicitly investigates robustness to transaction costs and whether effects persist in large/liquid coins. citeturn12view0

**Takes.** A 2.5% tail width is often where two forces balance: (i) stronger signal contrast between extremes, versus (ii) reduced breadth, more rank instability, and higher turnover. You should assume the “best region” could be either real alpha concentration or a fragile artefact of liquidity/coverage.

![Tail width trade-off schematic](sandbox:/mnt/data/tail_width_tradeoff_schematic.png)

### The key trade-offs and how to measure them

Let \(N_t\) be universe size at time \(t\), tail fraction \(q\), and \(n_t = \lfloor q N_t \rfloor\) names per side. A generic dollar-neutral long/short tail portfolio is:

\[
w_{i,t} =
\begin{cases}
+\frac{1}{n_t} & i \in \text{Top}(q)\\[4pt]
-\frac{1}{n_t} & i \in \text{Bottom}(q)\\[4pt]
0 & \text{otherwise}
\end{cases}
\]

**Facts.** Breadth matters: the “fundamental law of active management” line of work formalises that information ratio scales with forecast skill and breadth (roughly, number of independent bets), while also warning that constraints and implementation details matter. citeturn3search10turn3search14  
**Takes.** Narrowing tails reduces breadth \(n_t\), often increasing idiosyncratic volatility and instability of ranks—especially in noisy alt markets—so you must measure *forward drift per unit turnover* rather than raw Sharpe only.

### Equal-weight vs vol-scaled weighting inside narrow tails

Two canonical within-tail weightings:

- **Equal-weight tails** (above): maximises breadth but can overweight high-vol, low-liquidity names.
- **Vol-scaled tails**:
\[
w_{i,t} \propto \frac{\mathrm{sign}(\mathrm{score}_{i,t})}{\hat{\sigma}_{i,t}},
\quad i\in \text{tails}
\]
with caps to prevent concentration.

**Facts.** Volatility scaling is explicitly proposed and empirically supported as a way to manage momentum crash risks in traditional markets; and momentum crashes are conditional on market states and volatility. citeturn20search0turn2search0turn5search3  
**Takes.** In narrow tails, vol-scaling can turn into a stealth liquidity filter (because high vol often correlates with poor liquidity). That can be good (lower costs) but can also “explain away” the perceived alpha; you should decompose performance into (signal) vs (liquidity selection).

### How to tell “real alpha” from “lower breadth”

A practical diagnostic set:

1) **Neighbourhood test**: does performance remain good for \(q\in[2\%, 4\%]\), or is it a spike at 2.5%? Overfitting in model selection is exactly the risk; plateaus are healthier than spikes. citeturn16view0turn18view2  
2) **Liquidity stratification**: run the same tail widths within liquidity buckets; if edge exists only in illiquid names and dies after costs, it’s likely not deployable. citeturn13view0turn12view0  
3) **Turnover-penalised objective**: evaluate net drift subject to a turnover budget; narrow tails that win only by slashing turnover can be fine, but it changes what you should promote.

**What would change the decision.** If the 2.5% tail advantage is not robust to (a) small changes in \(q\), (b) liquidity filters, and (c) execution-cost perturbations, treat it as “fragile”. Prefer a slightly wider tail that yields a plateau in net drift and smaller forward-drift variance. citeturn16view0turn12view0

## Funding as an overlay rather than a core predictor

**Facts.** Hyperliquid funding is paid hourly, computed from an 8‑hour formula that combines an interest component and a premium component, includes an explicit clamp term, and uses oracle and impact prices; funding is peer-to-peer (not a venue fee). citeturn19view0

**Facts.** The academic literature on crypto derivatives emphasises that “carry”-like measures in crypto are persistent, linked to limits-to-arbitrage and margin frictions, and can predict stress events such as liquidations; the BIS working paper provides evidence of large drawdowns and forced-liquidation risk in carry implementations in crypto futures contexts. citeturn14view0

**Takes.** Funding should be treated as an overlay because it is simultaneously:
- a *cashflow* (directly affects realised PnL over multi-day holds), and
- a *positioning proxy* (often reflects one-sided demand / leverage appetite),
which can cut both ways (reinforce trend, or indicate crowded trades prone to reversal).

### When funding should reinforce trend

A conservative overlay logic is to use funding as a **position sizing modifier** rather than as a standalone ranker. For an existing trend score \(z_{i,t}\), define:

\[
z'_{i,t} = z_{i,t}\cdot g(f_{i,t}),
\]
where \(f_{i,t}\) is recent realised funding (or predicted funding) and \(g(\cdot)\) is bounded (to avoid blow-ups), e.g.
\[
g(f)=\mathrm{clip}(1 + \kappa \cdot \mathrm{zscore}(f),\, g_{min},\, g_{max}).
\]

**Takes.** Funding “reinforces” trend most plausibly when the trend is aligned with broad persistent demand (eg longs paying positive funding in a prolonged uptrend), *and* when your expected net edge already clears `minNetEdgeBps` without funding—so funding is incremental. Using it this way prevents the overlay from becoming a hidden architecture change.

### When funding proxies crowding or reversal risk

**Facts.** Carry/funding-like premia are not free lunches; limits to arbitrage and margining frictions can force liquidation before convergence, and carry can predict liquidation activity. citeturn14view0  
**Takes.** Very extreme funding can be treated as a “crowding” alarm: you can cap position size or require stronger net edge to hold/enter. Mechanically, funding is a headwind for the crowded side; economically, extreme one-sided leverage can increase crash susceptibility.

### How to test overlay stability without overfitting weights

**Facts.** Large searches over overlays are exactly where data snooping bites; Reality Check and selection-bias-aware statistics exist for this problem. citeturn18view2turn18view1  
A robust test protocol:

- Treat \(\kappa\) as a *neighbourhood* parameter: test a grid and require plateau stability, not a single best value. citeturn16view0  
- Use nested walk-forward: choose \(\kappa\) using only prior windows, then evaluate on the next window. citeturn16view0turn3search8  
- Report incremental value as **Δ net drift** and **Δ drawdown** at fixed turnover (funding overlays can change holding times).  
- Apply multiplicity control (Holm/BH) across assets/parameters if you’re testing many overlays. citeturn7search0turn7search1  

**What would change the decision.** If the funding overlay improves backtest Sharpe while worsening forward drift or increasing tail losses in high-vol regimes, treat it as a failure—even if mean performance rises. This follows directly from the “strong backtest / weak forward” risk discipline implied by the data-snooping literature. citeturn18view2turn16view1

## Regime-sliced forward drift decision framework

**Facts.** Momentum/trend strategies have well-documented regime dependence, including crash-like episodes that are partly forecastable and linked to volatility states; additionally, momentum risk is time-varying and can be managed by volatility scaling in traditional markets. citeturn2search0turn20search0turn5search3

**Facts.** Crypto trend-factor work explicitly checks robustness across market states and alternative research designs, and reports survival of transaction costs in certain settings—useful as a standard of evidence for “deployability”. citeturn12view0

**Takes.** Your “forward drift” framing is exactly right: multi-day cross-sectional strategies can look statistically fine in backtest while failing to monetise live if the profitable regimes are sparsely sampled or if transaction costs/impact shift with liquidity regimes.

### A decision matrix you can implement

Define two estimates per configuration \(c\):

- \(IS(c)\): in-sample performance summary (across training segments), eg median net bps/day.
- \(OOS(c)\): out-of-sample performance summary (walk-forward validation), plus a forward drift estimate with uncertainty (bootstrap CI).

Then classify by quadrant:

| Quadrant | Interpretation | Default action |
|---|---|---|
| Strong backtest / strong forward | likely real, scaling question remains | keep, tighten risk and cost bounds |
| Strong backtest / weak forward | classic overfit or regime mismatch | recalibrate thresholds/residualisation; if still weak, kill |
| Weak backtest / strong forward | indicates your search metric mis-specified or regime shift | investigate regime slicing; consider “promote lightly” with higher thresholds |
| Weak backtest / weak forward | not worth resource | kill |

**Facts.** The Probability of Backtest Overfitting framework formalises how easily in-sample winners can disappoint out-of-sample and recommends reporting PBO rather than trusting IS metrics alone. citeturn16view1

### Regime slicing that matters for your stack

At minimum, slice performance by:

- **Volatility regime** (eg realised vol quantiles), because momentum crash risk is state-dependent and volatility scaling is a known stabiliser. citeturn2search0turn20search0turn5search3  
- **Liquidity regime** (spread/depth proxies and turnover–slippage conditions), because some documented crypto interactions are driven by liquidity constraints, and transaction costs can dampen apparent anomalies. citeturn13view0turn12view0  
- **Market trend regime** (market proxy up/down, drawdown state).

**Takes.** The purpose of slicing is not storytelling; it is to decide whether your 18–21d pocket is “real but conditional” (deploy with regime gates) or “backtest-only” (do not deploy).

**What would change the decision.** If your best configs show (i) positive net drift only in one volatility bucket and lose heavily elsewhere, you’re not selecting a robust trend engine—you’re selecting a conditional bet. That can still be deployable, but only if you adopt explicit regime gating; otherwise you should kill or materially recalibrate. citeturn2search0turn16view1

## Trailing-stop and exit overlays for 1d / 72h trend families

**Facts.** The stop-loss literature supports your framing that stops are an *overlay*: under a random-walk model, simple stop rules reduce expected return; in the presence of momentum, stop policies can add value (“stopping premium” can be positive). This is formalised in the stop-loss framework by entity["people","Kathryn M. Kaminski","risk mgmt researcher"] and entity["people","Andrew W. Lo","finance professor"]. citeturn25view0

**Facts.** There is also explicit empirical work arguing that stop-loss overlays can tame momentum crashes: one study proposes a simple stop-loss rule that substantially reduces extreme momentum drawdowns in long historical samples and reports improved Sharpe under certain stop levels. citeturn24view1  Crypto-specific evidence exists as well: a paper analysing 147 cryptocurrencies (2015–2022) reports that stop-loss momentum outperforms benchmark momentum strategies across market states. citeturn24view2

**Takes.** For your current stack, treat stop logic as a **risk/exit layer that is evaluated after the core daily residual-trend region is stabilised**. That isn’t just intuition: stop overlays are parameter-rich and easy to overfit, so they should inherit the same “plateau not spikes” discipline as thresholds and residualisation.

### Overlay families aligned to multi-day cross-sectional portfolios

1) **Volatility-based trailing stop (ATR/realised vol).**  
Let \(P_{i,t}\) be price and \(\widehat{\sigma}_{i,t}\) a recent realised vol or ATR-like measure. A trailing stop can be written:

\[
StopPrice_{i,t} = \max_{u\le t} P_{i,u} - k \cdot \widehat{\sigma}_{i,t}
\]
(for a long), with analogous for shorts.

2) **Layered partial exits.**  
Reduce exposure by fractions when drawdown crosses thresholds \(L_1<L_2\):
\[
w_{i,t} = w_{i,t}^{base}\cdot
\begin{cases}
1 & DD < L_1\\
\alpha & L_1 \le DD < L_2\\
0 & DD \ge L_2
\end{cases}
\]

3) **Time stops.**  
Exit after \(H\) days if the position has not generated sufficient net progress (prevents dead capital).

4) **Trend-break exits vs price-only exits.**  
Exit when the trend score falls below a threshold (signal-consistent) rather than when price crosses a trailing barrier.

**Takes.** In cross-sectional portfolios, stop logic can reduce portfolio convexity by chopping winners (the “cut your flowers” problem) while helping by preventing correlated reversal clusters. This is why you must evaluate stop overlays at the portfolio level—not just per-asset.

### Validation rules that match your “no magic multipliers” requirement

- Test stops as **neighbourhoods**, eg \(k\in[1.5,3.0]\) with step 0.25, and demand plateau stability (same selection-bias logic as thresholds). citeturn16view0turn18view2  
- Report: turnover, profit giveback, drawdown, forward drift, and “stop-out clustering” frequency.  
- Treat “improved backtest, worse forward” as failure; PBO/DSR/Reality-Check logic applies equally to stop overlays. citeturn16view1turn18view1turn18view2  

### Crypto/HLL-specific mechanics

**Facts.** Hyperliquid funding accrues hourly and can be computed/queried via the venue’s documented mechanisms; for multi-day holds, funding is a first-order term in realised PnL and must be included when judging whether a stop “helped” or “hurt”. citeturn19view0turn15search5  
**Facts.** Fees are tiered and assessed daily UTC; this creates natural UTC boundary considerations for daily rebalances and also affects stop overlays if they change trading frequency. citeturn19view1

**What would change the decision.** If an exit overlay reduces drawdown but also reduces forward drift enough that it fails `minNetEdgeBps` under promotion thresholds, it’s not a deployable improvement for your objective (“after-cost PnL with survivable drawdown”). Keep it only if it improves a risk-adjusted criterion that correlates with deployability in your own forward windows (not in-sample). citeturn16view1turn18view1