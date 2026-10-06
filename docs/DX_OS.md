# OpenVie and the DX-OS (Open-Core) architecture

[Documentation index](README.md) · [Architecture](ARCHITECTURE.md) · [Deployment](DEPLOYMENT.md) · [Open-source boundary](OPEN_SOURCE.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#tiếng-việt) · [English](#english)

---

## Tiếng Việt

Tài liệu này đối chiếu mã nguồn OpenVie với khung kiến trúc **Hệ điều hành Chuyển đổi số (DX-OS)** và mô hình **Open-Core** dùng trong cuộc thi Phần mềm nguồn mở (OLP) 2026. Mọi nhận định được kiểm chứng trực tiếp trên mã nguồn.

### 1. Vị trí trong tháp 3 tầng DX-OS

| Tầng DX-OS | Thành phần trong OpenVie | Trạng thái |
| --- | --- | --- |
| **Tầng 1 – Nền tảng Dịch vụ Lõi số** (PaaS, headless) | `api/` (Spring Boot 21), `rag-chatbot-fastapi/` (AI HTTP + gRPC + worker), `apisix/` (cổng API), `docker-compose.yml` (Postgres, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu) | Một phần |
| **Tầng 2 – Không gian Năng lực số** (H-P-D-I, có giao diện) | `frontend/` (Next.js) | **Đạt** (có ứng dụng minh họa giao diện) |
| **Tầng 3 – Nền tảng Kinh doanh số** (OMS/WMS/POS/LMS/CRM…) | Không có | Ngoài phạm vi |

### 2. Nguyên tắc lõi chạy ngầm (Headless)

- Nền tảng lõi **không cung cấp UI cho người dùng cuối**, hoạt động như một PaaS phục vụ các ứng dụng tầng trên.
- `api/` chỉ xuất HTTP JSON (`/api/v1/**`) và gRPC nội bộ (`:50051`).
- Toàn bộ giao diện người dùng tách biệt hoàn toàn tại `frontend/` (Tầng 2).
- Cổng biên `apisix/` chỉ mở giao diện web và `/api/v1/*`. Các hạ tầng dữ liệu và AI không lộ ra ngoài mạng.

### 3. Bốn dịch vụ lõi bắt buộc

| Dịch vụ lõi | Hiện trạng kiểm chứng | Trạng thái | Việc cần làm |
| --- | --- | --- | --- |
| **Định danh, SSO** | Mật khẩu + JWT, phân quyền 3 cấp (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`), xoay vòng token | **Chưa đạt** (chưa có SSO/OIDC/MFA) | Tích hợp Keycloak làm IdP và cấu hình Resource Server |
| **Cổng API (API Gateway)** | Apache APISIX standalone (`:8088`), định tuyến và giới hạn tần suất biên (`limit-req`) | **Đạt** | Xem [DEPLOYMENT §9](DEPLOYMENT.md#9-edge-gateway-apache-apisix) |
| **Quản trị dữ liệu cấu trúc** | PostgreSQL 16 + Flyway (20 bảng nghiệp vụ, ràng buộc tenant_id) | **Đạt** | — |
| **Quản trị dữ liệu phi cấu trúc** | SeaweedFS (S3), Qdrant (vector), Kuzu (đồ thị tri thức), hấp thụ đa định dạng | **Đạt** | Chưa có OCR / âm thanh / video |
| **Động cơ luồng công việc (Workflow)** | Chưa có trong repo (`flowable`/`camunda`/`n8n` = 0) | **Chưa đạt** | Tích hợp Flowable hoặc n8n cho bài toán phê duyệt |

### 4. Đánh nhãn H-P-D-I cho giao diện Tầng 2

- **[I] Trí tuệ số:** Trợ lý ảo RAG vận hành trên kho tri thức nội bộ (`/`, `/documents/[id]`) — **Đạt** (ứng dụng minh họa chính).
- **[H] Con người số:** Kho tài liệu, bách khoa hướng dẫn, quản trị thành viên/workspace (`/documents`, `/documentation`, `/users`, `/workspaces`) — **Đạt**.
- **[P] Quy trình số:** Thiết lập tổ chức, phân quyền 3 cấp, kênh thông báo, nhật ký kiểm toán — **Một phần** (chưa có BPMN).
- **[D] Dữ liệu số:** Nguồn gốc tài liệu, nhật ký kiểm toán, số liệu Prometheus — **Chưa đạt** (chưa có dashboard BI/IOC).

### 5. Bốn nguyên lý thiết kế

1. **(i) Phân tách vòng đời & tốc độ dữ liệu:** *Chưa đạt* (chỉ có OLTP, chưa có CDC/Lakehouse).
2. **(ii) Hội tụ dữ liệu IT và OT:** *Chưa đạt* (phạm vi thuần IT, chưa có IoT/Digital Twin).
3. **(iii) Quản trị dữ liệu thế hệ mới:** *Một phần* (có provenance, audit log, outbox/inbox; chưa có data lineage đầy đủ).
4. **(iv) Zero-Trust & GitOps:** *Một phần* (có JWT, APISIX limit-req, CI kiểm tra, metrics; chưa có Vault, WAF tệp, backup tự động).

### 6. Bảy lớp công nghệ tham chiếu

- **Lớp 1 (Trải nghiệm):** Next.js + Tailwind CSS (*Một phần*).
- **Lớp 2 (Biên mạng & An toàn):** Apache APISIX, Spring Security JWT (*Một phần* - thiếu IdP/WAF).
- **Lớp 3 (Tác nghiệp & Trạng thái):** PostgreSQL, RabbitMQ, gRPC (*Một phần*).
- **Lớp 4 (Tự động hóa & DevSecOps):** GitHub Actions CI (*Một phần* - thiếu BPMN).
- **Lớp 5 (Dữ liệu hội tụ & Sổ cái):** RabbitMQ, SeaweedFS (*Một phần* - thiếu Lakehouse).
- **Lớp 6 (Phân tích & Trí tuệ):** Ollama (`vylinh` + `bge-m3`), Qdrant, Kuzu (*Một phần* - thiếu BI/GIS).
- **Lớp 7 (Quản trị & Giám sát):** Qdrant, Prometheus metrics (*Một phần* - thiếu Grafana/Log tập trung).

---

## English

This document evaluates OpenVie against the **Digital Transformation Operating System (DX-OS)** architecture and **Open-Core** competition guidelines (OLP 2026).

### 1. Position in the DX-OS 3-Tier Pyramid

| DX-OS Tier | OpenVie Component | Status |
| --- | --- | --- |
| **Tier 1 – Digital Core Platform** (Headless PaaS) | `api/` (Spring Boot 21), `rag-chatbot-fastapi/` (AI inference, gRPC, worker), `apisix/` (gateway), `docker-compose.yml` (Postgres, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu) | Partial |
| **Tier 2 – Digital Capability Space** (H-P-D-I with UI) | `frontend/` (Next.js) | **Achieved** (UI demo application) |
| **Tier 3 – Digital Business Platform** (OMS, WMS, POS, LMS...) | None | Out of scope |

### 2. Headless Core Principle

- The core platform **does not provide end-user UI**; it functions as a PaaS providing services for upper-tier applications.
- `api/` only exposes REST endpoints (`/api/v1/**`) and internal gRPC (`:50051`).
- The entire web UI lives in `frontend/` (Tier 2).
- Edge gateway `apisix/` only publishes the web client and `/api/v1/*`. Internal data and AI services are never exposed.

### 3. Four Mandatory Core Services

| Core Service | Delivered Implementation | Status | Roadmap |
| --- | --- | --- | --- |
| **Identity & SSO** | Password + JWT, 3-role RBAC (`ORG_OWNER`, `WORKSPACE_ADMIN`, `MEMBER`), token rotation | **Not Met** (no SSO/OIDC/MFA) | Integrate Keycloak as IdP |
| **API Gateway** | Apache APISIX standalone (`:8088`), route proxying, and edge rate-limiting (`limit-req`) | **Achieved** | See [DEPLOYMENT §9](DEPLOYMENT.md#9-edge-gateway-apache-apisix) |
| **Structured Data** | PostgreSQL 16 + Flyway (20 business tables with tenant scoping) | **Achieved** | — |
| **Unstructured Data** | SeaweedFS (S3), Qdrant (vectors), Kuzu (knowledge graph), asynchronous multi-format ingestion | **Achieved** | OCR / audio / video not delivered |
| **Workflow Engine** | None in repo (`flowable`/`camunda`/`n8n` = 0) | **Not Met** | Add Flowable (BPMN) or n8n |

### 4. H-P-D-I Capability Labeling (Tier 2)

- **[I] Digital Intelligence:** Document-grounded RAG assistant (`/`, `/documents/[id]`) — **Achieved** (primary demo).
- **[H] Digital Human:** Document vault, documentation wiki, workspace/member management (`/documents`, `/documentation`, `/users`, `/workspaces`) — **Achieved**.
- **[P] Digital Process:** Org setup, RBAC, notification channels, audit logging — **Partial** (no BPMN).
- **[D] Digital Data:** Source provenance, audit logs, Prometheus metrics — **Not Met** (no BI dashboard).

### 5. Four Design Principles

1. **(i) Lifecycle & Velocity Separation:** *Not Met* (OLTP only; no Lakehouse / CDC).
2. **(ii) IT and OT Convergence:** *Not Met* (Pure IT scope; no sensor/IoT connections).
3. **(iii) Next-Gen Data Governance:** *Partial* (Provenance, audit logs, outbox/inbox present; no automated lineage).
4. **(iv) Zero-Trust & GitOps:** *Partial* (JWT auth, APISIX edge rate limiting, CI checks, Prometheus; no Vault or automated backups).

### 6. Seven Reference Technology Layers

- **Layer 1 (Experience):** Next.js, React, Tailwind CSS (*Partial*).
- **Layer 2 (Perimeter & Security):** Apache APISIX, Spring Security JWT (*Partial* - missing IdP/WAF).
- **Layer 3 (Operations & State):** PostgreSQL, RabbitMQ, gRPC (*Partial*).
- **Layer 4 (Automation & DevSecOps):** GitHub Actions CI (*Partial* - missing BPMN).
- **Layer 5 (Hyperconverged Data & Ledger):** RabbitMQ, SeaweedFS (*Partial* - missing Lakehouse).
- **Layer 6 (Analytics & Intelligence):** Ollama (`vylinh` + `bge-m3`), Qdrant, Kuzu (*Partial* - missing BI/GIS).
- **Layer 7 (Governance & Monitoring):** Qdrant search, Prometheus metrics (*Partial* - missing Grafana/centralized logs).
