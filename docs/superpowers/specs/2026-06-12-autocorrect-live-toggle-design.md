# Anında etkinleşen otomatik düzeltme seçenekleri — tasarım

**Tarih:** 2026-06-12 · **Dal:** `feature/autocorrect-live-toggle` · **Bayrak:** `LIVETOGGLE=1` (varsayılan açık)

> Adlandırma notu: depoda paralel yürüyen "yazarken oto-düzeltme" işi
> `scripts/macos-autocorrect/` + `apply_autocorrect` adlarını kullanıyor;
> çakışmamak için bu birim `macos-livetoggle` adını taşır.

## Sorun

"Otomatik Büyük Harf", "Baş Harfler Büyük" ve "Kelime Denetimi" onay kutuları yalnız
tercih yazar (`~/.uki/acilisDegerleri.xml` → `initValues/ToUpperCase`,
`FirstLetterUpperCase`, `SpellCheck`) ve "editor yeniden başlatılmadığı sürece aktif
olmayacaktır" diyaloğu gösterir. Dinleyicileri (`text.hN`, `text.fY`, `text.im`)
editöre takan tek yer `text.fk.run()`'dır ve yalnız editör KURULURKEN, tercih "true"
ise çalışır. Sonuç: toggle'ın etkisi bir sonraki açılışa kalır.

Ek upstream bug: toggle eylemleri (`text.dq`=To-Uppercase, `text.dA`=first Letter
Upper, `text.db`=spell-check) yeni değeri HEP şeritteki kutudan okur
(`tr.gov.uyap.system.a.b.a.a.z` statikleri `a`/`b`/`c`). Menü çubuğundaki eş
kopyadan (gui.ak statikleri) tıklanırsa şerit kutusu değişmediğinden ESKİ değer
kaydedilir.

## Çözüm mimarisi

Mevcut build-zamanı yama deseniyle yeni birim: `scripts/macos-livetoggle/` +
`build.sh` içinde `apply_livetoggle()` (apply_imgresize/apply_pasteimage deseni:
helper enjekte → patcher koş → başarısızlıkta helper'ı geri çıkar).

### 1. Helper: `macoslivetoggle/LiveToggle.java` (jar'a enjekte, jar'a karşı derlenir)

`public static void apply(String key)` — key ∈ {"ToUpperCase",
"FirstLetterUpperCase", "SpellCheck"}; EDT'de çağrılır (eylem zaten EDT'de).

1. Kalıcılaşan tercihi okur: `pki.b.l.a()` → ilgili Properties
   (`m()`/`n()`/`l()`) → `getProperty(key)` → `on`. Tercih = tek doğruluk
   kaynağı; eylem tercihi zaten yazmış durumda (insertAfter'dayız).
2. Tüm pencereleri tarar (`Frame.getFrames()` + sahipli pencereler, bileşen
   ağacı özyinelemeli) → `text.fi` örnekleri.
3. Her editörde, key'e karşılık gelen dinleyici sınıfı (`hN`/`fY`/`im`):
   - `on` ve o sınıftan dinleyici yoksa → ekle (`addKeyListener(new hN())` …).
     SpellCheck için önce `im.a()` (statik Zemberek getter — TEMBEL YÜKLER);
     null dönerse ekleme (orijinal `fk` davranışıyla aynı, sessiz).
   - `!on` → o sınıftan TÜM dinleyicileri sök.
4. Onay kutusu kopyalarını tercihe eşitler: `z.a/b/c` (public static) doğrudan,
   `gui.ak`'taki menü statikleri reflection ile (erişilemezse sessiz geç).
5. Tamamı `try/catch (Throwable)` içinde — toggle hiçbir koşulda eylemi/EDT'yi
   düşürmez. Adım 3 pencere başına da try'lıdır (tek bozuk pencere kalanı
   engellemesin).

`fk`'ya DOKUNULMAZ: yeni açılan belgeler tercihi zaten doğru okuyarak kurulur.

### 2. Patcher: `LiveTogglePatch.java` (Javassist, build'de koşar)

Üç sınıfın (`dq`, `dA`, `db`) `a(Ljava/awt/event/ActionEvent;)V` metoduna:

- **insertBefore** — kaynak senkronu (menü-yolu bug düzeltmesi):
  `if ($1.getSource() instanceof javax.swing.JCheckBoxMenuItem) {
     tr.gov.uyap.system.a.b.a.a.z.<alan>.setSelected(
        ((javax.swing.JCheckBoxMenuItem)$1.getSource()).isSelected()); }`
  (alan: dq→`a`, dA→`b`, db→`c`; z alanları ve hedef kutu null olabilir —
  null guard'lı yaz). Böylece orijinal gövde hangi kutudan tıklanırsa
  tıklansın YENİ değeri okur.
- **ExprEditor (MethodCall)** — `gui.kP.b(...)` restart diyaloğu çağrısı →
  `$_ = 0;` (artık yanlış bilgi; tamamen kaldırılır).
- **insertAfter** — `macoslivetoggle.LiveToggle.apply("<key>");`

Javassist kuralları: gövde string'lerinde `//` yorum yok; sınıf başına tek
writeClass (frozen tuzağı). Patcher üç sınıftan herhangi birini yamalayamazsa
sıfır-dışı çıkar → build helper'ı geri çıkarır, uyarı basar, stok davranış kalır.

### 3. build.sh entegrasyonu

- `LIVETOGGLE_SRC="$SCRIPT_DIR/macos-livetoggle"` + `LIVETOGGLE="${LIVETOGGLE:-1}"`.
- `apply_livetoggle()` patch_jar zincirine `apply_imgresize`'dan sonra eklenir.
- İdempotans işareti: jar'da `macoslivetoggle/LiveToggle.class` varlığı.

## Veri akışı

Kutu tıklanır → [insertBefore: şerit kutusu kaynağa eşitlenir] → orijinal gövde
tercihi yazar → [diyalog no-op] → [insertAfter: `LiveToggle.apply(key)`] →
tercih okunur → açık editörlerde dinleyici ekle/sök → kutu kopyaları eşitlenir.

## Bilinen sınırlar

- Kelime Denetimi KAPATILINCA önceden çizilmiş yazım işaretleri anında
  silinmez; yeni denetim durur, işaretler belge yeniden açılınca temizlenir.
- Dikteyle giren metne bu özellikler yine uygulanmaz (DictationFix takası —
  ayrı iş; bkz. bellek `macos-dictation-fix`).

## Test

1. **Build doğrulaması:** patcher üç metodu da yamaladığını raporlar; tam döngü
   `download → patch → lookagent → package`.
2. **Attach probe (build sonrası, GUI'siz):** canlı süreçte dinleyici listesi
   toggle öncesi/sonrası karşılaştırılır; `LiveToggle.apply` doğrudan çağrılıp
   ekleme/sökme ve Zemberek tembel yükleme doğrulanır. Sentetik `dispatchEvent`
   KeyEvent TUZAĞINA girilmez (KeyboardFocusManager yutar) — dinleyici mantığı
   gerekirse `hN.keyTyped(ev)` doğrudan çağrısıyla test edilir.
3. **Elle GUI testi (kullanıcı):** taze başlat (pkill → doğrudan binary) →
   kutuyu işaretle → diyalog ÇIKMAMALI → restart'sız yazınca davranış değişmeli;
   menüdeki eş kopyadan da toggle denenir.
