# Harici Stilli Yapıştırma (PASTERICH) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Word/tarayıcı/PDF gibi harici uygulamalardan kopyalanan stilli metin (kalın/italik/renk/font/hizalama/liste/tablo) UDE editörüne yapıştırıldığında biçimiyle korunsun.

**Architecture:** Panodaki HTML → udf-cli (paketli self-contained bun ikilisi) ile UDE'nin kendi `.udf` formatına çevrilir → UDE'nin mevcut UDF okuyucusuna (`WPDocumentPanel.a(InputStream)`) beslenir → `select-all`/`copy`/`paste` ile caret'e eklenir. Bu, UYAP-web-işareti yapıştırma yolunun birebir aynısıdır; UDE'nin tüm liste/tablo/paragraf makinesi yeniden kullanılır.

**Tech Stack:** Java 11 (Javassist build-zamanı yama + çalışma-anı yardımcı sınıf), `bun build --compile` (udf-cli → arm64 ikili), bash (build.sh), jpackage.

---

## Dosya Yapısı

- Create: `scripts/macos-pasterich/PasteRichPatch.java` — Javassist patcher; `hj.a(Transferable)`'a harici-HTML dalı enjekte eder.
- Create: `scripts/macos-pasterich/macospasterich/RichPaste.java` — çalışma-anı: pano HTML → udf-cli alt süreç → `.udf` bayt dizisi; ikili yolu çözümleme; hata→null.
- Create: `scripts/macos-pasterich/macospasterich/PrLog.java` — teşhis günlüğü (`~/Library/Logs/ude-pasterich.txt`), `TrLog` deseni.
- Create: `scripts/udfcli/html2udf-entry.ts` — minimal bun giriş noktası: stdin HTML → htmlToUdf → stdout `.udf` (auth/sign import ETMEZ → native-dep yok).
- Create: `scripts/udfcli/build-udfcli.sh` — udf-cli kaynağından `bun build --compile` ile arm64 ikili üretir.
- Create: `tests/RichPasteUdfTest.java` — paketli ikiliyi örnek HTML ile çalıştırıp geçerli `.udf` (PK zip + `content.xml`) döndüğünü doğrular.
- Create: `scripts/macos-pasterich/spike/FeedProbe.java` — Phase 0 dynamic-attach probe (canlı UDE'de `.udf` besleme doğrulaması).
- Modify: `scripts/build.sh` — `PASTERICH` bayrağı + `apply_pasterich()` + `patch_jar` çağrısı + java-options (gerekirse).

## Obfuscate imza referansı (javap ile doğrulandı, `editor-app.jar`)

- `hj.a(java.awt.datatransfer.Transferable) : boolean` — yamalanacak metot ("paste from web").
- `hj.paste() : void`.
- `WPDocumentPanel.a() : fi` (fi = `tr.com.havelsan.uyap.system.editor.common.text.fi`).
- `WPDocumentPanel.a(java.io.InputStream) : int` — UDF okuyucu.
- `fi.setCaret(javax.swing.text.Caret) : void`.
- `fi.a(java.lang.String, java.lang.Object) : boolean` — komut ("select-all").
- `fi.copy() : void`.
- `java.awt.datatransfer.DataFlavor.allHtmlFlavor` — pano HTML flavor'ı.
- UDE-içi/UYAP-web işareti: HTML içinde `uyap-web-editor-data` alt dizesi.

Tam sınıf adları:
- `tr.com.havelsan.uyap.system.editor.common.text.hj`
- `tr.com.havelsan.uyap.system.editor.common.gui.WPDocumentPanel`
- `tr.com.havelsan.uyap.system.editor.common.text.fi`

---

## Task 1: Phase 0 — besleme doğrulama spike (DOĞRULAMA KAPISI)

Amaç: udf-cli çıktısı `.udf`'nin `WPDocumentPanel.a(InputStream)` tarafından kabul edilip metin/element içeren DocumentEx ürettiğini CANLI UDE'de kanıtla. Geçmezse mimari yeniden değerlendirilir (kod yazmadan önce). Statik kanıt güçlü (`u` UDF zip okuyor; udf-cli ve gerçek UDE `.udf`'leri yapısal olarak özdeş — yalnız `content.xml`).

**Files:**
- Create: `scripts/macos-pasterich/spike/FeedProbe.java`

- [ ] **Step 1: Probe agent sınıfını yaz**

`FeedProbe.java` — dynamic-attach agentmain; verilen `.udf` yolunu okuyup EDT'de bir `WPDocumentPanel` oluşturur, `a(InputStream)` ile besler, dönen belgenin uzunluğunu/element sayısını loglar. Reflection kullan (agent jar app classpath'siz). CLAUDE.md "dynamic attach" deseni.

```java
package spike;
import java.io.*; import java.lang.instrument.Instrumentation; import java.lang.reflect.*;
import java.nio.file.*; import javax.swing.*;
public class FeedProbe {
  public static void agentmain(String args, Instrumentation inst) {
    final String udf = args; // .udf mutlak yolu
    SwingUtilities.invokeLater(new Runnable(){ public void run(){
      StringBuilder log = new StringBuilder();
      try {
        byte[] bytes = Files.readAllBytes(Paths.get(udf));
        Class<?> wpc = Class.forName("tr.com.havelsan.uyap.system.editor.common.gui.WPDocumentPanel");
        Object wp = wpc.getDeclaredConstructor().newInstance();
        Method getFi = wpc.getMethod("a"); // a():fi
        Object fi = getFi.invoke(wp);
        Method aStream = wpc.getMethod("a", java.io.InputStream.class);
        Object r = aStream.invoke(wp, new ByteArrayInputStream(bytes));
        Method getDoc = fi.getClass().getMethod("getDocument");
        Object doc = getDoc.invoke(fi);
        Method getLen = doc.getClass().getMethod("getLength");
        log.append("OK a(InputStream)=").append(r)
           .append(" docLength=").append(getLen.invoke(doc));
      } catch (Throwable t) {
        StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
        log.append("FAIL ").append(sw);
      }
      try { Files.write(Paths.get(System.getProperty("user.home"),"Library","Logs","ude-feedprobe.txt"),
            (log+"\n").getBytes("UTF-8")); } catch (Throwable ignore) {}
    }});
  }
}
```

- [ ] **Step 2: Probe jar'ını derle/paketle (MANIFEST Agent-Class ile)**

Run:
```bash
JR=$(/bin/ls -d /Users/saidsurucu/Documents/GitHub/ude-mac-arm/build/_jdk*/*/Contents/Home 2>/dev/null | head -1); \
JC="$JR/bin/javac"; JARC="$JR/bin/jar"; \
cd /tmp && rm -rf feedprobe && mkdir -p feedprobe && \
"$JC" --release 11 -d feedprobe /Users/saidsurucu/Documents/GitHub/ude-mac-arm/scripts/macos-pasterich/spike/FeedProbe.java && \
printf 'Agent-Class: spike.FeedProbe\nCan-Redefine-Classes: true\n' > /tmp/feedprobe/MANIFEST.MF && \
"$JARC" cfm /tmp/feedprobe.jar /tmp/feedprobe/MANIFEST.MF -C /tmp/feedprobe spike
```
Expected: `/tmp/feedprobe.jar` oluşur, hata yok. (JDK yolu yoksa `bash scripts/build.sh download && … patch` ile build önce çalıştırılır.)

- [ ] **Step 3: UDE'yi çalıştır, içinden boş bir belge aç**

Run: `pkill -f UyapDokumanEditoru; "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru" &`
Beklenen: UDE penceresi açılır (doğrudan binary; `open` KULLANMA).

- [ ] **Step 4: Probe'u canlı sürece dinamik attach et**

`tests/AttachProbe.java` benzeri tek-seferlik attach çağrısı yaz/çalıştır (bundled JDK `jdk.attach`):
```bash
JR=$(/bin/ls -d build/_jdk*/*/Contents/Home | head -1); \
PID=$(pgrep -f UyapDokumanEditoru | head -1); \
cat > /tmp/Attach.java <<'EOF'
import com.sun.tools.attach.VirtualMachine;
public class Attach { public static void main(String[] a) throws Exception {
  VirtualMachine vm = VirtualMachine.attach(a[0]);
  vm.loadAgent(a[1], a[2]); vm.detach(); System.out.println("attached"); } }
EOF
"$JR/bin/javac" -d /tmp /tmp/Attach.java && \
"$JR/bin/java" -cp /tmp:"$JR/lib/tools.jar" Attach "$PID" /tmp/feedprobe.jar \
  /Users/saidsurucu/Documents/GitHub/udf-cli/test-output/02-tablo.udf
```
Expected: `attached` yazar. (Java 11'de `tools.jar` yok; `jdk.attach` modülü built-in → `-cp /tmp` yeterli, gerekirse `tools.jar` parçasını çıkar.)

- [ ] **Step 5: Sonucu oku — DOĞRULAMA KAPISI**

Run: `cat ~/Library/Logs/ude-feedprobe.txt`
Expected: `OK a(InputStream)=… docLength=N` (N > 0). ✅ → mimari onaylı, Task 2'ye geç. ❌ `FAIL …` → besleme yolu çalışmıyor; STOP, kök nedeni incele (imzasız UDF reddi? content.xml format farkı?), tasarımı revize et.

- [ ] **Step 6: Commit (spike kodu)**

```bash
git add scripts/macos-pasterich/spike/FeedProbe.java
git commit -m "spike(pasterich): WPDocumentPanel.a(InputStream) UDF besleme doğrulama probe'u"
```

---

## Task 2: udf-cli'yi self-contained arm64 ikiliye derle

**Files:**
- Create: `scripts/udfcli/html2udf-entry.ts`
- Create: `scripts/udfcli/build-udfcli.sh`

- [ ] **Step 1: Minimal bun giriş noktasını yaz**

`scripts/udfcli/html2udf-entry.ts` — yalnız html2udf yolunu import eder (auth/sign YOK → pkcs11 native-dep'i bundle'a girmez):

```ts
import { htmlToUdf } from '../src/converters/html-to-udf.js';

async function readStdin(): Promise<string> {
  const chunks: Uint8Array[] = [];
  for await (const c of process.stdin) chunks.push(c as Uint8Array);
  return Buffer.concat(chunks).toString('utf-8');
}

const html = await readStdin();
const udf = await htmlToUdf(html);   // Buffer (.udf zip)
process.stdout.write(udf);
```

Not: dosya, build sırasında udf-cli reposunun KÖKÜNE kopyalanıp `./src/...`'i çözecek (aşağıdaki script konumlandırır).

- [ ] **Step 2: build-udfcli.sh yaz**

`scripts/udfcli/build-udfcli.sh` — udf-cli kaynağını bulur/klonlar, bağımlılık kurar, ikiliyi derler:

```bash
#!/usr/bin/env bash
set -euo pipefail
OUT="${1:?çıktı ikili yolu}"          # ör. .../Contents/Resources/udf-cli
SRC="${UDFCLI_SRC:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUN="$(command -v bun || echo "$HOME/.bun/bin/bun")"
[ -x "$BUN" ] || { echo "[udfcli] bun yok; PASTERICH atlanacak" >&2; exit 3; }

if [ -z "$SRC" ]; then
  SRC="/tmp/udf-cli-src"
  rm -rf "$SRC"
  git clone --depth 1 https://github.com/saidsurucu/udf-cli.git "$SRC" \
    || { echo "[udfcli] klon başarısız" >&2; exit 3; }
fi
cp "$HERE/html2udf-entry.ts" "$SRC/html2udf-entry.ts"
( cd "$SRC" && "$BUN" install --frozen-lockfile >/dev/null 2>&1 || "$BUN" install >/dev/null 2>&1 )
( cd "$SRC" && "$BUN" build --compile --target=bun-darwin-arm64 \
    ./html2udf-entry.ts --outfile "$OUT" )
chmod +x "$OUT"
echo "[udfcli] ikili üretildi: $OUT"
```

- [ ] **Step 3: İkiliyi yerel üret ve test et**

Run:
```bash
chmod +x scripts/udfcli/build-udfcli.sh
UDFCLI_SRC=/Users/saidsurucu/Documents/GitHub/udf-cli \
  bash scripts/udfcli/build-udfcli.sh /tmp/udf-cli-bin
echo '<p><b>Merhaba</b> <i>dünya</i></p>' | /tmp/udf-cli-bin > /tmp/out.udf
unzip -l /tmp/out.udf
```
Expected: `/tmp/udf-cli-bin` oluşur; `/tmp/out.udf` içinde `content.xml` listelenir.

- [ ] **Step 4: content.xml'in beklenen şemayı içerdiğini doğrula**

Run: `unzip -p /tmp/out.udf content.xml | grep -o 'bold="true"\|italic="true"\|<elements'`
Expected: `<elements`, `bold="true"`, `italic="true"` görünür (biçim korunuyor).

- [ ] **Step 5: Commit**

```bash
git add scripts/udfcli/html2udf-entry.ts scripts/udfcli/build-udfcli.sh
git commit -m "feat(pasterich): udf-cli html2udf self-contained ikili derleyici (bun compile)"
```

---

## Task 3: RichPaste çalışma-anı sınıfı + PrLog + birim test

**Files:**
- Create: `scripts/macos-pasterich/macospasterich/PrLog.java`
- Create: `scripts/macos-pasterich/macospasterich/RichPaste.java`
- Create: `tests/RichPasteUdfTest.java`

- [ ] **Step 1: PrLog yaz (TrLog deseni)**

```java
package macospasterich;
import java.io.*; import java.nio.file.*; import java.time.*;
final class PrLog {
  private static final boolean ON = "1".equals(System.getenv("UDE_PASTERICHLOG"));
  private static Path file() {
    return Paths.get(System.getProperty("user.home"),"Library","Logs","ude-textreplace.txt")
      .resolveSibling("ude-pasterich.txt");
  }
  static void log(String msg) {
    if (!ON) return;
    try { Files.write(file(), (LocalDateTime.now()+" "+msg+"\n").getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (Throwable ignore) {}
  }
  static void log(String ctx, Throwable t) {
    if (!ON) return;
    StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
    log(ctx+": "+sw);
  }
  private PrLog() {}
}
```

- [ ] **Step 2: RichPaste yaz (pano HTML → udf-cli alt süreç → bayt)**

```java
package macospasterich;
import java.io.*; import java.net.URL; import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public final class RichPaste {
  /** Pano HTML'ini .udf baytına çevirir; başarısızlıkta null (düz-metin fallback). */
  public static byte[] fromClipboardHtml(String html) {
    if (html == null || html.isEmpty()) return null;
    String bin = resolveBinary();
    if (bin == null) { PrLog.log("ikili bulunamadı"); return null; }
    try {
      ProcessBuilder pb = new ProcessBuilder(bin);
      pb.redirectErrorStream(false);
      Process p = pb.start();
      try (OutputStream os = p.getOutputStream()) {
        os.write(html.getBytes("UTF-8"));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream is = p.getInputStream()) {
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) >= 0) out.write(buf, 0, n);
      }
      boolean done = p.waitFor(5, TimeUnit.SECONDS);
      if (!done) { p.destroyForcibly(); PrLog.log("timeout"); return null; }
      if (p.exitValue() != 0) { PrLog.log("exit="+p.exitValue()); return null; }
      byte[] udf = out.toByteArray();
      if (udf.length < 4 || udf[0] != 'P' || udf[1] != 'K') { PrLog.log("PK değil"); return null; }
      PrLog.log("ok "+udf.length+" bayt");
      return udf;
    } catch (Throwable t) { PrLog.log("fromClipboardHtml", t); return null; }
  }

  /** Önce UDE_UDFCLI env; sonra editor-app.jar konumuna göre ../../Resources/udf-cli. */
  static String resolveBinary() {
    String env = System.getenv("UDE_UDFCLI");
    if (env != null && new File(env).canExecute()) return env;
    try {
      URL loc = RichPaste.class.getProtectionDomain().getCodeSource().getLocation();
      File jar = new File(loc.toURI());          // .../Contents/app/editor-app.jar
      File res = new File(jar.getParentFile().getParentFile(), "Resources/udf-cli");
      if (res.canExecute()) return res.getAbsolutePath();
    } catch (Throwable t) { PrLog.log("resolveBinary", t); }
    return null;
  }
  private RichPaste() {}
}
```

- [ ] **Step 3: Birim testi yaz (paketli ikiliye karşı)**

`tests/RichPasteUdfTest.java` — `UDE_UDFCLI` env ile ikiliyi gösterir, örnek Word-benzeri HTML verir, dönen baytın PK zip + `content.xml` olduğunu doğrular.

```java
import java.io.*; import java.util.zip.*;
public class RichPasteUdfTest {
  public static void main(String[] a) throws Exception {
    String html = "<p style='text-align:center'><b>Başlık</b></p>"
      + "<p>Normal <i>italik</i> ve <u>altı çizili</u>.</p>"
      + "<table><tr><td>A</td><td>B</td></tr></table>";
    byte[] udf = macospasterich.RichPaste.fromClipboardHtml(html);
    if (udf == null) throw new AssertionError("null döndü (UDE_UDFCLI ayarlı mı?)");
    if (udf[0] != 'P' || udf[1] != 'K') throw new AssertionError("PK zip değil");
    boolean hasContent = false;
    try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(udf))) {
      for (ZipEntry e; (e = z.getNextEntry()) != null; )
        if ("content.xml".equals(e.getName())) hasContent = true;
    }
    if (!hasContent) throw new AssertionError("content.xml yok");
    System.out.println("PASS RichPasteUdfTest ("+udf.length+" bayt)");
  }
}
```

- [ ] **Step 4: Testi derle ve çalıştır (önce FAIL doğrula — ikili yokken)**

Run:
```bash
JR=$(/bin/ls -d build/_jdk*/*/Contents/Home | head -1)
"$JR/bin/javac" --release 11 -d /tmp/prtest \
  scripts/macos-pasterich/macospasterich/PrLog.java \
  scripts/macos-pasterich/macospasterich/RichPaste.java \
  tests/RichPasteUdfTest.java
# ikili YOKKEN: null -> AssertionError (beklenen başarısızlık)
"$JR/bin/java" -cp /tmp/prtest RichPasteUdfTest || echo "beklenen FAIL (ikili yok)"
```
Expected: `null döndü` AssertionError (ikili henüz gösterilmedi).

- [ ] **Step 5: İkiliyle PASS doğrula**

Run:
```bash
UDE_UDFCLI=/tmp/udf-cli-bin "$JR/bin/java" -cp /tmp/prtest RichPasteUdfTest
```
Expected: `PASS RichPasteUdfTest (NNNN bayt)`.

- [ ] **Step 6: Commit**

```bash
git add scripts/macos-pasterich/macospasterich/PrLog.java \
        scripts/macos-pasterich/macospasterich/RichPaste.java \
        tests/RichPasteUdfTest.java
git commit -m "feat(pasterich): RichPaste pano-HTML→udf-cli alt süreç köprüsü + PrLog + test"
```

---

## Task 4: PasteRichPatch — hj.a(Transferable) Javassist kancası

**Files:**
- Create: `scripts/macos-pasterich/PasteRichPatch.java`

- [ ] **Step 1: Patcher'ı yaz**

`hj.a(Transferable)` metodunun BAŞINA insertBefore ile harici-HTML dalı ekler: işaret yok + HTML var → `RichPaste.fromClipboardHtml` → başarıda UYAP-web yolunun aynısı (`new WPDocumentPanel` → setCaret → `a(stream)` → `select-all` → `copy` → `this.paste()`) → `return true`. Tüm UDE çağrıları gerçek (obfuscate) imzalarla; patcher build-zamanı jar classpath'ine karşı çözer.

```java
import javassist.*;
import java.io.*;

public class PasteRichPatch {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) { System.err.println("Kullanım: PasteRichPatch <jar> <out-dir>"); System.exit(2); }
    String jar = args[0]; File outDir = new File(args[1]);
    ClassPool pool = ClassPool.getDefault();
    pool.insertClassPath(jar);

    CtClass hj = pool.get("tr.com.havelsan.uyap.system.editor.common.text.hj");
    CtMethod a = hj.getDeclaredMethod("a", new CtClass[]{ pool.get("java.awt.datatransfer.Transferable") });

    String src =
      "{"
    + "  try {"
    + "    java.awt.datatransfer.Transferable __t = $1;"
    + "    if (__t != null && __t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.allHtmlFlavor)) {"
    + "      Object __o = __t.getTransferData(java.awt.datatransfer.DataFlavor.allHtmlFlavor);"
    + "      if (__o instanceof java.lang.String) {"
    + "        java.lang.String __h = (java.lang.String) __o;"
    + "        if (__h.indexOf(\"uyap-web-editor-data\") < 0) {"
    + "          byte[] __u = macospasterich.RichPaste.fromClipboardHtml(__h);"
    + "          if (__u != null) {"
    + "            tr.com.havelsan.uyap.system.editor.common.gui.WPDocumentPanel __p ="
    + "              new tr.com.havelsan.uyap.system.editor.common.gui.WPDocumentPanel();"
    + "            __p.a().setCaret(new javax.swing.text.DefaultCaret());"
    + "            __p.a(new java.io.ByteArrayInputStream(__u));"
    + "            __p.a().a(\"select-all\", null);"
    + "            __p.a().copy();"
    + "            this.paste();"
    + "            return true;"
    + "          }"
    + "        }"
    + "      }"
    + "    }"
    + "  } catch (java.lang.Throwable __e) { macospasterich.RichPaste.logExternal(__e); }"
    + "}";
    a.insertBefore(src);

    byte[] code = hj.toBytecode();
    File f = new File(outDir, hj.getName().replace('.', '/') + ".class");
    f.getParentFile().mkdirs();
    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(code); }
    System.out.println("[PasteRichPatch] hj.a(Transferable) harici-HTML dalı enjekte edildi.");
  }
}
```

Not (Javassist `//` yorum YASAK kuralı — string'lerde yok). `return true` insertBefore içinde metottan döner.

- [ ] **Step 2: RichPaste'e logExternal ekle**

`RichPaste.java`'ya ekle (patcher'ın çağırdığı public sarmalayıcı):
```java
  public static void logExternal(Throwable t) { PrLog.log("inject", t); }
```
Run: yeniden derle (Task 3 Step 4 javac komutu) — derleme hatası olmamalı.

- [ ] **Step 3: Patcher'ı derle (javassist classpath ile)**

Run:
```bash
JR=$(/bin/ls -d build/_jdk*/*/Contents/Home | head -1)
JVS=$(/bin/ls scripts/lib/javassist*.jar | head -1)
"$JR/bin/javac" --release 11 -cp "$JVS" -d /tmp/prpatch scripts/macos-pasterich/PasteRichPatch.java
```
Expected: hata yok (javassist jar yolu `scripts/lib/` altında; yoksa build.sh'nin kullandığı yolu kullan).

- [ ] **Step 4: Patcher'ı taze jar'a uygula (akış testi)**

Run:
```bash
cp build/_input/editor-app.jar /tmp/test.jar
# RichPaste sınıflarını jar'a koy (patcher çözebilsin)
( cd /tmp/prtest && "$JR/bin/jar" uf /tmp/test.jar macospasterich )
"$JR/bin/java" -cp "/tmp/prpatch:$JVS" PasteRichPatch /tmp/test.jar /tmp/prpatch/out
```
Expected: `[PasteRichPatch] hj.a(Transferable) harici-HTML dalı enjekte edildi.` (CannotCompileException YOK → obfuscate imzalar doğru çözüldü).

- [ ] **Step 5: Commit**

```bash
git add scripts/macos-pasterich/PasteRichPatch.java scripts/macos-pasterich/macospasterich/RichPaste.java
git commit -m "feat(pasterich): hj.a(Transferable) harici-HTML Javassist kancası"
```

---

## Task 5: build.sh entegrasyonu

**Files:**
- Modify: `scripts/build.sh` (bayrak bildirimi bölgesi; `apply_*` fonksiyonları; `patch_jar` çağrı listesi; tek-adım dispatch case)

- [ ] **Step 1: PASTERICH bayrağı + kaynak yolu bildir**

`build.sh`'de diğer bayrakların (PASTEIMG) yanına ekle:
```bash
PASTERICH_SRC="$SCRIPT_DIR/macos-pasterich" # harici stilli yapıştırma yaması
UDFCLI_DIR="$SCRIPT_DIR/udfcli"             # udf-cli ikili derleyici
PASTERICH="${PASTERICH:-1}"                 # 1=açık (harici stilli yapıştırma) | 0=kapalı
```

- [ ] **Step 2: apply_pasterich fonksiyonunu yaz**

`apply_pasteimage` yanına ekle. (a) ikiliyi `Resources/`e üret; (b) `macospasterich` sınıflarını jar'a enjekte; (c) `PasteRichPatch` çalıştır. İkili/bun yoksa UYARI + atla (build düşmez). `$APP_DIR` = `.../Uyap Doküman Editörü.app` (package adımında mevcut konvansiyon).

```bash
apply_pasterich() {  # $1=JAR
	local JAR="$1"
	[ "$PASTERICH" = "1" ] || return 0
	if unzip -l "$JAR" 2>/dev/null | grep 'macospasterich/RichPaste.class' >/dev/null 2>&1; then
		c_ok "[pasterich] zaten yamalı, atlandı."; return 0
	fi
	c_info "[pasterich] harici stilli yapıştırma yaması…"
	local jr jc jvs
	jr="$(java17)"  || { c_warn "[pasterich] 17+ java yok, atlandı."; return 0; }
	jc="$(javac17)" || { c_warn "[pasterich] 17+ javac yok, atlandı."; return 0; }
	jvs="$JVASSIST" # build.sh'nin javassist jar değişkeni

	rm -rf "$BUILD/_prhelper"; mkdir -p "$BUILD/_prhelper"
	"$jc" --release 11 -encoding UTF-8 -d "$BUILD/_prhelper" \
		"$PASTERICH_SRC/macospasterich/PrLog.java" \
		"$PASTERICH_SRC/macospasterich/RichPaste.java" \
		|| { c_warn "[pasterich] RichPaste derlenemedi; atlandı."; return 0; }
	( cd "$BUILD/_prhelper" && zip -q -r "$JAR" macospasterich )

	rm -rf "$BUILD/_prpatch"; mkdir -p "$BUILD/_prpatch/out"
	"$jc" --release 11 -encoding UTF-8 -cp "$jvs" -d "$BUILD/_prpatch" \
		"$PASTERICH_SRC/PasteRichPatch.java" \
		|| { c_warn "[pasterich] PasteRichPatch derlenemedi; atlandı."; return 0; }
	if ! "$jr" -cp "$BUILD/_prpatch:$jvs" PasteRichPatch "$JAR" "$BUILD/_prpatch/out"; then
		zip -q -d "$JAR" 'macospasterich/*' >/dev/null 2>&1 || true
		c_warn "[pasterich] kanca uygulanamadı (UDE sürümü değişmiş olabilir); geri alındı."
		return 0
	fi
	( cd "$BUILD/_prpatch/out" && zip -q -r "$JAR" tr )
	c_ok "[pasterich] yapıştırma yaması uygulandı."
}
```

- [ ] **Step 3: İkili üretimini package adımına bağla**

`package` fonksiyonunda, `.app` oluşturulduktan SONRA (Resources mevcutken) ikiliyi üret:
```bash
	if [ "$PASTERICH" = "1" ]; then
		mkdir -p "$APP_DIR/Contents/Resources"
		bash "$UDFCLI_DIR/build-udfcli.sh" "$APP_DIR/Contents/Resources/udf-cli" \
			|| c_warn "[pasterich] udf-cli ikilisi üretilemedi; harici yapıştırma çalışmayacak."
	fi
```
(`$APP_DIR` değişken adı build.sh'deki gerçek adla eşleştirilir — package fonksiyonundaki mevcut app dizini değişkenini kullan.)

- [ ] **Step 4: patch_jar içine apply_pasterich çağrısı ekle**

`apply_pasteimage "$JAR"` satırının yanına:
```bash
	apply_pasterich "$JAR"
```

- [ ] **Step 5: Tek-adım dispatch + USAGE ekle**

`paste-image)` case'inin yanına:
```bash
	paste-rich) apply_pasterich "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ;;
```
Ve USAGE metnine `PASTERICH (1=açık varsayılan | 0=kapalı; harici stilli yapıştırma)` satırı.

- [ ] **Step 6: Tam build çalıştır ve yama loglarını doğrula**

Run:
```bash
bash scripts/build.sh download && bash scripts/build.sh patch && \
bash scripts/build.sh lookagent && bash scripts/build.sh package 2>&1 | \
  grep -iE "pasterich|udf-cli|udfcli"
```
Expected: `[pasterich] yapıştırma yaması uygulandı.` ve `[udfcli] ikili üretildi: …/Resources/udf-cli`.

- [ ] **Step 7: İkilinin .app içinde olduğunu doğrula**

Run: `ls -la "build/Uyap Doküman Editörü.app/Contents/Resources/udf-cli" && file "build/Uyap Doküman Editörü.app/Contents/Resources/udf-cli"`
Expected: çalıştırılabilir Mach-O arm64 ikili.

- [ ] **Step 8: Commit**

```bash
git add scripts/build.sh
git commit -m "feat(pasterich): build.sh PASTERICH bayrağı + apply_pasterich + udf-cli paketleme"
```

---

## Task 6: Uçtan uca doğrulama + dokümantasyon

**Files:**
- Modify: `CLAUDE.md` (PASTERICH bölümü)
- Modify: `MEMORY.md` + yeni memory dosyası

- [ ] **Step 1: Uygulamayı çalıştır, harici stilli içeriği yapıştır (ELLE — kullanıcı)**

Run: `pkill -f UyapDokumanEditoru; "build/Uyap Doküman Editörü.app/Contents/MacOS/UyapDokumanEditoru" &`
Kullanıcı doğrulaması: Word veya tarayıcıdan **kalın/italik/renkli metin + bir tablo + bir liste** seç → kopyala → UDE'ye Cmd+V. Beklenen: biçim (kalın/italik/renk/font/hizalama/liste/tablo) korunur. (Proje tercihi: GUI son doğrulaması kullanıcıya bırakılır.)

- [ ] **Step 2: Teşhis logunu kontrol et (gerekirse)**

Sorun olursa: `UDE_PASTERICHLOG=1` ile başlat, `cat ~/Library/Logs/ude-pasterich.txt` → `ok N bayt` / hata satırı. Besleme şüphesinde Task 1 probe'unu tekrar çalıştır.

- [ ] **Step 3: CLAUDE.md'ye bölüm ekle**

`## macOS Metin Değiştirme` bölümünden sonra:
```markdown
## Harici Stilli Yapıştırma (PASTERICH=1, 2026-06)

Word/tarayıcı/PDF'den kopyalanan stilli metin UDE'ye biçimiyle yapışır. KÖK
NEDEN: hj.paste() stilleri yalnız EditorDataFlavor (UDE-içi) ve uyap-web-editor-data
HTML işaretiyle (UYAP web) korur; harici HTML düz metne düşer. ÇÖZÜM: hj.a(Transferable)
kancası — işaret yok + allHtmlFlavor varsa pano HTML'i macospasterich/RichPaste ile
PAKETLİ udf-cli ikilisine (Contents/Resources/udf-cli; bun --compile html2udf, saf-JS
native-dep'siz) alt süreçle çevrilir → .udf (imzasız zip, gerçek UDE taslağıyla
yapısal özdeş) → WPDocumentPanel.a(InputStream) UDF okuyucu → select-all/copy/paste
(UYAP-web yolunun aynısı, UDE'nin liste/tablo/paragraf makinesi yeniden kullanılır).
İkili yoksa/başarısızsa null → düz-metin fallback (çökme yok). content.xml 1.8 şeması:
<content> CDATA global metin + <elements> offset'li run/paragraf/tablo. Teşhis:
UDE_PASTERICHLOG=1 → ~/Library/Logs/ude-pasterich.txt. Besleme spike: FeedProbe
dynamic-attach (.udf → WPDocumentPanel.a). Sınırlama: resim (ayrı clipboard item)
taşınmaz. Test: tests/RichPasteUdfTest.java (UDE_UDFCLI env ile javac+java).
```

- [ ] **Step 4: Memory ekle**

`memory/macos-rich-paste.md` oluştur (feedback/project tipi değil — reference/project): kök neden + udf-cli yeniden kullanımı + besleme yolu + tuzaklar. `MEMORY.md`'ye tek satır pointer ekle: `- [Harici stilli yapıştırma](macos-rich-paste.md) — PASTERICH=1; pano HTML→udf-cli ikili→.udf→WPDocumentPanel.a; imzasız UDF; [[macos-text-replacement]]`.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(pasterich): CLAUDE.md harici stilli yapıştırma bölümü"
```

---

## Self-Review notları

- **Spec kapsamı:** Tüm spec gereksinimleri (bağlanma noktası, akış, udf-cli yeniden kullanımı, Phase 0 spike, build entegrasyonu, fallback, test) Task 1-6'da karşılanıyor.
- **Tip tutarlılığı:** `RichPaste.fromClipboardHtml(String):byte[]`, `RichPaste.logExternal(Throwable)`, `RichPaste.resolveBinary():String`, `PrLog.log(...)` her görevde aynı imzayla kullanılıyor.
- **Placeholder yok:** Tüm kod blokları somut. `$APP_DIR`/`$JVASSIST`/`java17` build.sh'nin GERÇEK değişken adlarıyla eşleştirilecek (Task 5'te mevcut fonksiyonlara bakılarak) — bu plan-içi tek "yerel uyarlama" notu.
- **Kritik risk:** Task 1 doğrulama kapısı; geçmezse sonraki görevler durur.
```
