# Deploying Nifty AutoTrader

The app is a Spring Boot 3.3 / Java 21 JAR that needs **PostgreSQL**. Flyway
migrations run automatically on startup, so the database schema is created for you.

All secrets are read from **environment variables** — nothing is hardcoded. Live
trading stays **disabled** (`app.trading.live-enabled: false`) regardless of host.

## Required environment variables

| Variable | Required | Purpose |
|---|---|---|
| `DATABASE_URL` *or* `SPRING_DATASOURCE_URL` | yes | Postgres connection. `DATABASE_URL` may be the `postgres://user:pass@host:port/db` form — it's auto-converted to JDBC. |
| `APP_USERNAME` / `APP_PASSWORD` | yes | Login for the web UI. **Set strong values for any public deployment.** |
| `ANTHROPIC_API_KEY` | optional | Claude sentiment veto + strategy enhancement. |
| `KITE_API_KEY` / `KITE_API_SECRET` / `KITE_REDIRECT_URL` | optional | Zerodha data. |
| `DHAN_ENABLED` / `DHAN_CLIENT_ID` / `DHAN_ACCESS_TOKEN` | optional | Dhan market data. |
| `PORT` | auto | Set by the platform; app listens on it. |

---

## 100% FREE — Render (web) + Neon (Postgres)  ⭐ recommended free combo

Render's free Postgres expires after ~30 days, so use **Neon** for a persistent
free database and Render's free web service to run the app.

**1. Create a free Postgres on Neon**
- Sign up at [neon.tech](https://neon.tech) → create a project (region: choose one
  near you, e.g. Singapore for India).
- Copy the **connection string** — looks like
  `postgresql://user:pass@ep-xxx.aws.neon.tech/dbname?sslmode=require`.
  (The app auto-converts this to a JDBC URL; keep `?sslmode=require`.)

**2. Deploy the app on Render**
- Push this repo to GitHub.
- On [render.com](https://render.com): **New → Web Service** → connect the repo.
- Render detects the `Dockerfile`. Set **Instance Type: Free**.
- Under **Environment**, add:
  - `DATABASE_URL` = the Neon connection string from step 1
  - `APP_USERNAME` = your login
  - `APP_PASSWORD` = a strong password
  - `ANTHROPIC_API_KEY` = optional
- Create the service. First build takes a few minutes; Flyway creates the schema
  on first boot.

**Free-tier notes**
- The free web service **sleeps after 15 min of inactivity**; the next visit takes
  ~50s to wake. Fine for personal use/demos.
- Neon free tier is **persistent** (your imported candles survive) but may pause an
  idle DB; it resumes automatically on the next query.
- 512MB RAM is enough — the Dockerfile caps the JVM heap to fit.

After it's live: open the URL, log in, then **Backtest → Import Data** to pull
NIFTY candles before using Replay.

---

## Option A — Railway (easiest, paid after trial)

1. Push this repo to GitHub.
2. On [railway.app](https://railway.app): **New Project → Deploy from GitHub repo**.
3. **+ New → Database → PostgreSQL.** Railway creates it and a `DATABASE_URL`.
4. Open the app service → **Variables** → add a reference to the Postgres
   `DATABASE_URL`, plus `APP_USERNAME`, `APP_PASSWORD`, and `ANTHROPIC_API_KEY`.
5. Railway detects the `Dockerfile` and builds automatically. Once live, open the
   generated URL and log in with your `APP_USERNAME` / `APP_PASSWORD`.

## Option B — Render (free tier available)

1. Push to GitHub.
2. On [render.com](https://render.com): **New → Blueprint**, point at this repo.
   The included `render.yaml` provisions the web service **and** a Postgres DB,
   wiring `DATABASE_URL` automatically.
3. In the dashboard, fill the secret vars marked `sync: false`
   (`APP_USERNAME`, `APP_PASSWORD`, `ANTHROPIC_API_KEY`, …).
4. Deploy. Health check is `/actuator/health`.

## Option C — Any VPS / your own server (Docker)

```bash
git clone <your-repo> && cd trading-app
cp .env.example .env        # then edit .env with real secrets
docker compose up -d --build
```

This runs the app + Postgres together. The app is on port `8080`. Put Nginx (or
Caddy) in front for HTTPS and a domain.

## Build/run without Docker

```bash
mvn clean package -DskipTests
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/trading-app \
DB_USERNAME=postgres DB_PASSWORD=secret \
APP_USERNAME=admin APP_PASSWORD=strongpass \
java -jar target/*.jar
```

---

## After deploying

- **Import data first:** open **Backtest → Import Data** and pull NIFTY candles
  (1m gives the last ~7 days; 5m gives ~60 days). The **Replay** feature needs
  this data.
- **HTTPS:** all hosts above terminate TLS for you (Railway/Render) or via your
  reverse proxy (VPS). Don't expose plain HTTP publicly — `APP_PASSWORD` is sent
  on login.
- **Live trading stays off.** Enabling it is a deliberate config + credentials
  change and is out of scope for a public demo deployment.
