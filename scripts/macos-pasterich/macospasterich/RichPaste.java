package macospasterich;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Harici stilli yapıştırma köprüsü. Panodaki HTML'i (Word/tarayıcı/PDF) paketli
 * udf-cli ikilisine alt süreçle çevirip UDE'nin kendi .udf (UDF zip) formatını
 * üretir. Dönen baytlar build-zamanı kancası (PasteRichPatch) tarafından
 * WPDocumentPanel.a(InputStream)'e beslenir → select-all/copy/paste.
 *
 * Bu sınıf UDE iç sınıflarına BAĞIMLI DEĞİLDİR (yalnız java.* + alt süreç);
 * jar'a yardımcı olarak enjekte edilir. UDE-bağımlı besleme adımları kancanın
 * kendisinde (hj.a(Transferable) içinde) yapılır.
 *
 * Tasarımı gereği SESSIZ BAŞARISIZLIK: ikili yoksa / hata / timeout → null
 * döner; çağıran mevcut düz-metin yoluna düşer (çökme yok).
 */
public final class RichPaste {

    private static final long TIMEOUT_SECONDS = 5;

    /**
     * Pano HTML'ini .udf baytına çevirir; başarısızlıkta null.
     */
    public static byte[] fromClipboardHtml(String html) {
        if (html == null || html.isEmpty()) return null;
        String bin = resolveBinary();
        if (bin == null) {
            PrLog.log("udf-cli ikilisi bulunamadı");
            return null;
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin);
            pb.redirectErrorStream(false);
            p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(html.getBytes("UTF-8"));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) >= 0) out.write(buf, 0, n);
            }
            boolean done = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                PrLog.log("timeout");
                return null;
            }
            if (p.exitValue() != 0) {
                PrLog.log("exit=" + p.exitValue());
                return null;
            }
            byte[] udf = out.toByteArray();
            if (udf.length < 4 || udf[0] != 'P' || udf[1] != 'K') {
                PrLog.log("PK zip değil (" + udf.length + " bayt)");
                return null;
            }
            PrLog.log("ok " + udf.length + " bayt");
            return udf;
        } catch (Throwable t) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Throwable ignore) {}
            }
            PrLog.log("fromClipboardHtml", t);
            return null;
        }
    }

    /**
     * İkili yolunu çözer: önce UDE_UDFCLI env override; sonra editor-app.jar
     * konumuna göre ../../Resources/udf-cli (.app/Contents/app → Contents/Resources).
     */
    static String resolveBinary() {
        String env = System.getenv("UDE_UDFCLI");
        if (env != null && new File(env).canExecute()) return env;
        try {
            URL loc = RichPaste.class.getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(loc.toURI()); // .../Contents/app/editor-app.jar
            File res = new File(jar.getParentFile().getParentFile(), "Resources/udf-cli");
            if (res.canExecute()) return res.getAbsolutePath();
        } catch (Throwable t) {
            PrLog.log("resolveBinary", t);
        }
        return null;
    }

    /** PasteRichPatch kancası catch bloğundan çağrılır. */
    public static void logExternal(Throwable t) {
        PrLog.log("inject", t);
    }

    private RichPaste() {
    }
}
