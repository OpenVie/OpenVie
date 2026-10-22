# Local development

[Documentation index](README.md) · [Project overview](../README.md) · [Deployment](DEPLOYMENT.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#hướng-dẫn-phát-triển-cục-bộ--tiếng-việt) · [English](#local-development-guide)

---

## Hướng dẫn phát triển cục bộ — Tiếng Việt

Tài liệu này hướng dẫn chạy toàn bộ hạ tầng trong Docker và 3 ứng dụng chính (Java, Python, Web) trực tiếp trên máy chủ phát triển (host).

### 1. Yêu cầu tiên quyết

| Công cụ | Yêu cầu |
| --- | --- |
| **Hệ điều hành / Shell** | Linux, macOS, hoặc **Windows 10/11 qua WSL2** (POSIX shell + GNU Make chạy ngay trong WSL2) |
| **Docker** | Docker Engine + Compose plugin (Linux) hoặc Docker Desktop (macOS; Windows dùng backend WSL2) |
| **Java** | JDK 21 (API đã tích hợp sẵn Maven Wrapper) |
| **Python** | Python 3.11 hoặc 3.12 |
| **Node.js** | Node.js 22 LTS kèm npm |
| **Ollama** | Cài đặt trực tiếp trên host để tận dụng GPU/Metal (mục Bước 4) |
| **TEI Reranker (tùy chọn)** | Chạy qua Docker (mọi OS có Docker) hoặc nhị phân native (macOS Apple Silicon) |

#### Cài nhanh Windows (WSL2)

Trên Windows 10 22H2 / Windows 11, **tất cả** lệnh trong tài liệu này chạy trong terminal
Ubuntu WSL2 thay vì PowerShell:

```powershell
wsl --install -d Ubuntu-24.04   # PowerShell (Admin); khởi động lại máy khi xong
```

1. Trong Ubuntu WSL2, cài JDK 21, Python 3.11, Node 22 và Make ngay trong distro (apt, sdkman,
   nvm) — không dùng bản cài Windows native cho các toolchain này.
2. Docker Desktop → Settings → General → bật **Use the WSL 2 based engine** để containers
   dùng chung được với distro WSL2.
3. Clone repo vào **filesystem của WSL** (ví dụ `~/projects/OpenVie`), không để trên
   `/mnt/c/...`: mount Windows chậm gấp nhiều lần cho Maven/npm/pytest.
4. Ollama vẫn cài trên **Windows host** (xem Bước 4); từ WSL2 và containers trỏ đến
   `localhost:11434` / `host.docker.internal:11434` như mô tả.
5. Bước 6 cần 3 ứng dụng chạy song song — dùng **Windows Terminal** mở 3 tab
   Ubuntu WSL2 (menu dropdown chọn profile Ubuntu), thay vì 3 cửa sổ riêng lẻ.

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

*Khởi động Keycloak và APISIX để kiểm thử SSO / Gateway:*
`dev-infra` mặc định bỏ qua Keycloak và Gateway để tiết kiệm tài nguyên. Để kiểm thử tính năng SSO và định tuyến qua gateway:
```bash
docker compose up -d keycloak-db keycloak apisix
```
Hoặc khởi động toàn bộ hạ tầng phát triển cùng một lệnh:
```bash
docker compose up -d
docker compose --profile reranker up -d  # kèm TEI Reranker (:8082), tùy chọn
```
Lệnh này khởi chạy thêm Keycloak (`:18080`, tự động nạp realm `openvie` từ `keycloak/realm/openvie-realm.json`) và APISIX Gateway (`:8088`, định tuyến `/realms/*`, `/resources/*`, `/js/*` → Keycloak, `/api/v1/*` → Spring Boot `:8080`, `/admin/*` → `:3001`, `/*` → Next.js `:3000`).

#### Bước 4: Cài đặt Ollama và tải mô hình
Tải và cài đặt Ollama trực tiếp trên host (để tận dụng GPU/Metal):
- **Linux / WSL2:**
  ```bash
  curl -fsSL https://ollama.com/install.sh | sh
  ```
- **macOS:** tải tại `https://ollama.com/download`, hoặc:
  ```bash
  brew install --cask ollama
  ```
- **Windows:** tải installer tại `https://ollama.com/download`, hoặc:
  ```powershell
  winget install Ollama.Ollama
  ```
Kiểm tra `ollama --version` và `http://127.0.0.1:11434`. Trên Windows, container
trỏ vào Ollama qua `host.docker.internal:11434` — nếu không với tới, đặt
`OLLAMA_HOST=0.0.0.0` rồi khởi động lại Ollama.

Tải các mô hình tiêu chuẩn:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
ollama rm hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
```

*Kích hoạt xử lý song song và ghim bộ nhớ:*
- Đặt biến môi trường `OLLAMA_NUM_PARALLEL=4` và `OLLAMA_MAX_LOADED_MODELS=3` trong service
  manager theo hệ điều hành:
  - **Linux (systemd):** tạo drop-in `systemctl edit ollama` và thêm
    `Environment="OLLAMA_NUM_PARALLEL=4"`, `Environment="OLLAMA_MAX_LOADED_MODELS=3"`, rồi
    `systemctl restart ollama`.
  - **macOS (launchd):** `launchctl setenv OLLAMA_NUM_PARALLEL 4` + `launchctl setenv
    OLLAMA_MAX_LOADED_MODELS 3`, rồi khởi động lại Ollama.
  - **Windows:** đặt biến hệ thống rồi khởi động lại Ollama (thoát icon system tray trước):
    ```powershell
    setx OLLAMA_NUM_PARALLEL 4
    setx OLLAMA_MAX_LOADED_MODELS 3
    ```
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
Mặt quản trị (`/admin/*`: organization, workspaces, settings, yêu cầu
`ORG_OWNER`) chạy chung trên dev server `:3000` — ví dụ
`http://localhost:3000/admin/organization`. Lý do: `next dev` chỉ cho phép
một instance mỗi thư mục (lockfile `.next/dev`), nên không thể chạy song song
`:3000` + `:3001` từ cùng source.

Muốn test đúng OIDC client `openvie-admin` trên dev server duy nhất, restart
với env khác (rồi đổi lại khi xong):
```bash
NEXT_PUBLIC_OIDC_CLIENT_ID=openvie-admin npm --prefix frontend run dev
```
Sự tách biệt `:3001` + client riêng chỉ có hiệu lực ở production
(service `frontend-admin` trong compose) và qua gateway `:8088/admin/*`.
Qua APISIX dev (`:8088`), route `edge-admin-web` trỏ `:3001` — muốn test
gateway ở local thì chạy mặt quản trị bằng production build:
```bash
NEXT_PUBLIC_OIDC_CLIENT_ID=openvie-admin npm --prefix frontend run build
PORT=3001 npm --prefix frontend run start
```
(lưu ý build này ghi đè `.next`, chạy xong build lại dev bình thường).

`make -C api dev` sinh mã Java/gRPC từ `proto/` vào `api/target/protobuf/`
trước khi biên dịch. Đường dẫn này tách khỏi `generated-sources` để mã protobuf
và class không bị mất khi Maven biên dịch tăng dần.

Nếu truy cập bản **dev** qua APISIX/ngrok, đặt `NEXT_DEV_ALLOWED_ORIGINS=your-subdomain.ngrok-free.dev`
(chỉ hostname, không có `https://`) trong `frontend/.env.local`, giữ
`NEXT_PUBLIC_API_BASE_URL=/api/v1`, rồi **khởi động lại** Next.js. Chạy
`docker compose up -d apisix` và `ngrok http 8088`; mở URL HTTPS do ngrok cấp.
Next.js chỉ cho phép WebSocket `/_next/webpack-hmr` từ các hostname khai báo;
nếu tunnel đổi tên, cập nhật biến và khởi động lại. `localhost` và `127.0.0.1`
được cho phép sẵn. Không dùng wildcard công khai cho allowlist này.

Trong `api/.env`, đặt
`ALLOWED_ORIGIN_PATTERNS=http://localhost:*,http://127.0.0.1:*,https://your-subdomain.ngrok-free.dev`
và khởi động lại Spring API. POST đăng nhập có header `Origin`; Spring sẽ trả
`403 Invalid CORS request` nếu HTTPS origin này chưa được cho phép.

### 3. Thiết lập lần đầu và khôi phục tài khoản

1. Truy cập `http://localhost:3000` trên trình duyệt. Hệ thống tự động chuyển hướng tới `/setup`.
2. Điền tên tổ chức, họ tên, email và mật khẩu (tối thiểu 12 ký tự) để tạo tài khoản Chủ sở hữu (`ORG_OWNER`) và workspace mặc định.
3. Nếu quên mật khẩu `ORG_OWNER`, đặt lại qua lệnh ngoại tuyến:
```bash
make -C api recover-owner RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=MatKhauMoiCucKyBaoMat123"
```

### 4. Xếp hạng lại cục bộ (TEI Reranker - Tùy chọn)

- **macOS (Apple Silicon, native Metal):**
  ```bash
  brew install text-embeddings-inference
  make -C rag-chatbot-fastapi dev-reranker-native
  ```
- **Linux / WSL2 / Windows (qua Docker, cần Docker Engine hoặc Docker Desktop):**
  ```bash
  make -C rag-chatbot-fastapi dev-reranker
  ```
  Lần đầu kéo image `ghcr.io/huggingface/text-embeddings-inference:cpu-1.9` và
  tải checkpoint `Alibaba-NLP/gte-multilingual-reranker-base` (~1 GB).
  (Windows native không có nhị phân TEI chính thức — dùng cách Docker này.)
Khi chạy `make dev` trong Python, `DEV_RERANKER_ENABLED=true` được bật theo mặc định trên cổng `127.0.0.1:8082`. Nếu không dùng TEI, tắt bằng `DEV_RERANKER_ENABLED=false`.

### OCR cục bộ (Tùy chọn — dành cho máy mạnh)

OCR tắt mặc định; bật để chạy pipeline nhận dạng thật trên chính máy dev
(máy 4+ nhân vẫn thoải mái, tesseract không cần GPU). Chuẩn bị tesseract:

```bash
brew install tesseract tesseract-lang   # macOS (tesseract-lang mang gói vie)
sudo apt install tesseract-ocr tesseract-ocr-vie tesseract-ocr-eng  # Debian/Ubuntu — và cả WSL2 trên Windows
```

Chạy Python trực tiếp trên Windows (ngoài WSL2 — ngoài luồng khuyến nghị) dùng bản UB Mannheim:

```powershell
winget install -e --id UB-Mannheim.TesseractOCR
```

Trong lúc cài, chọn thêm gói ngôn ngữ **Vietnamese (vie)** và **English (eng)**; sau cài đặt
`tesseract --list-langs` phải thấy cả `vie` và `eng` (worker gọi tesseract từ PATH). Khi chạy
trong production thì không cần cài này: container `ai-api` đã cài sẵn tesseract kèm `vie`/`eng`.

Bật trong `rag-chatbot-fastapi/.env` (worker — chạy `make -C rag-chatbot-fastapi dev`):

```ini
INGESTION_OCR_ENABLED=true
# Trang scan dày: đặt theo cấu hình máy (1-16)
INGESTION_OCR_PAGE_CONCURRENCY=4
# Ảnh lồng (.docx/.xlsx + PDF có text): bật khi cần test citation có trang/sheet
INGESTION_OCR_EMBEDDED_IMAGES=true
# Chất lượng cao thay thế tesseract (máy cần GPU riêng và model VLM đã tải):
# INGESTION_OCR_PROVIDER=ollama-vlm
# OCR_VLM_MODEL_ID=ten-model-vlm-trong-ollama
```

Bật cổng upload ảnh trong `api/.env` (Spring): `OCR_UPLOADS_ENABLED=true`.

Kiểm thử:
1. Upload một PDF scan (hoặc `.png`/`.jpg`) trong UI → tài liệu hiển thị badge **OCR**
   (`text_source` là `ocr` hoặc `mixed`), xác nhận qua sự kiện `COMPLETED`.
2. Upload `.docx` chứa ảnh (kèm `INGESTION_OCR_EMBEDDED_IMAGES=true`) → hỏi kèm ảnh
   bằng câu hỏi trích dẫn `[S1]` → panel nguồn hiển thị badge OCR và số trang
   ước tính từ dấu ngắt trang Word.
3. Upload lại cùng file → OCR cache bỏ qua nhận dạng (fingerprint trùng), thời gian
   hấp thụ trở về mức thường.
4. Tắt hết lại (comment các biến) → hành vi quay về byte-identical như cũ; test backend
   (`make -C rag-chatbot-fastapi check`) vẫn xanh ở mọi cấu hình.

Chi tiết knob và ceiling (DPI, megapixel, timeout, 3 lỗi provider liên tiếp):
`docs/RETRIEVAL.md` §7.

### 5. Kiểm tra và xác minh (Verification)

| Lĩnh vực | Lệnh kiểm tra |
| --- | --- |
| **Java API** | `make -C api test` hoặc `make -C api verify` |
| **Python AI** | `make -C rag-chatbot-fastapi check PYTHON=python3.11` |
| **Web Frontend** | `npm --prefix frontend run lint` và `npm --prefix frontend run typecheck` |

### Hướng dẫn chạy và kiểm tra đăng nhập SSO cục bộ (Local SSO Run & First-Run Checks)

Hệ thống hỗ trợ đăng nhập một lần (Single Sign-On - SSO) qua giao thức OIDC (Authorization Code flow kèm PKCE S256) tích hợp với Keycloak. Để kiểm thử luồng SSO từ một bản clone mới (fresh clone):

#### Bước A: Khởi động dịch vụ Keycloak
1. Khởi động Keycloak và cơ sở dữ liệu Keycloak (hoặc khởi động toàn bộ stack với APISIX):
   ```bash
   docker compose up -d keycloak-db keycloak apisix
   ```
   *Lưu ý:* Keycloak tự động nhập cấu hình realm `openvie` từ thư mục `keycloak/realm/openvie-realm.json` (chứa sẵn client `openvie-frontend`, `openvie-admin`, `openvie-backend`).
2. Xác minh Keycloak đã sẵn sàng:
   ```bash
   curl --fail http://localhost:18080/realms/openvie/.well-known/openid-configuration
   ```

#### Bước B: Cấu hình biến môi trường
1. Trong `api/.env`:
   ```ini
   SSO_ENABLED=true
   SSO_ISSUER_URI=http://localhost:18080/realms/openvie
   SSO_FRONTEND_CLIENT_ID=openvie-frontend
   SSO_BACKEND_CLIENT_ID=openvie-backend
   ```
2. Trong `frontend/.env.local`:
   ```ini
   NEXT_PUBLIC_OIDC_ISSUER=http://localhost:18080/realms/openvie
   NEXT_PUBLIC_OIDC_CLIENT_ID=openvie-frontend
   ```
   *(Nếu truy cập qua APISIX cổng 8088, có thể đặt issuer thành `http://localhost:8088/realms/openvie`).*
3. Khởi chạy các ứng dụng trên host theo Bước 6 (Spring API `:8080`, Python `:18000`, Frontend `:3000`).

#### Bước C: Chuẩn bị tài khoản người dùng
1. **Thiết lập tài khoản trong OpenVie:** Mở trình duyệt tại `http://localhost:3000` (hoặc `http://localhost:8088`). Hoàn thành trang `/setup` ban đầu để tạo tổ chức và tài khoản Chủ sở hữu với email (ví dụ: `owner@example.com`).
2. **Tạo người dùng tương ứng trong Keycloak:**
   - Mở Keycloak Admin Console: `http://localhost:18080` (đăng nhập: `admin` / `change-me-keycloak-admin`).
   - Chọn realm **openvie** ở menu thả xuống góc trên bên trái.
   - Vào mục **Users** -> nhấn **Add user**:
     - **Username:** `owner` (hoặc `owner@example.com`)
     - **Email:** `owner@example.com` *(bắt buộc phải trùng khớp với email đã tạo trong OpenVie)*
     - **Email verified:** bật **On**
     - Nhấn **Create**.
   - Chuyển sang tab **Credentials** -> nhấn **Set password**:
     - **Password:** ví dụ `Password123!`
     - **Temporary:** tắt **Off**
     - Nhấn **Save**.

#### Bước D: Thực hiện kiểm tra đăng nhập SSO (First-run checks)
1. **Đăng nhập:** Mở `http://localhost:3000/login` (hoặc `http://localhost:8088/login`), nhấn nút **Đăng nhập với SSO**.
2. **Xác thực IdP:** Trình duyệt chuyển hướng đến giao diện đăng nhập Keycloak. Điền thông tin `owner@example.com` và mật khẩu `Password123!`.
3. **Trao đổi Token (Token Exchange):** Sau khi xác thực, Keycloak chuyển hướng về `/sso/callback?code=...&state=...`. Frontend tiêu thụ giao dịch PKCE một lần và gọi `POST /api/v1/auth/sso/exchange`. Spring API xác thực token từ Keycloak, liên kết danh tính SSO, cấp Access Token và Refresh Token cookie. Trình duyệt tự động chuyển vào màn hình chính.
4. **Khôi phục phiên (Session Rehydration):** Nhấn tải lại trang (F5) — phiên làm việc được tự động khôi phục nhờ refresh cookie.
5. **Đăng xuất (Logout):** Nhấn Đăng xuất — ứng dụng xóa phiên cục bộ và đưa về trang đăng nhập.
6. **Kiểm tra xử lý từ chối (Error handling):** Thử đăng nhập bằng một tài khoản Keycloak có email chưa từng được tạo trong OpenVie — hệ thống sẽ từ chối với mã lỗi `SSO_UNKNOWN_EMAIL` ("Tài khoản chưa được đăng ký trong hệ thống"). Frontend không tự động provision tài khoản.
### 6. Xử lý sự cố thường gặp

- **Cổng bị chiếm (Address already in use):** Kiểm tra tiến trình ngầm bằng `lsof -i :18000` hoặc `lsof -i :50051` và đóng chúng trước khi chạy `make dev`.
- **Windows (WSL2):** mọi lệnh POSIX trong tài liệu (`make`, `lsof`, `curl`, `chmod`…) chạy từ terminal Ubuntu WSL2; nếu thiếu `lsof` thì `sudo apt install lsof`. Kiểm tra port bị chiếm trên Windows host bằng PowerShell: `netstat -ano | findstr :18000` (thay số cổng tương ứng). Containers trỏ Ollama trên Windows host qua `host.docker.internal` (Bước 4).
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
| **OS / Shell** | Linux, macOS, or **Windows 10/11 via WSL2** (POSIX shell + GNU Make inside the WSL distro) |
| **Docker** | Docker Engine + Compose plugin (Linux) or Docker Desktop (macOS; Windows uses the WSL2 backend) |
| **Java** | JDK 21 (bundled Maven wrapper included) |
| **Python** | Python 3.11 or 3.12 |
| **Node.js** | Node.js 22 LTS with npm |
| **Ollama** | Native host installation for GPU/Metal hardware acceleration (see Step 4) |
| **TEI Reranker (optional)** | Docker container (any OS with Docker) or native binary (Apple Silicon) |

#### Quick Windows (WSL2) setup

On Windows 10 22H2 / Windows 11, run **all** commands from this guide inside the Ubuntu WSL2
terminal rather than PowerShell:

```powershell
wsl --install -d Ubuntu-24.04   # from PowerShell (Admin); reboot afterwards
```

1. Inside Ubuntu WSL2, install JDK 21, Python 3.11, Node 22, and Make in the distro (apt,
   sdkman, nvm) — do not use the Windows-native toolchains.
2. Docker Desktop → Settings → General → enable **Use the WSL 2 based engine** so containers
   share the WSL2 distro.
3. Clone the repo into the **WSL filesystem** (e.g. `~/projects/OpenVie`), not under
   `/mnt/c/...`: the Windows mount is far slower for Maven/npm/pytest.
4. Ollama still installs on the **Windows host** (see Step 4); from WSL2 and containers reach
   it at `localhost:11434` / `host.docker.internal:11434` as described.
5. Step 6 runs three applications at once — use **Windows Terminal** to open three Ubuntu WSL2
   tabs (Ubuntu profile from the dropdown) instead of three separate windows.

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

*Starting Keycloak & APISIX for SSO / Gateway verification:*
`dev-infra` skips Keycloak and the gateway by default to save system resources. To run and test SSO flows and gateway routing:
```bash
docker compose up -d keycloak-db keycloak apisix
```
Or start the complete development infrastructure:
```bash
docker compose up -d
docker compose --profile reranker up -d  # plus TEI Reranker (:8082), optional
```
This brings up Keycloak (`:18080`, automatically importing realm `openvie` from `keycloak/realm/openvie-realm.json`) and the APISIX Gateway (`:8088`, routing `/realms/*`, `/resources/*`, `/js/*` → Keycloak, `/api/v1/*` → host Spring `:8080`, `/admin/*` → `:3001`, `/*` → host Next.js `:3000`).

### Step 4: Install Ollama and prepare models
Download and install Ollama natively on the host (for GPU/Metal acceleration):
- **Linux / WSL2:**
  ```bash
  curl -fsSL https://ollama.com/install.sh | sh
  ```
- **macOS:** download from `https://ollama.com/download`, or:
  ```bash
  brew install --cask ollama
  ```
- **Windows:** download the installer from `https://ollama.com/download`, or:
  ```powershell
  winget install Ollama.Ollama
  ```
Verify with `ollama --version` and `http://127.0.0.1:11434`. On Windows,
containers reach Ollama through `host.docker.internal:11434` — if unreachable,
set `OLLAMA_HOST=0.0.0.0` and restart Ollama.

Download the standard weights:
```bash
ollama pull bge-m3
ollama pull hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
ollama cp hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M vylinh
ollama rm hf.co/QuantFactory/Arcee-VyLinh-GGUF:Q4_K_M
```

*Enable parallelism and warm memory:*
- Set `OLLAMA_NUM_PARALLEL=4` and `OLLAMA_MAX_LOADED_MODELS=3` in the platform service manager:
  - **Linux (systemd):** create a drop-in with `systemctl edit ollama`, adding
    `Environment="OLLAMA_NUM_PARALLEL=4"` and `Environment="OLLAMA_MAX_LOADED_MODELS=3"`, then
    `systemctl restart ollama`.
  - **macOS (launchd):** `launchctl setenv OLLAMA_NUM_PARALLEL 4` and `launchctl setenv
    OLLAMA_MAX_LOADED_MODELS 3`, then restart Ollama.
  - **Windows:** set system-wide variables and relaunch Ollama (quit the system-tray icon
    first):
    ```powershell
    setx OLLAMA_NUM_PARALLEL 4
    setx OLLAMA_MAX_LOADED_MODELS 3
    ```
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
The admin surface (`/admin/*` pages: organization, workspaces, settings,
requiring `ORG_OWNER`) runs on the same `:3000` dev server — e.g.
`http://localhost:3000/admin/organization`. Reason: `next dev` allows a single
instance per directory (`.next/dev` lockfile), so `:3000` + `:3001` cannot run
in parallel from one source tree.

To test the `openvie-admin` OIDC client on the single dev server, restart with
a different env (and switch back afterwards):
```bash
NEXT_PUBLIC_OIDC_CLIENT_ID=openvie-admin npm --prefix frontend run dev
```
The `:3001` separation only takes effect in production (the `frontend-admin`
service in compose) and through the `:8088/admin/*` gateway route. To test the
gateway locally, serve the admin surface from a production build:
```bash
NEXT_PUBLIC_OIDC_CLIENT_ID=openvie-admin npm --prefix frontend run build
PORT=3001 npm --prefix frontend run start
```
(note: this overwrites `.next`; rebuild normally afterwards).

`make -C api dev` generates Java/gRPC code from `proto/` into
`api/target/protobuf/` before compilation. This directory stays separate from
`generated-sources` so incremental Maven builds retain the protobuf sources
and classes.

To access the **dev** server through APISIX/ngrok, set
`NEXT_DEV_ALLOWED_ORIGINS=your-subdomain.ngrok-free.dev` (hostname only, no
`https://`) in `frontend/.env.local`, keep `NEXT_PUBLIC_API_BASE_URL=/api/v1`,
then **restart** Next.js. Run `docker compose up -d apisix` and `ngrok http 8088`;
open the HTTPS URL ngrok provides. Next.js allows `/_next/webpack-hmr`
WebSocket connections only from configured hosts; update the variable and
restart when the tunnel hostname changes. `localhost` and `127.0.0.1` are
already allowed. Do not use a public wildcard in this allowlist.

In `api/.env`, set
`ALLOWED_ORIGIN_PATTERNS=http://localhost:*,http://127.0.0.1:*,https://your-subdomain.ngrok-free.dev`
and restart the Spring API. Login POSTs include an `Origin` header; Spring
returns `403 Invalid CORS request` unless this HTTPS origin is allowed.

## 3. First-run setup and owner recovery

1. Navigate to `http://localhost:3000`. The client detects an uninitialized instance and routes to `/setup`.
2. Fill out organization name, owner credentials, and password (≥12 chars) to initialize `ORG_OWNER` and default workspace.
3. Offline recovery command:
```bash
make -C api recover-owner RECOVER_ARGS="--recover-owner-email=owner@example.com --recover-owner-password=YourSecurePassword123"
```

## 4. Optional local reranker

- **macOS (Apple Silicon, native Metal):**
  ```bash
  brew install text-embeddings-inference
  make -C rag-chatbot-fastapi dev-reranker-native
  ```
- **Linux / WSL2 / Windows (via Docker, needs Docker Engine or Docker Desktop):**
  ```bash
  make -C rag-chatbot-fastapi dev-reranker
  ```
  The first run pulls `ghcr.io/huggingface/text-embeddings-inference:cpu-1.9` and
  downloads the `Alibaba-NLP/gte-multilingual-reranker-base` checkpoint (~1 GB).
  (There is no official native Windows TEI binary — use the Docker route.)
Runs on `127.0.0.1:8082`. Controlled via `DEV_RERANKER_ENABLED` in `make dev`.

## Local OCR (Optional — for strong machines)

OCR is off by default; enable it to exercise the real recognition pipeline on
the dev machine (a 4-core box is fine, tesseract needs no GPU). Install tesseract:

```bash
brew install tesseract tesseract-lang   # macOS (tesseract-lang carries vie)
sudo apt install tesseract-ocr tesseract-ocr-vie tesseract-ocr-eng  # Debian/Ubuntu — also on Windows WSL2
```

Running Python directly on Windows (outside WSL2 — off the recommended path) uses the
UB-Mannheim build instead:

```powershell
winget install -e --id UB-Mannheim.TesseractOCR
```

During setup add the **Vietnamese (vie)** and **English (eng)** language packs; afterwards
`tesseract --list-langs` must list both `vie` and `eng` (the worker invokes tesseract from
PATH). Production needs none of this: the `ai-api` container ships tesseract with
`vie`/`eng` already installed.

Enable in `rag-chatbot-fastapi/.env` (worker — `make -C rag-chatbot-fastapi dev`):

```ini
INGESTION_OCR_ENABLED=true
# Heavy scan backlog: size for the machine (1-16)
INGESTION_OCR_PAGE_CONCURRENCY=4
# Embedded images (.docx/.xlsx + PDF pages with text): enable to test citations
# carrying a page number / sheet name
INGESTION_OCR_EMBEDDED_IMAGES=true
# Higher-quality alternative to tesseract (needs a dedicated GPU and a VLM model):
# INGESTION_OCR_PROVIDER=ollama-vlm
# OCR_VLM_MODEL_ID=your-vlm-model-in-ollama
```

Enable the image upload gate in `api/.env` (Spring): `OCR_UPLOADS_ENABLED=true`.

Verification:
1. Upload a scanned PDF (or a `.png`/`.jpg`) through the UI → the document shows
   the **OCR** badge (`text_source` is `ocr` or `mixed`), also visible in the
   `COMPLETED` event.
2. Upload a `.docx` with images (with `INGESTION_OCR_EMBEDDED_IMAGES=true`) → ask
   about its images citing `[S1]` → the sources panel shows the OCR badge and
   the page estimated from Word page-break markers.
3. Re-upload the same file → the OCR cache skips recognition (fingerprint hit),
   ingestion returns to the usual duration.
4. Comment the variables out again → behavior returns to the byte-identical
   legacy path; the backend checks (`make -C rag-chatbot-fastapi check`) stay
   green under either configuration.

Knob details and ceilings (DPI, megapixels, timeout, 3 consecutive provider
failures): `docs/RETRIEVAL.md` §7.

## 5. Verification

| Area | Command |
| --- | --- |
| **Java** | `make -C api test` or `make -C api verify` |
| **Python** | `make -C rag-chatbot-fastapi check PYTHON=python3.11` |
| **Frontend** | `npm --prefix frontend run lint` && `npm --prefix frontend run typecheck` |

### Local SSO Run & First-Run Verification Guide

OpenVie supports Single Sign-On (SSO) via OIDC (Authorization Code flow with PKCE S256) integrated with Keycloak. Follow these steps to verify the SSO workflow on a fresh clone:

#### Step A: Start Keycloak Services
1. Start Keycloak and its dedicated database (or start the full stack including APISIX):
   ```bash
   docker compose up -d keycloak-db keycloak apisix
   ```
   *Note:* Keycloak automatically imports the `openvie` realm from `keycloak/realm/openvie-realm.json` (pre-configured with `openvie-frontend`, `openvie-admin`, and `openvie-backend` clients).
2. Verify Keycloak is ready:
   ```bash
   curl --fail http://localhost:18080/realms/openvie/.well-known/openid-configuration
   ```

#### Step B: Configure Environment Variables
1. In `api/.env`:
   ```ini
   SSO_ENABLED=true
   SSO_ISSUER_URI=http://localhost:18080/realms/openvie
   SSO_FRONTEND_CLIENT_ID=openvie-frontend
   SSO_BACKEND_CLIENT_ID=openvie-backend
   ```
2. In `frontend/.env.local`:
   ```ini
   NEXT_PUBLIC_OIDC_ISSUER=http://localhost:18080/realms/openvie
   NEXT_PUBLIC_OIDC_CLIENT_ID=openvie-frontend
   ```
   *(If accessing via the APISIX gateway on port 8088, set the issuer to `http://localhost:8088/realms/openvie`).*
3. Start the host applications per Step 6 (Spring API `:8080`, Python `:18000`, Frontend `:3000`).

#### Step C: Prepare Matching Accounts
1. **Initialize OpenVie:** Navigate to `http://localhost:3000` (or `http://localhost:8088`). Complete `/setup` to create the initial organization and owner account (e.g. `owner@example.com`).
2. **Create Corresponding Keycloak User:**
   - Open Keycloak Admin Console at `http://localhost:18080` (credentials: `admin` / `change-me-keycloak-admin`).
   - Select the **openvie** realm from the top-left dropdown.
   - Go to **Users** -> click **Add user**:
     - **Username:** `owner` (or `owner@example.com`)
     - **Email:** `owner@example.com` *(must match the email registered in OpenVie)*
     - **Email verified:** toggle **On**
     - Click **Create**.
   - Navigate to the **Credentials** tab -> click **Set password**:
     - **Password:** e.g. `Password123!`
     - **Temporary:** toggle **Off**
     - Click **Save**.

#### Step D: Run SSO First-Run Checks
1. **Sign In:** Open `http://localhost:3000/login` (or `http://localhost:8088/login`), click **Sign in with SSO**.
2. **IdP Authentication:** Browser redirects to Keycloak's login screen. Enter `owner@example.com` and `Password123!`.
3. **Token Exchange:** Keycloak redirects back to `/sso/callback?code=...&state=...`. The frontend validates the one-time PKCE transaction and calls `POST /api/v1/auth/sso/exchange`. Spring API validates the token, links the SSO identity, issues an Access Token, and sets a secure Refresh Token cookie. Browser enters the dashboard.
4. **Session Rehydration:** Refresh the page (F5) — the session is smoothly restored via the refresh cookie.
5. **Sign Out:** Click Sign out — the application clears local session and returns to login.
6. **Rejection Handling:** Attempting to log in with a Keycloak account whose email is not registered in OpenVie is rejected with `SSO_UNKNOWN_EMAIL`. The frontend never auto-provisions accounts.
## 6. Troubleshooting

- **Port conflicts:** Terminate existing processes on 18000 or 50051 before starting `make dev`.
- **Windows (WSL2):** run the POSIX commands in this guide (`make`, `lsof`, `curl`, `chmod`…) from the Ubuntu WSL2 terminal; if `lsof` is missing, `sudo apt install lsof`. To find a port grabber on the Windows host use PowerShell: `netstat -ano | findstr :18000` (adjust the port). Containers reach the Windows-host Ollama via `host.docker.internal` (Step 4).
- **Database connectivity:** Check container health via `docker compose ps`. Service-level `.env` files must reference `localhost` ports rather than container names.
- **Graceful shutdown:** Stop host applications with `Ctrl-C`, then bring down containers with `make -C rag-chatbot-fastapi dev-down`. Avoid `docker compose down -v` to prevent volume loss.

## 7. Development vs Production

- **Development:** `docker-compose.yml` (via `make dev-infra`) runs stateful databases only; applications run directly on the host with live reload.
- **Production:** `docker-compose.prod.yml` containerizes the entire stack (data, backend, AI, frontend, APISIX) in a single deployment. See [DEPLOYMENT.md](DEPLOYMENT.md).
