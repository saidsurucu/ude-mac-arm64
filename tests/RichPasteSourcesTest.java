import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Google Docs / Pages / AI-aracı pano HTML desenleri için birim test (harici
 * bağımlılık yok). Word olmayan kaynaklar stili &lt;b&gt;/&lt;i&gt; yerine inline
 * font-weight/font-style/text-decoration ya da &lt;style&gt; class kurallarıyla taşır.
 *
 *   javac --release 11 -d OUT scripts/macos-pasterich/macospasterich/*.java tests/RichPasteSourcesTest.java
 *   java -cp OUT RichPasteSourcesTest
 */
public class RichPasteSourcesTest {
    public static void main(String[] a) throws Exception {
        // 1) Google Docs: bold/italic/underline inline span stiliyle, renk rgb()
        String docs =
            "<b style=\"font-weight:normal\">"   // Docs sarmalayıcı — HER ŞEYİ bold yapmamalı
          + "<p dir=\"ltr\"><span style=\"font-weight:700\">kalın</span> "
          + "<span style=\"font-style:italic\">italik</span> "
          + "<span style=\"text-decoration:underline\">altı</span> "
          + "<span style=\"color:rgb(255,0,0)\">kırmızı</span> "
          + "<span>düz</span></p></b>";
        String x1 = xml(docs);
        require(x1, "bold=\"true\"");          // 700 → bold
        require(x1, "italic=\"true\"");
        require(x1, "underline=\"true\"");
        require(x1, "foreground=\"-65536\""); // rgb(255,0,0)
        // "düz" run bold OLMAMALI (sarmalayıcı <b> font-weight:normal ile geçersiz)
        if (countBoldRuns(x1) != 1)
            throw new AssertionError("Docs sarmalayıcı tüm run'ları bold yaptı: bold run=" + countBoldRuns(x1));

        // 2) Pages: <style> bloğu + class kuralları
        String pages =
            "<style>p.p1{margin:0 0 0 0;font:12.0px 'Helvetica';text-align:center}"
          + "span.s1{font-weight:bold}span.s2{font-style:italic}</style>"
          + "<p class=\"p1\"><span class=\"s1\">başlık</span> "
          + "<span class=\"s2\">alt</span></p>";
        String x2 = xml(pages);
        require(x2, "Alignment=\"1\"");   // class p1 → center
        require(x2, "bold=\"true\"");
        require(x2, "italic=\"true\"");

        // 3) AI/markdown: <strong>/<em> hâlâ çalışır (regresyon yok)
        String ai = "<p><strong>kalın</strong> <em>italik</em></p>";
        String x3 = xml(ai);
        require(x3, "bold=\"true\"");
        require(x3, "italic=\"true\"");

        System.out.println("PASS RichPasteSourcesTest");
    }

    private static String xml(String html) throws Exception {
        byte[] udf = macospasterich.RichPaste.fromClipboardHtml(html);
        if (udf == null) throw new AssertionError("null döndü: " + html);
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(udf))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if ("content.xml".equals(e.getName())) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = z.read(buf)) >= 0) b.write(buf, 0, n);
                    return new String(b.toByteArray(), "UTF-8");
                }
            }
        }
        throw new AssertionError("content.xml yok");
    }

    private static int countBoldRuns(String xml) {
        int c = 0, i = 0;
        while ((i = xml.indexOf("bold=\"true\"", i)) >= 0) { c++; i += 5; }
        return c;
    }

    private static void require(String xml, String needle) {
        if (!xml.contains(needle)) throw new AssertionError("beklenen parça yok: " + needle + "\n--- xml ---\n" + xml);
    }
}
