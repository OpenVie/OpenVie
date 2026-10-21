# ADR-0001: Chọn Flowable làm workflow engine cho v0.3.0

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-15
- **Phạm vi quyết định:** MVP v0.3.0, một luồng phê duyệt e-Office minh họa.

## Bối cảnh

OpenVie có backend Java/Spring Boot và cần một workflow engine tự host để chạy quy trình phê duyệt có trạng thái bền vững, mô hình hóa theo BPMN và có thể kiểm tra lịch sử xử lý. MVP cần giữ phạm vi hẹp, tránh duy trì hai engine hoặc tạo thêm một dịch vụ tự động hóa độc lập khi chưa có nhu cầu.

## Các phương án

1. **Flowable OSS** — engine BPMN viết bằng Java, có thể nhúng vào ứng dụng Java hoặc chạy như dịch vụ. Dự án Flowable OSS công bố giấy phép Apache 2.0.
2. **n8n Community** — nền tảng tự động hóa workflow có thể self-host, nhưng phần mã nguồn cộng đồng hiện dùng Sustainable Use License (SUL), một giấy phép fair-code có giới hạn sử dụng; không phải giấy phép OSI-approved và không tương đương Apache 2.0.

## Đánh giá theo tiêu chí

| Tiêu chí | Flowable OSS | n8n Community | Nhận xét cho OpenVie |
| --- | --- | --- | --- |
| Self-host | Có; nhúng trong ứng dụng hoặc chạy dạng service | Có; triển khai riêng | Cả hai đáp ứng yêu cầu triển khai nội bộ |
| Giấy phép tương thích Apache-2.0 | **Đạt** — Flowable OSS công bố Apache 2.0 | **Không đạt** — SUL có điều kiện sử dụng riêng, không phải giấy phép nguồn mở OSI | Đây là tiêu chí quyết định với dự án OpenVie Apache-2.0 |
| Chi phí tích hợp Spring | **Thấp hơn** — Java engine có thể nhúng trong backend Spring; dùng cùng hệ sinh thái triển khai | **Trung bình/cao hơn** — thường cần một service riêng và tích hợp qua HTTP/webhook/API | Flowable giảm số ranh giới vận hành cho MVP |
| BPMN | **Đáp ứng trực tiếp** — engine BPMN chuyên dụng | Có workflow dạng node, nhưng không thay thế một BPMN engine chuyên dụng | Flowable phù hợp yêu cầu quy trình BPMN của roadmap |
| Auditability | Có lịch sử/ dữ liệu tiến trình; cần cấu hình history level, retention và quyền truy cập | Có execution records; cần cấu hình lưu trữ, retention và quyền truy cập | Flowable cho mô hình tiến trình phù hợp; phải kiểm chứng lịch sử trong PoC |

## Quyết định

**Chọn Flowable OSS làm engine duy nhất cho v0.3.0.** Không đưa n8n vào runtime, Docker Compose, thiết kế tích hợp hay tiêu chí nghiệm thu của MVP này.

Lý do chính:

1. Apache 2.0 phù hợp với giấy phép dự án và mục tiêu Open-Core; SUL của n8n tạo thêm rủi ro điều kiện cấp phép cho việc phân phối/nhúng sản phẩm.
2. Backend OpenVie đã dùng Java/Spring Boot; Flowable có thể được tích hợp trong cùng ứng dụng, giảm chi phí vận hành và giao tiếp liên dịch vụ.
3. Yêu cầu roadmap nêu rõ BPMN; Flowable là BPMN engine chuyên dụng.
4. Lịch sử tiến trình của Flowable có thể là nền tảng cho audit trail, với cấu hình lưu lịch sử và retention được xác định rõ.

## Hệ quả và giới hạn

- v0.3.0 chỉ triển khai **một luồng phê duyệt e-Office**: gửi yêu cầu → chờ người duyệt → approve/reject → ghi nhận kết quả.
- Backend Spring Boot sở hữu API nghiệp vụ và quyền truy cập; không để frontend gọi trực tiếp engine bằng quyền đặc biệt.
- Phải xác định quyền người duyệt, tenant/workspace isolation, lịch sử ai làm gì/khi nào, retention, xử lý retry và giao dịch trước khi coi là production-ready.
- Không xây dựng adapter đa engine hoặc hỗ trợ song song Flowable và n8n trong MVP.
- Việc chọn engine không đồng nghĩa đã hoàn tất tích hợp runtime; implementation và kiểm thử end-to-end thuộc các thay đổi triển khai v0.3.0 tiếp theo.

## Spike và kiểm chứng còn lại

Đây là quyết định kiến trúc dựa trên đối chiếu tài liệu chính thức và mức độ phù hợp với repository. Chưa tuyên bố hai PoC chạy thực tế đã được thực hiện trong môi trường này. Trước khi triển khai production, spike kỹ thuật cần xác nhận tối thiểu:

- Deploy Flowable với database bền vững trong môi trường dev.
- Chạy approval process BPMN qua API Spring, gồm approve và reject.
- Khởi động lại dịch vụ và xác nhận trạng thái tiến trình còn nguyên.
- Kiểm tra lịch sử tiến trình và người thực hiện hành động.
- Ghi lại thời gian tích hợp, dependency/version và các giới hạn phát hiện được.

## Nguồn tham khảo

- Flowable OSS licensing: https://www.flowable.com/open-source/docs/oss-introduction/
- Flowable OSS engines / embedding: https://www.flowable.com/open-source
- n8n Community License: https://docs.n8n.io/sustainable-use-license/
- n8n license FAQ: https://support.n8n.io/article/can-i-use-your-license-for-my-use-case
