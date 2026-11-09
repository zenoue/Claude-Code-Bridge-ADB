# CLAUDE.md

## Điều khiển điện thoại Android qua cầu nối

Điện thoại chạy app trong repo này. `tools/bridge.py` gửi lệnh `adb shell` qua đường hầm
(`BRIDGE_URL` dạng `https://xxxx.lhr.life/<khoá>`, lấy từ nút *Sao chép cho Claude Code* trong app;
không có mật khẩu – khoá ngẫu nhiên nằm sẵn trong địa chỉ).

```bash
python3 tools/bridge.py ping                       # luôn ping trước
python3 tools/bridge.py exec "getprop ro.build.version.release"
python3 tools/bridge.py exec "logcat -d -t 200 *:E"
```

- Exit code của `bridge.py exec` = exit code lệnh trên máy. Mã âm từ app → exit 1:
  `-3` bị chặn (chế độ chỉ-đọc), `-4` ADB chưa kết nối, `124` hết giờ.
- HTTP 404: khoá sai/đã đổi. Lỗi HTTP khác/không kết nối được: địa chỉ có thể đã đổi
  → nhờ người dùng sao chép lại từ app.
- Không chạy lệnh không tự kết thúc bằng `exec` (vd `logcat` không có `-d`) – dùng `bridge.py logcat`.
- Mỗi lệnh người dùng thấy ngay trong tab CLI của app (dòng `claude$`).

## Phát triển

- Kotlin, không dùng AndroidX/Compose; UI dựng bằng code (`UiKit.kt`). Không dùng file tài nguyên trong `feature/`.
- 3 module:
  - `feature/` (package `com.zenoue.ccbridge.feature`): **toàn bộ chức năng** – giao diện, ADB không dây, đường hầm, lệnh.
    Build thành `feature.dex`; app **tự tải từ GitHub Releases**, người dùng không cần cài lại APK.
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
- Test: `./gradlew testDebugUnitTest` (test nằm ở `feature/src/test`).
