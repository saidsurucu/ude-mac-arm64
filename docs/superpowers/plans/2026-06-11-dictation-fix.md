# macOS Dikte Düzeltmesi — Uygulama Planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** macOS dikte UDE'de güvenilir çalışsın — dikte kapanınca metin kaybolmasın, uygulama donmasın.

**Architecture:** İki faz. Faz 1: `macos-textkeys` javaagent'ına `DictationProbe` teşhis sınıfı (InputMethodEvent + EDT istisna logu) eklenir, canlı dikte testiyle kök neden kanıtlanır. Faz 2 (H2 doğrulanırsa): editör bileşeni `tr…common.text.hj`'nin `processInputMethodEvent`'i Javassist ile sarılır; jar'a enjekte edilen `macosdict.DictationGuard` committed metni kalıcı, composed metni geçici düz metin olarak kendisi yazar — Swing'in composed-text makinesi devre dışı kalır.

**Tech Stack:** Java 11 (javaagent + jar helper), Javassist (build-zamanı yama), bash (`scripts/build.sh`).

**Spec:** `docs/superpowers/specs/2026-06-11-dictation-fix-design.md`

**⚠️ FAZ KAPISI:** Task 3'ten sonra DURULUR; canlı dikte testinin log/jstack bulgusu H2'yi ("olaylar Swing'e ulaşıyor, commit aşamasında istisna/kayıp") doğrulamadan Task 4+ ÇALIŞTIRILMAZ. H1 çıkarsa (native kilit, olaylar hiç gelmiyor) plan durur, JRE yükseltme kararı kullanıcıya döner.

---

## Faz 1 — Teşhis

### Task 1: `DictationProbe` teşhis sınıfı

**Files:**
- Create: `scripts/macos-textkeys/macostextkeys/DictationProbe.java`
- Modify: `scripts/macos-textkeys/macostextkeys/MacTextKeys.java:74` (install() içine bir satır)

Agent ailesindeki `MacOptionChars` log deseni izlenir (env bayrağı + `~/Library/Logs` dosyası; System.err uygulama tarafından yutuluyor).

- [ ] **Step 1: DictationProbe.java'yı yaz**

```java
package macostextkeys;

/*
 * macOS dikte (IME) teşhis sınıfı — UDE_DICTLOG=1 ile etkinleşir, aksi halde no-op.
 *
 * Amaç: dikte sırasında akan InputMethodEvent'leri ve EDT'de yakalanmamış
 * istisnaları ~/Library/Logs/ude-dictation.txt'ye dökmek. Dikte kapanınca
 * metnin kaybolması (H2: composed-text commit'i özel doküman katmanında
 * patlıyor) ya da olayların hiç gelmemesi (H1: native kilit) buradan kanıtlanır.
 *
 * Not: AWTEventListener salt dinleyicidir (olay tüketemez) — teşhis için yeterli,
 * düzeltme için değil. Faz 2 düzeltmesi ayrı (DictationGuard, Javassist).
 */

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputMethodEvent;
import java.awt.im.InputMethodRequests;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.AttributedCharacterIterator;
import java.text.CharacterIterator;

import javax.swing.text.JTextComponent;

public final class DictationProbe {

    private static final boolean LOG = "1".equals(System.getenv("UDE_DICTLOG"));
    private static volatile boolean dumpedHierarchy = false;

    private DictationProbe() {}

    public static void install() {
        if (!LOG) return;
        try {
            // EDT dahil yakalanmamış istisnalar (Java 7+ EDT istisnaları default
            // handler'a düşer): composed-text commit patlaması buradan görünür.
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable ex) {
                    logLine("UNCAUGHT thread=" + t.getName() + "\n" + stackTrace(ex));
                    if (prev != null) prev.uncaughtException(t, ex);
                }
            });
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e instanceof InputMethodEvent) logIme((InputMethodEvent) e);
                }
            }, AWTEvent.INPUT_METHOD_EVENT_MASK);
            logLine("DictationProbe kuruldu (java.version="
                    + System.getProperty("java.version") + ")");
        } catch (Throwable t) {
            // Agent asla uygulamayı düşürmemeli.
        }
    }

    private static void logIme(InputMethodEvent e) {
        try {
            Object src = e.getSource();
            StringBuilder sb = new StringBuilder();
            sb.append(e.getID() == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED
                    ? "TEXT_CHANGED" : "CARET_CHANGED");
            sb.append(" committed=").append(e.getCommittedCharacterCount());
            AttributedCharacterIterator it = e.getText();
            if (it == null) {
                sb.append(" text=null");
            } else {
                StringBuilder all = new StringBuilder();
                for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
                    all.append(c);
                }
                sb.append(" len=").append(all.length())
                  .append(" text=\"").append(all).append('"');
            }
            sb.append(" src=").append(src.getClass().getName());
            if (src instanceof JTextComponent) {
                sb.append(" caret=").append(((JTextComponent) src).getCaretPosition());
            }
            logLine(sb.toString());
            if (!dumpedHierarchy) {
                dumpedHierarchy = true;
                StringBuilder h = new StringBuilder("hiyerarşi:");
                for (Class<?> k = src.getClass(); k != null; k = k.getSuperclass()) {
                    h.append(' ').append(k.getName());
                }
                logLine(h.toString());
                if (src instanceof Component) {
                    InputMethodRequests req = ((Component) src).getInputMethodRequests();
                    logLine("inputMethodRequests="
                            + (req == null ? "null" : req.getClass().getName()));
                }
            }
        } catch (Throwable t) {
            logLine("logIme hata: " + t);
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static synchronized void logLine(String s) {
        try {
            File f = new File(System.getProperty("user.home"),
                    "Library/Logs/ude-dictation.txt");
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(System.currentTimeMillis() + " " + s + System.lineSeparator());
            }
        } catch (IOException ignore) {
            // log başarısızsa sessizce geç
        }
    }
}
```

- [ ] **Step 2: MacTextKeys.install()'a kancayı ekle**

`scripts/macos-textkeys/macostextkeys/MacTextKeys.java` içinde `MacTooltips.install();` satırından (74. satır) hemen sonra:

```java
        // Dikte/IME teşhisi (UDE_DICTLOG=1 ile etkin; aksi halde no-op).
        DictationProbe.install();
```

- [ ] **Step 3: Derlemeyi doğrula**

Run: `cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm && bash scripts/build.sh textkeys`
Expected: `macos-textkeys derlendi (N sınıf)` — N öncekinden büyük (DictationProbe + iç sınıflar dahil), hata yok.

- [ ] **Step 4: Commit**

```bash
git add scripts/macos-textkeys/macostextkeys/DictationProbe.java scripts/macos-textkeys/macostextkeys/MacTextKeys.java
git commit -m "feat(dictation): DictationProbe — IME olay + EDT istisna teşhis logu"
```

### Task 2: Tam build + canlı dikte testi (KULLANICI)

**Files:** yok (build + manuel test)

- [ ] **Step 1: Tam build**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh download && bash scripts/build.sh patch && bash scripts/build.sh lookagent && bash scripts/build.sh package
```
Expected: build hatasız biter; `build/Uyap Doküman Editörü.app` oluşur.
(Not: `download` her iterasyonda şart — taze kaynak, çift yama yok.)

- [ ] **Step 2: Kullanıcıya test protokolünü ver ve DUR**

Kullanıcı terminalden çalıştırır (stdout görünür kalsın — donmada SIGQUIT dump'ı buraya düşer):

```bash
pkill -f UyapDokumanEditoru; sleep 1
rm -f ~/Library/Logs/ude-dictation.txt
UDE_DICTLOG=1 "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru"
```

Protokol:
1. Yeni belge aç, imleci metne koy.
2. Dikteyi başlat (klavye kısayolu, örn. çift Fn ya da F5), 2-3 cümle söyle.
3. Dikteyi kapat. Gözlemle: (a) metin kayboldu mu? (b) donma var mı?
4. **Donduysa**, ikinci terminalden: `pkill -QUIT -f UyapDokumanEditoru` — thread dump
   ilk terminale (stdout) düşer; tamamını kopyala. (Host `jstack` JVM-11'e
   sürüm uyumsuzluğundan bağlanamayabilir; SIGQUIT güvenilir yol.)
5. `~/Library/Logs/ude-dictation.txt` + varsa thread dump geri gönderilir.

- [ ] **Step 3: Bulguyu yorumla — FAZ KAPISI**

- Logda dikte boyunca `TEXT_CHANGED` olayları **var** ve kapanış civarında
  `UNCAUGHT`/istisna var ya da metin kaybı commit olayıyla eşzamanlı → **H2
  doğrulandı**, Task 4+'e geç.
- Olaylar Swing'e **hiç gelmiyor** ya da thread dump EDT'yi
  `sun.lwawt.macosx.CInputMethod` native çağrısında bekler gösteriyor → **H1**:
  plan burada durur; bulgu raporlanır, JRE yükseltme (Zulu 17/21) kararı
  kullanıcıya sunulur (ayrı iş).
- Başka mekanizma → tasarım revizyonu için kullanıcıya dön.
- Hedef bileşenin gerçek sınıfı `hiyerarşi:` satırından okunur; `hj` değilse
  Task 6'daki `HJ` sabiti o sınıfla değiştirilir.

---

## Faz 2 — Düzeltme (yalnız H2 doğrulanırsa)

### Task 3: `DictationGuardTest` — önce başarısız test

**Files:**
- Create: `tests/DictationGuardTest.java`

Test, `macosdict.DictationGuard`'ı (henüz yok) düz bir `JTextArea` ile sentetik
`InputMethodEvent`'ler üzerinden sürer — guard yalnız `javax.swing.text` API'si
kullandığı için UDE jar'ı gerekmez. `tests/IconDarkenPixelTest.java` gibi elle
javac+java ile koşulur.

- [ ] **Step 1: Testi yaz**

```java
import java.awt.event.InputMethodEvent;
import java.awt.font.TextHitInfo;
import java.text.AttributedString;

import javax.swing.JTextArea;

import macosdict.DictationGuard;

/**
 * DictationGuard mantık testi (ekran-dışı, UDE jar'sız).
 * Koşum:
 *   javac --release 11 -encoding UTF-8 -d build/_dicttest \
 *       scripts/macos-dictation/macosdict/DictationGuard.java tests/DictationGuardTest.java
 *   java -Djava.awt.headless=true -cp build/_dicttest DictationGuardTest
 */
public class DictationGuardTest {

    static int failures = 0;

    public static void main(String[] args) {
        testIncrementalDictation();
        testDeadKeyCircumflex();
        testComposedCleared();
        testCaretEventIdlePassthrough();
        testNotEditablePassthrough();
        if (failures > 0) {
            System.err.println(failures + " test BAŞARISIZ");
            System.exit(1);
        }
        System.out.println("Tüm testler geçti.");
    }

    /** Dikte akışı: composed büyür, sonunda commit olur; metin kalıcı kalmalı. */
    static void testIncrementalDictation() {
        JTextArea ta = new JTextArea();
        handle(ta, "mer", 0);                 // composed "mer"
        check("dikte-1 composed", "mer", ta);
        handle(ta, "merhaba", 0);             // composed büyüdü
        check("dikte-2 composed büyüme", "merhaba", ta);
        handle(ta, "merhaba", 7);             // tamamı commit (dikte kapanışı)
        check("dikte-3 commit", "merhaba", ta);
        handle(ta, " dünya", 6);              // ikinci parça doğrudan commit
        check("dikte-4 ek commit", "merhaba dünya", ta);
    }

    /** Ölü tuş: ^ composed görünür, â commit'le yerine geçer. */
    static void testDeadKeyCircumflex() {
        JTextArea ta = new JTextArea();
        ta.setText("h");
        ta.setCaretPosition(1);
        handle(ta, "^", 0);
        check("ölütuş-1 composed", "h^", ta);
        handle(ta, "â", 1);
        check("ölütuş-2 commit", "hâ", ta);
    }

    /** Boş TEXT_CHANGED (iptal): composed temizlenmeli, kalıcı metin kalmalı. */
    static void testComposedCleared() {
        JTextArea ta = new JTextArea();
        ta.setText("sabit");
        ta.setCaretPosition(5);
        handle(ta, "xyz", 0);
        check("iptal-1 composed", "sabitxyz", ta);
        handle(ta, "", 0);
        check("iptal-2 temizlik", "sabit", ta);
    }

    /** Aktif composed yokken CARET olayı guard'a takılmamalı (false dönmeli). */
    static void testCaretEventIdlePassthrough() {
        JTextArea ta = new JTextArea();
        InputMethodEvent e = new InputMethodEvent(ta,
                InputMethodEvent.CARET_POSITION_CHANGED, null, 0,
                TextHitInfo.leading(0), null);
        boolean handled = DictationGuard.handle(ta, e);
        if (handled) { failures++; System.err.println("caret-idle: true döndü, false beklenirdi"); }
        else System.out.println("caret-idle OK");
    }

    /** Düzenlenemez bileşende guard devreye girmemeli. */
    static void testNotEditablePassthrough() {
        JTextArea ta = new JTextArea();
        ta.setEditable(false);
        boolean handled = handle(ta, "abc", 3);
        if (handled) { failures++; System.err.println("salt-okunur: true döndü, false beklenirdi"); }
        else System.out.println("salt-okunur OK");
        if (!ta.getText().isEmpty()) { failures++; System.err.println("salt-okunur: metin değişti"); }
    }

    // --- yardımcılar ---

    static boolean handle(JTextArea ta, String text, int committed) {
        InputMethodEvent e = new InputMethodEvent(ta,
                InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                text.isEmpty() ? null : new AttributedString(text).getIterator(),
                committed, TextHitInfo.leading(0), null);
        return DictationGuard.handle(ta, e);
    }

    static void check(String name, String expected, JTextArea ta) {
        if (!expected.equals(ta.getText())) {
            failures++;
            System.err.println(name + ": beklenen \"" + expected
                    + "\" bulunan \"" + ta.getText() + "\"");
        } else {
            System.out.println(name + " OK");
        }
    }
}
```

- [ ] **Step 2: Testin derlenemediğini (sınıf yok) doğrula**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
mkdir -p build/_dicttest
javac --release 11 -encoding UTF-8 -d build/_dicttest tests/DictationGuardTest.java
```
Expected: FAIL — `package macosdict does not exist`.

- [ ] **Step 3: Commit (yalnız test)**

```bash
git add tests/DictationGuardTest.java
git commit -m "test(dictation): DictationGuard mantık testi (önce başarısız)"
```

### Task 4: `macosdict.DictationGuard` helper

**Files:**
- Create: `scripts/macos-dictation/macosdict/DictationGuard.java`

- [ ] **Step 1: DictationGuard.java'yı yaz**

```java
package macosdict;

/*
 * macOS dikte (IME) düzeltmesi — DICTFIX=1.
 *
 * Sorun: macOS dikte metni InputMethodEvent'lerle (composed/committed) verir.
 * UDE'nin özel doküman/view katmanı (DocumentEx + wp.*) Swing'in composed-text
 * makinesini taşıyamıyor: dikte kapanırken commit aşaması patlıyor → metin
 * kayboluyor, EDT donuyor (Faz 1 teşhisiyle kanıtlandı).
 *
 * Çözüm: editör bileşeninin processInputMethodEvent'i Javassist ile sarılır
 * (DictationPatch). Bu sınıf olayı KENDİSİ işler: committed metni kalıcı düz
 * metin olarak yazar (replaceSelection), composed metni geçici düz metin olarak
 * gösterir (bir sonraki olayda doğrulayarak siler). Swing'in attribute'lı
 * composed makinesi hiç çalışmaz. Ölü tuşlar (^→â) ve emoji seçici aynı
 * committed yoldan bozulmadan geçer.
 *
 * Hata sözleşmesi: handle() hiçbir koşulda istisna sızdırmaz; sorun olursa
 * false döner → çağıran super'e düşer (statüko). En kötü durum = bugünkü davranış.
 */

import java.awt.event.InputMethodEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.AttributedCharacterIterator;
import java.text.CharacterIterator;
import java.util.Map;
import java.util.WeakHashMap;

import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;

public final class DictationGuard {

    /** Bileşen başına aktif geçici (composed) metin kaydı. */
    private static final class State {
        Position start;   // belge içinde kayan başlangıç konumu
        int length;       // geçici metnin uzunluğu
        String text;      // silmeden önce doğrulama için içerik
    }

    private static final Map<JTextComponent, State> STATES =
            new WeakHashMap<JTextComponent, State>();
    private static final boolean LOG = "1".equals(System.getenv("UDE_DICTLOG"));

    private DictationGuard() {}

    /**
     * Yamalı processInputMethodEvent'ten çağrılır. true → olay işlendi
     * (çağıran return etmeli); false → varsayılan işleme (super) devam etsin.
     */
    public static boolean handle(Object comp, InputMethodEvent e) {
        try {
            if (!(comp instanceof JTextComponent)) return false;
            JTextComponent tc = (JTextComponent) comp;
            if (!tc.isEditable() || !tc.isEnabled()) return false;

            if (e.getID() != InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) {
                // CARET_POSITION_CHANGED: composed makinemiz yok. Aktif geçici
                // metnimiz varsa olayı yut (varsayılan makineye sızmasın);
                // yoksa dokunma.
                boolean active;
                synchronized (STATES) { active = STATES.get(tc) != null; }
                if (active) { e.consume(); return true; }
                return false;
            }

            Document doc = tc.getDocument();
            if (doc == null) return false;

            // 1) Önceki geçici metni DOĞRULAYARAK sil (belge başka yoldan
            //    değiştiyse dokunma — yanlış silme metin kaybından beterdir).
            State st;
            synchronized (STATES) { st = STATES.remove(tc); }
            if (st != null && st.length > 0) {
                int off = st.start.getOffset();
                if (off >= 0 && off + st.length <= doc.getLength()
                        && doc.getText(off, st.length).equals(st.text)) {
                    doc.remove(off, st.length);
                    tc.setCaretPosition(Math.min(off, doc.getLength()));
                }
            }

            // 2) Olay metnini committed/composed olarak ayır.
            int committedCount = e.getCommittedCharacterCount();
            StringBuilder committed = new StringBuilder();
            StringBuilder composed = new StringBuilder();
            AttributedCharacterIterator it = e.getText();
            if (it != null) {
                int i = 0;
                for (char c = it.first(); c != CharacterIterator.DONE; c = it.next(), i++) {
                    if (i < committedCount) committed.append(c);
                    else composed.append(c);
                }
            }

            // 3) Committed metni KALICI yaz (editör kit'inin giriş
            //    attribute'larıyla; seçim varsa onu da değiştirir).
            if (committed.length() > 0) {
                tc.replaceSelection(committed.toString());
            }

            // 4) Kalan composed metni geçici düz metin olarak ekle, yerini
            //    Position ile kaydet (araya başka ekleme olursa kayar).
            if (composed.length() > 0) {
                int pos = tc.getCaretPosition();
                doc.insertString(pos, composed.toString(), null);
                State ns = new State();
                ns.start = doc.createPosition(pos);
                ns.length = composed.length();
                ns.text = composed.toString();
                synchronized (STATES) { STATES.put(tc, ns); }
                tc.setCaretPosition(Math.min(pos + composed.length(), doc.getLength()));
            }

            e.consume();
            return true;
        } catch (Throwable t) {
            log(t);
            return false; // statükoya düş
        }
    }

    private static void log(Throwable t) {
        if (!LOG) return;
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            File f = new File(System.getProperty("user.home"),
                    "Library/Logs/ude-dictation.txt");
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(System.currentTimeMillis() + " GUARD-HATA\n" + sw + System.lineSeparator());
            }
        } catch (Throwable ignore) {
            // log başarısızsa sessizce geç
        }
    }
}
```

- [ ] **Step 2: Testi derle ve koş — geçmeli**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
rm -rf build/_dicttest && mkdir -p build/_dicttest
javac --release 11 -encoding UTF-8 -d build/_dicttest \
    scripts/macos-dictation/macosdict/DictationGuard.java tests/DictationGuardTest.java
java -Djava.awt.headless=true -cp build/_dicttest DictationGuardTest
```
Expected: her senaryo `… OK`, son satır `Tüm testler geçti.`, çıkış kodu 0.

- [ ] **Step 3: Commit**

```bash
git add scripts/macos-dictation/macosdict/DictationGuard.java
git commit -m "feat(dictation): DictationGuard — IME olaylarını düz-metin yoluyla işle"
```

### Task 5: `DictationPatch` Javassist yaması

**Files:**
- Create: `scripts/macos-dictation/DictationPatch.java`

`ImageResizePatch` deseni: hedef sınıf varsayılan `hj` (Faz 1 `hiyerarşi:` logu
farklı sınıf gösterdiyse `HJ` sabiti onunla değiştirilir).

- [ ] **Step 1: DictationPatch.java'yı yaz**

```java
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UDE macOS dikte (DICTFIX=1) build-zamanı bytecode yaması.
 * Hedef: tr…editor.common.text.hj (editör metin bileşeni, JTextPane torunu).
 *   processInputMethodEvent(InputMethodEvent): yoksa override EKLENİR, varsa
 *   başına guard girilir — DictationGuard.handle true dönerse olay işlenmiştir,
 *   Swing'in composed-text makinesi (UDE doküman katmanını patlatan yol) çalışmaz.
 *
 * ÖN KOŞUL: macosdict/DictationGuard.class jar'a ÖNCEDEN enjekte edilmiş olmalı
 * (javassist çağrı derlemesi sınıfı jar classpath'inden çözer).
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class DictationPatch {
    private static final String HJ = "tr.com.havelsan.uyap.system.editor.common.text.hj";
    private static final String GUARD = "macosdict.DictationGuard";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: DictationPatch <jar> <out-dir>");
            System.exit(2);
        }
        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(args[0]);
        File outDir = new File(args[1]);

        CtClass hj = pool.get(HJ);
        CtClass ime = pool.get("java.awt.event.InputMethodEvent");
        try {
            CtMethod m = hj.getDeclaredMethod("processInputMethodEvent", new CtClass[]{ime});
            m.insertBefore("{ if (" + GUARD + ".handle(this, $1)) return; }");
            System.out.println("[DictationPatch] processInputMethodEvent: mevcut metoda guard eklendi.");
        } catch (javassist.NotFoundException nf) {
            hj.addMethod(CtNewMethod.make(
                "protected void processInputMethodEvent(java.awt.event.InputMethodEvent e) {"
              + "  if (" + GUARD + ".handle(this, e)) return;"
              + "  super.processInputMethodEvent(e);"
              + "}", hj));
            System.out.println("[DictationPatch] processInputMethodEvent: override eklendi.");
        }

        writeClass(hj, outDir);
        System.out.println("[DictationPatch] hj yamalandı.");
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

(Javassist gövde string'lerinde `//` yorum YASAK — bilinen tuzak; yukarıdaki
gövdelerde yok.)

- [ ] **Step 2: Commit**

```bash
git add scripts/macos-dictation/DictationPatch.java
git commit -m "feat(dictation): DictationPatch — processInputMethodEvent Javassist sarması"
```

### Task 6: build.sh entegrasyonu (`DICTFIX` bayrağı)

**Files:**
- Modify: `scripts/build.sh` (4 nokta: bayrak ~52. satır, kaynak yolu ~37. satır, `apply_dictfix` fonksiyonu, `patch_jar` çağrısı + yardım metni ~723. satır)

- [ ] **Step 1: Bayrak ve kaynak yolu değişkenlerini ekle**

`SKIN="${SKIN:-1}"` satırının (52) hemen altına:

```bash
DICTFIX="${DICTFIX:-1}" # 1=açık (varsayılan; macOS dikte IME düzeltmesi) | 0=kapalı
```

`TEXTKEYS_SRC="$SCRIPT_DIR/macos-textkeys"` satırının (37) hemen altına:

```bash
DICT_SRC="$SCRIPT_DIR/macos-dictation"
```

- [ ] **Step 2: apply_dictfix fonksiyonunu ekle**

`apply_imgresize()` fonksiyonunun bitiminden hemen sonra (apply_skin'den önce):

```bash
apply_dictfix() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$DICTFIX" = "1" ] || return 0
	# İdempotans: helper zaten enjekte edilmişse atla.
	# grep -q DEĞİL (SIGPIPE/pipefail tuzağı): grep tüm girdiyi okuyup >/dev/null'a yazar.
	if unzip -l "$JAR" 2>/dev/null | grep 'macosdict/DictationGuard.class' >/dev/null 2>&1; then
		c_ok "[dictfix] zaten yamalı, atlandı."; return 0
	fi
	c_info "[dictfix] macOS dikte (IME) yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[dictfix] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[dictfix] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (diğer yamalarla ortak)
	# 1) guard helper'ı derle + jar'a enjekte et (patcher'dan ÖNCE; handle
	#    çağrısı derlemesi guard'ı jar classpath'inden çözer)
	rm -rf "$BUILD/_dicthelper"; mkdir -p "$BUILD/_dicthelper"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_dicthelper" "$DICT_SRC/macosdict/DictationGuard.java" \
		|| { c_warn "[dictfix] DictationGuard derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_dicthelper" && zip -q -r "$JAR" macosdict )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_dictpatch"; mkdir -p "$BUILD/_dictpatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_dictpatch" "$DICT_SRC/DictationPatch.java" \
		|| { c_warn "[dictfix] DictationPatch derlenemedi; yama atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_dictpatch:$jvs" DictationPatch "$JAR" "$BUILD/_dictpatch/out"; then
		# Yarım-yama bırakma: helper'ı geri çıkar ki idempotans kontrolü yanılmasın.
		zip -q -d "$JAR" 'macosdict/*' >/dev/null 2>&1 || true
		c_warn "[dictfix] hj yaması uygulanamadı (UDE sürümü değişmiş olabilir); yama geri alındı."
		return 0
	fi
	( cd "$BUILD/_dictpatch/out" && zip -q -r "$JAR" tr )
	c_ok "[dictfix] dikte yaması uygulandı."
}
```

- [ ] **Step 3: patch_jar'a çağrıyı ekle**

`patch_jar()` içinde `apply_imgresize "$JAR"` satırından hemen sonra
(`apply_skin "$JAR"`'dan önce — ikisi de `hj`'yi yamalar, sıralı JVM'ler
olduğundan çakışmaz, ama dictfix imgresize'dan SONRA koşmalı ki ikisinin
çıktısı da jar'da kalsın):

```bash
	apply_dictfix "$JAR"
```

- [ ] **Step 4: Yardım metnine ekle**

`usage` içinde `SKIN (…)` açıklamasının yanına (≈727. satır bölgesi):

```
       DICTFIX (1=açık varsayılan | 0=kapalı; macOS dikte — IME olaylarını
                editörün taşıyamadığı composed-text makinesi yerine düz metin
                yoluyla işler; metin kaybı + donma düzeltmesi)
```

- [ ] **Step 5: Yamanın jar'a işlendiğini doğrula**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh download && bash scripts/build.sh patch
JAR="build/_src/app/Contents/Java/editor-app.jar"
unzip -l "$JAR" | grep macosdict
"build/Uyap Doküman Editörü.app/Contents/runtime/Contents/Home/bin/javap" -classpath "$JAR" -p tr.com.havelsan.uyap.system.editor.common.text.hj | grep processInputMethodEvent || \
javap -classpath "$JAR" -p tr.com.havelsan.uyap.system.editor.common.text.hj | grep processInputMethodEvent
```
Expected: `macosdict/DictationGuard.class` listede; javap çıktısında
`protected void processInputMethodEvent(java.awt.event.InputMethodEvent)` var.
(Jar'ı dosya sistemine AÇMA — case-insensitive FS `kx/kX` sınıflarını ezer;
javap'ı doğrudan jar'a karşı çalıştır.)

- [ ] **Step 6: Commit**

```bash
git add scripts/build.sh
git commit -m "feat(dictation): build.sh DICTFIX bayrağı + apply_dictfix yaması"
```

### Task 7: Tam build + canlı doğrulama (KULLANICI) + dokümantasyon

**Files:**
- Modify: `README.md:87` bölgesi (özellik listesine dikte düzeltmesi)
- Modify: `CLAUDE.md` (yeni mekanizma notu — kısa)

- [ ] **Step 1: Tam build**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm
bash scripts/build.sh download && bash scripts/build.sh patch && bash scripts/build.sh lookagent && bash scripts/build.sh package
```
Expected: hatasız; `[dictfix] dikte yaması uygulandı.` satırı görünür.

- [ ] **Step 2: Kullanıcı canlı doğrulaması — DUR ve sonuç bekle**

```bash
pkill -f UyapDokumanEditoru; sleep 1
UDE_DICTLOG=1 "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru"
```

Kontrol listesi:
1. **Dikte:** 2-3 cümle söyle, dikteyi kapat → metin **kalıcı**, donma **yok**.
2. **Ölü tuş:** `^` sonra `a` → `â` yazılır ("hâkim" denenir); `î`, `û` de.
3. **Emoji seçici:** ⌃⌘Space ile emoji ekle → yazılır.
4. **Normal yazım + Türkçe karakterler** (ğüşıöç) etkilenmemiş.
5. **Geri al:** dikteden sonra ⌘Z çalışıyor (adım adım geri alma kabul).
6. **Regresyon:** Option+Delete (kelime sil), Option+karakterler (@ # [ ]),
   ⌘B/⌘S kısayolları çalışıyor (MacTextKeys/MacOptionChars etkileşimi).

Sorun çıkarsa `~/Library/Logs/ude-dictation.txt` (`GUARD-HATA` satırları) incelenir.

- [ ] **Step 3: README ve CLAUDE.md güncelle**

README.md 87. satırdaki özellik cümlesine "macOS dikte düzeltmesi" eklenir
(mevcut üslupla, kalın). CLAUDE.md'ye "## Dikte (DICTFIX=1)" altında 5-6
satırlık mekanizma özeti eklenir: hedef sınıf, guard sözleşmesi (false=statüko),
ölü tuş/emoji aynı yoldan, log bayrağı `UDE_DICTLOG=1`, Faz 1 bulgusunun özeti.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs(dictation): README özellik listesi + CLAUDE.md mekanizma notu"
```

- [ ] **Step 5: Branch'i bitir**

superpowers:finishing-a-development-branch skill'i ile devam edilir
(main'e merge önerisi; kullanıcı onayıyla).
