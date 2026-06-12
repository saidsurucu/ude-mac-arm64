import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * RichPaste saf-Java HTML→.udf dönüştürücü birim testi (harici bağımlılık yok).
 * Derle (tüm macospasterich + bu dosya) ve çalıştır:
 *   javac --release 11 -d OUT scripts/macos-pasterich/macospasterich/*.java tests/RichPasteUdfTest.java
 *   java -cp OUT RichPasteUdfTest
 */
public class RichPasteUdfTest {
    public static void main(String[] a) throws Exception {
        String html = "<p style='text-align:center'><b>Başlık</b></p>"
                + "<p>Normal <i>italik</i> ve <u>altı çizili</u> "
                + "<span style='color:#FF0000'>kırmızı</span>.</p>"
                + "<ul><li>Madde bir</li><li>Madde iki</li></ul>"
                + "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>";

        byte[] udf = macospasterich.RichPaste.fromClipboardHtml(html);
        if (udf == null) throw new AssertionError("null döndü");
        if (udf[0] != 'P' || udf[1] != 'K') throw new AssertionError("PK zip değil");

        String xml = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(udf))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if ("content.xml".equals(e.getName())) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = z.read(buf)) >= 0) b.write(buf, 0, n);
                    xml = new String(b.toByteArray(), "UTF-8");
                }
            }
        }
        if (xml == null) throw new AssertionError("content.xml yok");

        require(xml, "<template format_id=\"1.8\">");
        require(xml, "<elements resolver=\"hvl-default\">");
        require(xml, "Alignment=\"1\"");          // ortala
        require(xml, "bold=\"true\"");
        require(xml, "italic=\"true\"");
        require(xml, "underline=\"true\"");
        require(xml, "foreground=\"-65536\"");     // kırmızı
        require(xml, "Bulleted=\"true\"");          // liste
        require(xml, "<table");
        require(xml, "<row");
        require(xml, "<cell");

        System.out.println("PASS RichPasteUdfTest (" + udf.length + " bayt)");
    }

    private static void require(String xml, String needle) {
        if (!xml.contains(needle)) throw new AssertionError("içerik beklenen parçayı içermiyor: " + needle);
    }
}
