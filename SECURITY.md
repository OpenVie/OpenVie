# OpenVie

> **Ngôn ngữ / Language:** [Tiếng Việt](#openvie--bảo-mật) · [English](#openvie-1)

---

## OpenVie — Bảo mật

Đừng đăng lỗ hổng, dữ liệu khách hàng, credential production, hay exploit đang hoạt động lên issue công khai. Dùng nút **Report a vulnerability** trên tab Security của repository này để gửi báo cáo riêng tư.

Ghi rõ revision bị ảnh hưởng, ranh giới tin cậy bị vượt qua, các bước tái hiện bằng dữ liệu tổng hợp, hành vi mong đợi và thực tế, cùng khả năng credential hay dữ liệu tenant bị lộ. Không đính kèm tài liệu tenant thật hay key đang hoạt động. Chưa có cam kết thời gian phản hồi hay danh sách phiên bản được hỗ trợ.

Khi triển khai, chặn truy cập công cộng vào PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu và các service suy luận. File Compose development mở port ra host; bản production bind service nội bộ vào loopback và để APISIX làm entry point công khai — hãy đặt TLS/ingress trước APISIX và không expose console/API trực tiếp. Rà soát provider sinh nội dung đang cấu hình trước khi dùng với nguồn tin mật: provider bên ngoài có thể nhận trích đoạn tài liệu. Repository không chứng nhận triển khai cho dữ liệu chịu điều tiết hay dữ liệu chính phủ. Xem [deployment](docs/DEPLOYMENT.md) và [architecture](docs/ARCHITECTURE.md).

---

# OpenVie

## Security policy

Do not post a vulnerability, customer data, production credentials, or a working exploit in a
public issue. Use the **Report a vulnerability** button on this repository's Security tab to
submit a private report.

Include the affected revision, the trust boundary crossed, steps to reproduce with synthetic
data, expected and actual behavior, and whether credentials or tenant data might be exposed.
Do not attach real tenant documents or live keys. There is no guaranteed response time or
supported-version list yet.

For deployments, protect PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu, and inference
services from public access. The development Compose file publishes ports on the host; production
binds internal services to loopback and exposes APISIX as the public entry point — put TLS/ingress
in front of APISIX and do not expose the console/API directly. Review the configured generation
provider before using confidential sources: an external provider may receive document excerpts.
The repository does not certify a deployment for regulated or government data. See
[deployment](docs/DEPLOYMENT.md) and [architecture](docs/ARCHITECTURE.md).
