# EvTV — Android TV için kişisel IPTV oynatıcı

Xtream Codes hesabınla çalışan, TiviMate tarzı sade bir oynatıcı.

## Şu an neler var
- Xtream Codes ile giriş (bilgiler TV'de saklanır). Sağlayıcının M3U linkini sunucu alanına yapıştırırsan kullanıcı adı/şifre otomatik alınır.
- Solda kategoriler, sağda logolu kanal listesi
- Üstte odaktaki kanalın **şimdi / sonra** yayın akışı (15 dakikada bir tazelenir)
- Kanal listesi TV'de saklanır: uygulama anında açılır, güncel liste arka planda iner
- Favoriler: kanalda orta tuşu **basılı tut** veya **Menü** tuşuna bas
- Yayın koparsa otomatik yeniden bağlanır; `.ts` açılmazsa `.m3u8` dener (veya tersi)
- Çok büyük listeler (on binlerce kanal) belleği şişirmeden okunur
- "Hesaptan çık" kazara basılmasın diye iki kez basma ister

### Oynatıcıda kumanda tuşları
| Tuş | İşlev |
|---|---|
| Yukarı / CH+ | Sonraki kanal |
| Aşağı / CH− | Önceki kanal |
| Sol | Bir önceki izlediğin kanala dön |
| Orta tuş, Sağ veya Info | Kanal bilgisini göster (hata ekranında: tekrar dene) |
| Rakamlar | Kanal numarasıyla geçiş |
| Menü | Favoriye ekle/çıkar |
| Geri | Listeye dön |

## Bilinen sınırlar
- Bazı TV'ler AC3/E-AC3 (Dolby) sesi çözemez; o kanallarda ses gelmeyebilir. Bu, TiviMate'te de cihaza bağlıdır.
- Tam EPG ızgarası, kayıt, catch-up, VOD ve arama henüz yok.

## APK nasıl elde edilir (bilgisayar gerekmez)
1. github.com'da ücretsiz hesap aç, **New repository** ile `EvTV` adında depo oluştur.
2. **Add file → Upload files** ile bu klasördeki her şeyi yükle (`.github` klasörü dahil).
3. **Actions** sekmesinde derleme kendiliğinden başlar (~5 dk). Yeşil tik çıkınca hazır.
4. APK **Releases → latest → EvTV.apk** altında olur.

> Depo **public** ise TV'deki *Downloader* uygulamasına şu linki yazarak doğrudan indirebilirsin:
> `https://github.com/KULLANICI_ADIN/EvTV/releases/download/latest/EvTV.apk`
> Kodda hesap bilgisi yok; şifreni uygulamanın içinde girersin, o yüzden public depo güvenlidir.

## TV'ye kurulum
1. TV'de **Ayarlar → Cihaz tercihleri → Hakkında → Android TV OS yapısı** satırına 7 kez bas (geliştirici modu).
2. **Ayarlar → Uygulamalar → Güvenlik → Bilinmeyen kaynaklar** altında *Downloader*'a izin ver.
3. Google Play'den **Downloader** (AFTVnews) kur, yukarıdaki linki gir, APK'yı kur.
4. Alternatif: APK'yı telefona indir, *Send Files to TV* uygulamasıyla TV'ye gönder.

Yeni sürümler eskisinin üzerine kurulur; favoriler ve giriş bilgileri kaybolmaz.

## Bilgisayarda geliştirmek istersen
Android Studio ile bu klasörü aç → üstteki ▶ düğmesiyle TV'ye veya emülatöre kur.

## Sonraki adımlar (fikirler)
- Oynatırken sola basınca açılan kanal listesi
- Tam EPG ızgarası (XMLTV)
- Arama
- Filmler ve diziler (VOD)
- Catch-up / geri sarma
