# PoC BPMN phê duyệt tài liệu e-Office

Bản demo độc lập này mô hình hóa quy trình phê duyệt bằng Flowable
## Nội dung bao gồm

- `bpmn/eoffice-document-approval.bpmn20.xml`: mô hình BPMN có thể thực thi, gồm external task đánh giá độ nhạy cảm, exclusive gateway, các candidate group cấp 1 và cấp 2, timer nhắc nhở/leo thang, các kết quả phê duyệt/từ chối, và các external task cho tác vụ phụ (side effect) ghi audit/xuất bản.
- `data/demo-documents.json`: các tài liệu mẫu thông thường và nhạy cảm.
- `scripts/worker.py`: worker xử lý external job, import trực tiếp `sensitive_policy.py` hiện có. Worker cố ý không sao chép lại bộ từ khóa hay phiên bản chính sách của file này.
- `scripts/seed_demo.py`: triển khai (deploy) BPMN và khởi chạy cả hai process instance mẫu.
- `tests/test_process_assets.py`: các test tĩnh chạy cục bộ để kiểm tra cấu trúc XML và sự đồng bộ giữa seed và policy.

Topic `publish-document` của worker mẫu chỉ là **bản giả lập (stub) cho demo**: nó ghi nhận trạng thái `APPROVED` và log ra vị trí cần gọi adapter ingestion thật của OpenVie. Nó chưa tải tài liệu lên storage và chưa đưa job ingestion production vào hàng đợi. Các side effect nhắc nhở/audit chỉ được log và lưu dưới dạng process variable; thông báo ở môi trường production phải được nối với các kênh thông báo hiện có.

## Khởi động engine độc lập

Yêu cầu máy host có Docker Compose và Python 3.11+. Từ thư mục này, chạy:

```bash
docker compose -f compose.yaml up -d
```

Chờ Flowable khởi động xong. Engine được publish tại `http://localhost:18081/flowable-rest/service`. Stack này dùng một database Postgres riêng trên cổng host `15433`.

Với demo cục bộ, hãy đặt mật khẩu REST khác mặc định trong shell trước khi khởi động Compose và dùng cùng giá trị đó cho các script:

```bash
# Bash / Git Bash
export FLOWABLE_REST_PASSWORD='replace-with-a-local-dev-password'
export FLOWABLE_DB_PASSWORD='replace-with-a-local-db-password'
docker compose -f compose.yaml up -d
```

Thông tin đăng nhập mặc định chỉ được để thuận tiện cho demo cục bộ dùng một lần. Không mở dịch vụ này ra mạng và không dùng lại các thông tin đăng nhập này trong môi trường triển khai thật.

## Triển khai process và tạo hai instance demo

Từ `spikes/eoffice-approval`:

```bash
python scripts/seed_demo.py
```

Script sẽ deploy BPMN và khởi chạy cả hai ví dụ. Các biến timer mặc định là `PT24H` và `PT48H`. Để demo timer nhanh, hãy đặt các khoảng thời gian ISO-8601 ngắn hơn **trước khi khởi chạy các process instance**, ví dụ `APPROVAL_REMINDER_DURATION=PT1M` và `APPROVAL_ESCALATION_DURATION=PT2M`.

## Khởi động worker

Mở terminal thứ hai trong cùng thư mục, với `FLOWABLE_REST_PASSWORD` được đặt cùng giá trị:

```bash
python scripts/worker.py
```

External task `score-sensitivity` gọi hàm dùng chung `is_sensitive_query()` của repository và lưu `sensitive`, `policyVersion` và `approvalLevel` thành các process variable. Sau đó BPMN điều hướng các tài liệu workspace thông thường tới `WORKSPACE_ADMIN`; các tài liệu nhạy cảm và các tài liệu có `target=shared`/`target=public` được chuyển thẳng tới `ORG_OWNER`.

## Demo phê duyệt, từ chối, nhận việc (claim) và leo thang

Liệt kê các task đang hoạt động:

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" \
  "http://localhost:18081/flowable-rest/service/runtime/tasks?includeProcessVariables=true"
```

Các candidate group của task là `WORKSPACE_ADMIN` và `ORG_OWNER`. Để nhận (claim) một task, gửi POST tới `/runtime/tasks/{taskId}` với nội dung `{"action":"claim","assignee":"demo-admin"}`. Các thành viên khác đủ điều kiện trong group vẫn có thể thấy task đó như một phương án dự phòng thông qua các truy vấn task theo candidate group; ứng dụng vẫn phải tự kiểm soát tư cách thành viên tenant/workspace khi cung cấp task cho người dùng thật.

Hoàn thành một task bằng biến quyết định. Với task cấp 1, `APPROVE` là phê duyệt, `REJECT` là từ chối, và `ESCALATE` chuyển yêu cầu lên cấp trên mà không bỏ qua cấp 2. Ví dụ:

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" -H 'Content-Type: application/json' \
  -X POST "http://localhost:18081/flowable-rest/service/runtime/tasks/TASK_ID" \
  -d '{"action":"complete","variables":[{"name":"approvalOutcome","value":"APPROVE","type":"string"}]}'
```

Với trường hợp từ chối, hãy đặt thêm `rejectReason` trong các biến khi hoàn thành task. Worker xử lý từ chối sẽ ghi một dấu audit `document-rejected`. Task cấp 1 khi đến hạn timer leo thang sẽ bị hủy và được chuyển lên cấp 2 sau khi audit worker `overdue, escalated` chạy. Timer nhắc nhở là loại không ngắt (non-interrupting) và giữ nguyên task cấp 1 ở trạng thái hoạt động.

## Kiểm tra các file cục bộ

Từ thư mục gốc của repository:

```bash
python -m unittest discover -s spikes/eoffice-approval/tests -v
```

Các test này chỉ kiểm tra các asset nguồn và việc tái sử dụng policy. Chúng không thay thế cho các integration test chạy thật trên container Flowable.

## Ranh giới về bảo mật và tích hợp

- BPMN dùng các role candidate group chỉ như metadata định tuyến workflow. Đây không phải là sự thay thế cho cơ chế phân quyền của API OpenVie; trước khi đưa vào production, cần xác định role và phạm vi tenant/workspace từ principal đã được xác thực, không lấy từ các biến do client cung cấp.
- Việc xem/nhận task theo candidate group phải được trung gian hóa qua API của ứng dụng để người dùng không thể truy vấn task của workspace khác.
- Process lưu phiên bản policy và cấp quyết định để phục vụ audit. Thiết lập REST của engine hiện tại chỉ dành cho phát triển cục bộ.
- Môi trường production vẫn cần kết nối bước publish với pipeline ingestion tài liệu hiện có, và nối các sự kiện nhắc nhở/leo thang với các kênh thông báo trong ứng dụng hiện có.
- Việc thực thi timer yêu cầu bật async executor của Flowable. Hãy xác nhận thiết lập này với đúng image/phiên bản container khi chạy thử runtime.
