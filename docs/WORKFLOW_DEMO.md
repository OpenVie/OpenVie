# Demo Workflow BPMN trong 15 phút

[Danh mục tài liệu](README.md) · [Kiến trúc](ARCHITECTURE.md) · [Triển khai](DEPLOYMENT.md)

## Mục tiêu

Trình diễn PoC phê duyệt e-Office độc lập theo quy trình **gửi yêu cầu → phê duyệt → xem kết quả**. Kết quả hiển thị là trạng thái tiến trình trong Flowable và nhật ký của worker; đây **không phải** là thao tác xuất bản tài liệu trên môi trường production.

Đây là demo engine **độc lập** dành cho PoC. Luồng phê duyệt trong sản phẩm chạy nhúng trong API runtime chính và không cần stack compose này; luồng runtime xem [Kiến trúc](ARCHITECTURE.md).

## Chuẩn bị trước buổi demo

- Đã cài Docker và Docker Compose plugin.
- Đã cài Python 3.11 trở lên.
- Đã tải repository về máy.
- Các cổng `15433` và `18081` chưa bị chương trình khác chiếm dụng trên máy cục bộ.

## Phút 0–3: Khởi động engine

Mở terminal tại thư mục gốc repository và chạy:

```bash
cd spikes/eoffice-approval
export FLOWABLE_REST_PASSWORD='change-this-local-demo-password'
export FLOWABLE_DB_PASSWORD='change-this-local-db-password'
docker compose -f compose.yaml up -d
```

Chờ dịch vụ Flowable REST sẵn sàng tại `http://localhost:18081/flowable-rest/service`. File Compose giới hạn REST endpoint ở loopback; không công khai endpoint này ra Internet. Hai mật khẩu trên chỉ là ví dụ cho môi trường demo cục bộ; hãy thay bằng giá trị riêng của bạn.

## Phút 3–6: Gửi các tiến trình demo

Trong cùng thư mục và terminal, chạy:

```bash
python scripts/seed_demo.py
```

Script triển khai quy trình `bpmn/eoffice-document-approval.bpmn20.xml` và tạo các tiến trình từ `data/demo-documents.json`. Giải thích rằng worker sử dụng lại policy `sensitive_policy.py` của repository, thay vì duy trì một danh sách từ khóa riêng biệt.

**Kết quả mong đợi:** tiến trình demo được tạo trong Flowable và đi đến bước phù hợp trong quy trình BPMN.

## Phút 6–10: Chạy worker và phê duyệt

Mở terminal thứ hai tại `spikes/eoffice-approval`, đặt cùng thông tin xác thực rồi chạy worker:

```bash
export FLOWABLE_REST_PASSWORD='change-this-local-demo-password'
export FLOWABLE_DB_PASSWORD='change-this-local-db-password'
python scripts/worker.py
```

Trong terminal thứ ba, liệt kê các task đang hoạt động:

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" \
  "http://localhost:18081/flowable-rest/service/runtime/tasks?includeProcessVariables=true"
```

Dùng đúng `task ID` do Flowable trả về. Để nhận task, thay `TASK_ID` bằng ID thực tế:

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" \
  -H 'Content-Type: application/json' -X POST \
  "http://localhost:18081/flowable-rest/service/runtime/tasks/TASK_ID" \
  -d '{"action":"claim","assignee":"demo-admin"}'
```

Hoàn tất task với kết quả phê duyệt:

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" \
  -H 'Content-Type: application/json' -X POST \
  "http://localhost:18081/flowable-rest/service/runtime/tasks/TASK_ID" \
  -d '{"action":"complete","variables":[{"name":"approvalOutcome","value":"APPROVE","type":"string"}]}'
```

Với task ở cấp đầu tiên, `REJECT` và `ESCALATE` cũng là các kết quả được hỗ trợ. Hãy dùng ID task và nhóm ứng viên thực tế; không giả định tất cả dữ liệu mẫu đều đi qua cùng một nhánh xử lý.

**Kết quả mong đợi:** task được hoàn tất và trạng thái/kết quả phê duyệt được ghi nhận trong tiến trình. Chỉ người vận hành demo được cấp quyền phù hợp mới nên thực hiện thao tác này.

## Phút 10–13: Hiển thị kết quả

- Truy vấn task hoặc process instance qua Flowable REST.
- Hiển thị process variables và nhật ký của worker.
- Chỉ ra kết quả phê duyệt đã ghi nhận và log bàn giao sang bước publish.

Handler `publish-document` hiện chỉ là **stub (mã mô phỏng)**. Nó không tải tài liệu lên SeaweedFS và không đưa tài liệu vào pipeline ingestion production. Các tác động audit và nhắc việc cũng chỉ ở mức demo, chưa phải tích hợp thông báo production.

## Phút 13–15: Tổng kết và kiểm tra

Chạy các bài kiểm thử tĩnh của PoC từ thư mục gốc repository:

```bash
python -m unittest discover -s spikes/eoffice-approval/tests -v
```

Dừng demo từ thư mục `spikes/eoffice-approval`:

```bash
docker compose -f compose.yaml down
```

Không thêm `-v` nếu cần giữ lại volume cơ sở dữ liệu demo cục bộ.

## Checklist nghiệm thu

- [ ] Tài liệu nêu đủ điều kiện tiên quyết và các bước khởi động cho môi trường mới.
- [ ] Triển khai BPMN và tạo tiến trình demo thành công.
- [ ] Người vận hành demo có quyền phù hợp có thể nhận và hoàn tất task phê duyệt.
- [ ] Có thể quan sát kết quả tiến trình trong Flowable và nhật ký worker.
- [ ] Giới hạn của PoC và những phần chưa tích hợp production được ghi rõ.
- [ ] Trạng thái tiếng Việt và tiếng Anh trong `docs/DX_OS.md` nhất quán.

## Xử lý sự cố thường gặp

- **REST trả về `401`:** kiểm tra `FLOWABLE_REST_PASSWORD` có giống nhau trong Compose và tất cả terminal hay không.
- **Cổng đã được sử dụng:** dừng dịch vụ đang chiếm cổng hoặc thay đổi ánh xạ cổng phía host, sau đó cập nhật URL demo.
- **Không thấy task đang hoạt động:** xem đầu ra worker và process variables; task có thể đã chuyển sang cấp phê duyệt khác hoặc tiến trình đã hoàn tất.
- **Kiểm thử tĩnh thành công:** điều này chỉ xác nhận các kiểm tra trên source assets; không chứng minh runtime Flowable chạy end-to-end thành công trong container.

---

# Workflow BPMN demo — 15 minutes

[Documentation index](README.md) · [Architecture](ARCHITECTURE.md) · [Deployment](DEPLOYMENT.md)

## Goal

Demonstrate the isolated e-Office approval PoC using the sequence **submit → approve → visible result**. The visible result is the Flowable process state and worker logs; it is not a production document publication.

This demo exercises the **isolated** engine built for the PoC. The product-integrated approval flow runs embedded inside the main API runtime and does not need this Compose stack; see [Architecture](ARCHITECTURE.md) for the runtime flow.

## Before the demo

- Docker with the Compose plugin.
- Python 3.11 or newer.
- Repository checked out locally.
- Ports `15433` and `18081` available on loopback.

## 0–3 min: Start the engine

From the repository root:

```bash
cd spikes/eoffice-approval
export FLOWABLE_REST_PASSWORD='change-this-local-demo-password'
export FLOWABLE_DB_PASSWORD='change-this-local-db-password'
docker compose -f compose.yaml up -d
```

Wait for the Flowable REST service at `http://localhost:18081/flowable-rest/service` to become available. The REST endpoint is bound to loopback by the Compose file; do not expose it to the public internet.

## 3–6 min: Submit demo processes

In the same directory and shell:

```bash
python scripts/seed_demo.py
```

The script deploys `bpmn/eoffice-document-approval.bpmn20.xml` and starts process instances from `data/demo-documents.json`. Explain that the worker reuses the repository's `sensitive_policy.py` policy instead of maintaining a separate keyword list.

## 6–10 min: Run worker and approve

Open a second terminal in `spikes/eoffice-approval`, export the same credentials, then start the worker:

```bash
export FLOWABLE_REST_PASSWORD='change-this-local-demo-password'
export FLOWABLE_DB_PASSWORD='change-this-local-db-password'
python scripts/worker.py
```

In a third terminal, list active tasks:

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" \
  "http://localhost:18081/flowable-rest/service/runtime/tasks?includeProcessVariables=true"
```

Use the actual task ID returned by Flowable. Claim the task (replace `TASK_ID`):

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" \
  -H 'Content-Type: application/json' -X POST \
  "http://localhost:18081/flowable-rest/service/runtime/tasks/TASK_ID" \
  -d '{"action":"claim","assignee":"demo-admin"}'
```

Complete the task with an approval outcome:

```bash
curl -u "rest-admin:$FLOWABLE_REST_PASSWORD" \
  -H 'Content-Type: application/json' -X POST \
  "http://localhost:18081/flowable-rest/service/runtime/tasks/TASK_ID" \
  -d '{"action":"complete","variables":[{"name":"approvalOutcome","value":"APPROVE","type":"string"}]}'
```

For a first-level task, `REJECT` and `ESCALATE` are other supported outcomes. Use the task's actual ID and candidate group; do not assume every sample takes the same route.

## 10–13 min: Show the visible result

- Query active tasks/process instances in Flowable REST.
- Show process variables and worker logs.
- Identify the recorded approval outcome and publication hand-off log.

The `publish-document` handler is a **stub**. It does not upload to SeaweedFS or enqueue the production document-ingestion pipeline. Audit and reminder side effects are also demo-level behavior, not production notification integration.

## 13–15 min: Close and verify

Run static PoC tests from the repository root:

```bash
python -m unittest discover -s spikes/eoffice-approval/tests -v
```

Stop the demo from `spikes/eoffice-approval`:

```bash
docker compose -f compose.yaml down
```

Do not use `down -v` if the local demo database must be preserved.

## Acceptance checklist

- [ ] Fresh environment prerequisites and startup steps are documented.
- [ ] BPMN deployment and demo process creation succeed.
- [ ] An authorized demo operator can claim and complete an approval task.
- [ ] Process outcome is visible in Flowable state and worker logs.
- [ ] PoC limitations and production integration gaps are explicit.
- [ ] `docs/DX_OS.md` uses consistent Vietnamese and English status labels.

## Troubleshooting

- If REST calls return `401`, confirm the `FLOWABLE_REST_PASSWORD` is identical in Compose and all terminals.
- If ports are occupied, stop the conflicting local service or change the host-side port mapping and update the demo URL.
- If the process has no active task, inspect the worker output and process variables; the task may have advanced to another approval level or completed.
- Static tests validate source assets only. They do not prove that the containerized Flowable runtime works end-to-end.
