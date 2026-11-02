# Müzik Kırp

Android için **ücretsiz, reklamsız ve çevrimdışı** müzik kırpma/kesme aracı.

- Şarkı seç, başlangıç ve bitiş noktasını kaydırıcılarla ayarla
- Kırpmadan önce seçili aralığı dinle (önizleme)
- Sonucu `Müzik/MuzikKirp` klasörüne `.m4a` (AAC) olarak kaydet
- İnternet izni yok, reklam yok, izleme yok, dış kütüphane yok

Her ses formatını (mp3, m4a, wav, ogg, flac…) Android'in kendi codec'leri çözebildiği sürece destekler.

## Kurulum (APK)

1. Bu reponun **Actions** sekmesinde en son başarılı "APK derle" çalışmasını aç.
2. En alttaki **Artifacts** bölümünden `muzik-kirp-apk` dosyasını indir ve zip'ten çıkar.
3. `app-debug.apk` dosyasını telefona gönder ve aç. "Bilinmeyen kaynaklardan yükleme" izni istenirse onay ver.

Gereksinim: Android 10 (API 29) veya üstü.

## Kendin derlemek istersen

JDK 17 ve Android SDK kurulu olmalı:

```bash
gradle assembleDebug
```

APK `app/build/outputs/apk/debug/app-debug.apk` konumunda oluşur.

## Nasıl çalışır?

Seçilen aralık Android'in `MediaCodec` ile çözülür, geçici PCM dosyasına yazılır, sonra AAC olarak kodlanıp `MediaMuxer` ile `.m4a` dosyasına yazılır. Kırpma sırasında bir kez yeniden kodlama yapıldığı için çok küçük bir kalite kaybı olabilir (192 kbps AAC).

## Yapılacaklar

- [ ] Kayıpsız kırpma (AAC/M4A girdiler için yeniden kodlamadan)
- [ ] Çıktı biçimi seçimi
- [ ] Fade in / fade out
- [ ] Uygulama simgesi
- [ ] Dalga formu gösterimi

## Katkı

Hata bildirimleri ve pull request'ler açıktır. Büyük değişikliklerden önce bir issue açman iyi olur.

## Lisans

[MIT](LICENSE)
