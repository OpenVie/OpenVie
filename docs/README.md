# OpenVie documentation

[Project overview](../README.md) · [Local development](DEVELOPMENT.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#tài-liệu-openvie--tiếng-việt) · [English](#openvie-documentation-1)

---

## Tài liệu OpenVie — tiếng Việt

[Project overview](../README.md) · [Phát triển cục bộ](DEVELOPMENT.md)

Chọn hướng dẫn phù hợp với việc bạn cần làm.

## Lộ trình cho người mới

1. Đọc [tổng quan dự án và sơ đồ repo](../README.md).
2. Làm theo [phát triển cục bộ](DEVELOPMENT.md) để cấu hình, khởi động và kiểm tra toàn bộ stack.
3. Đọc [kiến trúc](ARCHITECTURE.md) để hiểu service nào sở hữu dữ liệu gì và ranh giới giữa các service.
4. Đọc hướng dẫn cho phần mã bạn sẽ sửa:
   - [Hướng dẫn module Java](../api/GUIDE.md): hợp đồng module công khai, sự kiện và quyền sở hữu bảng.
   - [Hướng dẫn module Python](../rag-chatbot-fastapi/GUIDE.md): các module năng lực, transport và vai trò runtime.
   - [Phát triển web và kiểm tra](DEVELOPMENT.md#2-các-bước-thiết-lập).
5. Chỉ tra tài liệu tính năng khi thay đổi của bạn thực sự cần.
6. Xem lại [ranh giới nguồn mở và phân phối](OPEN_SOURCE.md) trước khi đóng góp.

## Hướng dẫn theo việc

| Tôi muốn... | Đọc |
| --- | --- |
| Chạy cục bộ, nắm cấu hình, chạy kiểm tra | [Phát triển cục bộ](DEVELOPMENT.md) |
| Hiểu service nào sở hữu gì, lưu trữ, bảo mật và các chuỗi runtime | [Kiến trúc](ARCHITECTURE.md) |
| Hiểu luồng phê duyệt tài liệu, SLA và việc ghi kết quả | [Workflow](WORKFLOW.md) |
| Sửa phần parse tài liệu, đánh chỉ mục, model hoặc truy hồi | [Nạp và truy hồi](RETRIEVAL.md) |
| Cấu hình bản tự triển khai hoặc lập kế hoạch kiểm chứng, phục hồi | [Triển khai](DEPLOYMENT.md) |
| Gom về một điểm vào HTTP hoặc giới hạn lưu lượng ở biên | [Triển khai §10 — Edge gateway](DEPLOYMENT.md#10-edge-gateway-apache-apisix) |
| Tự triển khai, đánh giá giấy phép hoặc xuất bản mã nguồn có review | [Ranh giới nguồn mở và phân phối](OPEN_SOURCE.md) |
| Cấu hình IdP Keycloak, OIDC client và ranh giới SSO | [Cấu hình Keycloak](KEYCLOAK.md) |
| Chọn hoặc đánh giá model cho chat, phân loại ý định, trích slot và soạn thảo BizApp | [Định tuyến model AI](AI_MODEL_ROUTING.md) |
| Tích hợp ứng dụng của bạn vào lõi headless (API key, webhook, chat đồng bộ, engine BPMN) | [Tích hợp](INTEGRATION.md) |
| Xây dựng/tùy biến BizApp, composer trực quan, báo cáo và đồng bộ tri thức | [BizApps](BIZAPPS.md) |

## Mỗi tài liệu dành cho việc gì

- **README gốc:** định hướng, phạm vi hiện tại, sơ đồ repo và liên kết; không phải cẩm nang API đầy đủ.
- **Hướng dẫn service:** quy tắc triển khai và quyền sở hữu module theo từng ngôn ngữ, đặt ngay cạnh mã nguồn.
- **Hướng dẫn phát triển:** thiết lập, nguồn cấu hình, các lệnh chính và xử lý sự cố cục bộ.
- **Tài liệu chuyên đề:** hành vi hiện tại, ví dụ, ranh giới và liên kết tới phần triển khai.

## Trạng thái triển khai và bằng chứng

"Đã triển khai" chỉ có nghĩa là trong repo đã có đường mã; không có nghĩa tính năng đã được bật,
đã cấu hình, đã đo tải hay đã được duyệt cho production. Cụ thể:

- Bộ đệm tùy chọn cần được bật rõ ràng và có bằng chứng rollout.
- OCR (ảnh/PDF scan) và ASR (âm thanh/video) là các bước tùy chọn, mặc định tắt.
- Một health response, fixture hay artifact smoke cũ không chứng minh toàn bộ workflow chạy đúng.

Xem [RETRIEVAL.md](RETRIEVAL.md) để biết chi tiết hành vi nạp tài liệu, và
[DEPLOYMENT.md](DEPLOYMENT.md) để biết yêu cầu kiểm chứng khi tự triển khai.

## Đặt tên và thương hiệu

OpenVie là tên sản phẩm và định danh thống nhất của toàn bộ hệ thống. Các package (`com.openvie.api`),
hợp đồng protobuf (`openvie.ai.v1`), tài nguyên triển khai, hàng đợi RabbitMQ và cấu hình hệ thống
đều dùng định danh `openvie`.
Mã nguồn ứng dụng OpenVie theo giấy phép Apache-2.0, bản quyền thuộc LinkedNodeDigital; xem
[LICENSE](../LICENSE), [NOTICE](../NOTICE) và [publication gate](OPEN_SOURCE.md#publication-gate).

## Cách bảo trì tài liệu

1. Mỗi chủ đề chỉ có một nơi duy nhất làm nguồn chuẩn; hãy liên kết tới đó thay vì sao chép danh mục cấu hình.
2. Đối chiếu các phát biểu về hành vi hiện tại với mã nguồn, schema, Makefile, script trong
   package.json và cấu hình triển khai.
3. Tách rõ hành vi đã có, tính năng đang tắt và yêu cầu vận hành.
4. Khi chuyển một mục, cập nhật liên kết tương đối, heading, điều hướng và các liên kết trỏ tới mục đó.
5. Kiểm tra Markdown, sơ đồ và các lệnh hiển thị đúng; ghi rõ những bước kiểm tra runtime/provider chưa chạy.

Định dạng trao đổi dùng chung nằm ở [`contracts/`](../contracts/) (schema và fixture JSON)
và [`proto/`](../proto/) (gRPC). Tài liệu chỉ giải thích các ranh giới đó, không thay thế định
nghĩa máy đọc được hay các kiểm tra tương thích.

---

# OpenVie documentation

[Project overview](../README.md) · [Local development](DEVELOPMENT.md)

Start with the guide for your task.

## New developer reading path

1. Read the [project overview and repository map](../README.md).
2. Follow [local development](DEVELOPMENT.md) to configure, start, and check the stack.
3. Read [architecture](ARCHITECTURE.md) for service ownership and cross-service boundaries.
4. Follow the guide for the code you will change:
   - [Java module guide](../api/GUIDE.md): public module contracts, events, and table ownership.
   - [Python module guide](../rag-chatbot-fastapi/GUIDE.md): capability modules, transports, and runtime roles.
   - [Web development and checks](DEVELOPMENT.md#2-step-by-step-setup).
5. Consult the feature reference only when your change needs it.
6. Review the [open-source and distribution boundary](OPEN_SOURCE.md) before contributing to this repository.

## Guides by task

| I want to... | Read |
| --- | --- |
| Run locally, understand configuration, or execute checks | [Local development](DEVELOPMENT.md) |
| Understand service ownership, storage, security, or runtime sequences | [Architecture](ARCHITECTURE.md) |
| Understand document approval, SLA, and decision write-back | [Workflow](WORKFLOW.md) |
| Change document parsing, indexing, models, or retrieval | [Ingestion and retrieval](RETRIEVAL.md) |
| Configure a self-hosted release or plan verification and recovery | [Deployment](DEPLOYMENT.md) |
| Expose one HTTP entry point or rate-limit traffic at the edge | [Deployment §10 — Edge gateway](DEPLOYMENT.md#10-edge-gateway-apache-apisix) |
| Self-host, assess licensing, or publish a reviewed source export | [Open-source and distribution boundary](OPEN_SOURCE.md) |
| Configure Keycloak IdP, OIDC clients, and SSO boundaries | [Keycloak configuration](KEYCLOAK.md) |
| Select or evaluate models for chat, intent, slots, and BizApp drafting | [AI model routing](AI_MODEL_ROUTING.md) |
| Integrate your own app with the headless core (API keys, webhooks, synchronous chat, BPMN engine) | [Integration](INTEGRATION.md) |
| Build or customize BizApps, the visual composer, reports, and knowledge projection | [BizApps](BIZAPPS.md) |

## What belongs where

- **Root README:** orientation, current scope, repository map, and links. Not the full API manual.
- **Service guides:** language-specific implementation and module ownership rules. These stay beside the code.
- **Development guide:** setup, configuration sources, command entry points, and local troubleshooting.
- **Topic references:** detailed current behavior, examples, boundaries, and implementation links.

## Implementation status and evidence

“Implemented” means the repository contains a code path, not that the feature is enabled,
configured, load-tested, or approved for production. In particular:

- Optional caches require explicit configuration and rollout evidence.
- OCR (images/scanned PDFs) and ASR (audio/video) are optional stages, disabled by default.
- A health response, fixture, or historical smoke artifact is not proof of full workflow correctness.

Use [RETRIEVAL.md](RETRIEVAL.md) for detailed ingestion behavior, and
[DEPLOYMENT.md](DEPLOYMENT.md) for verification requirements on a self-hosted install.

## Naming and branding

OpenVie is the unified product and technical identifier across the platform. Internal packages (`com.openvie.api`),
protobuf contracts (`openvie.ai.v1`), deployment resources, message queues, and service configurations
standardize on `openvie`.
OpenVie application source is Apache-2.0 under LinkedNodeDigital copyright; see
[LICENSE](../LICENSE), [NOTICE](../NOTICE), and the [publication gate](OPEN_SOURCE.md#publication-gate).

## Maintaining these docs

1. Give a topic one canonical home and link to it instead of copying configuration catalogs.
2. Verify current-behavior statements against source, schemas, Makefiles, package scripts, and deployment configuration.
3. Separate implemented behavior, disabled features, and operational requirements.
4. When moving a section, update relative source links, headings, navigation, and inbound links.
5. Verify rendered Markdown, diagrams, and command entry points; report any runtime/provider checks not exercised.

Shared wire-format definitions remain in [`contracts/`](../contracts/) (JSON schemas and fixtures)
and [`proto/`](../proto/) (gRPC). Documentation explains those boundaries; it does not replace their
machine-readable definitions or compatibility checks.
