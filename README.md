# Kumanda

Xiaomi Mi Box / Android TV için telefon kumandası (Android uygulaması).

Wi‑Fi üzerinden, Google TV uygulamasının kullandığı **Android TV Remote v2** protokolüyle çalışır.
TV'de ADB / geliştirici modu açmaya gerek yoktur.

## Kurulum

1. [Releases](../../releases) sayfasından en son `Kumanda.apk` dosyasını telefona indirip kurun.
2. Telefon ve Mi Box **aynı Wi‑Fi ağında** olsun.
3. Uygulamayı açın, listeden TV'yi seçin (çıkmazsa "IP adresi gir").
4. "Eşleştir"e basın, TV ekranında görünen 6 haneli kodu girin. Bu bir kez yapılır.

## Özellikler

- Yön tuşları + OK (basılı tutunca tekrarlar), Geri, Ana Ekran, Menü
- Ses +/−, Sessiz, Güç, Ayarlar, Asistan
- Medya tuşları (oynat/duraklat, ileri/geri sar, önceki/sonraki)
- YouTube, Netflix, Prime Video, Disney+ kısayolları
- Telefonun ses tuşları TV'nin sesini ayarlar
- Ağdaki cihazları otomatik bulma, otomatik yeniden bağlanma

## Derleme

```
./gradlew assembleRelease
```

Her push'ta GitHub Actions APK'yı derler ve Releases'e ekler.
