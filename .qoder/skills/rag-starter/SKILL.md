---
name: rag-starter
description: One-click startup for the Political Economy RAG QA system (Docker infra + Spring Boot backend + Vue3 frontend). Handles Docker daemon recovery, MySQL volume corruption, port conflicts, and env-var injection automatically. Use when the user says "start the RAG system", "launch the app", "一键启动", "/rag-starter", or wants to bring the full stack online.
---

# RAG Starter — 一键启动

## Prerequisites

Verify before starting:

```bash
ls /Users/mj/Desktop/Vibe_Coding/docker-compose.yml
ls /Users/mj/Desktop/Vibe_Coding/backend/pom.xml
ls /Users/mj/Desktop/Vibe_Coding/frontend/package.json
```

## Startup Workflow

Execute steps in order. Use the checklist to track progress:

```
Progress:
- [ ] Step 1: Ensure Docker daemon running
- [ ] Step 2: Start infrastructure (docker compose up -d)
- [ ] Step 3: Wait for all 5 containers healthy, fix MySQL if needed
- [ ] Step 4: Start backend (Spring Boot on 8081)
- [ ] Step 5: Start frontend (Vite on 5173)
- [ ] Step 6: Verify end-to-end
```

### Step 1 — Docker Daemon

Check if Docker is reachable:

```bash
docker ps >/dev/null 2>&1 && echo "Docker OK" || echo "Docker down"
```

If not running, start Docker Desktop and poll until ready:

```bash
open -a Docker
for i in $(seq 1 30); do
  if docker ps >/dev/null 2>&1; then echo "Docker ready after ${i}0s"; break; fi
  sleep 10
done
```

### Step 2 — Start Infrastructure

```bash
cd /Users/mj/Desktop/Vibe_Coding
docker compose up -d
```

This launches 5 containers: rag-mysql, rag-redis, rag-milvus, rag-milvus-etcd, rag-milvus-minio.

### Step 3 — Wait for Healthy + MySQL Recovery

Poll health status (up to 20 × 6s = 2 min):

```bash
for i in $(seq 1 20); do
  unhealthy=$(docker ps --filter "name=rag-" --filter "health=unhealthy" --format "{{.Names}}" 2>/dev/null)
  if [ -z "$unhealthy" ]; then
    all_healthy=$(docker ps --filter "name=rag-" --filter "health=healthy" --format "{{.Names}}" | wc -l | tr -d ' ')
    if [ "$all_healthy" -ge 5 ]; then echo "All 5 healthy after ${i}x6s"; break; fi
  fi
  sleep 6
done
docker ps --filter "name=rag-" --format "{{.Names}} | {{.Status}}"
```

**⚠️ MySQL restart-loops (data corruption)**: If rag-mysql keeps restarting, check logs:

```bash
docker logs rag-mysql --tail 20 2>&1 | grep -i "corrupt\|redo log\|DD Storage"
```

If you see `redo log files…corrupt` or `DD Storage Engine` errors, the volume was corrupted by an unclean Docker shutdown. Since this is a fresh dev environment, delete the volume and recreate:

```bash
docker compose stop mysql
docker compose rm -f mysql
docker volume rm vibe_coding_mysql_data
docker compose up -d mysql
```

Then wait for healthy as above. Flyway migrations will re-seed the schema on first backend startup.

### Step 4 — Start Backend (Spring Boot, port 8081)

**Critical environment variables** — inject before launch:

| Variable | Value |
|----------|-------|
| `DEEPSEEK_API_KEY` | `sk-...` (your DeepSeek key) |
| `DASHSCOPE_API_KEY` | `sk-...` (your Aliyun DashScope key) |
| `REDIS_PORT` | `6380` (avoids conflict with host Redis on 6379) |
| `SERVER_PORT` | `8081` (avoids conflict with know-engine-xxl-job on 8080) |

```bash
cd /Users/mj/Desktop/Vibe_Coding/backend
export DEEPSEEK_API_KEY=sk-...   # your DeepSeek key
export DASHSCOPE_API_KEY=sk-...  # your Aliyun DashScope key
export REDIS_PORT=6380
export SERVER_PORT=8081
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xms512m -Xmx1g" > /tmp/rag-backend.log 2>&1 &
```

Wait for "Started RagApplication" (~45s for first run with compilation):

```bash
sleep 45
grep -q "Started RagApplication" /tmp/rag-backend.log && echo "Backend ready" || tail -30 /tmp/rag-backend.log
```

### Step 5 — Start Frontend (Vite, port 5173)

**CRITICAL**: Use `is_background=true` for the Bash tool when starting Vite. Do NOT use shell `&` — the tool's shell session ends and sends SIGSTOP, freezing the Vite process (STAT `TN`). The port will appear occupied but no requests are served.

Run with `is_background=true`:
```bash
cd /Users/mj/Desktop/Vibe_Coding/frontend
npm run dev
```

Wait for Vite to print "Local:" (~5s), then verify the process is `S+` (running), not `TN` (suspended):
```bash
sleep 5
pid=$(lsof -ti :5173 2>/dev/null)
stat=$(ps -p $pid -o stat= 2>/dev/null | tr -d ' ')
if [[ "$stat" == S* ]]; then echo "Frontend ready (STAT=$stat)"; else echo "WARNING: Vite is $stat — kill and restart with is_background=true"; fi
```

### Step 6 — Verify

Use `--max-time` to prevent hanging on frozen processes:

```bash
# Frontend page
curl -s --max-time 10 -o /dev/null -w "Frontend: %{http_code}\n" http://localhost:5173/

# Backend via proxy (also confirms auth chain works)
curl -s --max-time 10 -w "\nAPI-proxy: %{http_code}\n" -X POST http://localhost:5173/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

Both should return `200`. If either returns `000` (timeout) despite port being in use, the process was frozen by shell job control — see Troubleshooting Error 8.

## Quick Verification Commands

| Check | Command |
|-------|---------|
| Container health | `docker ps --filter "name=rag-" --format "{{.Names}} {{.Status}}"` |
| Backend log tail | `tail -20 /tmp/rag-backend.log` |
| Frontend log tail | `tail -5 /tmp/rag-frontend.log` |
| Stop all | `kill $(lsof -ti :8081) 2>/dev/null; kill $(lsof -ti :5173) 2>/dev/null` |

## Post-Startup Info

| Service | URL |
|---------|-----|
| Frontend | http://localhost:5173 |
| Backend API | http://localhost:8081 |
| API Docs (Knife4j) | http://localhost:8081/doc.html |
| Admin account | admin / 123456 |

## Troubleshooting Reference

For detailed error patterns and fixes, see [troubleshooting.md](troubleshooting.md).
