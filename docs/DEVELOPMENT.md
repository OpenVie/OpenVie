# Local development

[Documentation index](README.md) · [Project overview](../README.md) · [Deployment](DEPLOYMENT.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#hướng-dẫn-phát-triển-cục-bộ--tiếng-việt) · [English](#local-development-guide-1)

---

## Hướng dẫn phát triển cục bộ — Tiếng Việt

Tài liệu này hướng dẫn chạy toàn bộ hạ tầng trong Docker và 3 ứng dụng chính (Java, Python, Web) trực tiếp trên máy chủ phát triển (host).

### 1. Yêu cầu tiên quyết

| Công cụ | Yêu cầu |
| --- | --- |
| **Hệ điều hành / Shell** | Linux, macOS, hoặc Windows qua WSL2 (sử dụng POSIX shell và GNU Make) |
| **Docker** | Docker Engine + Compose plugin (Linux) hoặc Docker Desktop (macOS, Windows WSL2) |
| **Java** | JDK 21 (API đã tích hợp sẵn Maven Wrapper) |
| **Python** | Python 3.11 hoặc 3.12 |
| **Node.js** | Node.js 22 LTS kèm npm |
| **Ollama** | Cài đặt trực tiếp trên host để tận dụng GPU/Metal |
| **TEI Reranker (tùy chọn)** | Chạy qua Docker (Linux/WSL2) hoặc nhị phân native Metal (macOS) |

### 2. Các bước thiết lập

#### Bước 1: Chuẩn bị tệp cấu hình
Khởi tạo cấu hình từ các file mẫu mà không ghi đè cấu hình hiện có:
```bash
test -f .env || cp .env.example .env
test -f api/.env || cp api/.env.example api/.env
test -f rag-chatbot-fastapi/.env || cp rag-chatbot-fastapi/.env.example rag-chatbot-fastapi/.env
test -f frontend/.env.local || cp frontend/.env.example frontend/.env.local
```

#### Bước 2: Cài đặt thư viện phụ thuộc
```bash
make -C api deps
make -C rag-chatbot-fastapi setup PYTHON=python3.11
npm --prefix frontend ci
```

#### Bước 3: Khởi động hạ tầng Docker
```bash
make -C rag-chatbot-fastapi dev-infra
docker compose ps
```
*Dịch vụ khởi động:* PostgreSQL (15432), Redis (16379), RabbitMQ (15673/25672), Qdrant (16333), Kuzu Graph (8010), SeaweedFS S3/Filer (18333/18888).

#### Bước 4: Chuẩn bị mô hình Ollama
Cài đặt Ollama trên host và tải các mô hình tiêu chuẩn:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
ollama rm hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
```

*Kích hoạt xử lý song song và ghim bộ nhớ:*
- Đặt biến môi trường `OLLAMA_NUM_PARALLEL=4` và `OLLAMA_MAX_LOADED_MODELS=3` trong service manager của hệ điều hành.
- Làm ấm (warm-up) mô hình:
```bash
curl -s -o /dev/null http://127.0.0.1:11434/api/generate -d '{"model":"vylinh","keep_alive":-1}'
curl -s -o /dev/null http://127.0.0.1:11434/api/embed -d '{"model":"bge-m3","input":["warmup"],"keep_alive":-1}'
```

#### Bước 5: Di chuyển cơ sở dữ liệu (Flyway Migration)
```bash
make -C api migrate
```

#### Bước 6: Khởi chạy 3 ứng dụng (mở 3 terminal riêng)
```bash
# Terminal 1: Spring Boot API (:8080)
make -C api dev

# Terminal 2: Python AI Service (:18000, gRPC :50051)
make -C rag-chatbot-fastapi dev PYTHON=python3.11

# Terminal 3: Next.js Frontend (:3000)
npm --prefix frontend run dev
```

### 3. Thiết lập lần đầu và khôi phục tài khoản

1. Truy cập `http://localhost:3000` trên trình duyệt. Hệ thống tự động chuyển hướng tới `/setup`.
2. Điền tên tổ chức, họ tên, email và mật khẩu (tối thiểu 12 ký tự) để tạo tài khoản Chủ sở hữu (`ORG_OWNER`) và workspace mặc định.
3. Nếu quên mật khẩu `ORG_OWNER`, đặt lại qua lệnh ngoại tuyến:
```bash
make -C api recover-owner RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=MatKhauMoiCucKyBaoMat123"
```

### 4. Xếp hạng lại cục bộ (TEI Reranker - Tùy chọn)

- **macOS (Apple Silicon):**
  ```bash
  brew install text-embeddings-inference
  make -C rag-chatbot-fastapi dev-reranker-native
  ```
- **Linux/WSL2:**
  ```bash
  make -C rag-chatbot-fastapi dev-reranker
  ```
Khi chạy `make dev` trong Python, `DEV_RERANKER_ENABLED=true` được bật theo mặc định trên cổng `127.0.0.1:8082`. Nếu không dùng TEI, tắt bằng `DEV_RERANKER_ENABLED=false`.

### 5. Kiểm tra và xác minh (Verification)

| Lĩnh vực | Lệnh kiểm tra |
| --- | --- |
| **Java API** | `make -C api test` hoặc `make -C api verify` |
| **Python AI** | `make -C rag-chatbot-fastapi check PYTHON=python3.11` |
| **Web Frontend** | `npm --prefix frontend run lint` và `npm --prefix frontend run typecheck` |

### 6. Xử lý sự cố thường gặp

- **Cổng bị chiếm (Address already in use):** Kiểm tra tiến trình ngầm bằng `lsof -i :18000` hoặc `lsof -i :50051` và đóng chúng trước khi chạy `make dev`.
- **Lỗi kết nối Postgres/Redis:** Đảm bảo các dịch vụ container đang chạy qua `docker compose ps`. File `.env` của service phải trỏ vào `localhost`, không dùng tên DNS nội bộ Docker như trong root `.env`.
- **Dừng hệ thống giữ nguyên dữ liệu:** Nhấn `Ctrl-C` ở các terminal ứng dụng, sau đó hạ container bằng `make -C rag-chatbot-fastapi dev-down`. Tránh dùng lệnh `docker compose down -v`.

### 7. Phân biệt với môi trường sản xuất (Development vs Production)

- **Phát triển (Local Dev):** Dùng `docker-compose.yml` (hoặc `make dev-infra`) để chạy hạ tầng dữ liệu; mã nguồn Java, Python, Web chạy trực tiếp trên host để hỗ trợ live-reload và debug.
- **Sản xuất (Production):** Dùng `docker-compose.prod.yml` để đóng gói và chạy trọn gói 100% ứng dụng trong container khép kín. Chi tiết xem [DEPLOYMENT.md](DEPLOYMENT.md).

---

# Local development guide

This guide covers running infrastructure in Docker and the three application tiers on the host machine.

## 1. Prerequisites

| Tool | Requirement |
| --- | --- |
| **OS / Shell** | Linux, macOS, or Windows via WSL2 (POSIX shell with GNU Make) |
| **Docker** | Docker Engine + Compose plugin (Linux) or Docker Desktop (macOS, Windows WSL2) |
| **Java** | JDK 21 (bundled Maven wrapper included) |
| **Python** | Python 3.11 or 3.12 |
| **Node.js** | Node.js 22 LTS with npm |
| **Ollama** | Native host installation for GPU/Metal hardware acceleration |
| **TEI Reranker (optional)** | Docker container (Linux/WSL2) or native Homebrew binary (Apple Silicon) |

## 2. Step-by-step setup

### Step 1: Copy configuration files
Initialize environment files without overwriting existing ones:
```bash
test -f .env || cp .env.example .env
test -f api/.env || cp api/.env.example api/.env
test -f rag-chatbot-fastapi/.env || cp rag-chatbot-fastapi/.env.example rag-chatbot-fastapi/.env
test -f frontend/.env.local || cp frontend/.env.example frontend/.env.local
```

### Step 2: Install dependencies
```bash
make -C api deps
make -C rag-chatbot-fastapi setup PYTHON=python3.11
npm --prefix frontend ci
```

### Step 3: Start infrastructure containers
```bash
make -C rag-chatbot-fastapi dev-infra
docker compose ps
```
*Services started:* PostgreSQL (15432), Redis (16379), RabbitMQ (15673/25672), Qdrant (16333), Kuzu Graph (8010), SeaweedFS (18333/18888).

### Step 4: Prepare models in Ollama
Install Ollama natively and download standard weights:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
ollama rm hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
```

*Enable parallelism and warm memory:*
- Set `OLLAMA_NUM_PARALLEL=4` and `OLLAMA_MAX_LOADED_MODELS=3` in the platform service manager.
- Pin resident weights:
```bash
curl -s -o /dev/null http://127.0.0.1:11434/api/generate -d '{"model":"vylinh","keep_alive":-1}'
curl -s -o /dev/null http://127.0.0.1:11434/api/embed -d '{"model":"bge-m3","input":["warmup"],"keep_alive":-1}'
```

### Step 5: Database migrations
```bash
make -C api migrate
```

### Step 6: Start applications (separate terminals)
```bash
# Terminal 1: Spring Boot business API (:8080)
make -C api dev

# Terminal 2: Python AI service (:18000, gRPC :50051)
make -C rag-chatbot-fastapi dev PYTHON=python3.11

# Terminal 3: Next.js frontend (:3000)
npm --prefix frontend run dev
```

## 3. First-run setup and owner recovery

1. Navigate to `http://localhost:3000`. The client detects an uninitialized instance and routes to `/setup`.
2. Fill out organization name, owner credentials, and password (≥12 chars) to initialize `ORG_OWNER` and default workspace.
3. Offline recovery command:
```bash
make -C api recover-owner RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=YourSecurePassword123"
```

## 4. Optional local reranker

- **macOS (Apple Silicon):**
  ```bash
  brew install text-embeddings-inference
  make -C rag-chatbot-fastapi dev-reranker-native
  ```
- **Linux / WSL2:**
  ```bash
  make -C rag-chatbot-fastapi dev-reranker
  ```
Runs on `127.0.0.1:8082`. Controlled via `DEV_RERANKER_ENABLED` in `make dev`.

## 5. Verification

| Area | Command |
| --- | --- |
| **Java** | `make -C api test` or `make -C api verify` |
| **Python** | `make -C rag-chatbot-fastapi check PYTHON=python3.11` |
| **Frontend** | `npm --prefix frontend run lint` && `npm --prefix frontend run typecheck` |

## 6. Troubleshooting

- **Port conflicts:** Terminate existing processes on 18000 or 50051 before starting `make dev`.
- **Database connectivity:** Check container health via `docker compose ps`. Service-level `.env` files must reference `localhost` ports rather than container names.
- **Graceful shutdown:** Stop host applications with `Ctrl-C`, then bring down containers with `make -C rag-chatbot-fastapi dev-down`. Avoid `docker compose down -v` to prevent volume loss.

## 7. Development vs Production

- **Development:** `docker-compose.yml` (via `make dev-infra`) runs stateful databases only; applications run directly on the host with live reload.
- **Production:** `docker-compose.prod.yml` containerizes the entire stack (data, backend, AI, frontend, APISIX) in a single deployment. See [DEPLOYMENT.md](DEPLOYMENT.md).
