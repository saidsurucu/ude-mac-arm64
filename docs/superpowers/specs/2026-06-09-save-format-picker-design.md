# Native Kaydet Penceresinde Format Seçimi — Tasarım

**Tarih:** 2026-06-09
**Dal:** `feature/save-format-picker`
**Dosya:** `scripts/macos-filedialog/macosfiledialog/MacFileDialog.java`

## Problem

UDE'nin "Farklı Kaydet" / "PDF olarak kaydet" işlemleri build-time Javassist yamasıyla
`java.awt.FileDialog` (NSSavePanel) köprüsüne yönlendiriliyor. `java.awt.FileDialog`,
macOS NSSavePanel'deki "Dosya Formatı" açılır menüsü gibi bir **accessory view**'ı
desteklemez. Önceki düzeltme (2026-06-09 öncesi, `.rtf`/`.xml` açma sorununu çözmek için)
tüm filtreleri kaldırdığından, kullanıcı artık çıktı formatını (UDF/XML/USF/RTF/PDF)
**hiçbir yerden seçemiyor**.

UDE çıktı formatını seçili `FileFilter` (`fc.getFileFilter()`) üzerinden belirler — bu,
`tr.com.havelsan.uyap.system.editor.common.text.cA` ve `…common.gui.fs` sınıflarının
decompile incelemesiyle doğrulandı (bağımsız ikinci görüş / agy). Filtre kaldırıldığı için
artık format kontrolü kayboldu.

## Çözüm Özeti

SAVE modunda, native panel açılmadan **önce** küçük modal bir Swing penceresi
(`JOptionPane` + combobox) format seçtirir. Seçim `fc.setFileFilter(...)`'a yazılır
(UDE'nin yazıcıyı seçmesi için) ve dönen dosya adına seçilen formatın uzantısı zorlanır.
LOAD modu değişmez.

## Akış

`MacFileDialog.show(fc, parent, mode)` içinde, **yalnızca `mode == FileDialog.SAVE`** için:

1. Gerçek (accept-all olmayan) choosable filtreleri topla: `fc.getChoosableFileFilters()`.
2. Filtre sayısına göre dallan:
   - **0 filtre** → pre-dialog yok, uzantı zorlama yok (bugünkü davranış, regresyon yok).
   - **1 filtre** → pre-dialog yok; aktif filtreden (`fc.getFileFilter()`, yoksa o tek filtre)
     uzantı çözülür ve zorlanır.
   - **2+ filtre** → pre-dialog gösterilir.
3. **Pre-dialog**: combobox choosable filtrelerden dinamik dolar. Varsayılan seçim,
   belgenin mevcut uzantısına (`docNameFromTitle` çıktısının uzantısı) uyan filtre; yoksa ilk.
   - **İptal / pencere kapatma** → `JFileChooser.CANCEL_OPTION`, native panel hiç açılmaz.
   - **Tamam** → seçilen `FileFilter` → `fc.setFileFilter(chosen)`.
4. **Hedef uzantı** belirle: pre-dialog gösterildiyse seçilen filtreden, gösterilmediyse
   aktif filtreden — her ikisi de **probe** ile (aşağıda).
5. Hedef uzantı **iki yerde** uygulanır:
   - (a) Native panele ön-doldurulan dosya adı (`fd.setFile`) — `forceExtension` ile.
   - (b) Panel kapanınca dönen dosya adı — `forceExtension` ile.
6. Hedef uzantı çözülemezse (tüm filtreler bilinmeyen) uzantı zorlama atlanır, ham ad kullanılır.

## Uzantı Normalleştirme — `forceExtension(name, ext)`

- Ad sonundaki **bilinen UDE uzantısını** (`udf`, `rtf`, `pdf`, `xml`, `usf`) büyük-küçük
  harf duyarsız olarak keser, sonra `.ext` ekler.
- `belge.udf` + `xml` → `belge.xml`; `belge` + `udf` → `belge.udf`;
  `belge.2024.udf` + `rtf` → `belge.2024.rtf` (yalnız son bilinen uzantı değişir, ara nokta korunur).
- Hedef uzantı zaten doğruysa no-op.
- **Gerekçe (çift uzantı kusuru):** native panel başlıktan gelen `belge.udf` ile ön-dolduğu
  için, strip yapılmazsa `belge.udf.xml` oluşurdu.

## Format Etiketleri ve Probe

`FileFilter` arayüzü yalnız `accept()`/`getDescription()` verir; obfuscated `gui.fs`
filtresinin uzantısı içeriden okunamaz. Bu yüzden **probe**:

- Bilinen uzantı tablosu (sıralı): `udf, rtf, pdf, xml, usf`.
- Her filtre için her aday uzantıyı dene: `ff.accept(new File("p." + ext))`. Kabul edilen
  ilk uzantı o filtrenin uzantısıdır.
- Dostça etiket haritası (uzantıyla anahtarlanır):
  | Uzantı | Etiket |
  |--------|--------|
  | udf | UDF Belgesi (.udf) |
  | rtf | Word / RTF (.rtf) |
  | pdf | PDF (.pdf) |
  | xml | XML (.xml) |
  | usf | USF (.usf) |
- Probe'a uymayan filtre → yedek etiket: `getDescription()` (boşsa "Bilinmeyen").
- Combobox öğesi görünür etiketi + arkadaki `FileFilter` nesnesini birlikte taşır;
  seçilince doğrudan `fc.setFileFilter`'a verilir.

Tüm yardımcılar `MacFileDialog` içinde saf-JDK kalır (yeni bağımlılık yok, mevcut enjeksiyon
hattıyla uyumlu).

## Entegrasyon Noktaları

- Mantık tek özel `show(fc, parent, mode)` içinde; `showSave` ve `showCustom` (SAVE tipi)
  buradan geçer → otomatik kapsanır.
- `showOpen` / LOAD modu **değişmez** (mevcut `matchChoosableFilter` davranışı korunur).
- SAVE'de çoklu seçim yoktur; mevcut multi-select dalı yalnız LOAD'da anlamlı, dokunulmaz.

## EDT / Threading

`show()` zaten EDT'de (UDE action'ı) çalışır. `JOptionPane.showOptionDialog` modal → kapanır →
ardından `fd.setVisible(true)` modal. İç içe değil **ardışık** modal; deadlock/reentrancy yok
(bağımsız inceleme soru 2'yi sorunsuz saydı).

## Test

- Mevcut `scripts/macos-filedialog/test/MacFileDialogFilterTest` yanına saf-JDK birim testleri:
  - `forceExtension`: çift uzantı, eksik uzantı, case-insensitive strip, ara-nokta korunması, no-op.
  - Probe-tabanlı uzantı çözümü: sahte `FileFilter` nesneleriyle (udf/rtf/pdf eşleşmeleri,
    bilinmeyen filtre yedek davranışı).
- Native panel ve pre-dialog GUI otomatik test edilmez. **Manuel doğrulama:** UDF/RTF/PDF ile
  "Farklı Kaydet" ve "PDF olarak kaydet"; çıktı dosyasının uzantısı + UDE'nin gerçekten o
  formatta yazması (rtf'i Word'de açma, pdf geçerliliği).

## Kapsam Dışı

- Native NSSavePanel accessory view (gerçek dropdown) — JNI/Obj-C gerektirir, kapsam dışı.
- LOAD davranışı değişikliği.
- Cmd+V / modal panel klavye sınırları (ayrı bilinen konu).

## Doğrulanmış Varsayım

UDE kaydetme yazıcısını `fc.getFileFilter()`'a göre seçer — `cA`/`gui.fs` decompile
incelemesiyle teyit edildi. Uygulama sırasında manuel kaydetme testiyle son kez doğrulanacak.
