# Claude Code Bridge ADB

> App Android làm **cầu nối giữa ADB và Claude Code**: app tự mở **đường hầm** ra một địa chỉ `https://…`,
> Claude Code gửi lệnh `adb shell` thẳng vào đó; điện thoại chạy bằng ADB nội bộ (Gỡ lỗi không dây,
> không root, không PC) và trả kết quả về. Không cần tài khoản, không cần server riêng, **không mật khẩu**.

```
[Claude Code] ──HTTPS──▶ https://xxxx.lhr.life/<khoá> ══SSH -R══▶ [Điện thoại: app] ──▶ ADB nội bộ → adbd
 tools/bridge.py          (localhost.run)                         127.0.0.1:8765
```

Phần ADB không dây (ghép nối + kết nối TLS bằng [libadb-android](https://github.com/MuntashirAkon/libadb-android))
lấy từ [zenoue/Debug_unroot](https://github.com/zenoue/Debug_unroot).

## Hai tab

| Tab | Chức năng |
|---|---|
| **CLI** | Gõ lệnh `adb shell` và xem kết quả (kèm exit code). Lệnh Claude Code gửi đến cũng hiện ở đây với tiền tố `claude$`. Có lệnh nhanh, gọi lại lệnh trước, sao chép/xoá nhật ký. |
| **Connect** | Bật/tắt cầu nối, xem địa chỉ đường hầm · Ghép nối & kết nối **Gỡ lỗi không dây** · Đổi khoá địa chỉ, chế độ chỉ-đọc · **Sao chép cho Claude Code**. |

## Tự cập nhật chức năng (không cần cài lại APK)

Toàn bộ chức năng (giao diện, ADB không dây, đường hầm, lệnh) nằm trong `feature.dex`, tách khỏi APK:

```
APK (cài một lần, từ Releases)            nhánh `live` (CI đẩy mỗi khi code chức năng đổi)
├─ vỏ MainActivity / BridgeService  ◀──── raw.githubusercontent.com/<repo>/live/feature.json {version, api, sha256, file, notes}
├─ FeatureHost: nạp + cập nhật      ◀──── raw.githubusercontent.com/<repo>/live/feature-N.dex
├─ thư viện: libadb, jsch, BC, …
└─ assets/feature.dex (bản dự phòng)
```

Hai workflow tách riêng: **Live update** (đổi `feature/`) chỉ đẩy `feature.dex` lên nhánh `live`, không tạo release;
**Build APK** (đổi `app/`, `api/`, cấu hình Gradle, hoặc chạy tay trong tab Actions) mới build APK và tạo release `v1.0.N`.
Phiên bản chức năng `#N` = số commit trên `main_1`.

- App kiểm tra khi mở, khi bật cầu nối và mỗi 30 phút; tải bản mới, kiểm SHA‑256, lưu read‑only
  (bắt buộc từ Android 14). Bấm **Áp dụng ngay** (Connect → *Cập nhật chức năng*) để khởi động lại app;
  cầu nối đang bật sẽ tự bật lại (địa chỉ đường hầm có thể đổi).
- Giao diện dựng bằng code (không XML, không WebView) nên cập nhật được cả giao diện qua `DexClassLoader`.
- Bản tải về gây lỗi → tự đánh dấu hỏng, quay về bản đi kèm APK; có nút *Quay về bản đi kèm APK*.
- **Vẫn phải cài APK mới** khi đổi: `app/` (manifest, quyền, icon, thư viện), hoặc `api/` (`API_VERSION`).
  Mỗi release trên GitHub đều là một APK mới cần cài.
- Tin cậy: bản tải về đến từ cùng repo và cùng CI với APK, qua HTTPS, kiểm SHA‑256 – ngang mức tin cậy của file APK.

## Thiết lập (3 phút, chỉ trên điện thoại)

1. Vào [**Releases**](../../releases/latest) → tải `CCBridgeADB-v1.0.N.apk` → cài (cài đè được bản cũ).
2. Bật **Gỡ lỗi không dây**: Cài đặt → Tuỳ chọn nhà phát triển → Gỡ lỗi không dây (Android 11+, cần đang nối Wi‑Fi).
3. App → tab **Connect**:
   1. **Gỡ lỗi không dây** – Bước 1 ghép nối (cổng + mã 6 số, dùng chia đôi màn hình), Bước 2 kết nối (cổng ở dòng
      "Địa chỉ IP và cổng", hoặc *Tự dò cổng*).
   2. **Bắt đầu cầu nối** → vài giây sau hiện địa chỉ `https://xxxx.lhr.life`.
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
  hoặc chọn mức truy cập rộng hơn.
  [Hướng dẫn](https://code.claude.com/docs/en/cloud-environments#network-access).

## Đường hầm localhost.run

- Chỉ dùng **localhost.run** (không tài khoản), chạy ngay trong app bằng JSch, tương đương
  `ssh -R 80:localhost:8765 nokey@localhost.run -- --output json`.
- Kênh SSH mở **không chờ xác nhận** (như OpenSSH): localhost.run có thể không trả lời yêu cầu `exec`,
  nếu chờ sẽ lỗi `channel request: timeout`.
- Đọc địa chỉ từ sự kiện JSON `tcpip-forward`; 40 giây chưa có địa chỉ thì lần sau chuyển sang chế độ text (shell).
- **Tự kiểm tra**: mỗi 45 giây app gọi `GET <địa chỉ>/<khoá>/health` qua Internet; hỏng 2 lần liên tiếp → nối lại ngay.
- **Đổi mạng** (Wi‑Fi ↔ 4G): nối lại tức thì. Nối lại theo backoff 2s → 30s.
- Thử cục bộ không cần điện thoại: `tools/mock_lhr.py` (server SSH giả) + `TunnelLiveTest`.

### Giao thức

`POST {BRIDGE_URL}/cmd` với JSON `{type, cmd, timeout}` → `{exit, output, next}`.
`type`: `exec` · `ping` · `logcat_start` · `logcat_stop` · `logs` (cmd = id dòng cuối đã đọc).
`GET {BRIDGE_URL}/health` → `ok`. `GET /` (không khoá) → `CC Bridge ADB`. Sai khoá → `404`.

## Bảo mật

- **Không có mật khẩu.** Thay vào đó app tự sinh một khoá ngẫu nhiên 24 ký tự (~140 bit) nằm trong đường dẫn địa chỉ;
  ai không có địa chỉ đầy đủ chỉ nhận `404`. Coi `BRIDGE_URL` như bí mật; lộ thì bấm *Đổi khoá trong địa chỉ*.
- localhost.run giải mã TLS nên **có thể thấy nội dung lệnh, kết quả và cả khoá**. Đừng chạy lệnh in ra dữ liệu nhạy cảm.
- Server trong app chỉ nghe `127.0.0.1`, không lộ ra mạng LAN.
- Mặc định **chỉ-đọc** (cả lệnh tay lẫn lệnh từ xa); bật *Cho phép mọi lệnh* nếu cần.
- Lệnh chạy ở quyền *shell* (uid 2000, như `adb shell`) – không phải root.

## Giới hạn

- Địa chỉ đường hầm miễn phí có thể đổi khi nối lại (mất mạng, đổi Wi‑Fi/4G) → bấm *Sao chép cho Claude Code* lại.
- Gỡ lỗi không dây cần đang nối Wi‑Fi (mạng không có internet cũng được; internet đi qua 4G) – kể cả Android 17
  (ADB Wi‑Fi 2.0 còn tự tắt khi rời mạng tin cậy). **Không có Wi‑Fi**: khi ADB đang kết nối, bấm
  *Bật ADB không cần Wi‑Fi* (tab Connect) → adbd mở cổng `5555` trên máy (như `adb tcpip 5555`), app nối
  `127.0.0.1:5555`; sau đó tắt Wi‑Fi/chỉ dùng 4G vẫn chạy, tới khi khởi động lại điện thoại (lần đầu bấm *Cho phép gỡ lỗi USB*).
- Cổng kết nối ADB đổi sau mỗi lần tắt/bật Gỡ lỗi không dây hoặc khởi động lại → nhập lại Bước 2.
- Mỗi lệnh tối đa ~10 phút (`BRIDGE_TIMEOUT`); lệnh không tự kết thúc dùng `bridge.py logcat`.

## Build

```bash
./gradlew testDebugUnitTest   # test bộ lọc lệnh, exit code, console, giao thức HTTP + khoá, đọc địa chỉ đường hầm
./gradlew assembleDebug       # APK + feature/build/outputs/feature/{feature.dex,feature.json}; cần Android SDK 35, JDK 17
```

Push lên `main_1`: đổi `feature/` → workflow *Live update* đẩy `feature.dex` lên nhánh `live` (app tự cập nhật);
đổi phần vỏ (`app/`, `api/`, Gradle) → workflow *Build APK* build APK và tạo release `v1.0.N`
(ghi chú = các commit kể từ bản trước). Có thể chạy tay *Build APK* trong tab Actions.
