# UDE mac-arm — Claude notları

UYAP Doküman Editörü'nü (kapalı kaynak, obfuscate `editor-app.jar`) macOS'a uyarlayan
build sistemi. Asıl mantık `scripts/build.sh`; her özellik build-zamanı Javassist
yaması ya da çalışma-anı javaagent'ı olarak eklenir. Bu dosya özellikle **2026 modern
görünüm (SKIN=1)** işinin pahalı keşiflerini kalıcılaştırır — yeniden keşfetme.

## Build döngüsü

```bash
bash scripts/build.sh download && bash scripts/build.sh patch \
  && bash scripts/build.sh lookagent && bash scripts/build.sh package
```

- `download` HER iterasyonda şart (taze kaynak = çift yama yok; idempotans guard'ları
  `.skin-patched` marker'ına bakar, marker varsa yamalar atlanır).
- Varsayılanlar: `SKIN=1`, `ICONS=1`, `FOPFONTS/FILEDIALOG/PASTEIMG/IMGRESIZE/FOOTNOTE=1`.
  `SKIN=0` modern görünümün tamamını (agent dahil) kapatır.
- Test: `pkill -f UyapDokumanEditoru` sonra **doğrudan binary** çalıştır
  (`build/…app/Contents/MacOS/UyapDokumanEditoru`); `open` LaunchServices -54 verebilir
  ve eski süreç penceresi yanıltır.

## Görünüm mimarisi (SKIN=1)

İki katman:
1. **Build-zamanı** (`scripts/skin/SkinPatch.java`, Javassist): skin kurulumu, Flamingo
   şerit yamaları, cetvel renkleri, buton dolguları, ikon aydınlatma kancaları.
2. **Çalışma-anı** (`scripts/skin/agent/macoslook/MacLook.java`, javaagent): bütünleşik
   başlık çubuğu, WebMemoryBar kaldırma, cetvel zemini, sekme fontu. Bileşenleri
   bytecode avı yerine **bileşen ağacında türle** bulur. Log: `-Dmacoslook.debug=1`
   → `/tmp/macos-look-agent.log` (System.err'i uygulama yutar!).

### KRİTİK: skin kurulumu menuTheme'e bırakılamaz

UDE Substance'ı açılışta YALNIZ `acilisDegerleri.xml → initValues/menuTheme` doluysa
kurar (`tr/gov/uyap/system/a/b/a/a/` altında `au` → `an.a(String)` →
`getAllSkins().get(ad)` → `setSkin(String)`). Tema ayarındaki "standart" seçeneği bu
anahtarı SİLER → sonraki açılışlar kalıcı **Aqua**, setSkin hiç çağrılmaz, sarma
devreye giremez. Bizim skin tema combo'sunda eşleşmediğinden bu kolayca tetiklenir.
**Çözüm (mevcut):** `SkinPatch`, `WPAppManager.main → invokeLater(new aF(args))`
zincirindeki `aF.run()`'a (EDT) insertBefore ile skin'i TERCİHTEN BAĞIMSIZ kurar.
`setSkin(SubstanceSkin)` LAF Substance değilse `UIManager.setLookAndFeel`'i kendisi
çağırır (bytecode'dan doğrulandı). `setSkin(String)` sarması tema değişimleri için
ayrıca durur. Teşhis izi: `-Dmacosskin.debug=1` → `/tmp/skinpatch-trace.log`.

### KRİTİK: Substance EDT denetimleri no-op (PDF dışa aktarımı, 2026-06)

UDE bileşenleri arka plan iş parçacığında kurmaya yaslanır: "PDF Olarak Kaydet"
(cG → "save" action `save_as`, format kodu "08") → `aD` SwingWorker →
`J.b(File)` → `editor.b.b.a(out,in)` **WPDocumentPanel'i worker'da üretir**;
hata diyaloğu `JOptionPane("Çevirim sırasında hata oluştu.","Editor")` de
worker'dan açılır. Aqua/WebLaF ses çıkarmaz; Substance
`UiThreadingViolationException` fırlatır → `UIDefaults.getUI` yutar (yalnız
stack basar, null UI ile devam!) → dönüşüm NPE + **0 bayt PDF**, hata diyaloğu
**80×29 boş modal pencere** (başlıkta "E…", AWT adı dialogN — kullanıcı
ekranındaki "minik pencere + sonsuz spinner" görüntüsünün ta kendisi; worker
modal beklediğinden `done()` çalışmaz, spinner kalkmaz). **Çözüm:** SkinPatch
`SubstanceCoreUtilities.testComponentCreation/StateChangeThreadingViolation` +
`LafWidgetUtilities.testComponentStateChangeThreadingViolation` gövdelerini
boşaltır (UDE'nin yazıldığı gevşek LAF davranışı). Teşhis deseni: dönüşümü
GUI'siz dene (`b.b.a`'ya content.xml ver) — skin'siz ÇALIŞIR, skin EDT'de
kurulup worker'dan çağrılınca patlar; off-EDT JOptionPane probe'u 80×29
diyaloğu birebir üretir. Not: uygulamaya CLI argümanı olarak .udf vermek
açılışı splash'ta bırakır (UDE dosya argümanı işlemez) — test için dosyayı
uygulama İÇİNDEN aç.

### Başlık çubuğu yazısı (2026-06)

`transparentTitleBar` modunda macOS başlık METNİNİ yine de çizer ve pencere
genişliğine göre ortalar — UDE başlığı ~100 boşlukla sağa iter (vendor hilesi),
dar pencerede metin sola kayıp hızlı erişim ikonlarının ALTINA girer ("ikonlar
üst üste girmiş" görünümünün gerçek nedeni; ikon yerleşimi bozulmamıştı, TbDump
bileşen dökümüyle kanıtlandı). Zulu 11 AWT'de `titleVisibility` erişimi YOK
(libawt_lwawt strings + CPlatformWindow alanlarıyla doğrulandı;
`apple.awt.windowTitleVisible` client property yalnız JDK 17+). Çözüm: MacLook
`hookTitle` — "title" property dinleyicisi gerçek başlığı temizler (" - "
öncesi uygulama adı + " (yol)" kuyruğu atılır), rootpane `macoslook.title`'a
koyar; SkinPatch `TaskbarPanel.paintComponent` saklanan adı panelde ortalar,
son bileşenin sağına klempler, sığmazsa "..." ile kırpar — çakışma yapısal
olarak imkânsız. Hızlı erişimdeki boş kutu UDE'nin kendi JTextField'i (bizden
değil). Not: ImageIcon'lar `Utils.a(ImageIcon,II)` ölçekleme yolunda -1x-1
bildirir (getScaledInstance asenkron) ama tüketici `p.a(Action,II)` ikonu
BufferedImage'e rasterleştirdiği için zararsız.

#### Dock/Pencere menüsü pencere adları (2026-06)

ESKİ hâl yerel başlığı tek boşluğa (" ") indiriyordu → Dock sağ-tık menüsü /
Pencere menüsü / Mission Control pencereleri **NSWindow.title** ile listelediği
için tüm açık belgeler aynı BOŞ adla görünüyordu (hangisi hangisi belli olmuyor).
YENİ çözüm: `applyTitle` artık gerçek (temiz) belge adını yerel başlık olarak
**korur** (`f.setTitle(clean)`); başlık METNİ çizimini (eski çakışma nedeni)
macos-textkeys dylib'i (`NativeDialogKeys.m` → `udeHideDocTitle`/
`udeInstallTitleHider`) `[w setTitleVisibility:NSWindowTitleHidden]` ile bastırır.
Yalnız `titlebarAppearsTransparent==YES` + `NSWindowStyleMaskTitled` pencerelerde
(belge çerçeveleri; NSSavePanel/diyaloglar dokunulmaz → kendi başlıkları kalır).
Tetikleyici `NSWindowDidUpdateNotification` (çizimden ÖNCE → başlık titreşimi yok;
zaten gizliyse no-op) + ilk `[NSApp windows]` taraması. `titleVisibility` hidden
yalnız metni gizler, `window.title` Dock/menü için korunur (10.10+; agy doğruladı).
AWT `setTitle:` görünürlüğü sıfırlamaz. **KRİTİK kuplaj:** başlık METNİNİ gizleyen
native kod TEXTKEYS bayrağında, başlığı SETleme SKIN'de → tam sıra `download &&
patch && lookagent && textkeys && package && sign` (varsayılan `…lookagent &&
package` textkeys'i ATLAR → dylib bayat → SKIN=1 TEXTKEYS=0'da başlık metni
yeniden görünür/çakışır). GUI doğrulaması elle: 2 belge açıkken Dock'ta uygulamaya
sağ tık → her belgenin adı ayrı görünmeli.

### Koyu mod

- `macosskin.DarkMode.isDark()` = `defaults read -g AppleInterfaceStyle` (2s timeout,
  hata=açık mod, sonuç cache). Substance'a bağımlılığı YOK (wp.p clinit erken çalışır).
- `FlatUdeDarkSkin extends FlatUdeSkin(resource, prefix, areas[])`; koyu bundle
  NONE+GENERAL+HEADER+FOOTER+TOOLBAR alanlarına kaydedilmeli (yalnız NONE yetmez).
- jpackage java-options: `-Dapple.awt.application.appearance=system` (native başlık ve
  JRSUI bileşenleri sistemle koyulaşır; Aqua delegate'leri için ŞART).
- Kanvas `wp.p.E = DarkMode.canvasColor()` (koyu #282828, açık #ECECEC). `an` pref-load
  remap listesi: eski teal `-13854290`, açık gri `-1775637`, ara koyu değerler
  `-14803426`, `-13224394` (tercihler.xml'e kalıcılaşan eski denemeler).

### Renk modu seçimi + CANLI geçiş (2026-06)

- Görünüm > "Renk modu" combo (Açık/Koyu/Sistem; varsayılan Sistem) — MacLook
  agent ribbon MODELİNDEN ekler (darkpage deseni). Tercih
  `ude-mac-arm/colorMode`; `DarkMode.getMode()/setMode()`, isDark() önce modu
  çözer ("system" → eski defaults okuma), sonuç cache + `resetCache()`.
- **Canlı geçiş `macosskin.ModeSwitch.apply(mode)`** (EDT): setMode+resetCache →
  FlatUde(Dark)Skin kur → Aqua scrollbar/slider put + TÜM Word*.install yeniden
  (setSkin UIDefaults'u siler) → `wp.p.E` güncelle → pencere başına
  `updateTreeSafe` (bileşen-başına try'lı updateUI; tek hata ağacın kalanını
  bırakmasın) → kuruluşta DONAN renkleri elle düzelt → repaint. Font policy'ye
  DOKUNMA (yeniden sarmak FlatFontPolicy'yi üst üste bindirir).
- **Kuruluşta donan renkler** (canlı geçişin gerçek zorluğu; updateUI tazelemez):
  (a) editör kanvas bileşeni `text.hj` türevleri bg'ye E'yi KOPYALAR →
  setBackground(canvasColor); (b) cetvel eV `color_border` STOK #282828 (her
  temada!) → hem ctor yaması hem switch'te `setColor_border(canvasColor())`
  (Word'de cetvel kanvasa karışır, border görünmez olmalı — kullanıcı isteği);
  `setColor_unusableregion(canvasColor())` de switch'te şart; (c)
  JRootPane/JLayeredPane bg'leri (UI delegate'siz) eski UIResource ile kalır →
  Panel.background bas.
- **İkonlar `ModeAwareImage`** (AbstractMultiResolutionImage): IconDarken artık
  HEP sarar, aydınlatma paint ANINDA moda göre (koyu varyant lazy+cache).
  Ölçekleme yolu `Utils.a(ImageIcon,II)`'ye SkinPatch insertBefore →
  `IconDarken.scaleIcon` (açık varyantlar ölçeklenir, tekrar sarılır).
  MenuMarks WebLaF statikleri açılışta basılır — canlı geçişte eski renkte
  kalır (bilinen küçük sınır; menü işaretlerinin çoğu paint-anı WordCheck'ten).
- **Teşhis: dynamic attach** — `VirtualMachine.attach(pid).loadAgent(jar,args)`
  (bundled JDK, jdk.attach) ile canlı JVM'de kod koşturma: ModeSwitch tetikleme,
  bileşen zinciri dökümü (TreeProbe), UIManager/skin durumu (StateProbe).
  TUZAKLAR: anonim iç sınıfları jar'a koymayı unutma (AgentInitializationException);
  jar yolu MUTLAK olmalı (hedef süreç çözer); combo action kaynağı stack'te
  `BasicComboPopup$Handler.mouseReleased` = gerçek tıklama.
- **Test düzeneği prefs yarışı:** java.util.prefs macOS'ta süreç kapanırken
  flush eder — `PrefSet yaz → pkill → başlat` sırası ÖLEN sürecin eski değeri
  geri yazmasıyla bozulur; doğrusu `pkill → bekle → yaz → başlat`.

### Word-Mac koyu paleti (piksel ölçümü; `flatude-dark.colorschemes`)

| Öğe | Değer |
|---|---|
| Tek genel yüzey (şerit=kanvas=durum çubuğu=üst bar) | `#282828` |
| Hover dolgu | `#3D3D3D` |
| Seçili buton dolgu | `#474747` (basılı `#515151`) |
| Metin | `#E4E4E4`; devre dışı `#6E6E6E` |
| Cetvel: zemin `#464646`, marjlar `#262626`, rakam `#E2E2E2`, tik `#98989E`, işaretçi `#DCDCDC` |

**Kalibrasyon bulgusu:** Substance genel paneli **`Default.ultraLight`** slotundan
boyar (doğrudan renkler birebir basılır; ekran yakalaması renk kaydırmaz — bunu
kanıtlamak 3 build iterasyonu sürdü). Hedef yüzeyi Default.ultraLight'a yaz, kalan
slotları ~4-6 birim adımlarla koyulaştır. Hover/seçili dolgular artık şemadan değil
`BasicCommandButtonUI` yamasından gelir (aşağıda).

### Word-Mac açık paleti (piksel ölçümü, 2026-06)

| Öğe | Değer |
|---|---|
| Tek genel yüzey (başlık=şerit=durum çubuğu) | `#F5F5F5` (açıkta `Default.extraLight` slotu boyar) |
| Kanvas | `#ECECEC` (`DarkMode.canvasColor()`) |
| Cetvel | zemin beyaz, marj `#DFDFDF` (eV LIGHT_GRAY FieldAccess artık açıkta da remap) |
| Metin/kontur | metin `#262626`; ince kontur `#C9C9C9`; ayraç `#DEDEDE` |

Eski mavimsi palet (#F7F8FA/#E4E7EB) nötr grilere çevrildi; `an` remap listesi
eski açık kanvası (-1775637) zaten kapsıyor, yeni değer #ECECEC kalıcılaşır.

### Diyalog düzleştirme (Bul/Değiştir vb., 2026-06)

Diyaloglardaki gri kabartmalı butonlar + kutulu 3B sekmeler **Substance'ın kendi
delegate'leri** (SubstanceButtonUI/SubstanceTabbedPaneUI, ClassicButtonShaper) —
UDE'nin `gui.a.*` widget'ları (`g`=JButton, `O`=JTabbedPane…) getUIClassID'yi
override eder ama STANDART ID döndürür, yani UIManager.put ile delegate değişimi
işler. WordCombo deseniyle: `WordButton` (düz yuvarlak, varsayılan buton vurgu
mavisi `#3B69DA`; paint içinde setForeground YASAK — repaint döngüsü, paintText
override kullan), `WordTabs` (sekme kutusu yok, seçili = mavi alt çubuk + ince
ayraç), `WordField` (**SubstanceTextFieldUI kenarlığı UIDefaults'tan OKUMAZ**,
kendi içinden kurar → Basic tabanlı delegate şart). "Seçenekler" grubunun
EtchedBorder'ı `jc`'de NewExpr ile değişir; **Javassist NewExpr tuzağı: `$_`
orijinal sınıf tipindedir**, LineBorder atanamaz → `FlatEtchedBorder extends
EtchedBorder`. Teşhis/iterasyon: diyalog widget'larını paketli jar'a karşı
EKRAN-DIŞI render eden DlgProbe deseni (build beklemeden delegate doğrulama);
Panel/CheckBox zemin eşitliği UIManager.getColor ile problanır. Sentetik
osascript klavye olayları UDE'ye güvenilir ulaşmaz (Ctrl+B diyaloğu açılmadı) —
diyalog doğrulaması probe + bytecode grep ile yapılır.

### Native delegate hilesi (büyük kazanç)

`com.apple.laf.AquaScrollBarUI` ve `AquaSliderUI`, **Substance global LAF iken**
sorunsuz çalışır (JRSUI native çizim; WebLaF'taki "delegate kendi LAF'ını ister"
kuralının istisnası). Kurulum: skin kurulduktan sonra
`UIManager.put("ScrollBarUI"/"SliderUI", "com.apple.laf.Aqua…UI")` — hem `aF.run()`
hem `setSkin(String)` sarmasında (skin değişimi UIDefaults'u tazeler, yeniden put
gerekir). `appearance=system` sayesinde açık/koyu otomatik.

### Flamingo şerit yamaları (obfuscate DEĞİL — isimle hedeflenir)

- Grup kutuları: `BasicRibbonBandUI.paintBandTitle/paintBandTitleBackground` +
  iç sınıf `BasicRibbonBandUI$RoundBorder.paintBorder` → setBody boş.
- Kontur rengi: `FlamingoUtilities.getBorderColor()` UIManager
  `TextField.inactiveForeground` okur → koyu temada BEYAZ çizer; tema-duyarlı sabit
  döndürülür.
- Sekme satırı: `BasicRibbonUI.paintTaskArea` → boş (Office-2007 tam-genişlik çizgisi
  + seçili sekme konturu gider). Seçili sekme alt çubuğu
  `BasicRibbonTaskToggleButtonUI.paintButtonBackground`'da çizilir. **TUZAK:** sekme
  düğmesi 31px ama parent yalnız ~21px gösterir (Office-2007 "banda taşma" tasarımı,
  alt ~10px kırpılır) → çubuk `parent.getHeight() - button.getY()` görünür yüksekliğe
  hizalanmalı, yoksa görünmez.
- Buton durum dolguları: `BasicCommandButtonUI.paintButtonBackground` — **İKİ overload
  da bu sınıfta**: `(G,R)` ve `(G,R,ButtonModel...)` (toggle'lar varargs'ı kullanır).
  İkisini AYNI CtClass üzerinde yamala; `BasicCommandToggleButtonUI`'dan getMethod
  yapmak parent'ı çözer ve ikinci writeClass "**class is frozen**" fırlatır.
- Tooltip'ler Word tarzı: zengin tooltip `BasicRichTooltipPanelUI.paintBackground`
  hardcoded AÇIK gradyan basar (`Label.disabledForeground.brighter()` 0.9/0.4) →
  koyu modda okunmaz; setBody düz dolgu (koyu `#2E3032` Word ölçümü, açık beyaz).
  Substance'ın RichTooltip delegate'i YOK, Basic'i yamalamak yeter. Düz JToolTip:
  `macosskin.WordTooltip.install()` — Aqua scrollbar deseniyle `ToolTipUI` →
  `BasicToolTipUI` + ToolTip.background/foreground/border put'ları (iki skin-kurulum
  noktasından da çağrılır).
- Combo box'lar Word tarzı: `macosskin.WordCombo.install()` — WordTooltip deseniyle
  `ComboBoxUI` → `WordCombo$UI extends BasicComboBoxUI` (iki skin-kurulum noktasından
  da çağrılır). Word ölçümü: koyu dolgu `#333333`, kontur `#5A5A5A` (tooltip çizgisiyle
  aynı), arc 8; `update()` yuvarlak dolgu+kontur, focus seçim vurgusu kapalı
  (`paintCurrentValue(…, false)`), opaque=false, chevron özel arrow button.
  Basic'in kare ok butonu Substance'tan geniş → dar combo metni kırpar; "Geçerli/Gövde"
  kapsam combo'su bu yüzden + istekle MacLook agent'ında şeritten tamamen kaldırıldı
  (model içinde "Geçerli" araması, JRibbonComponent sarmalayıcısıyla birlikte remove).
  **Type-ahead (2026-06, orijinal UDE'de de olan hata):** font listesinde 'c' yazınca
  ilk c-fontu seçilip liste kapanıyordu. Kök neden: stok JComboBox type-ahead'i
  (`BasicComboBoxUI.Handler.keyPressed → selectWithKeyChar`) ilk harfte setSelectedIndex
  → UDE listener'ı (`gui.iv` vb.) fontu uygular + `requestFocusInWindow()` ile odağı
  editöre taşır → odak kaybı popup'ı kapatır. Çözüm WordCombo$UI.createKeyListener:
  popup AÇIKKEN harfler biriken önekle (ComboBox.timeFactor) yalnız liste vurgusunu
  taşır (listBox.setSelectedIndex, commit YOK); Enter/tık commit eder. Popup kapalıyken
  stok davranış korunur. Şerit font combo'su `gui.gc` (klasik toolbar `ir`/`ad`), hepsi
  `gui.a.k → JComboBox`. Test: tests/ComboTypeAheadTest.java (javac+java elle); canlı
  doğrulama dynamic attach probe ile (combo belge açılmadan ağaçta showing=false olur).
- Orb: görsel `resources/ude.png` (34×32) + `ude@2x/ude_ki/ude_ki@2x` (ikon override
  zip'i; boyutları DEĞİŞTİRME), arka plan efekti
  `BasicRibbonApplicationMenuButtonUI.paintButtonBackground` → boş.
- Orb MENÜSÜ Word tarzı: kenarlıklar `BasicRibbonApplicationMenuPopupPanelUI`'ın
  ANONİM Border iç sınıflarında (`Label.disabledForeground` + `brighter()×2` →
  koyu temada bembeyaz çift çerçeve). `$8`=dış kenarlık (insets top=20 Office
  bandı + renderSurface + CellRendererPane ile orb KOPYASI çizer) → tek ince
  kontur (koyu #5A5A5A/açık #C8C8C8) + insets 6,4,6,4; `$9`=mainPanel çift
  çerçeve → boş; `$6`=sütun ayracı → hover tonu (#3D3D3D/#E0E0E0); `$7`=footer
  renderSurface gradyanı → düz getBackground() dolgusu. Menü öğeleri
  JCommandMenuButton = BasicCommandMenuButtonUI, paintButtonBackground'ı
  override ETMEZ → hover dolguları yamalı BasicCommandButtonUI'dan kendiliğinden
  gelir. Substance-Flamingo köprü delegate'i jar'da YOK, Basic'ler çalışır.
- Sekme fontu bold: MacLook agent `JRibbonTaskToggleButton.setFont(BOLD)` — bileşen
  üzerinde türet ki genişlik bold ölçüyle hesaplansın. Font BOYUTU artırma (Flamingo
  katı layout, kırpar); yalnız aile/stil güvenli.
- Hızlı erişim ayraç/kontur çizgileri: `BasicRibbonUI$TaskbarPanel.paintComponent`
  Office "swoosh" dekoru (getOutline arc dolgu+kontur + alt çizgi) → setBody boş.
- Popup açılınca buton çevresindeki gri dikdörtgen: UDE `a.b.a.a.t` FocusListener
  `focusGained`'de `BevelBorder(RAISED)` basar (Win95 odak halkası) → boşaltıldı.
  Teşhis yöntemi: Graphics2D sarmalayıcı (draw/fill çağrılarına stack trace) ile
  root pane'i ekran-dışı çizdirmek — "kim çiziyor" sorusunu kesin yanıtlar.
- Komut popup'ları (Bul/Değiştir vb.): `BasicPopupPanelUI.installDefaults`
  LineBorder(getBorderColor) kurar → Word ölçümü #1E1E1E yüzey + #050505 kontur
  override (UIResource OLMAYAN değerler bırak ki yeniden kurulumda ezilmesin).
  `BasicCommandPopupMenuUI$MenuPanel.paintIconGutterBackground/Separator` → boş.
  Menü öğeleri (JCommand[Toggle]MenuButton) koyu modda tam satır mavi #3B69DA
  (Word ölçümü); çapa JCommandButton `popupModel.isPopupShowing()` → hover grisi.
- UDE özel galeri popup'ları (madde işareti/numaralandırma `kh`/`kx` pencereleri):
  `gui.a.t` (JLabel türevi) + `gui.a.A` (JPanel türevi) widget'larına Javassist'le
  setBackground/Foreground/Border override'ları enjekte → `macosskin.PopupRemap`
  eski grileri/beyazları #1E1E1E'ye, Office turuncularını (#FF9933..#FFE5CC)
  seçim mavisine, silver kenarlıkları koyu konturlara eşler (runtime border
  değişimleri dahil). `BasicCommandButtonPanelUI` grup zemini/başlığı da düzlendi.
- Onay kutuları/radyolar: Substance 5 işareti 18px 1x BufferedImage CACHE'inden
  basar → Retina'da bulanık. `WordCheck` (WordCombo deseni): CheckBoxUI/
  RadioButtonUI + CheckBoxMenuItemUI/RadioButtonMenuItemUI → Basic tabanlı,
  paint anında vektör Icon (mavi dolgu + beyaz tik). DİKKAT: Biçim sekmesindeki
  "Otomatik Büyük Harf" üçlüsü şeride gömülü JCheckBoxMenuItem (z sınıfı statikleri)
  — menü delegate'i bu yüzden şart. WebLaF menü işaretleri (Web*MenuItemUI statik
  1x PNG) `MenuMarks` BaseMultiResolutionImage ile değiştirildi.
- Koyu belge arkaplanı (Görünüm sekmesi onay kutusu, varsayılan KAPALI):
  `macosskin.DarkPage` — editör `text.hj.paint(Graphics)` girişinde `$1 =
  DarkPage.wrap(this,$1)` (hj super'i çağırır, tek alt sınıf `fi` paint
  override etmez). Sarmalayıcı delegeli Graphics2D; setColor/setPaint/
  setBackground HSL açıklık çevirisi `L' = 0.89 − 0.74·L` (beyaz→#262626,
  siyah→#E3E3E3, ton/doygunluk korunur → kırmızı metin kırmızı kalır, kontrast
  her bileşimde korunur). `wp.p.E` (kanvas) aynen geçer, `isPaintingForPrint`
  sarılmaz (baskı/PDF etkilenmez), drawImage filtrelenmez; create() türevleri
  sarılı kalır. Durum prefs `ude-mac-arm/darkPageBackground`. Onay kutusu
  MacLook agent'ında ribbon MODELİNDEN eklenir (bileşen ağacı yalnız SEÇİLİ
  sekmenin bandlarını içerir, ağaç araması açılışta bulamaz): tüm task'ların
  `getBands()` → `getControlPanel()` altında "Klasik görünüme geç" JCheckBox'ı
  aranır → o banda `addRibbonComponent(new JRibbonComponent(cb))`; tamamı
  yansıma (agent jar classpath'siz derlenir).
- TUZAK: jar'ı dosya sistemine açıp javap'lamak macOS case-insensitive FS'te
  `kx`/`kX` gibi sınıfları EZER — obfuscate sınıfları `javap -classpath <jar>`
  ile doğrudan jar'dan oku.

### İkon seti: Fluent + fonksiyonel renk (2026-06)

Set Material'den **Fluent UI System Icons** (MIT, regular/ince-çizgi) + fonksiyonel
renge geçti. Palet (açık tema): gövde `#444444`, sil/kapat `#D13438`, ekle/onayla
`#107C41`, belge/yön `#2B7CD3`, boya/vurgu `#D9A21B`. Üretim:
`scripts/icons/fluent/mapping.tsv` (resource → WxH|auto → fluent adı|KEEP|compose: →
renk kuralı) + `generate.py` (jsDelivr'den indirir, renkler, rsvg-convert ile
native+@2x rasterize → `overrides/resources/`). `--check` ad doğrular, `--dump`
alt-yol indekslerini listeler. PNG'ler commit'li; build çevrimdışı.

- **Winding tuzağı:** alt-yolları ayrı `<path>`'lere bölmek iç delikleri som dolguya
  çevirir → `sub:` yokken path bölünmez; varken aynı renkli ardışık parçalar tek
  path'te birleştirilir. `sub:N` yalnız bağımsız parçalarda (renk çubuğu, ok) işler;
  göreli `m` ile süren alt-yollar bölünmez. Boya damlası gibi parçalar `extra:`
  (zemine elle path) ile renklendirilir.
- `search` compose: 24×24 tuval, ~10px glyph +7+7, **@2x üretilmez** (kırpılma tuzağı).
  42×26 chevron'lu liste butonları `compose_glyph_chevron`.
- KEEP: stil önizlemeleri (`numberType*`, `mlListType*`), `ude/ude_ki/ude2` (orb/logo),
  `default`, `image_not_found`, `printPreviewBG`. `alignLeft16` ailesi jar'da YOK.
- **İmza rozetleri KEEP (kullanıcı isteği, 2026-06):** orijinal UYAP `certificate*`
  ailesi KIRMIZI KURDELELİ ROZET'tir (e-imza mührü görünümü); Fluent düz sertifika
  ikonu istenmedi. KEEP (orijinal korunur): `certificate` ("İmzalar" eylem ikonu,
  `text/cX` ActionCommand "06" + `pki/a/w`), `certificate72` (büyük, çalışma-anı
  dinamik yüklenir), `certificates` (`text/eY`), `certificateSB`/`certificateSBG`
  (`pki/a/n`, `gui/cW`). Override PNG'leri SİLİNDİ → `apply_icons` jar orijinalini
  ezmez. Hâlâ Fluent kalan tek istisna durum rozetleri: `certificateValid`(yeşil✓)/
  `certificateInvalid`(kırmızı✗)/`certificateQuestion`(sarı?) — imza durum panelinde
  (`pki/a/l`) kullanılır, override edilir. Orijinali görmek: ham `downloads/ude.zip`
  içindeki jar (`_input` jar zaten yamalı, md5 override ile aynı çıkar).
- **Küçük ikon kuralı:** native ≤16px ikonlarda 24-grid regular ince kalır →
  Fluent **16/20-grid _filled** varyantı kullan. `undo/redo` = `arrow_hook_up_left/right`
  (Word'ün kıvrık oku; `arrow_undo` ince ve "kırık" görünür).
- **Retina keskinlik yamaları (IconLoaderPatch):** (a) `Utils.a(ImageIcon,int,int)`
  `getScaledInstance` ile multi-res'i öldürüyordu (hızlı erişim bu yoldan ölçeklenir,
  bloklu görünümün kök nedeni) → varyant-başına ölçekleyip BaseMultiResolutionImage
  döndürür. (b) Flamingo `ImageWrapperIcon.paintIcon` kaynağı 1x rasterleyip
  cache'liyordu → kaynak Image hedef boyuta bicubic erken-dönüşle çizilir (eski
  "Flamingo multi-res yok sayıyor, çözülmedi" notu ARTIK GEÇERSİZ).
- **Disabled (Flamingo):** `FilteredResizableIcon.paintIcon` gövdesi IconLoaderPatch'le
  değiştirildi → delegate doğrudan AlphaComposite 0.38 ile çizilir. Orijinal yol
  (1x raster + ColorConvertOp(CS_GRAY)) Retina'da tırtık + ince çizgide kir yapıyordu;
  delegate çizimi keskinliği ve soluk renkleri korur. WebLaF tarafı hâlâ
  `disabledIconsTransparency=0.38f`.

### İkonlar koyu modda

`macosskin.IconDarken.lightenPixel()` İKİ kural: (1) nötr koyu (sat<0.35 && bri<0.6)
→ açık gri; (2) **doygun-koyu vurgu** (sat≥0.35 && bri<0.55) → ton korunup parlaklık
0.72'ye, doygunluk ×0.85 (örn. `#107C41` → `#30B86D`; piksel ölçümüyle doğrulandı).
`#D13438/#2B7CD3/#D9A21B` zaten parlak, dokunulmaz. Test:
`tests/IconDarkenPixelTest.java` (macosskin paketinde; javac+java ile elle).
Kancalar `Utils`'in ÜÇ yükleyicisine: `b(String)`, `a(String)`, `a(String,int)` —
`a(String)` unutulursa hızlı erişim ikonları eski kalır. SkinPatch, apply_icons'tan
SONRA çalıştığı için multi-res sarmasının üstüne insertAfter güvenli.

### Cetvel (`tr.com.havelsan.uyap.system.editor.common.gui.eV`, IRuler)

Renk akışı: rakamlar `a()`→Color.black; tikler `b()/c()`→statik alanlar; işaretçiler
statik `d/e`; marj blokları `Color.LIGHT_GRAY` hardcoded; zemin `getBackground()`
(beyaz set edilir). Yama deseni: getter'lara `if (isDark()) return …;` insertBefore
(obfuscate alan adı çakışmalarından kaçınır — `b` adında int/float/Color alanları
var, clinit'e yazma riskli); statik okumalar FieldAccess `$_ = isDark ? yeni :
($r) $proceed();`; zemin MacLook agent'ında `getInterfaces()` ile `IRuler` bulunarak
`setBackground`.

## macOS dikte düzeltmesi (DictationFix, 2026-06)

Dikte kapanınca metin kaybı + donma. KÖK NEDEN: `JTextComponent`, IME istemcisi
görünmeyen bileşende (override/listener yok) commit edilen her karakteri SENTETİK
KEY_TYPED olarak işler (`replaceInputMethodText`); UDE'nin kelime-denetimi
(`im.keyTyped → … → hB.changedUpdate → hj.a → gui.aC.a`) `getCaret()`'i kendi
`text.l` tipine cast eder, commit anında caret Swing'in geçici `ComposedTextCaret`'i
→ ClassCastException → commit yarıda ("deneme bir iki" → "Deneme"), EDT istisnası
CInputMethod akışını bozup dondurur. Cast jar'da onlarca yerde → cast-koruması yolu
ELENDİ. **Çözüm tek satır:** `macostextkeys.DictationFix` — odaklanan her
`JTextComponent`'e no-op `InputMethodListener` (idempotent). `addInputMethodListener`
JDK'da `needToSendKeyTypedEvent=false` yapar → commit `mapCommittedTextToAction` ile
kit'in NORMAL yazma aksiyonundan akar; composed görüntüleme/ölü tuş `^`→`â`/emoji
bozulmaz. Bilinen küçük fark: IME-commit metni `im.keyTyped`'dan (otobüyük harf,
anlık yazım işareti) geçmez — o yol zaten çöküyordu. Teşhis: `UDE_DICTLOG=1` →
`~/Library/Logs/ude-dictation.txt` (`DictationProbe`: IME olayları + EDT uncaught).
**Dikte testi build'siz simüle edilir:** dynamic attach agent'ı editörü (`text.t`,
`hj` torunu) bulup composed→commit `InputMethodEvent` dizisini `dispatchEvent` ile
EDT'de sürer — gerçek dikteyle aynı `processInputMethodEvent` yolu (DictSim deseni;
spec/plan: docs/superpowers/*/2026-06-11-dictation-fix*).

## Otomatik düzeltme seçenekleri anında etkin (LIVETOGGLE=1, 2026-06)

"Otomatik Büyük Harf"/"Baş Harfler Büyük"/"Kelime Denetimi" dinleyicilerini
(`text.hN`/`fY`/`im`, hepsi KeyListener) yalnız `text.fk.run()` AÇILIŞTA takar
(tercih `initValues/ToUpperCase|FirstLetterUpperCase|SpellCheck` true ise);
toggle eylemleri (`text.dq`/`dA`/`db`) yalnız tercih yazar + "yeniden başlat"
diyaloğu (`kP.b`) gösterirdi. `apply_livetoggle`: jar'a reflection tabanlı
`macoslivetoggle/LiveToggle` enjekte + üç eylemin `a(ActionEvent)`'ine
insertBefore `syncSource` (menü kopyasından tıklayınca şerit kutusu kaynağa
eşitlenir — eylem değeri HEP şeritteki `z` statiklerinden okur, upstream
eski-değer bug'ı) + `kP.b` → no-op + insertAfter `apply` (pref oku → tüm
Frame'lerde `fi` dolaş → dinleyici ekle/sök → kutu kopyalarını eşitle).
TUZAKLAR: obfuscate üye adları çakışır (`z`'de üç `a` alanı, `im`'de üç `a`
metodu) → erişim REFLECTION + TİP + görünen METİN ile; `im`'in statik Zemberek
getter'ı TEMBEL yükler (null → dinleyicisiz, stok fk davranışı); sentetik
KeyEvent `dispatchEvent` testte İŞLEMEZ (KeyboardFocusManager yutar) →
dinleyici listesi attach probe ile, davranış `hN.keyTyped(ev)` doğrudan
çağrısıyla doğrulanır. Dikteyle giren metne bu özellikler yine uygulanmaz
(DictationFix takası — IME commit keyTyped üretmez).

## macOS Metin Değiştirme (TEXTREPLACE=1, 2026-06)

Sistem Ayarları → Klavye → Metin Değiştirmeleri kısayolları ("mrb" →
"Merhaba!") UDE'de çalışır. Cocoa metin-denetim kanalı Java'ya kapalı →
`macos-textkeys` agent'ında `TextReplace`: KEY_TYPED sonlandırıcı (boşluk/
Enter/noktalama) → invokeLater → caret solundaki sözcük eşleşirse değiştirilir.
**TUZAK: seçim üzerinden değiştirme (setCaretPosition+moveCaretPosition+
replaceSelection) UDE'de NPE** — moveDot UDE caretUpdate zincirini
(`hB.caretUpdate → gui.aD.a`) düşürür; doğrusu belge-düzeyi atomik
`AbstractDocument.replace` + `StyledEditorKit.getInputAttributes().copyAttributes()`
(JTextPane.replaceSelection'ın iç yolu, seçimsiz). Liste `ReplacementStore`:
TextReplacements.db kopyası (db+wal+shm → temp) sqlite3 `hex()` ile (çok
satırlı phrase satır-ayrıştırmayı bozar), yedek `defaults export -globalDomain`
plist; pencere aktifleşince 30 sn kısıtlamayla daemon thread'de yenilenir
(EDT'de süreç yok). Küçük harfli kısayol ilk-harf-büyük yazılışla da eşleşir
(tr-TR capFirst — UDE Otomatik Büyük Harf uyumu). Dikteyle giren metne
uygulanmaz (keyTyped üretilmez, DictationFix takası). Teşhis: `UDE_TRLOG=1` →
`~/Library/Logs/ude-textreplace.txt`. Attach-probe tuzağı: ilk yüklenen agent
jar bozuksa (yanlış bytecode sürümü) classpath'te TAKILI kalır → düzeltilmiş
jar'ı aynı ada yüklemek yine başarısız olur; uygulamayı yeniden başlat ya da
sınıf/paket adını değiştir. Testler: `tests/TextReplaceMatchTest.java`,
`tests/ReplacementStoreTest.java` (javac+java elle).

## Backspace ile tablo silme (2026-06)

Tablolar (özellikle PASTERICH ile yapıştırılanlar) Word'deki gibi Backspace/Delete
ile silinir. UDE'nin tek tablo-silme primitifi `DocumentEx.f(int)` (üst sınıf
`wp.model.v.f`; "Tablo Sil" araç-çubuğu eylemi `text.ct` de bunu çağırır):
`getCharacterElement(pos)` → "table" atası varsa satırları tek tek kaldırır; pos
tablo İÇİNDE değilse no-op. `macos-textkeys` agent'ındaki `TableDelete` (MacTextKeys
`applyBindings0`'dan bileşen-başına bağlanır) düz Backspace/Delete'i ele alır: saf
`javax.swing.text` ile senaryo tespiti (tablo ardı / seçim / içeride-boş), tablo
varsa reflection ile `f()` çağrılır, yoksa **yakalanmış orijinal aksiyona devredilir**
(normal yazma/silme bozulmaz). Tespit testi: `tests/TableDeleteDetectTest.java`.
- **KRİTİK — offset-0 tablosu silinmez:** `f()`, tablo belgenin İLK öğesiyse
  (üstünde paragraf yok) `BadLocationException: "Nowhere to place the list"` fırlatır —
  satır içeriğini taşıyacak üst paragraf bulamaz ("list" UDE'nin genel terimi; tablo
  liste içermese de olur, piksel/probe ile doğrulandı). Çözüm yapıştırmada:
  `NativeInsert.insert` ilk blok tablo ve caret 0'dayken `insertString(0,"\n")` ile
  baş paragraf ekler (boş belge bağlamında offset 0 NORMAL paragraftır → temiz baş
  paragraf; tablo bağlamında olsa HÜCREYİ bölerdi) → tablo offset 1'e iter, silinebilir.
  Yüklenen belgedeki ilk-öğe tablosu hâlâ silinemez (UDE'nin kendi "Tablo Sil"i de
  silemez) — `f()` fırlatınca TableDelete sessizce orijinal aksiyona devreder.
- **Seçim:** `deleteSelectionWithTables` kapsanan TÜM tabloları `f()` ile kaldırır
  (Position ile offset izleme), sonra kalan seçili metni `doc.remove` ile siler (tek
  `f()` yalnız tabloyu kaldırıp metni bırakıyordu).
- Teşhis: `UDE_TABLEDELLOG=1` → `~/Library/Logs/ude-tabledelete.txt` (tryDelete/f()
  HATA + unwrap'lı stack). Kök neden dynamic-attach element-ağacı probe'uyla bulundu
  (offset-0 + "list" mesajı; `insertString(0,\n)` hücreyi böldüğü probe ile kanıtlandı).

## Harici Stilli Yapıştırma (PASTERICH=1, 2026-06)

Word/tarayıcı/PDF'den kopyalanan stilli metin (kalın/italik/altı-çizili/font/
boyut/renk/hizalama/**liste/tablo**) UDE'ye biçimiyle yapışır. KÖK NEDEN:
`hj.paste()` stilleri YALNIZ EditorDataFlavor (UDE-içi) ve HTML panosundaki
`uyap-web-editor-data` base64 işaretiyle (UYAP web) korur; harici HTML işaret
içermez → düz metin (tasarımı gereği). **`PasteRichPatch`** (Javassist)
`hj.a(Transferable)` BAŞINA dal enjekte eder: işaret yok + `allHtmlFlavor` varsa
`macospasterich.RichPaste.insertInto(this, html)` → başarıda `return true`
(düz-metin fallback çalışmaz), başarısızsa düz-metne düşer (çökme yok).

### KRİTİK: copy→paste tabloları DÜZLEŞTİRİR — yerel ekleme şart

İlk tasarım pano HTML'i UDE `.udf`'sine çevirip UYAP-web yolunun AYNISIYLA
(`WPDocumentPanel.a(InputStream)` → `select-all` → `copy` → `this.paste()`)
ekliyordu. Bu yol metin/liste için çalışır ama **tabloları DÜZLEŞTİRİR** —
EditorDataFlavor copy→paste tablo yapısını kaybeder (GERÇEK UDE tablolarını bile;
element-ağacı testiyle kanıtlandı: load→copy→paste sonrası `table` elementi 0).
Ayrıca temp-panel `copy()` canlıda tablo seçimini koyamayınca `this.paste()`
HTML'i tekrar görüp **sonsuz özyineleme/donma** (318 frame) ve EditorDataFlavor
tablo-ekleme `Hashtable.put` null-değer NPE'si çıkardı. **Tüm bu yol terk edildi.**

ÇÖZÜM: modeli canlı belgeye **YEREL ekle** (clipboard/copy/paste/özyineleme/NPE
YOK). `macospasterich.NativeInsert.insert(editor, model)`:
- **Tablo:** UDE'nin kendi tablo-kurma primitifi
  `DocumentEx.a(caretPos, "Sabit", rows, cols, int[] colWidths, String[] rowStyles,
  SimpleAttributeSet attrs)` — `bC` (table-insert action) diyalogların altında bunu
  çağırır; diyalogları atlayıp DOĞRUDAN çağırırız → GERÇEK tablo. **TUZAK (2026-06):**
  5. param `colWidths` uzunluğu = SÜTUN sayısı; 6. param PER-SATIR dizidir, uzunluk =
  SATIR sayısı, değerler `"row"+(i+1)`. DocumentEx.a gövdesi 6. diziyi p3(rows) ile
  karşılaştırır, eşleşmezse p4(cols) uzunlukta YENİDEN kurar (vendor bug) → `rows>cols`
  olan tabloda satır döngüsü `index=rows-1`'i `length=cols` dizide arar →
  `ArrayIndexOutOfBoundsException` ve tablo gelmez. 6. diziyi `cols` uzunlukta vermek
  KARE tablolarda gizleniyordu (Word testleri kareydi); Pages 4×5 tablosu açığa
  çıkardı. Doğru: `rowStyles = new String[rows]`. attrs:
  `ae.x(attrs,"Sabit")`=ad, `ae.w(attrs, Utils.a(int[]))`=genişlikler,
  `ae.z(attrs, "borderCell"/"borderNone")`=kenarlık (hepsi reflection). Tablo
  kurulunca element ağacında `cell` elementleri bulunur, her hücrenin ilk-paragraf
  offset'ine içerik SONDAN-başa yazılır (offset kayması olmasın).
- **Paragraf/karakter:** `StyledDocument.insertString(pos, text, attrs)` +
  `setParagraphAttributes`; `attrs` = standart `javax.swing.text.StyleConstants`
  (Bold/Italic/Underline/Foreground=`new Color(argb,true)`/FontFamily/FontSize/
  Alignment 0-3). Saf java.* + reflection (UDE iç tipleri derleme-zamanı gerekmez).
- **TUZAK — run-içi `\r\n`:** HTML'de paragraf-içi satır sonu BOŞLUKTUR. Word
  `MsoListParagraph` liste itemleri run metninde `"1.\nAd"` taşır; `insertString`
  `\n`'i yeni paragraf sayıp item'i ikiye böler → `NativeInsert.clean` run metnindeki
  `[\r\n]+` → tek boşluk.
- **TUZAK — columnSpans MUTLAK genişlik:** "1,1,1" dejenere (3px) tablo → kenarlık
  görünmez; gerçek UDE 100/sütun kullanır → `HtmlToUde` columnSpans=100. Hücreler
  ÇIPLAK (`<cell>`), kenarlık tablo-düzeyi `border="borderCell"` ile çizilir
  (per-cell `borderColor="0"` tablo çizimini eziyordu — gerçek UDE'de 259 çıplak
  hücre, 0 kenarlıklı).

### Kaynak-bağımsız (Word + Google Docs + Pages + AI, 2026-06)

Her kaynak stili FARKLI taşır: Word `<b>`/`<i>` + inline; **Google Docs** her şeyi
`<span style="font-weight:700">` ile işaretler + tümünü `<b style="font-weight:normal">`
ile sarar (eski kod bunu görünce HER ŞEYİ bold yapar, span stillerini hiç okumazdı);
**Pages/TextEdit/Mail** panoya HTML KOYMAZ (yalnız RTF/RTFD). HtmlToUde artık
kaynak-bağımsız:
- inline `font-weight`/`font-style`/`text-decoration` okunur; bold/italic/underline
  ÜÇ DURUMLU (`Boolean` null=miras) → Docs sarmalayıcısı `font-weight:normal` artık
  her şeyi bold yapmaz (açıkça geçersiz kılar).
- `<style>` bloğu artık YUTULMAZ (`Html.onStyle`); class kuralları toplanıp
  `resolved()` ile (class + inline; inline kazanır) hizalama/girinti/kenarlık/zemin/
  font DAHİL her yere uygulanır (Pages tüm stili class'ta taşır).
- `font:` kısayolu (`applyFontShorthand`), `<font>` etiketi, `rgb()/rgba()`.
- **Pages = RTF (HTML yok):** pano `osascript -e 'clipboard info'` → «class RTF/rtfd»,
  HTML flavor YOK → eski kanca HİÇ tetiklenmezdi (log boş kalır, kök tanı bu).
  Çözüm: `a(Transferable)` kancasına RTF dalı (`else if`) — `RichPaste.insertRtf`
  RTF flavor'ını alıp macOS `/usr/bin/textutil -convert html` ile HTML'e çevirir
  (textutil çıktısı zaten `<style>` class + `font:` kısayolu + tablo-kenarlığı-class
  biçimi → HtmlToUde sorunsuz çözer), sonra aynı YEREL ekleme yolu. `paste()`
  EditorDataFlavor yoksa `a(Transferable)`'ı KOŞULSUZ çağırır (bytecode offset 94-96
  doğrulandı) → RTF-only pano da kancaya ulaşır. TUZAK: Pages resimleri RTFD ekinde;
  düz `text/rtf` flavor'ında YOK → Pages'tan metin/tablo/stil gelir, resim gelmez
  (Word/Docs HTML yolunda resim çalışır). Teşhis: ham pano HTML'i her yapıştırmada
  `~/Library/Logs/ude-pasterich-last.html` (`PrLog.dumpHtml`). Test:
  `tests/RichPasteSourcesTest.java` (Docs/Pages/AI desenleri).

- **Saf-Java dönüştürücü** (`scripts/macos-pasterich/macospasterich/`): `Css`
  (renk/inline-stil), `UdeDoc` (model), `Html` (KENDİ hoşgörülü tokenizer'ımız —
  yorum/DOCTYPE/PI atlar, `<script>` yutar, `<style>` içeriğini `onStyle`'a verir,
  entity çözer, `o:p`/ad-uzayı tolere eder → kirli Word/Cocoa HTML sağlam),
  `HtmlToUde` (bağlam+stil yığını parser; class kuralları + inline çözer),
  `NativeInsert` (ASIL ekleme yolu), `RichPaste.insertInto` (köprü). `UdeXml`
  (content.xml serializer) + `RichPaste.fromClipboardHtml` (.udf) artık yalnız
  test/yedek; ekleme YEREL yoldan. Mantık `udf-cli` (saidsurucu/udf-cli) referansı.
### GERÇEK liste işaretleri (bullet/auto-numara, 2026-06)

Eski hâl: listeler düz girintili paragraftı (işaret yok; Google Docs liste itemi
boş gelirdi → "stiller yok" şikâyetinin ASIL nedeni). Çözüm: UDE'nin gerçek liste
paragraf-özniteliklerini `NativeInsert.paraAttrs`'ta kur. Anahtarlar STRING
(`wp.model.ad` sabitleri: `"Bulleted"/"Numbered"/"BulletType"/"NumberType"/
"ListLevel"/"ListId"`). **DEĞER TİPLERİ kritik** (bytecode'dan ClassCastException
ölçümüyle bulundu): `Bulleted/Numbered`=**Boolean.TRUE**, `BulletType/NumberType`=
**String** (`BULLET_TYPE_ELLIPSE` / `NUMBER_TYPE_NUMBER_DOT`), `ListLevel`=
**Integer**, `ListId`=**Long** (Integer verirsen numara render'ı `prof.d.l.c`'de
patlar; Long verirsen bullet apply `gui.aC.a`'da patlar). Her `<ul>/<ol>`'a
benzersiz `ListId` (HtmlToUde `listIdSeq`) → numara sürekliliği.
**TUZAK — paragraf mirası:** UDE her yeni paragrafı bir öncekinin özniteliklerini
MİRAS alarak kurar → `setParagraphAttributes` **replace=TRUE** şart; `false` yalnız
EKLER, bullet bir sonraki (numara/düz) paragrafa sızar (numara yerine bullet,
"bitti." de madde işaretli; bullet+number aynı paragrafta → bullet kazanır).
replace=true'da paragraf `\n` ile sınırlanmış olmalı: önce `\n` ekle, SONRA
setParagraphAttributes (yoksa işaret sonraki içeriğe taşar).
**TUZAK — `<li><p>…</p></li>`:** Google Docs liste itemi `<li>` içinde `<p>` taşır;
naif parse boş işaretli paragraf + ayrı metin paragrafı üretir → içerik alt satıra
düşer. Çözüm `HtmlToUde.pendingList`: `<li>` işareti BEKLEMEYE alır, öğenin İLK
paragrafı (iç `<p>` ya da doğrudan metin via ensureParagraph) devralır; `</li>`'de
hâlâ pending varsa boş işaretli paragraf.

### Vurgu (highlight) rgb() boşluk tuzağı (2026-06)

Google Docs vurguyu `background-color: rgb(255, 153, 0)` ile verir. `highlight()`
çok-değerli `background` kısayolunu atlamak için boşluk içeren değeri reddediyordu —
ama `rgb(255, 153, 0)` parantez-içi boşluk taşır → vurgu DÜŞÜYORDU. Düzeltme:
boşluğu parantez DIŞINDA ara (`v.replaceAll("\\([^)]*\\)","")` sonra boşluk kontrolü).

- **NativeInsert kapsamı:** karakter (bold/italic/underline/foreground/family/size +
  **vurgu** `StyleConstants.setBackground`), paragraf (hizalama + left/right/firstLine
  girinti + spaceAbove/Below), **tablo** (DocumentEx.a + hücre doldurma), **liste**
  (GERÇEK UDE bullet/auto-numara — yukarıda; eski "1." metin yaklaşımı bırakıldı),
  **resim**
  (base64→`hj.a(BufferedImage,w,h)` caret'e, ≤480pt). `trimEmpties` baş/son/ardışık
  boş paragrafları kırpar. TUZAK: run-içi `\r\n`→boşluk (clean). TUZAK: Word vurgusu
  `mso-highlight`/`background:` kısayolu (background-color DEĞİL) → `highlight()`.
  TUZAK: resim primitifi caret'i `text.l`'ye cast eder → canlı editörde çalışır,
  DefaultCaret'li harness'te NPE (DictationFix deseni).
- **İmleç sonda (2026-06):** `insertImage` caret'i ekleme noktasına taşır; sonraki
  içerik (tablo/paragraf) `doc.insertString` ile eklenince caret onları TAKİP ETMEZ →
  imleç belge ortasında kalırdı. `NativeInsert.insert` artık eklenen toplam delta'yı
  toplayıp sonda `tc.setCaretPosition(start+delta)` yapar (resimli+tablolu yapıştırmada
  dynamic-attach ile doğrulandı: caret==len). Tablolar artık Backspace ile silinir
  (bkz. "Backspace ile tablo silme") — yapıştırma baştaki tabloya üst paragraf ekler.
- **Üzerine yapıştırma seçimi siler (2026-06-25):** `NativeInsert.insert` ekleme
  ÖNCESİ aktif seçimi `doc.remove(selS, selE-selS)` ile kaldırır, `start=selS` yapar
  (yoksa `getCaretPosition()`). Eskiden seçim bırakılıp metin imleç ucuna eklenirdi
  ("var olanı silmiyor, üstüne ekliyor"). Düz-metin yedeği (`insertPlainString`)
  zaten bu deseni kullanıyordu; rich/RTF/HTML yolu (⌘V harici + ⌘⇧V formatsız) artık
  ortak `NativeInsert`'ten kapsanır. `doc.remove` güvenli (moveDot YOK); seçim sonrası
  `snapshotParaFormat(doc,start)` ve offset-0 tablo-sentinel doğru paragraftan okur;
  `doc.remove` patlarsa catch→false→düz-metin yedeği seçimi yine siler. Test:
  `tests/RichPasteReplaceSelectionTest.java`. (codex incelemesi: ready.)
- Build: `apply_pasterich` tüm `macospasterich/*.java` derler (app-cp'siz —
  NativeInsert reflection kullanır) + jar'a enjekte + `PasteRichPatch` çalıştırır
  (gerçek fonksiyon `build.sh paste-rich` ile uçtan uca doğrulandı). Teşhis:
  `UDE_PASTERICHLOG=1` → `~/Library/Logs/ude-pasterich.txt` (`insertInto ok`). Test:
  `tests/RichPasteUdfTest.java` (dönüştürücü çıktısı). İterasyon: in-place patch +
  `codesign --force -s - --deep` + log modu (full build beklemeden); element-ağacı
  probe'u (table/cell sayımı) copy→paste düzleştirmesini kanıtlamada kilit oldu.

## Formatsız Yapıştır (PLAINPASTE=1, 2026-06)

Word'ün "Yalnızca Metni Koru"su: pano içeriğini KARAKTER STİLİ olmadan (imlecin
stilini alarak) ama **tablo/imaj/liste yapısı KORUNARAK** yapıştır. **Paragraf
biçimi (hizalama/aralık/girinti) de imlecin paragrafından alınır** — aşağıdaki
"Paragraf biçimi imleçten" notu (kaynağın sağa-yaslısı/aralığı düşer). Tetikleyici:
**⌘⇧V** + sağ tık menüsünde **"Formatsız Yapıştır"**. PASTERICH'e bağlı (yapı rich
boru hattından gelir); ikisi de varsayılan açık.

Çekirdek = PASTERICH boru hattını (HtmlToUde → NativeInsert) **düz-karakter
modunda** yeniden kullanmak. Tüm karakter stili tek noktadan
(`NativeInsert.charAttrs(TextStyle)`, hem paragraf hem tablo hücresi) uygulanır →
yalnız onu değiştir:
- `NativeInsert`: `insert(editor, model, AttributeSet cursorAttrs)` aşırı yüklemesi;
  `cursorAttrs != null` iken tek `static CURSOR_ATTRS` (insert'te **try/finally** ile
  set/temizle) üzerinden `charAttrs` stil yerine cursorAttrs döner. İki-argümanlı
  insert korunur (→ null). `insertTable`/`insertImage` DEĞİŞMEZ → yapı aynen kalır
  (`paraAttrs` paragraf biçimi için aşağıdaki nota bak).
- `RichPaste`: `insertInto(...,cursorAttrs)` ve `insertRtf(...,cursorAttrs)` aşırı
  yüklemeleri (private RTF yardımcılarını AÇMADAN — pbrich/textutil yolu aynen).
- `macospasterich.PlainPaste.paste(JTextComponent)`: pano `allHtmlFlavor` → RichPaste
  insertInto; yoksa `insertRtf` (flavor'ı içeride çözer); yoksa `stringFlavor` →
  `doc.remove`+`doc.insertString` (Document arayüzü, **cast YOK**; AbstractDocument.replace
  cast'ı PlainDocument alanında patlardı — agy bulgusu). İmleç stili KATMANLAMA:
  taban TNR 12 → caret `getCharacterElement(pos).getAttributes()` → kit
  `getInputAttributes()` (FontFamily HEP tanımlı; `JTextComponent.getCharacterAttributes()`
  YOK — o JTextPane'e özgü, agy bulgusu).

İki tetikleyici (tek implementasyon, iki çağıran):
- **Sağ tık:** menü UDE'NİN KENDİ menüsüdür (lafwidget DEĞİL — `EditContextMenuWidget`
  yanlış izdi: kullanıcının gördüğü menü Türkçe "Yapıştır" + Windows ^V hızlandırıcı
  taşıyordu, lafwidget "Paste" gösterirdi). Kaynak: `…editor.common.text.fK`
  (MouseListener, editör `fi`'ye takılı) → popup'ı `gui.dx.getPopupMenu()` kurar,
  `fK.a(MouseEvent)` `popup.show(fi, x, y)` ile gösterir (menü:
  Kes/Kopyala/Yapıştır/——/Sil/Tümünü Seç). fK/dx OBFUSCATE → `PlainPastePatch`
  metot ADIYLA DEĞİL, `JPopupMenu.show` ÇAĞRI YERİYLE hedefler (sürüm değişimine
  dayanıklı): `ExprEditor.replace` ile `PlainPaste.addMenuItem($0=popup, $1=fi)`
  sarar. addMenuItem "Yapıştır" öğesini metinle bulup ardına ekler (idempotans:
  "Formatsız Yapıştır" zaten varsa atlar) + `fixAccelerators` Windows ^X/^C/^V →
  ⌘ (`getMenuShortcutKeyMaskEx`). TUZAK: editör text bileşenleri (hj/fi/t) kendi
  MouseListener'ı taşımaz → menü kaynağı ayrı `fK` sınıfındadır (ilk recon kaçırdı).
  apply_plainpaste çıktısı `tr/` altında (lafwidget olsa `org/`).
- **⌘⇧V:** `MacShortcutRemap` `Fb.PLAIN_PASTE` + `Map(VK_V, META|SHIFT, null,…)`;
  `perform` switch → editörde `Class.forName("macospasterich.PlainPaste").paste(c)`
  (agent app-cp'siz; aynı System ClassLoader); `performLocal` → editör-dışı alanlar
  (PlainDocument; şerit arama kutusu) normal `tc.paste()` (cast hatası önlenir).

Build: `apply_pasterich` zaten `macospasterich/*.java` derleyip enjekte ettiğinden
`PlainPaste.java` otomatik dahil; `apply_plainpaste` (PASTERICH'TEN SONRA çağrılır,
PlainPaste.class şart) yalnız `PlainPastePatch`'i çalıştırır. ⌘⇧V için `build.sh
textkeys` adımı şart — `download&&patch&&lookagent&&package` textkeys'i ATLAR (agent
jar stale kalır, PLAIN_PASTE girmez); tam sıra `…textkeys; …package; sign`. Teşhis:
`UDE_PLAINPASTELOG=1` → `~/Library/Logs/ude-plainpaste.txt`. Test:
`tests/PlainPasteStripTest.java` (headless: düz-karakter modu karakter stilini düşürür,
liste korunur — javac+java elle). Spec/plan: `docs/superpowers/{specs,plans}/2026-06-1*-plain-paste*`.

### Paragraf biçimi imleçten (2026-06-25)

İlk sürüm karakter stilini düşürüyor ama paragraf biçimini (hizalama/aralık/girinti)
KAYNAKTAN alıyordu → iki-yana-yaslı + kendi aralığıyla çalışan kullanıcıya yapışan
metin sağa-yaslı + kaynak aralığıyla geliyordu. Çözüm (`NativeInsert`): düz modda
(`CURSOR_ATTRS != null`) gövde paragraflarının biçimi imlecin paragrafından alınır.
- `CURSOR_PARA_ATTRS` (CURSOR_ATTRS paragraf eşi; `insert()` `try/finally`'de set/
  **restore**). `snapshotParaFormat(doc, start)`: ekleme `start`'ında (tablo sentinel
  `insertString(0,"\n")`'den ÖNCE) imleç paragraf elementinden **beyaz-liste**
  anahtarları (`Alignment, LeftIndent, RightIndent, FirstLineIndent, SpaceAbove,
  SpaceBelow, LineSpacing, TabSet`) **`getAttribute(key)`** ile okunur (`isDefined`
  DEĞİL — stilden/`ResolveAttribute`'tan miras gelen değeri kaçırır). Beyaz liste
  char/`NameAttribute`/`ResolveAttribute` sızıntısını otomatik eler.
- `paraAttrsPlain(p)` (`insertParagraph` düz modda bunu kullanır, `replace=true`):
  liste-DIŞI paragrafta 8 anahtar tümü imleçten; **liste paragrafında** yalnız
  hizalama/aralık (`LIST_CURSOR_KEYS`) imleçten, **girinti/tabset KAYNAKTAN**
  (`paraAttrs`) — aksi halde imlecin girintisi listenin **asılı girintisini (hanging
  indent)** ezer. Liste işaretleri (`Bulleted/Numbered/…/ListLevel=Integer/ListId=Long`)
  kaynaktan.
- DEĞİŞMEZ: tablo HÜCRESİ paragraf düzeni (kaynak; `insertTable` → `paraAttrs`),
  karakter stili (her yerde imleç; hücre metni dahil), `stringFlavor` yedeği, normal
  rich paste (`cursorAttrs == null` → `CURSOR_PARA_ATTRS` null → eski yol).
- TUZAK: offset-0 tablo sentinel `start`'ı 1'e iter → snapshot sentinel'den ÖNCE
  alınmalı. Tasarım agy+codex iki tur bağımsız incelemeyle oturdu (beyaz liste,
  getAttribute, hanging indent). Test: `tests/PlainPasteParaFormatTest.java` (headless:
  gövde adopt, liste girinti regresyonu, miras hizalama, rich regresyon — javac+java
  elle). Spec/plan: `docs/superpowers/{specs,plans}/2026-06-25-plain-paste-paragraph-format*`.

## Antetlerim (ANTET=1, 2026-06)

"Arka Plan Resmi Düzenleme" (gui.gR) diyaloğuna kişisel antet bölümü.
`AntetPatch` gR.c() sonuna `macosantet.AntetUI.install(this)` ekler; helper'lar
jar'a enjekte (livetoggle deseni). Depo `~/Library/Application Support/UDE/
Antetler` (`macosantet.dir` property ile test-yönlendirilebilir); "Antet Ekle…"
kopyalar, × siler, antet butonu resmi `paperWidth/paperHeight`'a (yoksa A4
595x842 pt) contain-fit bicubic ölçekleyip gR'nin BufferedImage alanına basar
(String b=null) + `e()` (önizleme) çağırır — Tamam stok yol PNG base64 gömer.
Taşmanın kök nedeni stok yolun resmi DOĞAL boyutla gömmesiydi. Yansıma erişim:
BufferedImage alanı tipten tek; modül paneli "GÖZAT" butonlu Container alanı.
GÖZAT/kurum modülleri bilinçli stok. Teşhis: `UDE_ANTETLOG=1` →
`~/Library/Logs/ude-antet.txt`. Testler: `tests/AntetFitTest.java`,
`tests/AntetStoreTest.java` (javac+java elle). TUZAK: GUI testinden önce
PAKETLENMİŞ jar'ı doğrula (`unzip -l "$JAR"|grep -c macosantet` ≥9 +
`javap gR|grep -c AntetUI`=1) — paketlenmiş .app jar yamasız kalabilir (gR
yamalı ama macosantet yok → install NoClassDefFound, panel/log yok); temiz
`build.sh package` düzeltir.

## Metin imleci: temiz çizim + zoom kayması (CARETFIX=1, 2026-06)

İmleç "kelimelerin üzerine geliyor / boşluktaymış gibi" + "büyütüp küçültünce
kayıyor" şikâyeti. İki AYRI sorun, ikisi de `scripts/macos-caret/CaretPatch.java`
(Javassist, build-zamanı, `apply_caretfix`/CARETFIX bayrağı):

1. **Çizim biçimi** — `editor.common.s.a(Graphics,Rectangle)` (editör imleci
   `text.l` MİRAS alır, override etmez) imleci `fillRect(x+1, y+2, 2, h-3)` ile
   çiziyordu: 2px geniş + 1px sağa kaydık → macOS fractional render'ında harf
   gövdesine biniyordu. Düzeltme: `fillRect(x, y+2, 1, h-3)` (1px, kaydırmasız).
   Silme/damage bölgesi `s.a(Rectangle)` x=r.x-4/w=10 → daha dar imleç kapsanır,
   hayalet yok.

2. **Zoom kayması (KÖK NEDEN, 9-ajanlı bytecode workflow + 3/3 adversaryal
   doğrulama).** Aktif render yolu `wp.b.*` DEĞİL: canlı View ağacı
   `RootView → text.io(extends wp.prof.d.O) → text.ga → wp.prof.d.J(satır) →
   wp.prof.d.t(yaprak)`. Zoom, bölüm görünümü **`wp.prof.d.O`**'da TEK bir
   `Graphics2D.scale(s,s)` ile uygulanır; alt ağaç LOGICAL (ölçeksiz) koordinatta
   yerleşir/çizer (yazı tipi deriveFont YOK — doğrulandı). Tek köprü `wp.textUtils.p`:
   `p.a(Rect,s)` logical→device (`x=(int)(x*s)-1`), `p.c(Rect,s)` device→logical
   (`x=(int)(x/s)`); İKİSİ DE Rectangle'ı YERİNDE değiştirir.
   - `O.paint` ve `O.modelToView(5-arg, SEÇİM)` girişi önce `p.c` ile
     device→logical çevirir.
   - **`O.modelToView(3-arg, İMLEÇ)` bu `p.c`'yi ATLAR** → device alloc'ı logical
     sanıp super'a verir, çıktıyı `p.a` ile ölçekler →
     `caret_x=(int)((D+L)*s)-1` vs `paint≈D+s*L` → **kayma=(s-1)*D-1** (D=bölüm
     alloc'ının device x-orijini ~6.7px; L logical ilerleme, sadeleşir → kayma
     glyph-0'da bile var, zoom'la büyür). Ölçüm: glyph-0 +1/+1/+5/+7px
     (97/130/160/220%).
   - **Düzeltme:** 3-arg `modelToView`'a `insertBefore` ile `p.c` giriş çevirimi
     ekle (5-arg/paint kardeşini yansıt). `O.viewToModel` da EŞGÜDÜMLÜ yamalanmalı
     (noktayı zaten /s böler ama alloc'ı çevirmeden geçirir = KIRIK imleçle uyumlu;
     yamasız bırakılırsa tıklama düzeltilmiş imlece oturmaz). `getBounds()` her
     zaman KLON verir → `p.c`'nin yerinde değişimi çağıranı bozmaz. `O.modelToView/
     viewToModel` OBFUSCATE DEĞİL (kolay hedef). Sonuç: glyph-0 kayması sabit
     ~-2/-3px (zoom'dan bağımsız), tıklama round-trip 0/13 uyumsuzluk.
   - **Effect B (~0.4px/glyph, ERTELENDİ):** yaprak `wp.prof.d.A` ilerlemeyi
     statik identity-FRC `getStringBounds` ile ölçüp f2i-keser; paint ölçekli
     ekran FRC'sinde `drawString` ile ilerler → küçük per-glyph artık. Düzeltme
     noktası (paylaşılan #74 ölçüm yardımcısı) satır-kaydırma + PDF ile ORTAK →
     yüksek risk, dokunulmadı.
   - **PDF/print ETKİLENMEZ:** export'ta imleç/tıklama yok; render paint+span
     yolundan gider, `O.modelToView(3-arg)/viewToModel`'i çağırmaz. Satır-kaydırma
     da ayrı int hit-test yardımcısı (yapısal olarak ayrı).

Teşhis deseni (kilit): canlı View ağacını reflection'la yürü (yanlış `wp.b.*`
yolundan `wp.prof.d.*`'ye DÖNDÜ — statik javap yanıltır); imleç konumunu
`modelToView(off).x` vs ekrana basılan glyph ink-sol-kenarı AYNI piksel uzayında
(offscreen `comp.paint(img)` + ink tespiti) karşılaştır — ölçekli modelToView'i
ölçeksiz `getStringBounds`'la karşılaştırmak SAHTE kayma artefaktı verir (bir kez
yanılttı). Düzeltme doğrulaması: uniform 'l'×24 ile zoom süpürüp glyph-0 kaymasının
zoom-bağımsız olduğunu + viewToModel round-trip'i göster.

## Native dipnot (FOOTNOTE=1, 2026-06) — DURUM: CANLI ÇALIŞIYOR + per-sayfa yerleşim TAMAM (attach-drive + ekran görüntüsüyle doğrulandı)

UDF'de native dipnot kavramı YOK. Dipnotlar düz UDF içeriği olarak gömülür
(udf-converter-go Word→UDF çıktısıyla BİREBİR aynı biçim): gövdede üst simge
işaret (¹), sayfa dibinde ayraç (15 em-dash `—`) + 10pt blok. Böylece yamasız
editörde/Quicklook'ta da dipnot gibi görünür. Yamalı editör bunları **marker'sız
sezgisel** tanır, Word-gibi yerinde düzenletir ve debounce'lu reflow ile sayfa
diplerinde tutar. Spec/plan: `docs/superpowers/specs/2026-06-12-footnote-design.md`,
`docs/superpowers/plans/2026-06-13-footnote.md`.

### Mimari — iki kademe

1. **Saf çekirdek** (`scripts/macos-textkeys/macosfootnote/`, agent jar'a otomatik
   derlenir; tamamı headless `DefaultStyledDocument` ile test edilebilir,
   `tests/Footnote*Test.java` — javac+java elle):
   - `Superscript` — üst simge ↔ int (Go `GetFootnoteNumber` aynası; ¹²³ Latin-1,
     ⁰⁴⁵⁶⁷⁸⁹ Superscripts bloğu).
   - `FootnoteModel.scan(Document)` → `Scan{blocks,markers}`. Kurallar: **ayraç** =
     yalnız `—`, ≥10 char, ≤12pt (ya da punto bilinmiyor=0→geçer); **dipnot
     paragrafı** = ayraçtan sonra üst-simge+boşluk ile başlayan, ≤12pt, **numara
     ≥1** (0 geçersiz); **işaret** = blok DIŞINDA, numarası bir blok notuyla
     eşleşen üst-simge dizisi (m² gibi sahteler dokunulmaz).
   - `FootnoteRenumber.reconcile` — işaretleri belge sırasına göre 1..n; sahipsiz
     not atılır; `markerOffsetToNewNumber` döner.
   - `FootnoteReflow.strip/place/plan` — blok söküm/yerleştirme/planlama. **Round-trip
     simetrisi:** `place` = `[dolgu \n]×N + ayraç\n + (ⁿ metin\n)… + (sayfa sonu \f\n)`;
     `strip` bloğu + ayraçtan ÖNCEKİ boş dolgu paragraflarını + sonraki yalnız-`\f`
     paragrafını da siler. KASITLI değişmez: `String.trim()` `\f`'yi boşluk sayar →
     önceki bloğun `\f`'sini sonraki bloğun dolgusu yutar; strip SONDAN başa
     sildiği için çift-sayım olmaz (`extendLeadingPadding`/`extendTrailingPageBreak`
     birlikte değişirse dikkat).
2. **Agent entegrasyonu** (aynı paket): `SwingPageGeometry` (canlı `modelToView`).
   **İKİ reflow modu (KRİTİK — veri kaybı dersi):**
   - `FootnoteReflow.reflow(editor)` = **HAFİF** (`reflowDocEnd`): numara senkronu +
     TÜM notlar tek blok BELGE SONUNDA (dolgu yok, min mutasyon). Debounce + Dipnot
     Ekle bunu kullanır. EDİTLEMEDE budur — ağır churn yok.
   - `FootnoteReflow.reflowToPages(editor)` = **TAM** (`reflowDocPages`): per-sayfa
     dolgu (aşağıda). YALNIZ "Dipnotları Yerleştir" düğmesi + kayıt anında.
   - **NEDEN:** debounce'ta TAM per-sayfa çalıştırmak (~50 dolgu satırı strip+yeniden
     dolgu) yazarken belgeyi churn'leyip kullanıcının yazdığını siliyordu ("ikinci
     sayfaya geçince bir süre sonra"). Kullanıcının onayladığı tasarım: editlemede
     dipnotlar belge sonunda (temiz), sonlandırınca/kayıtta sayfa dibine.
   `reflowDocPages` (headless test: `tests/FootnoteReflowDocTest.java`).
   Akış: scan→renumber→strip→ölç→plan→place;
   tek `inReflow` guard; ≤3 geçiş ama v1'de `stable()`=true → 1 geçiş. **KRİTİK gotcha:**
   strip blokları silince blok kalmaz → `FootnoteModel.findMarkers` blok-gate'li olduğu
   için 0 işaret bulur → 0 yerleşim → DİPNOTLAR SİLİNİR (veri kaybı; ilk sürümde vardı,
   yakalandı). Çözüm: strip SONRASI işaret offset'leri **blok-gate'siz**
   `FootnoteModel.findMarkersByNumbers(doc, yeniNumaraKümesi)` ile bulunur; yerleştirilen
   notlar `reconcile`'ın `rr.ordered`'ı (yeniden numaralı, belge sırası) — strip'in
   döndürdüğü ESKİ-numaralı liste DEĞİL (yoksa numara/eşleşme bozulur). `FootnoteActions`
   (Dipnot Ekle / Dipnotları Yerleştir; ekleme numarası = mevcut TÜM numaraların
   max+1 → silme-sonrası çakışma/metin kaybı yok), `FootnoteInstall` (FOCUS_GAINED'de
   editöre tak: ~1500ms debounce reflow Timer + çift-tık gezinme + şerit düğmeleri
   ribbon MODELİNDEN — darkpage deseni). `MacTextKeys.install()` → `FootnoteInstall.install()`.
   Tüm mutasyonlar `AbstractDocument.replace`/`StyledDocument` (moveDot NPE YASAK).

### Kayıt-anı senkron reflow (Javassist) — FN_SAVE_CLASS bekliyor

`scripts/macos-footnote/FootnotePatch.java`: kayıt action'ının `actionPerformed`'ine
insertBefore ile reflection üzerinden `FootnoteReflow.reflow(odaklı editör)` çağrılır
(dosyaya hep doğru yerleşim yazılsın). Kayıt action'ının obfuscate sınıf/metot adı
HENÜZ BİLİNMİYOR → `FN_SAVE_CLASS` env ile verilir; **verilmezse apply_footnote
kancayı ATLAR** (agent reflow yine çalışır: debounce + "Dipnotları Yerleştir"
düğmesi). Yani build FN_SAVE_CLASS olmadan da sağlam çıkar. İdempotans: save sınıfı
zaten `macosfootnote/FootnoteReflow` referansı içeriyorsa yeniden yamalanmaz.

### Canlı build bulguları (2026-06-13, tam build + launch)

- **Editör sınıfı:** `tr.com.havelsan.uyap.system.editor.common.text.t` (FOCUS_GAINED'de
  `FootnoteInstall.attach` buldu; debounce reflow + çift-tık gezinme bu bileşene takılı).
- **TUZAK (paketleme):** `textkeys()` `find … *.java` ile TÜM paketleri derler ama
  jar satırı yalnız `macostextkeys`'i paketliyordu → `macosfootnote` sınıfları jar'da
  YOK → `MacTextKeys.install()` `FootnoteInstall`'ı çağırınca premain'de
  NoClassDefFoundError → `-javaagent` yüklenemez → **JVM açılışta düşer** (FATAL,
  processJavaStart failed). Düzeltme: jar satırına `macosfootnote` eklendi. Headless
  derleme bunu yakalamaz (yalnız jar paketleme); ancak tam build+launch yakalar.
- **TUZAK (şerit — düğmeler EKLE sekmesinde):** Flamingo bir band'de `JRibbonComponent`
  (sarmalı Swing) ile özel komut-butonlarını KARIŞTIRMAYA izin vermez →
  `UnsupportedOperationException: Ribbon band groups do not support mixing …`. "Ekle"
  sekmesi bandları komut-buton bandı → JRibbonComponent reddeder (darkpage'in
  JRibbonComponent yolu burada ÇALIŞMAZ). Çözüm: NATIVE `JCommandButton(String,
  ResizableIcon)` + `band.addCommandButton(btn, RibbonElementPriority.TOP/MEDIUM)`
  (`FootnoteInstall.ensureRibbon`, hepsi yansıma). "Dipnot Ekle"/"Dipnotları Yerleştir"
  artık Ekle sekmesinde (Tablo Ekle yanında). Band = Ekle task'ının ilk bandı.
- **TUZAK (ikon bulanık):** `ImageWrapperResizableIcon.getIcon(img, dim)` ile raster
  32px ikon, büyük (TOP) komut butonunda + Retina'da BÜYÜTÜLÜP bulanıklaşıyordu
  (kullanıcı bildirdi). Çözüm: VEKTÖR ikon — `java.lang.reflect.Proxy` ile Flamingo
  `ResizableIcon` (= Icon + setDimension) arayüzü derleme-zamanı görmeden uygulanır;
  `paintIcon` gerçek Graphics'e o anki boyutta vektör çizer (her ölçek/DPI'de keskin).
  `makeFootnoteIcon`/`paintFootnote`. Glyph: metin satırları + mavi üst-simge "1" +
  ayraç; "yerleştir" varyantında yeşil aşağı ok.
- **TUZAK (ayraç yapışması):** gerçek editör belgesi `\n` ile bitmiyordu →
  `place()` ayracı gövde paragrafına yapıştırdı → `isSeparator` tanımadı → dipnot
  GÖRÜNMEDİ (headless test \n'li gövdelerle maskeliyordu). Çözüm: place() ayraçtan
  önce, insertOffset satır başında değilse `\n` ekler.
- **PER-SAYFA YERLEŞİM (converter deseni, page-break İŞARETSİZ) — ÇALIŞIYOR:**
  UDE düz akış + render-anı sayfalama; sayfa sınırı SAF GÖVDE bir kez modelToView ile
  ölçülerek (yTop/yBottom) bulunur. `reflowDocPages`: scan→renumber→strip→paragrafları
  yukarıdan aşağı dolaş, yükseklik biriktir, dipnot için yer AYIR (footnoteH); gövde+
  dipnot sayfayı aşacaksa o paragraftan ÖNCE kır, önceki paragrafların sonuna o
  sayfanın dipnot bloğunu DOLGUYLA (sayfa dibine) koy; bloklar SONDAN başa eklenir
  (offset kaymasın). Dolgu sonraki gövdeyi doğal olarak sonraki sayfaya iter — page-
  break İŞARETİ YOK (kullanıcı kararı). KRİTİK: dolgu YUKARI yuvarlanmalı
  (`(num+lineH-1)/lineH`) yoksa altına bir gövde satırı SIZAR (kullanıcı ekran
  görüntüsüyle yakalandı). Geometri `SwingPageGeometry`: pitch (~1000px @%83) ardışık
  paragraf y-SIÇRAMASINDAN, contentH = son-satır-dibi − üst-marj, lineH = en sık delta;
  ≤1 sayfa belgede PageFormat (editor.a() → wp.model.P) × önbellekli ölçek yedeği.
- **agy bağımsız incelemesi (needs-revision → çözüldü):** #1 long-paragraph -1 ve #2
  modelToView bayatlığı, "saf gövdeyi BİR KEZ ölç + sondan-başa ekle" tasarımıyla
  giderildi (ekleme-sonrası yeniden ölçme YOK). #3 cachedScale zoom: çok-sayfada pitch
  tazelenir; tek-sayfa+zoom değişiminde küçük sapma (kabul). #4 (dolgu kırılganlığı):
  kullanıcı page-break istemediğinden dolgu tek seçenek; tek-ölçüm + ceil ile sağlamlaştı.
- **CANLI DOĞRULAMA (attach-drive, DictSim deseni):** `/tmp/FnDrive` agent'ı çalışan
  JVM'de `FootnoteActions.addFootnote` + `FootnoteReflow.reflow`'u GERÇEK editörde
  (text.t) EDT'de sürer, sonra `FootnoteModel.scan` ile yapıyı doğrular — sentetik
  klavye YOK. Doğrulandı: dipnot ekleme (ayraç+blok tanınıyor), dipnota metin yazma,
  ardışık numaralandırma (¹²), işaret silince reflow renumber + sahipsiz not düşme.
  Sonuç deseni: `gövde¹.\n———\n¹ metin\n² …\n` (belge sonunda, dolgusuz).
- Hızlı test düzeneği: worktree `downloads/`'u ana repoya symlink (240MB yeniden
  indirme yok); `bash scripts/build.sh package && … sign` agent değişikliğini hızlı
  yeniden paketler; doğrudan binary + `UDE_FNLOG=1`. Attach-drive: zulu-21
  `VirtualMachine.attach(pid).loadAgent` (agent --release 11 derlenmeli — hedef Zulu 11).
- **Görsel doğrulama (odak çalmadan):** swift `CGWindowListCopyWindowInfo` ile UDE
  pid'inin en büyük penceresinin id'si → `screencapture -x -o -l<id> out.png` → yalnız
  o pencere (Retina 2x). Ekle sekmesini probe ile seçtir (`setSelectedTask`), sonra
  yakala; ikon keskinliği `magick -crop … -resize` ile 1:1 incelenir.
- **Attach-probe TUZAKLARI (tekrar yaşandı):** (1) anonim iç sınıfları jar'a koymayı
  unutma → `NoClassDefFoundError: X$1`; en sağlamı agent'ı TEK sınıf yap (Runnable'ı
  kendisi implement etsin, `new Agent()` ile invokeLater). (2) bozuk jar bir kez
  yüklenince sınıf classloader'da TAKILI kalır → düzeltilmiş jar'ı AYNI adla yüklemek
  yine patlar → sınıf adını değiştir (FnSel→FnSelB) ya da uygulamayı yeniden başlat.

### BEKLEYEN / sınırlar

- **Keşif (recon):** (a) editör sınıfı `…text.t` ve sayfa modeli `editor.a()→wp.model.P`
  bulundu. (c) düz UDF kayıt action sınıf/metot (→ FN_SAVE_CLASS) hâlâ açık; kanca
  atlanıyor ama gerek yok: kayıt-anı reflow yerine debounce + "Dipnotları Yerleştir"
  düğmesi + ekleme-anı reflow zaten doğru dosya üretiyor.
- **Bilinen sınırlar:** dipnot yüksekliği satır-başına 1 satır olarak tahmin edilir
  (uzun/sarılan not hafif fazla taşabilir); footnote 10pt satır yüksekliği gövde lineH'i
  ile yaklaşıklanır. Düz metin dipnot (10pt TNR). Tek-sayfa+zoom değişiminde küçük
  geometri sapması. Düzen dolguyla yarı-donar (sonraki editlemede reflow düzeltir).

Teşhis: `UDE_FNLOG=1` → `~/Library/Logs/ude-footnote.txt`.

## Teşhis cephaneliği

- **Yamasız uygulamaya agent takma:** `JAVA_TOOL_OPTIONS=-javaagent:/tmp/dbg.jar` —
  jpackage launcher'ı saygı duyar. LAF kim kurdu/devirdi:
  `UIManager.addPropertyChangeListener("lookAndFeel")` + stack trace dump.
- **Çağıran avı (obfuscate):** jar'ı aç, `LC_ALL=C grep -rl --binary-files=text
  'setSkin' <dizin>` → constant-pool eşleşmesi çağıranı verir; sonra `javap -c -p`.
- **Bileşen keşfi:** premain'de gecikmeli component-tree dökümü (sınıf adı + bounds +
  bg) — cetvel böyle bulundu.
- **Ekran doğrulama (ZORUNLU KURAL):** Ekran görüntüsü ALIRKEN YALNIZ UDE app
  penceresini çek — ASLA tüm ekranı/bölgeyi değil (kullanıcının diğer pencereleri
  = gizlilik). `swift` ile `CGWindowListCopyWindowInfo`'dan UDE pencere ID al,
  `screencapture -x -l<ID>` ile SADECE o pencereyi yakala (odak da çalınmaz).
  `screencapture` argümansız / `-R bölge` / tam-ekran KULLANMA.
  Renkler `magick … '%[pixel:p{x,y}]'` ile ölçülür; "değişiklik etkisiz" iddiasını
  piksel ölçümü keser. **sips cropOffset sırası `<y> <x>`'tir** (saatler kaybettirdi).
- Hover/pressed gibi durumlar ekran-dışı doğrulanır: bileşeni `model.setRollover(true)`
  ile kur, `panel.paint(g2)` → PNG; gerçek hover animasyonu tam şema değerine gider,
  statik probe ara kare yakalar.

## Genel Javassist/build tuzakları

- `insertBefore/setBody/replace` string'lerinde `//` yorum YASAK (newline yok, gövde
  yutulur, CannotCompileException).
- Aynı CtClass'a ikinci `writeClass` öncesi tüm yamaları bitir ("class is frozen").
- bash 3.2 + `set -u`: boş dizi `${arr[@]+"${arr[@]}"}` ile genişletilir.
- jpackage `-javaagent` satırı jar yoksa JVM'i HİÇ başlatmaz → agent opsiyonelse
  java-options koşullu eklenir (`lookopts` deseni).
- `fullWindowContent`/`transparentTitleBar` rootpane client property'leri Zulu 11'de
  pencere açıldıktan SONRA da etkili (FwcProbe ile kanıtlı); trafik ışıkları için
  JRibbon'a 72px sol içlik.
