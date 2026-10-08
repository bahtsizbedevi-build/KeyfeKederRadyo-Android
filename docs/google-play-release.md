# Keyfe Keder Radyo — Google Play yayın kontrol listesi

## Teknik

- [x] `applicationId`: `com.keyfekederradyo.android`
- [x] `compileSdk`: 36
- [x] `targetSdk`: 36
- [x] AAB (`bundleRelease`) workflow'u hazır
- [ ] Play upload keystore oluşturulmalı ve GitHub Actions secrets olarak tanımlanmalı
- [ ] Signed AAB ile Play Console Internal Testing yüklemesi yapılmalı
- [ ] Fiziksel cihazlarda son smoke test
- [ ] Android 16 / Android 15 / Android 14 cihazlarında kontrol

## Play App Signing

Yeni uygulama için Google Play App Signing kullanılmalı. Upload key ayrı tutulmalı; özel anahtar GitHub repository'sine kesinlikle eklenmemeli.

Gerekli GitHub Actions secrets:

- `PLAY_KEYSTORE_BASE64`
- `PLAY_KEYSTORE_PASSWORD`
- `PLAY_KEY_ALIAS`
- `PLAY_KEY_PASSWORD`

`PLAY_KEYSTORE_BASE64` değeri, keystore dosyasının (`.jks` / `.keystore`) tek satır base64 hâlidir. Windows PowerShell'de:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\yol\upload-keystore.jks")) | Set-Clipboard
```

Panodaki metnin tamamını secret değeri olarak yapıştırın. Keystore dosyasını veya bu metni repoya eklemeyin.

## Mağaza bilgileri

**Uygulama adı:** Keyfe Keder Radyo

**Kısa açıklama önerisi:**
> Keyfine göre radyonu bul, canlı müziği sade ve keyifli bir deneyimle dinle.

**Kategori:** Müzik ve Ses

**İçerik:** Uygulama kullanıcı hesabı oluşturmaz; favoriler ve tercihler cihazda tutulur. Radyo yayınları üçüncü taraf yayın sağlayıcılarından gelir.

## Mağaza görselleri

Google Play için:

- 512x512 PNG uygulama simgesi
- 1024x500 feature graphic
- En az 2 gerçek uygulama ekran görüntüsü
- Önerilen kalite için 4 adet 1080x1920 dikey ekran görüntüsü

Ekran görüntüleri gerçek uygulama arayüzünden alınmalı; yanıltıcı reklam metinleri ve üçüncü taraf marka/logoları kullanılmamalı.

## Gizlilik

`docs/privacy-policy.html` Play Console'da herkese açık bir URL üzerinden sunulmalı ve Data safety formu uygulamanın gerçek veri davranışına göre doldurulmalı.

## Yeni kişisel geliştirici hesabıysa

Google Play'in mevcut kuralına göre, 13 Kasım 2023'ten sonra oluşturulan yeni kişisel geliştirici hesaplarında üretim erişimi için en az 12 test kullanıcısının kapalı teste kesintisiz 14 gün katılması gerekir.
