# AI model routing

[Documentation index](README.md) · [BizApps](BIZAPPS.md)

> **Ngôn ngữ / Language:** [Tiếng Việt](#định-tuyến-model-ai-tiếng-việt) · [English](#ai-model-routing-english)

---

## Định tuyến model AI (Tiếng Việt)

Trang này mô tả ranh giới chọn model mà OpenVie đang dùng. Cấu hình mặc định và mô tả biến môi trường nằm trong
[tham chiếu cấu hình BizApps](BIZAPPS.md#9-cấu-hình).

### Định tuyến hiện tại

| Tác vụ | Cách chọn model | Ghi chú |
| --- | --- | --- |
| Chat và câu trả lời RAG | `LLM_MODEL_ID` (model 3B đã cấu hình) | Đường xử lý các request nhạy cảm về độ trễ. |
| Phân loại ý định BizApp | `LLM_MODEL_ID` | Mặc định không ghi đè theo tác vụ. |
| Trích slot BizApp | `LLM_MODEL_ID` | Mặc định không ghi đè theo tác vụ. |
| Soạn và sửa manifest BizApp | `BIZAPPS_AI_DRAFT_MODEL` | Dùng model riêng để soạn thảo nếu được cấu hình. |
| Thử lại bản thảo khi kiểm tra hợp lệ thất bại | `BIZAPPS_AI_DRAFT_FALLBACK_MODEL` | Dùng khi model soạn thảo chính không vượt qua kiểm tra hợp lệ. |

Khi chưa đặt, phần ghi đè cho intent và slot sẽ quay về `LLM_MODEL_ID`. Bản thảo lưu lại model đã chọn, số lần thử,
việc dùng fallback và kết quả kiểm tra hợp lệ trong metadata và bản ghi audit. Kiểm tra hợp lệ và chuẩn hóa manifest
tất định vẫn chạy với mọi model.

### Quy trình đổi model an toàn

1. Đánh giá model hiện tại và từng ứng viên; ghi lại tính hợp lệ, kiểm tra ngữ nghĩa, số lần sửa lỗi và độ trễ.
2. Đặt `BIZAPPS_AI_DRAFT_MODEL` và, nếu cần, `BIZAPPS_AI_DRAFT_FALLBACK_MODEL`. Chat, intent và slot vẫn dùng
   `LLM_MODEL_ID` trừ khi được ghi đè rõ ràng.
3. Canary model soạn thảo ở một môi trường, so sánh tỷ lệ vượt qua kiểm tra hợp lệ, việc dùng fallback, số lần
   sửa lỗi và độ trễ sinh.
4. Giữ validator và normalizer tất định bật trong suốt quá trình triển khai.
5. Rollback bằng cách khôi phục biến môi trường model soạn thảo trước đó.

---

## AI model routing (English)

This page describes the model-selection boundary that is shipped with OpenVie.
Configuration defaults and environment-variable descriptions live in the
[BizApps configuration reference](BIZAPPS.md#9-configuration).

### Shipped routing

| Workload | Model selection | Notes |
| --- | --- | --- |
| Chat and RAG answers | `LLM_MODEL_ID` (the configured 3B model) | Latency-sensitive request path. |
| BizApp intent classification | `LLM_MODEL_ID` | The per-task override is blank by default. |
| BizApp slot extraction | `LLM_MODEL_ID` | The per-task override is blank by default. |
| BizApp manifest drafting and repair | `BIZAPPS_AI_DRAFT_MODEL` | Use a drafting-specific model when configured. |
| Draft retry after validation failure | `BIZAPPS_AI_DRAFT_FALLBACK_MODEL` | Used after the primary drafting model fails validation. |

Intent and slot overrides fall back to `LLM_MODEL_ID` when unset. Drafts retain
the selected model, attempt count, fallback use, and validation result in their
metadata and audit entry. Deterministic manifest validation and normalization
remain in the path for every model.

### Rollout, canary, and rollback

1. Evaluate the current model and each candidate; record validity, semantic
   checks, repair attempts, and latency.
2. Set `BIZAPPS_AI_DRAFT_MODEL` and, if needed,
   `BIZAPPS_AI_DRAFT_FALLBACK_MODEL`. Chat, intent, and slot routing remain on
   `LLM_MODEL_ID` unless explicitly overridden.
3. Canary the drafting model in one environment and compare validation pass
   rate, fallback use, repair attempts, and generation latency.
4. Keep the deterministic validators and normalizers enabled throughout the
   rollout.
5. Roll back by restoring the prior drafting-model environment variables.
