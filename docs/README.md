# OpenVie documentation

[Project overview](../README.md) · [Local development](DEVELOPMENT.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#tài-liệu-openvie--tiếng-việt) · [English](#openvie-documentation-1)

---

## Tài liệu OpenVie — tiếng Việt

[Project overview](../README.md) · [Phát triển cục bộ](DEVELOPMENT.md)

Bắt đầu từ guide đúng với việc bạn cần làm.

## Lộ trình đọc cho người mới

1. Đọc [tổng quan dự án và sơ đồ repo](../README.md).
2. Làm theo [phát triển cục bộ](DEVELOPMENT.md) để cấu hình, khởi động và kiểm tra stack.
3. Đọc [kiến trúc](ARCHITECTURE.md) để hiểu quyền sở hữu service và ranh giới liên service.
4. Đọc guide cho phần code bạn sẽ sửa:
   - [Java module guide](../api/GUIDE.md): hợp đồng module công khai, sự kiện, quyền sở hữu bảng.
   - [Python module guide](../rag-chatbot-fastapi/GUIDE.md): module năng lực, transport, vai trò runtime.
   - [Phát triển web và kiểm tra](DEVELOPMENT.md#start-the-applications).
5. Chỉ tra tài liệu tính năng khi thay đổi của bạn cần đến nó.
6. Đọc [ánh xạ DX-OS / Open-Core](DX_OS.md) trước khi trình bày hay so sánh repo này với
   kiến trúc ba tầng DX-OS.
7. Xem lại [ranh giới nguồn mở và phân phối](OPEN_SOURCE.md) trước khi xuất một repo công khai.

## Guide theo việc

| Tôi muốn... | Đọc |
| --- | --- |
| Chạy cục bộ, hiểu cấu hình, chạy kiểm tra | [Phát triển cục bộ](DEVELOPMENT.md) |
| Hiểu quyền sở hữu service, lưu trữ, bảo mật, chuỗi runtime | [Kiến trúc](ARCHITECTURE.md) |
| Sửa parsing tài liệu, đánh chỉ mục, model, truy hồi | [Hấp thụ và truy hồi](RETRIEVAL.md) |
| Cấu hình bản self-hosted hoặc lập kế hoạch kiểm chứng và phục hồi | [Triển khai](DEPLOYMENT.md) |
| Mở một điểm vào HTTP duy nhất hoặc giới hạn lưu lượng ở biên | [Triển khai §9 — Edge gateway](DEPLOYMENT.md#9-edge-gateway-apache-apisix) |
| Self-host, đánh giá giấy phép, hay xuất bản nguồn có review | [Ranh giới nguồn mở và phân phối](OPEN_SOURCE.md) |
| Đối chiếu repo với kiến trúc DX-OS / Open-Core và không gian H-P-D-I | [Ánh xạ DX-OS](DX_OS.md) |

## Cái gì nằm ở đâu

- **README gốc:** định hướng, phạm vi hiện tại, sơ đồ repo, và liên kết. Không phải tài liệu API đầy đủ.
- **Service guide:** quy tắc triển khai và quyền sở hữu module theo ngôn ngữ, đặt cạnh code.
- **Development guide:** thiết lập, nguồn cấu hình, điểm vào lệnh, xử lý sự cố cục bộ.
- **Tài liệu chủ đề:** hành vi hiện tại chi tiết, ví dụ, ranh giới, liên kết triển khai.

## Trạng thái triển khai và bằng chứng

"Đã triển khai" nghĩa là repo có đường mã, chưa chắc tính năng đã được bật, cấu hình, đo tải
hay duyệt cho production. Cụ thể:

- Bộ đệm tùy chọn yêu cầu cấu hình tường minh và bằng chứng rollout.
- Hấp thụ ảnh/âm thanh/video tổng quát chưa triển khai; chỉ các định dạng có text được xử lý.
- Fine-tuning/thích ứng model là đề xuất, không phải pipeline huấn luyện hiện có.
- Health response, fixture hay artifact smoke cũ không phải bằng chứng workflow đúng toàn trình.

Dùng [RETRIEVAL.md](RETRIEVAL.md) cho hành vi hấp thụ đã cung cấp so với kế hoạch, và
[DEPLOYMENT.md](DEPLOYMENT.md) cho yêu cầu kiểm chứng trên bản tự triển khai.

## Đặt tên trong giai đoạn đổi thương hiệu

OpenVie là tên sản phẩm hướng tới người dùng. Tên package/protobuf nội bộ, tài nguyên triển khai,
route, tiền tố credential và tên miền hiện có cố ý giữ nguyên định danh; tên kỹ thuật còn chứa
`cacanode` hoặc `ccn` khớp với code mà chúng mô tả. Việc đổi thương hiệu trình bày này không
thay đổi hợp đồng tích hợp hay tạo tên miền công khai mới. Đổi ví dụ chạy được phải đi cùng thay
đổi trong triển khai.

Mã nguồn ứng dụng OpenVie là Apache-2.0 theo bản quyền LinkedNodeDigital; xem
[LICENSE](../LICENSE), [NOTICE](../NOTICE), và [publication gate](OPEN_SOURCE.md#publication-gate).

## Bảo trì tài liệu này

1. Mỗi chủ đề chỉ có một nơi chuẩn; liên kết tới nó thay vì sao chép danh mục cấu hình.
2. Kiểm chứng phát biểu về hành vi hiện tại với mã nguồn, schema, Makefile, package script,
   cấu hình triển khai.
3. Tách rõ hành vi đã triển khai, tính năng đang tắt, yêu cầu vận hành, và đề xuất tương lai.
4. Khi di chuyển một mục, cập nhật liên kết tương đối, heading, điều hướng, và liên kết tới.
5. Kiểm tra Markdown, sơ đồ, điểm vào lệnh hiển thị đúng; ghi rõ check runtime/provider chưa chạy.

Định dạng dây truyền dùng chung nằm ở [`contracts/`](../contracts/) (schema và fixture JSON)
và [`proto/`](../proto/) (gRPC). Tài liệu giải thích các ranh giới đó; nó không thay thế định
nghĩa máy đọc được hay check tương thích.

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
   - [Web development and checks](DEVELOPMENT.md#start-the-applications).
5. Consult the feature reference only when your change needs it.
6. Read the [DX-OS / Open-Core mapping](DX_OS.md) before presenting or comparing this repository
   with the three-tier DX-OS architecture.
7. Review the [open-source and distribution boundary](OPEN_SOURCE.md) before exporting a public repository.

## Guides by task

| I want to... | Read |
| --- | --- |
| Run locally, understand configuration, or execute checks | [Local development](DEVELOPMENT.md) |
| Understand service ownership, storage, security, or runtime sequences | [Architecture](ARCHITECTURE.md) |
| Change document parsing, indexing, models, or retrieval | [Ingestion and retrieval](RETRIEVAL.md) |
| Configure a self-hosted release or plan verification and recovery | [Deployment](DEPLOYMENT.md) |
| Expose one HTTP entry point or rate-limit traffic at the edge | [Deployment §9 — Edge gateway](DEPLOYMENT.md#9-edge-gateway-apache-apisix) |
| Self-host, assess licensing, or publish a reviewed source export | [Open-source and distribution boundary](OPEN_SOURCE.md) |
| Map this repository against the DX-OS / Open-Core architecture and its H-P-D-I spaces | [DX-OS mapping](DX_OS.md) |

## What belongs where

- **Root README:** orientation, current scope, repository map, and links. Not the full API manual.
- **Service guides:** language-specific implementation and module ownership rules. These stay beside the code.
- **Development guide:** setup, configuration sources, command entry points, and local troubleshooting.
- **Topic references:** detailed current behavior, examples, boundaries, and implementation links.

## Implementation status and evidence

“Implemented” means the repository contains a code path, not that the feature is enabled,
configured, load-tested, or approved for production. In particular:

- Optional caches require explicit configuration and rollout evidence.
- General image/audio/video ingestion is not implemented; only the documented text-bearing
  formats are processed.
- Model fine-tuning/adaptation is proposed work, not an existing training pipeline.
- A health response, fixture, or historical smoke artifact is not proof of full workflow correctness.

Use [RETRIEVAL.md](RETRIEVAL.md) for delivered versus planned ingestion behavior, and
[DEPLOYMENT.md](DEPLOYMENT.md) for verification requirements on a self-hosted install.

## Naming during the rebrand

OpenVie is the user-facing product name. Internal package/protobuf names, deployment resources,
routes, credential prefixes, and existing domains intentionally retain their current identifiers;
technical names still containing `cacanode` or `ccn` match the code they document. This
presentation rebrand does not change integration contracts or establish a new public domain.
Change executable examples in the same change as their implementation.

OpenVie application source is Apache-2.0 under LinkedNodeDigital copyright; see
[LICENSE](../LICENSE), [NOTICE](../NOTICE), and the [publication gate](OPEN_SOURCE.md#publication-gate).

## Maintaining these docs

1. Give a topic one canonical home and link to it instead of copying configuration catalogs.
2. Verify current-behavior statements against source, schemas, Makefiles, package scripts, and deployment configuration.
3. Separate implemented behavior, disabled features, operational requirements, and future proposals.
4. When moving a section, update relative source links, headings, navigation, and inbound links.
5. Verify rendered Markdown, diagrams, and command entry points; report any runtime/provider checks not exercised.

Shared wire-format definitions remain in [`contracts/`](../contracts/) (JSON schemas and fixtures)
and [`proto/`](../proto/) (gRPC). Documentation explains those boundaries; it does not replace their
machine-readable definitions or compatibility checks.