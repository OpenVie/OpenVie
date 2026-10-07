# OpenVie

> **Ngôn ngữ / Language:** [Tiếng Việt](#openvie--bảo-mật) · [English](#openvie-1)

---

## OpenVie — Bảo mật

Đừng đăng lỗ hổng, dữ liệu khách hàng, credential production, hay exploit đang hoạt động lên issue công khai. Dùng liên kết **Report a vulnerability** trên tab Security của repository này (báo cáo lỗ hổng riêng tư) khi chủ repository đã bật. Nếu chưa có, hãy liên hệ maintainer qua kênh riêng do chủ repository công bố trước khi tiết lộ chi tiết. Maintainer phải bật kênh báo cáo riêng trước khi xuất bản repository; file này không tự đặt địa chỉ email bảo mật không có người giám sát.

Ghi rõ revision bị ảnh hưởng, ranh giới tin cậy bị vượt qua, các bước tái hiện bằng dữ liệu tổng hợp, hành vi mong đợi và thực tế, cùng khả năng credential hay dữ liệu tenant bị lộ. Không đính kèm tài liệu tenant thật hay key đang hoạt động. Chưa có cam kết thời gian phản hồi hay danh sách phiên bản được hỗ trợ.

Khi triển khai, chặn truy cập công cộng vào PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu và các service suy luận: file Compose đi kèm mở port phát triển ra host, và bản phát hành này không kèm reverse proxy, TLS termination hay lọc ở biên — hãy đặt ingress của riêng bạn trước console và API. Rà soát provider sinh nội dung đang cấu hình trước khi dùng với nguồn tin mật: provider bên ngoài có thể nhận trích đoạn tài liệu. Repository không chứng nhận triển khai cho dữ liệu chịu điều tiết hay dữ liệu chính phủ. Xem [deployment](docs/DEPLOYMENT.md) và [architecture](docs/ARCHITECTURE.md).

---

# OpenVie

## Security policy

Do not post a vulnerability, customer data, production credentials, or a working exploit in a
public issue. Use GitHub's **Report a vulnerability** link on this repository's Security tab
(private vulnerability reporting) when the repository owner has enabled it. If it is not
available, contact the maintainers privately through a channel published by the repository
owner before disclosing details. Maintainers must enable a private reporting channel before
publishing the repository; this file does not invent an unmonitored security email address.

Include the affected revision, the trust boundary crossed, steps to reproduce with synthetic
data, expected and actual behavior, and whether credentials or tenant data might be exposed.
Do not attach real tenant documents or live keys. There is no guaranteed response time or
supported-version list yet.

For deployments, protect PostgreSQL, Redis, RabbitMQ, Qdrant, SeaweedFS, Kuzu, and inference
services from public access: the delivered Compose file publishes development ports on the host,
and no reverse proxy, TLS termination, or edge filtering is delivered with this release — put
your own ingress in front of the console and API. Review the configured generation provider
before using confidential sources: an external provider may receive document excerpts. The
repository does not certify a deployment for regulated or government data. See
[deployment](docs/DEPLOYMENT.md) and [architecture](docs/ARCHITECTURE.md).
