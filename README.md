# Claude Code Bridge ADB

> App Android làm **cầu nối giữa ADB và Claude Code**: app tự mở **đường hầm** ra một địa chỉ `https://…`,
> Claude Code gửi lệnh `adb shell` thẳng vào đó; điện thoại chạy bằng ADB nội bộ (Gỡ lỗi không dây,
> không root, không PC) và trả kết quả về. Không cần tài khoản, không cần server riêng, **không mật khẩu**.

```
[Claude Code] ──HTTPS──▶ https://xxxx.lhr.life/<khoá> ══SSH -R══▶ [Điện thoại: app] ──▶ ADB nội bộ → adbd
 tools/bridge.py          (localhost.run / Pinggy / serveo)       127.0.0.1:8765
```

Phần ADB không dây (ghép nối + kết nối TLS bằng [libadb-android](https://github.com/MuntashirAkon/libadb-android))
lấy từ [zenoue/Debug_unroot](https://github.com/zenoue/Debug_unroot).

## Hai tab

| Tab | Chức năng |
|---|---|
| **CLI** | Gõ lệnh `adb shell` và xem kết quả (kèm exit code). Lệnh Claude Code gửi đến cũng hiện ở đây với tiền tố `claude$`. Có lệnh nhanh, gọi lại lệnh trước, sao chép/xoá nhật ký. |
| **Connect** | Bật/tắt cầu nối, xem địa chỉ đường hầm · Ghép nối & kết nối **Gỡ lỗi không dây** · Nhà cung cấp đường hầm (Tự động/cố định), đổi khoá địa chỉ, chế độ chỉ-đọc · **Sao chép cho Claude Code**. |

## Thiết lập (3 phút, chỉ trên điện thoại)

1. Vào [**Releases**](../../releases/latest) → tải `CCBridgeADB-v1.0.N.apk` → cài (cài đè được bản cũ).
2. Bật **Gỡ lỗi không dây**: Cài đặt → Tuỳ chọn nhà phát triển → Gỡ lỗi không dây (Android 11+, cần đang nối Wi‑Fi).
3. App → tab **Connect**:
   1. **Gỡ lỗi không dây** – Bước 1 ghép nối (cổng + mã 6 số, dùng chia đôi màn hình), Bước 2 kết nối (cổng ở dòng
      "Địa chỉ IP và cổng", hoặc *Tự dò cổng*).
   2. **Bắt đầu cầu nối** → vài giây sau hiện địa chỉ `https://xxxx.lhr.life` (mặc định *Tự động*).
   3. **Sao chép cho Claude Code** → dán sang Claude Code (một dòng `export BRIDGE_URL=…`, đã kèm khoá).

## Phía Claude Code

```bash
export BRIDGE_URL='https://xxxx.lhr.life/<khoá>'   # nút "Sao chép cho Claude Code" trong app

python3 tools/bridge.py ping                    # app còn sống? ADB đã kết nối?
python3 tools/bridge.py exec "dumpsys battery"  # in output, thoát với exit code của lệnh
python3 tools/bridge.py logcat "logcat -v time" # stream logcat (Ctrl+C)
python3 tools/bridge.py stop-logcat
```

Chỉ cần Python 3 (thư viện chuẩn). Xem thêm [`CLAUDE.md`](CLAUDE.md).

- **Claude Code trên máy tính**: dùng được ngay.
- **Claude Code trên web (phiên cloud)**: mạng của phiên bị giới hạn theo danh sách tên miền. Vào cài đặt
  environment (menu environment trên thanh tiêu đề phiên → Edit → Network access) và thêm `*.lhr.life`
  (Pinggy: `*.pinggy.link`, serveo: `*.serveousercontent.com`), hoặc chọn mức truy cập rộng hơn.
  [Hướng dẫn](https://code.claude.com/docs/en/cloud-environments#network-access).

## Đường hầm (viết lại)

- **Nhà cung cấp**: *Tự động* (mặc định) thử `localhost.run` → `Pinggy` → `serveo.net`; nhà nào lỗi 2 lần liên tiếp
  thì chuyển nhà kế, phiên chạy ổn rồi rớt thì lần sau lại ưu tiên nhà đầu. Có thể chọn cố định một nhà.
- **localhost.run** chạy với `--output json` → app đọc địa chỉ từ sự kiện `tcpip-forward` (vẫn dự phòng đọc text).
- **Tự kiểm tra**: mỗi 45 giây app gọi `GET <địa chỉ>/<khoá>/health` qua Internet; hỏng 2 lần liên tiếp → nối lại ngay.
- **Đổi mạng** (Wi‑Fi ↔ 4G): app nối lại đường hầm tức thì thay vì chờ SSH keep‑alive hết hạn.
- Nối lại theo backoff 2s → 30s; đổi nhà cung cấp trong app thì cầu nối tự khởi động lại.

| Đường hầm | Tài khoản | Nhận xét |
|---|---|---|
| **localhost.run** | Không | Không giới hạn thời gian phiên; URL ngẫu nhiên, có thể đổi khi nối lại |
| **Pinggy** | Không | Phiên free giới hạn thời gian (~60 phút), URL đổi mỗi phiên – app tự nối lại |
| **serveo.net** | Không | Hay chập chờn hơn |

### Giao thức

`POST {BRIDGE_URL}/cmd` với JSON `{type, cmd, timeout}` → `{exit, output, next}`.
`type`: `exec` · `ping` · `logcat_start` · `logcat_stop` · `logs` (cmd = id dòng cuối đã đọc).
`GET {BRIDGE_URL}/health` → `ok`. `GET /` (không khoá) → `CC Bridge ADB`. Sai khoá → `404`.

## Bảo mật

- **Không có mật khẩu.** Thay vào đó app tự sinh một khoá ngẫu nhiên 24 ký tự (~140 bit) nằm trong đường dẫn địa chỉ;
  ai không có địa chỉ đầy đủ chỉ nhận `404`. Coi `BRIDGE_URL` như bí mật; lộ thì bấm *Đổi khoá trong địa chỉ*.
- Nhà cung cấp đường hầm giải mã TLS nên **có thể thấy nội dung lệnh, kết quả và cả khoá**. Đừng chạy lệnh in ra dữ liệu nhạy cảm.
- Server trong app chỉ nghe `127.0.0.1`, không lộ ra mạng LAN.
- Mặc định **chỉ-đọc** (cả lệnh tay lẫn lệnh từ xa); bật *Cho phép mọi lệnh* nếu cần.
- Lệnh chạy ở quyền *shell* (uid 2000, như `adb shell`) – không phải root.

## Giới hạn

- Địa chỉ đường hầm miễn phí có thể đổi khi nối lại (mất mạng, đổi Wi‑Fi/4G) → bấm *Sao chép cho Claude Code* lại.
- Gỡ lỗi không dây cần đang nối Wi‑Fi (mạng không có internet cũng được; internet đi qua 4G).
- Cổng kết nối ADB đổi sau mỗi lần tắt/bật Gỡ lỗi không dây hoặc khởi động lại → nhập lại Bước 2.
- Mỗi lệnh tối đa ~10 phút (`BRIDGE_TIMEOUT`); lệnh không tự kết thúc dùng `bridge.py logcat`.

## Build

```bash
./gradlew testDebugUnitTest   # test bộ lọc lệnh, exit code, console, giao thức HTTP + khoá, đọc địa chỉ đường hầm
./gradlew assembleDebug       # cần Android SDK (compileSdk 35), JDK 17
```

Mỗi lần push lên `main_1`, GitHub Actions build APK và tạo release `v1.0.N`; ghi chú release liệt kê
vắn tắt các commit kể từ bản trước.
