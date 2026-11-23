# Screen Protection 2.1.5 Adaptive Core

**Loại phát hành:** Release chính thức trên GitHub, bản thử nghiệm nội bộ. Đây không phải APK đã được chứng nhận hoặc kiểm thử thiết bị Redmi K90.

## Thay đổi so với 2.1.4

- **Một nút BẬT/TẮT:** khi BẬT lưu chế độ độ sáng Android trước đó, chuyển sang MANUAL và điều khiển bằng cảm biến ánh sáng; khi TẮT trả về chế độ gốc.
- **Bỏ Usage Access và cơ chế theo ứng dụng foreground.** Không còn theo dõi ứng dụng đang mở để giữ sáng, cũng không tạo hồ sơ độ sáng riêng cho từng ứng dụng.
- **Giữ sáng khi chỉnh tay:** giữ giá trị đã chỉnh tới khi thay đổi lux đạt ngưỡng `max(12 lux, 45% của lux neo)`, sử dụng hysteresis `max(7 lux, 22% của lux neo)` và xác nhận ít nhất 1.100 ms cùng nhiều mẫu cảm biến. Đổi ứng dụng hay tắt/bật màn hình không tự thả mức chỉnh tay.
- **Giảm trễ theo thiết kế:** cửa sổ cảm biến nhanh/chậm chuyển từ 700/3.200 ms thành **500/1.900 ms**; debounce tăng/giảm thông thường còn 180/420 ms. Vẫn có chống che cảm biến và chống nhấp nháy.
- **Tối ưu chu kỳ lấy mẫu:** sau khoảng 5 giây ổn định dùng chu kỳ **2 giây**, có thể batching 500 ms khi hệ thống hỗ trợ. Màn hình tắt thì ngừng cảm biến.
- **Giữ trần bảo vệ raw 52**, và khi phát hiện người dùng kéo độ sáng hệ thống vượt trần trong lúc bật, ứng dụng đưa về raw 52. Đây là cơ chế phản ứng sau sự kiện, **không phải giới hạn vật lý tức thời tuyệt đối**.
- **Đo độ trễ:** cung cấp log từ sự kiện cảm biến tới khi ghi thành công giá trị Settings, không đo phản ứng nits của panel.

## Ký bản release

APK được tạo bằng **`assembleRelease`** với `signingConfig signingConfigs.debug`. Android Gradle Plugin dùng **debug keystore tự sinh trên GitHub Actions runner**, không phải chứng chỉ ký phát hành riêng và không có tài sản Play Store. GitHub Actions chạy **`:app:testDebugUnitTest` trước `:app:assembleRelease`**, sau đó dùng `apksigner verify --print-certs` để kiểm tra APK.

**Chú ý cập nhật APK:** Debug keystore trên runner có thể khác khóa của bản debug pre-release hoặc bản build khác. Vì vậy bản này **có thể không cập nhật đè** lên APK đã cài. Trước khi gỡ bản cũ, hãy **tắt Screen Protection trong ứng dụng để khôi phục chế độ độ sáng Android**. Nếu Android báo lỗi chữ ký khi cài đè, cần gỡ bản cũ rồi cài APK này; việc gỡ có thể xóa dữ liệu ứng dụng.

## Những điểm CHƯA xác minh trên Redmi K90 / HyperOS 4

1. Tương quan raw **17, 19, 49, 52** với vị trí thanh sáng HyperOS 4 và độ chói hiển thị thực tế. Raw 17/19 là mốc cảm nhận từ Redmi Turbo 5 Max/HyperBrightnessWidget, raw 49/52 là những mốc giới hạn của hai ứng dụng; chưa thể coi là giá trị đo trên K90.
2. **Độ trễ thực tế** khi đi từ tối sang sáng và ngược lại, mức nhấp nháy, chống che cảm biến. Thay đổi hằng số thời gian không tự chứng minh trải nghiệm nhanh hơn.
3. **Tiêu hao pin A/B** của bản 2.1.4 so với bản 2.1.5 trong điều kiện sáng, thời gian bật màn hình, nhiệt độ và tác vụ tương đương. Chưa có số liệu chứng minh tiết kiệm pin.
4. **HyperOS dừng dịch vụ nền**, khởi động lại, tắt/bật màn hình, phục hồi chế độ tự động và trạng thái giữ sáng.
5. Trần raw 52 chỉ được áp trong những lần ứng dụng điều khiển hoặc quan sát thay đổi Settings, không bảo đảm trần phần cứng tuyệt đối nếu ROM có cơ chế ghi đè hoặc điều khiển riêng.

**Không sử dụng** root, Magisk, Zygisk, Shizuku, ADB helper, overlay, Extra Dim hoặc phần mềm chạy kèm.

Bản debug pre-release `screen-protection-v2.1.5-adaptive-core-debug` được giữ nguyên, không thay thế hay xóa.

Dữ liệu SHA-256, commit và chữ ký APK của lần build chính thức được đính kèm hoặc ghi lại bởi GitHub Actions và `SHA256SUMS.txt`.
