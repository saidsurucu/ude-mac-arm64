# macOS Metin Değiştirme (Text Replacement) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** macOS sistem geneli Metin Değiştirme kısayolları (ör. "mrb" → "Merhaba!") UDE'nin tüm metin alanlarında çalışsın.

**Architecture:** `macos-textkeys` javaagent ailesine üç yeni sınıf: `TextReplace` (KEY_TYPED tetikleyici + eşleştirme + belge değiştirme), `ReplacementStore` (TextReplacements.db SQLite kopyasından, yedek olarak `defaults export`'tan liste okuma; pencere aktifleşince 30 sn kısıtlamayla arka planda yenileme), `TrLog` (UDE_TRLOG=1 dosya logu). Vendor jar'a yama yok; build bayrağı `TEXTREPLACE=1` (0 → jpackage `-Dmacostextreplace.off=1`).

**Tech Stack:** Java 11 (javaagent, `--release 11`), `sqlite3` CLI, javax.xml plist ayrıştırma, elle çalıştırılan `tests/` birim testleri (javac+java deseni).

**Spec:** `docs/superpowers/specs/2026-06-12-text-replacement-design.md`

---

### Task 1: TrLog + TextReplace eşleştirme çekirdeği (saf mantık, TDD)

**Files:**
- Create: `scripts/macos-textkeys/macostextkeys/TrLog.java`
- Create: `scripts/macos-textkeys/macostextkeys/TextReplace.java` (bu task'ta yalnız statik eşleştirme metotları + `maybeReplace`)
- Test: `tests/TextReplaceMatchTest.java`

- [ ] **Step 1: Başarısız testi yaz** — `tests/TextReplaceMatchTest.java`:

```java
package macostextkeys;

import java.util.HashMap;
import java.util.Map;

import javax.swing.JTextArea;

/*
 * TextReplace eşleştirme çekirdeği testi (GUI'siz; JTextArea headless çalışır).
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/trm-test \
 *     scripts/macos-textkeys/macostextkeys/TrLog.java \
 *     scripts/macos-textkeys/macostextkeys/TextReplace.java \
 *     tests/TextReplaceMatchTest.java
 *   java -Djava.awt.headless=true -cp /tmp/trm-test macostextkeys.TextReplaceMatchTest
 */
public final class TextReplaceMatchTest {

    static int fails = 0;

    public static void main(String[] args) {
        Map<String, String> t = new HashMap<String, String>();
        t.put("mrb", "Merhaba!");
        t.put("Yldym", "Yoldayım!");
        t.put("işadr", "Şişli/İstanbul adresi");
        t.put("adr", "Satır 1\nSatır 2");

        // --- tokenStart / isBoundary ---
        eq("tokenStart düz", 6, TextReplace.tokenStart("selam mrb", 9));
        eq("tokenStart belge başı", 0, TextReplace.tokenStart("mrb", 3));
        eq("tokenStart paren", 1, TextReplace.tokenStart("(mrb", 4));
        ok("isBoundary boşluk", TextReplace.isBoundary(' '));
        ok("isBoundary nokta", TextReplace.isBoundary('.'));
        ok("isBoundary değil harf", !TextReplace.isBoundary('ş'));

        // --- lookup ---
        eq("lookup birebir", "Merhaba!", TextReplace.lookup(t, "mrb"));
        eq("lookup ilk-harf-büyük uyarlama", "Merhaba!", TextReplace.lookup(t, "Mrb"));
        eq("lookup tr İ uyarlama", "Şişli/İstanbul adresi", TextReplace.lookup(t, "İşadr"));
        eq("lookup büyük kısayol birebir", "Yoldayım!", TextReplace.lookup(t, "Yldym"));
        eq("lookup büyük kısayol küçüğü eşleşmez", null, TextReplace.lookup(t, "yldym"));
        eq("lookup yok", null, TextReplace.lookup(t, "xmrb"));

        // --- maybeReplace (belge üzerinde) ---
        eq("replace boşlukla", "Merhaba! ", replaced("mrb ", 4, t));
        eq("replace noktayla", "selam Merhaba!.", replaced("selam mrb.", 10, t));
        eq("replace ilk-harf-büyük", "Merhaba! ", replaced("Mrb ", 4, t));
        eq("replace çok satırlı", "Satır 1\nSatır 2 ", replaced("adr ", 4, t));
        eq("replace eşleşmeyen dokunmaz", "xmrb ", replaced("xmrb ", 5, t));
        eq("replace tetiksiz dokunmaz", "mrb", replaced("mrb", 3, t));
        eq("replace ortada", "Merhaba! sonu", replaced("mrb sonu", 4, t));

        // Caret konumu: değiştirilen metin + sonlandırıcının arkası.
        JTextArea ta = new JTextArea("mrb ");
        ta.setCaretPosition(4);
        TextReplace.maybeReplace(ta, t, 16);
        eq("caret sonlandırıcı arkasında", 9, ta.getCaretPosition());

        // Pencere sınırı koruması: sözcük maxLen penceresinden uzunsa eşleştirme yok.
        JTextArea ta2 = new JTextArea("aaaaaaaaaaaaaaaaaaaamrb ");
        ta2.setCaretPosition(24);
        TextReplace.maybeReplace(ta2, t, 3);
        eq("pencere sınırı", "aaaaaaaaaaaaaaaaaaaamrb ", ta2.getText());

        if (fails > 0) { System.out.println("FAIL: " + fails); System.exit(1); }
        System.out.println("OK");
    }

    static String replaced(String text, int caret, Map<String, String> t) {
        JTextArea ta = new JTextArea(text);
        ta.setCaretPosition(caret);
        TextReplace.maybeReplace(ta, t, 16);
        return ta.getText();
    }

    static void eq(String name, Object want, Object got) {
        boolean p = want == null ? got == null : want.equals(got);
        if (!p) { fails++; System.out.println("FAIL " + name + ": beklenen=" + want + " gelen=" + got); }
    }

    static void ok(String name, boolean cond) { if (!cond) { fails++; System.out.println("FAIL " + name); } }
}
```

- [ ] **Step 2: Testin derlenemediğini doğrula** (TextReplace yok):

Run: `javac -encoding UTF-8 -d /tmp/trm-test scripts/macos-textkeys/macostextkeys/TrLog.java scripts/macos-textkeys/macostextkeys/TextReplace.java tests/TextReplaceMatchTest.java`
Expected: FAIL — dosyalar yok.

- [ ] **Step 3: TrLog.java yaz:**

```java
package macostextkeys;

import java.io.File;
import java.io.FileWriter;

/**
 * UDE_TRLOG=1 ile ~/Library/Logs/ude-textreplace.txt'ye yazar
 * (System.err uygulama tarafından yutuluyor — UDE_DICTLOG deseni).
 */
final class TrLog {

    private static final boolean ON = "1".equals(System.getenv("UDE_TRLOG"));

    private TrLog() {}

    static void log(String msg) {
        if (!ON) return;
        try {
            File f = new File(System.getProperty("user.home"),
                    "Library/Logs/ude-textreplace.txt");
            FileWriter w = new FileWriter(f, true);
            try { w.write(System.currentTimeMillis() + " " + msg + "\n"); } finally { w.close(); }
        } catch (Throwable ignore) {
            // Log asla uygulamayı etkilememeli.
        }
    }
}
```

- [ ] **Step 4: TextReplace.java'yı yalnız çekirdek mantıkla yaz** (install/onKeyTyped Task 3'te eklenecek):

```java
package macostextkeys;

/*
 * macOS sistem geneli "Metin Değiştirme" (Text Replacement) genişleticisi.
 *
 * Sorun: Sistem Ayarları → Klavye → Metin Değiştirmeleri kısayolları
 * ("mrb" → "Merhaba!") native uygulamalarda Cocoa metin-denetim katmanından
 * uygulanır; Java/Swing bu kanala bağlanmaz → UDE'de çalışmaz.
 *
 * Çözüm: KEY_TYPED ile sonlandırıcı karakter (boşluk, Enter, noktalama)
 * yazıldığında, UDE'nin kendi keyTyped zinciri işini bitirdikten sonra
 * (invokeLater) caret'in solundaki sözcük sistem listesiyle (ReplacementStore)
 * eşleştirilir ve belge üzerinden değiştirilir. Kısayol tamamen küçük harfse
 * ilk-harfi-büyük yazılışı da eşleşir (macOS davranışı + UDE "Otomatik Büyük
 * Harf" uyumu) ve karşılığın ilk harfi tr-TR ile büyütülür.
 */

import java.util.Locale;
import java.util.Map;

import javax.swing.text.JTextComponent;

public final class TextReplace {

    private static final Locale TR = new Locale("tr", "TR");
    /** Değiştirmeyi tetikleyen sonlandırıcılar (yazılan karakter). */
    static final String TRIGGERS = " \t\n\r.,;:!?)\"'";
    /** Geri taramada sözcüğü sınırlayanlar (tetikleyiciler + açılış ayraçları). */
    private static final String BOUNDARIES = TRIGGERS + "([{";

    private TextReplace() {}

    static boolean isBoundary(char c) {
        return BOUNDARIES.indexOf(c) >= 0;
    }

    /** end'den (hariç) geriye doğru sözcük başlangıcını bulur. */
    static int tokenStart(String text, int end) {
        int i = end;
        while (i > 0 && !isBoundary(text.charAt(i - 1))) i--;
        return i;
    }

    /**
     * Birebir eşleşme; yoksa macOS uyarlaması: kısayol tamamen küçük harfse
     * ilk-harfi-büyük yazılışı da eşleşir, karşılığın ilk harfi büyütülür.
     */
    static String lookup(Map<String, String> table, String word) {
        String exact = table.get(word);
        if (exact != null) return exact;
        String decap = decapFirst(word);
        if (decap.equals(word)) return null;
        String phrase = table.get(decap);
        if (phrase != null && decap.equals(decap.toLowerCase(TR))) return capFirst(phrase);
        return null;
    }

    static String capFirst(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(TR) + s.substring(1);
    }

    static String decapFirst(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(TR) + s.substring(1);
    }

    /**
     * Caret'in solundaki "<sözcük><sonlandırıcı>" desenini tabloyla eşleştirip
     * sözcüğü değiştirir; sonlandırıcı korunur, caret arkasında bırakılır.
     * invokeLater'dan çağrılır; belge bu arada değişmiş olabilir → her koşul
     * yeniden doğrulanır, her hata yutularak statüko korunur.
     */
    static boolean maybeReplace(JTextComponent tc, Map<String, String> table, int maxShortcutLen) {
        try {
            if (table.isEmpty() || maxShortcutLen <= 0) return false;
            int caret = tc.getCaretPosition();
            if (caret < 2) return false;
            int from = Math.max(0, caret - (maxShortcutLen + 1));
            String ctx = tc.getDocument().getText(from, caret - from);
            char last = ctx.charAt(ctx.length() - 1);
            if (TRIGGERS.indexOf(last) < 0) return false;
            int tokEnd = ctx.length() - 1;
            int tokStart = tokenStart(ctx, tokEnd);
            if (tokStart >= tokEnd) return false;
            if (tokStart == 0 && from > 0
                    && !isBoundary(tc.getDocument().getText(from - 1, 1).charAt(0))) {
                return false; // sözcük pencereden uzun → kısayol olamaz
            }
            String word = ctx.substring(tokStart, tokEnd);
            String phrase = lookup(table, word);
            if (phrase == null) return false;
            int docStart = from + tokStart;
            tc.setCaretPosition(docStart);
            tc.moveCaretPosition(docStart + word.length());
            tc.replaceSelection(phrase);
            tc.setCaretPosition(docStart + phrase.length() + 1);
            TrLog.log("değiştirildi: '" + word + "' → " + phrase.length() + " karakter");
            return true;
        } catch (Throwable t) {
            TrLog.log("maybeReplace hata: " + t);
            return false;
        }
    }
}
```

- [ ] **Step 5: Testi çalıştır, geçtiğini doğrula:**

Run:
```bash
rm -rf /tmp/trm-test && javac -encoding UTF-8 -d /tmp/trm-test \
  scripts/macos-textkeys/macostextkeys/TrLog.java \
  scripts/macos-textkeys/macostextkeys/TextReplace.java \
  tests/TextReplaceMatchTest.java \
&& java -Djava.awt.headless=true -cp /tmp/trm-test macostextkeys.TextReplaceMatchTest
```
Expected: `OK`

- [ ] **Step 6: Commit:**

```bash
git add scripts/macos-textkeys/macostextkeys/TrLog.java \
  scripts/macos-textkeys/macostextkeys/TextReplace.java tests/TextReplaceMatchTest.java
git commit -m "feat(textreplace): eşleştirme çekirdeği + TrLog (macOS metin değiştirme)"
```

---

### Task 2: ReplacementStore — sistem listesini okuma (TDD)

**Files:**
- Create: `scripts/macos-textkeys/macostextkeys/ReplacementStore.java`
- Test: `tests/ReplacementStoreTest.java`

- [ ] **Step 1: Başarısız testi yaz** — `tests/ReplacementStoreTest.java`:

```java
package macostextkeys;

import java.io.File;
import java.util.Map;

/*
 * ReplacementStore SQLite okuma testi: gerçek şemayla geçici db kurulur
 * (sqlite3 CLI), çok satırlı/Türkçe karakterli/silinmiş kayıtlar doğrulanır.
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/trs-test \
 *     scripts/macos-textkeys/macostextkeys/TrLog.java \
 *     scripts/macos-textkeys/macostextkeys/ReplacementStore.java \
 *     tests/ReplacementStoreTest.java
 *   java -cp /tmp/trs-test macostextkeys.ReplacementStoreTest
 */
public final class ReplacementStoreTest {

    public static void main(String[] args) throws Exception {
        File db = File.createTempFile("ude-trs", ".db");
        db.delete();
        String sql = "CREATE TABLE ZTEXTREPLACEMENTENTRY (Z_PK INTEGER PRIMARY KEY,"
                + " ZWASDELETED INTEGER, ZPHRASE VARCHAR, ZSHORTCUT VARCHAR);"
                + " INSERT INTO ZTEXTREPLACEMENTENTRY (ZWASDELETED, ZPHRASE, ZSHORTCUT) VALUES"
                + " (0, 'Merhaba!', 'mrb'),"
                + " (0, 'Satır 1' || char(10) || 'Satır 2', 'adr'),"
                + " (0, 'Şişli/İstanbul ğüöç', 'işadr'),"
                + " (1, 'silinmiş', 'sil');";
        Process p = new ProcessBuilder("/usr/bin/sqlite3", db.getAbsolutePath(), sql).start();
        if (p.waitFor() != 0) throw new AssertionError("fixture db kurulamadı");

        Map<String, String> m = ReplacementStore.readFromDb(db);
        db.delete();

        int fails = 0;
        if (m == null) { System.out.println("FAIL: null döndü"); System.exit(1); }
        if (m.size() != 3) { fails++; System.out.println("FAIL boyut: " + m); }
        if (!"Merhaba!".equals(m.get("mrb"))) { fails++; System.out.println("FAIL mrb: " + m.get("mrb")); }
        if (!"Satır 1\nSatır 2".equals(m.get("adr"))) { fails++; System.out.println("FAIL çok satır: " + m.get("adr")); }
        if (!"Şişli/İstanbul ğüöç".equals(m.get("işadr"))) { fails++; System.out.println("FAIL türkçe: " + m.get("işadr")); }
        if (m.containsKey("sil")) { fails++; System.out.println("FAIL silinmiş kayıt geldi"); }

        if (fails > 0) { System.out.println("FAIL: " + fails); System.exit(1); }
        System.out.println("OK");
    }
}
```

- [ ] **Step 2: Derlemenin başarısız olduğunu doğrula:**

Run: `javac -encoding UTF-8 -d /tmp/trs-test scripts/macos-textkeys/macostextkeys/TrLog.java scripts/macos-textkeys/macostextkeys/ReplacementStore.java tests/ReplacementStoreTest.java`
Expected: FAIL — ReplacementStore yok.

- [ ] **Step 3: ReplacementStore.java yaz:**

```java
package macostextkeys;

/*
 * macOS Metin Değiştirme listesini okur ve cache'ler.
 *
 * Birincil kaynak ~/Library/KeyboardServices/TextReplacements.db (SQLite, WAL;
 * iCloud'la senkron iPhone kayıtları dahil). Canlı dosyaya dokunmamak için
 * db + -wal + -shm geçici dizine kopyalanıp sqlite3 CLI ile okunur. Çok
 * satırlı karşılık metinleri satır-temelli ayrıştırmayı bozacağından her iki
 * kolon hex() ile çekilip Java'da UTF-8 çözülür. Yedek kaynak:
 * `defaults export -globalDomain -` plist'indeki NSUserDictionaryReplacementItems.
 *
 * Yenileme: pencere aktifleşince refreshAsync() (30 sn kısıtlama, daemon
 * arka plan thread'i — EDT'de süreç çalıştırılmaz). Okuma başarısızsa eski
 * tablo korunur; hiç okunamadıysa boş tablo = özellik sessiz no-op.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class ReplacementStore {

    private static final long THROTTLE_MS = 30_000L;

    private static volatile Map<String, String> table = Collections.emptyMap();
    private static volatile int maxLen = 0;
    private static volatile long lastRead = 0L;
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private ReplacementStore() {}

    static Map<String, String> table() { return table; }

    static int maxShortcutLen() { return maxLen; }

    /** Pencere aktifleşince çağrılır; kısıtlamalı, arka planda yeniler. */
    static void refreshAsync() {
        long now = System.currentTimeMillis();
        if (now - lastRead < THROTTLE_MS) return;
        if (!busy.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { refreshNow(); } finally { busy.set(false); }
            }
        }, "ude-textreplace-refresh");
        t.setDaemon(true);
        t.start();
    }

    static void refreshNow() {
        lastRead = System.currentTimeMillis();
        Map<String, String> m = null;
        try {
            File db = new File(System.getProperty("user.home"),
                    "Library/KeyboardServices/TextReplacements.db");
            if (db.isFile()) m = readFromDb(db);
        } catch (Throwable t) {
            TrLog.log("sqlite okuma hata: " + t);
        }
        if (m == null) {
            try { m = readFromDefaults(); }
            catch (Throwable t) { TrLog.log("defaults okuma hata: " + t); }
        }
        if (m != null) {
            int max = 0;
            for (String k : m.keySet()) max = Math.max(max, k.length());
            table = m;
            maxLen = max;
            TrLog.log("liste yenilendi: " + m.size() + " kısayol");
        }
    }

    /** WAL modundaki canlı db'ye dokunmamak için kopya üzerinden okur. */
    static Map<String, String> readFromDb(File db) throws Exception {
        Path tmp = Files.createTempDirectory("ude-tr");
        try {
            Path copy = tmp.resolve("tr.db");
            Files.copy(db.toPath(), copy);
            for (String suf : new String[] {"-wal", "-shm"}) {
                File f = new File(db.getPath() + suf);
                if (f.isFile()) Files.copy(f.toPath(), tmp.resolve("tr.db" + suf));
            }
            String out = run("/usr/bin/sqlite3", copy.toString(),
                    "SELECT hex(ZSHORTCUT) || '|' || hex(ZPHRASE) FROM ZTEXTREPLACEMENTENTRY"
                    + " WHERE ZWASDELETED=0 AND ZSHORTCUT IS NOT NULL AND ZPHRASE IS NOT NULL;");
            if (out == null) return null;
            Map<String, String> m = new HashMap<String, String>();
            for (String line : out.split("\n")) {
                int bar = line.indexOf('|');
                if (bar <= 0) continue;
                String sc = fromHexUtf8(line.substring(0, bar));
                String ph = fromHexUtf8(line.substring(bar + 1));
                if (!sc.isEmpty() && !ph.isEmpty()) m.put(sc, ph);
            }
            return m;
        } finally {
            try {
                File[] kids = tmp.toFile().listFiles();
                if (kids != null) for (File f : kids) f.delete();
                Files.deleteIfExists(tmp);
            } catch (Throwable ignore) {}
        }
    }

    /** Yedek yol: global defaults plist'inden NSUserDictionaryReplacementItems. */
    static Map<String, String> readFromDefaults() throws Exception {
        String xml = run("/usr/bin/defaults", "export", "-globalDomain", "-");
        if (xml == null) return null;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Apple DTD'sini ağdan çekmeye kalkmasın:
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Element root = dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        Element topDict = firstChildElement(root, "dict");
        if (topDict == null) return null;
        Element items = valueForKey(topDict, "NSUserDictionaryReplacementItems");
        if (items == null || !"array".equals(items.getTagName())) return null;
        Map<String, String> m = new HashMap<String, String>();
        NodeList dicts = items.getChildNodes();
        for (int i = 0; i < dicts.getLength(); i++) {
            Node n = dicts.item(i);
            if (!(n instanceof Element) || !"dict".equals(((Element) n).getTagName())) continue;
            Element d = (Element) n;
            Element on = valueForKey(d, "on");
            if (on != null && ("false".equals(on.getTagName())
                    || "0".equals(on.getTextContent().trim()))) continue;
            Element rep = valueForKey(d, "replace");
            Element wit = valueForKey(d, "with");
            if (rep == null || wit == null) continue;
            String sc = rep.getTextContent();
            String ph = wit.getTextContent();
            if (sc != null && ph != null && !sc.isEmpty() && !ph.isEmpty()) m.put(sc, ph);
        }
        return m;
    }

    /** plist dict'inde <key>k</key>'in hemen ardından gelen değer elemanı. */
    private static Element valueForKey(Element dict, String k) {
        NodeList kids = dict.getChildNodes();
        boolean next = false;
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (next) return el;
            if ("key".equals(el.getTagName()) && k.equals(el.getTextContent())) next = true;
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) return (Element) n;
        }
        return null;
    }

    /**
     * Komutu çalıştırıp stdout'u döndürür; hata/zaman aşımında null.
     * Daemon arka plan thread'inden çağrılır (EDT değil).
     */
    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
        if (p.exitValue() != 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String fromHexUtf8(String hex) {
        int len = hex.length() & ~1;
        byte[] b = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            b[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: Testi çalıştır, geçtiğini doğrula:**

Run:
```bash
rm -rf /tmp/trs-test && javac -encoding UTF-8 -d /tmp/trs-test \
  scripts/macos-textkeys/macostextkeys/TrLog.java \
  scripts/macos-textkeys/macostextkeys/ReplacementStore.java \
  tests/ReplacementStoreTest.java \
&& java -cp /tmp/trs-test macostextkeys.ReplacementStoreTest
```
Expected: `OK`

- [ ] **Step 5: Gerçek sistem db'sine karşı duman testi** (bu makinede mrb/omw/Yldym kayıtlı):

```bash
cat > /tmp/TrsSmoke.java <<'EOF'
package macostextkeys;
import java.io.File;
public final class TrsSmoke {
    public static void main(String[] a) throws Exception {
        File db = new File(System.getProperty("user.home"),
                "Library/KeyboardServices/TextReplacements.db");
        System.out.println(ReplacementStore.readFromDb(db));
    }
}
EOF
javac -encoding UTF-8 -d /tmp/trs-test /tmp/TrsSmoke.java -cp /tmp/trs-test \
&& java -cp /tmp/trs-test macostextkeys.TrsSmoke && rm /tmp/TrsSmoke.java
```
Expected: `{mrb=Merhaba!, omw=On my way!, Yldym=Yoldayım!}` (sıra farklı olabilir).

- [ ] **Step 6: Commit:**

```bash
git add scripts/macos-textkeys/macostextkeys/ReplacementStore.java tests/ReplacementStoreTest.java
git commit -m "feat(textreplace): sistem listesi okuyucu (SQLite + defaults yedeği)"
```

---

### Task 3: Tetikleyici kablolama (install + onKeyTyped) ve MacTextKeys'e bağlama

**Files:**
- Modify: `scripts/macos-textkeys/macostextkeys/TextReplace.java` (install/onKeyTyped ekle)
- Modify: `scripts/macos-textkeys/macostextkeys/MacTextKeys.java:84` (`DictationFix.install();` sonrası)

- [ ] **Step 1: TextReplace'e install + onKeyTyped ekle.** Sınıfın import bloğuna ekle:

```java
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;

import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
```

`private TextReplace() {}` satırından sonra şu iki metodu ekle:

```java
    /** Agent giriş noktası; -Dmacostextreplace.off=1 ile tamamen kapatılır. */
    public static void install() {
        if (System.getProperty("macostextreplace.off") != null) return;
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() == KeyEvent.KEY_TYPED) {
                        onKeyTyped((KeyEvent) e);
                    } else if (e.getID() == WindowEvent.WINDOW_ACTIVATED) {
                        // Mac'te eklenen yeni kısayol restart'sız gelsin.
                        ReplacementStore.refreshAsync();
                    }
                }
            }, AWTEvent.KEY_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
            ReplacementStore.refreshAsync();
            TrLog.log("TextReplace kuruldu");
        } catch (Throwable t) {
            System.err.println("[macos-textkeys] TextReplace kurulamadı: " + t);
        }
    }

    private static void onKeyTyped(KeyEvent e) {
        try {
            if (TRIGGERS.indexOf(e.getKeyChar()) < 0) return;
            Object src = e.getSource();
            if (!(src instanceof JTextComponent) || src instanceof JPasswordField) return;
            final JTextComponent tc = (JTextComponent) src;
            if (!tc.isEditable() || !tc.isEnabled()) return;
            // AWTEventListener bileşen işlemeden ÖNCE çağrılır; karakterin belgeye
            // girmesini ve UDE'nin kendi keyTyped zincirini (otomatik büyük harf
            // vb.) beklemek için invokeLater.
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    maybeReplace(tc, ReplacementStore.table(), ReplacementStore.maxShortcutLen());
                }
            });
        } catch (Throwable t) {
            TrLog.log("onKeyTyped hata: " + t);
        }
    }
```

- [ ] **Step 2: MacTextKeys.install()'a bağla.** `MacTextKeys.java`'da `DictationFix.install();` satırından (83. satır civarı) sonra ekle:

```java
        // macOS sistem geneli Metin Değiştirme (Ayarlar → Klavye) kısayollarını
        // UDE metin alanlarında uygula ("mrb " → "Merhaba! ").
        TextReplace.install();
```

- [ ] **Step 3: Tüm agent kaynaklarının birlikte derlendiğini doğrula:**

Run: `rm -rf /tmp/tk-all && javac --release 11 -encoding UTF-8 -d /tmp/tk-all $(find scripts/macos-textkeys -name '*.java')`
Expected: hatasız çıkış (uyarı olabilir).

- [ ] **Step 4: Task 1 testinin hâlâ geçtiğini doğrula** (Task 1 Step 5'teki komut).
Expected: `OK`

- [ ] **Step 5: Commit:**

```bash
git add scripts/macos-textkeys/macostextkeys/TextReplace.java \
  scripts/macos-textkeys/macostextkeys/MacTextKeys.java
git commit -m "feat(textreplace): KEY_TYPED tetikleyici + agent kablolaması"
```

---

### Task 4: build.sh TEXTREPLACE bayrağı

**Files:**
- Modify: `scripts/build.sh:54` (bayrak varsayılanı) ve `package()` jpackage çağrısı (~satır 655)

- [ ] **Step 1: Bayrak varsayılanını ekle.** `LIVETOGGLE="${LIVETOGGLE:-1}"` satırının (54) altına:

```bash
TEXTREPLACE="${TEXTREPLACE:-1}" # 1=açık (varsayılan; macOS Metin Değiştirme kısayolları UDE'de) | 0=kapalı
```

- [ ] **Step 2: jpackage koşullu java-options.** `package()` içinde `local lookopts=()` bloğundan sonra:

```bash
	local tropts=()
	[ "$TEXTREPLACE" = "1" ] || tropts=(--java-options '-Dmacostextreplace.off=1')
```

ve jpackage çağrısında `${lookopts[@]+"${lookopts[@]}"}` satırının altına (bash 3.2 + set -u boş dizi deseni):

```bash
		${tropts[@]+"${tropts[@]}"} \
```

- [ ] **Step 3: Sözdizimi doğrula:**

Run: `bash -n scripts/build.sh`
Expected: çıktı yok (hata yok).

- [ ] **Step 4: Commit:**

```bash
git add scripts/build.sh
git commit -m "feat(textreplace): build.sh TEXTREPLACE bayrağı (varsayılan açık)"
```

---

### Task 5: Tam build + canlı doğrulama + dokümantasyon

**Files:**
- Modify: `CLAUDE.md` (yeni bölüm)
- Build çıktısı: `build/…app`

- [ ] **Step 1: Tam build:**

```bash
bash scripts/build.sh download && bash scripts/build.sh patch \
  && bash scripts/build.sh textkeys && bash scripts/build.sh lookagent \
  && bash scripts/build.sh package
```
Expected: `Paketlendi: …` (her adım yeşil). Not: `download` her iterasyonda şart (CLAUDE.md).

- [ ] **Step 2: Agent jar'da sınıfların varlığını doğrula:**

Run: `unzip -l build/_input/macos-textkeys.jar | grep -E 'TextReplace|ReplacementStore|TrLog'`
Expected: üç sınıf da listede.

- [ ] **Step 3: Uygulamayı logla başlat** (eski süreci öldürerek; `open` değil doğrudan binary — CLAUDE.md test kuralı):

```bash
pkill -f UyapDokumanEditoru; sleep 2
rm -f ~/Library/Logs/ude-textreplace.txt
UDE_TRLOG=1 build/*.app/Contents/MacOS/UyapDokumanEditoru & sleep 25
cat ~/Library/Logs/ude-textreplace.txt
```
Expected: `TextReplace kuruldu` ve `liste yenilendi: 3 kısayol` satırları (sistemde mrb/omw/Yldym kayıtlı).

- [ ] **Step 4: Canlı doğrulama — dynamic attach probe** (sentetik KeyEvent güvenilmez → editör belgesine "mrb " yazıp `maybeReplace`'i doğrudan çağır; DictSim/attach deseni: anonim iç sınıfları jar'a koy, jar yolu MUTLAK). Probe agent'ı:
  1. Tüm Frame'lerde bileşen ağacından `javax.swing.text.JTextComponent` türevi, görünür ve düzenlenebilir editörü bulur (sınıf adı `tr.com…text.t`).
  2. EDT'de `doc.insertString(caret, "mrb ", null)` → `TextReplace.maybeReplace(tc, ReplacementStore.table(), ReplacementStore.maxShortcutLen())` (yansıma ile, agent jar sınıfları uygulama classpath'inde — aynı ClassLoader'da `Class.forName("macostextkeys.TextReplace")`).
  3. Belge metnini `/tmp/tr-probe.txt`'ye yazar.

  Doğrulama: `/tmp/tr-probe.txt` içinde `Merhaba! ` geçmeli ("mrb " kalmamalı). (Boş belge açık olmalı — uygulama içinden Yeni Belge; CLI argümanı splash'ta bırakır.)

- [ ] **Step 5: Task 1–2 testlerini son kez çalıştır** (regresyon).
Expected: her ikisi `OK`.

- [ ] **Step 6: CLAUDE.md'ye bölüm ekle** ("## Otomatik düzeltme seçenekleri anında etkin" bölümünden sonra):

```markdown
## macOS Metin Değiştirme (TEXTREPLACE=1, 2026-06)

Sistem Ayarları → Klavye → Metin Değiştirmeleri kısayolları ("mrb" →
"Merhaba!") UDE'de çalışır. Cocoa metin-denetim kanalı Java'ya kapalı →
`macos-textkeys` agent'ında `TextReplace`: KEY_TYPED sonlandırıcı (boşluk/
Enter/noktalama) → invokeLater → caret solundaki sözcük eşleşirse
replaceSelection. Liste `ReplacementStore`: TextReplacements.db kopyası
sqlite3 hex() ile (çok satırlı phrase satır-ayrıştırmayı bozar), yedek
`defaults export -globalDomain` plist; pencere aktifleşince 30 sn
kısıtlamayla daemon thread'de yenilenir (EDT'de süreç yok). Küçük harfli
kısayol ilk-harf-büyük yazılışla da eşleşir (tr-TR capFirst — UDE Otomatik
Büyük Harf uyumu). Dikteyle giren metne uygulanmaz (keyTyped üretilmez,
DictationFix takası). Teşhis: `UDE_TRLOG=1` →
`~/Library/Logs/ude-textreplace.txt`. Testler: `tests/TextReplaceMatchTest.java`,
`tests/ReplacementStoreTest.java` (javac+java elle).
```

- [ ] **Step 7: Commit:**

```bash
git add CLAUDE.md
git commit -m "docs: macOS metin değiştirme mekanizması notları"
```

- [ ] **Step 8: Elle son test (repo sahibi — elle test tercihi):** gerçek yazımla editörde "mrb"+boşluk, Bul/Değiştir kutusunda deneme, ⌘Z geri alma, Mac'te yeni kısayol ekleyip restart'sız UDE'ye gelmesi (pencere değiştirip dönünce ≥30 sn sonra).

---

## Self-Review Notu

- Spec kapsaması: veri okuma (Task 2), tetikleme/değiştirme (Task 1+3), büyük harf uyarlaması (Task 1), kapsam=her yer (AWTEventListener global, Task 3), TEXTREPLACE bayrağı (Task 4), UDE_TRLOG (Task 1 TrLog), restart'sız yenileme (Task 2+3 WINDOW_ACTIVATED), testler + canlı doğrulama + elle test (Task 5). ✓
- `maybeReplace(tc, table, maxShortcutLen)` imzası Task 1'de tanımlı, Task 3 ve Task 5 probe'u aynı imzayı kullanır. ✓
- JPasswordField dışlanır; salt-okunur/disabled alanlar dışlanır. ✓
