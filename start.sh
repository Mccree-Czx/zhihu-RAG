#!/bin/bash
# ============================================
# RAG Knowledge Base QA System - Quick Start
# ============================================

echo "=== 政治经济学RAG知识库问答系统 ==="
echo ""

# Step 1: Start infrastructure
echo "[1/3] Starting MySQL, Redis, Milvus..."
cd "$(dirname "$0")"
docker compose up -d mysql redis etcd minio milvus
echo "Waiting for services to be healthy..."
sleep 15

# Step 2: Start backend
echo "[2/3] Building and starting backend..."
cd backend
# Inject API keys & ports (override via your shell env if desired)
export DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY:-sk-placeholder}
export DASHSCOPE_API_KEY=${DASHSCOPE_API_KEY:-sk-placeholder}
export JWT_SECRET=${JWT_SECRET:-change-me-base64-256bit-secret-key}
export REDIS_PORT=${REDIS_PORT:-6380}
export SERVER_PORT=${SERVER_PORT:-8081}
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xms512m -Xmx1g" &
BACKEND_PID=$!
echo "Backend PID: $BACKEND_PID (http://localhost:${SERVER_PORT})"
cd ..

# Step 3: Start frontend
echo "[3/3] Starting frontend..."
cd frontend
npm run dev &
FRONTEND_PID=$!
echo "Frontend PID: $FRONTEND_PID (http://localhost:5173)"
cd ..

echo ""
echo "=== 系统启动完成 ==="
echo "前端地址: http://localhost:5173"
echo "后端接口: http://localhost:${SERVER_PORT:-8081}"
echo "API文档:   http://localhost:${SERVER_PORT:-8081}/doc.html"
echo "管理员账户: admin / 123456"
echo ""
echo "按 Ctrl+C 停止所有服务"
wait
