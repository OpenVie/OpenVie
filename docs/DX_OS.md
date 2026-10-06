# OpenVie và khung kiến trúc DX-OS (Open-Core)

[Documentation index](README.md) · [Architecture](ARCHITECTURE.md) · [Deployment](DEPLOYMENT.md) · [Open-source boundary](OPEN_SOURCE.md)

Tài liệu này đối chiếu mã nguồn OpenVie với khung kiến trúc **Hệ điều hành Chuyển đổi số
(DX-OS)** và mô hình **Open-Core** dùng trong cuộc thi Phần mềm nguồn mở (OLP) 2026.

Mọi nhận định dưới đây được kiểm chứng trực tiếp trên mã nguồn. Mục nào chưa có trong kho mã
được ghi rõ là **chưa cung cấp**, không suy đoán và không mô tả tính năng tương lai như tính năng
đã chạy. Khi phát biểu trong phần trình bày, chỉ dùng cột "Trạng thái" của bảng dưới đây.

## Nội dung

- [Vị trí trong tháp 3 tầng DX-OS](#vị-trí-trong-tháp-3-tầng-dx-os)
- [Nguyên tắc lõi chạy ngầm (headless)](#nguyên-tắc-lõi-chạy-ngầm-headless)
- [Bốn dịch vụ lõi bắt buộc](#bốn-dịch-vụ-lõi-bắt-buộc)
- [Đánh nhãn H-P-D-I cho giao diện Tầng 2](#đánh-nhãn-h-p-d-i-cho-giao-diện-tầng-2)
- [Bốn nguyên lý thiết kế](#bốn-nguyên-lý-thiết-kế)
- [Bảy lớp công nghệ tham chiếu](#bảy-lớp-công-nghệ-tham-chiếu)
- [Khoảng trống và thứ tự đóng](#khoảng-trống-và-thứ-tự-đóng)
- [Ranh giới trung thực](#ranh-giới-trung-thực)

## Vị trí trong tháp 3 tầng DX-OS

| Tầng DX-OS | Thành phần trong OpenVie | Trạng thái |
| --- | --- | --- |
| **Tầng 1 – Nền tảng Dịch vụ Lõi số** (PaaS, headless) | [`api/`](../api/) (Spring Boot 21), [`rag-chatbot-fastapi/`](../rag-chatbot-fastapi/) (AI HTTP + gRPC + document worker), [`apisix/`](../apisix/) (cửa ngõ), [`docker-compose.yml`](../docker-compose.yml) (PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu) | Một phần — xem [Bốn dịch vụ lõi bắt buộc](#bốn-dịch-vụ-lõi-bắt-buộc) |
| **Tầng 2 – Không gian Năng lực số** (H-P-D-I, có giao diện) | [`frontend/`](../frontend/) (Next.js), routes xem [bảng H-P-D-I](#đánh-nhãn-h-p-d-i-cho-giao-diện-tầng-2) | **Đạt**: có ứng dụng minh họa giao diện |
| **Tầng 3 – Nền tảng Kinh doanh số** (OMS/WMS/POS/LMS/CRM…) | Không có | Ngoài phạm vi; không phát biểu trong phần trình bày |

## Nguyên tắc lõi chạy ngầm (headless)

Nguyên tắc của đề thi: nền tảng lõi **không cung cấp giao diện người dùng cuối**, chỉ làm PaaS
cung cấp dịch vụ cho các ứng dụng ở tầng trên (giống Supabase / Strapi / Directus).

- `api/` chỉ xuất ra HTTP JSON tại `/api/v1/**` ([`AuthController`](../api/src/main/java/com/cacanode/api/auth/controller/AuthController.java),
  [`ChatController`](../api/src/main/java/com/cacanode/api/chat/controller/ChatController.java),
  [`DocumentController`](../api/src/main/java/com/cacanode/api/document/controller/DocumentController.java),
  [`WorkspaceController`](../api/src/main/java/com/cacanode/api/tenant/controller/WorkspaceController.java)…)
  và gRPC nội bộ theo [`proto/cacanode_ai_v1.proto`](../proto/). Không có màn hình quản trị riêng
  của tầng lõi.
- `rag-chatbot-fastapi/` chỉ phục vụ HTTP/gRPC máy-với-máy (`/actuator/prometheus`, gRPC `:50051`,
  graph service `:8010`).
- Toàn bộ giao diện người dùng nằm ở `frontend/`, tức là **ứng dụng Tầng 2**, tách khỏi lõi.
- Cửa ngõ [`apisix/`](../apisix/) chỉ công bố hai bề mặt trình bày: web client và `/api/v1/*`.
  AI HTTP (`:18000`), gRPC nội bộ (`:50051`), PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS và
  graph service **không được định tuyến ra biên mạng**.

Kết luận khi trình bày: **nguyên tắc headless được tuân thủ ở phần lõi do đội xây dựng**; các sản
phẩm nguồn mở bên ngoài (nếu thêm Keycloak, APISIX ở các mục sau) có giao diện quản trị riêng của
họ là bình thường — chúng là "sản phẩm lõi công nghệ mở" được tích hợp, không phải UI của nền tảng.

## Bốn dịch vụ lõi bắt buộc

Đề thi (Mục 3) bắt buộc nền tảng lõi cung cấp: Định danh/SSO, Cổng API, Quản trị dữ liệu
cấu trúc/phi cấu trúc, Động cơ luồng công việc.

| Dịch vụ lõi | Thành phần hiện có (kiểm chứng trong repo) | Trạng thái | Việc cần làm |
| --- | --- | --- | --- |
| **Định danh, SSO** | Xác thực bằng mật khẩu + JWT [`SecurityConfig.java`](../api/src/main/java/com/cacanode/api/bootstrap/config/SecurityConfig.java), [`JwtServiceImpl.java`](../api/src/main/java/com/cacanode/api/auth/service/implement/JwtServiceImpl.java); phân quyền 3 cấp `ORG_OWNER` / `WORKSPACE_ADMIN` / `MEMBER`; làm mới token xoay vòng; invitation; bảng `users`, `workspace_members` | **Chưa đạt** — chưa có SSO/OIDC, chưa có MFA (mật khẩu thuần) | Thêm Keycloak (IdP, MFA) và biến Spring API thành OIDC resource server. Dependency `spring-boot-starter-security-oauth2-resource-server` đã có sẵn trong [`api/pom.xml`](../api/pom.xml) nhưng chưa dùng |
| **Cổng API (API Gateway)** | **Apache APISIX** chạy standalone (không etcd, không Admin API) trên `127.0.0.1:8088`: [`apisix/config.yaml`](../apisix/config.yaml) + [`apisix/routes.yaml`](../apisix/routes.yaml) ghép vào service `apisix` của [`docker-compose.yml`](../docker-compose.yml). Định tuyến `/api/v1/*` → Spring, `/*` → web client, health probe `/apisix/status`, và **giới hạn tần suất ở biên** (`limit-req`: route auth 5 r/s burst 10, route API 50 r/s burst 100 → HTTP 429). Kết hợp với rate-limit trong ứng dụng ở [`PublicRateLimitFilter.java`](../api/src/main/java/com/cacanode/api/common/filter/PublicRateLimitFilter.java) | **Đạt** | Chi tiết vận hành: [DEPLOYMENT mục 9](DEPLOYMENT.md#9-edge-gateway-apache-apisix). Muốn nâng cao: xác thực JWT tập trung tại gateway, WAF (Coraza), quét mã độc (ClamAV), TLS |
| **Quản trị dữ liệu cấu trúc** | PostgreSQL + Flyway (20 bảng: `organizations`, `tenants`, `users`, `workspace_members`, `invitations`, `knowledge_bases`, `documents`, `chat_*`, `audit_logs`, `notifications`, outbox/inbox …), ràng buộc `tenant_id` trên mọi bảng nghiệp vụ, chỉ mục tổ hợp, kiểu dữ liệu có cấu trúc | **Đạt** | — |
| **Quản trị dữ liệu phi cấu trúc** | SeaweedFS (lưu trữ đối tượng S3), Qdrant (vector + tìm kiếm ngữ nghĩa), Kuzu (đồ thị tri thức), quy trình hấp thụ bất đồng bộ PDF/DOCX/TXT/MD/HTML/CSV/XLSX kèm nguồn gốc tài liệu | **Đạt** | OCR/ảnh/âm thanh **chưa** cung cấp ([`README.md`](../README.md)) |
| **Động cơ luồng công việc (Workflow)** | Không có trong repo (`flowable` / `camunda` / `bpmn` / `n8n` = 0 kết quả). Hàng đợi phê duyệt bị ghi ngoài phạm vi v0.2 ở [`docs/WORKSPACES_PLAN.md`](WORKSPACES_PLAN.md) | **Chưa đạt** | Thêm Flowable (BPMN) hoặc n8n, dùng bài toán phê duyệt tài liệu / tham gia workspace làm minh họa, kèm trang danh sách tác vụ ở Tầng 2 |

> Yêu cầu tối thiểu của đề: **đủ 4 dịch vụ lõi**. Đã đạt **3/4**: Cổng API (Apache APISIX),
> dữ liệu cấu trúc và dữ liệu phi cấu trúc. Còn thiếu **Định danh/SSO** và **Động cơ luồng
> công việc** — hai mục này là phần việc chính còn lại.

## Đánh nhãn H-P-D-I cho giao diện Tầng 2

Đề thi yêu cầu **ít nhất một ứng dụng minh họa giao diện** nằm trong một không gian H-P-D-I của
Tầng 2. Các trang hiện có của `frontend/` (định tuyến xem
[`navigation.ts`](../frontend/src/components/app/navigation.ts)):

| Không gian năng lực | Trong OpenVie | Routes | Trạng thái |
| --- | --- | --- | --- |
| **[I] Trí tuệ số** — trợ lý ảo vận hành trên tri thức nội bộ (RAG) | Chat có trích dẫn nguồn, truy hồi hỗn hợp vector dày/mảnh + đồ thị + xếp hạng lại, điều hướng câu hỏi nhạy cảm, tìm kiếm ngữ nghĩa trên kho tri thức | `/` (chat), `/documents/[documentId]` | **Đạt** — ứng dụng minh họa dùng để dự thi |
| **[H] Con người số** — môi trường số, tri thức, cộng tác | Kho tài liệu + xem trước/tải xuống, bách khoa tài liệu hướng dẫn, quản trị thành viên và không gian làm việc | `/documents`, `/documentation`, `/users`, `/workspaces` | **Đạt** |
| **[P] Quy trình số** — chuẩn hóa luồng nghiệp vụ | Thiết lập tổ chức một lần, phân quyền 3 cấp, mời/tham gia không gian, kênh thông báo, nhật ký kiểm toán (`audit_logs`) | `/settings`, `/users`, `/workspaces` | **Một phần** — có quản trị quy tắc nhưng **chưa có động cơ BPMN/phê duyệt** |
| **[D] Dữ liệu số** — khai thác, phân tích, ra quyết định | Nguồn gốc từng tài liệu, nhật ký kiểm toán, số liệu Prometheus của ứng dụng | Chưa có trang bảng điều hành/IOC-BI | **Chưa đạt** — chưa có cổng dữ liệu hay trực quan hóa |

Với yêu cầu "ít nhất một", `[I]` và `[H]` đã đủ điều kiện. `[D]` là ứng viên rẻ nhất để mở rộng
(nhúng Metabase hoặc một trang biểu đồ), `[P]` mở rộng cùng lúc với động cơ Workflow.

## Bốn nguyên lý thiết kế

| # | Nguyên lý (Mục 4 đề thi) | Hiện trạng trong repo | Trạng thái |
| --- | --- | --- | --- |
| **(i)** | Tách theo vòng đời và tốc độ dữ liệu: OLTP thời gian thực ⇒ tự đẩy dữ liệu lịch sử xuống hồ phân tích | Chỉ có PostgreSQL cho tác nghiệp. Không có CDC/luồng sự kiện xuống hồ, không có lakehouse | **Chưa đạt** — cần Debezium/luồng sự kiện → đối tượng/lakehouse cho tầng phân tích |
| **(ii)** | Hội tụ dữ liệu hành chính (IT) và vận hành (OT): cảm biến hiện trường, bản sao số thời gian thực | Không có nguồn OT nào (không MQTT, không NGSI-LD, không digital twin) | **Chưa đạt** — hoặc bổ sung trình kết nối cảm biến, hoặc xác định rõ phạm vi bài toán là IT |
| **(iii)** | Quản trị dữ liệu thế hệ mới: phả hệ, nguồn gốc, chất lượng | Nguồn gốc tài liệu khi hấp thụ; bảng `audit_logs` + danh mục hành vi [`LogAction`](../api/src/main/java/com/cacanode/api/common/enums/LogAction.java); outbox/inbox sự kiện | **Một phần** — chưa có phả hệ dữ liệu (lineage) và kiểm soát chất lượng trên báo cáo |
| **(iv)** | Zero-Trust + GitOps, kiểm dịch mã độc tại biên, cấu hình hạ tầng bằng mã, tự phục hồi | JWT + phân quyền theo không gian làm việc, **giới hạn tần suất ở biên qua APISIX `limit-req`** ([`apisix/routes.yaml`](../apisix/routes.yaml)) + rate-limit trong ứng dụng [`PublicRateLimitFilter`](../api/src/main/java/com/cacanode/api/common/filter/PublicRateLimitFilter.java), AI/hạ tầng dữ liệu không lộ ra cửa ngõ, token nội bộ cho gRPC, CI kiểm tra (`.github/workflows/ci.yml`), metrics Prometheus (Spring + Python) | **Một phần** — chưa có WAF/kiểm dịch tệp, chưa có Vault (cấu hình còn nằm ở `.env`), chưa có GitOps/deploy tự động, chưa có sao lưu ([`DEPLOYMENT.md`](DEPLOYMENT.md): "ships no backup or restore scripts") |

## Bảy lớp công nghệ tham chiếu

Bảng này chỉ là **lớp công nghệ tham chiếu của đề thi**; đề cho phép toàn quyền chọn công nghệ.
Cột cuối ghi đúng những gì có trong kho mã.

| Lớp | Công nghệ tham chiếu của đề | OpenVie hiện dùng | Trạng thái |
| --- | --- | --- | --- |
| 1 – Trải nghiệm & giao diện đa kênh | Nuxt 3/Vue 3, Flutter, Centrifugo, Tailwind CSS | Next.js + React + Tailwind CSS ([`frontend/`](../frontend/)); chưa có app di động, chưa có truyền phát WebSocket (chỉ poll định kỳ, SSE đã loại khỏi mục tiêu [`ARCHITECTURE.md`](ARCHITECTURE.md)) | Một phần |
| 2 – Biên mạng, cửa ngõ & an toàn thông tin | APISIX, Keycloak, Vault, ClamAV, Coraza WAF | **Apache APISIX** làm cửa ngõ + `limit-req` ở biên ([`apisix/`](../apisix/)), Spring Security + JWT, rate-limit trong ứng dụng; **chưa có IdP/WAF/Vault** | Một phần |
| 3 – Tác nghiệp, logic & trạng thái thực thể | Hasura, PostgreSQL(+PostGIS), MongoDB, Orion | PostgreSQL + Flyway, hàng đợi RabbitMQ, gRPC tới Python; chưa có PostGIS/MongoDB/Orion | Một phần |
| 4 – Tự động hóa, quy trình & DevSecOps | ArgoCD/GitLab CI, Flowable, n8n, Kestra, Velero | CI GitHub Actions (chỉ kiểm tra, không triển khai); **chưa có BPMN, chưa có sao lưu** | Một phần |
| 5 – Dữ liệu siêu hội tụ, truyền phát & sổ cái | Redpanda, Iceberg, MinIO, ImmuDB | RabbitMQ (hàng đợi công việc), SeaweedFS (đối tượng); chưa có trục streaming/lakehouse/sổ cái bất biến | Một phần |
| 6 – Phục vụ phân tích, không gian & trí tuệ | Trino/DuckDB, GeoServer, Metabase/Cube.dev, Ollama/vLLM | Ollama tương thích OpenAI (sinh văn bản + embedding BGE-M3), Qdrant (vector), Kuzu (đồ thị); chưa có GIS, chưa có BI | Một phần |
| 7 – Quản trị, tìm kiếm & giám sát | Vector/Parseable, OpenMetadata, Meilisearch/Qdrant, Prometheus/Grafana | Qdrant (tìm kiếm ngữ nghĩa), metrics Prometheus ở Spring và Python; chưa có log tập trung, chưa có dòng chữ ký/phả hệ, chưa có Grafana | Một phần |

## Khoảng trống và thứ tự đóng

Thứ tự ưu tiên đề xuất (dễ → khó), dùng để lên tiến độ:

1. **Tài liệu hóa khung HPDI** — tài liệu này, 0 thay đổi mã. *(đã làm)*
2. **Cổng API (APISIX)** — bổ sung, thuần cấu hình, không sửa mã nghiệp vụ; ghi điểm cả dịch vụ
   lõi bắt buộc lẫn nguyên lý (iv). *(đã làm — [`apisix/`](../apisix/), xem
   [DEPLOYMENT mục 9](DEPLOYMENT.md#9-edge-gateway-apache-apisix))*
3. **Luồng công việc** — n8n (thuần cấu hình) cho bằng chứng nhanh, sau đó Flowable (BPMN) gắn
   vào `api/` kèm trang tác vụ ở Tầng 2 `[P]`.
4. **Sao lưu + Vault + endpoint đọc nhật ký kiểm toán** — nguyên lý (iv) và (iii).
5. **SSO (Keycloak) + MFA** — bắt buộc nhưng xâm lấn nhất: thay `SecurityConfig`, `JwtAuthFilter`,
   luồng đăng nhập ở `frontend`.
6. **CDC → hồ dữ liệu** (nguyên lý i) và **kết nối OT/digital twin** (nguyên lý ii) — hai mục tốn
   hạ tầng nhất; nếu không làm, phải nói rõ phạm vi là IT.

## Ranh giới trung thực

- "Đã có" nghĩa là **mã nguồn tồn tại trong kho**, không đồng nghĩa đã tải thử, đã cấu hình cho
  sản xuất hay đã được kiểm định. Xem [`ARCHITECTURE.md`](ARCHITECTURE.md) (mục retired claims) và
  [`docs/OPEN_SOURCE.md`](OPEN_SOURCE.md).
- OpenVie là phần mềm nguồn mở **Apache License 2.0** ([`LICENSE`](../LICENSE),
  [`NOTICE`](../NOTICE)).
- Các tài liệu chuyên sâu khác: [phát triển](DEVELOPMENT.md), [kiến trúc](ARCHITECTURE.md),
  [hấp thụ & truy hồi](RETRIEVAL.md), [triển khai](DEPLOYMENT.md), [biên giới nguồn mở](OPEN_SOURCE.md).
