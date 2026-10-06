# Self-hosted installation

[Documentation index](README.md) · [Development](DEVELOPMENT.md) · [Architecture](ARCHITECTURE.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#hướng-dẫn-triển-khai-tự-lưu-trữ--tiếng-việt) · [English](#self-hosted-installation-guide-1)

---

## Hướng dẫn triển khai tự lưu trữ — Tiếng Việt

Tài liệu này hướng dẫn người vận hành cài đặt và vận hành OpenVie trên máy chủ production độc lập.

### 1. Phân định phạm vi cung cấp

- **Được cung cấp:** `docker-compose.yml` (Postgres 16, Redis 7, RabbitMQ, Qdrant, SeaweedFS, Kuzu Graph, Apache APISIX Gateway), mã nguồn Spring Boot API, Python AI Service, Next.js Web Console, và file mẫu `.env.production.example`.
- **Trách nhiệm của người vận hành:** Cấu hình chứng chỉ SSL/TLS (HTTPS) phía trước Gateway, WAF, kiểm dịch mã độc tệp tải lên, hệ thống sao lưu/phục hồi tự động, và giám sát hạ tầng.

### 2. Yêu cầu tiên quyết

- Máy chủ Linux (Ubuntu 22.04+ khuyến nghị) hoặc macOS/Windows WSL2.
- Docker kèm Compose plugin, JDK 21, Python 3.11/3.12, Node.js 22 LTS, Make, OpenSSL.
- Dung lượng đĩa đủ chứa các checkpoint model Ollama (`bge-m3`, `vylinh`) và chỉ mục vector.

### 3. Chuẩn bị cấu hình sản xuất

Tạo file `.env.production` bảo mật từ mẫu có sẵn:
```bash
cp .env.production.example .env.production
chmod 600 .env.production
```
Tạo các khóa bí mật ngẫu nhiên bằng `openssl rand -hex 32` cho:
`TOKEN_KEY`, `NOTIFICATION_ENC_KEY` (base64: `openssl rand -base64 32`), `GRAPH_INTERNAL_TOKEN`, `POSTGRES_PASSWORD`, `RABBITMQ_DEFAULT_PASS`.

*Các thiết lập quan trọng:*
- `CORS_ORIGINS`, `INVITATION_LINK`, và các biến `NEXT_PUBLIC_*` phải trỏ đúng domain/IP người dùng truy cập.
- Đồng bộ thông số sinh phản hồi: `LLM_MAX_OUTPUT_TOKENS=512`, `GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS=512`.
- Kiểm tra tính hợp lệ của compose:
```bash
docker compose --env-file .env.production config --quiet
```

### 4. Khởi động hạ tầng và dịch vụ mô hình

Khởi động các dịch vụ lưu trữ nền tảng:
```bash
docker compose --env-file .env.production up -d --wait
```

Cài đặt và cấu hình Ollama trực tiếp trên host (systemd trên Linux, launchd trên macOS):
```ini
# Thiết lập song song trong service manager của OS:
Environment="OLLAMA_NUM_PARALLEL=4"
Environment="OLLAMA_MAX_LOADED_MODELS=3"
```
Tải và ghim mô hình trong bộ nhớ:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
curl -s -o /dev/null http://127.0.0.1:11434/api/generate -d '{"model":"vylinh","keep_alive":-1}'
curl -s -o /dev/null http://127.0.0.1:11434/api/embed -d '{"model":"bge-m3","input":["warmup"],"keep_alive":-1}'
```

*Tùy chọn TEI Reranker (`RERANKER_ENABLED=true`):*
```bash
# Linux x86_64:
docker compose --env-file .env.production --profile reranker up -d reranker-service
```

### 5. Cài đặt và vận hành ứng dụng

Nạp biến môi trường cho shell hiện tại:
```bash
set -a; . ./.env.production; set +a
```

Khởi chạy ứng dụng (nên dùng systemd supervisor):
```bash
# 1. Python AI service
make -C rag-chatbot-fastapi setup PYTHON=python3.11
make -C rag-chatbot-fastapi run PYTHON=python3.11

# 2. Spring Boot API
make -C api migrate ENV_FILE=../.env.production
make -C api run-prod ENV_FILE=../.env.production

# 3. Web Console
npm --prefix frontend ci
npm --prefix frontend run build
npm --prefix frontend run start
```

### 6. Thiết lập lần đầu và khôi phục chủ sở hữu

1. Mở `http://your-domain` (hoặc cổng APISIX 8088). Hệ thống chuyển hướng tới `/setup`.
2. Tạo tài khoản `ORG_OWNER` ban đầu và workspace mặc định. Sau khi tạo, `/setup` tự động đóng (HTTP 404).
3. Đặt lại mật khẩu owner khẩn cấp không cần can thiệp SQL:
```bash
make -C api recover-owner ENV_FILE=../.env.production RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=MatKhauMoi123456"
```

### 7. Kiểm tra xác minh trạng thái (Health Checks)

```bash
docker compose --env-file .env.production ps
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:18000/health/ready
curl --fail http://localhost:8010/health/ready
curl --fail http://127.0.0.1:8088/apisix/status
```

### 8. Cổng biên Apache APISIX (Edge Gateway)

APISIX chạy ở chế độ standalone trên cổng `8088`:
- `/apisix/status`: Probe sức khỏe nội bộ.
- `/api/v1/auth/*`: Trỏ tới Spring Boot (`:8080`), giới hạn 5 r/s (burst 10).
- `/api/v1/*`: Trỏ tới Spring Boot (`:8080`), giới hạn 50 r/s (burst 100).
- `/*`: Trỏ tới Web client Next.js (`:3000`).
*Lưu ý:* Các cổng gRPC (`:50051`), Qdrant, Redis, Postgres không được định tuyến qua gateway này.

### 9. Vận hành, sao lưu và cập nhật

- **Sao lưu:** Cần lên lịch sao lưu tự động định kỳ cho PostgreSQL (pg_dump), SeaweedFS volumes, và `.env.production`.
- **Cập nhật & Tái lập chỉ mục:** Khi logic trích xuất đồ thị thay đổi, kích hoạt đánh chỉ mục lại toàn bộ qua `DocumentReindexCommand` của Spring Boot với cấu hình `app.maintenance.reindex.enabled=true`.

---

# Self-hosted installation guide

This guide covers deploying OpenVie on an operator-controlled production server.

## 1. Scope and boundaries

- **Included:** `docker-compose.yml` (Postgres 16, Redis 7, RabbitMQ, Qdrant, SeaweedFS, Kuzu Graph, Apache APISIX), Spring Boot API, Python AI Service, Next.js frontend, and `.env.production.example`.
- **Operator-owned:** TLS termination (HTTPS), public DNS, external WAF, upload antivirus scanning, backup/restore schedules, and systemd process supervision.

## 2. Prerequisites

- Linux host (Ubuntu 22.04+ recommended) or macOS/WSL2.
- Docker with Compose plugin, JDK 21, Python 3.11/3.12, Node.js 22 LTS, Make, OpenSSL.
- Sufficient disk space for model weights (`vylinh`, `bge-m3`) and vector indexes.

## 3. Configuration preparation

Copy and restrict the production configuration template:
```bash
cp .env.production.example .env.production
chmod 600 .env.production
```
Generate unique 32-byte cryptographic secrets via `openssl rand -hex 32` for:
`TOKEN_KEY`, `NOTIFICATION_ENC_KEY` (base64: `openssl rand -base64 32`), `GRAPH_INTERNAL_TOKEN`, `POSTGRES_PASSWORD`, `RABBITMQ_DEFAULT_PASS`.

Ensure `CORS_ORIGINS`, `INVITATION_LINK`, and `NEXT_PUBLIC_*` match your public domain. Align generation budgets: `LLM_MAX_OUTPUT_TOKENS=512`, `GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS=512`.

Validate compose structure:
```bash
docker compose --env-file .env.production config --quiet
```

## 4. Infrastructure and model service

Start stateful infrastructure services:
```bash
docker compose --env-file .env.production up -d --wait
```

Install Ollama natively on the host and configure parallel processing:
```ini
# Add to host service manager environment:
Environment="OLLAMA_NUM_PARALLEL=4"
Environment="OLLAMA_MAX_LOADED_MODELS=3"
```
Download and pin model checkpoints in memory:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
curl -s -o /dev/null http://127.0.0.1:11434/api/generate -d '{"model":"vylinh","keep_alive":-1}'
curl -s -o /dev/null http://127.0.0.1:11434/api/embed -d '{"model":"bge-m3","input":["warmup"],"keep_alive":-1}'
```

*Optional TEI Reranker container:*
```bash
docker compose --env-file .env.production --profile reranker up -d reranker-service
```

## 5. Application deployment

Export configuration to the deployment shell:
```bash
set -a; . ./.env.production; set +a
```

Run services (recommended under systemd supervision):
```bash
# 1. Python AI service
make -C rag-chatbot-fastapi setup PYTHON=python3.11
make -C rag-chatbot-fastapi run PYTHON=python3.11

# 2. Spring Boot API
make -C api migrate ENV_FILE=../.env.production
make -C api run-prod ENV_FILE=../.env.production

# 3. Web Console
npm --prefix frontend ci
npm --prefix frontend run build
npm --prefix frontend run start
```

## 6. First-run setup and owner recovery

1. Navigate to your domain or port 8088. Unclaimed systems route to `/setup`.
2. Register the initial `ORG_OWNER` account and primary workspace. Once created, `/setup` returns HTTP 404 permanently.
3. Offline owner password reset:
```bash
make -C api recover-owner ENV_FILE=../.env.production RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=YourNewPassword123"
```

## 7. Verification health checks

```bash
docker compose --env-file .env.production ps
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:18000/health/ready
curl --fail http://localhost:8010/health/ready
curl --fail http://127.0.0.1:8088/apisix/status
```

## 8. Apache APISIX edge gateway

APISIX operates standalone on port `8088`:
- `/apisix/status`: Gateway health probe.
- `/api/v1/auth/*`: Spring Boot (:8080), rate-limited at 5 r/s (burst 10).
- `/api/v1/*`: Spring Boot (:8080), rate-limited at 50 r/s (burst 100).
- `/*`: Next.js web application (:3000).
*Note:* Internal endpoints (`:50051`, Qdrant, Redis, Postgres) are not routed through the gateway.

## 9. Backup and operations

- **Backups:** Implement off-host backups for PostgreSQL (`pg_dump`), SeaweedFS object volumes, and `.env.production`.
- **Reindexing:** To re-extract knowledge graphs after pipeline updates, trigger Spring's `DocumentReindexCommand` via `app.maintenance.reindex.enabled=true`.
