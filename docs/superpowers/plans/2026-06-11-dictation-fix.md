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

## Faz 2 — Düzeltme (REVİZE, 2026-06-11)

> **Revizyon notu:** Faz 1, canlı JVM'e dynamic attach simülasyonuyla kapatıldı
> (kullanıcı testi beklenmeden). Bulgu: commit'teki sentetik KEY_TYPED'lar
> UDE'nin `getCaret()→(text.l)` cast'ini `ComposedTextCaret` aktifken patlatıyor.
> JDK, bileşende kayıtlı `InputMethodListener` varsa sentezi kapatır ve commit
> `mapCommittedTextToAction` ile temiz akar — canlı deneyle kanıtlandı
> (spec'teki "EK" bölümü). Bu yüzden eski Task 3-6 (DictationGuardTest,
> DictationGuard, DictationPatch, DICTFIX build entegrasyonu) İPTAL; düzeltme
> tek agent sınıfına indi. Vendor jar'a dokunulmaz.

### Task 3 (revize): `DictationFix` agent sınıfı

**Files:**
- Create: `scripts/macos-textkeys/macostextkeys/DictationFix.java`
- Modify: `scripts/macos-textkeys/macostextkeys/MacTextKeys.java` (install() içine bir satır)

- [ ] **Step 1: DictationFix.java'yı yaz**

```java
package macostextkeys;

/*
 * macOS dikte (IME) düzeltmesi.
 *
 * Sorun: macOS dikte metni InputMethodEvent'lerle verir. JTextComponent,
 * processInputMethodEvent override'ı ya da kayıtlı InputMethodListener'ı
 * OLMAYAN bileşeni "pasif IME istemcisi" sayar ve commit edilen her karakteri
 * SENTETİK KEY_TYPED olarak keyTyped dinleyicilerine işler
 * (JTextComponent.replaceInputMethodText). UDE'nin kelime-denetim zinciri
 * (im.keyTyped → … → gui.aC.a) o sırada getCaret()'i kendi text.l tipine cast
 * eder; commit anında caret Swing'in geçici ComposedTextCaret'i olduğundan
 * ClassCastException fırlar → commit yarıda kalır (dikte metni kaybolur),
 * EDT'deki istisna CInputMethod akışını bozar (donma).
 *
 * Çözüm: her JTextComponent odaklandığında no-op InputMethodListener eklenir.
 * addInputMethodListener, JTextComponent'te needToSendKeyTypedEvent=false
 * yapar → sentetik keyTyped üretilmez; committed metin
 * mapCommittedTextToAction ile editör kit'inin NORMAL yazma aksiyonundan
 * belgeye girer. Composed görüntüleme, ölü tuşlar (^→â) ve emoji seçici aynı
 * yoldan bozulmadan çalışır (canlı JVM'de attach deneyiyle kanıtlandı).
 */

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;

import javax.swing.text.JTextComponent;

public final class DictationFix {

    /** No-op dinleyici; varlığı sentetik keyTyped üretimini kapatır. */
    private static final class NoopImListener implements InputMethodListener {
        @Override public void inputMethodTextChanged(InputMethodEvent e) {}
        @Override public void caretPositionChanged(InputMethodEvent e) {}
    }

    private DictationFix() {}

    public static void install() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() != FocusEvent.FOCUS_GAINED) return;
                    Object src = e.getSource();
                    if (src instanceof JTextComponent) apply((JTextComponent) src);
                }
            }, AWTEvent.FOCUS_EVENT_MASK);
        } catch (Throwable t) {
            // Agent asla uygulamayı düşürmemeli.
            System.err.println("[macos-textkeys] DictationFix kurulamadı: " + t);
        }
    }

    private static void apply(JTextComponent tc) {
        try {
            for (InputMethodListener l : tc.getInputMethodListeners()) {
                if (l instanceof NoopImListener) return; // idempotent
            }
            tc.addInputMethodListener(new NoopImListener());
        } catch (Throwable t) {
            // EDT'yi asla düşürme.
        }
    }
}
```

- [ ] **Step 2: MacTextKeys.install()'a kancayı ekle**

`DictationProbe.install();` satırından hemen sonra:

```java
        // Dikte düzeltmesi: no-op InputMethodListener → sentetik keyTyped kapanır,
        // commit kit'in normal yazma aksiyonundan akar (metin kaybı + donma biter).
        DictationFix.install();
```

- [ ] **Step 3: Derlemeyi doğrula**

Run: `bash scripts/build.sh textkeys`
Expected: `macos-textkeys derlendi (16 sınıf)` (DictationFix + 2 iç sınıf eklendi), hata yok.

- [ ] **Step 4: Commit**

```bash
git add scripts/macos-textkeys/macostextkeys/DictationFix.java scripts/macos-textkeys/macostextkeys/MacTextKeys.java
git commit -m "fix(dictation): no-op InputMethodListener — sentetik keyTyped kapanır, dikte commit'i temiz akar"
```

### Task 4 (revize): Repaketle + attach doğrulaması

**Files:**
- Create (geçici, repo dışı): `/tmp/dictsim/src/DictSim3.java`

- [ ] **Step 1: Paketle**

Run: `bash scripts/build.sh package`
Expected: `Paketlendi: …/build/Uyap Doküman Editörü.app`.
(Jar yamaları değişmedi; yalnız agent jar yenilendi — download/patch gerekmez.)

- [ ] **Step 2: Yamalı uygulamayı başlat, DictSim3 ile doğrula**

DictSim3 = DictSim2'nin listener EKLEMEYEN varyantı: editörü bulur, agent'ın
listener'ı odakla takması için sentetik FOCUS_GAINED dispatch eder,
`getInputMethodListeners().length`'i loglar, sonra aynı IME dizisini
(composed×2 → commit → kapanış → ölü tuş → iptal) sürer.

Run:
```bash
pkill -f UyapDokumanEditoru; sleep 1
(UDE_DICTLOG=1 "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru" >/tmp/ude-stdout.log 2>&1 &)
sleep 12
J11="$(/usr/libexec/java_home -v 11)"
: > /tmp/ude-dict-test.log
"$J11/bin/java" -cp /tmp/dictsim/out AttachMain "$(pgrep -f UyapDokumanEditoru | head -1)" /tmp/dictsim/dictsim3.jar
sleep 3; cat /tmp/ude-dict-test.log
```
Expected: `imListeners=1` (agent takmış), commit sonrası belge tam metin
("deneme bir iki"), EDT-HATA YOK, ölü tuş `â`, iptal temiz.

- [ ] **Step 3: Geçici dosyaları temizle**

Run: `pkill -f UyapDokumanEditoru; rm -rf /tmp/dictsim /tmp/ude-dict-test.log /tmp/ude-stdout.log /tmp/JTextComponent.java /tmp/ude-ime-probe`

### Task 5 (revize): Dokümantasyon + kullanıcı canlı dikte testi

**Files:**
- Modify: `README.md` (özellik listesi, ~87. satır bölgesi)
- Modify: `CLAUDE.md` (mekanizma notu)

- [ ] **Step 1: README + CLAUDE.md güncelle**

README özellik cümlesine "macOS dikte düzeltmesi" eklenir. CLAUDE.md'ye kısa
bölüm: kök neden (sentetik keyTyped × ComposedTextCaret cast), çözüm
(DictationFix no-op listener), teşhis araçları (DictationProbe UDE_DICTLOG=1,
dynamic attach DictSim deseni).

- [ ] **Step 2: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs(dictation): README özellik + CLAUDE.md dikte mekanizma notu"
```

- [ ] **Step 3: Kullanıcı canlı doğrulaması — DUR ve sonuç bekle**

```bash
pkill -f UyapDokumanEditoru; sleep 1
UDE_DICTLOG=1 "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru"
```
Kontrol: (1) dikteyle 2-3 cümle + dikteyi kapat → metin kalıcı, donma yok;
(2) `^`+a → â; (3) ⌃⌘Space emoji; (4) normal yazım + ğüşıöç; (5) ⌘Z;
(6) Option+Delete, @ # [ ], ⌘B/⌘S regresyonu.

- [ ] **Step 4: Branch'i bitir**

superpowers:finishing-a-development-branch ile devam (main'e merge, kullanıcı onayıyla).
