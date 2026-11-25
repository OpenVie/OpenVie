## Cấu hình cơ bản cho Keycloak OIDC

Tài liệu này hướng dẫn cấu hình và vận hành Keycloak OIDC làm nhà cung cấp danh tính cho OpenVie. Hệ thống hỗ trợ hai cách đăng nhập: mật khẩu truyền thống và đăng nhập một lần (SSO) qua Keycloak OIDC (Authorization Code flow kèm PKCE S256).

## Dịch vụ và lưu trữ

* **keycloak:** Keycloak 26.3.3, tự động import cấu hình realm từ `keycloak/realm/openvie-realm.json`.
* **keycloak-db:** PostgreSQL riêng cho Keycloak, tách bảng dữ liệu Keycloak khỏi database ứng dụng OpenVie.
* Môi trường phát triển dùng cổng `127.0.0.1:18080`.
* Ở production, cổng container cũng chỉ lắng nghe trên loopback của host. Đặt một reverse proxy kết thúc TLS trước Keycloak; tuyệt đối không mở cổng HTTP trực tiếp ra Internet.

Import realm là thao tác idempotent: Keycloak chỉ import khi realm chưa tồn tại. Sửa tệp JSON sẽ không ghi đè realm đã có. Muốn thử import lại từ đầu trong môi trường phát triển, hãy dùng volume database dùng một lần; xóa volume cũng xóa sạch dữ liệu Keycloak.

## Khởi động trong môi trường phát triển

1. Tạo tệp cấu hình local (không ghi đè nếu đã có):

   ```bash
   test -f .env || cp .env.example .env
   ```

2. Đổi `KEYCLOAK_ADMIN_PASSWORD` và `KEYCLOAK_DB_PASSWORD` trong `.env` nếu máy dùng chung. Các giá trị mặc định chỉ dành cho phát triển.
3. Chỉ khởi động các dịch vụ identity provider:

   ```bash
   docker compose up -d keycloak-db keycloak
   docker compose ps keycloak-db keycloak
   docker compose logs -f keycloak
   ```

4. Mở `http://localhost:18080`. Tài khoản admin console là `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`.

## Cấu hình production

1. Sao chép `.env.production.example` thành `.env.production` và thay mọi chuỗi bí mật mẫu bằng giá trị riêng. Giới hạn quyền đọc tệp (ví dụ `chmod 600`).
2. Đặt `KEYCLOAK_PUBLIC_URL` thành URL HTTPS công khai mà reverse proxy sẽ phục vụ, ví dụ `https://example.com`. Cấu hình proxy chuyển tiếp host và giao thức gốc qua các header `X-Forwarded-*` tới Keycloak.
3. Trước lần import đầu tiên, thêm URL production thật vào danh sách redirect URI và web origin được phép của client frontend trong tệp JSON realm. Không dùng ký tự đại diện `*` quá rộng cho redirect URI trên production.
4. Khởi động hệ thống production:

   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
   ```

Cổng HTTP của Keycloak chỉ lắng nghe trên `127.0.0.1:${KEYCLOAK_PORT}` của host. Hệ thống mặc định việc mã hóa/giải mã TLS do reverse proxy của người vận hành đảm nhận.

## Cấu hình cơ bản cho realm và client

Realm: `openvie`

| Client ID | Loại | Mục đích |
|---|---|---|
| `openvie-frontend` | Public OIDC client | Đăng nhập trên trình duyệt bằng Authorization Code flow kèm PKCE S256 |
| `openvie-admin` | Public OIDC client | Giao diện quản trị (`/admin/*`, port 3001): Authorization Code flow kèm PKCE S256, tách khỏi app chính |
| `openvie-backend` | Confidential OIDC client | Cấu hình cơ sở cho client phía server; secret không được ghi cứng trong tệp JSON realm |

Các vai trò trong realm được tạo sẵn để ánh xạ danh tính sau này:

* `ORG_OWNER`
* `WORKSPACE_ADMIN`
* `MEMBER`

## Redirect URI và web origin

Client frontend sau khi import có các mẫu redirect URI:

* `http://localhost:3000/*` — Next.js dev server trên máy local
* `http://localhost:8088/*` — APISIX gateway trên máy local
* `https://example.com*` — URL giữ chỗ production; thay bằng URL ứng dụng thật trước lần import đầu tiên trên production

Web origin tương ứng là `http://localhost:3000`, `http://localhost:8088` và `https://example.com`. Thay URL giữ chỗ production ở cả hai allowlist bằng origin production chính xác. Tệp JSON realm là nguồn chuẩn duy nhất; chỉ sửa `.env` sẽ không ghi đè redirect URI đã import.

Client admin (`openvie-admin`) sau khi import gồm:

* `http://localhost:3001/*` — giao diện quản trị Next.js chạy trực tiếp trên máy local
* `http://localhost:8088/admin/*` — giao diện quản trị qua APISIX gateway
* `https://example.com/admin/*` — URL giữ chỗ production

> **Realm đã import trước đó:** import realm là idempotent và không ghi đè realm đã có, nên các realm dev tạo trước bản này **chưa có** client `openvie-admin`. Hãy tạo thủ công trên admin console theo đúng các redirect URI ở trên, hoặc xóa volume `keycloak_db_data` để import lại từ đầu.

Issuer URL cho môi trường phát triển local là `http://localhost:18080/realms/openvie`. Ở production, URL này là `<KEYCLOAK_PUBLIC_URL>/realms/openvie`.

Tuyệt đối không đặt secret của confidential client vào biến môi trường frontend hay commit lên Git. Client này được cấu hình an toàn phía server; secret nạp qua biến `SSO_BACKEND_CLIENT_SECRET`. Luồng OIDC công khai của frontend (`openvie-frontend`, `openvie-admin`) dùng PKCE S256 và đổi token qua endpoint `/api/v1/auth/sso/exchange` của Spring Boot API; API tự liên kết danh tính và ánh xạ vai trò an toàn.

## Xác minh

Kiểm tra Compose nội suy biến và cú pháp YAML:

```bash
docker compose config --services
docker compose -f docker-compose.prod.yml --env-file .env.production config --services
```

Sau khi khởi động các dịch vụ dev, kiểm tra endpoint realm trả JSON:

```bash
curl --fail http://localhost:18080/realms/openvie/.well-known/openid-configuration
```

Kết quả mong đợi: cấu hình OpenID Provider dạng JSON với issuer `http://localhost:18080/realms/openvie`. Ở production, URL tương ứng phải dùng tên miền HTTPS công khai đã cấu hình.

## Hợp đồng tích hợp SSO (frontend OIDC và backend exchange)

Luồng OIDC giữa frontend (`openvie-frontend`, `openvie-admin`), Spring Boot backend (`api/`) và Keycloak:

* `POST /api/v1/auth/sso/exchange` với nội dung `{ "code", "codeVerifier", "redirectUri" }` (Authorization Code + PKCE S256 cho public client `openvie-frontend`; backend đổi code bằng đúng client này + `code_verifier`, không dùng secret — Keycloak từ chối khi client đổi code khác client nhận code. Secret của `openvie-backend` để dành cho machine-to-machine sau này, đọc từ env phía server và không commit).
* Thành công → `200` + `AuthResponse` chuẩn (gồm `user.orgRole`, `user.workspaceRole`, `activeWorkspaceId`) + cookie refresh như `/login`. Lớp bảo vệ route `(admin)` và menu đọc các trường này, nên sau SSO frontend phải nạp đủ `user`, không chỉ giữ token.
* Lỗi: `404 SSO_UNKNOWN_EMAIL` (email chưa tồn tại — không tự tạo tài khoản), `401 SSO_UNLINKED` (sub lạ hoặc không liên kết được), `403 SSO_ROLE_UNKNOWN` (vai trò Keycloak lạ), `409 SSO_OTP_REQUIRED` (để dành cho tương lai khi tổ chức bật xác nhận TOTP; hiện chưa dùng).
* Sau khi đổi token, các thao tác refresh/chuyển workspace/đăng xuất vẫn gọi endpoint cũ; không có endpoint SSO riêng.
