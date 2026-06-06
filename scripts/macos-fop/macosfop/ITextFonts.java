package macosfop;

import com.lowagie.text.pdf.BaseFont;

import java.awt.Font;
import java.io.File;

/**
 * UDE'nin iText (com.lowagie) tabanlı PDF dışa aktarım yolunu Türkçe için düzeltir.
 *
 * Bu yol GUI "PDF'e çevir" menüsü DEĞİL; entegrasyon/headless dönüştürmedir
 * (tr.com.havelsan...editor.utils.tray.TrayUtils.udfToPdf → editor.b.b → iText).
 * b.b, sayfaları PdfTemplate.createGraphics(w, h, FontMapper) ile çizer; FontMapper
 * olarak editor.b.c kullanılır ve onun awtToPdf(...) metodu base-14 (Times/Helvetica/
 * Courier — GÖMÜLÜ DEĞİL) BaseFont döndürür. Sonuç: Türkçe harfler (özellikle büyük
 * İ) /Differences ile glif adıyla yazılır; Preview bunu sistem fontuyla ikame edip
 * gösterir ama Acrobat gibi katı görüntüleyiciler büyük İ'yi göstermez.
 *
 * Çözüm: awtToPdf bu sınıfın map(...)'ine yönlendirilir; map, AWT fontuna göre
 * (serif/sans + bold/italic) GÖMÜLÜ, Identity-H kodlamalı, tam Unicode bir BaseFont
 * döndürür → tüm Türkçe harfler PDF'e gömülür, her görüntüleyicide doğru çıkar.
 *
 * HİBRİT font seçimi (FopFonts ile aynı mantık): sistemde Times New Roman / Arial
 * varsa onlar gömülür; yoksa uygulamayla gelen Liberation Serif/Sans (Contents/app/
 * fopfonts) gömülür. Tümü try/catch ile sarılı: hata olursa iText'in eski davranışına
 * (base-14 Helvetica) düşülür, dönüştürme kırılmaz.
 */
public final class ITextFonts {

    private static final String SUP = "/System/Library/Fonts/Supplemental/";

    private ITextFonts() {}

    /** editor.b.c.awtToPdf(...) yerine: AWT font → gömülü Identity-H BaseFont. */
    public static BaseFont map(Font f) {
        try {
            boolean bold   = f != null && f.isBold();
            boolean italic = f != null && f.isItalic();
            boolean sans   = isSans(f);
            File ttf = pick(sans, bold, italic);
            if (ttf != null) {
                // 3 argümanlı createFont cached=true kullanır → belgeler arası güvenli yeniden kullanım.
                return BaseFont.createFont(ttf.getAbsolutePath(), BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
        } catch (Throwable ignore) {
            // düş → fallback
        }
        return fallback();
    }

    /** Font/metrik bulunamazsa iText'in eski (base-14) davranışı. */
    private static BaseFont fallback() {
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Throwable t) {
            return null;
        }
    }

    /** AWT font sans mı? (açık sans adları → sans; aksi halde serif = UYAP standardı Times). */
    private static boolean isSans(Font f) {
        if (f == null) return false;
        String n = (f.getFamily() + " " + f.getName() + " " + f.getFontName()).toLowerCase();
        return n.contains("arial") || n.contains("helvetica") || n.contains("sans")
            || n.contains("tahoma") || n.contains("verdana") || n.contains("calibri")
            || n.contains("dialog") || n.contains("segoe");
    }

    /** Hibrit: sistem fontu (Times/Arial) varsa onu, yoksa pakete gömülü Liberation'ı seçer. */
    private static File pick(boolean sans, boolean bold, boolean italic) {
        File dir = bundleFopFontsDir();
        String sysName, libName;
        if (sans) {
            sysName = "Arial" + sysStyle(bold, italic) + ".ttf";
            libName = "LiberationSans-" + libStyle(bold, italic) + ".ttf";
        } else {
            sysName = "Times New Roman" + sysStyle(bold, italic) + ".ttf";
            libName = "LiberationSerif-" + libStyle(bold, italic) + ".ttf";
        }
        File sys = new File(SUP + sysName);
        if (sys.isFile()) return sys;
        if (dir != null) {
            File lib = new File(dir, libName);
            if (lib.isFile()) return lib;
        }
        return null;
    }

    private static String sysStyle(boolean bold, boolean italic) {
        if (bold && italic) return " Bold Italic";
        if (bold)           return " Bold";
        if (italic)         return " Italic";
        return "";
    }

    private static String libStyle(boolean bold, boolean italic) {
        if (bold && italic) return "BoldItalic";
        if (bold)           return "Bold";
        if (italic)         return "Italic";
        return "Regular";
    }

    /** Çalışan editor-app.jar'ın yanındaki Contents/app/fopfonts dizini. */
    private static File bundleFopFontsDir() {
        try {
            File self = new File(ITextFonts.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File base = self.getParentFile();   // .../Contents/app
            if (base == null) return null;
            return new File(base, "fopfonts");
        } catch (Throwable t) {
            return null;
        }
    }
}
