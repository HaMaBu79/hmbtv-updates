#Bước 1 — Tăng số version trong code:
- Mở app/build.gradle, sửa 2 dòng này, mỗi bản mới tăng versionCode lên 1:
       versionCode 13
       versionName "1.2.0"
------------------------------------------------------------------------------
Quy tắc: versionCode là số nguyên để app so sánh (12 → 13 → 14...). 
versionName là chữ hiện cho user xem. Không tăng versionCode là app không báo.
------------------------------------------------------------------------------

#Bước 2 : build apk lại và đưa lên github

#Bước 3 — Up lên GitHub Releases
  Vào github.com/HaMaBu79/hmbtv-updates → tab Releases → Draft a new release
  Choose a tag → gõ v13 → Create new tag
  Release title ghi v13 - 1.2.0
  Kéo file app-release.apk ở Bước 2 vào khung đính kèm
  Bấm Publish release

#Bước 4 — Sửa version.json cho trỏ vào bản mới
- Vào repo → mở version.json → bấm sửa (cây bút) → sửa thành:
----------------------------------------------------------------------------------------------------
{
  "versionCode": 13,
  "versionName": "1.2.0",
  "apkUrl": "https://github.com/HaMaBu79/hmbtv-updates/releases/download/v13/app-release.apk",
  "notes": "Sửa lỗi SCTV đen màn hình"
}
-----------------------------------------------------------------------------------------------------
- Bấm Commit changes. Xong.
- Mở app bản cũ (v12) lên, đợi ~5 giây là nó hiện:
      Có bản cập nhật mới Bản hiện tại: 1.1.9 / Bản mới: 1.2.0 + notes [Cập nhật] [Để sau]

- Bấm Cập nhật là nó tải APK về hiện % rồi tự nhảy sang màn hình cài đặt.

######lưu ý:
User nào đã bấm Để sau ở bản 13 thì nó nhớ, không hỏi lại bản 13 nữa. Muốn nó hỏi lại thì phải ra bản 14.
Test nhanh sau khi sửa version.json: mở link raw trên trình duyệt xem nó hiện đúng versionCode mới chưa, vì GitHub raw cache khoảng 1-2 phút.
