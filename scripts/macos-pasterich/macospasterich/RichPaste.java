package macospasterich;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import macospasterich.UdeDoc.Document;

/**
 * Harici stilli yapıştırma köprüsü (saf Java — harici bağımlılık / alt süreç YOK).
 * Panodaki HTML'i (Word/tarayıcı/PDF) UDE'nin kendi .udf (UDF zip: content.xml)
 * formatına çevirir. Boru hattı: Html tokenizer → HtmlToUde (model) → UdeXml
 * (content.xml) → zip. Dönen baytlar build-zamanı kancası (PasteRichPatch)
 * tarafından WPDocumentPanel.a(InputStream)'e beslenir → select-all/copy/paste.
 *
 * Tasarımı gereği SESSIZ BAŞARISIZLIK: hata/boş içerik → null; çağıran mevcut
 * düz-metin yoluna düşer (çökme yok).
 */
public final class RichPaste {

    /**
     * Pano HTML'ini canlı editörün belgesine caret'e YEREL ekler (copy→paste
     * YOK — o yol tabloları düzleştiriyordu). HtmlToUde modeli kurar, NativeInsert
     * tabloları DocumentEx.a ile gerçek tablo olarak, paragrafları StyleConstants
     * ile ekler. Başarıda true; başarısızsa false → çağıran düz-metne düşer.
     */
    public static boolean insertInto(Object editor, String html) {
        return insertInto(editor, html, null);
    }

    /**
     * cursorAttrs != null ise DÜZ-KARAKTER modu (Formatsız Yapıştır): yapı
     * korunur, karakter stili cursorAttrs'a indirgenir.
     */
    public static boolean insertInto(Object editor, String html, javax.swing.text.AttributeSet cursorAttrs) {
        try {
            if (html == null || html.isEmpty()) return false;
            PrLog.dumpHtml(html);
            UdeDoc.Document model = HtmlToUde.convert(html);
            if (model.body.isEmpty()) { PrLog.log("boş model"); return false; }
            boolean ok = NativeInsert.insert(editor, model, cursorAttrs);
            PrLog.log(ok ? ("insertInto ok " + model.body.size() + " blok"
                    + (cursorAttrs != null ? " (düz)" : "")) : "insertInto başarısız");
            return ok;
        } catch (Throwable t) {
            PrLog.log("insertInto", t);
            return false;
        }
    }

    /**
     * Pano RTF'ini (Pages/TextEdit/Mail — bu kaynaklar panoya HTML KOYMAZ, yalnız
     * RTF/RTFD) macOS yerel `textutil` aracıyla HTML'e çevirip aynı boru hattından
     * geçirir. textutil'in ürettiği HTML zaten &lt;style&gt; class kuralları + `font:`
     * kısayolu + tablo-kenarlığı-class biçimindedir (HtmlToUde bunu çözer). Başarıda
     * true; aksi halde false → çağıran düz-metne düşer.
     */
    public static boolean insertRtf(Object editor, java.awt.datatransfer.Transferable t) {
        return insertRtf(editor, t, null);
    }

    /** cursorAttrs != null → düz-karakter modu (Formatsız Yapıştır). */
    public static boolean insertRtf(Object editor, java.awt.datatransfer.Transferable t,
                                    javax.swing.text.AttributeSet cursorAttrs) {
        try {
            // Birincil: pbrich (Cocoa) panoyu ekleriyle okur → imaj-GÖMÜLÜ HTML.
            String html = pasteboardRichHtml();
            if (html == null || html.isEmpty()) {
                // Yedek: Transferable RTF → textutil (imaj YOK, ama metin/tablo/stil).
                if (t == null) return false;
                byte[] rtf = extractRtf(t);
                if (rtf == null || rtf.length == 0) return false;
                html = rtfToHtml(rtf);
                if (html != null && !html.isEmpty()) PrLog.log("rtf yedeği (textutil) " + rtf.length + " bayt");
            } else {
                PrLog.log("pbrich html " + html.length());
            }
            if (html == null || html.isEmpty()) { PrLog.log("rtf→html boş"); return false; }
            return insertInto(editor, html, cursorAttrs);
        } catch (Throwable e) {
            PrLog.log("insertRtf", e);
            return false;
        }
    }

    /**
     * Jar'a gömülü pbrich (Swift/Cocoa) ikilisini geçici dosyaya çıkarıp çalıştırır;
     * NSPasteboard'ı okuyup imaj-gömülü HTML döndürür. İkili yoksa / başarısızsa null
     * (çağıran textutil yedeğine düşer). İkili bir kez çıkarılıp yeniden kullanılır.
     */
    private static synchronized String pasteboardRichHtml() {
        try {
            java.io.File bin = pbrichBinary();
            if (bin == null) return null;
            Process p = new ProcessBuilder(bin.getAbsolutePath()).redirectErrorStream(false).start();
            byte[] out = readAll(p.getInputStream());
            p.waitFor();
            if (out.length == 0) return null;
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            PrLog.log("pbrich", e);
            return null;
        }
    }

    private static java.io.File pbrichBin;

    private static java.io.File pbrichBinary() throws Exception {
        if (pbrichBin != null && pbrichBin.canExecute()) return pbrichBin;
        java.io.InputStream is = RichPaste.class.getResourceAsStream("/macospasterich/pbrich");
        if (is == null) return null;   // swiftc yoktu → ikili gömülmedi
        try {
            java.io.File f = java.io.File.createTempFile("ude-pbrich", "");
            f.deleteOnExit();
            byte[] bytes = readAll(is);
            java.nio.file.Files.write(f.toPath(), bytes);
            f.setExecutable(true, true);
            pbrichBin = f;
            return f;
        } finally {
            is.close();
        }
    }

    /** Transferable'dan RTF baytlarını çıkarır (düz text/rtf, rtfd'ye tercih edilir). */
    private static byte[] extractRtf(java.awt.datatransfer.Transferable t) throws Exception {
        java.awt.datatransfer.DataFlavor best = null;
        for (java.awt.datatransfer.DataFlavor f : t.getTransferDataFlavors()) {
            String mt = f.getMimeType();
            if (mt == null) continue;
            String low = mt.toLowerCase();
            if (low.contains("rtf")) {
                if (low.contains("text/rtf") || low.contains("application/rtf")) { best = f; break; }
                if (best == null) best = f;   // rtfd vb. — düz rtf yoksa yedek
            }
        }
        if (best == null) return null;
        Object data = t.getTransferData(best);
        if (data instanceof byte[]) return (byte[]) data;
        if (data instanceof java.io.InputStream) return readAll((java.io.InputStream) data);
        if (data instanceof String) return ((String) data).getBytes(StandardCharsets.UTF_8);
        return null;
    }

    /** RTF baytlarını macOS textutil ile HTML'e çevirir. */
    private static String rtfToHtml(byte[] rtf) throws Exception {
        java.io.File in = java.io.File.createTempFile("ude-paste", ".rtf");
        try {
            java.nio.file.Files.write(in.toPath(), rtf);
            Process p = new ProcessBuilder("/usr/bin/textutil",
                    "-convert", "html", "-encoding", "UTF-8", "-stdout", in.getAbsolutePath())
                    .redirectErrorStream(false).start();
            byte[] out = readAll(p.getInputStream());
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8);
        } finally {
            in.delete();
        }
    }

    private static byte[] readAll(java.io.InputStream is) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Pano HTML'ini .udf (UDF zip) baytına çevirir; başarısızlıkta null. (Testler için.) */
    public static byte[] fromClipboardHtml(String html) {
        if (html == null || html.isEmpty()) return null;
        try {
            Document doc = HtmlToUde.convert(html);
            if (doc.body.isEmpty()) { PrLog.log("boş belge (içerik yok)"); return null; }
            String contentXml = UdeXml.serialize(doc);
            byte[] udf = zip(contentXml);
            PrLog.log("ok " + udf.length + " bayt, " + doc.body.size() + " blok");
            return udf;
        } catch (Throwable t) {
            PrLog.log("fromClipboardHtml", t);
            return null;
        }
    }

    /** content.xml dizesini tek girişli UDF zip'ine (.udf) paketler. */
    private static byte[] zip(String contentXml) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry e = new ZipEntry("content.xml");
            zos.putNextEntry(e);
            zos.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /** PasteRichPatch kancası catch bloğundan çağrılır. */
    public static void logExternal(Throwable t) {
        PrLog.log("inject", t);
    }

    /**
     * WPDocumentPanel.a():fi metodunu reflection ile çağırır. WPDocumentPanel'de
     * 'a()' adıyla İKİ no-arg metot vardır (obfuscate dönüş-tipi overload'ı):
     * biri int, biri fi döndürür. Javassist/javac kaynak düzeyinde dönüş tipiyle
     * ayırt edemez ("bad method") → bu yardımcı fi-döndüren olanı seçer. Dönen
     * Object, kancada tr...text.hj'ye cast edilir (fi extends hj).
     */
    public static Object docOf(Object panel) throws Exception {
        for (java.lang.reflect.Method m : panel.getClass().getMethods()) {
            if (m.getName().equals("a") && m.getParameterCount() == 0
                    && m.getReturnType().getName().endsWith(".text.fi")) {
                return m.invoke(panel);
            }
        }
        throw new NoSuchMethodException("WPDocumentPanel.a():fi bulunamadı");
    }

    private RichPaste() {
    }
}
