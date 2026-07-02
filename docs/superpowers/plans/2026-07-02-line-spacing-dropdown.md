# Satır Aralığı Dropdown'u (LINESPACING=1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Giriş sekmesi "Paragraf" bandına Word-tarzı satır-aralığı dropdown'u (1,0 · 1,15 · 1,5 · 2,0 · 2,5 · 3,0) — UDF formatında sıfır değişiklik.

**Architecture:** textkeys javaagent'ına yeni `macoslinespacing` paketi. Saf-Swing çekirdek (`LineSpacingApply`, headless test edilebilir) + yansıma tabanlı şerit UI (`LineSpacingMenu`, Flamingo `JCommandButton` POPUP_ONLY, ribbon MODELİNDEN eklenir — darkpage/footnote deseni). UDF değeri = görünen aralık − 1 (float `LineSpacing`, formatta zaten var).

**Tech Stack:** Java 11 (`javac --release 11`, app-cp'siz), Flamingo yansıma API'si, bash 3.2 build.sh.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-02-line-spacing-dropdown-design.md`
- Agent kodu UYGULAMAYI ASLA DÜŞÜRMEZ: her giriş noktası try/catch, hata = sessiz no-op + log.
- Caret'e dokunma YOK (`moveDot`/`setCaretPosition` ile seçim oynatmak yasak — bilinen NPE zinciri). Tek mutasyon `setParagraphAttributes(..., false)`.
- `replace=false` (merge) — diğer paragraf öznitelikleri (hizalama, girinti, liste anahtarları) korunmalı.
- Komut-buton bandına `JRibbonComponent` EKLENMEZ (mixing exception) — native `JCommandButton` şart.
- Agent jar paketleme satırına yeni paket eklenmeli — eklenmezse `NoClassDefFoundError` premain'de JVM'i açılışta düşürür (macosfootnote dersi).
- Log dosyaya (`UDE_LSLOG=1` → `~/Library/Logs/ude-linespacing.txt`) — System.err uygulama tarafından yutulur.
- bash 3.2 + `set -u`: boş diziler `${arr[@]+"${arr[@]}"}` ile.
- Değer eşleme toleransı: |mevcut − seçenek| < 0.02 (float hassasiyeti: 1.15f−1f = 0.14999998).
- Etiketler Türkçe virgüllü: "1,0" "1,15" "1,5" "2,0" "2,5" "3,0".

---

### Task 1: LineSpacingApply çekirdeği (TDD, headless)

**Files:**
- Create: `scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java`
- Test: `tests/LineSpacingApplyTest.java`

**Interfaces:**
- Produces (Task 2 ve test bunları kullanır):
  - `public static final float[] VALUES = {1.0f, 1.15f, 1.5f, 2.0f, 2.5f, 3.0f};`
  - `public static final String[] LABELS = {"1,0", "1,15", "1,5", "2,0", "2,5", "3,0"};`
  - `public static boolean apply(JTextComponent tc, float display)` — seçime (yoksa imlecin paragrafına) `LineSpacing = display−1` basar; StyledDocument değilse false.
  - `public static float current(JTextComponent tc)` — imlecin paragrafının görünen aralığı (`getLineSpacing()+1`).
  - `public static boolean matches(float current, float option)` — |fark| < 0.02f.

- [ ] **Step 1: Failing test'i yaz**

`tests/LineSpacingApplyTest.java` (repo test deseni: `main()` + `javac`+`java` elle; assert yerine `fail()`):

```java
import javax.swing.JTextPane;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import macoslinespacing.LineSpacingApply;

/**
 * LineSpacingApply birim testi (headless, UDE tipi gerekmez).
 * Derle + çalıştır:
 *   javac --release 11 -encoding UTF-8 -d /tmp/lsout \
 *     scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java tests/LineSpacingApplyTest.java
 *   java -cp /tmp/lsout LineSpacingApplyTest
 */
public class LineSpacingApplyTest {
    private static int fails = 0;

    public static void main(String[] a) throws Exception {
        testSingleParagraph();
        testSelectionSpansParagraphs();
        testOtherAttrsPreserved();
        testResetToSingle();
        testCurrentAndMatches();
        if (fails > 0) { System.out.println("FAIL: " + fails); System.exit(1); }
        System.out.println("OK");
    }

    private static JTextPane pane(String text) throws Exception {
        JTextPane p = new JTextPane();
        p.getDocument().insertString(0, text, null);
        return p;
    }

    /** 1) Tek paragraf: imleç içindeyken 1,5 → LineSpacing==0.5 */
    private static void testSingleParagraph() throws Exception {
        JTextPane p = pane("birinci paragraf\nikinci paragraf\n");
        p.setCaretPosition(3); // 1. paragrafın içi
        check("apply döndü", LineSpacingApply.apply(p, 1.5f));
        StyledDocument doc = (StyledDocument) p.getDocument();
        float ls1 = StyleConstants.getLineSpacing(doc.getParagraphElement(3).getAttributes());
        float ls2 = StyleConstants.getLineSpacing(doc.getParagraphElement(20).getAttributes());
        check("p1 LineSpacing==0.5", Math.abs(ls1 - 0.5f) < 0.001f);
        check("p2 dokunulmadı (0)", ls2 == 0f);
    }

    /** 2) Çok paragraflı seçim: her iki paragrafa da uygulanır */
    private static void testSelectionSpansParagraphs() throws Exception {
        JTextPane p = pane("birinci paragraf\nikinci paragraf\n");
        p.setSelectionStart(3);
        p.setSelectionEnd(20); // 2. paragrafın içine taşar
        check("apply döndü", LineSpacingApply.apply(p, 2.0f));
        StyledDocument doc = (StyledDocument) p.getDocument();
        check("p1==1.0", Math.abs(StyleConstants.getLineSpacing(
                doc.getParagraphElement(3).getAttributes()) - 1.0f) < 0.001f);
        check("p2==1.0", Math.abs(StyleConstants.getLineSpacing(
                doc.getParagraphElement(20).getAttributes()) - 1.0f) < 0.001f);
    }

    /** 3) replace=false: mevcut hizalama/girinti korunur */
    private static void testOtherAttrsPreserved() throws Exception {
        JTextPane p = pane("paragraf metni\n");
        StyledDocument doc = (StyledDocument) p.getDocument();
        SimpleAttributeSet pre = new SimpleAttributeSet();
        StyleConstants.setAlignment(pre, StyleConstants.ALIGN_RIGHT);
        StyleConstants.setLeftIndent(pre, 42f);
        doc.setParagraphAttributes(0, 1, pre, false);
        p.setCaretPosition(3);
        LineSpacingApply.apply(p, 1.5f);
        Element para = doc.getParagraphElement(3);
        check("hizalama korunur", StyleConstants.getAlignment(para.getAttributes())
                == StyleConstants.ALIGN_RIGHT);
        check("girinti korunur", StyleConstants.getLeftIndent(para.getAttributes()) == 42f);
        check("aralık geldi", Math.abs(StyleConstants.getLineSpacing(
                para.getAttributes()) - 0.5f) < 0.001f);
    }

    /** 4) 1,0 seçimi mevcut 0.5'i 0.0'a indirir */
    private static void testResetToSingle() throws Exception {
        JTextPane p = pane("paragraf metni\n");
        p.setCaretPosition(3);
        LineSpacingApply.apply(p, 1.5f);
        LineSpacingApply.apply(p, 1.0f);
        StyledDocument doc = (StyledDocument) p.getDocument();
        check("sıfırlandı", StyleConstants.getLineSpacing(
                doc.getParagraphElement(3).getAttributes()) == 0f);
    }

    /** 5) current() + matches() eşlemesi (1,15 float hassasiyeti dahil) */
    private static void testCurrentAndMatches() throws Exception {
        JTextPane p = pane("paragraf metni\n");
        p.setCaretPosition(3);
        check("varsayılan 1,0", LineSpacingApply.matches(LineSpacingApply.current(p), 1.0f));
        LineSpacingApply.apply(p, 1.15f);
        check("1,15 eşleşir", LineSpacingApply.matches(LineSpacingApply.current(p), 1.15f));
        check("1,5 eşleşmez", !LineSpacingApply.matches(LineSpacingApply.current(p), 1.5f));
        check("dizi boyları", LineSpacingApply.VALUES.length == 6
                && LineSpacingApply.LABELS.length == 6);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FAIL ") + name);
        if (!ok) fails++;
    }
}
```

- [ ] **Step 2: Testin FAIL ettiğini gör (sınıf yok)**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
mkdir -p /tmp/lsout && javac --release 11 -encoding UTF-8 -d /tmp/lsout \
  scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java tests/LineSpacingApplyTest.java
```
Beklenen: `file not found: scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java` (derleme hatası).

- [ ] **Step 3: LineSpacingApply'ı yaz**

`scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java`:

```java
package macoslinespacing;

import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Satır aralığı çekirdeği: seçime (yoksa imlecin paragrafına) UDE'nin
 * kullandığı StyleConstants.LineSpacing özniteliğini basar.
 *
 * Kodlama (UDF ile birebir): saklanan değer = görünen aralık − 1
 * (1,0→0.0, 1,15→0.15, 1,5→0.5). UDE Paragraf diyaloğu (gui.cM) ve
 * udf-converter-go aynı dönüşümü yapar; content.xml <paragraph
 * LineSpacing="..."> float — formatta değişiklik yok.
 *
 * replace=false: diğer paragraf öznitelikleri (hizalama, girinti, liste
 * anahtarları) korunur. Caret'e dokunulmaz (moveDot NPE zinciri).
 */
public final class LineSpacingApply {

    public static final float[] VALUES = {1.0f, 1.15f, 1.5f, 2.0f, 2.5f, 3.0f};
    public static final String[] LABELS = {"1,0", "1,15", "1,5", "2,0", "2,5", "3,0"};

    private LineSpacingApply() {}

    /** display = görünen aralık (1.5 gibi). StyledDocument değilse false. */
    public static boolean apply(JTextComponent tc, float display) {
        Document d = tc.getDocument();
        if (!(d instanceof StyledDocument)) return false;
        StyledDocument doc = (StyledDocument) d;
        int s = Math.min(tc.getSelectionStart(), tc.getSelectionEnd());
        int e = Math.max(tc.getSelectionStart(), tc.getSelectionEnd());
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(attrs, display <= 1.0f ? 0f : display - 1f);
        doc.setParagraphAttributes(s, e - s, attrs, false);
        return true;
    }

    /** İmlecin paragrafının görünen aralığı (öznitelik yoksa 1.0). */
    public static float current(JTextComponent tc) {
        Document d = tc.getDocument();
        if (!(d instanceof StyledDocument)) return 1.0f;
        StyledDocument doc = (StyledDocument) d;
        return StyleConstants.getLineSpacing(
                doc.getParagraphElement(tc.getCaretPosition()).getAttributes()) + 1f;
    }

    /** Float hassasiyeti için gevşek eşleme (1.15f−1f = 0.14999998). */
    public static boolean matches(float current, float option) {
        return Math.abs(current - option) < 0.02f;
    }
}
```

- [ ] **Step 4: Testin PASS ettiğini gör**

```bash
mkdir -p /tmp/lsout && javac --release 11 -encoding UTF-8 -d /tmp/lsout \
  scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java tests/LineSpacingApplyTest.java \
  && java -cp /tmp/lsout LineSpacingApplyTest
```
Beklenen: her satır `ok`, son satır `OK`, exit 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-textkeys/macoslinespacing/LineSpacingApply.java tests/LineSpacingApplyTest.java
git commit -m "feat(linespacing): LineSpacingApply çekirdeği — seçime/paragrafa LineSpacing=değer−1 (headless test)"
```

---

### Task 2: LsLog + LineSpacingMenu (şerit UI, yansıma)

**Files:**
- Create: `scripts/macos-textkeys/macoslinespacing/LsLog.java`
- Create: `scripts/macos-textkeys/macoslinespacing/LineSpacingMenu.java`

**Interfaces:**
- Consumes: `LineSpacingApply.VALUES/LABELS/apply/current/matches` (Task 1).
- Produces: `public static void install()` (Task 3 bunu `Class.forName` ile çağırır); `LsLog.log(String)`.

**Bağlam (uygulayıcı için):** Bu sınıf UDE'nin app jar'ına classpath'siz derlenir — Flamingo/UDE tiplerine derleme-zamanı referans YASAK, her şey yansıma. Doğrulanmış Flamingo imzaları (bundled jar'dan javap ile):
- `JCommandButton(String, ResizableIcon)`; `setCommandButtonKind(JCommandButton$CommandButtonKind)` (enum sabiti `POPUP_ONLY`); `setPopupCallback(PopupPanelCallback)`.
- `PopupPanelCallback` arayüzü: `JPopupPanel getPopupPanel(JCommandButton)`.
- `JCommandPopupMenu()` no-arg; `addMenuButton(JCommandMenuButton)`.
- `JCommandMenuButton(String, ResizableIcon)` — icon null olabilir (metin-only menü öğesi).
- `JRibbonBand.addCommandButton(AbstractCommandButton, RibbonElementPriority)` — `RibbonElementPriority.MEDIUM`.
- `ResizableIcon extends javax.swing.Icon` + `setDimension(Dimension)` — `java.lang.reflect.Proxy` ile uygulanır (footnote ikon deseni).
- Ribbon modeli: `ribbon.getTaskCount()` / `getTask(int)` / `task.getTitle()` / `task.getBands()` / `band.getTitle()` (MacLook `addDarkPageToggle` deseni). Hedef: task başlığı `"Giriş"`, band başlığı `"Paragraf"` (bytecode'dan doğrulanmış gerçek başlıklar).
- Popup kapatma: `org.pushingpixels.flamingo.api.common.popup.PopupPanelManager.defaultManager().hidePopups(null)`.

- [ ] **Step 1: LsLog'u yaz** (TrLog deseni)

```java
package macoslinespacing;

import java.io.File;
import java.io.FileWriter;

/** UDE_LSLOG=1 → ~/Library/Logs/ude-linespacing.txt (System.err yutulur). */
final class LsLog {

    private static final boolean ON = "1".equals(System.getenv("UDE_LSLOG"));

    private LsLog() {}

    static void log(String msg) {
        if (!ON) return;
        try {
            File f = new File(System.getProperty("user.home"),
                    "Library/Logs/ude-linespacing.txt");
            FileWriter w = new FileWriter(f, true);
            try { w.write(System.currentTimeMillis() + " " + msg + "\n"); } finally { w.close(); }
        } catch (Throwable ignore) {
            // Log asla uygulamayı etkilememeli.
        }
    }
}
```

- [ ] **Step 2: LineSpacingMenu'yu yaz**

```java
package macoslinespacing;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Giriş sekmesi "Paragraf" bandına Word-tarzı Satır Aralığı dropdown'u.
 * Flamingo'ya derleme bağımlılığı YOK — tamamı yansıma (agent app-cp'siz).
 * Kurulum FOCUS_GAINED'de idempotan (footnote ensureRibbon deseni): bileşen
 * ağacı yerine ribbon MODELİNDEN band bulunur (darkpage dersi).
 */
public final class LineSpacingMenu {

    private static final String GUARD = "macoslinespacing.btn";
    /** Son odaklanan UDE editörü — menü eylemi popup odağı çalmışken bile hedefi bilir. */
    private static WeakReference<JTextComponent> lastEditor = new WeakReference<>(null);

    private LineSpacingMenu() {}

    public static void install() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() != FocusEvent.FOCUS_GAINED) return;
                    Object src = e.getSource();
                    if (!(src instanceof JTextComponent)) return;
                    JTextComponent tc = (JTextComponent) src;
                    if (isEditor(tc)) lastEditor = new WeakReference<>(tc);
                    java.awt.Window w = SwingUtilities.getWindowAncestor(tc);
                    if (w instanceof JFrame) {
                        try { ensureRibbon((JFrame) w); }
                        catch (Throwable t) { LsLog.log("ensureRibbon: " + t); }
                    }
                }
            }, AWTEvent.FOCUS_EVENT_MASK);
            LsLog.log("install tamam");
        } catch (Throwable t) {
            LsLog.log("install: " + t);
        }
    }

    /** UDE belge editörü mü? (MacShortcutRemap.isEditor deseni) */
    private static boolean isEditor(Component c) {
        for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals("tr.com.havelsan.uyap.system.swing.wp.a.f")
                    || n.equals("tr.com.havelsan.uyap.system.editor.common.text.hj")) return true;
        }
        return false;
    }

    /** Giriş > Paragraf bandına butonu bir kez ekler (clientProperty guard). */
    private static void ensureRibbon(JFrame f) throws Exception {
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon == null) return;
        JComponent r = (JComponent) ribbon;
        if (Boolean.TRUE.equals(r.getClientProperty(GUARD))) return;

        Object targetBand = null;
        int taskCount = (Integer) ribbon.getClass().getMethod("getTaskCount").invoke(ribbon);
        for (int i = 0; i < taskCount && targetBand == null; i++) {
            Object task = ribbon.getClass().getMethod("getTask", int.class).invoke(ribbon, i);
            String tTitle = (String) task.getClass().getMethod("getTitle").invoke(task);
            if (!"Giriş".equals(tTitle)) continue;
            java.util.List<?> bands = (java.util.List<?>)
                    task.getClass().getMethod("getBands").invoke(task);
            for (Object band : bands) {
                String bTitle = (String) band.getClass().getMethod("getTitle").invoke(band);
                if ("Paragraf".equals(bTitle)) { targetBand = band; break; }
            }
        }
        if (targetBand == null) { LsLog.log("Paragraf bandı bulunamadı"); return; }

        ClassLoader cl = ribbon.getClass().getClassLoader();
        Class<?> riCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.icon.ResizableIcon", false, cl);
        Object icon = makeIcon(riCls);

        Class<?> btnCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.JCommandButton", false, cl);
        Object btn = btnCls.getConstructor(String.class, riCls)
                .newInstance("Satır Aralığı", icon);

        Class<?> kindCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.JCommandButton$CommandButtonKind",
                false, cl);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object popupOnly = Enum.valueOf((Class<? extends Enum>) kindCls, "POPUP_ONLY");
        btnCls.getMethod("setCommandButtonKind", kindCls).invoke(btn, popupOnly);
        ((JComponent) btn).setToolTipText("Satırlar arasındaki mesafeyi ayarlar");

        Class<?> cbCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.popup.PopupPanelCallback", false, cl);
        Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{cbCls},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                        if ("getPopupPanel".equals(m.getName())) return buildPopup(cl);
                        if ("equals".equals(m.getName())) return p == a[0];
                        if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                        return null;
                    }
                });
        btnCls.getMethod("setPopupCallback", cbCls).invoke(btn, callback);

        Class<?> prioCls = Class.forName(
                "org.pushingpixels.flamingo.api.ribbon.RibbonElementPriority", false, cl);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object medium = Enum.valueOf((Class<? extends Enum>) prioCls, "MEDIUM");
        Class<?> acbCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.AbstractCommandButton", false, cl);
        targetBand.getClass().getMethod("addCommandButton", acbCls, prioCls)
                .invoke(targetBand, btn, medium);
        r.putClientProperty(GUARD, Boolean.TRUE);
        LsLog.log("buton eklendi (Giriş > Paragraf)");
    }

    /** Popup menü: 6 seçenek; mevcut değer "✓ " önekiyle işaretli. */
    private static Object buildPopup(ClassLoader cl) throws Exception {
        Class<?> menuCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.popup.JCommandPopupMenu", false, cl);
        Object menu = menuCls.getConstructor().newInstance();
        Class<?> itemCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.JCommandMenuButton", false, cl);
        Class<?> riCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.icon.ResizableIcon", false, cl);

        JTextComponent editor = lastEditor.get();
        float cur = (editor != null) ? LineSpacingApply.current(editor) : 1.0f;

        for (int i = 0; i < LineSpacingApply.VALUES.length; i++) {
            final float value = LineSpacingApply.VALUES[i];
            String label = (LineSpacingApply.matches(cur, value) ? "✓ " : "   ")
                    + LineSpacingApply.LABELS[i];
            Object item = itemCls.getConstructor(String.class, riCls)
                    .newInstance(label, null);
            ActionListener al = ev -> {
                try {
                    JTextComponent tc = lastEditor.get();
                    if (tc != null && tc.isShowing()) {
                        boolean ok = LineSpacingApply.apply(tc, value);
                        LsLog.log("uygula " + value + " ok=" + ok
                                + " sel=[" + tc.getSelectionStart() + "," + tc.getSelectionEnd() + "]");
                        tc.requestFocusInWindow(); // odak editöre dönsün (renk modu dersi)
                    } else {
                        LsLog.log("uygula " + value + ": editör yok");
                    }
                    hidePopups(cl);
                } catch (Throwable t) { LsLog.log("uygula: " + t); }
            };
            itemCls.getMethod("addActionListener", ActionListener.class).invoke(item, al);
            menuCls.getMethod("addMenuButton", itemCls).invoke(menu, item);
        }
        return menu;
    }

    private static void hidePopups(ClassLoader cl) {
        try {
            Class<?> pm = Class.forName(
                    "org.pushingpixels.flamingo.api.common.popup.PopupPanelManager", false, cl);
            Object mgr = pm.getMethod("defaultManager").invoke(null);
            pm.getMethod("hidePopups", Component.class).invoke(mgr, (Component) null);
        } catch (Throwable t) { LsLog.log("hidePopups: " + t); }
    }

    /** Vektör ikon: iki yönlü dikey ok + metin satırları (Word glyph'i).
     *  Proxy ile ResizableIcon (footnote deseni) — her DPI'de keskin; renk
     *  bileşenin foreground'ından → koyu modda kendiliğinden uyar. */
    private static Object makeIcon(Class<?> riCls) {
        final Dimension[] dim = { new Dimension(32, 32) };
        return Proxy.newProxyInstance(riCls.getClassLoader(), new Class<?>[]{riCls},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        switch (m.getName()) {
                            case "setDimension": dim[0] = (Dimension) a[0]; return null;
                            case "getIconWidth": return dim[0].width;
                            case "getIconHeight": return dim[0].height;
                            case "paintIcon":
                                paintGlyph((Component) a[0], (Graphics) a[1],
                                        (Integer) a[2], (Integer) a[3], dim[0]);
                                return null;
                            case "equals": return p == a[0];
                            case "hashCode": return System.identityHashCode(p);
                            default: return null;
                        }
                    }
                });
    }

    private static void paintGlyph(Component c, Graphics g, int x, int y, Dimension d) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = (c != null && c.getForeground() != null)
                    ? c.getForeground() : new Color(0x44, 0x44, 0x44);
            g2.setColor(fg);
            int w = d.width, h = d.height;
            int pad = Math.max(2, w / 8);
            int ax = x + pad + 2;               // ok sütunu
            int tx = x + w / 2 - 1;             // metin çizgileri başlangıcı
            int top = y + pad, bot = y + h - pad;
            // dikey çift ok
            g2.drawLine(ax, top, ax, bot);
            g2.drawLine(ax - 3, top + 3, ax, top);
            g2.drawLine(ax + 3, top + 3, ax, top);
            g2.drawLine(ax - 3, bot - 3, ax, bot);
            g2.drawLine(ax + 3, bot - 3, ax, bot);
            // metin satırları (4 çizgi, eşit aralık)
            int lines = 4;
            for (int i = 0; i < lines; i++) {
                int ly = top + (bot - top) * i / (lines - 1);
                g2.drawLine(tx, ly, x + w - pad, ly);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Basit sınıf-adı araması (MacLook.findByClassName deseni). */
    private static Component findByClassName(Component root, String simpleName) {
        if (root.getClass().getSimpleName().equals(simpleName)) return root;
        if (root instanceof Container) {
            for (Component k : ((Container) root).getComponents()) {
                Component hit = findByClassName(k, simpleName);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
```

- [ ] **Step 3: Derlemenin geçtiğini gör (Flamingo'suz — hepsi yansıma)**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
javac --release 11 -encoding UTF-8 -d /tmp/lsout2 scripts/macos-textkeys/macoslinespacing/*.java
echo "exit=$?"; ls /tmp/lsout2/macoslinespacing/
```
Beklenen: `exit=0`; `LineSpacingApply.class LineSpacingMenu.class LsLog.class` (+ anonim/lambda sınıfları).

- [ ] **Step 4: Task 1 testinin hâlâ geçtiğini gör**

```bash
java -cp /tmp/lsout LineSpacingApplyTest
```
Beklenen: `OK`.

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-textkeys/macoslinespacing/LsLog.java scripts/macos-textkeys/macoslinespacing/LineSpacingMenu.java
git commit -m "feat(linespacing): şerit dropdown'u — Giriş>Paragraf bandına JCommandButton popup (yansıma, footnote/darkpage deseni)"
```

---

### Task 3: MacTextKeys kancası + build.sh (bayrak, derleme, jar)

**Files:**
- Modify: `scripts/macos-textkeys/macostextkeys/MacTextKeys.java` (install() gövdesi, `TextReplace.install();` satırından sonra)
- Modify: `scripts/build.sh` (bayrak bloğu ~satır 64; `textkeys()` ~satır 266-280; jar paketleme ~satır 800-801)

**Interfaces:**
- Consumes: `macoslinespacing.LineSpacingMenu.install()` (Task 2).
- Produces: `LINESPACING=1` env bayrağı (varsayılan açık); `macos-textkeys.jar` içinde `macoslinespacing/` paketi.

- [ ] **Step 1: MacTextKeys.install()'a yansıma kancası ekle**

`TextReplace.install();` satırından hemen sonra:

```java
        // Giriş sekmesine Word-tarzı Satır Aralığı dropdown'u (LINESPACING=1).
        // Doğrudan referans YOK: LINESPACING=0 build'inde paket jar'da olmaz,
        // ClassNotFoundException sessizce yutulur — agent geri kalanı etkilenmez.
        try {
            Class.forName("macoslinespacing.LineSpacingMenu")
                 .getMethod("install").invoke(null);
        } catch (ClassNotFoundException e) {
            // LINESPACING=0 — paket bilinçli dışarıda.
        } catch (Throwable t) {
            System.err.println("[macos-textkeys] linespacing kurulamadı: " + t);
        }
```

- [ ] **Step 2: build.sh bayrak satırı ekle**

`PDFFRESH="${PDFFRESH:-1}"` satırının (64) altına:

```bash
LINESPACING="${LINESPACING:-1}" # 1=açık (varsayılan; Giriş sekmesinde Satır Aralığı dropdown'u — Word seti 1,0..3,0) | 0=kapalı
```

- [ ] **Step 3: textkeys() sonuna LINESPACING=0 budaması ekle**

`textkeys()` içinde, derleme `c_ok` satırından sonra (dylib bölümünden önce):

```bash
	# LINESPACING=0: satır-aralığı paketi jar'a girmesin (MacTextKeys yansıma
	# ile arar; sınıf yoksa sessizce atlar).
	[ "$LINESPACING" = "1" ] || { rm -rf "$BUILD/_textkeys/macoslinespacing"; c_info "[textkeys] LINESPACING=0, macoslinespacing budandı."; }
```

- [ ] **Step 4: jar paketleme satırını çok-paketli yap**

`package` içindeki mevcut satırı:

```bash
	( cd "$BUILD/_textkeys" && "$(dirname "$jp")/jar" cfm "$in/macos-textkeys.jar" MANIFEST.MF macostextkeys )
```

şununla değiştir (macosfootnote NoClassDefFound dersi — derlenen her paket jar'a girmeli):

```bash
	local tkpkgs="macostextkeys"
	[ -d "$BUILD/_textkeys/macoslinespacing" ] && tkpkgs="$tkpkgs macoslinespacing"
	( cd "$BUILD/_textkeys" && "$(dirname "$jp")/jar" cfm "$in/macos-textkeys.jar" MANIFEST.MF $tkpkgs )
```

(`$tkpkgs` kasıtlı tırnaksız — sözcük bölünmesi paket listesi üretir.)

- [ ] **Step 5: textkeys derlemesini çalıştır, sınıfları doğrula**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh textkeys
ls build/_textkeys/macoslinespacing/ | head
```
Beklenen: derleme `c_ok`; `LineSpacingApply.class`, `LineSpacingMenu.class`, `LsLog.class` listede.

- [ ] **Step 6: Commit**

```bash
git add scripts/macos-textkeys/macostextkeys/MacTextKeys.java scripts/build.sh
git commit -m "feat(linespacing): LINESPACING=1 bayrağı — textkeys derleme/budama + jar paketleme + MacTextKeys yansıma kancası"
```

---

### Task 4: Tam build + paketlenmiş jar doğrulaması + canlı GUI testi

**Files:**
- Yok (build + doğrulama görevi).

**Interfaces:**
- Consumes: Task 1-3'ün tamamı.

- [ ] **Step 1: Tam build (textkeys ATLANMAZ — bayat jar tuzağı)**

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh download && bash scripts/build.sh patch \
  && bash scripts/build.sh lookagent && bash scripts/build.sh textkeys \
  && bash scripts/build.sh package && bash scripts/build.sh sign
```
Beklenen: her adım `c_ok`, sonda imzalı .app.

- [ ] **Step 2: Paketlenmiş agent jar'ında sınıfları doğrula (GUI'den ÖNCE — antet dersi)**

```bash
unzip -l "build/Uyap Doküman Editörü.app/Contents/app/macos-textkeys.jar" | grep -c macoslinespacing
```
Beklenen: ≥ 3 (LineSpacingApply, LineSpacingMenu, LsLog + iç sınıflar).

- [ ] **Step 3: Uygulamayı log ile başlat**

```bash
pkill -f UyapDokumanEditoru 2>/dev/null; sleep 1
UDE_LSLOG=1 "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru" &
sleep 20 && tail -5 ~/Library/Logs/ude-linespacing.txt
```
Beklenen: `install tamam`; belge penceresi açılıp editöre tıklanınca `buton eklendi (Giriş > Paragraf)`.
(Doğrudan binary — `open` LaunchServices -54 tuzağı.)

- [ ] **Step 4: Ekran doğrulaması (YALNIZ UDE penceresi — gizlilik kuralı)**

```bash
WID=$(swift -e 'import CoreGraphics; let l = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as! [[String:Any]]; let w = l.filter { ($0["kCGWindowOwnerName"] as? String)?.contains("Uyap") == true }.max { (($0["kCGWindowBounds"] as! [String:Any])["Width"] as! Int) < (($1["kCGWindowBounds"] as! [String:Any])["Width"] as! Int) }; print(w?["kCGWindowNumber"] as! Int)')
screencapture -x -o -l"$WID" /tmp/ls-ribbon.png
```
Beklenen: Giriş sekmesi Paragraf bandında "Satır Aralığı" butonu görünür (Read ile png'ye bak).

Not: Spec'teki attach-probe y-delta ölçümü bilinçli sadeleştirildi — render
kanıtı zaten udf-converter-go çıktısından geliyor ve kullanıcı GUI
doğrulamasını elle yapmayı tercih ediyor; objektif kontrol content.xml
round-trip'i (Step 5/2) sağlıyor.

- [ ] **Step 5: Kullanıcıya elle GUI testi bırak (elle test tercihi)**

Kullanıcıdan istenecekler:
1. Birkaç paragraflık metin yaz, birini seç → Satır Aralığı → **1,5**: satırlar açılmalı; popup tekrar açılınca 1,5 işaretli olmalı.
2. Belgeyi kaydet, sonra terminalde doğrula:
```bash
unzip -p ~/Desktop/test.udf content.xml | grep -o 'LineSpacing="[^"]*"' | sort | uniq -c
```
Beklenen: `LineSpacing="0.5"` görünür.
3. Dosyayı kapat/yeniden aç: aralık korunmalı. (İsterse UYAP web'de de açıp doğrular — yamasız uyumluluk.)

- [ ] **Step 6: Commit (build script değişikliği yoksa yalnız durum notu; varsa düzeltmelerle)**

```bash
git status --short   # beklenmedik artık dosya kalmadığını doğrula (workspace hijyeni)
```

---

### Task 5: Dokümantasyon (CLAUDE.md + hafıza)

**Files:**
- Modify: `CLAUDE.md` (yeni bölüm — "Formatsız Yapıştır" bölümünden sonra uygun yere)
- Create: `/Users/saidsurucu/.claude/projects/-Users-saidsurucu-Documents-GitHub-ude-mac-arm/memory/line-spacing-dropdown.md` + MEMORY.md satırı

**Interfaces:**
- Consumes: Task 1-4'te öğrenilen her şey (özellikle canlı testte çıkan sürprizler — varsa bölüme ekle).

- [ ] **Step 1: CLAUDE.md bölümü ekle**

```markdown
## Satır Aralığı dropdown'u (LINESPACING=1, 2026-07)

Giriş sekmesi "Paragraf" bandına Word-tarzı Satır Aralığı butonu
(1,0 · 1,15 · 1,5 · 2,0 · 2,5 · 3,0). UDF formatında değişiklik YOK:
content.xml `<paragraph LineSpacing="...">` float zaten var (parser
`common.d.G/d.B` getFloatValue; udf-converter-go da yazar). Kodlama:
**UDF değeri = görünen aralık − 1** (1,5 → 0.5); Paragraf diyaloğu (gui.cM)
aynı dönüşümü yapar (serbest metin alanı 1,5'i zaten kabul eder — bu özellik
yalnız hızlı erişim ekler). `macoslinespacing` paketi textkeys agent'ında:
`LineSpacingApply` (saf Swing çekirdek; `setParagraphAttributes(...,false)`
merge, caret'e dokunmaz) + `LineSpacingMenu` (yansıma; ribbon MODELİNDEN
task "Giriş" → band "Paragraf" → native `JCommandButton` POPUP_ONLY —
JRibbonComponent mixing tuzağı). Mevcut değer popup'ta "✓" ile işaretli
(lastEditor WeakReference — popup odak çalar, hedef editör odaktan bulunamaz).
MacTextKeys kancası `Class.forName` (LINESPACING=0'da build.sh paketi budar,
CNFE sessiz). Jar paketleme satırı `tkpkgs` çok-paketli (NoClassDefFound →
JVM açılışta düşer tuzağı). Teşhis: `UDE_LSLOG=1` →
`~/Library/Logs/ude-linespacing.txt`. Test: `tests/LineSpacingApplyTest.java`
(javac+java elle).
```

(Canlı testte sürpriz çıktıysa — ör. band başlığı farklı, popup davranışı — bunları da bölüme işle.)

- [ ] **Step 2: Hafıza dosyası yaz**

`memory/line-spacing-dropdown.md`:

```markdown
---
name: line-spacing-dropdown
description: LINESPACING=1 — Giriş sekmesine Word-tarzı satır aralığı dropdown'u; UDF LineSpacing float zaten vardı (değer = aralık − 1)
metadata:
  type: project
---

UDE'de "1,5 satır aralığı yok" şikâyetinin kökü UI erişimiydi: UDF formatı
`<paragraph LineSpacing="...">` float'ı hep destekliyordu (parser d.G/d.B
getFloatValue; udf-converter-go `(word240/240)-1` yazar; Paragraf diyaloğu
serbest metin alanı 1,5 kabul eder). LINESPACING=1 (varsayılan) textkeys
agent'ında `macoslinespacing` paketi: ribbon modelinden task "Giriş" → band
"Paragraf" → native JCommandButton POPUP_ONLY (JRibbonComponent mixing
tuzağı), uygulama `setParagraphAttributes(sel, attrs, false)` merge.

**Why:** format değişikliği sanılan işlerin çoğu UI eksiği; önce parser/
serializer bytecode'una bak ([[modern-2026-mechanism]] teşhis desenleri).

**How to apply:** benzer "UDE'de X özelliği yok" isteklerinde önce
content.xml şemasında öznitelik var mı diye d.G/d.B'yi grep'le; varsa iş
ribbon-modeli enjeksiyonuna iner ([[macos-footnote]] JCommandButton deseni).
```

MEMORY.md'ye satır ekle:

```markdown
- [Satır aralığı dropdown](line-spacing-dropdown.md) — LINESPACING=1; UDF LineSpacing float zaten vardı, iş UI erişimiydi; ribbon-modeli JCommandButton deseni
```

- [ ] **Step 3: Spec'in durumunu güncelle + commit**

Spec başlığındaki `**Durum:**` satırını `Uygulandı (2026-07-02)` yap.

```bash
git add CLAUDE.md docs/superpowers/specs/2026-07-02-line-spacing-dropdown-design.md
git add -f docs/superpowers/plans/2026-07-02-line-spacing-dropdown.md
git commit -m "docs(linespacing): CLAUDE.md bölümü + spec durumu — LINESPACING=1 satır aralığı dropdown'u"
```

---

# REVİZYON (2026-07-02/2): native popup'a 1.5 — ayrı buton geri alınır

Task 4 bulgusu: UDE'nin Giriş>Paragraf bandında zaten native satır-aralığı
popup'ı var (`tr.gov.uyap.system.a.b.a.a.D` buton → `…a.a.M` popup; öğeler
"1.0","1.15","2.0","2.5","3.0"; dinleyiciler N/O/P/Q/R → `D.a(float)`),
yalnız "1.5" eksik. Kullanıcı kararı: ayrı buton yerine native popup'a 1.5
eklensin. Task 1-3 geri alınır; yerine Javassist yaması gelir. Orijinal
Task 4/5 yerine aşağıdaki R1-R4 geçerlidir.

### Task R1: Ayrı-buton işini geri al (revert)

**Files:** revert: 33908bc, cab60ae, edf3c8a, 103962a (tek revert commit'i)

- [ ] `git revert --no-commit 103962a edf3c8a cab60ae 33908bc` → tek commit:
  `revert(linespacing): ayrı buton yaklaşımı geri alındı — native popup'a 1.5 eklenecek (Task 4 bulgusu)`
- [ ] Doğrula: `scripts/macos-textkeys/macoslinespacing/` yok,
  `tests/LineSpacingApplyTest.java` yok, `git grep -n LINESPACING scripts/build.sh` boş,
  `git grep -n linespacing scripts/macos-textkeys/macostextkeys/MacTextKeys.java` boş,
  `bash -n scripts/build.sh` exit 0.

### Task R2: LineSpacingPatch (Javassist) + build.sh wiring

**Files:**
- Create: `scripts/macos-linespacing/LineSpacingPatch.java`
- Modify: `scripts/build.sh` (SRC değişkeni ~satır 49 civarı; bayrak ~satır 64;
  `apply_linespacing()` fonksiyonu `apply_pdffresh`'ten hemen sonra;
  `patch_jar` zincirinde `apply_pdffresh "$JAR"` satırından sonra çağrı;
  standalone case `pdf-fresh)` satırının yanına)

**Interfaces:**
- Produces: `apply_linespacing` (patch_jar adımı), `LINESPACING=1` bayrağı,
  jar'da `tr/gov/uyap/system/a/b/a/a/LS15.class` + yamalı `M.class`.

- [ ] **Step 1: LineSpacingPatch.java yaz** (PdfFreshPatch deseni):

```java
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Native satır aralığı menüsüne "1.5" ekler (satıcı unutmuş).
 *
 * Giriş>Paragraf bandındaki satır-aralığı popup'ı tr.gov.uyap.system.a.b.a.a.M
 * (JCommandPopupMenu): öğeler "1.0","1.15","2.0","2.5","3.0"; her öğenin
 * dinleyicisi (N/O/P/Q/R) tek satır M.a(this.a).a(görünenDeğer) — D.a(float)
 * satıcının kendi uygulama yolu (display−1 dönüşümü + undo/seçim mantığı orada).
 *
 * Yama: (1) "1.15" dinleyicisi O, LS15 adıyla aynı pakete kopyalanır
 * (getAndRename), gövdesi 1.5f'e çevrilir; (2) M kurucusunda 3. addMenuButton
 * ("2.0" öğesini ekleyen çağrı) öncesine JCommandMenuButton("1.5", null) +
 * LS15 dinleyicisi enjekte edilir → menü sırası 1.0, 1.15, 1.5, 2.0, 2.5, 3.0.
 *
 * İdempotans: LS15 jar'da varsa atlanır. Nokta biçimi "1.5" native öğelerle
 * tutarlı (menü satıcı biçiminde). UDF formatı değişmez.
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class LineSpacingPatch {
    static final String M   = "tr.gov.uyap.system.a.b.a.a.M";
    static final String O   = "tr.gov.uyap.system.a.b.a.a.O";
    static final String LS  = "tr.gov.uyap.system.a.b.a.a.LS15";
    static final String BTN = "org.pushingpixels.flamingo.api.common.JCommandMenuButton";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: LineSpacingPatch <editor-app.jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        if (pool.getOrNull(LS) != null) {
            System.out.println("[LineSpacingPatch] zaten yamalı (LS15 mevcut); atlandı.");
            return;
        }

        CtClass ls = pool.getAndRename(O, LS);
        CtMethod ap = ls.getDeclaredMethod("actionPerformed");
        ap.setBody("{ " + M + ".a(this.a).a(1.5f); }");

        CtClass m = pool.get(M);
        CtConstructor ctor = m.getDeclaredConstructors()[0];
        final int[] count = {0};
        final int[] hit = {0};
        ctor.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall mc) throws javassist.CannotCompileException {
                if (!"addMenuButton".equals(mc.getMethodName())) return;
                count[0]++;
                if (count[0] != 3) return;
                mc.replace(
                    "{ " + BTN + " b15 = new " + BTN + "(\"1.5\", null);"
                  + "  b15.addActionListener(new " + LS + "($0));"
                  + "  $0.addMenuButton(b15);"
                  + "  $proceed($$); }");
                hit[0]++;
            }
        });
        if (hit[0] != 1) {
            throw new IllegalStateException(
                "M kurucusunda 3. addMenuButton bulunamadı (toplam=" + count[0]
                + ") — UDE sürümü değişmiş olabilir.");
        }

        write(outDir, "tr/gov/uyap/system/a/b/a/a/LS15.class", ls.toBytecode());
        write(outDir, "tr/gov/uyap/system/a/b/a/a/M.class", m.toBytecode());
        System.out.println("[LineSpacingPatch] satır aralığı menüsüne 1.5 eklendi (M + LS15 yazıldı).");
    }

    static void write(File outDir, String rel, byte[] bytes) throws Exception {
        File f = new File(outDir, rel);
        f.getParentFile().mkdirs();
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(bytes);
        }
    }
}
```

- [ ] **Step 2: build.sh wiring** (dört ek):

(a) SRC değişkeni — `PDFFRESH_SRC=` satırının altına:
```bash
LINESPACING_SRC="$SCRIPT_DIR/macos-linespacing" # native satır aralığı menüsüne 1.5 ekleyen yama
```

(b) Bayrak — `PDFFRESH="${PDFFRESH:-1}"` satırının altına:
```bash
LINESPACING="${LINESPACING:-1}" # 1=açık (varsayılan; Giriş>Paragraf satır aralığı menüsüne 1.5 eklenir — satıcı unutmuş) | 0=kapalı
```

(c) `apply_pdffresh()` fonksiyonunun kapanışından sonra (apply_pdffresh deseni):
```bash
apply_linespacing() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$LINESPACING" = "1" ] || return 0
	c_info "[linespacing] satır aralığı menüsüne 1.5 ekleniyor (satıcı unutmuş)…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[linespacing] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[linespacing] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (ortak)
	rm -rf "$BUILD/_lspatch"; mkdir -p "$BUILD/_lspatch/out"
	"$jc" --release 11 -cp "$jvs" -d "$BUILD/_lspatch" "$LINESPACING_SRC/LineSpacingPatch.java" \
		|| { c_warn "[linespacing] LineSpacingPatch derlenemedi; yama atlandı."; return 0; }
	"$jr" -cp "$BUILD/_lspatch:$jvs" LineSpacingPatch "$JAR" "$BUILD/_lspatch/out" \
		|| die "[linespacing] 1.5 yaması uygulanamadı (UDE sürümü değişmiş olabilir)."
	if [ -d "$BUILD/_lspatch/out/tr" ]; then
		( cd "$BUILD/_lspatch/out" && zip -q -r "$JAR" tr )
		c_ok "[linespacing] satır aralığı menüsüne 1.5 eklendi."
	else
		c_ok "[linespacing] zaten yamalı, atlandı."
	fi
}
```

(d) `patch_jar` zincirinde `apply_pdffresh "$JAR"` satırından sonra:
```bash
	apply_linespacing "$JAR"
```
ve standalone case satırı (`pdf-fresh)` satırının yanına):
```bash
	line-spacing) LINESPACING=1 apply_linespacing "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
```

- [ ] **Step 3: Yerinde doğrulama (tam build'siz).** Mevcut kaynak jar'ın
  KOPYASI üzerinde patch'i çalıştır ve javap ile doğrula:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
cp "$(ls src-app/app/Contents/Java/editor-app.jar 2>/dev/null || echo build/_input/editor-app.jar)" /tmp/ls-test.jar
JVS=$(bash -c 'source scripts/build.sh 2>/dev/null; icon_deps' 2>/dev/null) || JVS=$(ls "$HOME"/.m2/repository/org/javassist/javassist/*/javassist-*.jar 2>/dev/null | head -1)
mkdir -p /tmp/lspatch/out && javac --release 11 -cp "$JVS" -d /tmp/lspatch scripts/macos-linespacing/LineSpacingPatch.java
java -cp "/tmp/lspatch:$JVS" LineSpacingPatch /tmp/ls-test.jar /tmp/lspatch/out
(cd /tmp/lspatch/out && zip -q -r /tmp/ls-test.jar tr)
javap -classpath /tmp/ls-test.jar -c -p tr.gov.uyap.system.a.b.a.a.M | grep -c 'String 1.5'
javap -classpath /tmp/ls-test.jar -c -p tr.gov.uyap.system.a.b.a.a.LS15 | grep 'float 1.5\|D.a'
```
Beklenen: M'de `String 1.5` ≥1; LS15'te `ldc … float 1.5f` ve `D.a:(F)V` çağrısı.
(İkinci kez çalıştırınca "zaten yamalı" mesajı — idempotans.) Javassist yolunu
build.sh'teki `icon_deps` neresi sağlıyorsa oradan al (source hilesi çalışmazsa
`grep -n 'icon_deps()' -A 10 scripts/build.sh` ile bak).
`bash -n scripts/build.sh` exit 0.

- [ ] **Step 4: Commit**
```bash
git add scripts/macos-linespacing/LineSpacingPatch.java scripts/build.sh
git commit -m "feat(linespacing): native satır aralığı menüsüne 1.5 — M kurucusuna Javassist enjeksiyonu (LS15=O kopyası, D.a(1.5f))"
```

### Task R2 DÜZELTMESİ (ilk deneme BLOCKED): FQCN'siz Javassist tasarımı

İlk R2 denemesi gerçek bir Javassist sınırlamasına çarptı: pakette **`a` adlı
SINIF da var** (`tr/gov/uyap/system/a/b/a/a.class`) → kaynak-düzeyi
`tr.gov.uyap.system.a.b.a.a.M` yazımı `a$M` iç-sınıfına çözülüyor
(`CannotCompileException: no such class …a$M`). Kural: **bu paketteki hiçbir
sınıf Javassist KAYNAK dizgisinde adıyla anılamaz** — yalnız bytecode
referansları (getAndRename, $0) ve string-literal yansıma (`Class.forName`)
kullanılabilir. `D.a(float)`'ın PUBLIC olduğu javap ile doğrulandı.
Step 1'deki kod bloğunun YERİNE aşağıdaki tasarım geçer (build.sh wiring'i
Step 2-4 aynen geçerli):

```java
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Native satır aralığı menüsüne "1.5" ekler (satıcı unutmuş).
 *
 * Giriş>Paragraf bandındaki satır-aralığı popup'ı tr.gov.uyap.system.a.b.a.a.M
 * (JCommandPopupMenu): öğeler "1.0","1.15","2.0","2.5","3.0"; her öğenin
 * dinleyicisi (N/O/P/Q/R) tek satır M.a(this.a).a(görünenDeğer) — D.a(float)
 * satıcının kendi uygulama yolu (display−1 dönüşümü + undo/seçim mantığı orada).
 *
 * KRİTİK Javassist tuzağı: pakette `a` adlı SINIF da var
 * (tr/gov/uyap/system/a/b/a/a.class) → bu paketin sınıfları Javassist KAYNAK
 * dizgisinde FQCN ile ANILAMAZ (çözümleyici a$M dener, CannotCompile).
 * Bu yüzden: (1) LS15 = O'nun getAndRename BYTECODE kopyası (kaynak yok);
 * (2) 1.15f→1.5f değişimi ExprEditor ile `$0.a(1.5f)` (FQCN'siz — $0'ın tipi
 * çağrı yerinden bilinir); (3) M kurucusundaki enjeksiyonda dinleyici,
 * temiz-paketli tr.lsinject.LsInject.make(Object) fabrikasından gelir
 * (Class.forName STRING literal + yansıma ctor — kaynak çözümleyici bypass).
 *
 * Yama: M kurucusunda 3. addMenuButton ("2.0" öğesi) öncesine
 * JCommandMenuButton("1.5", null) + LS15 → menü 1.0, 1.15, 1.5, 2.0, 2.5, 3.0.
 * İdempotans: LS15 jar'da varsa atlanır. UDF formatı değişmez.
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class LineSpacingPatch {
    static final String M   = "tr.gov.uyap.system.a.b.a.a.M";
    static final String O   = "tr.gov.uyap.system.a.b.a.a.O";
    static final String LS  = "tr.gov.uyap.system.a.b.a.a.LS15";
    static final String INJ = "tr.lsinject.LsInject";
    static final String BTN = "org.pushingpixels.flamingo.api.common.JCommandMenuButton";
    static final String RI  = "org.pushingpixels.flamingo.api.common.icon.ResizableIcon";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: LineSpacingPatch <editor-app.jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        if (pool.getOrNull(LS) != null) {
            System.out.println("[LineSpacingPatch] zaten yamalı (LS15 mevcut); atlandı.");
            return;
        }

        // 1) "1.15" dinleyicisi O → LS15 (aynı pakette bytecode kopyası;
        //    paket-içi M.a erişimi bozulmaz). Sabit 1.15f → 1.5f: D.a(F)
        //    çağrısı $0 üzerinden yeniden yazılır (kaynakta sınıf adı YOK).
        CtClass ls = pool.getAndRename(O, LS);
        CtMethod ap = ls.getDeclaredMethod("actionPerformed");
        final int[] swapped = {0};
        ap.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall mc) throws javassist.CannotCompileException {
                if ("a".equals(mc.getMethodName()) && "(F)V".equals(mc.getSignature())) {
                    mc.replace("{ $0.a(1.5f); }");
                    swapped[0]++;
                }
            }
        });
        if (swapped[0] != 1) {
            throw new IllegalStateException(
                "O.actionPerformed içinde D.a(F) çağrısı bulunamadı (n=" + swapped[0] + ").");
        }

        // 2) Fabrika: tr.lsinject.LsInject.make(Object) → LS15 örneği.
        //    Obfuscate paket kaynakta anılamadığından yansıma + string literal.
        CtClass inj = pool.makeClass(INJ);
        inj.addMethod(CtNewMethod.make(
              "public static java.awt.event.ActionListener make(Object m) {"
            + "  try {"
            + "    Class c = Class.forName(\"" + LS + "\");"
            + "    java.lang.reflect.Constructor k = c.getDeclaredConstructors()[0];"
            + "    k.setAccessible(true);"
            + "    return (java.awt.event.ActionListener) k.newInstance(new Object[]{ m });"
            + "  } catch (Throwable t) {"
            + "    throw new RuntimeException(t);"
            + "  }"
            + "}", inj));

        // 3) M kurucusu: 3. addMenuButton ("2.0") öncesine "1.5" öğesi.
        CtClass m = pool.get(M);
        CtConstructor ctor = m.getDeclaredConstructors()[0];
        final int[] count = {0};
        final int[] hit = {0};
        ctor.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall mc) throws javassist.CannotCompileException {
                if (!"addMenuButton".equals(mc.getMethodName())) return;
                count[0]++;
                if (count[0] != 3) return;
                mc.replace(
                    "{ " + BTN + " b15 = new " + BTN + "(\"1.5\", (" + RI + ") null);"
                  + "  b15.addActionListener(" + INJ + ".make($0));"
                  + "  $0.addMenuButton(b15);"
                  + "  $proceed($$); }");
                hit[0]++;
            }
        });
        if (hit[0] != 1) {
            throw new IllegalStateException(
                "M kurucusunda 3. addMenuButton bulunamadı (toplam=" + count[0]
                + ") — UDE sürümü değişmiş olabilir.");
        }

        write(outDir, "tr/gov/uyap/system/a/b/a/a/LS15.class", ls.toBytecode());
        write(outDir, "tr/lsinject/LsInject.class", inj.toBytecode());
        write(outDir, "tr/gov/uyap/system/a/b/a/a/M.class", m.toBytecode());
        System.out.println("[LineSpacingPatch] satır aralığı menüsüne 1.5 eklendi (M + LS15 + LsInject yazıldı).");
    }

    static void write(File outDir, String rel, byte[] bytes) throws Exception {
        File f = new File(outDir, rel);
        f.getParentFile().mkdirs();
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(bytes);
        }
    }
}
```

Doğrulama (Step 3) aynı kalır; ek beklenti: `javap -classpath /tmp/ls-test.jar
-p tr.lsinject.LsInject` make(Object) gösterir; LS15.actionPerformed'da
`ldc … 1.5f` + `D.a:(F)V`. (Tüm çıktı sınıfları `tr/` altında olduğundan
build.sh'ın `zip -r "$JAR" tr` satırı hepsini kapsar.)

### Task R3: Tam yeniden build + canlı doğrulama

- [ ] `bash scripts/build.sh download && bash scripts/build.sh patch && bash scripts/build.sh lookagent && bash scripts/build.sh textkeys && bash scripts/build.sh package && bash scripts/build.sh sign`
  — patch çıktısında `[linespacing] satır aralığı menüsüne 1.5 eklendi.` satırı görülmeli.
- [ ] Paketlenmiş jar doğrulaması:
```bash
javap -classpath "build/Uyap Doküman Editörü.app/Contents/app/editor-app.jar" -c -p tr.gov.uyap.system.a.b.a.a.M | grep -c 'String 1.5'
```
Beklenen: ≥1. (macos-textkeys.jar'da macoslinespacing OLMAMALI — revert doğrulaması: `unzip -l ... | grep -c macoslinespacing` → 0.)
- [ ] Uygulamayı başlat (doğrudan binary, `pkill` önce), pencere-tekil ekran görüntüsü:
  Giriş sekmesi → satır aralığı butonuna popup açtırmak sentetik tıklamayla YAPILMAZ;
  ekran görüntüsünde yalnız bandın görünümü yeterli, popup içeriği kullanıcı testinde.
- [ ] Kullanıcı testi (elle): satır aralığı popup'ında **1.5** görünmeli; birkaç
  paragraf seçip 1.5 uygula → satırlar açılmalı; kaydet →
  `unzip -p <dosya>.udf content.xml | grep -o 'LineSpacing="[^"]*"' | sort | uniq -c`
  → `LineSpacing="0.5"`; kapat/aç → korunmalı.

### Task R4: Dokümantasyon (orijinal Task 5'in yerine)

- [ ] CLAUDE.md bölümü (revize içerik):
```markdown
## Satır aralığı 1.5 (LINESPACING=1, 2026-07)

UDE'nin Giriş>Paragraf bandındaki NATIVE satır-aralığı popup'ı
(`tr.gov.uyap.system.a.b.a.a.D` buton → `…a.a.M extends JCommandPopupMenu`)
"1.0,1.15,2.0,2.5,3.0" içerir — **1.5 satıcı tarafından unutulmuş**.
`scripts/macos-linespacing/LineSpacingPatch.java` (Javassist, pdffresh deseni)
M kurucusunda 3. addMenuButton ("2.0") öncesine "1.5" öğesi enjekte eder;
dinleyici LS15 = "1.15" dinleyicisi O'nun getAndRename kopyası, gövde
`M.a(this.a).a(1.5f)` → satıcının kendi uygulama yolu `D.a(float)` (display−1
dönüşümü + undo/seçim orada; UDF `LineSpacing` float zaten destekler, format
değişikliği YOK — parser d.G/d.B getFloatValue; Paragraf diyaloğu gui.cM
serbest alanı 1,5'i zaten kabul eder). İdempotans: LS15 jar'da varsa atlanır.
TARİHSEL: önce ayrı JCommandButton denendi (Task 1-3, revert edildi) —
`AbstractCommandButton.setToolTipText` KOŞULSUZ UnsupportedOperationException
fırlatır ("Use rich tooltip APIs") → ribbon'a Flamingo butonu eklerken
setToolTipText ÇAĞIRMA (footnote deseni de çağırmaz). Native kontrolü ilk
taramanın kaçırma nedeni: sınıflar `tr.com.havelsan` değil `tr.gov.uyap`
ağacında. Teşhis: canlı popup içeriği ile bytecode string'lerini karşılaştır.
```
- [ ] Hafıza dosyası `line-spacing-dropdown.md` (revize):
  native kontrol keşfi (tr.gov.uyap ağacı!), setToolTipText tuzağı,
  "format değişikliği sanılan işler çoğu kez UI eksiği" dersi,
  [[macos-footnote]] ve [[modern-2026-mechanism]] bağlantıları; MEMORY.md satırı.
- [ ] Spec zaten revize edildi (kontrol et); plan + spec + CLAUDE.md + memory commit.
