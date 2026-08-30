# DoseWear — kurulum, test ve pil notları

Standalone Wear OS ilaç/takviye takip uygulaması. Telefon gerekmez, veri cihazda kalır
(Room/SQLite), bulut senkronu yok.

---

## 1. Mimari özeti

### Veri (Room, 4 tablo)

| Tablo | Ne tutar |
|---|---|
| `supplements` | **Stok kartı**: ad, doz bilgisi, birim, elde kalan stok, uyarı eşiği (varsayılan 5), bir kutu = kaç adet, renk, aktif/pasif |
| `reminders` | **Hatırlatıcı**: saat, gün maskesi, açık/kapalı, erteleme süresi, rastgele sapma, en fazla erteleme sayısı |
| `reminder_items` | Hatırlatıcı ↔ takviye bağlantısı + miktar (**bir hatırlatıcıda N ilaç**) |
| `dose_logs` | **Onay mekanizması ve geçmiş**: planlanan zaman, işlem zamanı, durum (PENDING/TAKEN/SNOOZED/SKIPPED/MISSED), kaç kez ertelendi, grup anahtarı |

Akış tam istediğin sırada: **önce stok kartı → sonra hatırlatıcı (listeden takviye seçilir) →
onaylayınca stok düşer → eşiğe inince "satın aldım" uyarısı**.

### Zamanlama

* `AlarmManager` — WorkManager **kullanılmıyor**.
* Varsayılan mod `setAlarmClock()`: sistemin en yüksek öncelikli alarm türü, Doze'dan ve OEM
  arka plan kısıtlarından muaf. Ayarlar'dan kapatırsan `setExactAndAllowWhileIdle()`'a düşer.
* `BootReceiver` — `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`,
  `TIME_SET`, `TIMEZONE_CHANGED` → tüm hatırlatıcılar **ve bekleyen ertelemeler** yeniden kurulur.
* Uygulama her açıldığında da alarm zinciri doğrulanır (OEM alarmı sessizce düşürürse ucuz kurtarma).
* Alarm tetiklendiğinde bir sonraki halka kurulur → zincir kopmaz.

### Pil

Hiç foreground service yok, hiç periyodik iş yok, hiç polling yok. Sadece gerektiği anda
kurulan tekil alarmlar. Tile ve complication yalnızca veri değiştiğinde dürtülür
(`Surfaces.refreshAll`), kendiliğinden en fazla 30 dakikada bir tazelenir.

### Aynı anda birden fazla ilaç (madde 2) — **iki çözüm birden**

1. **Gruplu hatırlatıcı**: bir hatırlatıcıya istediğin kadar takviye eklersin. Tek bildirim gelir,
   tam ekranda hepsi listelenir, **her birini ayrı onaylarsın**. "Hepsini aldım" kısayolu da var.
2. **Çakışma önleyici erteleme**: erteleme süresi arayüzden ayarlanır (hatırlatıcı bazında,
   varsayılan 10 dk). Üstüne **0–jitter dakika rastgele sapma** eklenir ve sapma **her doz için
   ayrı** hesaplanır. 3 ilacın 2'sini ertelersen ikisi aynı dakikaya düşmez, birbirini ezmez.
   Sapmayı 0 yaparsan kapanır.

### Onaya teşvik (madde 4)

* Bildirim `setOngoing(true)` — kaydırarak silinemez.
* `setFullScreenIntent` — ekran uyanır, kilit ekranının üstünde onay ekranı açılır.
* Artan titreşim paterni (`USAGE_ALARM`).
* Onaylanmazsa varsayılan 5 dakikada bir ısrar (titreşim + "❗ Hâlâ onaylanmadı"), 3 ısrardan
  sonra doz `MISSED` yazılır. Aralık ve tekrar sayısı Ayarlar'dan.
* Ana ekranda günlük `alınan/planlanan` çubuğu ve **üst üste gün serisi** (🔥) — pozitif teşvik.

---

## 2. Android Studio'da ilk derleme

1. **Gradle sync** yap. Yeni eklenen bağımlılıklar: Room + KSP, Wear Compose Material,
   Wear Compose Navigation, wear-input, kotlinx-coroutines-guava.
2. Eğer KSP satırında hata alırsan (`ksp` sürümü Kotlin sürümüyle birebir eşleşmek zorunda),
   `gradle/libs.versions.toml` içinde:
   ```toml
   kotlin = "2.2.10"
   ksp    = "2.2.10-2.0.2"   # <kotlin sürümü>-<ksp sürümü>
   ```
   AGP 9 kendi Kotlin sürümünü getiriyorsa hata mesajı beklenen sürümü yazar; `ksp` satırının
   ön ekini o sürüme çevir.
3. Eğer `androidx.room:room-*:2.7.2` bulunamazsa daha güncel bir 2.x sürümüne çek.
4. **Temizlik**: `com/example/dosewear/presentation/DepthProbe.kt`,
   `.../presentation/theme/DepthProbe.kt` ve `com/dosewear/` klasörünün tamamı kurulum
   artığıdır — Android Studio'dan sil.

## 3. Saate kurma (ADB, Play Store yok)

```bash
# Saatte: Ayarlar → Sistem → Hakkında → Yapı numarasına 7 kez dokun
# Ayarlar → Geliştirici seçenekleri → ADB hata ayıklama + Kablosuz hata ayıklama açık

adb pair <IP>:<PORT>          # saatteki eşleştirme kodunu gir
adb connect <IP>:<PORT>
adb devices                   # cihaz görünmeli

./gradlew installDebug
# veya
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Kurulumdan hemen sonra yapılacaklar

Uygulamayı aç → **Ayarlar** ekranı. Üç satır da ✅ olmalı:

| Satır | Ne yapar |
|---|---|
| Kesin alarm izni | `SCHEDULE_EXACT_ALARM` — `USE_EXACT_ALARM` sayesinde genelde otomatik verilir |
| **Pil optimizasyonu** | Uygulamayı Doze beyaz listesine alır — **en kritik madde** |
| Tam ekran uyarı izni | Android 14+ `USE_FULL_SCREEN_INTENT` |

### Xiaomi HyperOS ek adımları (bunlar olmadan gece alarmı kaçabilir)

HyperOS, AOSP'nin üstüne kendi arka plan katmanını koyuyor; Ayarlar ekranındaki muafiyet
tek başına yetmeyebilir:

1. **Ayarlar → Uygulamalar → DoseWear → Pil tasarrufu → "Kısıtlama yok"**
2. **Otomatik başlatma / Autostart** → DoseWear açık
3. **Son uygulamalar ekranında DoseWear kartını kilitle** (aşağı çek → kilit simgesi)
4. Bazı HyperOS sürümlerinde ayrıca **Güvenlik uygulaması → Pil → Uygulama pil tasarrufu →
   DoseWear → Kısıtlama yok**

## 5. Doğrulama testleri (madde 7)

Ayarlar → Teşhis bölümünde her alarmın **planlanan vs. gerçekleşen** zamanı ve şimdiye
kadarki **en kötü gecikme** kaydediliyor. Sırayla:

1. **Test alarmı kur (2 dk)** — uygulama açıkken temel doğrulama. Bildirim gelmeli,
   "✅ Zamanında" yazmalı.
2. Aynısını **saati bileğinden çıkarıp ekranı kapatarak** tekrarla (hafif Doze).
3. **Gece testi kur (8 saat)** — saati şarjdan çıkar, gece bırak. Sabah:
   * bildirim geldi mi?
   * Ayarlar → Teşhis → gecikme kaç saniye?
   * "En kötü gecikme" 2 dakikanın üstündeyse HyperOS alarmı geciktiriyor demektir →
     4. bölümdeki ek adımları gözden geçir.
4. **Reboot testi**: saati kapat-aç, sonra Ayarlar → Teşhis'te "Alarmları yeniden kur"a
   dokunmadan bir sonraki dozun geldiğini doğrula (BootReceiver çalışmış olmalı).

> `setExactAndAllowWhileIdle` Doze'da dakika hassasiyeti garanti etmez ve OEM katmanları
> sürpriz yapabilir. Bu yüzden varsayılan `setAlarmClock` — sistem bunu gerçek bir çalar saat
> gibi ele alır. Yan etkisi: saatte küçük bir alarm simgesi görünür. İstemezsen Ayarlar'dan
> "Yüksek öncelik"i kapatabilirsin, ama gecikme riskini kabul etmiş olursun.

## 6. Kullanım akışı

1. **Takviyelerim → Yeni takviye**: ad, birim, elimdeki stok, uyarı eşiği (varsayılan 5),
   bir kutu kaç adet, renk. Kaydet.
2. **Hatırlatıcılar → Yeni hatırlatıcı**: saat/dakika, günler, listeden bir veya birden fazla
   takviye seç (her biri için miktar), erteleme ayarları. Kaydet → alarm kurulur.
3. Zamanı gelince: ekran uyanır, her ilaç için **✓ Aldım / ⏰ Ertele / ⤼ Atla**.
   Bildirimden de onaylayabilirsin.
4. **Aldım** → `dose_logs`'a yazılır **ve stok düşer**. Stok eşiğe inince "Satın aldım (+N)"
   butonlu uyarı gelir; dokununca stok bir kutu kadar artar ve uyarı bayrağı sıfırlanır.
5. **Geçmiş**: gün gün alınan/kaçırılan, kaç kez ertelendiği.

## 7. Ekranlar

`Ana ekran` (bugün + sıradaki + bekleyenler) · `Takviyelerim` · `Takviye detay/stok` ·
`Takviye ekle/düzenle` · `Hatırlatıcılar` · `Hatırlatıcı ekle/düzenle` · `Geçmiş` · `Ayarlar` ·
`Tam ekran doz onayı` + saat kartı (Tile) ve saat yüzü complication'ı.

Metin girişi Wear'in standart RemoteInput ekranını açar (klavye / el yazısı / sesli giriş).
Sayısal alanların hepsi ± düğmeli — saatte klavye gerekmez.
