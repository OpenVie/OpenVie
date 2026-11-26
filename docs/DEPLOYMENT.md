# Self-hosted installation

[Documentation index](README.md) · [Development](DEVELOPMENT.md) · [Architecture](ARCHITECTURE.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#hướng-dẫn-tự-triển-khai--tiếng-việt) · [English](#self-hosted-installation-guide)

---

## Hướng dẫn tự triển khai — Tiếng Việt

Tài liệu này hướng dẫn người vận hành cài đặt và vận hành OpenVie trên một máy chủ production độc lập.

### 1. Phạm vi có sẵn

- **Có sẵn:**
  - `docker-compose.prod.yml`: stack production trọn gói cho toàn bộ nền tảng (Postgres, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu Graph, Spring Boot API, Python AI Service, Next.js Web Console và Apache APISIX Gateway).
  - `docker-compose.yml`: chỉ chạy hạ tầng dữ liệu (dùng cho phát triển hoặc mô hình hybrid chạy app trên host).
  - Tệp cấu hình mẫu `.env.production.example` và route gateway `apisix/routes.prod.yaml`.
  - Bộ công cụ sao lưu/phục hồi có kiểm tra toàn vẹn SHA-256 (`scripts/ops/backup.sh` & `restore.sh`).
  - Stack giám sát hệ thống và AI (Prometheus, Alertmanager, Grafana kèm 2 dashboard) qua profile `monitoring`.
  - Lakehouse Parquet nén Snappy và engine DuckDB OLAP chạy trực tiếp trên S3.
- **Trách nhiệm của người vận hành:** cấu hình chứng chỉ SSL/TLS (HTTPS) phía trước gateway, đặt lịch chạy sao lưu định kỳ (crontab/systemd timer), WAF bên ngoài và kiểm dịch mã độc cho tệp tải lên.

### 2. Yêu cầu tiên quyết

- Máy chủ Linux (khuyến nghị Ubuntu 22.04+) hoặc macOS. Trên **Windows 10/11**: dùng Docker
  Desktop với backend WSL2 và chạy mọi lệnh compose/make/openssl từ terminal Ubuntu WSL2;
  triển khai quy mô lớn nên chạy trên máy Linux native (Docker Desktop giới hạn I/O và tài
  nguyên cho workload nặng).
- Docker kèm Compose plugin, JDK 21, Python 3.11/3.12, Node.js 22 LTS, Make và OpenSSL. Trên
  Windows, các công cụ CLI này phải nằm **trong distro WSL2** — PowerShell không có sẵn.
- Đủ dung lượng đĩa cho checkpoint model Ollama (`bge-m3`, `vylinh`) và chỉ mục vector.

### 3. Chuẩn bị cấu hình sản xuất

Tạo tệp `.env.production` từ mẫu và giới hạn quyền đọc:
```bash
cp .env.production.example .env.production
chmod 600 .env.production
```
*(Trên Windows, chạy bước này trong terminal Ubuntu WSL2 — PowerShell không có sẵn `openssl`
và `chmod`.)*
Tạo secret ngẫu nhiên bằng `openssl rand -hex 32` cho:
`TOKEN_KEY`, `NOTIFICATION_ENC_KEY` (base64: `openssl rand -base64 32`), `GRAPH_INTERNAL_TOKEN`, `POSTGRES_PASSWORD`, `RABBITMQ_DEFAULT_PASS`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_DB_PASSWORD`.

*Secret và cấu hình SSO/Keycloak:*
- `KEYCLOAK_ADMIN_PASSWORD`: Mật khẩu admin console của Keycloak (tối thiểu 16 ký tự ngẫu nhiên).
- `KEYCLOAK_DB_PASSWORD`: Mật khẩu database PostgreSQL riêng của Keycloak.
- `SSO_BACKEND_CLIENT_SECRET`: Để dành cho client-credentials của `openvie-backend` sau này (service account trong realm export đang tắt). Luồng SSO hiện tại dùng public client + PKCE nên có thể để trống; nếu sau này bật, lấy secret từ Keycloak Admin Console và không commit.
- `SSO_FRONTEND_CLIENT_ID` và `SSO_ADMIN_CLIENT_ID`: Client id công khai cho ứng dụng chính và giao diện quản trị (mặc định `openvie-frontend` / `openvie-admin`).
- `KEYCLOAK_PUBLIC_URL` và `SSO_ISSUER_URI`: URL HTTPS công khai (ví dụ `https://auth.example.com` hoặc `https://app.example.com/realms/openvie`).
- **TLS và reverse proxy cho Keycloak:** Keycloak trong container chỉ lắng nghe trên cổng nội bộ `127.0.0.1:${KEYCLOAK_PORT:-18080}:8080` (hoặc đi qua APISIX gateway ở các route `/realms/*`, `/resources/*`, `/js/*`). Tuyệt đối không mở cổng HTTP trực tiếp ra Internet; việc kết thúc TLS (HTTPS) bắt buộc do reverse proxy phía trước đảm nhận. Proxy phải chuyển tiếp đầy đủ `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port` (`KC_PROXY_HEADERS: xforwarded` đã bật sẵn). Trước lần chạy đầu tiên trên production, mở `keycloak/realm/openvie-realm.json` và thay URL giữ chỗ `https://example.com*` trong redirect URI và web origin của `openvie-frontend` và `openvie-admin` bằng tên miền production thật.
*Các thiết lập cần lưu ý:*
- `CORS_ORIGINS`, `INVITATION_LINK` và các biến `NEXT_PUBLIC_*` phải trỏ đúng domain/IP mà người dùng truy cập.
- Giới hạn token câu trả lời: `LLM_MAX_OUTPUT_TOKENS=1024`; trích xuất đồ thị có giới hạn riêng `GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS=512`.
- OCR và ASR/video mặc định tắt (nạp tài liệu vẫn đầy đủ với lớp text). Bật bằng các khối `INGESTION_OCR_*`, `INGESTION_ASR_*`/`INGESTION_VIDEO_*` trong `.env.production`; các biến này được truyền thẳng vào container `ai-api` (image đã cài tesseract với gói `vie`/`eng` và ffmpeg), còn cổng upload đi kèm các biến `OCR_UPLOADS_ENABLED`, `ASR_UPLOADS_ENABLED`, `VIDEO_UPLOADS_ENABLED`, `DOCUMENT_MAX_SIZE_MB` trên container `api`. Hướng dẫn về mức tiêu thụ tài nguyên nằm ở `docs/RETRIEVAL.md` §7 và §8.
- Triển khai Spring và Python cùng phiên bản proto: `GenerateAnswer` đã chuyển từ unary sang server-streaming và không có đường tương thích unary. Proxy phải giữ `text/event-stream`, `Cache-Control: no-cache, no-transform` và `X-Accel-Buffering: no`, không gom hay nén luồng chat. Next.js same-origin rewrite đã được kiểm chứng với các header này.
- Kiểm tra cấu hình compose:
```bash
# Kiểm tra cấu hình stack sản xuất đầy đủ:
docker compose -f docker-compose.prod.yml --env-file .env.production config --quiet
```

### 4. Triển khai toàn bộ bằng Docker Compose (khuyến nghị cho production)

Một lệnh khởi chạy toàn bộ ứng dụng và hạ tầng dữ liệu trong container:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
```

Lệnh trên khởi chạy:
1. Hạ tầng dữ liệu: Postgres 16, Redis 7, RabbitMQ, Qdrant, SeaweedFS (S3), Kuzu Graph.
2. Dịch vụ AI và nạp tài liệu: Python FastAPI + gRPC (`:50051`) + worker xử lý tài liệu.
3. Dịch vụ nghiệp vụ: Spring Boot API (`:8080`), tự động chạy Flyway migration khi khởi động.
4. Giao diện: Next.js web console (`:3000`) và giao diện quản trị `frontend-admin` (`/admin/*`, OIDC client `openvie-admin`).
5. Cổng biên: Apache APISIX (`:8088` hoặc `APISIX_PORT`) định tuyến tới API, web và giao diện quản trị.

> Ghi chú về tài liệu API: worker AI (`rag-chatbot-fastapi`) cố ý tắt FastAPI docs/OpenAPI (`docs_url=None, openapi_url=None`) vì đây là service nội bộ, chỉ trao đổi với API Spring qua gRPC có mTLS. Tài liệu API công khai duy nhất là Swagger UI của API Spring tại `/swagger-ui`, và chỉ bật ở profile dev/test; production trả 404 cho `/v3/api-docs` và `/swagger-ui`.

Cài Ollama trực tiếp trên host (để dùng GPU/Metal) **trước** khi tải model:
- **Linux:** `curl -fsSL https://ollama.com/install.sh | sh`, chạy như service systemd.
- **macOS:** tải tại `https://ollama.com/download` (hoặc `brew install --cask ollama`), chạy như LaunchAgent qua launchd.
- **Windows:** tải installer tại `https://ollama.com/download` (hoặc `winget install Ollama.Ollama`).
Kiểm tra `ollama --version` và `http://127.0.0.1:11434`.

Cấu hình song song trong service manager của hệ điều hành:
- **Linux (systemd):** drop-in qua `systemctl edit ollama`:
  ```ini
  Environment="OLLAMA_NUM_PARALLEL=4"
  Environment="OLLAMA_MAX_LOADED_MODELS=3"
  ```
- **macOS (launchd):** `launchctl setenv OLLAMA_NUM_PARALLEL 4` và
  `launchctl setenv OLLAMA_MAX_LOADED_MODELS 3`, rồi khởi động lại Ollama.
- **Windows:** đặt biến hệ thống rồi khởi động lại Ollama (thoát icon system tray trước):
  ```powershell
  setx OLLAMA_NUM_PARALLEL 4
  setx OLLAMA_MAX_LOADED_MODELS 3
  ```
Tải model và giữ thường trú trong bộ nhớ:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
curl -s -o /dev/null http://127.0.0.1:11434/api/generate -d '{"model":"vylinh","keep_alive":-1}'
curl -s -o /dev/null http://127.0.0.1:11434/api/embed -d '{"model":"bge-m3","input":["warmup"],"keep_alive":-1}'
```

*Tùy chọn TEI Reranker container (chạy được trên mọi OS có Docker, kéo image
CPU và checkpoint `Alibaba-NLP/gte-multilingual-reranker-base` ~1 GB ở lần đầu):*
```bash
docker compose -f docker-compose.prod.yml --env-file .env.production --profile reranker up -d reranker-service
```

### 5. Phương án hybrid (chạy ứng dụng trực tiếp trên host)

Nếu chạy ứng dụng trực tiếp trên host thay vì container *(cần POSIX shell với supervisor tương
đương systemd — trên Windows hãy chạy trong WSL2)*:
```bash
# 1. Chỉ khởi động hạ tầng trong Docker:
docker compose --env-file .env.production up -d --wait

# 2. Nạp cấu hình và chạy các service trên host (systemd supervisor):
set -a; . ./.env.production; set +a
make -C rag-chatbot-fastapi setup PYTHON=python3.11 && make -C rag-chatbot-fastapi run PYTHON=python3.11
make -C api migrate ENV_FILE=../.env.production && make -C api run-prod ENV_FILE=../.env.production
npm --prefix frontend ci && npm --prefix frontend run build && npm --prefix frontend run start
```
### 6. Thiết lập lần đầu và khôi phục chủ sở hữu

1. Mở `http://your-domain` (hoặc cổng APISIX 8088). Hệ thống chuyển hướng tới `/setup`.
2. Tạo tài khoản `ORG_OWNER` đầu tiên và workspace mặc định. Sau khi tạo, `/setup` tự đóng (HTTP 404).
3. Đặt lại mật khẩu owner khi khẩn cấp, không cần can thiệp SQL:
```bash
make -C api recover-owner ENV_FILE=../.env.production RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=MatKhauMoi123456"
```

### 7. Kiểm tra tình trạng dịch vụ (health checks)

```bash
docker compose --env-file .env.production ps
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:18000/health/ready
curl --fail http://localhost:8010/health/ready
curl --fail http://127.0.0.1:8088/apisix/status
```

## 8. Chạy demo Workflow BPMN (PoC)

Demo nằm ở `spikes/eoffice-approval`, dùng Flowable và PostgreSQL riêng; không khởi chạy runtime production của OpenVie.

```bash
cd spikes/eoffice-approval
export FLOWABLE_REST_PASSWORD='change-this-local-demo-password'
export FLOWABLE_DB_PASSWORD='change-this-local-db-password'
docker compose -f compose.yaml up -d
```

Chờ Flowable sẵn sàng tại `http://localhost:18081/flowable-rest/service`, rồi mở terminal thứ nhất để triển khai BPMN và tạo process instance:

```bash
python scripts/seed_demo.py
```

Mở terminal thứ hai trong cùng thư mục, đặt lại các biến môi trường đó và chạy worker:

```bash
python scripts/worker.py
```

Dùng Flowable REST để xem task và trạng thái process; xem `spikes/eoffice-approval/README.md` để biết cách claim/complete task và các endpoint cụ thể. Kiểm chứng kết quả demo qua process variables và log của worker.

**Giới hạn:** `publish-document` là stub, không chạy nạp tài liệu production. Cổng chỉ bind vào loopback theo cấu hình Compose mặc định; không công khai Flowable REST ra Internet. Dừng demo bằng `docker compose -f compose.yaml down`. Không dùng `down -v` nếu cần giữ database demo.

**Phân biệt:** đây chỉ là demo độc lập để đánh giá engine (PoC). Luồng phê duyệt dùng trong sản phẩm chạy nhúng trong API runtime chính, không cần compose riêng; SLA cấu hình qua `openvie.workflow.*` phía api, luồng runtime mô tả tại [Kiến trúc](ARCHITECTURE.md).

### 9. Vận hành, sao lưu và cập nhật

- **Công cụ sao lưu/phục hồi:** `scripts/ops/backup.sh` tạo thư mục có timestamp UTC gồm `postgres.dump` (pg_dump), `seaweedfs.tar.gz`, `.env.production.bak` và `MANIFEST.txt` ghi kích thước cùng sha256 của từng tệp; `scripts/ops/restore.sh` phục hồi (`pg_restore --clean --if-exists`, giải nén volume, khôi phục tệp env) và bắt buộc có `--yes`.
- **Kiểm tra toàn vẹn:** `restore.sh` đối chiếu từng sha256 trong `MANIFEST.txt` trước khi phục hồi và dừng nếu sai lệch; quy trình diễn tập định kỳ (phục hồi vào database tạm rồi đối chiếu số bảng/cột và số dòng `flyway_schema_history`) nằm trong `scripts/ops/README.md`.
- **Lịch chạy do người vận hành quyết định:** nền tảng không tự sao lưu. Runbook có sẵn dòng crontab và systemd timer mẫu; cờ `--keep N` giữ N bản mới nhất và chỉ xóa các thư mục do script tự tạo đúng theo mẫu tên của nó (không truyền cờ thì không xóa gì).
- **Cập nhật và đánh chỉ mục lại:** khi logic trích xuất đồ thị thay đổi, kích hoạt đánh chỉ mục lại toàn bộ bằng `DocumentReindexCommand` của Spring Boot với `app.maintenance.reindex.enabled=true`.

### 10. Cổng biên Apache APISIX

APISIX chạy standalone trên cổng `8088`:
- `/apisix/status`: probe sức khỏe nội bộ.
- `/realms/*`: chuyển tới Keycloak để xác thực realm OIDC, giới hạn 10 r/s (burst 20) để chống brute-force.
- `/resources/*`, `/js/*`: tài nguyên theme tĩnh và JS client của Keycloak.
- `/api/v1/auth/*`: Spring Boot (`:8080`), giới hạn 5 r/s (burst 10).
- `/api/v1/admin/*`: Spring Boot (`:8080`), giới hạn 20 r/s (burst 40); Spring vẫn kiểm tra `ORG_OWNER` trên từng request.
- `/api/v1/*`: Spring Boot (`:8080`), giới hạn 50 r/s (burst 100).
- `/admin/*`: giao diện quản trị `frontend-admin` (OIDC client `openvie-admin`).
- `/*`: web client Next.js (`:3000`).
- `/apisix/prometheus/metrics`: endpoint metric Prometheus (chỉ cho phép từ loopback/mạng nội bộ).
*Lưu ý:* các cổng gRPC (`:50051`), Qdrant, Redis và Postgres không đi qua gateway này. Sau khi sửa tệp cấu hình route, chạy `docker compose restart apisix` để nạp lại.

### 11. Giám sát (Prometheus · Alertmanager · Grafana)

Stack giám sát là **tùy chọn**, nằm trong profile `monitoring`, nên `docker compose up` mặc định vẫn giữ hành vi cũ. Bật bằng:

```bash
# Production (full stack):
docker compose -f docker-compose.prod.yml --env-file .env.production --profile monitoring up -d

# Development (hạ tầng dữ liệu + giám sát; app chạy trên host):
docker compose --profile monitoring up -d
```

- **Prometheus** (`127.0.0.1:${PROMETHEUS_PORT:-9090}`) thu thập metric từ:
  - `openvie-api` → `http://api:8080/actuator/prometheus` (prod) hoặc `http://host.docker.internal:8080/actuator/prometheus` (dev, app trên host).
  - `openvie-ai` → `http://ai-api:18000/metrics` (prod) hoặc `http://host.docker.internal:18000/metrics` (dev).
  - job `prometheus` tự thu thập metric của chính nó.
  - `openvie-gateway` → `http://apisix:9080/apisix/prometheus/metrics` (route data-plane được bảo vệ; không dùng exporter `:9091` đã bỏ).
  Cấu hình: `monitoring/prometheus/prometheus.yml` (prod) và `monitoring/prometheus/prometheus.dev.yml` (dev); chu kỳ thu thập 15 giây.
- **Quy tắc cảnh báo**: `monitoring/prometheus/rules/openvie-alerts.yml` (target down, tỷ lệ lỗi 5xx, heap JVM, tỷ lệ lỗi cache, lỗi Redis, timeout model chat, fallback truy hồi).
- **Alertmanager** (`127.0.0.1:${ALERTMANAGER_PORT:-9093}`): cấu hình `monitoring/alertmanager/alertmanager.yml` chỉ có route `default`. **Bước thuộc về người vận hành:** receiver `default` cố tình chưa gắn địa chỉ gửi — thêm webhook/email/Slack vào đây để cảnh báo rời khỏi host. Quy tắc `inhibit_rules` cho phép cảnh báo `critical` chặn cảnh báo `warning` cùng `alertname` cho tới khi hết.
- **Grafana** (`127.0.0.1:${GRAFANA_PORT:-3002}`): nguồn dữ liệu Prometheus (`http://prometheus:9090`) và hai dashboard (`OpenVie Platform Overview`, `OpenVie AI & Ingestion`) được nạp sẵn từ tệp trong `monitoring/grafana/`. Đăng nhập bằng `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`; truy cập ẩn danh đã bị tắt.
- **Bảo mật:** cả ba cổng chỉ lắng nghe trên `127.0.0.1`. Muốn truy cập từ xa, đặt sau reverse proxy TLS của bạn (như với APISIX); không mở trực tiếp ra Internet.

### 12. Nâng cấp lên v1.0.0 và cấu hình an toàn cho truy hồi

#### A. Trình tự nâng cấp bắt buộc
1. **Sao lưu toàn bộ dữ liệu:** chạy `scripts/ops/backup.sh` (Postgres, Qdrant, Kuzu, SeaweedFS).
2. **Lưu ý checksum Flyway:** tuyệt đối không sửa các migration từ `V1` đến `V17`. Khởi động API để Flyway tự áp dụng `V18__document_provenance.sql` và `V19__chat_turn_intent.sql`.
3. **Cập nhật bảng node trong Kuzu:** chạy DDL bổ sung cột nguồn gốc trên bảng `Source` của Kuzu:
   ```cypher
   ALTER TABLE Source ADD document_kind STRING DEFAULT 'USER';
   ALTER TABLE Source ADD source_ref STRING DEFAULT '';
   ```
4. **Phân loại lại dữ liệu cũ (W0.7 backfill):** chạy lệnh chuẩn hóa tài liệu hệ thống (dry-run trước, sau đó chạy thật ngoài giờ cao điểm):
   ```bash
   # Dry-run:
   java -jar api/target/openvie-api-1.0.0.jar --app.command.mode=bizapp-provenance-backfill --dry-run
   # Chạy thật:
   java -jar api/target/openvie-api-1.0.0.jar --app.command.mode=bizapp-provenance-backfill
   ```
5. **Kiểm tra sau nâng cấp:** xác nhận tài liệu hệ thống không lộ trong UI và không xuất hiện trong trích dẫn của câu hỏi tài liệu thông thường.

#### B. Ma trận cấu hình trích xuất và truy hồi đồ thị
Hệ thống có hai công tắc độc lập cho kênh đồ thị:

| `GRAPH_EXTRACTION_ENABLED` | `GRAPH_RETRIEVAL_ENABLED` | Kết quả vận hành |
|---|---|---|
| `true` (mặc định) | `true` (mặc định) | Chuẩn v1.0.0: trích xuất khi nạp tài liệu + truy hồi đồ thị kết hợp dense/sparse. |
| `true` | `false` | Đồ thị vẫn được trích xuất nhưng không dùng để truy hồi (tốn compute lúc nạp — chỉ dùng khi muốn chuẩn bị dữ liệu đồ thị trước). |
| `false` | `true` | Nạp nhanh (không gọi LLM trích xuất đồ thị); truy hồi graph vẫn chạy nhưng trả về ít hoặc không có kết quả. |
| `false` | `false` | Chỉ dense + sparse (nhanh nhất, tốn ít compute nhất). |

#### C. Chính sách cache và phiên bản policy
- Cờ `GRAPH_RETRIEVAL_ENABLED` và hằng số `document_kind_policy_version` (hiện tại `document-kind-v1`) nằm trong `retrieval_configuration_fingerprint`.
- Mọi thay đổi về chính sách loại trừ hoặc công tắc graph đều tự động tách khóa cache truy hồi và cache câu trả lời ngữ nghĩa, tránh trả kết quả của chế độ cũ.
- Tham số `include_system=true` trên `/api/v1/documents` chỉ dành cho `ORG_OWNER` để kiểm tra vận hành.

---

# Self-hosted installation guide

This guide covers deploying OpenVie on an operator-controlled production server.

## 1. Scope and boundaries

- **Included:**
  - `docker-compose.prod.yml`: Full containerized production stack (Postgres, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu Graph, Spring Boot API, Python AI Service, Next.js frontend, and Apache APISIX Gateway).
  - `docker-compose.yml`: Data infrastructure only (used for development or hybrid host execution).
  - Template `.env.production.example` and production gateway routes `apisix/routes.prod.yaml`.
- **Operator-owned:** TLS termination (HTTPS), public DNS, external WAF, upload antivirus scanning, backup/restore schedules, and host systemd supervision.
## 2. Prerequisites

- Linux host (Ubuntu 22.04+ recommended) or macOS. On **Windows 10/11**: use Docker Desktop
  with the WSL2 backend and run every compose/make/openssl command from the Ubuntu WSL2
  terminal; large-scale deployments should run on a native Linux box (Docker Desktop caps I/O
  and resources for heavy workloads).
- Docker with Compose plugin, JDK 21, Python 3.11/3.12, Node.js 22 LTS, Make, OpenSSL. On
  Windows, the CLI tooling (Make, OpenSSL) lives **inside the WSL2 distro** — PowerShell does
  not provide it.
- Sufficient disk space for model weights (`vylinh`, `bge-m3`) and vector indexes.

## 3. Configuration preparation

Copy and restrict the production configuration template:
```bash
cp .env.production.example .env.production
chmod 600 .env.production
```
*(On Windows, run this step from the Ubuntu WSL2 terminal — PowerShell has no built-in
`openssl` or `chmod`.)*
Generate unique 32-byte cryptographic secrets via `openssl rand -hex 32` for:
`TOKEN_KEY`, `NOTIFICATION_ENC_KEY` (base64: `openssl rand -base64 32`), `GRAPH_INTERNAL_TOKEN`, `POSTGRES_PASSWORD`, `RABBITMQ_DEFAULT_PASS`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_DB_PASSWORD`.

*Keycloak & SSO production secrets:*
- `KEYCLOAK_ADMIN_PASSWORD`: Strong password for the Keycloak admin console (≥16 random bytes).
- `KEYCLOAK_DB_PASSWORD`: Dedicated PostgreSQL database password for Keycloak.
- `SSO_BACKEND_CLIENT_SECRET`: Reserved for future client-credentials on `openvie-backend` (service accounts are disabled in the realm export). The SSO flow uses public clients + PKCE, so it can stay empty; if you enable that flow later, read the secret from the Keycloak Admin Console and never commit it.
- `SSO_FRONTEND_CLIENT_ID` and `SSO_ADMIN_CLIENT_ID`: Public client ids for the main app and admin surface (defaults `openvie-frontend` / `openvie-admin`).
- `KEYCLOAK_PUBLIC_URL` & `SSO_ISSUER_URI`: Public HTTPS realm URLs (e.g. `https://auth.example.com` or `https://app.example.com/realms/openvie`).
- **TLS & Reverse Proxy Architecture for Keycloak:** Keycloak binds to host-local loopback `127.0.0.1:${KEYCLOAK_PORT:-18080}:8080` (or through the APISIX edge gateway routes `/realms/*`, `/resources/*`, `/js/*`). Never expose Keycloak's HTTP port directly to the public internet; TLS termination (HTTPS) must be handled by an operator-controlled reverse proxy (APISIX, Cloudflare, Nginx, or Caddy). The reverse proxy must forward `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port` (`KC_PROXY_HEADERS: xforwarded` is enabled). Before initial production import, edit `keycloak/realm/openvie-realm.json` to replace the `https://example.com*` placeholder in redirect URIs and web origins of `openvie-frontend` and `openvie-admin` with your actual production domain.

Ensure `CORS_ORIGINS`, `INVITATION_LINK`, and `NEXT_PUBLIC_*` match your public domain. Answer generation uses `LLM_MAX_OUTPUT_TOKENS=1024`; graph extraction retains its independent `GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS=512`. OCR and ASR/video are off by default (text-layer ingestion is unaffected). Enable them via the `INGESTION_OCR_*` and `INGESTION_ASR_*` / `INGESTION_VIDEO_*` blocks in `.env.production` — the variables are forwarded into the `ai-api` container (the image ships tesseract with `vie`/`eng` packs and ffmpeg) and upload gates ride along in `OCR_UPLOADS_ENABLED`, `ASR_UPLOADS_ENABLED`, `VIDEO_UPLOADS_ENABLED`, and `DOCUMENT_MAX_SIZE_MB` on the `api` container; capacity guidance lives in `docs/RETRIEVAL.md` §7 and §8.

Deploy Spring and Python with the same proto version: `GenerateAnswer` is now server-streaming, with no unary compatibility path. Proxies must preserve `text/event-stream`, `Cache-Control: no-cache, no-transform`, and `X-Accel-Buffering: no` without buffering/compressing chat streams. The Next.js same-origin rewrite has been smoke-verified with these headers.

Validate compose structure:
```bash
# Validate production full-stack compose:
docker compose -f docker-compose.prod.yml --env-file .env.production config --quiet
```

## 4. Full-stack containerized deployment (Recommended)

Deploy the complete application and data infrastructure with a single Docker Compose command:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
```

This containerizes and orchestrates:
1. Data services: Postgres 16, Redis 7, RabbitMQ, Qdrant, SeaweedFS, Kuzu Graph.
2. AI & Ingestion: Python FastAPI + gRPC (:50051) + embedded document worker.
3. Business API: Spring Boot (:8080) with automatic Flyway database migrations.
4. Frontend: Next.js production server (:3000) plus the `frontend-admin`
   surface (`/admin/*`, OIDC client `openvie-admin`).
5. Edge Gateway: Apache APISIX (:8088 or configured `APISIX_PORT`).

Install native Ollama on the host for GPU/Metal hardware acceleration **before**
pulling weights:
- **Linux:** `curl -fsSL https://ollama.com/install.sh | sh`, run as a systemd service.
- **macOS:** download from `https://ollama.com/download` (or `brew install --cask ollama`), run as a LaunchAgent via launchd.
- **Windows:** download the installer from `https://ollama.com/download` (or `winget install Ollama.Ollama`).
Verify with `ollama --version` and `http://127.0.0.1:11434`.
Configure parallelism in the OS service manager:
- **Linux (systemd):** drop-in via `systemctl edit ollama`:
  ```ini
  Environment="OLLAMA_NUM_PARALLEL=4"
  Environment="OLLAMA_MAX_LOADED_MODELS=3"
  ```
- **macOS (launchd):** `launchctl setenv OLLAMA_NUM_PARALLEL 4` and
  `launchctl setenv OLLAMA_MAX_LOADED_MODELS 3`, then restart Ollama.
- **Windows:** system-wide variables, then relaunch Ollama (quit the system-tray icon first):
  ```powershell
  setx OLLAMA_NUM_PARALLEL 4
  setx OLLAMA_MAX_LOADED_MODELS 3
  ```
Download and pin model checkpoints in memory:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
curl -s -o /dev/null http://127.0.0.1:11434/api/generate -d '{"model":"vylinh","keep_alive":-1}'
curl -s -o /dev/null http://127.0.0.1:11434/api/embed -d '{"model":"bge-m3","input":["warmup"],"keep_alive":-1}'
```

*Optional TEI Reranker container (runs on any OS with Docker; first run pulls the
CPU image and the ~1 GB `Alibaba-NLP/gte-multilingual-reranker-base` checkpoint):*
```bash
docker compose -f docker-compose.prod.yml --env-file .env.production --profile reranker up -d reranker-service
```

## 5. Hybrid deployment mode (Applications on Host)

If running applications directly on the host under systemd rather than containers *(this mode
needs a POSIX shell with a systemd-equivalent supervisor — on Windows run it inside WSL2)*:
```bash
# 1. Start data infrastructure only:
docker compose --env-file .env.production up -d --wait

# 2. Export environment and run services on host:
set -a; . ./.env.production; set +a
make -C rag-chatbot-fastapi setup PYTHON=python3.11 && make -C rag-chatbot-fastapi run PYTHON=python3.11
make -C api migrate ENV_FILE=../.env.production && make -C api run-prod ENV_FILE=../.env.production
npm --prefix frontend ci && npm --prefix frontend run build && npm --prefix frontend run start
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

## 8. Run the BPMN workflow demo (PoC)

The demo in `spikes/eoffice-approval` uses a separate Flowable engine and PostgreSQL database. It does not start or integrate the production OpenVie workflow runtime.

```bash
cd spikes/eoffice-approval
export FLOWABLE_REST_PASSWORD='change-this-local-demo-password'
export FLOWABLE_DB_PASSWORD='change-this-local-db-password'
docker compose -f compose.yaml up -d
```

Wait until Flowable is available at `http://localhost:18081/flowable-rest/service`. In the first terminal, deploy the BPMN and create demo process instances:

```bash
python scripts/seed_demo.py
```

In a second terminal, from the same directory, export the same environment variables and start the worker:

```bash
python scripts/worker.py
```

Use Flowable REST to inspect tasks and process state. See `spikes/eoffice-approval/README.md` for the task claim/complete procedure and exact endpoints. Verify the result using process variables and worker logs.

**Boundary:** `publish-document` is a stub and does not execute production ingestion. Keep the REST endpoint bound to loopback as configured; never expose it publicly. Stop with `docker compose -f compose.yaml down`. Do not use `down -v` if the demo database must be preserved.

**Distinction:** This is only the standalone engine-evaluation demo. The product-facing approval flow runs embedded inside the main API runtime — no extra Compose stack is required; SLA is configured through `openvie.workflow.*` on the api service, and the runtime flow is described in [Architecture](ARCHITECTURE.md).

## 9. Backup and operations

- **Backup & restore tooling:** `scripts/ops/backup.sh` writes a UTC-timestamped directory with `postgres.dump` (pg_dump), `seaweedfs.tar.gz`, `.env.production.bak`, and a `MANIFEST.txt` holding per-artifact sizes and sha256; `scripts/ops/restore.sh` restores (`pg_restore --clean --if-exists`, volume extraction, env file) and refuses to run without `--yes`.
- **Integrity verification:** `restore.sh` verifies every sha256 in `MANIFEST.txt` before restoring and aborts on mismatch; the recurring drill (restore into a scratch database and assert table/column counts and `flyway_schema_history` row counts) is in `scripts/ops/README.md`.
- **Schedules stay operator-owned:** the platform never runs backups. The runbook ships a copy-pasteable crontab line and a systemd timer alternative; `--keep N` retains the N most recent backups and prunes only directories created by the script under its own naming pattern (without the flag nothing is deleted).
- **Reindexing:** To re-extract knowledge graphs after pipeline updates, trigger Spring's `DocumentReindexCommand` via `app.maintenance.reindex.enabled=true`.

## 10. Edge gateway (Apache APISIX)

APISIX operates standalone on port `8088`:
- `/apisix/status`: Gateway health probe.
- `/realms/*`: Keycloak OIDC realm authentication, rate-limited at 10 r/s (burst 20) matching auth brute-force protection.
- `/resources/*`, `/js/*`: Keycloak static theme and JavaScript assets.
- `/api/v1/auth/*`: Spring Boot (:8080), rate-limited at 5 r/s (burst 10).
- `/api/v1/admin/*`: Spring Boot (:8080), rate-limited at 20 r/s (burst 40); Spring still enforces `ORG_OWNER` per request.
- `/api/v1/*`: Spring Boot (:8080), rate-limited at 50 r/s (burst 100).
- `/admin/*`: `frontend-admin` surface (OIDC client `openvie-admin`).
- `/*`: Next.js web application (:3000).
- `/apisix/prometheus/metrics`: Prometheus metrics endpoint (restricted to loopback/private network callers).
*Note:* Internal endpoints (`:50051`, Qdrant, Redis, Postgres) are not routed through the gateway. Route modifications in standalone YAML mode require `docker compose restart apisix`.

## 11. Monitoring (Prometheus · Alertmanager · Grafana)

The monitoring stack is **optional** and lives in the `monitoring` profile, so a plain `docker compose up` keeps the previous behavior. Enable it with:

```bash
# Production (full stack):
docker compose -f docker-compose.prod.yml --env-file .env.production --profile monitoring up -d

# Development (data infrastructure + monitoring; apps run on the host):
docker compose --profile monitoring up -d
```

- **Prometheus** (`127.0.0.1:${PROMETHEUS_PORT:-9090}`) scrapes:
  - `openvie-api` → `http://api:8080/actuator/prometheus` (prod) or `http://host.docker.internal:8080/actuator/prometheus` (dev, apps on host).
  - `openvie-ai` → `http://ai-api:18000/metrics` (prod) or `http://host.docker.internal:18000/metrics` (dev).
  - the `prometheus` job scrapes itself.
  - `openvie-gateway` → `http://apisix:9080/apisix/prometheus/metrics` (guarded data-plane route; the retired `:9091` exporter is not used).
  Configuration: `monitoring/prometheus/prometheus.yml` (prod) and `monitoring/prometheus/prometheus.dev.yml` (dev); scrape interval is 15 seconds.
- **Alert rules**: `monitoring/prometheus/rules/openvie-alerts.yml` (target down, 5xx error ratio, JVM heap, cache error ratio, Redis errors, chat-model timeouts, retrieval fallbacks).
- **Alertmanager** (`127.0.0.1:${ALERTMANAGER_PORT:-9093}`): `monitoring/alertmanager/alertmanager.yml` ships a single `default` route. **Operator-owned step:** the `default` receiver intentionally has no delivery address — add a webhook/email/Slack integration there so alerts leave the host. An `inhibit_rules` entry lets a `critical` alert mute the `warning` version of the same `alertname` until it resolves.
- **Security:** all three ports bind to `127.0.0.1` only. To reach them remotely, put them behind your own TLS reverse proxy (as with APISIX); never expose them directly to the Internet.

## 12. Upgrade Guide to v1.0.0 & Retrieval Safety Matrix

### A. Mandatory Upgrade Sequence
1. **Full Backup:** Execute `scripts/ops/backup.sh` (Postgres, Qdrant, Kuzu, SeaweedFS).
2. **Flyway Checksum Guard:** Never modify historical migrations `V1` through `V17`. Run API to apply `V18__document_provenance.sql` and `V19__chat_turn_intent.sql`.
3. **Kuzu Source Node Table Update:** Execute DDL adding provenance attributes to `Source`:
   ```cypher
   ALTER TABLE Source ADD document_kind STRING DEFAULT 'USER';
   ALTER TABLE Source ADD source_ref STRING DEFAULT '';
   ```
4. **Legacy System Document Reclassification (W0.7 Backfill):** Run the idempotent backfill command (dry-run first, then execute during maintenance window):
   ```bash
   # Dry-run:
   java -jar api/target/openvie-api-1.0.0.jar --app.command.mode=bizapp-provenance-backfill --dry-run
   # Live run:
   java -jar api/target/openvie-api-1.0.0.jar --app.command.mode=bizapp-provenance-backfill
   ```
5. **Post-Upgrade Verification:** Confirm system documents are hidden from UI and excluded from document citations.

### B. Graph Ingest & Retrieval Configuration Matrix

| `GRAPH_EXTRACTION_ENABLED` | `GRAPH_RETRIEVAL_ENABLED` | Operational Behavior |
|---|---|---|
| `true` (default) | `true` (default) | Standard v1.0.0: graph extraction during ingest + hybrid graph retrieval. |
| `true` | `false` | Graph is extracted but skipped during retrieval (wastes ingest compute; use only when prebuilding graph). |
| `false` | `true` | Fast ingest (no LLM extraction); graph retrieval runs with empty/sparse graph results. |
| `false` | `false` | Pure Dense + Sparse retrieval (fastest execution, minimal compute overhead). |

### C. Cache Policy Fingerprinting
- `GRAPH_RETRIEVAL_ENABLED` and `document_kind_policy_version` (`document-kind-v1`) are included in `retrieval_configuration_fingerprint`.
- Toggling graph retrieval or updating exclusion policy automatically invalidates both retrieval cache and semantic answer cache, preventing stale responses across modes.
- `include_system=true` on `/api/v1/documents` is restricted to `ORG_OWNER` for administrative inspection only.
