> **Ngôn ngữ / Language:** [Tiếng Việt](#cấu-hình-cơ-bản-cho-keycloak-oidc) · [English](#keycloak-oidc-basic-configuration)

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
2. Đặt `KEYCLOAK_PUBLIC_URL` thành URL HTTPS công khai mà reverse proxy sẽ phục vụ, ví dụ `https://example.com`. Cấu hình proxy chuyển tiếp host và giao thức gốc qua các header `X-Forwarded-*` tới Keycloak. Để bật SSO, đặt `SSO_ENABLED=true`; đặt `SSO_ISSUER_URI=<KEYCLOAK_PUBLIC_URL>/realms/openvie` và giữ `SSO_FRONTEND_CLIENT_ID`/`SSO_ADMIN_CLIENT_ID` trùng với client id trong realm.
3. Trước lần import đầu tiên, cập nhật redirect URI và web origin của hai client frontend (`openvie-frontend`, `openvie-admin`) trong tệp JSON realm bằng URL production thật. Không dùng ký tự đại diện `*` quá rộng cho redirect URI trên production.
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
| `openvie-admin` | Public OIDC client | Giao diện quản trị (`/admin/*`, port 3001): Authorization Code flow kèm PKCE S256, tách khỏi ứng dụng chính |
| `openvie-backend` | Confidential OIDC client | Client dự phòng cho các luồng server-to-server; chưa bật service account/client-credentials trong realm export; secret không ghi cứng vào tệp JSON realm |

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

Không đặt secret của confidential client vào biến môi trường frontend và không commit lên Git. `openvie-backend` là client dành cho các luồng server-to-server; secret nạp qua `SSO_BACKEND_CLIENT_SECRET` nhưng hiện chưa có đoạn code nào sử dụng, và service account trong realm export vẫn đang tắt.

Hai public client `openvie-frontend` và `openvie-admin` đều dùng PKCE S256. Khi đăng nhập, frontend gửi `clientId` tới `/api/v1/auth/sso/exchange`; backend chỉ chấp nhận `SSO_FRONTEND_CLIENT_ID` hoặc `SSO_ADMIN_CLIENT_ID` (mặc định `openvie-admin`) và đổi code với đúng client đó. API tự liên kết danh tính và ánh xạ vai trò an toàn.

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

* `POST /api/v1/auth/sso/exchange` với nội dung `{ "code", "codeVerifier", "redirectUri", "clientId" }`. `clientId` là public client đã nhận code (`openvie-frontend` hoặc `openvie-admin`); nếu bỏ trống, backend dùng `SSO_FRONTEND_CLIENT_ID`.
* Backend chỉ chấp nhận hai client id đã cấu hình. Khi đổi code, backend gửi kèm `code_verifier` và dùng đúng client đã nhận code, không dùng secret; Keycloak sẽ từ chối nếu client dùng để đổi code khác client đã phát code.
* Secret của `openvie-backend` để dành cho machine-to-machine sau này; đọc từ biến môi trường phía server và không commit.
* Thành công → `200` + `AuthResponse` chuẩn (gồm `user.orgRole`, `user.workspaceRole`, `activeWorkspaceId`) + cookie refresh như `/login`. Lớp bảo vệ route `(admin)` và menu đọc các trường này, nên sau SSO frontend phải nạp đủ `user`, không chỉ giữ token.
* Lỗi: `404 SSO_UNKNOWN_EMAIL` khi email chưa có tài khoản (không tự tạo tài khoản); `403 SSO_ROLE_UNKNOWN` khi không có realm role hợp lệ; `401` khi đổi code hoặc xác thực token với Keycloak thất bại, SSO bị tắt, tài khoản bị vô hiệu hóa hoặc phiên đăng nhập không còn hợp lệ; `409` khi email chưa được xác minh hoặc `sub` đã liên kết với tài khoản khác; `400` khi `clientId` không nằm trong danh sách client được phép. `SSO_OTP_REQUIRED` hiện chưa dùng — dành cho nhánh TOTP sau này.
* Sau khi đổi token, các thao tác refresh/chuyển workspace/đăng xuất vẫn gọi endpoint cũ; không có endpoint SSO riêng.

---

## Keycloak OIDC basic configuration

This guide covers configuring and operating Keycloak OIDC as the identity provider for OpenVie. The system supports two login paths: traditional password login and single sign-on (SSO) through Keycloak OIDC (Authorization Code flow with PKCE S256).

## Services and storage

* **keycloak:** Keycloak 26.3.3, automatically imports the realm configuration from `keycloak/realm/openvie-realm.json`.
* **keycloak-db:** a dedicated PostgreSQL database for Keycloak, keeping Keycloak tables separate from the OpenVie application database.
* The development environment uses `127.0.0.1:18080`.
* In production, the container port also listens only on the host loopback. Put a TLS-terminating reverse proxy in front of Keycloak; never expose the HTTP port directly to the Internet.

Realm import is idempotent: Keycloak imports only when the realm does not exist. Editing the JSON file does not overwrite an existing realm. To try a fresh import in development, use a disposable database volume; deleting the volume also deletes all Keycloak data.

## Starting the development environment

1. Create the local configuration file (without overwriting an existing one):

   ```bash
   test -f .env || cp .env.example .env
   ```

2. Change `KEYCLOAK_ADMIN_PASSWORD` and `KEYCLOAK_DB_PASSWORD` in `.env` on a shared machine. The defaults are for development only.
3. Start only the identity provider services:

   ```bash
   docker compose up -d keycloak-db keycloak
   docker compose ps keycloak-db keycloak
   docker compose logs -f keycloak
   ```

4. Open `http://localhost:18080`. The admin console account is `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`.

## Production configuration

1. Copy `.env.production.example` to `.env.production` and replace every sample secret with your own value. Restrict file permissions (for example, `chmod 600`).
2. Set `KEYCLOAK_PUBLIC_URL` to the public HTTPS URL served by the reverse proxy, for example `https://example.com`. Configure the proxy to forward the original host and protocol through the `X-Forwarded-*` headers to Keycloak. To enable SSO, set `SSO_ENABLED=true`, set `SSO_ISSUER_URI=<KEYCLOAK_PUBLIC_URL>/realms/openvie`, and keep `SSO_FRONTEND_CLIENT_ID`/`SSO_ADMIN_CLIENT_ID` aligned with the client ids in the realm.
3. Before the first import, update the redirect URIs and web origins of both frontend clients (`openvie-frontend`, `openvie-admin`) in the realm JSON file with the real production URL. Do not use an overly broad `*` wildcard for production redirect URIs.
4. Start the production system:

   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
   ```

The Keycloak HTTP port listens only on the host's `127.0.0.1:${KEYCLOAK_PORT}`. TLS termination is delegated to the operator's reverse proxy by default.

## Basic realm and client configuration

Realm: `openvie`

| Client ID | Type | Purpose |
|---|---|---|
| `openvie-frontend` | Public OIDC client | Browser login using Authorization Code flow with PKCE S256 |
| `openvie-admin` | Public OIDC client | Admin UI (`/admin/*`, port 3001): Authorization Code flow with PKCE S256, separate from the main application |
| `openvie-backend` | Confidential OIDC client | Placeholder for server-to-server flows; service accounts/client-credentials are disabled in the realm export; the secret is not hard-coded in the realm JSON file |

The realm roles are created up front for later identity mapping:

* `ORG_OWNER`
* `WORKSPACE_ADMIN`
* `MEMBER`

## Redirect URIs and web origins

After import, the frontend client has these redirect URI patterns:

* `http://localhost:3000/*` — local Next.js dev server
* `http://localhost:8088/*` — local APISIX gateway
* `https://example.com*` — production placeholder; replace it with the real application URL before the first production import

The corresponding web origins are `http://localhost:3000`, `http://localhost:8088`, and `https://example.com`. Replace the production placeholder in both allowlists with the exact production origin. The realm JSON file is the single source of truth; editing only `.env` does not overwrite an imported redirect URI.

After import, the admin client (`openvie-admin`) contains:

* `http://localhost:3001/*` — admin Next.js UI running directly on the local machine
* `http://localhost:8088/admin/*` — admin UI through the APISIX gateway
* `https://example.com/admin/*` — production placeholder

> **Previously imported realm:** realm import is idempotent and does not overwrite an existing realm, so dev realms created before this version **do not have** the `openvie-admin` client. Create it manually in the admin console with the redirect URIs above, or delete the `keycloak_db_data` volume to import from scratch.

The issuer URL for local development is `http://localhost:18080/realms/openvie`. In production it is `<KEYCLOAK_PUBLIC_URL>/realms/openvie`.

Never put a confidential-client secret in frontend environment variables or commit it to Git. `openvie-backend` is a client reserved for server-to-server flows; its secret is loaded from `SSO_BACKEND_CLIENT_SECRET`, but no code currently uses it, and service accounts remain disabled in the realm export.

Both public clients `openvie-frontend` and `openvie-admin` use PKCE S256. During login, the frontend sends `clientId` to `/api/v1/auth/sso/exchange`; the backend accepts only `SSO_FRONTEND_CLIENT_ID` or `SSO_ADMIN_CLIENT_ID` (default `openvie-admin`) and exchanges the code with that exact client. The API links the identity and maps roles safely.

## Verification

Check Compose interpolation and YAML syntax:

```bash
docker compose config --services
docker compose -f docker-compose.prod.yml --env-file .env.production config --services
```

After starting the dev services, check that the realm endpoint returns JSON:

```bash
curl --fail http://localhost:18080/realms/openvie/.well-known/openid-configuration
```

Expected result: an OpenID Provider JSON document whose issuer is `http://localhost:18080/realms/openvie`. In production, the URL must use the configured public HTTPS domain.

## SSO integration contract (frontend OIDC and backend exchange)

The OIDC flow between the frontend (`openvie-frontend`, `openvie-admin`), the Spring Boot backend (`api/`), and Keycloak:

* `POST /api/v1/auth/sso/exchange` with `{ "code", "codeVerifier", "redirectUri", "clientId" }`. `clientId` is the public client that received the code (`openvie-frontend` or `openvie-admin`); when omitted, the backend uses `SSO_FRONTEND_CLIENT_ID`.
* The backend accepts only the two configured client ids. During the exchange it sends `code_verifier` and uses the exact client that received the code, with no secret; Keycloak rejects an exchange that uses a client different from the one that issued the code.
* The `openvie-backend` secret is reserved for future machine-to-machine use; read it from a server-side environment variable and never commit it.
* Success → `200` + the standard `AuthResponse` (including `user.orgRole`, `user.workspaceRole`, and `activeWorkspaceId`) + the refresh cookie, exactly like `/login`. The `(admin)` route guard and menu read these fields, so the frontend must load the full `user` after SSO, not just keep the token.
* Errors: `404 SSO_UNKNOWN_EMAIL` when the email has no account (the system does not auto-create accounts); `403 SSO_ROLE_UNKNOWN` when no valid realm role is present; `401` when the Keycloak code exchange or token validation fails, SSO is disabled, the account is disabled, or the session is no longer valid; `409` when the email is not verified or the `sub` is already linked to another account; `400` when `clientId` is not in the allowed client list. `SSO_OTP_REQUIRED` is not used yet — reserved for a future TOTP branch.
* After the token exchange, refresh, workspace switching, and logout still use the existing endpoints; there is no separate SSO endpoint.
