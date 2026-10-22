# Workflow — phê duyệt tài liệu và engine BPMN

[Danh mục tài liệu](README.md) · [Kiến trúc](ARCHITECTURE.md) · [Tích hợp](INTEGRATION.md) · [Triển khai](DEPLOYMENT.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#workflow--phê-duyệt-tài-liệu-và-engine-bpmn--tiếng-việt) · [English](#workflow--document-approval-and-the-bpmn-engine)

---

## Workflow — phê duyệt tài liệu và engine BPMN — tiếng Việt

Luồng phê duyệt chạy **nhúng trong API runtime chính** (`api/`, module `workflow`): engine
Flowable (BPMN 2.0) cùng runtime Spring, dữ liệu trong cùng PostgreSQL, phạm vi hóa theo
tenant. Đây là luồng sản phẩm; stack demo độc lập trong `spikes/eoffice-approval` chỉ dành
cho PoC (xem [DEPLOYMENT §8](DEPLOYMENT.md#8-run-the-bpmn-workflow-demo-poc)).

### 1. Luồng phê duyệt tài liệu

Quy trình: **nộp → duyệt/từ chối/leo thang (cấp 1) → duyệt/từ chối (cấp 2)** với external task
cho mỗi cấp, exclusive gateway, timer nhắc nhở/leo thang.

| Việc | Route |
| --- | --- |
| Nộp tài liệu vào luồng phê duyệt | `POST /api/v1/workflow/processes` |
| Danh sách process (phân trang) + log từng process | `GET /api/v1/workflow/processes` · `GET /api/v1/workflow/processes/{id}/log` |
| Inbox tác vụ; nhận / hoàn tất | `GET /api/v1/workflow/tasks` · `POST /api/v1/workflow/tasks/{id}/claim` · `/complete` |
| Cấu hình SLA của tổ chức | `GET /api/v1/workflow/sla-config` |

Kết quả quyết định (APPROVE/REJECT/ESCALATE ở cấp 1; APPROVE/REJECT ở cấp 2) được ghi thẳng
vào trạng thái tài liệu: cột `approval_status` (`PENDING → APPROVED | REJECTED`, Flyway V8)
trong cùng transaction với quyết định, kèm nhật ký kiểm toán đầy đủ. Frontend và API đọc
trạng thái phê duyệt từ chính bảng `documents` — không cần đồng bộ song song.

### 2. Độ nhạy cảm và SLA

- Chính sách độ nhạy dùng chung `SensitivityPolicyService` (một nguồn chân lý, worker demo của
  PoC cũng tái dùng nó thay vì duy trì danh sách từ khóa riêng).
- SLA ghi trong bảng `workflow_sla_configs` theo tổ chức; mặc định reminder `PT24H`, leo thang
  `PT48H`, giới hạn trên `maxSlaMinutes = 20160` phút (14 ngày) để tổ chức không cấu hình giá
  trị vô lý. Timer → `WorkflowReminderDelegate` / `WorkflowTimerEscalationDelegate`.

### 3. Tác vụ kèm quyết định

- Thông báo `WORKFLOW_DECIDED` qua kênh SMTP đã cấu hình (`notification` module).
- Webhook có chữ ký: `workflow.started`, `workflow.approved`, `workflow.rejected`,
  `workflow.escalated` (phong bì JSON, HMAC-SHA256, outbox + retry — xem
  [INTEGRATION §4](INTEGRATION.md#4-webhook-đẩy-sự-kiện-có-chữ-ký)).
- Audit log cho mọi hành động claim/complete/escalate.

### 4. Engine BPMN tổng quát (cho ứng dụng khác)

Ngoài luồng tài liệu, engine mở API tổng quát `/api/v1/workflow/engine` để triển khai định nghĩa
BPMN bất kỳ, khởi động process, inbox/claim/complete tác vụ tổng quát và xem lịch sử — bảng route
đầy đủ ở [Tích hợp §5](INTEGRATION.md#5-workflow-engine-bpmn-tổng-quát). Tác vụ thuộc
luồng phê duyệt tài liệu bị **từ chối** claim/complete thô qua route tổng quát (`409`) để không
bỏ qua nhật ký quy trình tài liệu.

---

## Workflow — document approval and the BPMN engine

The approval flow runs **embedded in the main API runtime** (`api/`, `workflow` module): the
Flowable BPMN 2.0 engine shares the Spring runtime, the same PostgreSQL database, and the
tenant-scoping rules. The standalone demo stack in `spikes/eoffice-approval` is PoC-only
(see [DEPLOYMENT §8](DEPLOYMENT.md#8-run-the-bpmn-workflow-demo-poc)).

### 1. Document approval process

Sequence: **submit → approve/reject/escalate (level 1) → approve/reject (level 2)** with an
external task per level, an exclusive gateway, and reminder/escalation timers.

| Task | Route |
| --- | --- |
| Submit a document for approval | `POST /api/v1/workflow/processes` |
| Paged process list + per-process log | `GET /api/v1/workflow/processes` · `GET /api/v1/workflow/processes/{id}/log` |
| Task inbox; claim / complete | `GET /api/v1/workflow/tasks` · `POST /api/v1/workflow/tasks/{id}/claim` · `/complete` |
| Organization SLA configuration | `GET /api/v1/workflow/sla-config` |

Decision outcomes (APPROVE/REJECT/ESCALATE at level 1; APPROVE/REJECT at level 2) are written
directly onto document state: the `approval_status` column (`PENDING → APPROVED | REJECTED`,
Flyway V8) is updated in the same transaction as the decision, with a full audit log. Frontend
and API read approval state from `documents` itself — no parallel synchronization.

### 2. Sensitivity policy and SLA

- Shared sensitivity policy `SensitivityPolicyService` (single source of truth; the PoC demo
  worker reuses it instead of keeping a separate keyword list).
- SLA lives in `workflow_sla_configs` per organization; defaults are reminder `PT24H` and
  escalation `PT48H`, capped by `maxSlaMinutes = 20160` minutes (14 days) so organizations
  cannot configure unreasonable values. Timers drive
  `WorkflowReminderDelegate` / `WorkflowTimerEscalationDelegate`.

### 3. Side effects tied to decisions

- `WORKFLOW_DECIDED` notification over the configured SMTP channel (`notification` module).
- Signed webhooks: `workflow.started`, `workflow.approved`, `workflow.rejected`,
  `workflow.escalated` (JSON envelope, HMAC-SHA256, outbox + retries — see
  [INTEGRATION §4](INTEGRATION.md#4-webhooks-signed-event-push)).
- Audit log for every claim/complete/escalate action.

### 4. Generic BPMN engine (for other applications)

Beyond the document flow, the engine exposes the generic API `/api/v1/workflow/engine` to deploy
any BPMN definition, start processes, use a generic task inbox with claim/complete, and inspect
history — the full route table lives in
[Integration §5](INTEGRATION.md#5-workflow-engine-generic-bpmn). Document-approval tasks are
**rejected** from raw claim/complete via the generic route (`409`) so the document workflow log
cannot be bypassed.
