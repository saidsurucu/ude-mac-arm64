package macosskin;

import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Koyu modda ikon aydınlatma (SKIN=1).
 * İkon seti açık tema için tasarlandı (koyu lacivert monokrom glifler);
 * koyu zeminde görünmez oluyorlar. Düşük doygunluk + düşük parlaklık
 * pikselleri açık griye çekilir; renkli vurgular (doygun pikseller)
 * ve alpha korunur. Yalnız macOS koyu görünümde devreye girer.
 */
public final class IconDarken {
    private IconDarken() {}

    public static javax.swing.ImageIcon apply(javax.swing.ImageIcon icon) {
        if (icon == null || !DarkMode.isDark()) return icon;
        try {
            Image img = icon.getImage();
            if (img instanceof BaseMultiResolutionImage) {
                BaseMultiResolutionImage mr = (BaseMultiResolutionImage) img;
                List<Image> out = new ArrayList<>();
                for (Image v : mr.getResolutionVariants()) {
                    out.add(lighten(v));
                }
                return new javax.swing.ImageIcon(
                    new BaseMultiResolutionImage(out.toArray(new Image[0])));
            }
            return new javax.swing.ImageIcon(lighten(img));
        } catch (Throwable t) {
            return icon;
        }
    }

    private static Image lighten(Image src) {
        int w = src.getWidth(null), h = src.getHeight(null);
        if (w <= 0 || h <= 0) return src;
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = bi.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = bi.getRGB(x, y);
                int a = (argb >>> 24);
                if (a == 0) continue;
                int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b2 = argb & 0xFF;
                float[] hsb = java.awt.Color.RGBtoHSB(r, gg, b2, null);
                if (hsb[1] < 0.35f && hsb[2] < 0.6f) {
                    // koyu nötr glif -> açık gri (parlaklık ters çevrilir, hafif kısılır)
                    int v = Math.round((1f - hsb[2]) * 205f) + 50; // 50..255
                    if (v > 235) v = 235;
                    int rgb = (v << 16) | (v << 8) | v;
                    bi.setRGB(x, y, (a << 24) | rgb);
                }
            }
        }
        return bi;
    }
}
