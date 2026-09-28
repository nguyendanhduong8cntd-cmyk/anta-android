# Audit UI/UX Alobo Android

## Phạm vi đối chiếu

- Ground truth: APK `com.alobo.sportUser` 2.10.1 và toàn bộ ảnh trong `docs/clone-capture`.
- Màn đã đối chiếu: onboarding, Home, Map, Explore và 4 filter, Featured, Account, Login phone/email, Register, Forgot Password, Search, Advanced Search, Venue Detail và các tab, booking chooser/schedule, event, QR, Terms, mục lục và các dialog.
- Các quyết định giữ nguyên theo yêu cầu clone: portrait-only, giao diện sáng của app gốc và ẩn system navigation bar.

## Các lỗi đã xử lý

| Nhóm | Trước audit | Sau cập nhật |
|---|---|---|
| Responsive | Canvas scale X/Y độc lập, làm méo tròn/icon ở màn 1080×2340 | Scale đồng nhất theo chiều rộng; nav neo đáy theo viewport |
| Bottom nav | Panel phẳng, active color một màu, hình học và shadow lệch | Panel có hump đúng cấu trúc, vòng center, gradient gốc `#00D31C → #006D38`, icon SVG gốc và selected state |
| Map | Danh sách được dựng lại nên font, line-height và row-height lệch | Nội dung dùng đúng bố cục tham chiếu gốc; click target vẫn tách riêng |
| Form | `18sp` bị nhân density, hint lớn hơn app gốc | Cỡ chữ theo pixel thiết kế, input type/IME/autofill đúng, draft được giữ khi đổi tab |
| Form actions | Icon mắt, xóa và mã vùng chỉ hiển thị | Đã có hành vi click, log và hit area gần/tối thiểu 48dp |
| Back stack | Chi tiết sân luôn quay về Search; Forgot có thể về Account | Back quay về đúng màn nguồn và giữ state trước đó |
| Feedback | Press không có phản hồi | Có state layer khi nhấn, drag threshold giữ nguyên để tránh click nhầm |
| Accessibility | Toàn Canvas chỉ là một node vô danh | Mỗi button/tab/card/icon/dialog có node native, nhãn, bounds và selected state |
| Memory | Cache giữ vô hạn bitmap full-screen | LRU 64MB, trim còn 32MB và giải phóng khi app vào background |

## Kiểm tra hoàn tất

- `assembleDebug`: thành công.
- `lintDebug`: 0 lỗi. Warning còn lại chủ yếu là portrait lock theo yêu cầu và vector path nguyên bản không được rút gọn.
- ADB install: thành công trên `emulator-5554`.
- Visual: 1080×2400, 1080×2340 và 984×2187 (xấp xỉ 375dp tại density 420).
- Large text: `font_scale=1.3`, Login không tràn/cắt; đã hoàn nguyên về 1.0.
- Accessibility dump: Home có 20 action; Map có active node `Bản đồ` với `selected=true`.
- Logging: tap bottom nav ghi đủ `CLICK`, `SCREEN_SHOW`, `SCREEN_DRAW_BEGIN/END`.
- Không có Unicode icon bị cấm và không có metadata tắt Firebase collection.
