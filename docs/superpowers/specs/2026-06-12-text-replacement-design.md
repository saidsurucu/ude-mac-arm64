# macOS Metin Değiştirme (Text Replacement) — Tasarım

**Tarih:** 2026-06-12
**Dal:** `feature/text-replacement`
**Durum:** Onaylandı (implementasyon bekliyor)

## Sorun (kullanıcı geri bildirimi)

macOS/iOS'un sistem geneli "Metin Değiştirme" özelliği (Sistem Ayarları →
Klavye → Metin Değiştirmeleri; ör. "İşadresim" yazınca tam adresin gelmesi)
Safari/Word/Notlar'da çalışırken **UDE'de çalışmıyor**. Kullanıcı kısayolla
hazır metin yazdırmak istiyor.

## Bağlam ve ön bulgular (makinede doğrulandı)

- macOS bu özelliği native metin alanlarına Cocoa metin-denetim katmanından
  (NSTextCheckingController) uygular; **Java/Swing bu kanala bağlanmaz** —
  hiçbir Java uygulamasında çalışmaz. Çözüm uygulama-içi genişletici.
- Veri kaynağı erişilebilir (ek izin gerekmez, ikisi de bu makinede test
  edildi):
  - **Birincil:** `~/Library/KeyboardServices/TextReplacements.db` (SQLite,
    WAL modunda). Tablo `ZTEXTREPLACEMENTENTRY`, kolonlar `ZSHORTCUT`,
    `ZPHRASE`, `ZWASDELETED` (0 = aktif). iCloud ile senkron gelen iPhone
    kayıtları da burada. WAL nedeniyle db+`-wal` dosyası geçici dizine
    kopyalanıp `sqlite3` CLI ile okunur (canlı dosyada shm kilidi riskine
    girilmez).
  - **Yedek:** `defaults export -globalDomain -` → XML plist içindeki
    `NSUserDictionaryReplacementItems` dizisi (`replace`/`with`/`on`).
- Mevcut altyapı birebir uygun: `macos-textkeys` javaagent ailesi
  (DictationFix/MacOptionChars) zaten tüm klavye/odak olaylarını görüyor;
  yeni sınıf aynı jar'a eklenir, vendor jar'a Javassist yaması gerekmez.

## Tasarım

Yeni sınıf `scripts/macos-textkeys/macostextkeys/TextReplace.java`;
`MacTextKeys.install()` çağırır.

### Veri okuma (`ReplacementStore`)

- İlk kullanım anında liste okunur ve cache'lenir; pencere her
  aktifleştiğinde (WindowEvent.WINDOW_ACTIVATED, AWTEventListener) 30 sn
  kısıtlamayla yeniden okunur → Mac'te yeni eklenen kısayol UDE'yi yeniden
  başlatmadan gelir.
- Okuma sırası: SQLite kopyası (`sqlite3` 2 sn timeout, `-separator` ile
  güvenli ayrıştırma; çok satırlı phrase'ler için `quote()` ya da ayrı
  sorgu deseni) → başarısızsa `defaults export` plist ayrıştırma (Java XML)
  → o da başarısızsa boş liste (özellik sessizce no-op; uygulama davranışı
  bugünkü gibi kalır).
- Geçici dosyalar okuma biter bitmez silinir.

### Tetikleme ve değiştirme

- `Toolkit.addAWTEventListener(KEY_EVENT_MASK)`: KEY_TYPED + karakter
  **sonlandırıcı** ise (boşluk, Enter, Tab ve noktalama `. , ; : ! ? ) " '`)
  ve kaynak düzenlenebilir `JTextComponent` ise → `invokeLater` (UDE'nin
  kendi keyTyped zinciri — otomatik büyük harf vb. — işini bitirdikten
  sonra çalışmak için):
  1. Caret konumundan geriye taranır: sonlandırıcı karakterin hemen solundaki
     sözcük (önceki boşluk/sonlandırıcıya ya da satır başına kadar) çıkarılır.
  2. Sözcük kısayol tablosuyla eşleştirilir:
     - Birebir eşleşme (büyük/küçük harf duyarlı, Türkçe karakterler dahil).
     - **Büyük harf uyarlaması (macOS davranışı + UDE otomatik büyük harf
       uyumu):** kısayol tamamen küçük harfse ve yazılan sözcük yalnız ilk
       harfi büyük hâliyse eşleşir; karşılık metnin de ilk harfi
       büyütülerek yazılır (tr-TR locale ile — i→İ doğru). Böylece UDE'nin
       "Otomatik Büyük Harf" özelliği kısayolun ilk harfini büyütse bile
       değiştirme çalışır.
  3. Eşleşmede sözcük aralığı seçilip `replaceSelection(karşılık)` ile
     değiştirilir; sonlandırıcı karakter korunur, caret değiştirilen metnin
     (sonlandırıcı dahil) sonunda kalır.
- Değiştirme normal belge düzenlemesi olduğundan **⌘Z ile geri alınır**
  (macOS'taki gibi).
- Tüm gövde try/catch: istisna loglanır, hiçbir şey değiştirilmez (en kötü
  durum = statüko).

### Kapsam ve bayraklar

- Tüm düzenlenebilir `JTextComponent`'lerde aktif (belge editörü + Bul/
  Değiştir vb. diyalog kutuları) — kullanıcı kararı: "her yerde".
- Build bayrağı `TEXTREPLACE=1` (varsayılan açık); `TEXTREPLACE=0` build'i
  jpackage java-options'a `-Dmacostextreplace.off=1` ekler,
  `MacTextKeys.install()` bu system property doluysa TextReplace'i hiç
  kurmaz. Çalışma anında da aynı property ile kapatılabilir.
- Teşhis: `UDE_TRLOG=1` ortam değişkeni → `~/Library/Logs/ude-textreplace.txt`
  (System.err uygulama tarafından yutuluyor; `UDE_DICTLOG` deseni).

## Bilinen sınırlar

- **Dikteyle giren metne uygulanmaz:** DictationFix sonrası IME commit'i
  keyTyped üretmez (mevcut takas; otomatik büyük harf de aynı durumda).
- Karşılık metinler düz metindir (macOS'un kendi özelliği de düz metin
  saklar); biçimlendirme caret'teki mevcut nitelikleri alır.
- Kısayol içinde sonlandırıcı karakter (boşluk, nokta vb.) barındıran
  tanımlar desteklenmez (geri tarama sözcük sınırında durur). macOS'ta da
  kısayollar tek sözcüktür; pratik kayıp yok.

## Test

1. **Birim test:** eşleştirici + geri tarama saf Java
   (`tests/TextReplaceMatchTest.java`, javac+java elle çalıştırma deseni):
   birebir eşleşme, ilk-harf-büyük uyarlaması (tr-TR İ/i), eşleşmeme,
   satır başı/belge başı sınırı, çok satırlı karşılık metni.
2. **Veri okuma testi:** gerçek `TextReplacements.db` kopyasına karşı
   ReplacementStore çıktısı `defaults read` çıktısıyla karşılaştırılır.
3. **Canlı doğrulama:** dynamic attach probe ile çalışan UDE'de belgeye
   programatik "mrb"+boşluk yazılıp belge içeriği kontrol edilir (sentetik
   KeyEvent dispatch güvenilmez — doğrudan `keyTyped(ev)` çağrısı ya da
   document insert + TextReplace iç metodunun çağrılması).
4. **Elle son test (repo sahibi):** gerçek yazımla editörde + Bul/Değiştir
   kutusunda kısayol denemesi, ⌘Z geri alma, Mac tarafında yeni kısayol
   ekleyip UDE'ye restart'sız gelmesi.

## Kapsam dışı

- UDE içinden sistem listesine kısayol ekleme/düzenleme (liste yönetimi
  Sistem Ayarları'nda kalır).
- Apple'ın native eşleştirme motoruna JNI köprüsü (NSSpellChecker) —
  maliyet/fayda gereksiz.
- UDE'ye özel ayrı kısayol yöneticisi UI'ı.
