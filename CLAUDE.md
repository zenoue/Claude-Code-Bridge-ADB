# CLAUDE.md

## Điều khiển điện thoại Android qua cầu nối

Điện thoại chạy app trong repo này; lệnh `adb shell` đi qua Supabase. Dùng `tools/bridge.py`
(cần `BRIDGE_SUPABASE_URL`, `BRIDGE_SUPABASE_KEY`, `BRIDGE_PASSWORD`).

```bash
python3 tools/bridge.py ping                       # luôn ping trước
python3 tools/bridge.py exec "getprop ro.build.version.release"
python3 tools/bridge.py exec "logcat -d -t 200 *:E"
```

- Exit code của `bridge.py exec` = exit code lệnh trên máy. Mã âm từ app → exit 1:
  `-2` sai mật khẩu, `-3` bị chặn (chế độ chỉ-đọc), `-4` ADB chưa kết nối.
- Không chạy lệnh không tự kết thúc bằng `exec` (vd `logcat` không có `-d`) – dùng `bridge.py logcat`.
- Mỗi lệnh người dùng thấy ngay trong tab CLI của app (dòng `claude$`).

## Phát triển

- Kotlin, không dùng AndroidX/Compose; UI dựng bằng code (`UiKit.kt`). Package `com.zenoue.ccbridge`.
- Chỉ dùng nhánh `main`. Mỗi push → CI build APK + release `v1.0.N`, ghi chú = tiêu đề commit,
  nên viết tiêu đề commit ngắn gọn, mô tả thay đổi cho người dùng.
- Test: `./gradlew testDebugUnitTest`.
