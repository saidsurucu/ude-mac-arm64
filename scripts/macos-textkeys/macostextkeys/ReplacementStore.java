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
