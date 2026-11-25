# OpenVie source and distribution boundary

[Project overview](../README.md) · [Architecture](ARCHITECTURE.md) · [Local development](DEVELOPMENT.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#ranh-giới-nguồn-mở-và-phân-phối--tiếng-việt) · [English](#openvie-source-and-distribution-boundary-1)

---

## Ranh giới nguồn mở và phân phối — tiếng Việt

[Project overview](../README.md) · [Kiến trúc](ARCHITECTURE.md) · [Phát triển cục bộ](DEVELOPMENT.md)

**Trạng thái xuất bản:** bản chốt cho commit đầu tiên (chốt hợp đồng, loại bỏ phần ngoài phạm vi,
mốc cài mới, cùng các bước kiểm tra end-to-end, bảo mật, alignment và an toàn xuất bản) đã hoàn tất và
được ghi trong lịch sử commit ban đầu. Phần dưới mô tả cách cấp phép và cách tách repo dự kiến.

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

## Điều kiện xuất bản

Bản xuất nguồn cục bộ này không có lịch sử Git riêng tư hay remote. Trước khi xuất bản, người
giữ quyền và người phụ trách bảo mật phải:

1. Duyệt quyền phân phối cho từng thành phần, đóng góp, bản dịch, logo, artifact sinh ra, tài
   sản vendored, model và dataset; giữ nguyên third-party notices.
2. Rà soát toàn bộ cây xuất bản cùng mọi tài liệu, fixture, ảnh, ví dụ cấu hình và tệp sinh ra để
   tìm thông tin khách hàng, credential, địa chỉ nội bộ, thỏa thuận riêng tư. Xoay mọi secret
   bị lộ; không bao giờ sao chép lịch sử repo riêng tư, Actions secrets hay tệp `.env`
   production.
3. Cấu hình kênh báo cáo lỗ hổng riêng tư có người theo dõi, chính sách maintainer/review và quyền
   sở hữu repo; xem [SECURITY.md](../SECURITY.md).
4. Kiểm chứng từ một bản clone mới: stack mẫu cài được, nạp được một tài liệu có quyền, thực
   thi cách ly tenant, hiển thị trích dẫn và từ chối trả lời khi thiếu bằng chứng. Cấu hình
   phát triển có các giá trị mặc định đã biết và không bao giờ dùng cho production. Hãy kiểm chứng
   riêng phần lộ diện của production, egress tới provider và backup.

Chưa có GitHub repository nào được xuất bản bởi các thay đổi tệp này; checklist này không khẳng
định các review pháp lý, bảo mật hay vận hành đã thông qua.

---

# OpenVie source and distribution boundary

[Project overview](../README.md) · [Architecture](ARCHITECTURE.md) · [Local development](DEVELOPMENT.md)

**Publication status:** the first-commit cut (contract freeze, out-of-scope removal,
fresh-install baseline, and the end-to-end, security, alignment, and publication-safety gates)
is complete and recorded in the initial commit history. The boundary below describes the
intended licensing and repository split.

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

## Publication gate

This local source export has no private Git history or remote. Before publishing it, a rights
holder and security owner must:

1. Approve distribution rights for each authored component, contribution, translation, logo,
   generated artifact, vendored asset, model, and dataset; preserve third-party notices.
2. Review the entire exported tree and all documentation, fixtures, images, config examples,
   and generated files for customer information, credentials, internal addresses, and private
   agreements. Rotate any exposed secret; never copy the private repository's history, Actions
   secrets, or production `.env` files.
3. Configure a monitored private vulnerability-reporting channel, maintainers/review policy,
   and repository ownership; see [SECURITY.md](../SECURITY.md).
4. Verify from a fresh clone that the sample stack installs, ingests an authorized document,
   enforces tenant isolation, shows citations, and abstains without evidence. The development
   configuration has known defaults and must never be applied to production. Validate production
   exposure, provider egress, and backups separately.

No GitHub repository has been published by these file changes; this checklist does not assert
that legal, security, or operational reviews have passed.
