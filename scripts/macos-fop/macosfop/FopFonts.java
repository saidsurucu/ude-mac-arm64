package macosfop;

import org.apache.fop.apps.FopFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * macOS'ta UDF→PDF dışa aktarımda Türkçe harf (ğ Ğ ş Ş ı İ) kaybını giderir.
 *
 * Sorun: UDE'nin FOP sürücüsü (tr.com.havelsan...editor.b.a) FopFactory'yi font
 * yapılandırması OLMADAN kurar. FOP base-14 standart PDF fontlarına (WinAnsi=Cp1252)
 * düşer; Cp1252'de ç ö ü vardır ama Türkçe'ye özgü ğ Ğ ş Ş ı İ YOKTUR → bu harfler
 * PDF'te düşer. (Windows'ta da aynı mekanizma; macOS'a özgü değildir.)
 *
 * Neden fallback/varsayılan font'a bağlıyoruz (named triplet değil):
 * UDE'nin XSL→FO dönüştürücüsü (b.e) gerçek belgelerde font-family'yi ya HİÇ vermez
 * (FOP varsayılanı "sans-serif") ya da BOŞLUKLU ad verir ("Times New Roman",
 * "Tahoma"). FOP 0.92 boşluklu font-family adlarını eşleştiremez → her iki durumda
 * da FOP'un varsayılan/fallback font zincirine düşülür. Bu yüzden düzeltme, bu
 * fallback/varsayılan adları (sans-serif, serif, any, Times, Helvetica, monospace…)
 * tam Unicode kapsamlı, GÖMÜLÜ Times New Roman'a bağlamaktır. (UYAP belgelerinin
 * standart yazı tipi zaten Times New Roman'dır.) Boşluksuz "Arial" adı eşleşebildiği
 * için Arial ayrıca korunur.
 *
 * Çıktı PDF'te font Type0/Identity-H + gömülü FontFile2 olur, tüm Türkçe harfler
 * doğru çizilir. Gerçek belgelerle (font-family yok / boşluklu Times / Tahoma)
 * doğrulanmıştır: base-14'e düşüş kalmaz.
 *
 * Yapılandırma çalışma zamanında üretilir: metrik XML'ler uygulama paketinden
 * (Contents/app/fopfonts/, build'de TTFReader ile üretilir) okunur; gömülecek TTF'ler
 * macOS /System/Library/Fonts/Supplemental'dan alınır. Tümü try/catch ile sarılıdır:
 * metrik/font yoksa hiçbir şey yapılmaz, mevcut (base-14) davranış korunur — dışa
 * aktarım asla bu yama yüzünden kırılmaz.
 *
 * build.sh (apply_fop_fonts) bu sınıfı jar'a koyar ve FOP sürücüsünün
 * FopFactory.newInstance() çağrısından sonra apply(...)'i Javassist ile ekler.
 */
public final class FopFonts {

    private static final String SUP = "/System/Library/Fonts/Supplemental/";

    // Fiziksel font varyantları: metrik-xml | Supplemental TTF | style | weight
    private static final String[][] TIMES = {
        {"times.xml",            "Times New Roman.ttf",             "normal", "normal"},
        {"times-bold.xml",       "Times New Roman Bold.ttf",        "normal", "bold"},
        {"times-italic.xml",     "Times New Roman Italic.ttf",      "italic", "normal"},
        {"times-bolditalic.xml", "Times New Roman Bold Italic.ttf", "italic", "bold"},
    };
    private static final String[][] ARIAL = {
        {"arial.xml",            "Arial.ttf",                       "normal", "normal"},
        {"arial-bold.xml",       "Arial Bold.ttf",                  "normal", "bold"},
        {"arial-italic.xml",     "Arial Italic.ttf",               "italic", "normal"},
        {"arial-bolditalic.xml", "Arial Bold Italic.ttf",           "italic", "bold"},
    };
    // UDE FO'sunun düştüğü tüm varsayılan/fallback font-family adları → gömülü Times.
    // "TimesNewRoman" (boşluksuz) da güvenlik için eklenir.
    private static final String[] TIMES_FAMILIES = {
        "sans-serif", "serif", "any", "Times", "Helvetica", "monospace",
        "Courier", "SansSerif", "Serif", "Default", "TimesNewRoman",
    };

    private static boolean done;
    private static File conf;

    private FopFonts() {}

    /** FOP sürücüsünden, FopFactory üretildikten hemen sonra çağrılır. */
    public static void apply(FopFactory ff) {
        if (ff == null) return;
        try {
            File c = config();
            if (c != null) ff.setUserConfig(c);
        } catch (Throwable ignore) {
            // Sessizce geç: mevcut davranışı (base-14) bozma.
        }
    }

    /** Yapılandırmayı bir kez üretir ve önbelleğe alır; üretilemezse null. */
    private static synchronized File config() throws Exception {
        if (done) return conf;
        done = true;

        File metricsDir = bundleFopFontsDir();
        if (metricsDir == null || !metricsDir.isDirectory()) return null;

        // Temiz çalışma dizini: paket yolu boşluk/Türkçe içerebileceğinden
        // ("Uyap Doküman Editörü.app") metrikleri buraya kopyalayıp URL sorunlarını önle.
        File work = new File(System.getProperty("java.io.tmpdir", "/tmp"), "ude-fopfonts");
        work.mkdirs();

        StringBuilder fonts = new StringBuilder();
        // 1) Arial ailesi (boşluksuz "Arial" FO'da eşleşebilir → Arial sadakati)
        for (String[] v : ARIAL) addFont(fonts, metricsDir, work, v, "Arial");
        // 2) Tüm fallback/varsayılan adlar → gömülü Times New Roman (ASIL düzeltme)
        for (String fam : TIMES_FAMILIES)
            for (String[] v : TIMES) addFont(fonts, metricsDir, work, v, fam);

        if (fonts.length() == 0) return null;

        String xml = "<?xml version=\"1.0\"?>\n"
            + "<fop version=\"1.0\"><base>.</base><renderers>"
            + "<renderer mime=\"application/pdf\"><fonts>" + fonts
            + "</fonts></renderer></renderers></fop>";

        File out = new File(work, "fopconf.xml");
        Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        try { w.write(xml); } finally { w.close(); }
        conf = out;
        return conf;
    }

    /** Tek bir font-triplet ekler (metrik temiz dizine kopyalanır); eksikse atlar. */
    private static void addFont(StringBuilder sb, File metricsDir, File work,
                                String[] v, String family) throws Exception {
        File src = new File(metricsDir, v[0]);
        File ttf = new File(SUP + v[1]);
        if (!src.isFile() || !ttf.isFile()) return;
        File metric = new File(work, v[0]);
        if (!metric.isFile()) copy(src, metric);
        sb.append("<font metrics-url=\"").append(fileUrl(metric))
          .append("\" kerning=\"yes\" embed-url=\"").append(fileUrl(ttf))
          .append("\"><font-triplet name=\"").append(family)
          .append("\" style=\"").append(v[2])
          .append("\" weight=\"").append(v[3]).append("\"/></font>");
    }

    /** Çalışan editor-app.jar'ın yanındaki Contents/app/fopfonts dizinini bulur. */
    private static File bundleFopFontsDir() {
        try {
            File self = new File(FopFonts.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // self = .../Contents/app/editor-app.jar → parent = .../Contents/app
            File base = self.getParentFile();
            if (base == null) return null;
            return new File(base, "fopfonts");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * FOP 0.92 boşluklu dosya yollarını düz "file://"+mutlak-yol biçiminde (literal
     * boşlukla) yükler; "Times New Roman.ttf" gibi adlar bu biçimde çalışır.
     */
    private static String fileUrl(File f) {
        return "file://" + f.getAbsolutePath();
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        try {
            OutputStream o = new FileOutputStream(dst);
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            } finally { o.close(); }
        } finally { in.close(); }
    }
}
