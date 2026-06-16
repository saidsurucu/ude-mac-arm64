# Formatsız Yapıştır (PLAINPASTE=1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Word'ün "Yalnızca Metni Koru" davranışını UDE'ye ekle — pano içeriğini karakter stili olmadan (imlecin stilini alarak) ama tablo/imaj/liste/hizalama korunarak yapıştır; tetikleyiciler ⌘⇧V ve sağ tık menüsünde "Formatsız Yapıştır".

**Architecture:** PASTERICH boru hattını (HTML/RTF → `HtmlToUde` → `NativeInsert`) yeniden kullan, yalnız `NativeInsert.charAttrs`'ı "düz-karakter modunda" imlecin öznitelik kümesini döndürecek şekilde değiştir. Tek implementasyon (`macospasterich.PlainPaste` + `RichPaste` aşırı yüklemeleri), iki çağıran: sağ tık (lafwidget `EditContextMenuWidget` Javassist yaması) ve ⌘⇧V (`MacShortcutRemap` agent, reflection ile).

**Tech Stack:** Java 11 (Zulu), Swing (`javax.swing.text`), Javassist (build-zamanı bytecode yaması), bash (build.sh). Agent app-classpath'siz derlenir → UDE/macospasterich erişimi reflection ile. Spec: `docs/superpowers/specs/2026-06-16-plain-paste-design.md`.

---

## Dosya Yapısı

- **Değiştir** `scripts/macos-pasterich/macospasterich/NativeInsert.java` — düz-karakter modu (`CURSOR_ATTRS` guard + üç-argümanlı `insert` aşırı yüklemesi + `charAttrs` dalı).
- **Değiştir** `scripts/macos-pasterich/macospasterich/RichPaste.java` — `insertInto`/`insertRtf` için `AttributeSet cursorAttrs` aşırı yüklemeleri.
- **Oluştur** `scripts/macos-pasterich/macospasterich/PlainPaste.java` — pano okuma + imleç stili çözümü + yönlendirme + sağ tık menü öğesi kurucu (`addMenuItem`).
- **Oluştur** `scripts/macos-pasterich/PlainPastePatch.java` — Javassist; lafwidget `EditContextMenuWidget$1.handleMouseEvent` içine "Formatsız Yapıştır" öğesi enjekte eder.
- **Değiştir** `scripts/macos-textkeys/macostextkeys/MacShortcutRemap.java` — `Fb.PLAIN_PASTE` + ⌘⇧V eşlemesi + `perform`/`performLocal` dalları.
- **Değiştir** `scripts/build.sh` — `PLAINPASTE` bayrağı + `apply_plainpaste` fonksiyonu + `patch_jar` çağrısı + standalone `plain-paste` alt komutu.
- **Oluştur** `tests/PlainPasteStripTest.java` — headless: düz-karakter modu karakter stilini düşürür, liste korunur.

---

## Task 1: Headless test — düz-karakter modu (önce başarısız)

Test, henüz var olmayan `RichPaste.insertInto(editor, html, cursorAttrs)` aşırı yüklemesini çağırır → derleme hatasıyla başarısız olur (TDD kırmızı).

**Files:**
- Test: `tests/PlainPasteStripTest.java`

- [ ] **Step 1: Testi yaz**

`tests/PlainPasteStripTest.java`:

```java
import javax.swing.JTextPane;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * NativeInsert düz-karakter modu birim testi (headless; UDE tipi gerektiren
 * tablo/imaj YOK — yalnız paragraf + liste). RichPaste.insertInto(editor, html,
 * cursorAttrs) aşırı yüklemesini kullanır.
 * Derle + çalıştır:
 *   javac --release 11 -d OUT scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java
 *   java -cp OUT PlainPasteStripTest
 */
public class PlainPasteStripTest {
    public static void main(String[] a) throws Exception {
        // İmleç hedef stili: Calibri 16 (kaynaktan farklı). bold YOK, renk YOK.
        SimpleAttributeSet cursor = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cursor, "Calibri");
        StyleConstants.setFontSize(cursor, 16);

        // Kaynak: kalın + kırmızı + Arial 20 metin + madde listesi.
        String html =
              "<p><b><span style='font-family:Arial;font-size:20pt;color:#FF0000'>Kalin kirmizi</span></b></p>"
            + "<ul><li>Madde bir</li></ul>";

        JTextPane pane = new JTextPane();
        pane.setCaretPosition(0);
        boolean ok = macospasterich.RichPaste.insertInto(pane, html, cursor);
        if (!ok) throw new AssertionError("insertInto(plain) false döndü");

        StyledDocument doc = (StyledDocument) pane.getDocument();

        // 1) Karakter stili düşmüş, imleç stili alınmış olmalı.
        boolean sawText = false;
        Element root = doc.getDefaultRootElement();
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            for (int r = 0; r < para.getElementCount(); r++) {
                Element run = para.getElement(r);
                int s = run.getStartOffset(), e = Math.min(run.getEndOffset(), doc.getLength());
                if (e <= s) continue;
                String txt = doc.getText(s, e - s);
                if (txt.trim().isEmpty()) continue;
                sawText = true;
                AttributeSet at = run.getAttributes();
                if (StyleConstants.isBold(at))
                    throw new AssertionError("kalın düşmedi: '" + txt + "'");
                if (StyleConstants.getForeground(at).getRGB() != new java.awt.Color(0,0,0).getRGB()
                        && at.isDefined(StyleConstants.Foreground))
                    throw new AssertionError("renk düşmedi: '" + txt + "'");
                if (!"Calibri".equals(StyleConstants.getFontFamily(at)))
                    throw new AssertionError("font imleç stilini almadı: '" + txt
                            + "' → " + StyleConstants.getFontFamily(at));
            }
        }
        if (!sawText) throw new AssertionError("hiç metin eklenmedi");

        // 2) Liste paragrafı korunmuş olmalı ("Bulleted" özniteliği).
        boolean sawBullet = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            if (root.getElement(p).getAttributes().getAttribute("Bulleted") != null) sawBullet = true;
        }
        if (!sawBullet) throw new AssertionError("liste (Bulleted) korunmadı");

        System.out.println("PlainPasteStripTest OK");
    }
}
```

- [ ] **Step 2: Başarısızlığı doğrula (derleme hatası)**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
rm -rf /tmp/pp-out && mkdir -p /tmp/pp-out
javac --release 11 -d /tmp/pp-out scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java
```
Expected: FAIL — `error: method insertInto in class RichPaste cannot be applied to given types` (üç-argümanlı aşırı yükleme yok).

- [ ] **Step 3: Commit (yalnız test)**

```bash
git add tests/PlainPasteStripTest.java
git commit -m "test(plainpaste): düz-karakter modu için başarısız headless test"
```

---

## Task 2: NativeInsert düz-karakter modu

**Files:**
- Modify: `scripts/macos-pasterich/macospasterich/NativeInsert.java`

- [ ] **Step 1: `CURSOR_ATTRS` alanı + iki/üç-argümanlı `insert`**

`NativeInsert.java` içindeki mevcut iki-argümanlı `insert` (L47) imzasını koru ama gövdesini üç-argümanlıya yönlendir. Mevcut:

```java
    /** editor (hj/JTextComponent) belgesine caret'ten itibaren modeli ekler. */
    static boolean insert(Object editor, UdeDoc.Document model) {
        try {
```

ile değiştir:

```java
    /**
     * Düz-karakter modu için imleç öznitelik kümesi. Null değilse charAttrs(...)
     * stil yerine bunu döndürür (Formatsız Yapıştır). EDT tek-iş-parçacıklı;
     * insert() içinde try/finally ile set/temizlenir.
     */
    private static AttributeSet CURSOR_ATTRS;

    /** editor (hj/JTextComponent) belgesine caret'ten itibaren modeli ekler. */
    static boolean insert(Object editor, UdeDoc.Document model) {
        return insert(editor, model, null);
    }

    /**
     * cursorAttrs != null ise DÜZ-KARAKTER modu: tablo/imaj/liste/paragraf
     * korunur, karakter stili cursorAttrs'a indirgenir (Formatsız Yapıştır).
     */
    static boolean insert(Object editor, UdeDoc.Document model, AttributeSet cursorAttrs) {
        AttributeSet prev = CURSOR_ATTRS;
        CURSOR_ATTRS = cursorAttrs;
        try {
```

- [ ] **Step 2: `insert` gövdesinin sonundaki `finally` ile guard'ı temizle**

Mevcut `insert` gövdesinin kapanışı (L81-85):

```java
        } catch (Throwable t) {
            PrLog.log("NativeInsert.insert", t);
            return false;
        }
    }
```

ile değiştir (dış `try`'ın `finally`'si guard'ı geri yükler):

```java
        } catch (Throwable t) {
            PrLog.log("NativeInsert.insert", t);
            return false;
        } finally {
            CURSOR_ATTRS = prev;
        }
    }
```

- [ ] **Step 3: `charAttrs` düz-karakter dalı**

Mevcut `charAttrs` (L369):

```java
    private static AttributeSet charAttrs(TextStyle s) {
        SimpleAttributeSet a = new SimpleAttributeSet();
```

ile değiştir:

```java
    private static AttributeSet charAttrs(TextStyle s) {
        if (CURSOR_ATTRS != null) return CURSOR_ATTRS;   // düz-karakter modu
        SimpleAttributeSet a = new SimpleAttributeSet();
```

- [ ] **Step 4: Derlemenin geçtiğini doğrula**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
rm -rf /tmp/pp-out && mkdir -p /tmp/pp-out
javac --release 11 -d /tmp/pp-out scripts/macos-pasterich/macospasterich/*.java
```
Expected: PASS (hata yok). Test henüz başarısız (RichPaste aşırı yüklemesi yok) — sonraki task.

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-pasterich/macospasterich/NativeInsert.java
git commit -m "feat(plainpaste): NativeInsert düz-karakter modu (CURSOR_ATTRS guard)"
```

---

## Task 3: RichPaste cursorAttrs aşırı yüklemeleri → test geçer

**Files:**
- Modify: `scripts/macos-pasterich/macospasterich/RichPaste.java`

- [ ] **Step 1: `insertInto` aşırı yüklemesi**

`RichPaste.java`'de mevcut `insertInto(Object editor, String html)` (L28) gövdesini koruyup üç-argümanlıya yönlendir. Mevcut:

```java
    public static boolean insertInto(Object editor, String html) {
        try {
            if (html == null || html.isEmpty()) return false;
            PrLog.dumpHtml(html);
            UdeDoc.Document model = HtmlToUde.convert(html);
            if (model.body.isEmpty()) { PrLog.log("boş model"); return false; }
            boolean ok = NativeInsert.insert(editor, model);
            PrLog.log(ok ? ("insertInto ok " + model.body.size() + " blok") : "insertInto başarısız");
            return ok;
        } catch (Throwable t) {
            PrLog.log("insertInto", t);
            return false;
        }
    }
```

ile değiştir:

```java
    public static boolean insertInto(Object editor, String html) {
        return insertInto(editor, html, null);
    }

    /**
     * cursorAttrs != null ise DÜZ-KARAKTER modu (Formatsız Yapıştır): yapı
     * korunur, karakter stili cursorAttrs'a indirgenir.
     */
    public static boolean insertInto(Object editor, String html, javax.swing.text.AttributeSet cursorAttrs) {
        try {
            if (html == null || html.isEmpty()) return false;
            PrLog.dumpHtml(html);
            UdeDoc.Document model = HtmlToUde.convert(html);
            if (model.body.isEmpty()) { PrLog.log("boş model"); return false; }
            boolean ok = NativeInsert.insert(editor, model, cursorAttrs);
            PrLog.log(ok ? ("insertInto ok " + model.body.size() + " blok"
                    + (cursorAttrs != null ? " (düz)" : "")) : "insertInto başarısız");
            return ok;
        } catch (Throwable t) {
            PrLog.log("insertInto", t);
            return false;
        }
    }
```

- [ ] **Step 2: `insertRtf` aşırı yüklemesi**

Mevcut `insertRtf(Object editor, Transferable t)` (L50) gövdesindeki tek satırı (`return insertInto(editor, html);`, L65) parametreli yapacak şekilde aşırı yükle. Mevcut imza satırını:

```java
    public static boolean insertRtf(Object editor, java.awt.datatransfer.Transferable t) {
        try {
```

ile değiştir:

```java
    public static boolean insertRtf(Object editor, java.awt.datatransfer.Transferable t) {
        return insertRtf(editor, t, null);
    }

    /** cursorAttrs != null → düz-karakter modu (Formatsız Yapıştır). */
    public static boolean insertRtf(Object editor, java.awt.datatransfer.Transferable t,
                                    javax.swing.text.AttributeSet cursorAttrs) {
        try {
```

ve aynı metottaki `return insertInto(editor, html);` (L65) satırını:

```java
            return insertInto(editor, html, cursorAttrs);
```

ile değiştir.

- [ ] **Step 3: Testi çalıştır → geçmeli**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
rm -rf /tmp/pp-out && mkdir -p /tmp/pp-out
javac --release 11 -d /tmp/pp-out scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java
java -cp /tmp/pp-out PlainPasteStripTest
```
Expected: `PlainPasteStripTest OK`

- [ ] **Step 4: Mevcut pasterich testinin BOZULMADIĞINI doğrula (geriye uyum)**

Run:
```bash
java -cp /tmp/pp-out RichPasteUdfTest
```
Expected: hata yok (eski iki-argümanlı yol etkilenmedi).

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-pasterich/macospasterich/RichPaste.java
git commit -m "feat(plainpaste): RichPaste insertInto/insertRtf cursorAttrs aşırı yüklemeleri"
```

---

## Task 4: PlainPaste — pano okuma + imleç stili + yönlendirme + menü öğesi

**Files:**
- Create: `scripts/macos-pasterich/macospasterich/PlainPaste.java`

- [ ] **Step 1: PlainPaste sınıfını yaz**

`scripts/macos-pasterich/macospasterich/PlainPaste.java`:

```java
package macospasterich;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.AttributeSet;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;

/**
 * Formatsız Yapıştır (Word "Yalnızca Metni Koru"). Pano içeriğini KARAKTER STİLİ
 * olmadan (imlecin stilini alarak) ama tablo/imaj/liste/hizalama korunarak
 * editöre ekler. PASTERICH boru hattını (HtmlToUde → NativeInsert) düz-karakter
 * modunda yeniden kullanır.
 *
 * İki çağıran: ⌘⇧V (MacShortcutRemap, reflection) ve sağ tık menüsü
 * (EditContextMenuWidget Javassist yaması → addMenuItem). Teşhis:
 * UDE_PLAINPASTELOG=1 → ~/Library/Logs/ude-plainpaste.txt.
 */
public final class PlainPaste {

    /** Pano içeriğini editöre formatsız ekler. Başarıda true. */
    public static boolean paste(JTextComponent editor) {
        try {
            if (editor == null || !editor.isEditable() || !editor.isEnabled()) return false;
            AttributeSet cursor = cursorAttrs(editor);
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable t = cb.getContents(null);
            if (t == null) { log("pano boş"); return false; }

            // 1) HTML (Word/tarayıcı/PDF) — tablo/imaj/liste buradan gelir.
            if (t.isDataFlavorSupported(DataFlavor.allHtmlFlavor)) {
                Object o = t.getTransferData(DataFlavor.allHtmlFlavor);
                if (o instanceof String && RichPaste.insertInto(editor, (String) o, cursor)) {
                    log("html düz ok"); return true;
                }
            }
            // 2) RTF (Pages/TextEdit/Mail) — insertRtf flavor'ı içeride çözer,
            //    yoksa false döner.
            if (RichPaste.insertRtf(editor, t, cursor)) { log("rtf düz ok"); return true; }

            // 3) Düz metin yedeği.
            if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String s = (String) t.getTransferData(DataFlavor.stringFlavor);
                insertPlainString(editor, s, cursor);
                log("düz metin ok"); return true;
            }
            log("yapıştırılacak içerik yok");
            return false;
        } catch (Throwable e) {
            log("paste", e);
            return false;
        }
    }

    /**
     * İmlecin karakter stilini çözer (Formatsız metin bunu alır). Katmanlama:
     * taban (TNR 12) → caret'teki karakter elementi (hedef paragraf stili) →
     * kit giriş öznitelikleri (yazım stili). Böylece FontFamily HER ZAMAN tanımlı
     * (Swing'in "Monospaced"a düşmesi engellenir).
     */
    private static AttributeSet cursorAttrs(JTextComponent editor) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, "Times New Roman");
        StyleConstants.setFontSize(a, 12);
        try {
            Document doc = editor.getDocument();
            if (doc instanceof StyledDocument) {
                Element ce = ((StyledDocument) doc).getCharacterElement(editor.getCaretPosition());
                if (ce != null) a.addAttributes(ce.getAttributes());
            }
            if (editor instanceof JEditorPane) {
                Object kit = ((JEditorPane) editor).getEditorKit();
                if (kit instanceof StyledEditorKit) {
                    a.addAttributes(((StyledEditorKit) kit).getInputAttributes());
                }
            }
        } catch (Throwable ignore) { }
        return a;
    }

    /** Düz metni seçim-değiştirmeli olarak imleç stilinde ekler (cast YOK). */
    private static void insertPlainString(JTextComponent editor, String s, AttributeSet attrs) throws Exception {
        if (s == null) return;
        Document doc = editor.getDocument();
        int start = Math.min(editor.getSelectionStart(), editor.getSelectionEnd());
        int end = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
        if (end > start) doc.remove(start, end - start);
        doc.insertString(start, s, attrs);   // Document arayüzü → PlainDocument'te attrs yok sayılır
        try { editor.setCaretPosition(Math.min(start + s.length(), doc.getLength())); } catch (Throwable ignore) { }
    }

    /**
     * Sağ tık menüsüne (lafwidget popup) "Formatsız Yapıştır" öğesini ekler.
     * PlainPastePatch tarafından JPopupMenu.show çağrısından ÖNCE çağrılır.
     * Öğe, Kes/Kopyala/Yapıştır'dan hemen sonra (indeks 3, ayraçtan önce) eklenir.
     */
    public static void addMenuItem(JPopupMenu popup, Component invoker) {
        try {
            if (popup == null || !(invoker instanceof JTextComponent)) return;
            final JTextComponent tc = (JTextComponent) invoker;
            JMenuItem mi = new JMenuItem("Formatsız Yapıştır");
            mi.setEnabled(tc.isEditable() && tc.isEnabled());
            mi.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { paste(tc); }
            });
            int idx = popup.getComponentCount() >= 3 ? 3 : popup.getComponentCount();
            popup.insert(mi, idx);
        } catch (Throwable e) {
            log("addMenuItem", e);
        }
    }

    // ---- log (UDE_PLAINPASTELOG=1) ----
    private static void log(String msg) {
        if (!"1".equals(System.getenv("UDE_PLAINPASTELOG"))) return;
        write(msg);
    }
    private static void log(String msg, Throwable t) {
        if (!"1".equals(System.getenv("UDE_PLAINPASTELOG"))) return;
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        write(msg + "\n" + sw);
    }
    private static void write(String s) {
        try {
            java.io.File f = new java.io.File(System.getProperty("user.home"),
                    "Library/Logs/ude-plainpaste.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(s + "\n");
            }
        } catch (Throwable ignore) { }
    }

    private PlainPaste() { }
}
```

- [ ] **Step 2: Derlemeyi doğrula**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
rm -rf /tmp/pp-out && mkdir -p /tmp/pp-out
javac --release 11 -d /tmp/pp-out scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java
java -cp /tmp/pp-out PlainPasteStripTest
```
Expected: derleme hatasız + `PlainPasteStripTest OK` (test hâlâ geçer; PlainPaste yeni eklendi).

- [ ] **Step 3: Commit**

```bash
git add scripts/macos-pasterich/macospasterich/PlainPaste.java
git commit -m "feat(plainpaste): PlainPaste — pano okuma + imleç stili + menü öğesi"
```

---

## Task 5: ⌘⇧V — MacShortcutRemap

**Files:**
- Modify: `scripts/macos-textkeys/macostextkeys/MacShortcutRemap.java`

- [ ] **Step 1: `Fb` enum'a PLAIN_PASTE ekle**

L72:

```java
    private enum Fb { SYNTHETIC, SELECT_ALL, COPY, PASTE, CUT, CLOSE_WINDOW, MINIMIZE, NONE }
```

ile değiştir:

```java
    private enum Fb { SYNTHETIC, SELECT_ALL, COPY, PASTE, PLAIN_PASTE, CUT, CLOSE_WINDOW, MINIMIZE, NONE }
```

- [ ] **Step 2: ⌘⇧V eşlemesini MAPS'e ekle**

Mevcut Yapıştır satırı (L105):

```java
        new Map(KeyEvent.VK_V, META,         "Yapıştır",      0, 0, Fb.PASTE),
```

ile değiştir (altına PLAIN_PASTE satırı; label=null → menü atlanır, doğrudan fb):

```java
        new Map(KeyEvent.VK_V, META,         "Yapıştır",      0, 0, Fb.PASTE),
        // Formatsız Yapıştır (Word ⌘⇧V): menüde yok → fb. Editörde reflection ile
        // macospasterich.PlainPaste; editör-dışı alanlarda normal yapıştırma.
        new Map(KeyEvent.VK_V, META | SHIFT, null,            0, 0, Fb.PLAIN_PASTE),
```

- [ ] **Step 3: `perform` switch'ine PLAIN_PASTE dalı ekle**

Mevcut PASTE dalı (L326):

```java
            case PASTE:      if (c instanceof JTextComponent) ((JTextComponent) c).paste();     break;
```

ile değiştir:

```java
            case PASTE:      if (c instanceof JTextComponent) ((JTextComponent) c).paste();     break;
            case PLAIN_PASTE:
                if (c instanceof JTextComponent) {
                    try {
                        Class.forName("macospasterich.PlainPaste")
                             .getMethod("paste", JTextComponent.class)
                             .invoke(null, (JTextComponent) c);
                    } catch (Throwable ignore) {
                        ((JTextComponent) c).paste();   // PASTERICH yoksa normal yapıştır
                    }
                }
                break;
```

- [ ] **Step 4: `performLocal` switch'ine PLAIN_PASTE dalı ekle (editör-dışı alanlar)**

Mevcut performLocal PASTE dalı (L340):

```java
            case PASTE: if (tc.isEditable() && tc.isEnabled()) { tc.paste(); return true; } return false;
```

ile değiştir:

```java
            case PASTE: if (tc.isEditable() && tc.isEnabled()) { tc.paste(); return true; } return false;
            case PLAIN_PASTE: if (tc.isEditable() && tc.isEnabled()) { tc.paste(); return true; } return false;
```

- [ ] **Step 5: Agent'ın derlendiğini doğrula (app-cp'siz, reflection)**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
rm -rf /tmp/tk-out && mkdir -p /tmp/tk-out
javac --release 11 -d /tmp/tk-out scripts/macos-textkeys/macostextkeys/*.java scripts/macos-textkeys/macosfootnote/*.java 2>&1 | head
```
Expected: hata yok (uyarı olabilir). `macospasterich.PlainPaste` derleme-zamanı GEREKMEZ (Class.forName).

- [ ] **Step 6: Commit**

```bash
git add scripts/macos-textkeys/macostextkeys/MacShortcutRemap.java
git commit -m "feat(plainpaste): ⌘⇧V → Formatsız Yapıştır (MacShortcutRemap, reflection)"
```

---

## Task 6: Sağ tık — PlainPastePatch (Javassist)

**Files:**
- Create: `scripts/macos-pasterich/PlainPastePatch.java`

- [ ] **Step 1: Patcher'ı yaz**

`scripts/macos-pasterich/PlainPastePatch.java`:

```java
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Sağ tık menüsüne "Formatsız Yapıştır" ekler (build-zamanı bytecode yaması).
 *
 * Editörün sağ tık menüsü UDE'den DEĞİL, Substance/lafwidget'ten gelir:
 * org.jvnet.lafwidget.text.EditContextMenuWidget$1.handleMouseEvent bir JPopupMenu
 * kurar (Kes/Kopyala/Yapıştır/——/Sil/Tümünü Seç) ve JPopupMenu.show ile gösterir.
 * Bu sınıf OBFUSCATE DEĞİL → isimle hedeflenir.
 *
 * Yama: handleMouseEvent içindeki JPopupMenu.show(comp,x,y) çağrısı, önce
 * macospasterich.PlainPaste.addMenuItem(popup, comp) çağrılacak şekilde sarılır
 * ($0=popup, $1=comp). addMenuItem öğeyi indeks 3'e (Yapıştır'dan hemen sonra,
 * ayraçtan önce) ekler. Menü yapısı aynı bytecode'da sabit olduğundan güvenli.
 *
 * ÖN KOŞUL: macospasterich.PlainPaste sınıfı ÖNCE jar'a enjekte edilmiş olmalı
 * (apply_pasterich → apply_plainpaste sırası).
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class PlainPastePatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: PlainPastePatch <jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        CtClass inner = pool.get("org.jvnet.lafwidget.text.EditContextMenuWidget$1");
        CtMethod hm = inner.getDeclaredMethod("handleMouseEvent");

        // İdempotans: zaten PlainPaste.addMenuItem çağrısı varsa atla.
        final boolean[] already = { false };
        hm.instrument(new ExprEditor() {
            public void edit(MethodCall mc) {
                if (mc.getClassName().equals("macospasterich.PlainPaste")
                        && mc.getMethodName().equals("addMenuItem")) already[0] = true;
            }
        });
        if (already[0]) {
            System.out.println("[PlainPastePatch] zaten yamalı, atlandı.");
            return;
        }

        hm.instrument(new ExprEditor() {
            public void edit(MethodCall mc) throws CannotCompileException {
                if (mc.getClassName().equals("javax.swing.JPopupMenu")
                        && mc.getMethodName().equals("show")) {
                    mc.replace("{ macospasterich.PlainPaste.addMenuItem($0, $1); $proceed($$); }");
                }
            }
        });

        writeClass(inner, outDir);
        System.out.println("[PlainPastePatch] sağ tık 'Formatsız Yapıştır' öğesi enjekte edildi.");
    }

    private static void writeClass(CtClass cc, File outDir) throws Exception {
        byte[] code = cc.toBytecode();
        File f = new File(outDir, cc.getName().replace('.', '/') + ".class");
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(code);
        }
    }
}
```

- [ ] **Step 2: Patcher'ın derlendiğini doğrula (Javassist cp ile)**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
JVS=$(ls ~/.m2/repository/org/javassist/javassist/*/javassist-*.jar 2>/dev/null | head -1)
[ -z "$JVS" ] && JVS=$(find . build -name 'javassist*.jar' 2>/dev/null | head -1)
echo "JVS=$JVS"
rm -rf /tmp/ppp-out && mkdir -p /tmp/ppp-out
javac --release 11 -cp "$JVS" -d /tmp/ppp-out scripts/macos-pasterich/PlainPastePatch.java
```
Expected: derleme hatasız. (Javassist jar'ı yoksa build.sh `icon_deps` indirir; Task 7'de gerçek çalıştırma yapılır.)

- [ ] **Step 3: Commit**

```bash
git add scripts/macos-pasterich/PlainPastePatch.java
git commit -m "feat(plainpaste): sağ tık menüsüne Formatsız Yapıştır (PlainPastePatch)"
```

---

## Task 7: build.sh entegrasyonu

**Files:**
- Modify: `scripts/build.sh` (L60 bayrak, yeni `apply_plainpaste`, L712 çağrı, L902 alt komut)

- [ ] **Step 1: PLAINPASTE bayrağını ekle**

Mevcut PASTERICH bayrağı (L60):

```bash
PASTERICH="${PASTERICH:-1}" # 1=açık (varsayılan; harici stilli yapıştırma — Word/tarayıcı/PDF→UDE) | 0=kapalı
```

altına ekle:

```bash
PLAINPASTE="${PLAINPASTE:-1}" # 1=açık (varsayılan; Formatsız Yapıştır ⌘⇧V + sağ tık; PASTERICH'e bağlı) | 0=kapalı
```

- [ ] **Step 2: `apply_plainpaste` fonksiyonunu ekle**

`apply_pasterich` fonksiyonunun KAPANIŞINDAN sonra (L539 civarı, `}`'den hemen sonra) ekle:

```bash
apply_plainpaste() {  # $1=JAR — patch_jar içinden çağrılır (apply_pasterich'TEN SONRA)
	local JAR="$1"
	[ "$PLAINPASTE" = "1" ] || return 0
	# PlainPaste sınıfı pasterich tarafından enjekte edilir; PASTERICH kapalıysa yok.
	if ! unzip -l "$JAR" 2>/dev/null | grep 'macospasterich/PlainPaste.class' >/dev/null 2>&1; then
		c_warn "[plainpaste] macospasterich/PlainPaste yok (PASTERICH kapalı?); yama atlandı."; return 0
	fi
	# İdempotans: EditContextMenuWidget$1 zaten yamalıysa patcher kendisi atlar;
	# burada hızlı kontrol için yine de devam edilir (patcher idempotent).
	c_info "[plainpaste] sağ tık Formatsız Yapıştır yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[plainpaste] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[plainpaste] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist
	rm -rf "$BUILD/_pppatch"; mkdir -p "$BUILD/_pppatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_pppatch" "$PASTERICH_SRC/PlainPastePatch.java" \
		|| { c_warn "[plainpaste] PlainPastePatch derlenemedi; yama atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_pppatch:$jvs" PlainPastePatch "$JAR" "$BUILD/_pppatch/out"; then
		c_warn "[plainpaste] kanca uygulanamadı (lafwidget sürümü değişmiş olabilir); atlandı."
		return 0
	fi
	# Patcher boş çıktı üretebilir (zaten yamalı dalı) → org/ yoksa zip'e dokunma.
	if [ -d "$BUILD/_pppatch/out/org" ]; then
		( cd "$BUILD/_pppatch/out" && zip -q -r "$JAR" org )
		c_ok "[plainpaste] sağ tık Formatsız Yapıştır yaması uygulandı."
	else
		c_ok "[plainpaste] zaten yamalı, atlandı."
	fi
}
```

- [ ] **Step 3: `patch_jar`'a çağrıyı ekle**

Mevcut (L712):

```bash
	apply_pasterich "$JAR"
	apply_imgresize "$JAR"
```

ile değiştir:

```bash
	apply_pasterich "$JAR"
	apply_plainpaste "$JAR"
	apply_imgresize "$JAR"
```

- [ ] **Step 4: Standalone alt komutu ekle**

Mevcut `paste-rich` alt komutu (L902):

```bash
	paste-rich) apply_pasterich "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
```

ile değiştir:

```bash
	paste-rich) apply_pasterich "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
	plain-paste) apply_plainpaste "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
```

- [ ] **Step 5: bash sözdizimini doğrula**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash -n scripts/build.sh && echo "syntax OK"
```
Expected: `syntax OK`

- [ ] **Step 6: Commit**

```bash
git add scripts/build.sh
git commit -m "build(plainpaste): PLAINPASTE bayrağı + apply_plainpaste + patch_jar entegrasyonu"
```

---

## Task 8: Uçtan uca build + paketleme doğrulaması + dokümantasyon

**Files:**
- Modify: `CLAUDE.md`, memory dosyaları (`.claude/projects/.../memory/`)

- [ ] **Step 1: Taze kaynakla tam build**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh download && bash scripts/build.sh patch && bash scripts/build.sh lookagent && bash scripts/build.sh package
```
Expected: `[pasterich] … uygulandı.` ve `[plainpaste] sağ tık Formatsız Yapıştır yaması uygulandı.` log satırları; hata yok.

- [ ] **Step 2: Paketlenmiş jar'ın gerçekten yamalı olduğunu doğrula**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
JAR="build/Uyap Doküman Editörü.app/Contents/app/editor-app.jar"
unzip -l "$JAR" | grep -c 'macospasterich/PlainPaste.class'
javap -c -p -classpath "$JAR" 'org.jvnet.lafwidget.text.EditContextMenuWidget$1' 2>/dev/null | grep -c 'macospasterich/PlainPaste.addMenuItem'
```
Expected: birinci komut `1` (PlainPaste sınıfı var), ikinci komut `>=1` (menü çağrısı enjekte). İkisi de 0'sa paketleme yamasız — `bash scripts/build.sh package` tekrar.

- [ ] **Step 3: KULLANICI elle GUI doğrulaması (manuel test tercihi)**

Kullanıcıdan iste (osascript sentetik klavye reddedildi — son adım kullanıcıda):
1. Uygulamayı doğrudan binary ile başlat:
   `pkill -f UyapDokumanEditoru; "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru"`
2. Word/Google Docs/Pages'tan tablolu + stilli (kalın/renkli) metin kopyala.
3. UDE'de **⌘⇧V** → metin imleç stilinde (stilsiz) gelmeli, **tablo/imaj/liste KORUNMALI**.
4. **Sağ tık → "Formatsız Yapıştır"** → aynı sonuç.
5. Karşılaştırma: normal **⌘V** / sağ tık "Yapıştır" hâlâ ZENGİN (kalın/renk korunur).
6. Sorun olursa `UDE_PLAINPASTELOG=1` ile başlatıp `~/Library/Logs/ude-plainpaste.txt` incele.

- [ ] **Step 4: CLAUDE.md'ye bölüm ekle**

`CLAUDE.md`'ye PASTERICH bölümünden sonra yeni bölüm ekle (özet, ~10-15 satır): PLAINPASTE=1 mekanizması — NativeInsert düz-karakter modu (CURSOR_ATTRS guard + charAttrs dalı), RichPaste cursorAttrs aşırı yüklemeleri, PlainPaste (pano HTML/RTF/düz + imleç stili katmanlama), sağ tık PlainPastePatch (lafwidget EditContextMenuWidget$1.handleMouseEvent → JPopupMenu.show sarması, indeks 3), ⌘⇧V MacShortcutRemap Fb.PLAIN_PASTE (reflection). Tuzaklar: agy bulguları (getCharacterElement, doc.remove+insertString cast'sız, FontFamily fallback), PLAINPASTE→PASTERICH bağımlılığı, apply_plainpaste sırası (pasterich'ten sonra).

- [ ] **Step 5: Memory dosyası ekle**

`.claude/projects/-Users-saidsurucu-Documents-GitHub-ude-mac-arm/memory/plain-paste.md` oluştur (frontmatter: type project) + `MEMORY.md`'ye tek satır pointer ekle. İçerik: PLAINPASTE özeti + [[macos-rich-paste]] bağlantısı.

- [ ] **Step 6: Commit**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
git add CLAUDE.md
git add -f .claude/projects/*/memory/plain-paste.md .claude/projects/*/memory/MEMORY.md 2>/dev/null || true
git commit -m "docs(plainpaste): CLAUDE.md + memory — Formatsız Yapıştır mekanizması"
```

---

## Self-Review notları

- **Spec kapsamı:** karakter stili düşür (Task 2-3) ✓; tablo/imaj/liste/hizalama koru (NativeInsert değişmez, Task 2) ✓; imleç stili + FontFamily fallback (Task 4 cursorAttrs) ✓; ⌘⇧V (Task 5) ✓; sağ tık (Task 6) ✓; editör-dışı cast guard (Task 5 performLocal) ✓; agy düzeltmeleri (getCharacterElement, doc.remove+insertString, try/finally guard, RTF aşırı yükleme — privates açılmadı, daha temiz) ✓.
- **Tip tutarlılığı:** `insert(editor, model, AttributeSet)`, `insertInto(editor, html, AttributeSet)`, `insertRtf(editor, t, AttributeSet)`, `PlainPaste.paste(JTextComponent)`, `PlainPaste.addMenuItem(JPopupMenu, Component)` — tüm task'larda aynı imzalar.
- **Placeholder yok:** her kod adımı tam içerik.
