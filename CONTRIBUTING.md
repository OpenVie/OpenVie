# OpenVie

> **Ngôn ngữ / Language:** [Tiếng Việt](#openvie--đóng-góp) · [English](#openvie-1)

---

## OpenVie — Đóng góp

OpenVie nhận đóng góp cho ứng dụng Apache-2.0 trong repository này. Quyền sử dụng, sửa đổi và phân phối mã nguồn của bạn đến từ [Apache License 2.0](LICENSE), không đến từ bất kỳ dịch vụ hay thỏa thuận nào.

### Trước khi gửi

- Đọc [phát triển cục bộ](docs/DEVELOPMENT.md), [kiến trúc](docs/ARCHITECTURE.md), và guide theo ngôn ngữ liên quan trong `api/` hoặc `rag-chatbot-fastapi/`.
- Mở issue hoặc discussion trước khi viết patch lớn nếu thay đổi hợp đồng công khai, quyền sở hữu lưu trữ, cô lập tenant, hay cách xử lý dữ liệu model.
- Chỉ dùng dữ liệu, trọng số model, tài sản và mã nguồn mà bạn có quyền đóng góp. Không đưa tài liệu khách hàng, thông tin cá nhân, API token, file `.env` cục bộ, hay checkpoint model vào issue, pull request, test, hay ảnh chụp màn hình. Hãy dùng fixture tự dựng.
- Khi gửi đóng góp để đưa vào, bạn đồng ý với điều khoản đóng góp tại mục 5 của [Apache License 2.0](LICENSE). Đảm bảo chủ lao động hoặc chủ thể quyền khác cho phép. Repository này không yêu cầu thỏa thuận cấp phép đóng góp riêng.

### Pull request

1. Giữ thay đổi gọn theo phạm vi, giải thích hành vi người dùng thấy được và ảnh hưởng tương thích.
2. Giữ ranh giới nghiệp vụ có thẩm quyền Spring/PostgreSQL và ranh giới chỉ mục phái sinh của Python. Không tin tenant ID do client chưa xác thực gửi lên.
3. Với thay đổi định dạng truyền tải, cập nhật hợp đồng và cả hai phía tiêu thụ cùng lúc; mô tả năng lực mới hoặc bị gỡ mà không trình bày công việc đề xuất như đã phát hành.
4. Chạy các kiểm tra phù hợp với module đã sửa trong [phát triển cục bộ](docs/DEVELOPMENT.md#verification). Với thay đổi an toàn hay trích dẫn, kèm test thể hiện cả đường bị từ chối và đường được phép.
5. Cập nhật tài liệu liên quan và nêu rõ kiểm tra runtime/provider nào bạn chưa chạy được.

Báo cáo vấn đề bảo mật qua kênh riêng, không đăng issue công khai; xem [SECURITY.md](SECURITY.md).

---

# OpenVie

## Contributing to OpenVie

OpenVie accepts contributions to the Apache-2.0 application in this repository. Your rights to
use, modify, and distribute the code come from the [Apache License 2.0](LICENSE), not from any
service or agreement.

### Before submitting

- Read [local development](docs/DEVELOPMENT.md), the [architecture](docs/ARCHITECTURE.md),
  and the relevant language-specific guide in `api/` or `rag-chatbot-fastapi/`.
- Open an issue or discussion for changes to public contracts, storage ownership, tenant
  isolation, or model-data handling before writing a large patch.
- Use data, model weights, assets, and code you have permission to contribute. Never include
  customer documents, personal information, API tokens, local `.env` files, or model checkpoints
  in issues, pull requests, tests, or screenshots. Use invented fixtures.
- By submitting a contribution for inclusion, you agree to the contribution terms in section 5
  of the [Apache License 2.0](LICENSE). Ensure your employer or other rights holders authorize it.
  No separate contributor license agreement is required by this repository.

### Pull requests

1. Keep changes scoped and explain the user-visible behavior and any compatibility impact.
2. Preserve the Spring/PostgreSQL authoritative business boundary and Python-derived index
   boundary. Never trust a tenant ID supplied by an unauthenticated client.
3. Update contracts and both consumers together for wire-format changes; document new or
   removed capabilities without describing proposed work as shipped.
4. Run the checks appropriate to the touched modules in [local development](docs/DEVELOPMENT.md#verification).
   For safety or citation changes, include a test demonstrating the denied and allowed paths.
5. Update relevant documentation and describe any runtime/provider check you could not run.

Report security problems privately, not in a public issue; see [SECURITY.md](SECURITY.md).
