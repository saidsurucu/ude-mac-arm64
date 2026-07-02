# Satır Aralığı Dropdown'u (LINESPACING=1) — Tasarım

**Tarih:** 2026-07-02
**Durum:** REVİZE EDİLDİ (aşağıya bak) — orijinal ayrı-buton tasarımı geri alındı

## REVİZYON (2026-07-02/2, kullanıcı onaylı): native popup'a 1.5 ekle

Task 4 canlı doğrulaması iki şey ortaya çıkardı:

1. **UDE'nin Giriş > Paragraf bandında ZATEN native bir satır-aralığı
   popup'ı var**: `tr.gov.uyap.system.a.b.a.a.D` (JCommandButton) →
   `…a.a.M extends JCommandPopupMenu`. Öğeler string literal **"1.0",
   "1.15", "2.0", "2.5", "3.0"** + ayraç + "Paragraf Özellikleri"
   (paragraph-action). **"1.5" satıcı tarafından unutulmuş** — kullanıcının
   orijinal şikâyetinin gerçek kökü bu. (İlk keşif taraması `tr.com.havelsan`
   ağacına bakmıştı; kontrol `tr.gov.uyap` ağacında.)
   Her öğenin dinleyicisi (N=1.0, O=1.15, P=2.0, Q=2.5, R=3.0) tek satır:
   `M.a(this.a)` (M→D erişimcisi) → **`D.a(float görünenDeğer)`** — satıcının
   kendi uygulama yolu.
2. Bizim ayrı butonumuz `AbstractCommandButton.setToolTipText`'in koşulsuz
   `UnsupportedOperationException("Use rich tooltip APIs")` fırlatması
   nedeniyle hiç eklenmiyordu (plan kodu hatası); düzeltilse bile bantta
   İKİ satır-aralığı kontrolü olacaktı.

**Yeni tasarım (kullanıcı seçimi):** ayrı buton İPTAL (Task 1-3 commit'leri
geri alınır); yerine **build-zamanı Javassist yaması** `LineSpacingPatch`:
`M` kurucusunda 3. `addMenuButton` ("2.0" öğesi) çağrısından önce
`JCommandMenuButton("1.5", null)` + dinleyici eklenir; dinleyici, "1.15"
dinleyicisi `O`'nun `getAndRename` kopyası (`LS15`, aynı pakette) olup
gövdesi `M.a(this.a).a(1.5f)` — satıcının kendi uygulama yolu (undo/seçim
mantığı dahil) aynen kullanılır. Native kontrolün kendi simgesi korunur
(kullanıcının simge isteği kendiliğinden karşılanır). Görünüm native ile
tutarlı: nokta biçimi "1.5". `LINESPACING=1` bayrağı bu yamayı gate'ler.
UDF tarafı değişmedi (LineSpacing float, değer−1 — D.a satıcı yolu bunu
kendisi yapar). Aşağıdaki orijinal tasarım TARİHSEL bağlamdır.

---

## Problem

UDE'de satır aralığını değiştirmenin tek yolu Paragraf diyaloğundaki
"Satır Aralığı (satır):" serbest metin alanı. Word'deki gibi tek tıkla
1,5 (veya 1,15 / 2,0 …) seçilebilecek bir şerit kontrolü yok; kullanıcılar
özelliğin hiç olmadığını sanıyor. `bv` sabitlerindeki "Satır Aralığı" /
"Satırlar arasındaki mesafeyi ayarlar." metinleri jar'da kullanılmayan artık.

## UDF format gerçeği (değişiklik YOK)

- content.xml `<paragraph>` özniteliği `LineSpacing` **float** olarak zaten
  var: UDE parser'ları (`common.d.G`, `common.d.B`) `Attribute.getFloatValue()`
  → `StyleConstants.setLineSpacing`.
- Kodlama: **UDF değeri = görünen aralık − 1** (1,0→`0.0`, 1,15→`0.15`,
  1,5→`0.5`, 2,0→`1.0`). Paragraf diyaloğu (`gui.cM`) aynı dönüşümü yapar:
  `Float.parseFloat(text.replace(',','.'))` → `setLineSpacing(v==0?0:v-1)`.
- Fiili render kanıtı: udf-converter-go `LineSpacing="%.2f"` yazar
  (`udf_builder.go:293`; Word 240-birim → `(val/240)-1`, 0..3 kıskaç) ve bu
  belgeler UDE'de doğru görünür. Yamasız UDE / UYAP web aynı dosyaları açar.

## Çözüm

Word-tarzı satır-aralığı dropdown'u: Giriş sekmesi "Paragraf" bandına
Flamingo `JCommandButton` (POPUP_ONLY) + popup menüde **1,0 · 1,15 · 1,5 ·
2,0 · 2,5 · 3,0** seçenekleri. Runtime javaagent ile, jar'a Javassist yaması
gerekmeden.

### Yerleşim ve bileşen

- Yeni paket: `scripts/macos-textkeys/macoslinespacing/LineSpacingMenu.java`
  (textkeys agent jar'ına derlenir; tamamı yansıma, app-cp'siz).
- Kurulum: `MacTextKeys.install()` →
  `Class.forName("macoslinespacing.LineSpacingMenu")` üzerinden yansımayla
  `install()` (doğrudan referans YOK). Bayrak `LINESPACING=1` varsayılan;
  `LINESPACING=0`'da build.sh `macoslinespacing` kaynaklarını derleme/jar
  dışı bırakır, `Class.forName` ClassNotFoundException'ı sessizce yutulur —
  agent geri kalanı etkilenmez.
- Şerit ekleme **ribbon MODELİNDEN** (bileşen ağacı değil — darkpage dersi):
  FOCUS_GAINED'de idempotan `ensureRibbon` (footnote deseni):
  tasks → başlığı "Giriş" olan task → bands → başlığı "Paragraf" olan band →
  `band.addCommandButton(new JCommandButton("Satır Aralığı", ikon), MEDIUM)`.
  - TUZAK: komut-buton bandlarına `JRibbonComponent` eklemek
    `UnsupportedOperationException: mixing` fırlatır → NATIVE JCommandButton
    şart (footnote dersi).
  - Band bulunamazsa sessiz no-op + log.
- İkon: `java.lang.reflect.Proxy` ile Flamingo `ResizableIcon` — paint anında
  vektör çizim (satır çizgileri + iki yönlü ok, Word glyph'i), her DPI'de
  keskin, koyu modda mod-duyarlı renk (footnote ikon deseni).

### Popup davranışı

- `JCommandPopupMenu` + 6 `JCommandMenuButton` (etiketler: "1,0", "1,15",
  "1,5", "2,0", "2,5", "3,0" — Türkçe virgül).
- Popup açılırken imlecin paragrafından `StyleConstants.getLineSpacing()+1`
  okunur; ±0.01 toleransla eşleşen öğe vurgulanır (işaret/bold).
- Tıklanınca uygula ve popup kapansın; odak editöre dönsün
  (`requestFocusInWindow` — renk modu combo'sundaki odak-kaçışı dersi).

### Uygulama mantığı

- Hedef: odaklı editör (`text.hj` türevi; aktif pencerede ara — ModeSwitch
  `restoreEditorFocus` deseni).
- Aralık: seçim varsa `[selStart, selEnd]`, yoksa imlecin bulunduğu paragraf.
- `SimpleAttributeSet attrs; StyleConstants.setLineSpacing(attrs, v−1f);`
  `doc.setParagraphAttributes(start, length, attrs, false)` —
  **replace=false** (merge; diğer paragraf öznitelikleri korunur —
  PASTERICH'teki replace=true gereksiniminin tersi, burada merge doğru).
- 1,0 seçimi `LineSpacing=0.0` yazar (özniteliği silmek yerine — diyalog
  davranışıyla ve udf-converter-go çıktısıyla tutarlı).
- Caret'e dokunma YOK (`moveDot`/`setCaretPosition` yasak — bilinen NPE
  zinciri); `setParagraphAttributes` tek mutasyon.

### Hata durumu / teşhis

- Editör/band bulunamazsa sessiz no-op; agent asla uygulamayı düşürmez
  (her şey try/catch).
- `UDE_LSLOG=1` → `~/Library/Logs/ude-linespacing.txt` (kurulum, band bulma,
  uygulanan değer+aralık, hatalar). System.err yutulur — dosyaya logla.

### Build entegrasyonu

- `build.sh` `textkeys()` adımı `macoslinespacing/*.java`'yı derler; **jar
  paketleme satırına `macoslinespacing` eklenmeli** (macosfootnote'ta yaşanan
  NoClassDefFoundError → JVM açılışta düşer tuzağı).
- Bayrak: `LINESPACING=1` varsayılan; `SKIN`'den bağımsız (textkeys agent'ı).
- Tam sıra: `download && patch && lookagent && textkeys && package && sign`
  (textkeys atlanırsa agent jar bayat kalır — bilinen tuzak).

## Test

- **Headless:** `tests/LineSpacingApplyTest.java` (javac+java elle,
  DefaultStyledDocument):
  1. Tek paragrafta 1,5 → getLineSpacing==0.5.
  2. Çok paragraflı seçimde tüm paragraflara uygulanır.
  3. Diğer öznitelikler (Alignment, girinti, liste anahtarları) korunur
     (replace=false doğrulaması).
  4. 1,0 seçimi mevcut 0.5'i 0.0'a indirir.
- **Canlı (attach-probe, DictSim deseni):** gerçek editörde 1,5 uygula →
  `modelToView` ardışık satır y-delta'sının ~1,5× büyüdüğünü ölç; kaydet →
  content.xml'de `LineSpacing="0.5"` gör; yeniden aç → korunuyor.
- **Görsel:** yalnız UDE penceresi `screencapture -l<winID>` (tam ekran
  YASAK); Giriş sekmesinde butonun görünümü + popup.
- GUI son doğrulaması kullanıcıya bırakılır (elle test tercihi).

## Kapsam dışı (YAGNI)

- ⌥/⌘ klavye kısayolları (kullanıcı dropdown'u seçti; sonra eklenebilir).
- "Diğer…" öğesi / özel değer girişi (Paragraf diyaloğu zaten var).
- Paragraf öncesi/sonrası boşluk kontrolleri.
- Varsayılan belge stilini kalıcı değiştirme.
