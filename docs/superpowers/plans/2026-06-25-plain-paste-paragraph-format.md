# Formatsız Yapıştır — paragraf biçimi imleçten — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Formatsız Yapıştır düz modda gövde paragraflarının hizalama/aralık/girintisini kaynak yerine imlecin paragrafından alsın; liste/tablo/imaj yapısı korunsun.

**Architecture:** Tek dosya `NativeInsert.java`. `CURSOR_ATTRS` (char) deseninin paragraf eşi `CURSOR_PARA_ATTRS` eklenir: düz modda, ekleme öncesi imlecin paragraf biçimi beyaz-liste ile yakalanır; `insertParagraph` gövde paragraflarına bunu taban yapar (liste paragraflarında girinti kaynaktan korunur — hanging indent). Karakter stili (mevcut `CURSOR_ATTRS`), tablo hücresi düzeni, `stringFlavor` yedeği değişmez.

**Tech Stack:** Java (`--release 11`), `javax.swing.text` (StyledDocument/StyleConstants/AttributeSet). Test: bağımsız `main()` harness (JUnit yok), headless JTextPane.

## Global Constraints

- Derleme hedefi: `javac --release 11` (hedef Zulu 11; agent app-cp'siz).
- Yalnız saf `javax.swing.text` + `java.*` — UDE iç tipleri derleme-zamanı YASAK (NativeInsert reflection kullanır).
- Javassist gövde string'lerinde `//` yorum yok (bu işte Javassist YOK — yalnız Java kaynağı; kural ilgisiz ama dosya `apply_pasterich` ile derlenir).
- `ListLevel=Integer`, `ListId=Long` tip kuralları korunur (aksi halde UDE view ClassCastException).
- Paragraf öznitelik beyaz-listesi (yalnız bunlar imleçten kopyalanır): `StyleConstants.Alignment, LeftIndent, RightIndent, FirstLineIndent, SpaceAbove, SpaceBelow, LineSpacing, TabSet`.
- Karakter stili her yerde `CURSOR_ATTRS`'tan gelir; paragraf override onu EZMEZ.
- Test derle+çalıştır:
  `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/<Test>.java && java -cp /tmp/ppfmt <Test>`

---

### Task 1: `CURSOR_PARA_ATTRS` snapshot + gövde (liste-dışı) paragraf imleç biçimini alır

**Files:**
- Modify: `scripts/macos-pasterich/macospasterich/NativeInsert.java` (alan ~51; `insert()` 62-104; `insertParagraph` 177; yeni yardımcılar)
- Test: `tests/PlainPasteParaFormatTest.java` (Create)

**Interfaces:**
- Consumes: `macospasterich.RichPaste.insertInto(Object editor, String html, AttributeSet cursorAttrs)` (mevcut), `NativeInsert.CURSOR_ATTRS` deseni.
- Produces:
  - `static AttributeSet CURSOR_PARA_ATTRS` (NativeInsert alanı; düz modda imleç paragraf biçimi, değilse null)
  - `static AttributeSet snapshotParaFormat(StyledDocument doc, int offset)` — beyaz-liste anahtarlarını **lokal** (`isDefined`) okur (Task 3 `getAttribute`'a yükseltir)
  - `static SimpleAttributeSet paraAttrsPlain(Paragraph p)` — düz mod paragraf öznitelikleri (Task 1: tüm paragraflara `CURSOR_PARA_ATTRS` + liste işaretleri; Task 2 liste girintisini düzeltir)
  - `static void copyIfPresent(AttributeSet src, MutableAttributeSet dst, Object key)`

- [ ] **Step 1: Write the failing test**

`tests/PlainPasteParaFormatTest.java`:

```java
import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * NativeInsert düz-mod PARAGRAF biçimi testi (headless; UDE tipi gerektiren
 * tablo YOK). İmleç paragrafı iki-yana-yaslı + özel aralık/girinti iken düz
 * yapıştırılan gövde paragrafları o biçimi alır; kaynağın sağa-yaslısı düşer.
 * Derle+çalıştır:
 *   javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java
 *   java -cp /tmp/ppfmt PlainPasteParaFormatTest
 */
public class PlainPasteParaFormatTest {
    public static void main(String[] a) throws Exception {
        checkBodyAdoptsCursorFormat();
        checkRichPasteRegression();
        System.out.println("PlainPasteParaFormatTest OK");
    }

    /** Düz mod: gövde paragrafı imlecin JUSTIFIED + LineSpacing/SpaceBelow'unu alır. */
    static void checkBodyAdoptsCursorFormat() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        // İmleç paragrafı (para0) lokal olarak iki-yana-yaslı + özel aralık.
        SimpleAttributeSet cp = new SimpleAttributeSet();
        StyleConstants.setAlignment(cp, StyleConstants.ALIGN_JUSTIFIED);
        StyleConstants.setLineSpacing(cp, 0.5f);
        StyleConstants.setSpaceBelow(cp, 6f);
        doc.setParagraphAttributes(0, 1, cp, false);
        pane.setCaretPosition(0);

        // İmleç karakter stili (kaynaktan farklı).
        SimpleAttributeSet cursor = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cursor, "Calibri");
        StyleConstants.setFontSize(cursor, 16);

        // Kaynak: İKİ sağa-yaslı paragraf (2.si taze oluşur → drop kanıtı).
        String html =
              "<p style='text-align:right'>Birinci satir</p>"
            + "<p style='text-align:right'>Ikinci satir</p>";
        if (!macospasterich.RichPaste.insertInto(pane, html, cursor))
            throw new AssertionError("insertInto(plain) false");

        Element root = doc.getDefaultRootElement();
        boolean sawBody = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            String txt = doc.getText(para.getStartOffset(),
                    Math.min(para.getEndOffset(), doc.getLength()) - para.getStartOffset());
            if (txt.trim().isEmpty()) continue;
            sawBody = true;
            AttributeSet at = para.getAttributes();
            if (StyleConstants.getAlignment(at) != StyleConstants.ALIGN_JUSTIFIED)
                throw new AssertionError("gövde hizalaması imleçten alınmadı: '" + txt.trim()
                        + "' → align=" + StyleConstants.getAlignment(at));
            if (StyleConstants.getLineSpacing(at) != 0.5f)
                throw new AssertionError("gövde LineSpacing imleçten alınmadı: '" + txt.trim()
                        + "' → " + StyleConstants.getLineSpacing(at));
        }
        if (!sawBody) throw new AssertionError("hiç gövde paragrafı eklenmedi");
    }

    /** Rich paste (cursorAttrs=null): kaynak hizalaması KORUNUR — davranış değişmemeli. */
    static void checkRichPasteRegression() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        pane.setCaretPosition(0);
        String html = "<p style='text-align:right'>Sag yasli kaynak</p>";
        if (!macospasterich.RichPaste.insertInto(pane, html))   // 2-arg → cursorAttrs null
            throw new AssertionError("insertInto(rich) false");
        Element root = doc.getDefaultRootElement();
        boolean sawBody = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            String txt = doc.getText(para.getStartOffset(),
                    Math.min(para.getEndOffset(), doc.getLength()) - para.getStartOffset());
            if (txt.trim().isEmpty()) continue;
            sawBody = true;
            if (StyleConstants.getAlignment(para.getAttributes()) != StyleConstants.ALIGN_RIGHT)
                throw new AssertionError("rich paste kaynak hizalamasını kaybetti: '"
                        + txt.trim() + "'");
        }
        if (!sawBody) throw new AssertionError("rich: hiç gövde paragrafı eklenmedi");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java && java -cp /tmp/ppfmt PlainPasteParaFormatTest`
Expected: FAIL — `checkBodyAdoptsCursorFormat` → "gövde hizalaması imleçten alınmadı … align=2" (kaynağın `ALIGN_RIGHT`=2 sızar; mevcut `paraAttrs` kaynak hizalamasını kuruyor). `checkRichPasteRegression` zaten geçer.

- [ ] **Step 3: Add `CURSOR_PARA_ATTRS` field + whitelist + helpers**

`NativeInsert.java` içinde `CURSOR_ATTRS` alanının (~51) hemen ardına ekle:

```java
    /** Düz modda imleç paragraf biçimi (beyaz-liste). null = normal rich paste. */
    private static AttributeSet CURSOR_PARA_ATTRS;

    /** İmleçten kopyalanacak paragraf-düzen anahtarları (char/yapısal sızıntı yok). */
    private static final Object[] PARA_FORMAT_KEYS = {
        StyleConstants.Alignment, StyleConstants.LeftIndent, StyleConstants.RightIndent,
        StyleConstants.FirstLineIndent, StyleConstants.SpaceAbove, StyleConstants.SpaceBelow,
        StyleConstants.LineSpacing, StyleConstants.TabSet
    };
```

`paraAttrs` metodunun (~334) hemen ÜSTÜNE üç yardımcı ekle:

```java
    /**
     * İmlecin paragraf biçimini beyaz-liste ile yakalar. Task 1: yalnız LOKAL
     * (isDefined) attr. Task 3 mirası (getAttribute) ekler.
     */
    private static AttributeSet snapshotParaFormat(StyledDocument doc, int offset) {
        SimpleAttributeSet out = new SimpleAttributeSet();
        try {
            Element pe = doc.getParagraphElement(offset);
            AttributeSet as = pe.getAttributes();
            for (Object key : PARA_FORMAT_KEYS) {
                if (as.isDefined(key)) out.addAttribute(key, as.getAttribute(key));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    /** src'de varsa anahtarı dst'ye kopyalar. */
    private static void copyIfPresent(AttributeSet src, MutableAttributeSet dst, Object key) {
        Object v = src.getAttribute(key);
        if (v != null) dst.addAttribute(key, v);
    }

    /**
     * Düz mod paragraf öznitelikleri. Task 1: TÜM paragraflara imleç biçimi
     * (CURSOR_PARA_ATTRS) + liste paragraflarında kaynak liste işaretleri.
     * (Task 2 liste girintisini kaynaktan korur — hanging indent.)
     */
    private static SimpleAttributeSet paraAttrsPlain(Paragraph p) {
        SimpleAttributeSet pa = new SimpleAttributeSet();
        if (CURSOR_PARA_ATTRS != null) pa.addAttributes(CURSOR_PARA_ATTRS);
        if (p.list != null) {
            SimpleAttributeSet src = paraAttrs(p);   // kaynak (liste işaretleri dahil)
            copyIfPresent(src, pa, "Bulleted");
            copyIfPresent(src, pa, "Numbered");
            copyIfPresent(src, pa, "BulletType");
            copyIfPresent(src, pa, "NumberType");
            copyIfPresent(src, pa, "ListLevel");
            copyIfPresent(src, pa, "ListId");
        }
        return pa;
    }
```

- [ ] **Step 4: Wire snapshot into `insert()` + restore in finally**

`insert(Object editor, UdeDoc.Document model, AttributeSet cursorAttrs)` (62-104) düzenle. Üst kısım (63-64):

```java
        AttributeSet prev = CURSOR_ATTRS;
        AttributeSet prevPara = CURSOR_PARA_ATTRS;
        CURSOR_ATTRS = cursorAttrs;
```

`int start = tc.getCaretPosition();` (satır 68) hemen ALTINA, tablo sentinel'inden (77) ÖNCE:

```java
            if (cursorAttrs != null) CURSOR_PARA_ATTRS = snapshotParaFormat(doc, start);
```

`finally` (101-103):

```java
        } finally {
            CURSOR_ATTRS = prev;
            CURSOR_PARA_ATTRS = prevPara;
        }
```

`insertParagraph` (177) paragraf öznitelik satırını değiştir:

```java
        AttributeSet pAttrs = (CURSOR_PARA_ATTRS != null) ? paraAttrsPlain(para) : paraAttrs(para);
        doc.setParagraphAttributes(paraStart, pos - paraStart, pAttrs, true);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java && java -cp /tmp/ppfmt PlainPasteParaFormatTest`
Expected: PASS — `PlainPasteParaFormatTest OK`

- [ ] **Step 6: Run existing plain-paste test (regresyon)**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java && java -cp /tmp/ppfmt PlainPasteStripTest`
Expected: PASS — `PlainPasteStripTest OK` (char stili + Bulleted hâlâ korunuyor)

- [ ] **Step 7: Commit**

```bash
git add -f scripts/macos-pasterich/macospasterich/NativeInsert.java tests/PlainPasteParaFormatTest.java
git commit -m "feat(plainpaste): düz modda gövde paragrafı imleç biçimini alır

CURSOR_PARA_ATTRS snapshot + paraAttrsPlain; liste-dışı gövde paragrafları
hizalama/aralık/girintiyi imlecin paragrafından alır (beyaz-liste), kaynağın
sağa-yaslısı düşer. Rich paste (cursorAttrs=null) değişmez.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Liste paragrafı girintisi kaynaktan korunur (hanging indent)

**Files:**
- Modify: `scripts/macos-pasterich/macospasterich/NativeInsert.java` (`paraAttrsPlain` liste dalı; yeni `LIST_CURSOR_KEYS`)
- Test: `tests/PlainPasteParaFormatTest.java` (Modify — yeni kontrol + `main`'e çağrı)

**Interfaces:**
- Consumes: `paraAttrsPlain`, `copyIfPresent` (Task 1), `paraAttrs(Paragraph)` (mevcut).
- Produces: `static final Object[] LIST_CURSOR_KEYS` (liste paragrafında imleçten alınan anahtarlar: yalnız hizalama/aralık).

- [ ] **Step 1: Write the failing test**

`PlainPasteParaFormatTest.java` `main`'e çağrı ekle (`checkRichPasteRegression();`'dan sonra):

```java
        checkListKeepsSourceIndent();
```

Sınıfa yeni metot ekle:

```java
    /**
     * Liste paragrafı: işaret + KAYNAK girintisi korunur (imleç sıfır/farklı
     * girintisi EZMEZ — hanging indent); hizalama imleçten gelir.
     */
    static void checkListKeepsSourceIndent() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        // İmleç paragrafı: JUSTIFIED + BÜYÜK sol girinti (listeye SIZMAMALI).
        SimpleAttributeSet cp = new SimpleAttributeSet();
        StyleConstants.setAlignment(cp, StyleConstants.ALIGN_JUSTIFIED);
        StyleConstants.setLeftIndent(cp, 90f);
        doc.setParagraphAttributes(0, 1, cp, false);
        pane.setCaretPosition(0);

        SimpleAttributeSet cursor = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cursor, "Calibri");
        StyleConstants.setFontSize(cursor, 16);

        // Kaynak liste: girinti taşımaz (UDE list indent ListLevel'dan; LeftIndent=0).
        String html = "<ul><li>Madde bir</li></ul>";
        if (!macospasterich.RichPaste.insertInto(pane, html, cursor))
            throw new AssertionError("insertInto(plain list) false");

        Element root = doc.getDefaultRootElement();
        boolean sawList = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            AttributeSet at = root.getElement(p).getAttributes();
            if (at.getAttribute("Bulleted") == null && at.getAttribute("Numbered") == null) continue;
            sawList = true;
            // İmlecin 90f girintisi listeye sızMAMALI (kaynak 0).
            if (StyleConstants.getLeftIndent(at) != 0f)
                throw new AssertionError("liste girintisi imleçten sızdı (hanging indent bozuldu): "
                        + StyleConstants.getLeftIndent(at));
            // Hizalama imleçten (JUSTIFIED) gelmeli.
            if (StyleConstants.getAlignment(at) != StyleConstants.ALIGN_JUSTIFIED)
                throw new AssertionError("liste hizalaması imleçten alınmadı: "
                        + StyleConstants.getAlignment(at));
        }
        if (!sawList) throw new AssertionError("liste paragrafı bulunamadı");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java && java -cp /tmp/ppfmt PlainPasteParaFormatTest`
Expected: FAIL — "liste girintisi imleçten sızdı … 90.0" (Task 1 `paraAttrsPlain` listeye `CURSOR_PARA_ATTRS`'ı bütün ekliyor → imleç `LeftIndent=90` sızıyor).

- [ ] **Step 3: Refine `paraAttrsPlain` list branch**

`PARA_FORMAT_KEYS` tanımının altına ekle:

```java
    /** Liste paragrafında imleçten alınan anahtarlar (girinti/tabset DEĞİL → kaynak). */
    private static final Object[] LIST_CURSOR_KEYS = {
        StyleConstants.Alignment, StyleConstants.SpaceAbove,
        StyleConstants.SpaceBelow, StyleConstants.LineSpacing
    };
```

`paraAttrsPlain`'i değiştir:

```java
    private static SimpleAttributeSet paraAttrsPlain(Paragraph p) {
        SimpleAttributeSet pa = new SimpleAttributeSet();
        AttributeSet cur = CURSOR_PARA_ATTRS;
        if (p.list == null) {
            if (cur != null) pa.addAttributes(cur);   // 8 anahtarın hepsi imleçten
            return pa;
        }
        // Liste: hizalama/aralık imleçten, girinti/tabset + işaretler KAYNAKTAN.
        if (cur != null) {
            for (Object key : LIST_CURSOR_KEYS) copyIfPresent(cur, pa, key);
        }
        SimpleAttributeSet src = paraAttrs(p);
        copyIfPresent(src, pa, StyleConstants.LeftIndent);
        copyIfPresent(src, pa, StyleConstants.RightIndent);
        copyIfPresent(src, pa, StyleConstants.FirstLineIndent);
        copyIfPresent(src, pa, StyleConstants.TabSet);
        copyIfPresent(src, pa, "Bulleted");
        copyIfPresent(src, pa, "Numbered");
        copyIfPresent(src, pa, "BulletType");
        copyIfPresent(src, pa, "NumberType");
        copyIfPresent(src, pa, "ListLevel");
        copyIfPresent(src, pa, "ListId");
        return pa;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java && java -cp /tmp/ppfmt PlainPasteParaFormatTest`
Expected: PASS — `PlainPasteParaFormatTest OK` (gövde + rich + liste girinti hepsi geçer)

- [ ] **Step 5: Run existing plain-paste test (regresyon)**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java && java -cp /tmp/ppfmt PlainPasteStripTest`
Expected: PASS — `PlainPasteStripTest OK`

- [ ] **Step 6: Commit**

```bash
git add -f scripts/macos-pasterich/macospasterich/NativeInsert.java tests/PlainPasteParaFormatTest.java
git commit -m "fix(plainpaste): liste paragrafı girintisi kaynaktan korunur

Liste paragraflarında LeftIndent/RightIndent/FirstLineIndent/TabSet kaynaktan
(hanging indent ezilmesin); imleçten yalnız hizalama/aralık. agy+codex bulgusu.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Mirastan (stilden) gelen imleç biçimi de yakalanır (`getAttribute`)

**Files:**
- Modify: `scripts/macos-pasterich/macospasterich/NativeInsert.java` (`snapshotParaFormat`)
- Test: `tests/PlainPasteParaFormatTest.java` (Modify — yeni kontrol + `main`'e çağrı)

**Interfaces:**
- Consumes: `snapshotParaFormat` (Task 1).
- Produces: (yok — davranış düzeltmesi)

- [ ] **Step 1: Write the failing test**

`main`'e çağrı ekle (`checkListKeepsSourceIndent();`'dan sonra):

```java
        checkInheritedCursorAlignment();
```

Yeni metot ekle (import gerekli: `javax.swing.text.Style`):

```java
    /**
     * İmleç hizalaması LOKAL değil bir parent STİLDEN miras geliyorken bile
     * snapshot onu yakalamalı (getAttribute vs isDefined regresyonu).
     */
    static void checkInheritedCursorAlignment() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = (StyledDocument) pane.getDocument();
        // JUSTIFIED'ı bir mantıksal STİLE koy, para0'ın resolve-parent'ı yap
        // (lokal Alignment attr'ı YOK → isDefined false, getAttribute resolve eder).
        javax.swing.text.Style just = pane.addStyle("just", null);
        StyleConstants.setAlignment(just, StyleConstants.ALIGN_JUSTIFIED);
        doc.setLogicalStyle(0, just);
        pane.setCaretPosition(0);

        SimpleAttributeSet cursor = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cursor, "Calibri");
        StyleConstants.setFontSize(cursor, 16);

        if (!macospasterich.RichPaste.insertInto(pane,
                "<p style='text-align:right'>Miras testi</p>", cursor))
            throw new AssertionError("insertInto(plain inherited) false");

        Element root = doc.getDefaultRootElement();
        boolean sawBody = false;
        for (int p = 0; p < root.getElementCount(); p++) {
            Element para = root.getElement(p);
            String txt = doc.getText(para.getStartOffset(),
                    Math.min(para.getEndOffset(), doc.getLength()) - para.getStartOffset());
            if (txt.trim().isEmpty()) continue;
            sawBody = true;
            if (StyleConstants.getAlignment(para.getAttributes()) != StyleConstants.ALIGN_JUSTIFIED)
                throw new AssertionError("mirastan gelen hizalama yakalanmadı: align="
                        + StyleConstants.getAlignment(para.getAttributes()));
        }
        if (!sawBody) throw new AssertionError("miras: hiç gövde paragrafı eklenmedi");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java && java -cp /tmp/ppfmt PlainPasteParaFormatTest`
Expected: FAIL — "mirastan gelen hizalama yakalanmadı: align=2" (Task 1 `isDefined` lokal attr'a bakar, mirası kaçırır → snapshot boş → pasted para kaynağın `ALIGN_RIGHT`=2'sini alır).

- [ ] **Step 3: `snapshotParaFormat`'ı `getAttribute`'a yükselt**

`snapshotParaFormat` döngüsünü değiştir:

```java
            for (Object key : PARA_FORMAT_KEYS) {
                Object v = as.getAttribute(key);   // resolver zincirini izler (miras dahil)
                if (v != null) out.addAttribute(key, v);
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteParaFormatTest.java && java -cp /tmp/ppfmt PlainPasteParaFormatTest`
Expected: PASS — `PlainPasteParaFormatTest OK`

- [ ] **Step 5: Run existing plain-paste test (regresyon)**

Run: `javac --release 11 -d /tmp/ppfmt scripts/macos-pasterich/macospasterich/*.java tests/PlainPasteStripTest.java && java -cp /tmp/ppfmt PlainPasteStripTest`
Expected: PASS — `PlainPasteStripTest OK`

- [ ] **Step 6: Commit**

```bash
git add -f scripts/macos-pasterich/macospasterich/NativeInsert.java tests/PlainPasteParaFormatTest.java
git commit -m "fix(plainpaste): mirastan gelen imleç paragraf biçimini de yakala

snapshotParaFormat getAttribute(key) ile resolver zincirini izler; isDefined
yalnız lokal attr'a bakıp stilden gelen hizalama/aralığı kaçırıyordu (codex bulgusu).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Tam build + canlı GUI doğrulama (offset-0 tablo sentinel + hücre düzeni dahil)

> Headless test tablo (UDE `DocumentEx`) kuramaz → offset-0 tablo sentinel snapshot regresyonu ve tablo hücresi kaynak-düzeni manuel/probe ile doğrulanır.

**Files:**
- (yok — yalnız build + doğrulama)

**Interfaces:**
- Consumes: tüm önceki görevlerin `NativeInsert.java`'sı; `apply_pasterich` build adımı.

- [ ] **Step 1: Tam build + imzala**

Run:
```bash
bash scripts/build.sh download && bash scripts/build.sh patch \
  && bash scripts/build.sh lookagent && bash scripts/build.sh textkeys \
  && bash scripts/build.sh package && bash scripts/build.sh sign
```
Expected: hatasız tamamlanır (`apply_pasterich` `macospasterich/*.java`'yı derler).

- [ ] **Step 2: Paketlenmiş jar yamasını doğrula**

Run: `JAR=$(ls build/*.app/Contents/app/*.jar 2>/dev/null | head -1); unzip -p "$JAR" macospasterich/NativeInsert.class >/dev/null && echo "NativeInsert var" && javap -p -classpath "$JAR" macospasterich.NativeInsert | grep -c "paraAttrsPlain\|snapshotParaFormat"`
Expected: `NativeInsert var` + sayı `2` (her iki yeni metot paketlenmiş).

- [ ] **Step 3: Çalıştır + log aç**

Run: `pkill -f UyapDokumanEditoru; sleep 1; UDE_PLAINPASTELOG=1 build/*.app/Contents/MacOS/UyapDokumanEditoru &`
(Doğrudan binary — `open` LaunchServices -54 verebilir.)

- [ ] **Step 4: Kullanıcı elle GUI doğrulaması (proje tercihi)**

Kullanıcıya doğrulatılacak senaryolar:
1. UDE'de bir paragrafı **iki yana yaslı** yap + kendi satır aralığını ayarla; imleci oraya koy.
2. Word/tarayıcıdan **sağa-yaslı** stilli metin kopyala → **⌘⇧V** (veya sağ tık → Formatsız Yapıştır).
   → Yapışan gövde **iki yana yaslı** + imlecin aralığıyla gelmeli; sağa-yaslı DÜŞMELİ.
3. **Madde/numaralı liste** kopyala → Formatsız Yapıştır → işaretler korunmalı, metin hizası bozulmamalı (asılı girinti yerinde).
4. **Tablolu** içerik kopyala → Formatsız Yapıştır → tablo gelir; hücre metni imleç karakter stilinde ama hücre paragraf düzeni bozulmamış.
5. İmleç offset 0'da boş belgede tablo-başlangıçlı içerik → Formatsız Yapıştır → çökme yok, tablo Backspace ile silinebilir.

Teşhis gerekirse: `~/Library/Logs/ude-plainpaste.txt`.

- [ ] **Step 5: Memory + CLAUDE.md güncelle**

`plain-paste` memory dosyasına ve `CLAUDE.md` PLAINPASTE bölümüne "paragraf biçimi imleçten (liste girintisi kaynaktan; getAttribute miras)" notunu ekle. Commit:

```bash
git add -f CLAUDE.md "$HOME/.claude/projects/-Users-saidsurucu-Documents-GitHub-ude-mac-arm/memory/plain-paste.md"
git commit -m "docs(plainpaste): paragraf biçimi imleçten — CLAUDE.md + memory notu

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- Spec §1 (CURSOR_PARA_ATTRS snapshot, finally restore, sentinel-öncesi) → Task 1 Step 3-4. ✓
- Spec §2 (beyaz liste; getAttribute) → Task 1 Step 3 (whitelist, isDefined) + Task 3 (getAttribute yükseltme). ✓
- Spec §3 (paraAttrsPlain; liste girintisi kaynaktan; replace=true; tablo hücresi/breakAttrs/stringFlavor değişmez) → Task 1 + Task 2; hücre `paraAttrs` dokunulmadı. ✓
- Spec §4 (UDE aralık anahtarı probe) → Task 4 Step 4 GUI doğrulaması (özel anahtar çıkarsa beyaz listeye eklenir; canlı senaryo 2 satır-aralığını test eder). ✓
- Spec Test (6 senaryo): gövde=T1/checkBody, liste girinti regresyonu=T2, miras hizalama=T3, rich regresyon=T1/checkRich, offset-0 sentinel + hücre düzeni=Task 4 manuel (headless tablo kuramaz, spec bunu kabul ediyor). ✓
- Spec Build/dağıtım (apply_pasterich, textkeys, log) → Task 4 Step 1-3. ✓

**2. Placeholder scan:** Tüm adımlarda gerçek kod/komut var; TBD/TODO yok. ✓

**3. Type consistency:** `snapshotParaFormat(StyledDocument,int)→AttributeSet`, `paraAttrsPlain(Paragraph)→SimpleAttributeSet`, `copyIfPresent(AttributeSet,MutableAttributeSet,Object)`, `CURSOR_PARA_ATTRS:AttributeSet`, `PARA_FORMAT_KEYS`/`LIST_CURSOR_KEYS:Object[]` — Task 1/2/3 arasında tutarlı. `insertParagraph` `paraAttrsPlain`/`paraAttrs` dallanması tek noktada. ✓
