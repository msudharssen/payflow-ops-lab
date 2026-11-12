# PayFlow Ops Lab — Project Spec

> A practice environment for **application support** work: a realistic app that breaks in realistic ways, monitored with enterprise-style tools, and raising **ServiceNow incidents** you then work from start to finish.
>
> Owner: Sud · Target machine: MacBook Pro M3 (Apple Silicon / arm64), 18 GB RAM · Status: **Draft v0.1**

---

## 1. Why this exists

An app support analyst's day goes like this: **an alert or ticket comes in → find the cause → fix it or escalate → write it up**. This lab trains that loop with the tools the Citi role uses (or close stand-ins):

| At Citi | In this lab |
|---|---|
| ServiceNow | ServiceNow Personal Developer Instance (PDI), with a local fallback |
| Oracle + SQL Developer | Oracle Database Free + SQL Developer on the Mac |
| ELK / log search | Elasticsearch + Kibana (Elastic Stack) |
| ITRS Geneos / alerting | Prometheus rules + Alertmanager, Grafana dashboards |
| AppDynamics | Grafana APM-style dashboard (latency, errors, throughput per endpoint) + the separate AppDynamics SaaS trial |
| Teams / Outlook | Out of scope (practise directly) |

**Goal:** by the end, Sud can take an incident from "New" to "Resolved" with a clear root cause, using only dashboards, logs, SQL and the runbooks, and without looking at the answer.

---

## 2. Architecture

```
            ┌────────────┐   HTTP traffic   ┌──────────────────────┐
            │ traffic-gen│ ───────────────▶ │   payflow-app        │
            │   (k6)     │                  │ Spring Boot, Java 21 │
            └────────────┘                  │  REST API + batch job│
                                            └──┬──────┬──────┬─────┘
                               JDBC (Hikari)   │      │      │ HTTP
                                ┌──────────────┘      │      └───────────────┐
                                ▼                     │ JSON logs (file)     ▼
                       ┌────────────────┐             │             ┌────────────────┐
  SQL Developer ─────▶ │ oracle-free    │             ▼             │ fraud-check    │
  (on the Mac)         │ Oracle DB 23ai │      ┌────────────┐       │ (WireMock mock │
                       └────────────────┘      │ filebeat   │       │  downstream)   │
                                               └─────┬──────┘       └────────────────┘
       /actuator/prometheus                          ▼
  ┌──────────────┐  scrape  ┌────────────┐   ┌───────────────┐   ┌──────────┐
  │ payflow-app  │ ◀─────── │ prometheus │   │ elasticsearch │◀──│ kibana   │
  └──────────────┘          └─────┬──────┘   └───────────────┘   └──────────┘
                                  │ alert rules
                    ┌─────────────▼─────┐   webhook   ┌──────────────────┐   REST    ┌─────────────┐
   grafana ◀──────  │ alertmanager      │ ──────────▶ │ incident-bridge  │ ───────▶  │ ServiceNow  │
   (dashboards)     └───────────────────┘             │ (Python/FastAPI) │           │ PDI         │
                                                      └──────────────────┘           └─────────────┘
   chaos/ scripts ──▶ break things (DB, config, downstream, JVM, disk)
```

Everything runs with `docker compose up -d` except SQL Developer (installed on the Mac) and ServiceNow (cloud PDI).

---

## 3. Components

### 3.1 `payflow-app`: the application under support
- **Stack:** Java 21, Spring Boot 3.x, Maven, Spring Data JPA + plain JDBC for a few raw queries, HikariCP, Spring Actuator, Micrometer Prometheus registry, Logback JSON encoder.
- **Domain:** a simplified payments service.

**REST API**

| Method | Path | Behaviour |
|---|---|---|
| POST | `/api/payments` | Create payment: validate → call fraud-check → convert currency via `FX_RATES` → insert as `PENDING` |
| GET | `/api/payments/{id}` | Fetch one payment |
| GET | `/api/payments?status=&accountId=&from=&to=` | Search (the query that goes slow without its index) |
| GET | `/api/accounts/{id}/balance` | Balance from settled payments |
| GET | `/actuator/health`, `/actuator/prometheus` | Health + metrics |

**Batch job: `SettlementJob`**
- Runs every 5 min (stands in for a nightly end-of-day batch).
- Moves `PENDING` → `SETTLED`, writes a row to `SETTLEMENT_BATCHES` (records read / settled / skipped), logs a summary line.
- Must be able to **fail silently** (skip bad rows, exit "successfully") for one scenario.

**Logging**
- JSON to stdout **and** `/var/log/payflow/app.log` (shared volume read by Filebeat).
- Every log line has `timestamp, level, logger, message, traceId, paymentId (if any), endpoint, durationMs`.
- A `traceId` is generated per request and returned in the `X-Trace-Id` header, so a ticket can be followed request by request.

**Metrics (Micrometer)**
- HTTP: requests, latency histogram (p50/p95/p99), errors per endpoint
- Hikari: active / idle / pending connections, connection timeouts
- JVM: heap, GC pauses, threads, CPU
- Business: `payments_created_total`, `payments_failed_total{reason}`, `settlement_last_run_timestamp`, `settlement_skipped_records`, `fraudcheck_latency`
- Disk: `payflow_log_volume_used_bytes` (for the disk-full scenario)

**Chaos hooks** (enabled only with `SPRING_PROFILES_ACTIVE=lab`)
- `POST /internal/chaos/{scenario}/on|off`. Called by the `chaos/` scripts, never by the learner directly.

### 3.2 `oracle-free`: database
- Image: `gvenzl/oracle-free` (slim tag; has arm64 builds, so confirm the tag at build time). Fallback if it misbehaves on Apple Silicon: run Docker through Colima with Rosetta.
- Exposed on `localhost:1521`, service `FREEPDB1`, app user `PAYFLOW`, read-only user `SUPPORT_RO` (what Sud uses in SQL Developer, like an L2 analyst would).
- Seed data: ~50k payments, ~500 accounts, FX rates for 10 currencies.

**Schema**

| Table | Key columns |
|---|---|
| `ACCOUNTS` | `account_id`, `name`, `currency`, `status`, `created_at` |
| `PAYMENTS` | `payment_id`, `account_id`, `amount`, `currency`, `amount_usd`, `status` (PENDING/SETTLED/FAILED/REJECTED), `fraud_score`, `trace_id`, `created_at`, `settled_at` |
| `FX_RATES` | `currency`, `rate_to_usd`, `updated_at` |
| `SETTLEMENT_BATCHES` | `batch_id`, `started_at`, `finished_at`, `read_count`, `settled_count`, `skipped_count`, `status` |
| `AUDIT_LOG` | `id`, `entity`, `entity_id`, `action`, `actor`, `at` |

Indexes: `IX_PAY_STATUS_CREATED (status, created_at)`, `IX_PAY_ACCOUNT (account_id)`.

### 3.3 `fraud-check`: mocked downstream dependency
- WireMock container that answers `POST /score` with a fraud score.
- Chaos scripts use the WireMock admin API to add delay, return 503s or time out.

### 3.4 `traffic-gen`
- k6 container producing steady, realistic load (~5–20 req/s) with a daily-shape curve, so dashboards always have data.

### 3.5 Observability
- **Prometheus:** scrapes the app every 15s; alert rules in `observability/prometheus/rules.yml`.
- **Alertmanager:** groups alerts and sends them to `incident-bridge` by webhook. Handles both firing and resolved alerts.
- **Grafana:** provisioned automatically with dashboards:
  1. *PayFlow Overview* (traffic, errors, p95, health; the "is it on fire?" screen)
  2. *Endpoint Detail* (AppDynamics-style: per-endpoint latency, slowest calls, downstream latency)
  3. *JVM & Connection Pool*
  4. *Batch & Business KPIs* (settlement runs, skipped records, payment counts)
- **Elasticsearch + Kibana** (8.x, security disabled for local use, heap capped at 1 GB). **Filebeat** ships `app.log`. Logstash is left out to save memory; it can be added later if needed.
- Kibana is set up with a `payflow-*` data view and saved searches (errors only, by traceId, slow requests > 2s).

**Alert rules (first pass)**

| Alert | Condition | Severity → SN priority |
|---|---|---|
| `HighErrorRate` | 5xx > 5% for 2m | critical → P2 |
| `HighLatencyP95` | p95 > 2s for 5m | warning → P3 |
| `DBPoolExhausted` | pending connections > 0 for 2m | critical → P2 |
| `HeapHigh` | heap used > 85% for 5m | warning → P3 |
| `AppDown` | health check fails for 1m | critical → P1 |
| `SettlementStale` | no successful settlement in 15m | warning → P3 |
| `SettlementSkips` | skipped records > 0 in last run | warning → P4 |
| `LogVolumeFilling` | log volume > 80% | warning → P3 |
| `FraudCheckSlow` | downstream p95 > 1.5s for 3m | warning → P3 |

### 3.6 `incident-bridge`: alerts → ServiceNow
- Python 3.12 + FastAPI, about 200 lines.
- `POST /alertmanager` receives the webhook, and for each alert:
  - **Firing:** creates an incident through the ServiceNow Table API (`POST /api/now/table/incident`), unless an open incident already has the same `correlation_id` (alert fingerprint). In that case it adds a work note instead (deduplication).
  - **Resolved:** adds a work note saying "alert cleared at …". It does **not** close the incident. Closing is Sud's job, with close notes.
- Field mapping: `short_description` = `[PayFlow] <alertname>: <summary>`, `description` = labels, values and dashboard/Kibana links, `urgency`/`impact` taken from severity, `category=Software`, `assignment_group=PayFlow App Support`, `cmdb_ci=PayFlow`, `correlation_id=<fingerprint>`.
- Config through `.env`: `SN_INSTANCE`, `SN_USER`, `SN_PASSWORD` (a dedicated integration user, not admin), `MODE=servicenow|local`.
- **Local fallback (`MODE=local`):** saves incidents to SQLite and shows a basic queue page at `localhost:8090`, for use if the PDI is waitlisted or asleep.
- `scripts/user-ticket.sh "<vague complaint>"` raises a "user-reported" incident with no alert data, to practise tickets like "payments are slow??".

**ServiceNow PDI setup (one-time, documented in `docs/servicenow-setup.md`)**
- Create assignment group *PayFlow App Support* and add Sud to it.
- Create a CI *PayFlow* (Application class).
- Create integration user `payflow.bridge` with the `itil` role.
- Optional: a KB article template for runbooks.

---

## 4. Failure scenarios

Each scenario is a script in `chaos/` with `on`, `off` and `status`. The **learner-facing** runbooks in `runbooks/` describe symptoms and how to investigate. **Answers** (root cause + fix) are kept in `solutions/` so they don't spoil the exercise.

| # | Scenario | Level | What breaks | Where the clues are |
|---|---|---|---|---|
| 1 | Missing index | ★ | Drop `IX_PAY_STATUS_CREATED` → the search endpoint gets slow | p95 alert, slow-request logs, Explain Plan shows full table scan |
| 2 | Downstream slow | ★ | fraud-check adds 3s delay | FraudCheckSlow, endpoint latency breakdown, timeout logs |
| 3 | Bad config push | ★★ | App restarted with wrong fraud-check URL | Error spike right after a restart, `UnknownHostException` in logs, recent restart in uptime metric |
| 4 | Blocking session | ★★ | Script holds an uncommitted row lock on `PAYMENTS` | Updates hang, settlement stalls, `v$session.blocking_session` in SQL Developer |
| 5 | Connection pool exhaustion | ★★ | Code path leaks connections | DBPoolExhausted, `Connection is not available, request timed out` |
| 6 | Credential rotation | ★★ | DB password changed, app not updated | `ORA-01017` in logs, AppDown/health DOWN |
| 7 | Memory leak | ★★★ | Cache grows without limit → GC thrashing → OOM restart | HeapHigh, GC pause graph, restart count, `OutOfMemoryError` |
| 8 | Disk filling | ★★★ | Debug logging flood fills a size-capped log volume | LogVolumeFilling, then write errors |
| 9 | Silent batch failure | ★★★ | Rows with NULL currency get skipped; job says "SUCCESS" | Only SettlementSkips fires; found by SQL on `SETTLEMENT_BATCHES` and `PAYMENTS` |
| 10 | Bad reference data | ★★★ | An FX rate is set to 0 → `amount_usd` = 0 for one currency, no errors at all | User ticket ("balances look wrong"), found only by SQL |

**Modes**
- `./chaos/run.sh <n>` turns on a specific scenario (for learning).
- `./chaos/run.sh --blind` turns on a random scenario without saying which (for practice). The scenario is logged to `chaos/.last` for checking afterwards.
- `./chaos/reset.sh` turns everything off and restores the database, indexes, config and WireMock.

---

## 5. Practice workflow (for every incident)

1. **Acknowledge:** set the incident to *In Progress*, assign it to yourself, add a work note ("Investigating").
2. **Assess impact:** what's broken, for whom, since when? Change priority if needed.
3. **Investigate:** Grafana → Kibana (by time window or traceId) → SQL Developer → runbook.
4. **Mitigate:** fix it if the runbook allows; otherwise write up an escalation (what you checked, what you found, what you need).
5. **Resolve:** close notes using the template below.
6. **Review:** compare with `solutions/`, and paste the incident into chat for Claude to review as a senior engineer would.

**Close notes template**
```
Symptom:      <what users/alerts saw>
Impact:       <who/what, start–end time>
Root cause:   <the actual cause, with evidence>
Resolution:   <what was done>
Prevention:   <follow-up / problem ticket suggestion>
```

---

## 6. Repo layout

```
payflow-ops-lab/
├── README.md                 # quick start, ports, credentials
├── PROJECT_SPEC.md           # this file
├── docker-compose.yml
├── .env.example
├── app/                      # Spring Boot (Maven)
├── db/
│   ├── init/                 # schema, users, indexes
│   └── seed/                 # seed data
├── fraud-check/              # WireMock mappings
├── traffic/                  # k6 script
├── observability/
│   ├── prometheus/           # prometheus.yml, rules.yml
│   ├── alertmanager/
│   ├── grafana/              # provisioning + dashboards JSON
│   └── filebeat/
├── incident-bridge/          # FastAPI service + Dockerfile
├── chaos/                    # run.sh, reset.sh, scenarios/
├── scripts/                  # user-ticket.sh, healthcheck.sh
├── runbooks/                 # learner-facing, no spoilers
├── solutions/                # answers
└── docs/                     # servicenow-setup.md, sql-developer.md, cheatsheets
```

---

## 7. Resource budget (Docker Desktop: give it 8 GB)

| Service | Approx RAM |
|---|---|
| oracle-free | 2.0 GB |
| elasticsearch (1 GB heap) | 1.5 GB |
| kibana | 0.7 GB |
| payflow-app (512 MB heap) | 0.7 GB |
| prometheus + alertmanager + grafana | 0.5 GB |
| filebeat, wiremock, k6, incident-bridge | 0.5 GB |
| **Total** | **~6 GB** |

That leaves room for SQL Developer, a browser and Teams on 18 GB. Elastic can be stopped when not needed: `docker compose stop elasticsearch kibana filebeat`.

---

## 8. Build milestones

| # | Milestone | Done when |
|---|---|---|
| M1 | App + Oracle + traffic | `compose up` → app healthy, payments flowing, SQL Developer connects as `SUPPORT_RO` |
| M2 | Metrics + Grafana | All 4 dashboards show live data |
| M3 | Logs | Kibana shows app logs; can find a request by traceId |
| M4 | Alerts → tickets | Stopping the app creates a P1 incident in ServiceNow (or the local queue); restarting adds a "cleared" work note |
| M5 | Chaos 1–5 | Each scenario fires the expected alert and leaves the expected clues; `reset.sh` restores everything |
| M6 | Chaos 6–10 + blind mode | Same, plus blind mode works |
| M7 | Docs | README, runbooks, solutions, SN setup guide, SQL Developer guide |

**Order for a Monday deadline:** M1 → M4 first, so the full loop works end to end, then scenarios 1, 2, 4, 5, 9, which line up best with the Citi role. The rest can come after.

---

## 9. Non-goals
- No auth/UI for the payments app beyond the API (it's a backend service being supported, not a product).
- No Kubernetes and no cloud deploy. Local Docker only.
- Not production-hardened. Credentials live in `.env`, security is disabled in Elastic, and that's fine for a local lab.
- No real Citi data, names or screenshots anywhere in the repo.

---

## 10. Open questions (fill in before build)
- [ ] Docker Desktop installed? (or Colima)
- [x] ServiceNow PDI issued (Brazil release): `SN_INSTANCE=https://dev443933.service-now.com`. Credentials go in `.env` only, never in the repo.
- [ ] GitHub repo: Sud creates an empty repo `payflow-ops-lab` (public or private?), then Claude gets push access and pushes milestone by milestone
- [ ] SQL Developer installed on the Mac (arm64 build)?
- [ ] Any scenario to add or remove?
