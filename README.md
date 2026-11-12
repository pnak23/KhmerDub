# KhmerDub AI (v0.1.0)

Personal, local-first app that turns foreign-language movies into Khmer-dubbed movies with Khmer subtitles.
Status: **M1 (foundation)**. The services, database schema and health checks work. Movie processing starts in M2.

## Layout

```
apps/web    Nuxt 4 frontend (http://localhost:3000)
apps/api    Spring Boot 4.1 API (Java 21), owns all project/job state (127.0.0.1:8180)
apps/ai     FastAPI service (127.0.0.1:8765) + Celery tasks (AI and media processing)
infra/      docker-compose: PostgreSQL 16 (127.0.0.1:5442), Redis 7 (127.0.0.1:6380), Linux Celery worker
scripts/    dev.ps1: start / stop / status
```

Media and generated files live **outside the repo** in `D:\KhmerDub\data` (`KHMERDUB_STORAGE_ROOT`).
FFmpeg is the portable build in `D:\KhmerDub\tools\ffmpeg` (not on PATH).

## Prerequisites (already on this PC)

Docker Desktop, JDK 21 (`%USERPROFILE%\.jdks\corretto-21.0.6`, set by the script for its own processes only),
Python 3.13 (non-free-threaded `python.exe`), Node 24 + pnpm.

## First-time setup

```powershell
Copy-Item .env.example .env        # then set KHMERDUB_INTERNAL_TOKEN and POSTGRES_PASSWORD to long random values
& "$env:LOCALAPPDATA\Programs\Python\Python313\python.exe" -m venv apps\ai\.venv   # do NOT use `py` (defaults to 3.13t)
apps\ai\.venv\Scripts\python.exe -m pip install -e "apps/ai[dev]"
pnpm --dir apps/web install
docker compose -f infra/docker-compose.yml --env-file .env --profile linux-worker build worker-cpu
```

## Run

```powershell
.\scripts\dev.ps1 start            # PostgreSQL, Redis, API, AI service, web app, Linux worker
.\scripts\dev.ps1 status
.\scripts\dev.ps1 stop             # add -All to also stop PostgreSQL and Redis
```

Docker Desktop must be running first. Logs: `D:\KhmerDub\data\logs`. After changing Python code, restart the
worker: `docker compose -f infra/docker-compose.yml --env-file .env --profile linux-worker restart worker-cpu`.

Fallback without the container: `.\scripts\dev.ps1 start -Worker windows` runs a native Windows worker (`solo` pool:
one task at a time, no crash isolation, ignores control commands while busy).

## Tests

```powershell
cd apps/api; .\mvnw.cmd test        # needs JAVA_HOME = JDK 21 and Docker (Testcontainers)
apps\ai\.venv\Scripts\python.exe -m pytest apps/ai
pnpm --dir apps/web test; pnpm --dir apps/web typecheck
```

## Privacy

Cloud processing is **off** by default (`KHMERDUB_CLOUD_ENABLED=false`). API keys live only in `.env`,
which git ignores, and are never sent to the browser. All services bind to `127.0.0.1`.
