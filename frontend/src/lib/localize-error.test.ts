// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import { describe, expect, it } from "vitest";
import { localizeErrorMessage } from "./localize-error";

describe("localizeErrorMessage", () => {
  it("translates invalid credentials in Vietnamese and English", () => {
    expect(localizeErrorMessage("Invalid email or password", "vi")).toBe("Email hoặc mật khẩu không đúng");
    expect(localizeErrorMessage("Invalid email or password", "en")).toBe("Invalid email or password");
  });

  it("handles case insensitivity and whitespace trimming", () => {
    expect(localizeErrorMessage("  invalid email or password  ", "vi")).toBe("Email hoặc mật khẩu không đúng");
  });

  it("translates workspace and member errors", () => {
    expect(localizeErrorMessage("User not found", "vi")).toBe("Không tìm thấy người dùng");
    expect(localizeErrorMessage("User not found", "en")).toBe("User not found");
    expect(localizeErrorMessage("That account is already a member of this workspace", "vi"))
      .toBe("Tài khoản này đã là thành viên của không gian làm việc");
  });

  it("translates approval and workflow errors", () => {
    expect(localizeErrorMessage("A rejection requires a reason", "vi")).toBe("Từ chối tài liệu bắt buộc phải có lý do");
    expect(localizeErrorMessage("You can only view approval processes that you submitted", "vi"))
      .toBe("Bạn chỉ có thể xem các quy trình phê duyệt do chính mình nộp");
  });

  it("translates parameterized cooldown message", () => {
    expect(localizeErrorMessage("Please wait 45 seconds before resending", "vi"))
      .toBe("Vui lòng đợi 45 giây trước khi gửi lại");
    expect(localizeErrorMessage("Please wait 45 seconds before resending", "en"))
      .toBe("Please wait 45 seconds before resending");
  });

  it("falls back to the raw message when no translation exists", () => {
    expect(localizeErrorMessage("Custom internal server error #999", "vi")).toBe("Custom internal server error #999");
  });
});
