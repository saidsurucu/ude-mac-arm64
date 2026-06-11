# Otomatik Düzeltme Seçenekleri Anında Etkinleşme (LIVETOGGLE) — Uygulama Planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "Otomatik Büyük Harf", "Baş Harfler Büyük" ve "Kelime Denetimi" onay kutuları
işaretlendiği anda (restart'sız) açık belgelere uygulansın; "yeniden başlatılmadığı
sürece aktif olmayacaktır" diyaloğu kalksın.

**Architecture:** Build-zamanı Javassist yaması (mevcut `apply_pasteimage` deseni).
Jar'a tamamen reflection tabanlı `macoslivetoggle/LiveToggle` helper'ı enjekte edilir;
`LiveTogglePatch` üç toggle eyleminin (`text.dq`/`dA`/`db`) `a(ActionEvent)` metoduna
kaynak-senkronu (insertBefore), diyalog kaldırma (ExprEditor `kP.b` → no-op) ve
`LiveToggle.apply` (insertAfter) ekler. `text.fk` (açılış kaydı) DEĞİŞMEZ.

**Tech Stack:** bash 3.2 (build.sh), Javassist 3.30.2, Zulu 11 (helper hedefi),
Zulu 21 javac (build), dynamic attach probe (doğrulama).

**Spec:** `docs/superpowers/specs/2026-06-12-autocorrect-live-toggle-design.md`

**Çalışma dizini:** `/Users/saidsurucu/Documents/GitHub/ude-mac-arm/.claude/worktrees/livetoggle`
(dal: `feature/autocorrect-live-toggle`). Ana checkout'a DOKUNMA — orada paralel bir
oturum çalışıyor. `scripts/macos-autocorrect/` ve `apply_autocorrect` adları o işe ait;
bizim birim `macos-livetoggle`/`apply_livetoggle`.

**Kritik arka plan (yeniden keşfetme):**
- Dinleyici sınıfları: `tr.com.havelsan.uyap.system.editor.common.text.hN`
  (Otomatik Büyük Harf), `text.fY` (Baş Harfler Büyük), `text.im` (Kelime
  Denetimi, Zemberek). Üçü de `java.awt.event.KeyListener`; editör `text.fi`
  (somut sınıf `text.t`).
- Tercihler: `tr.com.havelsan.uyap.system.pki.b.l` singleton →
  `~/.uki/acilisDegerleri.xml` `initValues` grubu; anahtarlar `ToUpperCase`,
  `FirstLetterUpperCase`, `SpellCheck`.
- Şerit onay kutuları: `tr.gov.uyap.system.a.b.a.a.z` statikleri; menü çubuğu eşleri:
  `tr.com.havelsan.uyap.system.editor.common.gui.ak` statikleri.
- **Obfuscate üye adları çakışır** (`z`'de üç ayrı `a` alanı, `im`'de üç ayrı `a`
  metodu) → ne javac kaynak erişimi ne Javassist alan referansı güvenli; uygulama
  sınıflarına TÜM erişim reflection ile, üyeler TİP + görünen METİN ile seçilir.
- Javassist tuzakları: gövde string'lerinde `//` yorum YASAK; sınıf başına tek
  `writeFile`.
- Test tuzağı: sentetik `KeyEvent`'i `dispatchEvent` ile vermek İŞLEMEZ
  (KeyboardFocusManager yutar/yönlendirir); dinleyici mantığı gerekirse
  `hN.keyTyped(ev)` doğrudan çağrısıyla test edilir.

---

### Task 1: LiveToggle helper sınıfı

**Files:**
- Create: `scripts/macos-livetoggle/macoslivetoggle/LiveToggle.java`

- [ ] **Step 1: Dosyayı oluştur** (içerik birebir):

```java
package macoslivetoggle;

/*
 * Otomatik düzeltme seçeneklerini (Otomatik Büyük Harf / Baş Harfler Büyük /
 * Kelime Denetimi) toggle ANINDA açık belgelere uygular. Stok UDE dinleyicileri
 * yalnız editör kurulurken (text.fk.run, tercih "true" ise) taktığından
 * değişiklik bir sonraki açılışa kalıyordu.
 *
 * Obfuscate sınıflarda aynı adda birden çok üye var (z'de üç ayrı `a` alanı,
 * im'de üç ayrı `a` metodu) → kaynak-düzeyi erişim derlenemez; uygulama
 * sınıflarına TÜM erişim reflection iledir ve üyeler TİP + görünen METİN ile
 * seçilir. Bu sınıf jar classpath'i OLMADAN derlenir.
 *
 * Çağrı noktaları (LiveTogglePatch enjekte eder, hep EDT):
 *   syncSource(key, e) — toggle eyleminin başı (insertBefore)
 *   apply(key)         — toggle eyleminin sonu (insertAfter)
 */

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Properties;

import javax.swing.JCheckBoxMenuItem;

public final class LiveToggle {

    private static final String TEXT =
        "tr.com.havelsan.uyap.system.editor.common.text.";
    private static final String PREFS =
        "tr.com.havelsan.uyap.system.pki.b.l";
    private static final String RIBBON_BOXES =
        "tr.gov.uyap.system.a.b.a.a.z";
    private static final String MENU_BOXES =
        "tr.com.havelsan.uyap.system.editor.common.gui.ak";
    private static final String ZEMBEREK = "net.zemberek.erisim.Zemberek";

    private LiveToggle() {}

    /** key → dinleyici sınıfı (FQN). */
    private static String listenerClass(String key) {
        if ("ToUpperCase".equals(key)) return TEXT + "hN";
        if ("FirstLetterUpperCase".equals(key)) return TEXT + "fY";
        return TEXT + "im";
    }

    /** key → onay kutusunun görünen metni (kutular metinle bulunur). */
    private static String boxText(String key) {
        if ("ToUpperCase".equals(key)) return "Otomatik Büyük Harf";
        if ("FirstLetterUpperCase".equals(key)) return "Baş Harfler Büyük";
        return "Kelime Denetimi";
    }

    /**
     * Toggle eyleminin BAŞI: tıklanan kutu menü kopyasıysa şeritteki kutuyu
     * ona eşitle. Orijinal gövde yeni değeri HEP şeritten okur; bu, menü
     * yolundaki eski-değer-kaydetme (upstream) bug'ını düzeltir.
     */
    public static void syncSource(String key, ActionEvent e) {
        try {
            Object s = (e == null) ? null : e.getSource();
            if (!(s instanceof JCheckBoxMenuItem)) return;
            JCheckBoxMenuItem src = (JCheckBoxMenuItem) s;
            JCheckBoxMenuItem ribbon = findBox(RIBBON_BOXES, boxText(key));
            if (ribbon != null && ribbon != src) {
                ribbon.setSelected(src.isSelected());
            }
        } catch (Throwable t) {
            /* toggle eylemi hiçbir koşulda düşmemeli */
        }
    }

    /**
     * Toggle eyleminin SONU: kalıcılaşan tercihi okur (tek doğruluk kaynağı;
     * orijinal gövde tercihi az önce yazdı), açık tüm editörlerde dinleyiciyi
     * ekler/söker, kutu kopyalarını tercihe eşitler.
     */
    public static void apply(String key) {
        try {
            boolean on = readPref(key);
            if (on && "SpellCheck".equals(key) && !zemberekReady()) {
                /* Zemberek yüklenemedi: stok fk davranışıyla aynı — sessizce
                   dinleyicisiz kal, kutuyu yine de tercihle eşitle. */
                syncBoxes(key, true);
                return;
            }
            Class<?> fiCls = Class.forName(TEXT + "fi");
            Class<?> lCls = Class.forName(listenerClass(key));
            Frame[] frames = Frame.getFrames();
            for (int i = 0; i < frames.length; i++) {
                try {
                    walk(frames[i], fiCls, lCls, on);
                } catch (Throwable perWindow) {
                    /* tek bozuk pencere kalanını engellemesin */
                }
            }
            syncBoxes(key, on);
        } catch (Throwable t) {
            /* sessiz: en kötü ihtimalle stok (restart) davranışına düşülür */
        }
    }

    /* ——— tercih okuma ——— */

    private static boolean readPref(String key) throws Exception {
        Class<?> lCls = Class.forName(PREFS);
        Object inst = null;
        Method[] ms = lCls.getDeclaredMethods();
        for (int i = 0; i < ms.length; i++) {
            Method m = ms[i];
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                    && m.getReturnType() == lCls) {
                m.setAccessible(true);
                inst = m.invoke(null);
                break;
            }
        }
        if (inst == null) return false;
        for (int i = 0; i < ms.length; i++) {
            Method m = ms[i];
            if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                    && m.getReturnType() == Properties.class) {
                m.setAccessible(true);
                Properties p = (Properties) m.invoke(inst);
                String v = (p == null) ? null : p.getProperty(key);
                if (v != null) return Boolean.parseBoolean(v);
            }
        }
        return false;
    }

    /* ——— editör dolaşma ——— */

    private static void walk(Component c, Class<?> fiCls, Class<?> lCls,
                             boolean on) throws Exception {
        if (fiCls.isInstance(c)) setListener(c, lCls, on);
        if (c instanceof Container) {
            Component[] kids = ((Container) c).getComponents();
            for (int i = 0; i < kids.length; i++) walk(kids[i], fiCls, lCls, on);
        }
        if (c instanceof Window) {
            Window[] owned = ((Window) c).getOwnedWindows();
            for (int i = 0; i < owned.length; i++) walk(owned[i], fiCls, lCls, on);
        }
    }

    private static void setListener(Component editor, Class<?> lCls,
                                    boolean on) throws Exception {
        KeyListener[] ls = editor.getKeyListeners();
        if (on) {
            for (int i = 0; i < ls.length; i++) {
                if (ls[i].getClass() == lCls) return; /* zaten takılı */
            }
            editor.addKeyListener(
                (KeyListener) lCls.getDeclaredConstructor().newInstance());
        } else {
            for (int i = 0; i < ls.length; i++) {
                if (ls[i].getClass() == lCls) editor.removeKeyListener(ls[i]);
            }
        }
    }

    /** Zemberek hazır mı? im'in statik getter'ı TEMBEL yükler; hata → null. */
    private static boolean zemberekReady() {
        try {
            Class<?> imCls = Class.forName(TEXT + "im");
            Class<?> zCls = Class.forName(ZEMBEREK);
            Method[] ms = imCls.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && m.getReturnType() == zCls) {
                    m.setAccessible(true);
                    return m.invoke(null) != null;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /* ——— onay kutusu kopyaları ——— */

    private static void syncBoxes(String key, boolean on) {
        String text = boxText(key);
        String[] holders = { RIBBON_BOXES, MENU_BOXES };
        for (int i = 0; i < holders.length; i++) {
            JCheckBoxMenuItem cb = findBox(holders[i], text);
            if (cb != null && cb.isSelected() != on) cb.setSelected(on);
        }
    }

    /** holder sınıfının statik JCheckBoxMenuItem alanlarında metni eşleşeni bul. */
    private static JCheckBoxMenuItem findBox(String holder, String text) {
        try {
            Class<?> cls = Class.forName(holder);
            Field[] fs = cls.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!JCheckBoxMenuItem.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                JCheckBoxMenuItem cb = (JCheckBoxMenuItem) f.get(null);
                if (cb != null && text.equals(cb.getText())) return cb;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }
}
```

Not: `cb.setSelected(on)` JCheckBoxMenuItem'da ActionEvent ÜRETMEZ (yalnız
`doClick` üretir) — sonsuz toggle döngüsü riski yoktur.

- [ ] **Step 2: Derleme denetimi (jar'sız — sınıf bağımsız olmalı)**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm/.claude/worktrees/livetoggle
JC=/Users/saidsurucu/Library/Java/JavaVirtualMachines/zulu-21-arm64.jdk/Contents/Home/bin/javac
"$JC" --release 11 -encoding UTF-8 -d /tmp/ltg-check scripts/macos-livetoggle/macoslivetoggle/LiveToggle.java && echo DERLEME-OK
rm -rf /tmp/ltg-check
```
Expected: `DERLEME-OK` (uyarısız).

- [ ] **Step 3: Commit**

```bash
git add scripts/macos-livetoggle/macoslivetoggle/LiveToggle.java
git commit -m "feat(livetoggle): otomatik düzeltme seçeneklerini canlı uygulayan helper"
```

---

### Task 2: LiveTogglePatch (Javassist patcher)

**Files:**
- Create: `scripts/macos-livetoggle/LiveTogglePatch.java`

- [ ] **Step 1: Dosyayı oluştur** (içerik birebir):

```java
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/*
 * Otomatik düzeltme toggle eylemlerini anında-etkin yapar.
 *
 * Hedefler (üçü de aynı şekil): a(Ljava/awt/event/ActionEvent;)V
 *   text.dq — "To-Uppercase"        → pref ToUpperCase        (kutu z.a)
 *   text.dA — "first Letter Upper"  → pref FirstLetterUpperCase (kutu z.b)
 *   text.db — "spell-check"         → pref SpellCheck         (kutu z.c)
 *
 * Üç dokunuş:
 *   1) insertBefore: LiveToggle.syncSource(key, $1) — menü kopyasından
 *      tıklanınca şerit kutusu kaynağa eşitlenir (orijinal gövde değeri hep
 *      şeritten okur; upstream eski-değer-kaydetme bug'ı kapanır).
 *   2) ExprEditor: gui.kP.b(...) "yeniden başlatılmadığı sürece..." diyaloğu
 *      → $_ = 0; (tam olarak 1 çağrı beklenir; değilse UDE sürümü değişmiştir
 *      → İSTİSNA, build geri alır).
 *   3) insertAfter: LiveToggle.apply(key) — tercihi okuyup açık editörlere
 *      uygular.
 *
 * Javassist kuralları: gövde string'lerinde // yorum YASAK; sınıf başına tek
 * writeFile. Helper (macoslivetoggle/LiveToggle) bu patcher koşmadan ÖNCE
 * jar'a enjekte edilmiş olmalı (insertBefore/After derlemesi onu jar
 * classpath'inden çözer).
 *
 * Kullanım: java LiveTogglePatch <editor-app.jar> <çıktı-dizini>
 */
public class LiveTogglePatch {

    private static final String PKG =
        "tr.com.havelsan.uyap.system.editor.common.text.";

    public static void main(String[] args) throws Exception {
        ClassPool cp = ClassPool.getDefault();
        cp.insertClassPath(args[0]);
        patch(cp, args[1], PKG + "dq", "ToUpperCase");
        patch(cp, args[1], PKG + "dA", "FirstLetterUpperCase");
        patch(cp, args[1], PKG + "db", "SpellCheck");
        System.out.println("[livetoggle] dq/dA/db yamalandı (anında etkinleşme + diyalog kaldırıldı).");
    }

    private static void patch(ClassPool cp, String outDir, String cls,
                              String key) throws Exception {
        CtClass cc = cp.get(cls);
        CtMethod m = cc.getMethod("a", "(Ljava/awt/event/ActionEvent;)V");

        m.insertBefore(
            "macoslivetoggle.LiveToggle.syncSource(\"" + key + "\", $1);");

        final int[] removed = { 0 };
        m.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall c) throws javassist.CannotCompileException {
                if ("b".equals(c.getMethodName())
                        && c.getClassName().endsWith(".kP")) {
                    c.replace("$_ = 0;");
                    removed[0]++;
                }
            }
        });
        if (removed[0] != 1) {
            throw new IllegalStateException(cls + ": beklenen 1 kP.b diyalog çağrısı, bulunan " + removed[0]);
        }

        m.insertAfter("macoslivetoggle.LiveToggle.apply(\"" + key + "\");");

        cc.writeFile(outDir);
        cc.detach();
    }
}
```

- [ ] **Step 2: Derleme denetimi (Javassist classpath'iyle)**

Run:
```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm/.claude/worktrees/livetoggle
JC=/Users/saidsurucu/Library/Java/JavaVirtualMachines/zulu-21-arm64.jdk/Contents/Home/bin/javac
JVS=/Users/saidsurucu/Documents/GitHub/ude-mac-arm/scripts/lib/javassist-3.30.2-GA.jar
[ -s "$JVS" ] || curl -fsSL -o "$JVS" https://repo1.maven.org/maven2/org/javassist/javassist/3.30.2-GA/javassist-3.30.2-GA.jar
"$JC" --release 11 -encoding UTF-8 -cp "$JVS" -d /tmp/ltg-check scripts/macos-livetoggle/LiveTogglePatch.java && echo DERLEME-OK
rm -rf /tmp/ltg-check
```
Expected: `DERLEME-OK`.

- [ ] **Step 3: Commit**

```bash
git add scripts/macos-livetoggle/LiveTogglePatch.java
git commit -m "feat(livetoggle): dq/dA/db toggle eylemlerine Javassist yaması"
```

---

### Task 3: build.sh entegrasyonu

**Files:**
- Modify: `scripts/build.sh` (worktree kopyası)

- [ ] **Step 1: Kaynak dizini değişkeni** — satır 44 (`RESIZE_SRC=...`) altına ekle:

```bash
LIVETOGGLE_SRC="$SCRIPT_DIR/macos-livetoggle" # otomatik düzeltme seçenekleri anında etkin
```

- [ ] **Step 2: Bayrak** — satır ~52 (`SKIN="${SKIN:-1}"`) altına ekle:

```bash
LIVETOGGLE="${LIVETOGGLE:-1}" # 1=açık (varsayılan; Otomatik Büyük Harf vb. toggle'lar restart'sız etkin) | 0=kapalı
```

- [ ] **Step 3: apply_livetoggle fonksiyonu** — `apply_imgresize()` fonksiyonunun
bittiği yerin altına (yaklaşık satır 501, `apply_skin()` öncesi) ekle:

```bash
apply_livetoggle() {  # $1=JAR — patch_jar içinden çağrılır
	local JAR="$1"
	[ "$LIVETOGGLE" = "1" ] || return 0
	# İdempotans: helper zaten enjekte edilmişse atla.
	# grep -q DEĞİL (SIGPIPE/pipefail tuzağı): grep tüm girdiyi okuyup >/dev/null'a yazar.
	if unzip -l "$JAR" 2>/dev/null | grep 'macoslivetoggle/LiveToggle.class' >/dev/null 2>&1; then
		c_ok "[livetoggle] zaten yamalı, atlandı."; return 0
	fi
	c_info "[livetoggle] otomatik düzeltme seçenekleri anında-etkin yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[livetoggle] 17+ java yok, yama atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[livetoggle] 17+ javac yok, yama atlandı."; return 0; }
	jvs="$(icon_deps)"   # Javassist (diğer yamalarla ortak)
	# 1) helper'ı derle + jar'a enjekte et (patcher'dan ÖNCE; insertBefore/After
	#    derlemesi LiveToggle'ı jar classpath'inden çözer)
	rm -rf "$BUILD/_ltghelper"; mkdir -p "$BUILD/_ltghelper"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_ltghelper" "$LIVETOGGLE_SRC/macoslivetoggle/LiveToggle.java" \
		|| { c_warn "[livetoggle] LiveToggle derlenemedi; yama atlandı."; return 0; }
	( cd "$BUILD/_ltghelper" && zip -q -r "$JAR" macoslivetoggle )
	# 2) patcher'ı derle + çalıştır + çıktıyı jar'a enjekte et
	rm -rf "$BUILD/_ltgpatch"; mkdir -p "$BUILD/_ltgpatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_ltgpatch" "$LIVETOGGLE_SRC/LiveTogglePatch.java" \
		|| { c_warn "[livetoggle] LiveTogglePatch derlenemedi; yama atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_ltgpatch:$jvs" LiveTogglePatch "$JAR" "$BUILD/_ltgpatch/out"; then
		# Yarım-yama bırakma: helper'ı geri çıkar ki idempotans kontrolü yanılmasın.
		zip -q -d "$JAR" 'macoslivetoggle/*' >/dev/null 2>&1 || true
		c_warn "[livetoggle] toggle yaması uygulanamadı (UDE sürümü değişmiş olabilir); yama geri alındı."
		return 0
	fi
	( cd "$BUILD/_ltgpatch/out" && zip -q -r "$JAR" tr )
	c_ok "[livetoggle] seçenekler artık anında etkin (restart gerekmez)."
}
```

- [ ] **Step 4: patch_jar zincirine ekle** — `patch_jar()` içinde
`apply_imgresize "$JAR"` satırının ALTINA (apply_skin'den önce) ekle:

```bash
	apply_livetoggle "$JAR"
```

- [ ] **Step 5: Yardım metni + CLI hedefi** — `help()` içinde `SKIN (...)`
bloğunun altına ekle:

```
       LIVETOGGLE (1=açık varsayılan | 0=kapalı; Otomatik Büyük Harf / Baş
                 Harfler Büyük / Kelime Denetimi toggle'ları restart'sız anında
                 etkinleşir, "yeniden başlat" diyaloğu kalkar)
```

ve `case` dispatch'inde `image-resize)` satırının altına ekle:

```bash
	live-toggle) apply_livetoggle "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
```

- [ ] **Step 6: Sözdizimi denetimi**

Run: `bash -n scripts/build.sh && echo SYNTAX-OK`
Expected: `SYNTAX-OK`

- [ ] **Step 7: Commit**

```bash
git add scripts/build.sh
git commit -m "feat(livetoggle): build.sh apply_livetoggle birimi (LIVETOGGLE=1 varsayılan)"
```

---

### Task 4: Tam build + bytecode doğrulaması

**Files:** (kaynak değişikliği yok — build + doğrulama)

- [ ] **Step 1: İndirme önbelleklerini ana depodan bağla** (worktree'de yeniden
indirme olmasın):

```bash
cd /Users/saidsurucu/Documents/GitHub/ude-mac-arm/.claude/worktrees/livetoggle
ln -sfn ../../../downloads downloads
ln -sfn ../../../vendor vendor
mkdir -p scripts/lib
cp -n /Users/saidsurucu/Documents/GitHub/ude-mac-arm/scripts/lib/javassist-3.30.2-GA.jar scripts/lib/ 2>/dev/null || true
```

Not: `downloads`/`vendor` gitignore'dadır; symlink commit edilmez.

- [ ] **Step 2: Tam build döngüsü** (download HER iterasyonda şart — taze kaynak):

```bash
bash scripts/build.sh download && bash scripts/build.sh deps \
  && bash scripts/build.sh patch && bash scripts/build.sh shim \
  && bash scripts/build.sh textkeys && bash scripts/build.sh zoom \
  && bash scripts/build.sh lookagent && bash scripts/build.sh package
```

Expected: `[livetoggle] dq/dA/db yamalandı (anında etkinleşme + diyalog kaldırıldı).`
satırı ve `[livetoggle] seçenekler artık anında etkin (restart gerekmez).`;
sonda .app üretimi. (İlk koşuda `shim`/`jdk` eksikse build.sh yönlendirir;
`bash scripts/build.sh all` da kabul.)

- [ ] **Step 3: Bytecode doğrulaması** — üç sınıfta LiveToggle çağrıları var,
kP.b kalmadı:

```bash
JP=/Users/saidsurucu/Library/Java/JavaVirtualMachines/zulu-21-arm64.jdk/Contents/Home/bin/javap
JAR="build/_src/app/Contents/Java/editor-app.jar"
for c in dq dA db; do
  echo "== $c =="
  "$JP" -c -p -classpath "$JAR" "tr.com.havelsan.uyap.system.editor.common.text.$c" \
    | grep -c "LiveToggle"
  "$JP" -c -p -classpath "$JAR" "tr.com.havelsan.uyap.system.editor.common.text.$c" \
    | grep -c "kP.b" || true
done
```

Expected: her sınıf için LiveToggle sayısı ≥ 2 (syncSource + apply), `kP.b`
sayısı 0 (grep -c `0` basar ve sıfır-dışı çıkar — `|| true` bu yüzden var).

- [ ] **Step 4: İdempotans denetimi** — `bash scripts/build.sh live-toggle`
tekrar koş: Expected: `[livetoggle] zaten yamalı, atlandı.`

---

### Task 5: Canlı uçtan-uca doğrulama (attach probe)

**Files:**
- Create: `/tmp/ltg-e2e/LtgProbe.java` (geçici — commit edilmez)
- Create: `/tmp/ltg-e2e/Attacher.java` (geçici — commit edilmez)

- [ ] **Step 1: Eski süreci kapat, YAMALI uygulamayı worktree build'inden başlat**

```bash
pkill -f UyapDokumanEditoru; sleep 2
"/Users/saidsurucu/Documents/GitHub/ude-mac-arm/.claude/worktrees/livetoggle/build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru" &
sleep 20
pgrep -f UyapDokumanEditoru
```
Expected: pid basılır. (`open` KULLANMA — LaunchServices -54 ve eski-süreç
penceresi tuzağı.)

- [ ] **Step 2: Probe kaynaklarını yaz** — `/tmp/ltg-e2e/LtgProbe.java`:

```java
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.KeyListener;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/*
 * E2E: şeritteki "Otomatik Büyük Harf" kutusunu doClick ile iki kez tıklar
 * (kapat→aç), her adımda editörün KeyListener listesini ve görünür diyalog
 * olup olmadığını /tmp/ltg-e2e.log'a yazar. doClick patched eylemi uçtan uca
 * sürer: syncSource → pref yaz → (diyalog YOK) → LiveToggle.apply.
 * invokeLater kullanılır (invokeAndWait DEĞİL): yama başarısızsa modal diyalog
 * EDT'yi bloklar, log yarım kalır → test görünür biçimde patlar.
 */
public class LtgProbe {
    static PrintWriter out;

    public static void agentmain(String args, Instrumentation inst) {
        try {
            out = new PrintWriter(new FileWriter("/tmp/ltg-e2e.log", false), true);
            out.println("=== LtgProbe basladi ===");
            SwingUtilities.invokeLater(new Step("ONCE", true));
            SwingUtilities.invokeLater(new Step("SONRA-1.tik", false));
            SwingUtilities.invokeLater(new Step("SONRA-2.tik", false));
            SwingUtilities.invokeLater(new Step("SON", true));
        } catch (Throwable t) {
            try { t.printStackTrace(out); } catch (Throwable ignore) {}
        }
    }

    /* dumpOnly=true: yalnız durum yaz; false: ÖNCE tıkla sonra durum yaz. */
    static class Step implements Runnable {
        final String label; final boolean dumpOnly;
        Step(String label, boolean dumpOnly) { this.label = label; this.dumpOnly = dumpOnly; }
        public void run() {
            try {
                if (!dumpOnly) {
                    JCheckBoxMenuItem cb = findBox("Otomatik Büyük Harf");
                    if (cb == null) { out.println(label + ": KUTU BULUNAMADI"); return; }
                    cb.doClick();
                }
                dump(label);
            } catch (Throwable t) { t.printStackTrace(out); }
        }
    }

    static void dump(String label) throws Exception {
        Class<?> fiCls = Class.forName("tr.com.havelsan.uyap.system.editor.common.text.fi");
        StringBuilder sb = new StringBuilder(label + ": editorListeners=[");
        Frame[] fr = Frame.getFrames();
        for (int i = 0; i < fr.length; i++) collect(fr[i], fiCls, sb);
        sb.append("]");
        JCheckBoxMenuItem cb = findBox("Otomatik Büyük Harf");
        sb.append(" kutu=").append(cb == null ? "yok" : String.valueOf(cb.isSelected()));
        boolean dialog = false;
        Window[] ws = Window.getWindows();
        for (int i = 0; i < ws.length; i++) {
            if (ws[i] instanceof JDialog && ws[i].isShowing()) dialog = true;
        }
        sb.append(" gorunurDiyalog=").append(dialog);
        out.println(sb.toString());
    }

    static void collect(Component c, Class<?> fiCls, StringBuilder sb) {
        if (fiCls.isInstance(c)) {
            KeyListener[] ls = ((Component) c).getKeyListeners();
            for (int i = 0; i < ls.length; i++) {
                String n = ls[i].getClass().getName();
                if (n.endsWith(".hN") || n.endsWith(".fY") || n.endsWith(".im")) {
                    sb.append(n.substring(n.lastIndexOf('.') + 1)).append(' ');
                }
            }
            sb.append('|');
        }
        if (c instanceof Container) {
            Component[] kids = ((Container) c).getComponents();
            for (int i = 0; i < kids.length; i++) collect(kids[i], fiCls, sb);
        }
        if (c instanceof Window) {
            Window[] owned = ((Window) c).getOwnedWindows();
            for (int i = 0; i < owned.length; i++) collect(owned[i], fiCls, sb);
        }
    }

    static JCheckBoxMenuItem findBox(String text) throws Exception {
        Class<?> z = Class.forName("tr.gov.uyap.system.a.b.a.a.z");
        Field[] fs = z.getDeclaredFields();
        for (int i = 0; i < fs.length; i++) {
            Field f = fs[i];
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!JCheckBoxMenuItem.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            JCheckBoxMenuItem cb = (JCheckBoxMenuItem) f.get(null);
            if (cb != null && text.equals(cb.getText())) return cb;
        }
        return null;
    }
}
```

`/tmp/ltg-e2e/Attacher.java`:

```java
import com.sun.tools.attach.VirtualMachine;

public class Attacher {
    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], null); } finally { vm.detach(); }
        System.out.println("agent yuklendi");
    }
}
```

- [ ] **Step 3: Derle, jar'la (iç sınıflar DAHİL — AgentInitializationException
tuzağı), MUTLAK yolla attach et**

```bash
cd /tmp/ltg-e2e
JDK11=/Users/saidsurucu/Library/Java/JavaVirtualMachines/zulu-11-arm64.jdk/Contents/Home
"$JDK11/bin/javac" -encoding UTF-8 LtgProbe.java Attacher.java
printf 'Agent-Class: LtgProbe\n' > m.mf
"$JDK11/bin/jar" cfm ltg-probe.jar m.mf LtgProbe.class 'LtgProbe$Step.class'
"$JDK11/bin/java" -cp . Attacher "$(pgrep -f UyapDokumanEditoru | head -1)" /tmp/ltg-e2e/ltg-probe.jar
sleep 3
cat /tmp/ltg-e2e.log
```

Expected (ToUpperCase tercihte true olduğundan başlangıçta hN takılı):
```
ONCE: editorListeners=[hN im |] kutu=true gorunurDiyalog=false
SONRA-1.tik: editorListeners=[im |] kutu=false gorunurDiyalog=false
SONRA-2.tik: editorListeners=[hN im |] kutu=true gorunurDiyalog=false
SON: editorListeners=[hN im |] kutu=true gorunurDiyalog=false
```
(`im` satırda görünür çünkü SpellCheck=true; sıra önemli değil. KRİTİK
beklentiler: 1. tıkta `hN` GİTMELİ, 2. tıkta GERİ GELMELİ, `gorunurDiyalog`
HEP false, log 4 satır TAM olmalı — yarım log = diyalog EDT'yi bloklamış =
yama başarısız.)

- [ ] **Step 4: Tercih dosyası net-sıfır doğrulaması**

Run: `grep -o 'name="ToUpperCase">[a-z]*' ~/.uki/acilisDegerleri.xml`
Expected: `name="ToUpperCase">true` (iki tık = başlangıç durumuna dönüş).

- [ ] **Step 5: Süreci kapat + geçicileri temizle**

```bash
pkill -f UyapDokumanEditoru
rm -rf /tmp/ltg-e2e /tmp/ltg-e2e.log
```

---

### Task 6: Proje notları (CLAUDE.md)

**Files:**
- Modify: `CLAUDE.md` (worktree kopyası — "macOS dikte düzeltmesi" bölümünün altına)

- [ ] **Step 1: Bölüm ekle** (içerik birebir):

```markdown
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
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): LIVETOGGLE mekanizması ve tuzakları"
```

---

### Task 7: Elle GUI doğrulaması (kullanıcı) + dal kapanışı

- [ ] **Step 1: Kullanıcıdan elle doğrulama iste** (kullanıcı tercihi — sentetik
klavye yok). Senaryo:
  1. Worktree build'ini başlat (Task 5 Step 1 komutu).
  2. Biçim sekmesi → "Otomatik Büyük Harf" kutusunu KAPAT: diyalog ÇIKMAMALI.
  3. Belgeye `veli geldi. ali` yazıp boşluk: büyütme OLMAMALI.
  4. Kutuyu tekrar AÇ, aynı şeyi yaz: `Ali` OLMALI (restart'sız).
  5. (İsteğe bağlı) "Kelime Denetimi" kapat/aç: kırmızı çizgiler durmalı/dönmeli
     (kapatınca eski işaretler kalabilir — bilinen sınır).

- [ ] **Step 2: Onay sonrası** superpowers:finishing-a-development-branch
becerisini çağır (merge/PR kararı kullanıcıya sunulur). Merge'de
`feature/autocorrect` (paralel "yazarken oto-düzeltme" işi) ile `build.sh`
çakışması olursa: iki `apply_*` fonksiyonu ve iki `patch_jar` çağrısı yan yana
korunur (içerik bağımsız).
