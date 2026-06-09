# Native Kaydet Format Seçimi — Uygulama Planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SAVE modunda native panel açılmadan önce format (UDF/XML/USF/RTF/PDF) seçtiren bir pencere ekle ve dönen dosya adına seçilen formatın uzantısını zorla.

**Architecture:** Tüm mantık `MacFileDialog.java` içinde, saf-JDK statik yardımcılar (`forceExtension`, `probeExtension`, `knownExtOf`, `friendlyLabel`) + GUI tutkalı (`promptFormat`, `show()` entegrasyonu). LOAD davranışı değişmez. Yardımcılar paket-özel (default access) yapılır ki mevcut başsız test harness'i (`MacFileDialogFilterTest`) çağırabilsin.

**Tech Stack:** Java 11 (`javac --release 11`), Swing (`JOptionPane`, `JComboBox`), `java.awt.FileDialog`. Test: el harness'i (`main()` + `check()`), GUI yok.

---

## Dosya Yapısı

- **Modify:** `scripts/macos-filedialog/macosfiledialog/MacFileDialog.java` — yeni yardımcılar + `show()` entegrasyonu + `promptFormat` + `FormatItem` iç sınıfı.
- **Modify (test):** `scripts/macos-filedialog/test/MacFileDialogFilterTest.java` — `forceExtension` ve `probeExtension` için yeni `check()` çağrıları.

Test derleme/çalıştırma (her görevde aynı):

```bash
rm -rf /tmp/fdtest && mkdir -p /tmp/fdtest
javac -d /tmp/fdtest \
  scripts/macos-filedialog/macosfiledialog/MacFileDialog.java \
  scripts/macos-filedialog/test/MacFileDialogFilterTest.java
java -cp /tmp/fdtest macosfiledialog.MacFileDialogFilterTest
```

`MacFileDialog` yalnız JDK sınıfları (java.awt/javax.swing) kullandığından JAR olmadan derlenir.

---

### Task 1: `forceExtension` + `knownExtOf` + `KNOWN_EXTS`

`forceExtension`, `knownExtOf`'a bağlı olduğundan birlikte uygulanır.

**Files:**
- Modify: `scripts/macos-filedialog/macosfiledialog/MacFileDialog.java`
- Test: `scripts/macos-filedialog/test/MacFileDialogFilterTest.java`

- [ ] **Step 1: Başarısız testleri ekle**

`MacFileDialogFilterTest.java` içinde, `if (failures > 0)` satırının **hemen üstüne** şu satırları ekle:

```java
        check("forceExtension udf->xml strip",
              MacFileDialog.forceExtension("belge.udf", "xml").equals("belge.xml"));
        check("forceExtension uzantisiz ekler",
              MacFileDialog.forceExtension("belge", "udf").equals("belge.udf"));
        check("forceExtension ara nokta korunur",
              MacFileDialog.forceExtension("belge.2024.udf", "rtf").equals("belge.2024.rtf"));
        check("forceExtension buyuk-kucuk harf",
              MacFileDialog.forceExtension("Belge.UDF", "xml").equals("Belge.xml"));
        check("forceExtension ayni uzanti no-op",
              MacFileDialog.forceExtension("belge.xml", "xml").equals("belge.xml"));
        check("forceExtension bilinmeyen uzanti korunur",
              MacFileDialog.forceExtension("belge.docx", "udf").equals("belge.docx.udf"));
```

- [ ] **Step 2: Testin DERLENMEDİĞİNİ doğrula**

Run: yukarıdaki test derleme/çalıştırma bloğu.
Expected: FAIL — `javac` "cannot find symbol: method forceExtension" der (henüz yok).

- [ ] **Step 3: Asgari uygulamayı yaz**

`MacFileDialog.java` içinde, `private MacFileDialog() {}` satırının altına ekle:

```java
    /** Format/uzantı zorlamada tanınan UDE uzantıları (sıra = probe önceliği). */
    private static final String[] KNOWN_EXTS = {"udf", "rtf", "pdf", "xml", "usf"};

    /** Ad sonundaki bilinen UDE uzantısını (case-insensitive) döndürür; yoksa null. */
    static String knownExtOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        for (String k : KNOWN_EXTS) if (k.equals(ext)) return ext;
        return null;
    }

    /**
     * Dosya adının uzantısını hedef uzantıya getirir: ad sonundaki bilinen UDE
     * uzantısını (varsa) söküp ".ext" ekler. Yalnız bilinen uzantı sökülür; ara
     * noktalar ve bilinmeyen uzantılar korunur. Çift uzantıyı (belge.udf.xml) önler.
     */
    static String forceExtension(String name, String ext) {
        if (name == null || ext == null || ext.isEmpty()) return name;
        String known = knownExtOf(name);
        String base = (known != null) ? name.substring(0, name.length() - known.length() - 1) : name;
        return base + "." + ext;
    }
```

- [ ] **Step 4: Testin GEÇTİĞİNİ doğrula**

Run: test derleme/çalıştırma bloğu.
Expected: PASS — "Tum testler GECTI", forceExtension satırları PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-filedialog/macosfiledialog/MacFileDialog.java scripts/macos-filedialog/test/MacFileDialogFilterTest.java
git commit -m "feat(filedialog): forceExtension/knownExtOf uzanti normallestirme + test"
```

---

### Task 2: `probeExtension`

**Files:**
- Modify: `scripts/macos-filedialog/macosfiledialog/MacFileDialog.java`
- Test: `scripts/macos-filedialog/test/MacFileDialogFilterTest.java`

- [ ] **Step 1: Başarısız testleri ekle**

`MacFileDialogFilterTest.java` içinde, Task 1'de eklenen `forceExtension` check'lerinin **altına** (yine `if (failures > 0)` üstünde) ekle:

```java
        check("probeExtension rtf filtresi",
              "rtf".equals(MacFileDialog.probeExtension(rtf)));
        check("probeExtension udf filtresi",
              "udf".equals(MacFileDialog.probeExtension(udf)));
        check("probeExtension null filtre null",
              MacFileDialog.probeExtension(null) == null);
        FileNameExtensionFilter docx = new FileNameExtensionFilter("Word [.docx]", "docx");
        check("probeExtension bilinmeyen uzanti null",
              MacFileDialog.probeExtension(docx) == null);
```

(`rtf`, `udf` filtreleri dosyanın başında zaten tanımlı.)

- [ ] **Step 2: Testin DERLENMEDİĞİNİ doğrula**

Run: test derleme/çalıştırma bloğu.
Expected: FAIL — `javac` "cannot find symbol: method probeExtension" der.

- [ ] **Step 3: Asgari uygulamayı yaz**

`MacFileDialog.java` içinde `forceExtension`'ın altına ekle:

```java
    /**
     * Filtrenin uzantısını probe ile bulur: bilinen her uzantı için
     * ff.accept(new File("p."+ext)) dener, kabul edilen ilk uzantıyı döndürür.
     * Obfuscated/özel FileFilter alt sınıflarında da çalışır (yalnız accept()'e dayanır).
     * accept-all filtreleri çağıran taraf zaten dışlar.
     */
    static String probeExtension(FileFilter ff) {
        if (ff == null) return null;
        for (String ext : KNOWN_EXTS) {
            if (ff.accept(new File("p." + ext))) return ext;
        }
        return null;
    }
```

- [ ] **Step 4: Testin GEÇTİĞİNİ doğrula**

Run: test derleme/çalıştırma bloğu.
Expected: PASS — probeExtension satırları PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-filedialog/macosfiledialog/MacFileDialog.java scripts/macos-filedialog/test/MacFileDialogFilterTest.java
git commit -m "feat(filedialog): probeExtension filtre-uzanti cozumu + test"
```

---

### Task 3: `friendlyLabel` + `FormatItem`

GUI'siz, başsız test edilebilir küçük yardımcılar.

**Files:**
- Modify: `scripts/macos-filedialog/macosfiledialog/MacFileDialog.java`
- Test: `scripts/macos-filedialog/test/MacFileDialogFilterTest.java`

- [ ] **Step 1: Başarısız testleri ekle**

`MacFileDialogFilterTest.java` içinde, probeExtension check'lerinin altına ekle:

```java
        check("friendlyLabel udf etiketi",
              "UDF Belgesi (.udf)".equals(MacFileDialog.friendlyLabel("udf")));
        check("friendlyLabel rtf etiketi",
              "Word / RTF (.rtf)".equals(MacFileDialog.friendlyLabel("rtf")));
        check("friendlyLabel bilinmeyen null",
              MacFileDialog.friendlyLabel("docx") == null);
        check("friendlyLabel null null",
              MacFileDialog.friendlyLabel(null) == null);
```

- [ ] **Step 2: Testin DERLENMEDİĞİNİ doğrula**

Run: test derleme/çalıştırma bloğu.
Expected: FAIL — "cannot find symbol: method friendlyLabel".

- [ ] **Step 3: Asgari uygulamayı yaz**

`MacFileDialog.java` içinde `probeExtension`'ın altına ekle:

```java
    /** Uzantı için kullanıcıya gösterilecek dostça etiket; bilinmeyen/null → null. */
    static String friendlyLabel(String ext) {
        if (ext == null) return null;
        switch (ext) {
            case "udf": return "UDF Belgesi (.udf)";
            case "rtf": return "Word / RTF (.rtf)";
            case "pdf": return "PDF (.pdf)";
            case "xml": return "XML (.xml)";
            case "usf": return "USF (.usf)";
            default: return null;
        }
    }

    /** Format penceresi combobox öğesi: görünür etiket + arkadaki filtre + uzantı. */
    private static final class FormatItem {
        final String label;
        final FileFilter filter;
        final String ext;
        FormatItem(String label, FileFilter filter, String ext) {
            this.label = label; this.filter = filter; this.ext = ext;
        }
        @Override public String toString() { return label; }
    }
```

- [ ] **Step 4: Testin GEÇTİĞİNİ doğrula**

Run: test derleme/çalıştırma bloğu.
Expected: PASS — friendlyLabel satırları PASS. (FormatItem henüz kullanılmıyor; `private` olduğu için "unused" uyarısı sorun değil.)

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-filedialog/macosfiledialog/MacFileDialog.java scripts/macos-filedialog/test/MacFileDialogFilterTest.java
git commit -m "feat(filedialog): friendlyLabel + FormatItem combobox ogesi"
```

---

### Task 4: `promptFormat` + `show()` entegrasyonu (GUI tutkalı)

GUI içerdiğinden otomatik test edilmez; başsız test harness'i regresyonsuz geçmeli + manuel doğrulama.

**Files:**
- Modify: `scripts/macos-filedialog/macosfiledialog/MacFileDialog.java`

- [ ] **Step 1: `promptFormat` yardımcısını ekle**

`MacFileDialog.java` içinde `FormatItem` iç sınıfının altına ekle:

```java
    /**
     * Native panel açılmadan önce format seçtiren modal pencere.
     * filters: accept-all olmayan choosable filtreler (>=2). current: o an seçili filtre.
     * currentFileName: ön-doldurulan ad (varsayılan seçimi belgenin uzantısına çekmek için).
     * Dönüş: seçilen FileFilter; İptal/kapatma → null (kaydetme iptal).
     */
    private static FileFilter promptFormat(Window owner, java.util.List<FileFilter> filters,
                                           FileFilter current, String currentFileName) {
        FormatItem[] items = new FormatItem[filters.size()];
        int defaultIdx = 0;
        for (int i = 0; i < filters.size(); i++) {
            FileFilter ff = filters.get(i);
            String ext = probeExtension(ff);
            String label = friendlyLabel(ext);
            if (label == null) {
                String desc = ff.getDescription();
                label = (desc != null && !desc.isEmpty()) ? desc : "Bilinmeyen";
            }
            items[i] = new FormatItem(label, ff, ext);
            if (current != null && ff.equals(current)) defaultIdx = i;
        }
        // Belgenin mevcut uzantısına uyan formatı, seçili filtreye tercih et.
        String curExt = knownExtOf(currentFileName);
        if (curExt != null) {
            for (int i = 0; i < items.length; i++) {
                if (curExt.equals(items[i].ext)) { defaultIdx = i; break; }
            }
        }
        javax.swing.JComboBox<FormatItem> combo = new javax.swing.JComboBox<FormatItem>(items);
        combo.setSelectedIndex(defaultIdx);
        int res = javax.swing.JOptionPane.showOptionDialog(
            owner, combo, "Kaydetme Biçimi",
            javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE,
            null, new Object[]{"Tamam", "İptal"}, "Tamam");
        // options verildiğinde dönüş = seçilen seçeneğin indeksi (0=Tamam) veya CLOSED_OPTION(-1).
        if (res != 0) return null;
        FormatItem sel = (FormatItem) combo.getSelectedItem();
        return (sel != null) ? sel.filter : null;
    }
```

- [ ] **Step 2: `show()` içine SAVE format bloğunu ekle**

`show()` içinde, dosya adı ön-doldurma bloğunun bittiği yere — `if (fc.isMultiSelectionEnabled()) fd.setMultipleMode(true);` satırının **hemen üstüne** — ekle:

```java
            // SAVE: native panelde format dropdown'ı yok. 2+ filtre varsa önce
            // format seçtir; tek filtre varsa sessizce uzantıyı belirle. Hedef
            // uzantı hem ön-doldurulan ada hem dönen ada (aşağıda) zorlanır.
            String targetExt = null;
            if (mode == FileDialog.SAVE) {
                java.util.List<FileFilter> real = new java.util.ArrayList<FileFilter>();
                FileFilter acceptAll = fc.getAcceptAllFileFilter();
                FileFilter[] choosable = fc.getChoosableFileFilters();
                if (choosable != null) {
                    for (FileFilter ff : choosable) {
                        if (ff != null && !ff.equals(acceptAll)) real.add(ff);
                    }
                }
                if (real.size() >= 2) {
                    FileFilter chosen = promptFormat(owner, real, fc.getFileFilter(), fd.getFile());
                    if (chosen == null) {
                        log("format penceresi iptal (mode=SAVE)");
                        return JFileChooser.CANCEL_OPTION;
                    }
                    fc.setFileFilter(chosen);
                    targetExt = probeExtension(chosen);
                } else if (real.size() == 1) {
                    targetExt = probeExtension(real.get(0));
                }
                if (targetExt != null) {
                    String pre = fd.getFile();
                    if (pre != null && !pre.isEmpty()) fd.setFile(forceExtension(pre, targetExt));
                }
            }
```

- [ ] **Step 3: Dönüş yolunda uzantıyı zorla**

`show()` içinde, `name = fd.getFile();` ve onu izleyen `if (name == null)` iptal bloğunun **hemen altına** (yani `d = fd.getDirectory();` satırının üstüne) ekle:

```java
            if (mode == FileDialog.SAVE && targetExt != null) {
                name = forceExtension(name, targetExt);
            }
```

- [ ] **Step 4: Başsız testlerin hâlâ geçtiğini doğrula (regresyon)**

Run: test derleme/çalıştırma bloğu.
Expected: PASS — "Tum testler GECTI". (Bu adım `show()`/`promptFormat`'ı çalıştırmaz ama tüm dosyanın derlendiğini ve mevcut yardımcıların bozulmadığını doğrular.)

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-filedialog/macosfiledialog/MacFileDialog.java
git commit -m "feat(filedialog): SAVE'de format secim penceresi + uzanti zorlama"
```

---

### Task 5: Manuel doğrulama (build + gerçek uygulama)

Otomatik test GUI'yi ve UDE'nin gerçek yazıcı seçimini kapsayamaz; manuel doğrulanır.

**Files:** yok (doğrulama).

- [ ] **Step 1: Yamayı uygula ve uygulamayı kur**

Run: `bash scripts/build.sh` (proje normal build akışı; `apply_filedialog` köprüyü jar'a gömer).
Expected: `[filedialog] native dosya pencereleri yaması uygulandı.` Hata yok.

- [ ] **Step 2: "Farklı Kaydet" — format penceresi**

UDE'de bir UDF aç → "Farklı Kaydet". Doğrula:
- Native panelden ÖNCE "Kaydetme Biçimi" penceresi çıkar; combobox'ta UDF/RTF/XML/USF (ve bağlam PDF içeriyorsa PDF) dostça etiketlerle görünür.
- Varsayılan seçim belgenin mevcut uzantısı (UDF).
- "İptal" → kaydetme tümüyle iptal, native panel açılmaz.

- [ ] **Step 3: Format → uzantı + içerik doğru**

"RTF" seç → native panelde ad `.rtf` ile gelir. Kaydet → dosya `belge.rtf` olur ve **Word/TextEdit'te açılır** (UDE gerçekten RTF yazmış). Aynısını XML ile tekrarla. Native panelde ada `belge.pdf` yazıp RTF seçili bırak → dosya yine `.rtf` ("seçilen format kazanır", çift uzantı yok).

- [ ] **Step 4: "PDF olarak kaydet" — tek filtre yolu**

"PDF olarak kaydet" → format penceresi çıkmaz (tek filtre); native panelde ad `.pdf` ile gelir; kaydedilen dosya geçerli PDF.

- [ ] **Step 5: Aç penceresi regresyon yok**

"Aç" → tüm dosyalar görünür (filtre yok, eski davranış); `.rtf` seçip açma çalışır.

---

## Self-Review Notları

- **Spec kapsamı:** Akış (Task 4), forceExtension/çift-uzantı (Task 1), probe+etiket (Task 2/3), tek-filtre yolu (Task 4 `real.size()==1`), pre-dialog guard 0/1/2+ (Task 4), LOAD değişmez (dokunulmadı), EDT ardışık modal (Task 4 promptFormat→fd), test (Task 1-3 başsız + Task 5 manuel) — hepsi karşılandı.
- **Tip tutarlılığı:** `forceExtension`, `probeExtension`, `knownExtOf`, `friendlyLabel`, `FormatItem`, `promptFormat`, `targetExt` adları tüm görevlerde aynı imzayla kullanıldı.
- **Doğrulanacak varsayım:** UDE'nin `fc.getFileFilter()`'a göre yazıcı seçtiği — Task 5 Step 3 (RTF/XML içerik kontrolü) bunu kanıtlar.
