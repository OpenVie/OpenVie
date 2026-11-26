# OpenVie source and distribution boundary

[Project overview](../README.md) · [Architecture](ARCHITECTURE.md) · [Local development](DEVELOPMENT.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#ranh-giới-nguồn-mở-và-phân-phối--tiếng-việt) · [English](#openvie-source-and-distribution-boundary-1)

---

## Ranh giới nguồn mở và phân phối — tiếng Việt

[Project overview](../README.md) · [Kiến trúc](ARCHITECTURE.md) · [Phát triển cục bộ](DEVELOPMENT.md)

**Trạng thái phát hành:** mã nguồn OpenVie được phát hành công khai tại
[github.com/OpenVie/OpenVie](https://github.com/OpenVie/OpenVie) theo Apache-2.0. Tài liệu này mô tả
ranh giới cấp phép và phân phối của repo; cách báo cáo bảo mật và đóng góp nằm ở
[SECURITY.md](../SECURITY.md) và [CONTRIBUTING.md](../CONTRIBUTING.md).

## Ranh giới repo

Thư mục này là **repo ứng dụng nguồn mở**. Mã nguồn ứng dụng theo giấy phép
[Apache License 2.0](../LICENSE), ghi nhận trong [NOTICE](../NOTICE). Bất kỳ ai cũng có thể tự triển
khai, fork hoặc cung cấp dịch vụ hosting theo giấy phép này; không phần nào trong repo phụ thuộc vào
một service, subscription hay thỏa thuận riêng. Repo không kèm dịch vụ lưu trữ hay SLA nào, và
không chứa mã enterprise độc quyền.

Giấy phép Apache không cấp quyền dùng tên hay logo OpenVie như một trademark ngoài việc ghi nhận
nguồn gốc thông thường. Dependency, container image, dịch vụ lưu trữ, model weights và dataset
vẫn theo giấy phép riêng của chúng. Model ID trong cấu hình không đi kèm hay cấp phép weights.

## Ranh giới self-hosting

Bắt đầu từ [phát triển cục bộ](DEVELOPMENT.md) cho stack phi production. Spring Boot quản lý định
danh tổ chức/workspace chuẩn, phân quyền, chat và trạng thái PostgreSQL. FastAPI phụ trách truy hồi
và các chỉ mục phái sinh; RabbitMQ, SeaweedFS, Qdrant, Kuzu, Redis và các dịch vụ model đã cấu hình
cung cấp những dependency runtime còn lại. [Triển khai](DEPLOYMENT.md) mô tả cách cài self-host trên
một máy chủ; đó là hướng dẫn cho người vận hành, không phải chứng nhận gì cả. Quy trình triển khai
riêng của người vận hành cố ý **không** nằm trong repo này; `.github/workflows/ci.yml` chỉ chạy kiểm
tra. Hãy tự cấu hình secret manager, phê duyệt triển khai, backup, phục hồi, tên miền và thỏa thuận
provider.

Cấu hình mẫu đi kèm dùng Ollama cục bộ để sinh văn bản và embedding. Trỏ model adapter tới một
endpoint tương thích OpenAI bên ngoài là lựa chọn của người vận hành, và khi đó các đoạn trích nội
dung tenant sẽ đi qua ranh giới của provider đó. Với workload bị quản lý chặt, hãy xem xét residency,
retention, procurement, threat model và cơ sở pháp lý.

## Tệp vendored và tệp dẫn xuất

| Đường dẫn file | Nguồn gốc | Giấy phép | Ghi chú thay đổi |
| --- | --- | --- | --- |
| `frontend/public/pdf.worker.min.mjs` | Mozilla PDF.js v4.x | Apache-2.0 | Giữ nguyên header của Mozilla Foundation; dùng để render tài liệu PDF ngay trên trình duyệt. |

## Báo cáo bảo mật và đóng góp

Báo lỗ hổng qua nút **Report a vulnerability** trên tab Security của repository (báo cáo riêng tư);
không mở issue công khai. Xem [SECURITY.md](../SECURITY.md) để biết thông tin cần gửi kèm. Quy trình
gửi patch, header giấy phép, pin image và chính sách dependency nằm ở [CONTRIBUTING.md](../CONTRIBUTING.md).

---

# OpenVie source and distribution boundary

[Project overview](../README.md) · [Architecture](ARCHITECTURE.md) · [Local development](DEVELOPMENT.md)

**Release status:** OpenVie source is publicly released at
[github.com/OpenVie/OpenVie](https://github.com/OpenVie/OpenVie) under Apache-2.0. This document
describes the repository's licensing and distribution boundary; security reporting and
contribution guidance live in [SECURITY.md](../SECURITY.md) and [CONTRIBUTING.md](../CONTRIBUTING.md).

## Repository boundary

This directory is the **open-source application repository**. Its application source is
licensed under [Apache License 2.0](../LICENSE), with attribution in [NOTICE](../NOTICE).
Anyone may self-host, fork, or offer hosting under that license; nothing in this repository is
conditional on a separate service, subscription, or agreement. This repository does not grant
any hosted service or service-level agreement, and no separate proprietary enterprise code is
included here.

The Apache license does not grant rights to the OpenVie name or logo as a trademark beyond
customary origin attribution. Dependencies, container images, hosted services, model weights,
and datasets retain their own licenses. Model IDs in configuration do not bundle or license the
weights.

## Self-hosting boundary

Start with [local development](DEVELOPMENT.md) for a non-production stack. Spring Boot owns
authoritative organization and workspace identity, authorization, chat, and PostgreSQL state. FastAPI owns
retrieval and derived indexes; RabbitMQ, SeaweedFS, Qdrant, Kuzu, Redis, and configured model
services supply the other runtime dependencies. [Deployment](DEPLOYMENT.md) describes a
single-host self-installation; it is operator guidance, not a certification of any kind.
Operator-specific deployment workflows are intentionally **not** in this repository;
`.github/workflows/ci.yml` runs checks only. Configure your own secret manager, deployment
approvals, backups, recovery, domain, and provider agreements.

The delivered example configuration uses local Ollama generation and embedding. Pointing the
model adapter at an external OpenAI-compatible endpoint is an operator choice, and tenant
excerpts would then cross that provider boundary. For regulated workloads review residency,
retention, procurement, threat models, and legal basis.

## Vendored and derived files

| File path | Upstream origin | License | Modification note |
| --- | --- | --- | --- |
| `frontend/public/pdf.worker.min.mjs` | Mozilla PDF.js v4.x | Apache-2.0 | Original banner preserved; vendored for client-side PDF document rendering. |

## Security reports and contributions

Report vulnerabilities through the **Report a vulnerability** button on the repository's Security
tab (private report); do not open a public issue. See [SECURITY.md](../SECURITY.md) for what to
include. Patch workflow, license headers, image pins, and the dependency policy live in
[CONTRIBUTING.md](../CONTRIBUTING.md).
