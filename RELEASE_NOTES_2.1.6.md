# Screen Protection 2.1.6: Obsidian Precision

**Phát hành GitHub chính thức, bản thử nghiệm nội bộ.** Giao diện được thay mới trên nền Adaptive Core 2.1.5. Đường cong, trần raw 52, chính sách giữ mức sáng và thuật toán lấy mẫu không thay đổi.

## Giao diện

- Obsidian Precision dùng màu nền #0C1116, bề mặt #151D23, chữ #F2F5F5/#9DADB1, điểm nhấn #A7D7BF, cảnh báo #E7BA74, lỗi #F0A4A4.
- Một nút Bật/Tắt tròn duy nhất, đường kính 152 dp, biểu tượng nguồn nét mảnh 28 dp, nhãn 16 sp; trạng thái chính 34 sp, sans-serif Android.
- Hai chỉ số ở cuối màn: Raw X / trần 52 và N lx cùng nhãn ánh sáng thấp/trung bình/cao. Không hiển thị phần trăm độ sáng hay log chẩn đoán trên màn hình.
- Tám trạng thái: tắt, thiếu quyền, khởi động, bảo vệ, giữ mức chỉnh tay, chờ cảm biến, lỗi dịch vụ và khôi phục thất bại.
- Trạng thái bảo vệ yêu cầu Foreground Service thật, MANUAL, cảm biến đã đăng ký và mẫu lux mới. Không coi cờ bật lưu trong bộ nhớ là bằng chứng dịch vụ đang điều khiển.
- Chuyển động đổi trạng thái 200 ms, phản hồi chạm thu nhẹ 90 ms, rung xúc giác nhẹ. Không có gradient, halo, neon hoặc hoạt ảnh liên tục.
- Giao diện không sử dụng lux cũ từ lần chạy trước làm dữ liệu hiện tại.

## Ký và xây dựng

Build variant **release** bằng assembleRelease sau khi testDebugUnitTest thành công. APK ký bằng **debug key mặc định của Android Gradle Plugin trên GitHub Actions runner**, không có khóa phát hành lâu dài, không có tài sản Play Store. Kiểm tra với apksigner verify và xuất SHA256SUMS.txt.

**Cảnh báo cài đè:** khóa debug có thể thay đổi giữa các runner, nên APK release 2.1.6 có thể không cập nhật đè lên 2.1.5. Nếu lỗi chữ ký, hãy tắt Screen Protection trước để khôi phục chế độ sáng Android, sau đó gỡ bản cũ và cài APK mới. Việc gỡ có thể xóa dữ liệu ứng dụng.

## Chưa kiểm chứng trên Redmi K90

1. Bố cục thực tế trên HyperOS 4, cỡ chữ lớn, TalkBack và độ tương phản trong các điều kiện môi trường.
2. Tương quan raw 17/19/49/52 với thanh sáng Xiaomi và độ chói vật lý.
3. Độ trễ thực tế, nhấp nháy và mức pin trong thử nghiệm A/B.
4. HyperOS dừng nền, khôi phục sau khởi động, tắt/bật màn hình và quay lại chế độ AUTO.
5. Độ chính xác trạng thái điều khiển trên firmware K90: trạng thái hệ thống và hoạt động phần cứng có thể khác nhau.

Không root, Magisk, Shizuku, ADB helper, phần mềm thứ ba, overlay hoặc Extra Dim. Các bản phát hành 2.1.5 hiện có được giữ nguyên.
