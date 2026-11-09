# CLAUDE.md

## Điều khiển điện thoại Android qua cầu nối

Điện thoại chạy app trong repo này. `tools/bridge.py` gửi lệnh `adb shell` qua đường hầm
(`BRIDGE_URL` + `BRIDGE_PASSWORD`, lấy từ nút *Sao chép cho Claude Code* trong app).

```bash
python3 tools/bridge.py ping                       # luôn ping trước
python3 tools/bridge.py exec "getprop ro.build.version.release"
python3 tools/bridge.py exec "logcat -d -t 200 *:E"
```

- Exit code của `bridge.py exec` = exit code lệnh trên máy. Mã âm từ app → exit 1:
  `-2` sai mật khẩu, `-3` bị chặn (chế độ chỉ-đọc), `-4` ADB chưa kết nối, `-5` đồng hồ lệch, `124` hết giờ.
- Lỗi HTTP/không kết nối được đường hầm: địa chỉ có thể đã đổi → nhờ người dùng sao chép lại từ app.
- Không chạy lệnh không tự kết thúc bằng `exec` (vd `logcat` không có `-d`) – dùng `bridge.py logcat`.
- Mỗi lệnh người dùng thấy ngay trong tab CLI của app (dòng `claude$`).

## Phát triển

- Kotlin, không dùng AndroidX/Compose; UI dựng bằng code (`UiKit.kt`). Package `com.zenoue.ccbridge`.
- Chỉ dùng nhánh `main`. Mỗi push → CI build APK + release `v1.0.N`, ghi chú = tiêu đề commit,
  nên viết tiêu đề commit ngắn gọn, mô tả thay đổi cho người dùng.
- Test: `./gradlew testDebugUnitTest`.
