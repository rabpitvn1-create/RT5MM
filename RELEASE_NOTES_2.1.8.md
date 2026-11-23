# Screen Protection 2.1.8 Obsidian Precision

- Sửa lỗi bản 2.1.7 bị văng khi chạm biểu tượng ứng dụng. Bản 2.1.7 gọi `Window.getInsetsController()` trong lúc dựng `ObsidianSafeFrame`, trước khi Android tạo `DecorView`. Android ném `NullPointerException` ngay trong `MainActivity.onCreate()`. Bản 2.1.8 thiết lập kiểu biểu tượng thanh hệ thống sau khi khung đã gắn vào cửa sổ.
- Sửa thêm lỗi ở khung hình đầu tiên. Các TextView dùng để đo chiều cao tiêu đề và nhãn chưa có LayoutParams. Sau lần đo đầu, khi đổi chuỗi để đo tiếp, Android ném NullPointerException trong TextView.checkForRelayout(). Bản sửa cấp LayoutParams cho ba view đo chữ trước khi sử dụng.
- Giữ nguyên thiết kế Obsidian, xử lý vùng an toàn, hướng dẫn chạy nền HyperOS CN và logic Adaptive Core.
- GitHub Actions chạy kiểm thử đơn vị và mở APK release đã ký trên trình giả lập Android 15 và 16 trước khi phát hành. Bước này kiểm tra màn hình chính thực sự hiển thị sau khi mở.

## APK và chữ ký

APK là biến thể **release**, ký bằng **Android debug key do GitHub Actions runner tạo**, như các bản trước. Android chỉ cho phép cài đè nếu bản đang cài có cùng chứng chỉ. Gỡ bản cũ sẽ xóa dữ liệu và thiết lập của ứng dụng.
