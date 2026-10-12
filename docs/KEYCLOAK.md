
## Cấu hình Cơ sở cho Keycloak OIDC
Cấu hình này dùng để khởi tạo (provision) Keycloak cho phân hệ OIDC của dự án OpenVie MVP-2. Đây chỉ là cấu hình cơ sở cho nhà cung cấp danh tính (identity-provider baseline): các ứng dụng hiện tại gồm Spring API và Next.js client vẫn sẽ tiếp tục sử dụng cơ chế xác thực dựa trên mật khẩu cũ cho đến khi phần việc tích hợp SSO (Đăng nhập một lần) riêng biệt được triển khai và kiểm thử toàn diện (end-to-end).
## Các dịch vụ và lưu trữ dữ liệu (Persistence)

* keycloak: Sử dụng Keycloak phiên bản 26.3.3, tự động nhập (import) cấu hình realm từ tệp keycloak/realm/openvie-realm.json.
* keycloak-db: Cơ sở dữ liệu PostgreSQL chuyên dụng để tách biệt các bảng dữ liệu của Keycloak khỏi cơ sở dữ liệu ứng dụng của OpenVie.
* Cổng kết nối (endpoint) phục vụ cho môi trường phát triển (development) được cấu hình mặc định là 127.0.0.1:18080.
* Đối với môi trường vận hành thực tế (production), cổng container tương tự sẽ được ánh xạ (bind) vào một cổng nội bộ của máy chủ (host-local). Hãy cấu hình một reverse proxy hỗ trợ mã hóa TLS (TLS-terminating reverse proxy) đứng trước Keycloak; tuyệt đối không mở trực tiếp cổng HTTP ra môi trường internet công cộng.

Quá trình nhập realm có tính chất lũy đẳng (idempotent), nghĩa là Keycloak chỉ nhập cấu hình realm khi realm đó chưa tồn tại trong hệ thống. Việc chỉnh sửa tệp JSON sẽ không ghi đè lên realm hiện có. Trong môi trường phát triển, hãy sử dụng một volume cơ sở dữ liệu dùng một lần (disposable volume) nếu bạn muốn kiểm thử quá trình nhập mới hoàn toàn; việc xóa volume sẽ xóa sạch dữ liệu Keycloak đã lưu.
## Khởi động trong môi trường phát triển (Development)

   1. Tạo tệp cấu hình môi trường local (không ghi đè nếu tệp đã tồn tại):
   
   test -f .env || cp .env.example .env
   
   2. Thay đổi giá trị KEYCLOAK_ADMIN_PASSWORD và KEYCLOAK_DB_PASSWORD trong tệp .env nếu máy chủ được chia sẻ với người khác. Các giá trị mặc định này chỉ dành riêng cho môi trường phát triển.
   3. Chỉ khởi động các dịch vụ của nhà cung cấp danh tính (identity-provider):
   
   docker compose up -d keycloak-db keycloak
   docker compose ps keycloak-db keycloak
   docker compose logs -f keycloak
   
   4. Truy cập địa chỉ http://localhost:18080. Thông tin đăng nhập vào trang quản trị (admin console) chính là các giá trị được khai báo tại KEYCLOAK_ADMIN và KEYCLOAK_ADMIN_PASSWORD.

## Cấu hình môi trường vận hành thực tế (Production)

   1. Sao chép tệp .env.production.example thành .env.production và thay thế tất cả các chuỗi bí mật mẫu bằng các giá trị duy nhất. Giới hạn quyền truy cập vào tệp này (ví dụ: chmod 600).
   2. Thiết lập KEYCLOAK_PUBLIC_URL thành URL HTTPS công khai mà reverse proxy sẽ hiển thị ra ngoài, ví dụ: https://example.com. Cấu hình proxy để chuyển tiếp các thông tin về host và scheme gốc (X-Forwarded-*) đến Keycloak.
   3. Thêm URL thực tế của ứng dụng production vào danh sách cho phép (allowlist) của redirect URI và web-origin thuộc client frontend trong tệp JSON của realm trước khi tiến hành nhập lần đầu tiên. Không sử dụng ký tự đại diện rộng * cho redirect URI trên môi trường production.
   4. Khởi động hệ thống production bằng lệnh:
   
   docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
   
   Cổng lắng nghe HTTP của Keycloak sẽ được liên kết với 127.0.0.1:${KEYCLOAK_PORT} trên máy chủ. Hệ thống mặc định rằng việc mã hóa/giải mã TLS sẽ do reverse proxy do quản trị viên vận hành đảm nhận.

## Cấu hình cơ sở cho Realm và Client
Realm: openvie

| Client ID | Loại (Type) | Mục đích (Purpose) |
|---|---|---|
| openvie-frontend | Public OIDC client | Đăng nhập trên trình duyệt sử dụng luồng Authorization Code flow kết hợp PKCE S256 |
| openvie-backend | Confidential OIDC client | Cấu hình cơ sở cho client dịch vụ phía máy chủ (server-side); mã bí mật (secret) của client này không được ghi cứng (hardcode) trong tệp JSON của realm |

Các vai trò (roles) trong Realm được thiết lập sẵn cho việc ánh xạ danh tính sau này:

* ORG_OWNER
* WORKSPACE_ADMIN
* MEMBER

## Redirect URIs và Web Origins
Client frontend sau khi nhập sẽ bao gồm các mẫu redirect URI cơ sở sau:

* http://localhost:3000/* — Máy chủ phát triển Next.js tại máy local
* http://localhost:8088/* — APISIX gateway tại máy local
* https://example.com* — URL giữ chỗ cho môi trường production; hãy thay thế bằng URL ứng dụng thực tế trước khi nhập cấu hình lần đầu trên production

Các web origin tương ứng là http://localhost:3000, http://localhost:8088, và https://example.com. Hãy thay thế URL giữ chỗ của môi trường production trong cả hai danh sách cho phép bằng origin chính xác của production. Tệp JSON của realm là nguồn chân lý duy nhất (source of truth); việc chỉ thay đổi tệp .env sẽ không ghi đè lại các redirect URI đã được nhập trước đó.
URL của nhà phát hành (issuer URL) cho môi trường phát triển local là:
http://localhost:18080/realms/openvie
Đối với môi trường production sẽ là:
<KEYCLOAK_PUBLIC_URL>/realms/openvie
Tuyệt đối không đặt mã bí mật của confidential client (confidential client secret) vào các biến môi trường của frontend hoặc commit lên Git. Confidential client này được khởi tạo như một cấu hình nền tảng; hãy lấy hoặc làm mới (rotate) mã bí mật của nó trực tiếp trong Keycloak trước khi một thành phần phía máy chủ (server-side component) bắt đầu sử dụng. Việc cấu hình Spring Security đóng vai trò như một OIDC resource server và ánh xạ các role của Keycloak sang các role trong workspace của OpenVie là một phần việc tích hợp end-to-end riêng biệt.
## Xác minh (Verification)
Kiểm tra tính hợp lệ của việc chèn biến (interpolation) trong Compose và định dạng YAML:

docker compose config --services
docker compose -f docker-compose.prod.yml --env-file .env.production config --services

Sau khi khởi động các dịch vụ ở môi trường phát triển, hãy xác minh xem endpoint của realm có trả về định dạng JSON hay không:

curl --fail http://localhost:18080/realms/openvie/.well-known/openid-configuration

Kết quả mong đợi: Trả về một tệp cấu hình JSON của OpenID Provider với nhà phát hành (issuer) là http://localhost:18080/realms/openvie. URL tương đương trên môi trường production phải sử dụng tên miền HTTPS công khai đã được cấu hình.
