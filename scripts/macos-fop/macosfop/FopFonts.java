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
 * PDF'te düşer (sembol/“#”). (Windows'ta da aynı mekanizma; macOS'a özgü değildir.)
 *
 * Çözüm: FopFactory'ye, tam Unicode kapsamlı fontları GÖMEN bir kullanıcı
 * yapılandırması set edilir. Çıktı PDF'te font Type0/Identity-H + gömülü FontFile2
 * olur, tüm Türkçe harfler doğru çizilir ve PDF kendi kendine yeter (görüntüleyenin
 * fontu olması gerekmez).
 *
 * HİBRİT font seçimi (her macOS'ta çalışsın diye):
 *   - Sistemde Times New Roman / Arial varsa (çoğu Mac) onlar gömülür → gerçek Times/Arial.
 *   - Yoksa (bu fontlar silinmiş/hiç olmamış Mac'ler) uygulamayla BİRLİKTE GELEN
 *     Liberation Serif/Sans (OFL; Times/Arial ile metrik-uyumlu, tam Türkçe) gömülür.
 * Böylece "bir kullanıcıda çalışıp diğerinde çalışmama" (sistem fontu eksikliği)
 * sorunu ortadan kalkar.
 *
 * Neden named triplet değil fallback adlar: UDE'nin XSL→FO dönüştürücüsü (b.e)
 * gerçek belgelerde font-family'yi ya HİÇ vermez (FOP varsayılanı "sans-serif") ya da
 * BOŞLUKLU ad verir ("Times New Roman", "Tahoma") — FOP 0.92 boşluklu adı eşleştiremez.
 * Her iki durumda da FOP'un fallback/varsayılan zincirine düşülür; bu yüzden o adlar
 * (sans-serif, serif, any, Times, Helvetica, monospace…) gömülü serif fonta bağlanır.
 * UYAP belgelerinin standart yazı tipi zaten Times'tır. Boşluksuz "Arial" ayrıca korunur.
 *
 * Tümü try/catch ile sarılıdır: font/metrik yoksa hiçbir şey yapılmaz, mevcut
 * (base-14) davranış korunur — dışa aktarım asla bu yama yüzünden kırılmaz.
 *
 * Metrik XML'ler ve Liberation TTF'leri uygulama paketinde (Contents/app/fopfonts/)
 * bulunur; build (scripts/build.sh apply_fop_fonts) üretir/yerleştirir. Bu sınıf jar'a
 * konur ve FOP sürücüsünün FopFactory.newInstance() sonrası apply(...) Javassist ile eklenir.
 */
public final class FopFonts {

    private static final String SUP = "/System/Library/Fonts/Supplemental/";

    // variant: systemTtf | systemMetric | libTtf | libMetric | style | weight
    private static final String[][] SERIF = {
        {"Times New Roman.ttf",             "times.xml",            "LiberationSerif-Regular.ttf",    "libserif.xml",            "normal", "normal"},
        {"Times New Roman Bold.ttf",        "times-bold.xml",       "LiberationSerif-Bold.ttf",       "libserif-bold.xml",       "normal", "bold"},
        {"Times New Roman Italic.ttf",      "times-italic.xml",     "LiberationSerif-Italic.ttf",     "libserif-italic.xml",     "italic", "normal"},
        {"Times New Roman Bold Italic.ttf", "times-bolditalic.xml", "LiberationSerif-BoldItalic.ttf", "libserif-bolditalic.xml", "italic", "bold"},
    };
    private static final String[][] SANS = {
        {"Arial.ttf",            "arial.xml",            "LiberationSans-Regular.ttf",    "libsans.xml",            "normal", "normal"},
        {"Arial Bold.ttf",       "arial-bold.xml",       "LiberationSans-Bold.ttf",       "libsans-bold.xml",       "normal", "bold"},
        {"Arial Italic.ttf",     "arial-italic.xml",     "LiberationSans-Italic.ttf",     "libsans-italic.xml",     "italic", "normal"},
        {"Arial Bold Italic.ttf","arial-bolditalic.xml", "LiberationSans-BoldItalic.ttf", "libsans-bolditalic.xml", "italic", "bold"},
    };
    // font-family → kategori eşlemesi.
    // - SANS: yalnızca AÇIKÇA sans olan adlar (Arial, Helvetica…). Bu adlar gerçekten
    //   sans içeriği temsil eder; sans olarak basılır.
    // - SERIF: serif adlar + UDE'nin font yazmadığı durumda FOP'un kullandığı "sans-serif"
    //   varsayılanı + eşleşmeyen adların düştüğü "any". UDE font-family'yi çoğu zaman HİÇ
    //   yazmaz; bu belgeler aslında UYAP standardı Times'tır, bu yüzden "sans-serif"
    //   (=fontsuz varsayılan) bilerek serif/Times'a bağlanır. (UDE pratikte "sans-serif"
    //   anahtar kelimesini üretmediğinden sans içeriğin sadakati bozulmaz; gerçek sans
    //   metin "Arial" gibi adlarla gelir ve yukarıda sans'a düşer.)
    private static final String[] SANS_FAMILIES = {
        "SansSerif", "Helvetica", "Arial",
    };
    private static final String[] SERIF_FAMILIES = {
        "serif", "Serif", "sans-serif", "any", "Default", "Times", "TimesNewRoman",
        "monospace", "Courier",
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

        File bundle = bundleFopFontsDir();   // Contents/app/fopfonts (metrikler + Liberation TTF)
        if (bundle == null || !bundle.isDirectory()) return null;

        // Temiz çalışma dizini: paket yolu boşluk/Türkçe içerebileceğinden
        // ("Uyap Doküman Editörü.app") metrikleri buraya kopyalayıp URL sorunlarını önle.
        File work = new File(System.getProperty("java.io.tmpdir", "/tmp"), "ude-fopfonts");
        work.mkdirs();

        StringBuilder fonts = new StringBuilder();
        // sans adlar → sans (sistem Arial varsa o, yoksa Liberation Sans)
        for (String fam : SANS_FAMILIES)
            for (String[] v : SANS) addFont(fonts, bundle, work, v, fam);
        // serif adlar → serif (sistem Times varsa o, yoksa Liberation Serif)
        for (String fam : SERIF_FAMILIES)
            for (String[] v : SERIF) addFont(fonts, bundle, work, v, fam);

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

    /**
     * Bir variant için font-triplet ekler. HİBRİT: sistem fontu (Times/Arial) varsa
     * onu (sistem metriğiyle), yoksa pakete gömülü Liberation'ı (kendi metriğiyle) kullanır.
     * Metrik gömülen TTF ile EŞLEŞMELİ (aynı font), aksi halde glif kayar.
     */
    private static void addFont(StringBuilder sb, File bundle, File work,
                                String[] v, String family) throws Exception {
        // v: [0]=sysTtf [1]=sysMetric [2]=libTtf [3]=libMetric [4]=style [5]=weight
        File sysTtf    = new File(SUP + v[0]);
        File sysMetric = new File(bundle, v[1]);
        File libTtf    = new File(bundle, v[2]);
        File libMetric = new File(bundle, v[3]);

        File ttf, metric;
        if (sysTtf.isFile() && sysMetric.isFile()) {        // sistem fontu mevcut → gerçek Times/Arial
            ttf = sysTtf; metric = sysMetric;
        } else if (libTtf.isFile() && libMetric.isFile()) { // değilse → gömülü Liberation
            ttf = libTtf; metric = libMetric;
        } else {
            return; // ikisi de yoksa bu varyantı atla
        }

        File m = new File(work, metric.getName());
        if (!m.isFile()) copy(metric, m);
        sb.append("<font metrics-url=\"").append(fileUrl(m))
          .append("\" kerning=\"yes\" embed-url=\"").append(fileUrl(ttf))
          .append("\"><font-triplet name=\"").append(family)
          .append("\" style=\"").append(v[4])
          .append("\" weight=\"").append(v[5]).append("\"/></font>");
    }

    /** Çalışan editor-app.jar'ın yanındaki Contents/app/fopfonts dizinini bulur. */
    private static File bundleFopFontsDir() {
        try {
            File self = new File(FopFonts.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File base = self.getParentFile();   // .../Contents/app
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
