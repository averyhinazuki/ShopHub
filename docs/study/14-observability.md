# 14 — Observability

Unit 14, closing Block C. Prerequisite: `11` (the monitoring containers, loopback-only ports).

⚠️ Taught from code only — the app stack is destroyed, so no live verification (see `13`).

---

# Concept 1 — Metrics, and why histograms matter ✅

## The three signals

| | Answers | Cost |
|---|---|---|
| **Metrics** | how much / how many / how fast, over time | cheap, fixed size |
| **Logs** | what exactly happened in this one case | expensive, grows with traffic |
| **Traces** | where one request spent its time | expensive |

Metrics are cheap because they're **aggregates** — one number per series per scrape, whether you
served 10 requests or 10 million. ShopHub has metrics and logs; no tracing.

## The four types

- **Counter** — only goes up (`http_server_requests_seconds_count`); you read its **rate**, not it
- **Gauge** — up and down (`jvm_memory_used_bytes`, `hikaricp_connections_active`)
- **Histogram** — counts observations into **buckets** ← the interesting one
- **Summary** — client-computed quantiles, can't be aggregated across instances; mostly avoided

## How a histogram works

Cumulative counts per bucket boundary, where `le` = "less than or equal":

```
http_server_requests_seconds_bucket{le="0.005"}  128     ≤ 5ms
http_server_requests_seconds_bucket{le="0.05"}   482     ≤ 50ms (includes the 128)
http_server_requests_seconds_bucket{le="+Inf"}   505     everything
```

From that shape you can *estimate* any percentile by interpolation. That's `histogram_quantile`.

**It exists only because of one config line** (`application.yml:94-96`):

```yaml
distribution:
  percentiles-histogram:
    http.server.requests: true
```

Without it Micrometer emits count and sum but **no `_bucket` series**, and the p95/p99 panels and
the latency alert silently return nothing.

Also `exposure.include: health,prometheus` (`:90`) — two endpoints, not `*`. No `/actuator/env`
leaking config, no `/actuator/heapdump`.

## Reading the incantation

```promql
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri!="/actuator/prometheus"}[5m])) by (le))
```

| Part | Why |
|---|---|
| `{uri!="/actuator/prometheus"}` | exclude Prometheus' own scrape |
| `rate(...[5m])` | buckets are **counters**; raw values are all-time. `rate` gives recent behaviour |
| `sum(...) by (le)` | aggregate across instances **but keep the bucket boundary** — without `by (le)` there is nothing to interpolate over |
| `histogram_quantile(0.99, ...)` | interpolate to the 99th percentile |

Dropping `by (le)` returns nonsense. It's the most common PromQL mistake.

## Why exclude the actuator scrape

Prometheus scrapes every 15s and that endpoint answers in single-digit ms. Including it floods the
histogram with artificially fast samples, dragging the quantile down so real latency looks better
than it is. **On an idle site it's the only traffic**, so p99 would report the scrape's own
latency — meaningless. (And this is what sets up the `NoData` story below.)

## p99 vs average

99 requests at 10ms and one at 5s → **average 60ms** (healthy-looking), **p99 5s**. Averages hide
the tail, and the tail is what users notice and what signals saturation. At 1,000 rps, "p99 = 1s"
means **ten users every second** having a bad time.

---

# Concept 2 — Pull-based scraping ✅

```yaml
scrape_interval: 15s
scrape_configs:
  - job_name: shophub     targets: ['app:8080']            metrics_path: /actuator/prometheus
  - job_name: prometheus  targets: ['localhost:9090']      # self-scrape
  - job_name: node        targets: ['node-exporter:9100']  # host metrics
```

Prometheus **pulls** — connects to each target on a schedule and reads a text endpoint. (Push
alternatives: StatsD, OTLP.) Note `app:8080` is Docker DNS (→ `01`); Prometheus reaches the app on
the **internal network**, which is why the app publishing no ports doesn't prevent monitoring it.

**The killer advantage: the scrape is a health check.** Prometheus synthesises **`up`** for every
target — 1 if the scrape succeeded, 0 if not. Free liveness monitoring, and the entire basis of the
first alert. `up` is **not exported by the app**; Prometheus makes it.

Three jobs, three purposes: the app's metrics, Prometheus watching itself, and node-exporter for
host facts the JVM can't see — notably `/data` filling, since containers look healthy right up
until the disk is full.

---

# Concept 3 — Alert states and the `NoData` decision ✅

`Normal` → `Pending` → `Alerting`, plus `NoData` and `Error`.

**`Pending`** is what `for:` produces — `for: 5m` means the condition must hold **continuously**
for five minutes, suppressing flapping from one bad sample.

Each rule is a three-node chain (`rules.yml:10-11`): **A** the query, **B** reduce to one number,
**C** compare to a threshold.

## `noDataState` — the question the unit builds to

**When the query returns nothing, what does that mean?**

| Rule | Query | Absence means | State |
|---|---|---|---|
| App scrape down | `up{job="shophub"}` | Prometheus isn't even trying — **real signal** | `NoData` ✓ |
| /data under 20% | `node_filesystem_avail_bytes{mountpoint="/data"}` | node-exporter down or mount gone — **real signal** | `NoData` ✓ |
| p99 above 1s | `histogram_quantile(...{uri!="/actuator/prometheus"}...)` | **nobody visited** | `OK` ✓ |

The first two metrics **always exist** while healthy, so absence is itself a fault. The p99 rule is
different: with the actuator excluded, an **idle site produces zero samples**, so `NoData` was that
rule's *resting state* — a permanent `DatasourceNoData` instance that never cleared, plus spurious
firing during JVM warmup. Fixed in PR #9.

> **`noDataState` should encode whether absence of data is itself information.** For `up` and
> filesystem metrics, absence means something broke. For a rate over user traffic, absence means
> nobody visited — not a problem.

**And this can only be determined by watching a real idle system.** No amount of reading the YAML
tells you a rule will sit in `NoData` forever.

## Gotchas

- **The alerts go nowhere.** No contact point is provisioned (`rules.yml:4-8`); they fire onto a
  default policy with no SMTP and delivery silently no-ops. Documented as deliberate backlog (it
  needs a secret and a destination) — but it's the same shape as **F2**: detection exists,
  notification doesn't.
- **No domain metrics.** Fifteen panels covering infrastructure, zero covering the business. No
  checkout outcome counters, no DLT depth, no compensation-failure counter — so **F1, F2, F13, F17
  and F19 would all be invisible** while the dashboard stayed green. (→ **F22**)

## Questions I should be able to answer

- Distinguish metrics, logs and traces by what they answer and what they cost.
- What are the four metric types, and why is a Summary usually avoided?
- How does a histogram store latency, and what does `le` mean?
- Which single config line makes p99 possible at all, and what breaks without it?
- Explain each part of the `histogram_quantile` expression. What happens without `by (le)`?
- Why is `/actuator/prometheus` excluded from the latency queries — twice over?
- Give a concrete case where the average looks fine and p99 doesn't.
- What is `up`, who produces it, and why is that an argument for pull over push?
- What does `for: 5m` prevent?
- State the rule for choosing `noDataState`, and why the p99 rule differs from the other two.
- Which of the logged findings would the current dashboard catch? (Almost none — why?)
