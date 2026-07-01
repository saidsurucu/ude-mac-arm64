# Formatsız Yapıştırma Varsayılan — İmplementasyon Planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harici içerik yapıştırmada varsayılan formatsız olsun: ⌘V ve sağ tık "Yapıştır" akıllı (UDE-içi formatlı, harici formatsız), ⌘⇧V ve yeni "Formatlı Yapıştır" menü öğesi formatlı.

**Architecture:** PASTERICH kancasının (`hj.a(Transferable)` enjekte dalı) varsayılanı formatsıza çevrilir: yeni `macospasterich.PasteMode` sınıfı `forceRich` bayrağına göre `RichPaste.insertInto/insertRtf`'e `cursorAttrs` (formatsız) ya da `null` (formatlı) geçirir. UDE-içi kopyalar (EditorDataFlavor) `paste()`'in başında kancaya uğramadan işlendiğinden hep formatlı kalır. ⌘⇧V ve "Formatlı Yapıştır" bayrağı `try/finally` ile set/temizler.

**Tech Stack:** Java 11 (Zulu hedefi, `--release 11`), Javassist build-zamanı yaması, javaagent (macos-textkeys), headless JTextPane testleri (javac+java elle).

**Spec:** `docs/superpowers/specs/2026-07-01-plain-paste-default-design.md`

## Global Constraints

- Tüm Java kaynakları `--release 11 -encoding UTF-8` ile derlenir (hedef çalışma ortamı Zulu 11).
- `macospasterich/*.java` app-classpath'siz derlenir: yalnız `java.*`/`javax.swing.*` + kendi paketi; UDE iç tipleri reflection ile.
- Javassist `insertBefore/setBody/replace` string'lerinde `//` yorum YASAK (newline yok → gövde yutulur).
- Editör belge mutasyonlarında `moveDot`/`setCaretPosition+moveCaretPosition` deseni YASAK (UDE caretUpdate NPE); mevcut `NativeInsert`/`PlainPaste` yollarına dokunulmuyor.
- Testler `tests/` dizininde, javac+java elle çalıştırılır (test framework yok); her test `main` + `AssertionError` deseni.
- Yorum/log dili Türkçe (proje konvansiyonu); commit mesajları Türkçe, sonunda `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- `docs/` gitignore'da → spec/plan dosyaları `git add -f` ile eklenir.
- Menü öğesi metinleri birebir: `"Formatsız Yapıştır"`, `"Formatlı Yapıştır"`, UDE'ninki `"Yapıştır"`.

---

### Task 1: `PasteMode` sınıfı + `PlainPaste.cursorAttrs` görünürlüğü + headless test

**Files:**
- Create: `scripts/macos-pasterich/macospasterich/PasteMode.java`
- Modify: `scripts/macos-pasterich/macospasterich/PlainPaste.java` (yalnız `cursorAttrs` görünürlüğü, satır ~78)
- Test: `tests/PasteModeTest.java`

**Interfaces:**
- Consumes: `RichPaste.insertInto(Object editor, String html, AttributeSet cursorAttrs)` ve `RichPaste.insertRtf(Object editor, Transferable t, AttributeSet cursorAttrs)` (mevcut, `cursorAttrs=null` → formatlı); `PlainPaste.cursorAttrs(JTextComponent)` (şu an private → paket-içi yapılacak).
- Produces: `macospasterich.PasteMode` — `public static void setForceRich(boolean)`, `public static boolean insertHtml(Object editor, String html)`, `public static boolean insertRtf(Object editor, java.awt.datatransfer.Transferable t)`. Task 2 (kanca), Task 3 (menü) ve Task 4 (⌘⇧V, reflection) bunları kullanır.

- [ ] **Step 1: Başarısız testi yaz**

`tests/PasteModeTest.java`:

```java
import javax.swing.JTextPane;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * PasteMode birim testi (headless). Varsayılan mod FORMATSIZ: kaynak karakter
 * stili (kalın/Arial) düşer, imleç stili alınır. forceRich modunda kaynak
 * stili korunur; bayrak finally ile temizlenince yeniden formatsız.
 * Derle + çalıştır:
 *   OUT=$(mktemp -d)
 *   javac --release 11 -encoding UTF-8 -d "$OUT" scripts/macos-pasterich/macospasterich/*.java tests/PasteModeTest.java
 *   java -cp "$OUT" PasteModeTest
 */
public class PasteModeTest {
    public static void main(String[] a) throws Exception {
        String html = "<p><b><span style='font-family:Arial;font-size:20pt;"
                + "color:#FF0000'>Kalin kirmizi</span></b></p>";

        // 1) Varsayılan: formatsız → kalın düşer, kaynak fontu (Arial) alınmaz.
        JTextPane p1 = new JTextPane();
        p1.setCaretPosition(0);
        if (!macospasterich.PasteMode.insertHtml(p1, html))
            throw new AssertionError("insertHtml (varsayılan) false döndü");
        if (hasBold(p1))
            throw new AssertionError("varsayılan mod formatsız değil: kalın korunmuş");
        if ("Arial".equals(familyOfFirstRun(p1)))
            throw new AssertionError("varsayılan mod kaynak fontunu aldı (imleç stili beklenirdi)");

        // 2) forceRich: formatlı → kalın + Arial korunur.
        JTextPane p2 = new JTextPane();
        p2.setCaretPosition(0);
        macospasterich.PasteMode.setForceRich(true);
        try {
            if (!macospasterich.PasteMode.insertHtml(p2, html))
                throw new AssertionError("insertHtml (forceRich) false döndü");
        } finally {
            macospasterich.PasteMode.setForceRich(false);
        }
        if (!hasBold(p2))
            throw new AssertionError("forceRich modu formatlı değil: kalın düşmüş");
        if (!"Arial".equals(familyOfFirstRun(p2)))
            throw new AssertionError("forceRich modu kaynak fontunu korumadı: " + familyOfFirstRun(p2));

        // 3) Bayrak temizlendi: yeniden varsayılan (formatsız).
        JTextPane p3 = new JTextPane();
        p3.setCaretPosition(0);
        if (!macospasterich.PasteMode.insertHtml(p3, html))
            throw new AssertionError("insertHtml (bayrak sonrası) false döndü");
        if (hasBold(p3))
            throw new AssertionError("bayrak temizlenmedi: hâlâ formatlı");

        System.out.println("PasteModeTest OK");
    }

    private static boolean hasBold(JTextPane pane) throws Exception {
        StyledDocument doc = (StyledDocument) pane.getDocument();
        Element root = doc.getDefaultRootElement();
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            for (int r = 0; r < para.getElementCount(); r++) {
                Element run = para.getElement(r);
                int s = run.getStartOffset(), e = Math.min(run.getEndOffset(), doc.getLength());
                if (e <= s) continue;
                if (doc.getText(s, e - s).trim().isEmpty()) continue;
                if (StyleConstants.isBold(run.getAttributes())) return true;
            }
        }
        return false;
    }

    private static String familyOfFirstRun(JTextPane pane) throws Exception {
        StyledDocument doc = (StyledDocument) pane.getDocument();
        Element root = doc.getDefaultRootElement();
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            for (int r = 0; r < para.getElementCount(); r++) {
                Element run = para.getElement(r);
                int s = run.getStartOffset(), e = Math.min(run.getEndOffset(), doc.getLength());
                if (e <= s) continue;
                if (doc.getText(s, e - s).trim().isEmpty()) continue;
                return StyleConstants.getFontFamily(run.getAttributes());
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula (derleme hatası)**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
OUT=$(mktemp -d)
javac --release 11 -encoding UTF-8 -d "$OUT" scripts/macos-pasterich/macospasterich/*.java tests/PasteModeTest.java
```
Beklenen: DERLEME HATASI — `macospasterich.PasteMode` sınıfı yok ("cannot find symbol").

- [ ] **Step 3: `PasteMode`'u yaz + `cursorAttrs`'ı paket-içi yap**

`scripts/macos-pasterich/macospasterich/PasteMode.java` (yeni dosya):

```java
package macospasterich;

import java.awt.datatransfer.Transferable;

import javax.swing.text.AttributeSet;
import javax.swing.text.JTextComponent;

/**
 * Harici yapıştırma modu anahtarı. PasteRichPatch'in hj.a(Transferable) kancası
 * RichPaste'i DOĞRUDAN değil bu sınıf üzerinden çağırır: varsayılan FORMATSIZ
 * (karakter+paragraf biçimi imleçten, tablo/liste/imaj YAPISI kaynaktan —
 * PLAINPASTE anlamı); forceRich bayrağı set edilmişse eski FORMATLI (zengin)
 * yol. Bayrağı ⌘⇧V (MacShortcutRemap, reflection) ve sağ tık "Formatlı
 * Yapıştır" (PlainPaste.addMenuItem) try/finally ile set/temizler.
 *
 * UDE-İÇİ kopyalar (EditorDataFlavor) paste()'in başında, kancaya hiç
 * uğramadan işlenir → her zaman formatlı kalır (bu sınıf onlara dokunmaz).
 */
public final class PasteMode {

    private static volatile boolean forceRich;

    /** Çağıranlar try/finally ile set/temizlemeli (yarım bayrak kalmasın). */
    public static void setForceRich(boolean b) { forceRich = b; }

    /** Kancanın HTML dalı: varsayılan formatsız, forceRich'te formatlı. */
    public static boolean insertHtml(Object editor, String html) {
        return RichPaste.insertInto(editor, html, cursorAttrsOrNull(editor));
    }

    /** Kancanın RTF dalı (Pages/TextEdit/Mail): aynı mod seçimi. */
    public static boolean insertRtf(Object editor, Transferable t) {
        return RichPaste.insertRtf(editor, t, cursorAttrsOrNull(editor));
    }

    /** null = formatlı (zengin yol); değilse formatsızın imleç stili. */
    private static AttributeSet cursorAttrsOrNull(Object editor) {
        if (forceRich) return null;
        if (editor instanceof JTextComponent)
            return PlainPaste.cursorAttrs((JTextComponent) editor);
        return null;   /* editör tipi bilinmiyorsa güvenli taraf: eski (formatlı) davranış */
    }

    private PasteMode() { }
}
```

`scripts/macos-pasterich/macospasterich/PlainPaste.java` — tek satır değişir (~78. satır):

```java
    private static AttributeSet cursorAttrs(JTextComponent editor) {
```
→
```java
    static AttributeSet cursorAttrs(JTextComponent editor) {   /* paket-içi: PasteMode da kullanır */
```

- [ ] **Step 4: Testin geçtiğini doğrula**

```bash
OUT=$(mktemp -d)
javac --release 11 -encoding UTF-8 -d "$OUT" scripts/macos-pasterich/macospasterich/*.java tests/PasteModeTest.java
java -cp "$OUT" PasteModeTest
```
Beklenen: `PasteModeTest OK`

- [ ] **Step 5: Regresyon — mevcut düz/rich testleri**

```bash
javac --release 11 -encoding UTF-8 -d "$OUT" \
  scripts/macos-pasterich/macospasterich/*.java \
  tests/PlainPasteStripTest.java tests/PlainPasteParaFormatTest.java \
  tests/RichPasteSourcesTest.java tests/RichPasteUdfTest.java \
  tests/RichPasteReplaceSelectionTest.java
java -cp "$OUT" PlainPasteStripTest
java -cp "$OUT" PlainPasteParaFormatTest
java -cp "$OUT" RichPasteSourcesTest
java -cp "$OUT" RichPasteUdfTest
java -cp "$OUT" RichPasteReplaceSelectionTest
```
Beklenen: her biri kendi `… OK` çıktısını basar (beş test dosyası da `tests/` altında mevcut, adlar doğrulandı).

- [ ] **Step 6: Commit**

```bash
git add scripts/macos-pasterich/macospasterich/PasteMode.java \
        scripts/macos-pasterich/macospasterich/PlainPaste.java \
        tests/PasteModeTest.java
git commit -m "feat(pastemode): harici yapıştırma modu anahtarı — varsayılan formatsız, forceRich bayrağı

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `PasteRichPatch` enjekte dalını `PasteMode`'a yönlendir

**Files:**
- Modify: `scripts/macos-pasterich/PasteRichPatch.java:61-77` (enjekte `src` string'i) + sınıf üstü yorum

**Interfaces:**
- Consumes: `macospasterich.PasteMode.insertHtml(Object, String)` ve `insertRtf(Object, Transferable)` (Task 1).
- Produces: yamalı `hj.a(Transferable)` — harici HTML/RTF artık PasteMode üzerinden eklenir (varsayılan formatsız). Fonksiyonel doğrulama Task 5'te (javap).

- [ ] **Step 1: Enjekte dalı değiştir**

`scripts/macos-pasterich/PasteRichPatch.java` içinde `src` string'inde İKİ çağrı değişir:

```java
            + "          if (macospasterich.RichPaste.insertInto(this, __h)) return true;"
```
→
```java
            + "          if (macospasterich.PasteMode.insertHtml(this, __h)) return true;"
```

ve

```java
            + "      if (macospasterich.RichPaste.insertRtf(this, __t)) return true;"
```
→
```java
            + "      if (macospasterich.PasteMode.insertRtf(this, __t)) return true;"
```

(`catch` bloğundaki `RichPaste.logExternal(__e)` DEĞİŞMEZ.)

Ayrıca `src`'nin hemen üstündeki yorumun (satır ~57-60) başına şu satır eklenir:

```java
        // Varsayılan FORMATSIZ (2026-07): PasteMode forceRich bayrağına göre
        // cursorAttrs (formatsız) ya da null (formatlı) geçirir; ⌘V/menü
        // "Yapıştır" böylece "akıllı" olur (UDE-içi formatlı, harici formatsız).
```

Sınıf javadoc'una (satır ~9-38 bloğunun sonuna) tek paragraf eklenir:

```java
 * 2026-07: Enjekte dal RichPaste'i doğrudan değil macospasterich.PasteMode
 * üzerinden çağırır — varsayılan formatsız, ⌘⇧V/"Formatlı Yapıştır" forceRich
 * bayrağıyla zengin yolu zorlar. Spec: docs/superpowers/specs/
 * 2026-07-01-plain-paste-default-design.md
```

- [ ] **Step 2: Derleme kontrolü**

Enjekte string'in gerçek derlemesi patch anında (jar classpath'iyle) olur; burada
yalnız patcher'ın kendisinin derlendiği doğrulanır:

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
JVS=scripts/lib/javassist-3.30.2-GA.jar
[ -s "$JVS" ] || JVS=$(bash scripts/build.sh icon-deps 2>/dev/null | tail -1)   # yoksa indirir, yolu basar
OUT=$(mktemp -d)
javac --release 11 -encoding UTF-8 -cp "$JVS" -d "$OUT" scripts/macos-pasterich/PasteRichPatch.java
echo "derleme OK"
```
Beklenen: `derleme OK`. (Enjekte string'in kendisi ancak Task 5'teki tam build'de, jar classpath'ine karşı doğrulanır; hata olursa `[pasterich] kanca uygulanamadı` olarak görünür.)

- [ ] **Step 3: Commit**

```bash
git add scripts/macos-pasterich/PasteRichPatch.java
git commit -m "feat(pasterich): kanca varsayılanı formatsız — enjekte dal PasteMode üzerinden

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Sağ tık menüsü — "Formatlı Yapıştır" + hızlandırıcı düzeni

**Files:**
- Modify: `scripts/macos-pasterich/macospasterich/PlainPaste.java:116-147` (`addMenuItem`) + yeni `insertAfter` yardımcısı
- Test: `tests/PasteMenuTest.java`

**Interfaces:**
- Consumes: `PasteMode.setForceRich(boolean)` (Task 1), mevcut `PlainPaste.paste(JTextComponent)`, mevcut `fixAccelerators(JPopupMenu)`.
- Produces: `PlainPaste.addMenuItem(JPopupMenu, Component)` — davranışı genişler: "Formatsız Yapıştır" (hızlandırıcısız) + "Formatlı Yapıştır" (⌘⇧V göstergeli) ekler; imza DEĞİŞMEZ (PlainPastePatch enjekte çağrısı aynen çalışır).

- [ ] **Step 1: Başarısız testi yaz**

`tests/PasteMenuTest.java`:

```java
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;

/**
 * PlainPaste.addMenuItem testi (headless): "Formatsız Yapıştır" (hızlandırıcısız)
 * + "Formatlı Yapıştır" (⌘⇧V) doğru sırada ve idempotent eklenir; UDE'nin ^V
 * hızlandırıcısı ⌘'ye çevrilir.
 * Derle + çalıştır:
 *   OUT=$(mktemp -d)
 *   javac --release 11 -encoding UTF-8 -d "$OUT" scripts/macos-pasterich/macospasterich/*.java tests/PasteMenuTest.java
 *   java -cp "$OUT" PasteMenuTest
 */
public class PasteMenuTest {
    public static void main(String[] a) throws Exception {
        JPopupMenu popup = new JPopupMenu();
        popup.add(new JMenuItem("Kes"));
        popup.add(new JMenuItem("Kopyala"));
        JMenuItem yapistir = new JMenuItem("Yapıştır");
        yapistir.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        popup.add(yapistir);

        JTextPane editor = new JTextPane();
        macospasterich.PlainPaste.addMenuItem(popup, editor);
        macospasterich.PlainPaste.addMenuItem(popup, editor);   // idempotans: ikinci çağrı çift eklememeli

        int pasteIdx = indexOf(popup, "Yapıştır");
        int plainIdx = indexOf(popup, "Formatsız Yapıştır");
        int richIdx  = indexOf(popup, "Formatlı Yapıştır");
        if (plainIdx < 0) throw new AssertionError("Formatsız Yapıştır eklenmedi");
        if (richIdx < 0) throw new AssertionError("Formatlı Yapıştır eklenmedi");
        if (count(popup, "Formatsız Yapıştır") != 1 || count(popup, "Formatlı Yapıştır") != 1)
            throw new AssertionError("idempotans bozuk: çift öğe var");
        if (!(pasteIdx < plainIdx && plainIdx < richIdx))
            throw new AssertionError("sıra bozuk: Yapıştır(" + pasteIdx + ") → Formatsız("
                    + plainIdx + ") → Formatlı(" + richIdx + ") bekleniyordu");

        JMenuItem plain = (JMenuItem) popup.getComponent(plainIdx);
        if (plain.getAccelerator() != null)
            throw new AssertionError("Formatsız Yapıştır hızlandırıcı göstermemeli: " + plain.getAccelerator());

        int meta = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuItem rich = (JMenuItem) popup.getComponent(richIdx);
        KeyStroke want = KeyStroke.getKeyStroke(KeyEvent.VK_V, meta | InputEvent.SHIFT_DOWN_MASK);
        if (!want.equals(rich.getAccelerator()))
            throw new AssertionError("Formatlı Yapıştır hızlandırıcısı ⌘⇧V değil: " + rich.getAccelerator());

        // UDE'nin ^V hızlandırıcısı ⌘V olarak gösterilmeli (fixAccelerators).
        KeyStroke fixed = ((JMenuItem) popup.getComponent(pasteIdx)).getAccelerator();
        if (fixed == null || (fixed.getModifiers() & meta) == 0
                || (fixed.getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0)
            throw new AssertionError("Yapıştır hızlandırıcısı ⌘'ye çevrilmedi: " + fixed);

        System.out.println("PasteMenuTest OK");
    }

    private static int indexOf(JPopupMenu popup, String text) {
        Component[] cs = popup.getComponents();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] instanceof JMenuItem
                    && text.equals(((JMenuItem) cs[i]).getText().trim())) return i;
        }
        return -1;
    }

    private static int count(JPopupMenu popup, String text) {
        int n = 0;
        for (Component c : popup.getComponents()) {
            if (c instanceof JMenuItem && text.equals(((JMenuItem) c).getText().trim())) n++;
        }
        return n;
    }
}
```

- [ ] **Step 2: Testin başarısız olduğunu doğrula**

```bash
OUT=$(mktemp -d)
javac --release 11 -encoding UTF-8 -d "$OUT" scripts/macos-pasterich/macospasterich/*.java tests/PasteMenuTest.java
java -cp "$OUT" PasteMenuTest
```
Beklenen: FAIL — `AssertionError: Formatlı Yapıştır eklenmedi` (ya da hızlandırıcı asserti; mevcut kod "Formatsız Yapıştır"a ⌘⇧V basıyor ve "Formatlı" öğesi yok).

- [ ] **Step 3: `addMenuItem`'ı yeniden yaz**

`scripts/macos-pasterich/macospasterich/PlainPaste.java` — mevcut `addMenuItem`
(satır 109-147) TÜMÜYLE aşağıdakiyle değiştirilir; hemen altına `insertAfter`
yardımcısı eklenir (`fixAccelerators` DEĞİŞMEZ):

```java
    /**
     * UDE'nin kendi sağ tık menüsüne (text.fK → gui.dx.getPopupMenu()) yapıştırma
     * öğelerini ekler: "Formatsız Yapıştır" (hızlandırıcısız — ⌘V zaten "akıllı":
     * UDE-içi formatlı, harici formatsız) ve "Formatlı Yapıştır" (⌘⇧V; forceRich
     * bayrağıyla zengin yolu zorlar). PlainPastePatch tarafından JPopupMenu.show
     * çağrısından ÖNCE çağrılır ($0=popup, $1=editör). Sıra: Yapıştır →
     * Formatsız → Formatlı. İdempotent (popup yeniden gösterilse de tek kopya).
     * Ayrıca UDE'nin Windows kökenli ^X/^C/^V hızlandırıcıları macOS ⌘ ile
     * gösterilir.
     */
    public static void addMenuItem(JPopupMenu popup, Component invoker) {
        try {
            if (popup == null || !(invoker instanceof JTextComponent)) return;
            final JTextComponent tc = (JTextComponent) invoker;
            boolean hasPlain = false, hasRich = false;
            for (Component c : popup.getComponents()) {
                if (!(c instanceof JMenuItem)) continue;
                String txt = ((JMenuItem) c).getText();
                if ("Formatsız Yapıştır".equals(txt)) hasPlain = true;
                if ("Formatlı Yapıştır".equals(txt)) hasRich = true;
            }
            boolean enabled = tc.isEditable() && tc.isEnabled();
            if (!hasPlain) {
                JMenuItem mi = new JMenuItem("Formatsız Yapıştır");
                mi.setEnabled(enabled);
                // Hızlandırıcı YOK: ⌘⇧V artık Formatlı Yapıştır'ın.
                mi.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { paste(tc); }
                });
                insertAfter(popup, mi, "Yapıştır");
            }
            if (!hasRich) {
                JMenuItem mi = new JMenuItem("Formatlı Yapıştır");
                mi.setEnabled(enabled);
                int meta = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();   // macOS = ⌘
                mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, meta | InputEvent.SHIFT_DOWN_MASK));
                mi.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        // tc = hj türevi editör → virtual dispatch zengin hj.paste();
                        // forceRich kancaya "formatlı" dedirtir (UDE-içi zaten formatlı).
                        PasteMode.setForceRich(true);
                        try { tc.paste(); } finally { PasteMode.setForceRich(false); }
                    }
                });
                insertAfter(popup, mi, "Formatsız Yapıştır");
            }
            fixAccelerators(popup);
        } catch (Throwable e) {
            log("addMenuItem", e);
        }
    }

    /** mi'yi metni `after` olan öğeden hemen sonra ekler (bulunamazsa sona). */
    private static void insertAfter(JPopupMenu popup, JMenuItem mi, String after) {
        int idx = -1;
        Component[] cs = popup.getComponents();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] instanceof JMenuItem) {
                String t = ((JMenuItem) cs[i]).getText();
                if (t != null && after.equals(t.trim())) { idx = i + 1; break; }
            }
        }
        if (idx >= 0 && idx <= popup.getComponentCount()) popup.insert(mi, idx);
        else popup.add(mi);
    }
```

Not: sınıfın import listesi zaten `Toolkit, InputEvent, KeyEvent, KeyStroke,
ActionListener, ActionEvent, JMenuItem, JPopupMenu, Component` içeriyor — yeni
import gerekmez.

- [ ] **Step 4: Testlerin geçtiğini doğrula**

```bash
OUT=$(mktemp -d)
javac --release 11 -encoding UTF-8 -d "$OUT" scripts/macos-pasterich/macospasterich/*.java \
  tests/PasteMenuTest.java tests/PasteModeTest.java
java -cp "$OUT" PasteMenuTest
java -cp "$OUT" PasteModeTest
```
Beklenen: `PasteMenuTest OK` ve `PasteModeTest OK`.

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-pasterich/macospasterich/PlainPaste.java tests/PasteMenuTest.java
git commit -m "feat(plainpaste): sağ tık menüsüne Formatlı Yapıştır (⌘⇧V); Formatsız hızlandırıcısız

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: `MacShortcutRemap` — ⌘⇧V formatlı (RICH_PASTE)

**Files:**
- Modify: `scripts/macos-textkeys/macostextkeys/MacShortcutRemap.java` (satır 72 enum, 110-112 MAPS, 334-344 perform case, 359 performLocal case + yeni yardımcı)

**Interfaces:**
- Consumes: `macospasterich.PasteMode.setForceRich(boolean)` — REFLECTION ile (`Class.forName`; agent jar app-classpath'siz derlenir, aynı System ClassLoader'da çözülür). Mevcut `findMenuItem(String, Component)` (JMenuItem döner).
- Produces: ⌘⇧V davranışı — editörde forceRich + menü "Yapıştır"; editör-dışı alanlarda `tc.paste()`. ⌘V girdisi DEĞİŞMEZ.

- [ ] **Step 1: Enum + MAPS girdisini değiştir**

Satır 72: `Fb` enum'ında `PLAIN_PASTE` → `RICH_PASTE`:

```java
    private enum Fb { SYNTHETIC, SELECT_ALL, COPY, PASTE, RICH_PASTE, CUT, CLOSE_WINDOW, MINIMIZE, NONE }
```

Satır 109-112 (⌘V satırı DEĞİŞMEZ; yalnız yorum + ⌘⇧V satırı):

```java
        new Map(KeyEvent.VK_V, META,         "Yapıştır",      0, 0, Fb.PASTE),
        // ⌘V "akıllı"dır: menü "Yapıştır" → paste() → UDE-içi kopya formatlı,
        // harici içerik PASTERICH kancasının VARSAYILANI ile formatsız (PasteMode).
        // ⌘⇧V = Formatlı Yapıştır: forceRich bayrağı + aynı menü eylemi.
        new Map(KeyEvent.VK_V, META | SHIFT, null,            0, 0, Fb.RICH_PASTE),
```

- [ ] **Step 2: `perform` ve `performLocal` case'lerini değiştir**

`perform` switch'inde (satır 334-344) mevcut `case PLAIN_PASTE:` bloğu
TÜMÜYLE şununla değiştirilir:

```java
            case RICH_PASTE:
                if (c instanceof JTextComponent) {
                    setForceRich(true);
                    try {
                        JMenuItem mi = findMenuItem("Yapıştır", c);
                        if (mi != null) mi.doClick(0);
                        else ((JTextComponent) c).paste();
                    } finally {
                        setForceRich(false);
                    }
                }
                break;
```

`performLocal` switch'inde (satır 359) mevcut `case PLAIN_PASTE:` satırı
şununla değiştirilir (editör-dışı alanda düz yapıştırma — davranış eskisiyle aynı):

```java
            case RICH_PASTE: if (tc.isEditable() && tc.isEnabled()) { tc.paste(); return true; } return false;
```

Sınıfa (örn. `performLocal`'ın hemen altına) yeni yardımcı eklenir:

```java
    /** macospasterich.PasteMode.forceRich bayrağı (PASTERICH=0 build'de sınıf yok → sessiz no-op). */
    private static void setForceRich(boolean b) {
        try {
            Class.forName("macospasterich.PasteMode")
                 .getMethod("setForceRich", boolean.class)
                 .invoke(null, Boolean.valueOf(b));
        } catch (Throwable ignore) { }
    }
```

- [ ] **Step 3: Agent derlemesiyle doğrula**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh textkeys
LC_ALL=C grep -c --binary-files=text 'macospasterich.PasteMode' build/_textkeys/macostextkeys/MacShortcutRemap.class
```
Beklenen: textkeys `macos-textkeys derlendi` der; grep `1` (constant-pool'da
sınıf adı var). (Davranışsal klavye testi headless güvenilir DEĞİL — bilinen
tuzak: sentetik KeyEvent/FocusEvent Aqua/KFM'de yanlış sonuç verir; GUI
doğrulaması Task 5 sonunda kullanıcıda.)

- [ ] **Step 4: Commit**

```bash
git add scripts/macos-textkeys/macostextkeys/MacShortcutRemap.java
git commit -m "feat(shortcuts): ⌘⇧V formatlı yapıştır (forceRich) — ⌘V akıllı varsayılan formatsız

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Tam build + entegrasyon doğrulama + elle GUI testi

**Files:**
- Modify: `CLAUDE.md` (PLAINPASTE bölümü — yeni varsayılan davranış notu)
- Doğrulama: paketli jar + agent (dosya değişikliği yok)

**Interfaces:**
- Consumes: Task 1-4'ün tamamı.
- Produces: imzalı .app; kullanıcıya elle test listesi.

- [ ] **Step 1: Tam build (textkeys DAHİL — bilinen tuzak)**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh download && bash scripts/build.sh patch \
  && bash scripts/build.sh lookagent && bash scripts/build.sh textkeys \
  && bash scripts/build.sh package && bash scripts/build.sh sign
```
Beklenen: `[pasterich] harici stilli yapıştırma yaması uygulandı.` ve
`[plainpaste] sağ tık Formatsız Yapıştır yaması uygulandı.` satırları; hata yok.
(`textkeys` atlanırsa agent jar bayat kalır → ⌘⇧V eski davranışta görünür.)

- [ ] **Step 2: Jar doğrulaması (javap)**

```bash
JAR=$(find build -name editor-app.jar | head -1)
unzip -l "$JAR" | grep 'macospasterich/PasteMode.class'
javap -classpath "$JAR" -c tr.com.havelsan.uyap.system.editor.common.text.hj 2>/dev/null \
  | grep -c 'macospasterich/PasteMode'
```
Beklenen: `PasteMode.class` listede; grep sayısı ≥ 2 (insertHtml + insertRtf
çağrıları). (TUZAK: jar'ı dosya sistemine AÇMA — case-insensitive FS sınıf ezer;
javap'ı `-classpath <jar>` ile doğrudan jar'dan çalıştır.)

- [ ] **Step 3: Headless testlerin son hâlde yeniden koşulması**

```bash
OUT=$(mktemp -d)
javac --release 11 -encoding UTF-8 -d "$OUT" scripts/macos-pasterich/macospasterich/*.java \
  tests/PasteModeTest.java tests/PasteMenuTest.java tests/PlainPasteStripTest.java \
  tests/PlainPasteParaFormatTest.java
java -cp "$OUT" PasteModeTest && java -cp "$OUT" PasteMenuTest \
  && java -cp "$OUT" PlainPasteStripTest && java -cp "$OUT" PlainPasteParaFormatTest
```
Beklenen: dört `… OK`.

- [ ] **Step 4: CLAUDE.md güncelle**

`CLAUDE.md` "Formatsız Yapıştır (PLAINPASTE=1, 2026-06)" bölümünün başına
(başlık satırından hemen sonra) şu paragraf eklenir:

```markdown
**VARSAYILAN DAVRANIŞ DEĞİŞTİ (2026-07):** ⌘V ve sağ tık "Yapıştır" artık
AKILLI — UDE-içi kopya (EditorDataFlavor, paste() başında kancasız işlenir)
FORMATLI, harici içerik FORMATSIZ (PASTERICH kancasının varsayılanı:
`macospasterich.PasteMode` cursorAttrs geçirir). ⌘⇧V ve sağ tık "Formatlı
Yapıştır" (YENİ öğe) `PasteMode.setForceRich` bayrağını try/finally ile set
edip zengin yolu zorlar; MacShortcutRemap `Fb.RICH_PASTE` bayrağı reflection
ile set eder + menü "Yapıştır" tıklar. "Formatsız Yapıştır" öğesi kalır
(hızlandırıcı göstergesi kaldırıldı). Spec:
`docs/superpowers/specs/2026-07-01-plain-paste-default-design.md`. Aşağıdaki
⌘⇧V=formatsız anlatımı TARİHSEL bağlamdır.
```

- [ ] **Step 5: Uygulamayı başlat + kullanıcıya elle test listesi**

```bash
pkill -f UyapDokumanEditoru; sleep 1
APP=$(find build -maxdepth 2 -name '*.app' | head -1)
"$APP/Contents/MacOS/UyapDokumanEditoru" &
```
(`open` KULLANMA — LaunchServices -54 + eski süreç penceresi tuzağı.)

Kullanıcıya sunulacak elle test listesi (elle test tercihi — sentetik klavye
güvenilmez):
1. Word/tarayıcıdan stilli metin kopyala → **⌘V** → formatsız yapışmalı
   (tablo/liste yapısı korunur, karakter stili imleçten).
2. Aynı panoyla → **⌘⇧V** → formatlı yapışmalı.
3. UDE içinde stilli metin kopyala → **⌘V** → FORMATLI yapışmalı (akıllı dal).
4. Sağ tık: "Yapıştır" (harici→formatsız), "Formatsız Yapıştır" (hep formatsız,
   hızlandırıcı göstergesi yok), "Formatlı Yapıştır" (⌘⇧V göstergeli, formatlı).
5. Editör-dışı alan (şerit arama kutusu): ⌘V ve ⌘⇧V düz yapıştırma.
Sorun teşhisi: `UDE_PLAINPASTELOG=1` / `UDE_PASTERICHLOG=1` →
`~/Library/Logs/ude-plainpaste.txt`, `ude-pasterich.txt`.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): formatsız yapıştırma varsayılan — ⌘V akıllı, ⌘⇧V formatlı notu

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
