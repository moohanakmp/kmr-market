# KMR Market 1.0

Java 21 / Spring Boot 3.5.6, React 19 / TypeScript / Vite, PostgreSQL 17/18.
Personal, single-admin starter: watchlist CRUD, manual trade-plan CRUD, price validation,
reward/risk display, database migrations, responsive UI, health endpoint and CI/CD.
This is a new starter, not a migration of the previous KMR scanner. Live quotes and automatic signals are supplied by the Windows MT5 bridge.
Optional demo broker orders are supported by the separate MT5 execution worker. Live-account execution and backtesting are not implemented. Broker-deal P&L is available in v1.1. Redis and Kubernetes/Devtron are unnecessary for this first version.

## Architecture

```mermaid
flowchart TD
  Browser[React browser UI] --> App[Render: Spring Boot container]
  App --> DB[Neon PostgreSQL]
  App --> Static[Bundled React static assets]
  Git[GitHub main commit] --> CI[GitHub Actions verification]
  CI --> Hook[Render deploy hook with commit SHA]
  Hook --> App
```

Same origin: React calls `/api`; Spring serves React assets and authenticated API.
Locally Compose runs the app and PostgreSQL, with persistent PostgreSQL volume.
Credentials are held only in browser memory and cleared on sign-out/reload.
Basic authentication requires HTTPS on every public deployment. API endpoints are
stateless and accept an explicit Authorization header; cookie-based auth is not used.
CSRF is disabled for this API design. This is a single trusted-admin setup, not a
multi-user platform. Add account lifecycle, rate limiting and authorization before
opening registration. Prices use NUMERIC(20,8) / BigDecimal in backend; browser numbers
are suitable for this display starter, not broker settlement accounting.

## Windows 11 installation

In **PowerShell as Administrator**:

```powershell
wsl --install -d Ubuntu-24.04
wsl --update
wsl --set-default-version 2
winget install --exact --id Microsoft.VisualStudioCode
winget install --exact --id Docker.DockerDesktop
```

Restart Windows if requested. Launch Ubuntu and create a Linux user.
Launch Docker Desktop, select the WSL2 engine, and enable Settings > Resources >
WSL Integration > Ubuntu-24.04. Docker Desktop is free for eligible personal use;
check its licensing if installing on an employer-managed machine.
Install the Microsoft WSL extension in VS Code. Do not install a second Docker daemon
inside Ubuntu when using Docker Desktop integration.

In **Ubuntu / WSL terminal**:

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven git curl unzip ca-certificates
# Install pinned Node through nvm. Review downloaded installation script if required.
curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh -o /tmp/install-nvm.sh
bash /tmp/install-nvm.sh
source ~/.bashrc
nvm install 22
nvm use 22
node --version
npm --version
java -version
mvn -version
docker version
docker compose version
mkdir -p ~/projects
```

Extract this ZIP into `~/projects/kmr-market-v1.0`. Keeping code in the Linux filesystem
avoids slow bind mounts from `/mnt/c`. You may copy the downloaded ZIP from Windows:

```bash
unzip /mnt/c/Users/kalai/Downloads/kmr-market-v1.0.zip -d ~/projects
cd ~/projects/kmr-market-v1.0
cp .env.example .env
nano .env
# Set two distinct random passwords. ADMIN_PASSWORD must be at least 10 characters.
docker compose up --build -d
docker compose logs -f app
```

Open http://localhost:8080. Sign in using the `.env` admin credentials.
Health: http://localhost:8080/actuator/health. First build downloads dependencies.

```bash
docker compose stop        # stop, retain data
# Restart:
docker compose up -d
# Remove containers but retain PostgreSQL data:
docker compose down
```

Do not use `docker compose down -v` unless you intend to erase the database.

## Development with hot reload

Run database alone; stop app container to release 8080. In Ubuntu terminal 1:

```bash
cd ~/projects/kmr-market-v1.0
docker compose stop app
docker compose up -d db
set -a
source .env
set +a
export DB_PASSWORD="$POSTGRES_PASSWORD"
cd backend
mvn spring-boot:run
```

When sourcing `.env`, use shell-safe values (for example hex random passwords).
Terminal 2:

```bash
cd ~/projects/kmr-market-v1.0/frontend
npm ci
npm run dev
```

Open http://localhost:5173. Vite proxies `/api` to localhost:8080.
Use VS Code from WSL (`code .`); Java Extension Pack is helpful.

## Free-tier cloud setup

Choose Render Free web service plus Neon Free database. Use provider-supplied
`*.onrender.com` URL for zero domain cost. `kalaimarket.in` requires registration and
renewal payment unless you already own it. Cloud hosting quotas and policies can
change; select Free explicitly, avoid paid upgrades, and review current limits.
Render Free sleeps after 15 minutes without traffic. It is not reliable infrastructure
for uninterrupted trading/scanning or deadline-sensitive broker execution. Neon has
free-plan storage/compute quotas. Render Free PostgreSQL expires after 30 days, so
this starter uses Neon instead. Keep DB and service regions close.

1. Create GitHub repository `kmr-market` and push the extracted source. Choose public
   for standard free hosted-runner access; private repositories have limited free CI
   minutes/storage. Use branch protection to require the `verify` check if available.
2. Create Neon Free project. Obtain hostname, database, username and password.
   Use the **direct/unpooled** endpoint for this small connection pool and Flyway.
   Construct JDBC URL without credentials:
   `jdbc:postgresql://YOUR_HOST/neondb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`.
   Do not paste `postgresql://user:password@...` into JDBC_DATABASE_URL.
3. In Render, create a Blueprint connected to this repository (`render.yaml`) or
   create a Docker web service manually. Choose Free, root Dockerfile, health path
   `/actuator/health`, and disable automatic Git deployments (Actions owns deploys).
4. Set Render environment variables: `JDBC_DATABASE_URL`, `DB_USERNAME`,
   `DB_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD` (10+ characters).
   PORT is supplied by Render. Never put secrets in frontend code or Git.
   Set `DB_SCHEMA=kmr`. In Neon SQL Editor, run as the database owner:\n\n   ```sql\n   CREATE SCHEMA IF NOT EXISTS kmr;\n   GRANT USAGE, CREATE ON SCHEMA kmr TO kmr_user;\n   ```\n\n   Run as `neondb_owner`; the schema remains admin-owned, while the app role can\n   create its own tables. This avoids needing SET ROLE permission to kmr_user.\n   Substitute your actual DB username in GRANT. KMR tables and Flyway history use this schema;\n   existing public objects are preserved. For a previously working deployment that\n   already stores KMR tables in public, leave DB_SCHEMA unset or use public to\n   preserve access to existing data. Do not change an existing app schema blindly.\n5. Initial deploy can be performed from Render. Open the HTTPS service URL.
6. Copy the secret deploy-hook URL from Render service Settings. In GitHub, create
   environment `production` and environment secret `RENDER_DEPLOY_HOOK_URL`.
   Do not add required manual reviewers if you want fully automatic deployment.
7. Push a main-branch change. Actions compiles frontend, runs Java unit tests,
   builds the production image, starts it against PostgreSQL, checks auth, validates
   API writes/invalid prices and static UI, then requests deployment of that exact SHA.
   Pull requests verify but do not deploy. The hook step confirms acceptance, not
   completed rollout. Check Render Deploys for success and the app health endpoint.

```bash
git init -b main
git add .
git commit -m "KMR Market v1.0 starter"
git remote add origin https://github.com/YOUR_USERNAME/kmr-market.git
git push -u origin main
```

GitHub authentication may use Git Credential Manager or SSH; never embed tokens in
remote URLs. Verify `.env` is ignored before committing (`git status --short`).

## API

Authenticated using HTTP Basic (admin credentials). Public health endpoint only.

| Method | Path | Purpose |
|---|---|---|
| GET | /api/me | Auth check / app version |
| GET/POST | /api/watchlist | List/add symbol |
| DELETE | /api/watchlist/{symbol} | Remove symbol |
| GET/POST | /api/plans | Latest 100/create plan |
| DELETE | /api/plans/{id} | Delete plan |

POST /api/plans example:

```json
{"symbol":"XAUUSD","direction":"BUY","entry":2000,"stopLoss":1990,"target":2020}
```

Symbols are 2–20 uppercase letters/digits/dot/underscore/hyphen. All prices positive,
up to 12 integer digits and 8 decimals. BUY: stop < entry < target;
SELL: target < entry < stop. Deletions are idempotent.

## Backups and extensions

Local backup (WSL):

```bash
mkdir -p backups
docker compose exec -T db pg_dump -U kmr -d kmr > backups/kmr.sql
```

Add `backups/` to ignore (already supplied). For cloud backups use Neon connection
credentials through your PostgreSQL client, never print passwords in logs.
Flyway migrations are forward-only: add V2, V3 etc.; never edit an applied migration.
For rollback deploy the previous compatible commit from Render; a code rollback
will not automatically reverse DB migrations.

External integrations: introduce server-side provider interfaces, timeouts, retry
policy and provider-specific credentials as environment secrets. Select the broker
and supported API before extending execution beyond the supplied demo worker. MT5 terminal bridging requires a
running Windows terminal/bridge; see AUTO_SIGNALS.md for the supplied bridge. Start with demo-account execution,
idempotent order submission, reconciliation, limits and an explicit kill switch.
Do not use free-tier sleep-prone hosting for unattended live orders.

## Optional demo broker execution

See [DEMO_EXECUTION.md](DEMO_EXECUTION.md) for the opt-in Windows worker: exact
0.01 lots, separate limits of five pending and five open per instrument, TP2
take-profit, and TP1 SL protection. Only M1/M5/M15/M30/H1/D1/W1/MN1 are enabled
for new scans and entries. Real broker accounts are rejected. Demo account binding
is configured privately in Render; default account 0 disables execution claims.
The data bridge stays read-only, and the execution worker must be started
separately with --execute-demo.

## Local orders, multiple targets and P&L (v1.1)

See [LOCAL_MT5_V1_1.md](LOCAL_MT5_V1_1.md) for manual pending-order queueing,
automatic three-target setups, broker cancellation/fill synchronization, daily
net P&L, the complete PostgreSQL deal/event tracker, persistent browser sessions
and optional Windows sign-in startup. The updated worker is required after
deploying the v2 execution API. Existing orders keep their original targets.
