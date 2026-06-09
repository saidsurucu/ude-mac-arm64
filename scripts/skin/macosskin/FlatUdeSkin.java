package macosskin;

import org.jvnet.substance.painter.border.FlatBorderPainter;
import org.jvnet.substance.painter.gradient.FlatGradientPainter;
import org.jvnet.substance.skin.NebulaSkin;

/**
 * UDE modern düz açık skin (SKIN=1).
 * Nebula tabanlı: super() tüm zorunlu skin alanlarını (buttonShaper,
 * decorationPainter, watermark, geçerli şema bundle'ları) kurar.
 * Biz yalnızca gradient/border painter'ları DÜZ (gradient'siz) yaparız.
 * Grafit renk şeması Task 3'te eklenir.
 */
public class FlatUdeSkin extends NebulaSkin {
    /** setSkin(String) sarmasında yeniden-giriş koruması için. */
    public static boolean installing = false;

    public FlatUdeSkin() {
        super();
        this.gradientPainter = new FlatGradientPainter();
        this.borderPainter = new FlatBorderPainter();
        this.highlightBorderPainter = new FlatBorderPainter();
    }

    public String getDisplayName() {
        return "FlatUde";
    }
}
