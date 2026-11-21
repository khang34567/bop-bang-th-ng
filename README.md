# VDK Net Limiter Pro v2.0

Ứng dụng Android (không cần Root) giúp theo dõi thiết bị đang kết nối vào Hotspot 4G, xem tốc độ realtime, tên miền truy cập gần nhất, **chọn bóp băng thông riêng cho từng thiết bị (chuẩn từng KB/s)** hoặc **chặn mạng ngay lập tức**.

*Code By VDK • @Developeronetop*

## Tính năng chính
- **Phát hiện & Hiển thị thiết bị:** Quét dải mạng Hotspot (ARP/Neighbor + Ping Sweep), tự nhận diện hãng máy qua địa chỉ MAC (Samsung, Apple, Xiaomi, OPPO, PC...), cho phép đặt tên gợi nhớ cho từng máy.
- **Bóp băng thông chính xác theo từng IP (Per-Device Token Bucket):** Dù thiết bị kia mở nhiều luồng tải song song (YouTube, TikTok, IDM...), tổng tốc độ vẫn bị kìm đúng mức KB/s bạn chọn.
- **Chặn mạng 1 chạm (Instant Kill):** Ngắt toàn bộ kết nối đang chạy của thiết bị đó ngay lập tức mà không cần tắt Hotspot.
- **Chạy ngầm bền bỉ:** Tích hợp Foreground Service + Partial WakeLock chống tắt ngầm trên Samsung One UI Core.
- **Tự động đóng gói APK bằng GitHub Actions:** Chỉ cần up lên GitHub là tự build ra file `.apk` cài thẳng.

## Cách build ra file `.apk` trong 2 phút (không cần Android Studio)
1. Giải nén file `VDK-Net-Limiter-Pro.zip`.
2. Tạo một Repository mới trên GitHub (ví dụ `vdk-net-limiter-pro`).
3. Upload toàn bộ file và thư mục bên trong `VDK-Net-Limiter-Pro/` lên repo (đảm bảo có thư mục `.github/workflows/build-apk.yml`).
4. Vào tab **Actions** trên GitHub -> đợi khoảng 1–2 phút cho workflow **Build VDK Net Limiter Pro APK** chạy xong dấu tích xanh.
5. Bấm vào bản build đó -> kéo xuống mục **Artifacts** -> tải `VDK-Net-Limiter-Pro-APK` về giải nén lấy file `VDK-Net-Limiter-Pro-v2.0.apk` cài lên điện thoại.

## Cách sử dụng
1. Bật **Điểm phát sóng di động (Hotspot)** chia sẻ 4G trên điện thoại của bạn.
2. Mở app **VDK Net Limiter Pro** -> bấm **BẬT PROXY BÓP BĂNG THÔNG**.
3. Trên thiết bị bắt ké Wi-Fi: vào cài đặt Wi-Fi -> chọn **Proxy thủ công** -> nhập đúng **IP** hiển thị trong app (thường là `192.168.43.1`) và **Port `8080`** -> Lưu.
4. Quay lại app trên máy bạn: thiết bị kia sẽ tự xuất hiện với trạng thái **QUA PROXY**, hiển thị tốc độ trực tiếp và web/app đang truy cập -> bấm **Chọn mức bóp / Đổi tên** hoặc **Chặn mạng** tùy ý.
