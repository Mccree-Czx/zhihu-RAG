# RAG Starter — Troubleshooting Reference

Detailed error patterns and their fixes, distilled from actual startup runs.

## Error 1: MySQL restart-loops (InnoDB corruption)

**Symptoms:**
- `docker ps` shows rag-mysql status: `Restarting (1)`
- Logs contain: `[MY-012960] [InnoDB] Cannot create redo log files because data files are corrupt or the database was not shut down cleanly`
- Logs contain: `[MY-010334] [Server] Failed to initialize DD Storage Engine`

**Root cause:** Docker daemon crashed while MySQL was writing, leaving InnoDB redo logs in an inconsistent state. The data volume itself is corrupted.

**Fix (dev environment only — this deletes all MySQL data):**

```bash
cd /Users/mj/Desktop/Vibe_Coding
docker compose stop mysql
docker compose rm -f mysql
docker volume rm vibe_coding_mysql_data
docker compose up -d mysql
# Wait ~20s for fresh init, then verify:
docker inspect --format '{{.State.Health.Status}}' rag-mysql
```

Flyway migrations will re-create all tables and seed data on next backend startup.

## Error 2: Milvus index creation fails (HNSW efConstruction)

**Symptoms:**
- Backend fails at startup with: `Failed to create Index`
- Caused by: `efConstruction out of range: [1, 2147483647]`

**Root cause:** Spring AI MilvusVectorStore default `indexParameters` (`{"nlist":1024}`) is for IVF indexes, not HNSW. When `indexType: HNSW` is configured but no HNSW parameters are given, efConstruction defaults to 0 (invalid).

**Fix:** In `application.yml`, under `spring.ai.vectorstore.milvus`, add:
```yaml
indexParameters: '{"M":16,"efConstruction":64}'
```

Also verify `AiConfig.java` exists to expose `ChatClient` bean (Spring AI 1.0 only provides `ChatClient.Builder`).

## Error 3: ChatClient bean not found

**Symptoms:**
- Backend fails: `Parameter 4 of constructor in ChatService required a bean of type 'org.springframework.ai.chat.client.ChatClient' that could not be found`

**Root cause:** Spring AI 1.0 auto-configures only `ChatClient.Builder`, not `ChatClient`. Constructor injection of `ChatClient` directly fails.

**Fix:** Create `com.pol.rag.config.AiConfig`:
```java
@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

## Error 4: Port 8080 occupied (know-engine-xxl-job)

**Symptoms:**
- `Web server failed to start. Port 8080 was already in use.`
- `lsof -nP -iTCP:8080` shows a `com.docke` process (another project's container)

**Root cause:** The host already has a container (`know-engine-xxl-job`) bound to port 8080 from a different project.

**Fix:** DO NOT stop the other container. Reconfigure this project to use port 8081:
- `application.yml`: `server.port: ${SERVER_PORT:8081}`
- `vite.config.js`: proxy target → `http://localhost:8081`
- Inject `export SERVER_PORT=8081` before starting backend

## Error 5: Port 6379 occupied (host Redis)

**Symptoms:**
- `docker compose up -d` fails: port 6379 already in use

**Root cause:** The macOS host already runs a Redis instance (e.g., via `com.docke`) on port 6379.

**Fix:**
- In `docker-compose.yml`, change Redis port mapping: `"6380:6379"`
- In `application.yml`, change Redis port: `port: ${REDIS_PORT:6380}`
- Inject `export REDIS_PORT=6380` before starting backend

## Error 6: Docker daemon not running

**Symptoms:**
- `Cannot connect to the Docker daemon at unix:///Users/mj/.docker/run/docker.sock`

**Root cause:** Docker Desktop was not launched, or crashed after a system event.

**Fix:**
```bash
open -a Docker
# Poll until ready (may take 10-30s):
for i in $(seq 1 30); do
  if docker ps >/dev/null 2>&1; then echo "ready"; break; fi
  sleep 10
done
```

Note: After Docker Desktop restarts, existing containers usually auto-start. Run `docker compose up -d` again to ensure all 5 are present.

## Error 7: Backend fails silently (check logs first)

Always check `/tmp/rag-backend.log` before retrying:
```bash
grep -i "error\|exception\|failed\|BUILD FAILURE" /tmp/rag-backend.log | tail -10
```

Common patterns:
- `BUILD SUCCESS` + `Started RagApplication` → OK
- `APPLICATION FAILED TO START` → read the Description block
- `Connection refused` → MySQL/Redis/Milvus not yet healthy

## Error 8: Vite process suspended — port occupied but no response

**Symptoms:**
- `curl http://localhost:5173/` hangs and eventually times out (HTTP 000)
- `lsof -ti :5173` shows a PID, but `ps -p PID -o stat` shows `TN` (Stopped)
- The port appears "in use" but serves no requests

**Root cause:** Starting Vite with shell background syntax (`npm run dev &`) causes the process to receive SIGSTOP when the Bash tool's shell session ends. The Vite process remains bound to port 5173 but is frozen and unable to respond.

**Diagnosis:**
```bash
pid=$(lsof -ti :5173 2>/dev/null)
ps -p $pid -o pid,stat,command 2>/dev/null | head -3
```
If `STAT` is `TN` or `T`, the process is suspended.

**Fix:**
1. Kill the suspended process: `kill -9 $(lsof -ti :5173)`
2. Restart Vite using `is_background=true` for the Bash tool (NOT shell `&`):
```bash
cd /Users/mj/Desktop/Vibe_Coding/frontend
npm run dev
# (run with is_background=true in the Bash tool)
```
3. Verify the new process is in `S+` state:
```bash
pid=$(lsof -ti :5173 2>/dev/null)
ps -p $pid -o stat= | tr -d ' '
# Should show S+ (running foreground), not TN
```
