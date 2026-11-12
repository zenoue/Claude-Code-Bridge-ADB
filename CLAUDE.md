# CLAUDE.md

## Điều khiển điện thoại Android qua cầu nối

Điện thoại chạy app trong repo này. `tools/bridge.py` gửi lệnh `adb shell` qua đường hầm. Cần 2 biến môi trường
(nút *Sao chép cho Claude Code* trong app, chỉ cần sao chép một lần):

- `BRIDGE_ID` – địa chỉ cố định (`ccb-…`). Địa chỉ hầm `https://xxxx.lhr.life` đổi mỗi lần nối lại; app đăng địa chỉ
  hiện tại (có ký) lên `ntfy.sh/<BRIDGE_ID>`, `bridge.py` tự tra và tự tra lại khi địa chỉ cũ chết.
- `BRIDGE_PASSWORD` – mật khẩu chung, không bao giờ gửi đi; mỗi lệnh được ký HMAC‑SHA256.
- `BRIDGE_URL` (tuỳ chọn) – địa chỉ hầm hiện tại, chỉ dùng khi không tra được địa chỉ cố định.

```bash
python3 tools/bridge.py ping                       # luôn ping trước
python3 tools/bridge.py exec "getprop ro.build.version.release"
python3 tools/bridge.py exec "logcat -d -t 200 *:E"
python3 tools/selftest.py                          # tự kiểm tra cầu nối + khả năng ADB
```

- Exit code của `bridge.py exec` = exit code lệnh trên máy. Mã âm từ app → exit 1:
  `-2` sai mật khẩu, `-3` bị chặn (chế độ chỉ-đọc), `-4` ADB chưa kết nối, `-5` đồng hồ lệch >5 phút,
  `-6` lệnh trùng (đã nhận trước đó), `124` hết giờ.
- "no reachable tunnel": cầu nối chưa bật trong app, hoặc mạng phiên này chặn `ntfy.sh` / `*.lhr.life`.
- Không chạy lệnh không tự kết thúc bằng `exec` (vd `logcat` không có `-d`) – dùng `bridge.py logcat`.
- Mỗi lệnh người dùng thấy ngay trong tab CLI của app (dòng `claude$`).
- Người dùng không clone repo: nút *Sao chép cho Claude Code* chép cả lệnh tải `tools/bridge.py` từ nhánh `main_1`
  về `~/.ccbridge/bridge.py` lẫn hướng dẫn dùng (`ClaudeSetup.kt`). Đổi cách dùng `bridge.py` thì sửa cả đoạn đó.

## Phát triển

- Kotlin, không dùng AndroidX/Compose; UI dựng bằng code (`UiKit.kt`). Không dùng file tài nguyên trong `feature/`.
- **Ngôn ngữ**: toàn bộ code, comment, log gửi về Claude Code, script, workflow viết bằng **tiếng Anh**. Chỉ chuỗi
  **hiển thị trên giao diện app** (nhãn, nút, toast, thông báo, dòng trong tab CLI, trạng thái) là tiếng Việt.
  Tiêu đề commit và README/CLAUDE.md viết tiếng Việt (tiêu đề commit hiện trong app ở mục cập nhật).
  README có 3 bản cùng nội dung: `README.md` (tiếng Việt, bản gốc), `README.en.md`, `README.zh.md` – sửa một bản thì sửa cả ba.
- 3 module:
  - `feature/` (package `com.zenoue.ccbridge.feature`): **toàn bộ chức năng** – giao diện, ADB không dây, đường hầm, lệnh.
    Build thành `feature.dex`; app **tự tải từ nhánh `live`**, người dùng không cần cài lại APK.
  - `app/` (package `com.zenoue.ccbridge`): vỏ APK – Activity/Service chuyển tiếp, nạp & cập nhật feature (`FeatureHost.kt`),
    manifest, icon, thư viện (libadb, jsch, bouncycastle, conscrypt). Đổi ở đây = người dùng phải cài APK mới.
  - `api/`: hợp đồng giữa hai bên (`Api.kt`). Đổi chữ ký nào → **tăng `API_VERSION`** (bản cũ sẽ báo cần APK mới).
- Ưu tiên sửa trong `feature/`. Cần thư viện mới, quyền mới, tài nguyên mới → phải sửa `app/` (cần APK mới).
  `feature` chỉ `compileOnly` thư viện; thư viện đó phải có trong `app` (`implementation`).
- Chỉ dùng nhánh `main_1` (nhánh `live` do CI tạo, không sửa tay). Hai workflow tách riêng:
  - **Live update** (push đổi `feature/` hoặc `api/`): build `feature.dex` → đẩy lên nhánh `live`, **không release**.
    Phiên bản chức năng `#N` = số commit; ghi chú hiện trong app = tiêu đề commit.
  - **Build APK** (push đổi `app/`, `api/`, cấu hình Gradle, hoặc chạy tay): build APK → release `v1.0.N`.
  Viết tiêu đề commit ngắn gọn, mô tả thay đổi cho người dùng.
- Test: `./gradlew testDebugUnitTest` (test nằm ở `feature/src/test`; `EndToEndTest` chạy `tools/bridge.py` thật
  với server Kotlin thật, cần `python3`). Đổi giao thức thì sửa cả `HttpApi.kt`/`Pointer.kt` lẫn `tools/bridge.py`.
