package macosskin;

import org.jvnet.substance.painter.border.FlatBorderPainter;
import org.jvnet.substance.painter.gradient.FlatGradientPainter;
import org.jvnet.substance.skin.NebulaSkin;

/**
 * UDE modern düz açık skin (SKIN=1).
 * Nebula tabanlı: super() tüm zorunlu skin alanlarını (buttonShaper,
 * decorationPainter, watermark, geçerli şema bundle'ları) kurar.
 * Biz yalnızca gradient/border painter'ları DÜZ (gradient'siz) yaparız.
 * Grafit renk şeması eklendi (nötr açık + grafit vurgu, NONE alanı).
 */
public class FlatUdeSkin extends NebulaSkin {
    /** setSkin(String) sarmasında yeniden-giriş koruması için. */
    public static boolean installing = false;

    public FlatUdeSkin() {
        super();
        this.gradientPainter = new FlatGradientPainter();
        this.borderPainter = new FlatBorderPainter();
        this.highlightBorderPainter = new FlatBorderPainter();

        // Grafit nötr şema bundle'ı — NONE (varsayılan) alanı için Nebula'nın mavisini override eder
        java.net.URL u = FlatUdeSkin.class.getResource("/macosskin/flatude.colorschemes");
        if (u != null) {
            java.util.Map schemes = org.jvnet.substance.api.SubstanceSkin.getColorSchemes(u);
            if (schemes != null) {
                org.jvnet.substance.api.SubstanceColorScheme active =
                    (org.jvnet.substance.api.SubstanceColorScheme) schemes.get("FlatUde Active");
                org.jvnet.substance.api.SubstanceColorScheme def =
                    (org.jvnet.substance.api.SubstanceColorScheme) schemes.get("FlatUde Default");
                org.jvnet.substance.api.SubstanceColorScheme dis =
                    (org.jvnet.substance.api.SubstanceColorScheme) schemes.get("FlatUde Disabled");
                org.jvnet.substance.api.SubstanceColorScheme rollUnsel =
                    (org.jvnet.substance.api.SubstanceColorScheme) schemes.get("FlatUde Rollover Unselected");
                org.jvnet.substance.api.SubstanceColorScheme rollSel =
                    (org.jvnet.substance.api.SubstanceColorScheme) schemes.get("FlatUde Rollover Selected");
                org.jvnet.substance.api.SubstanceColorScheme pressed =
                    (org.jvnet.substance.api.SubstanceColorScheme) schemes.get("FlatUde Pressed");
                if (active != null && def != null && dis != null
                        && rollUnsel != null && rollSel != null && pressed != null) {
                    org.jvnet.substance.api.SubstanceColorSchemeBundle bundle =
                        new org.jvnet.substance.api.SubstanceColorSchemeBundle(active, def, dis);
                    bundle.registerColorScheme(rollUnsel,
                        org.jvnet.substance.api.ComponentState.ROLLOVER_UNSELECTED);
                    bundle.registerColorScheme(rollSel,
                        org.jvnet.substance.api.ComponentState.ROLLOVER_SELECTED);
                    bundle.registerColorScheme(pressed,
                        org.jvnet.substance.api.ComponentState.PRESSED_SELECTED,
                        org.jvnet.substance.api.ComponentState.PRESSED_UNSELECTED,
                        org.jvnet.substance.api.ComponentState.ARMED,
                        org.jvnet.substance.api.ComponentState.ROLLOVER_ARMED);
                    bundle.registerHighlightColorScheme(rollUnsel, 0.6f,
                        org.jvnet.substance.api.ComponentState.ROLLOVER_UNSELECTED);
                    bundle.registerHighlightColorScheme(active, 0.8f,
                        org.jvnet.substance.api.ComponentState.SELECTED);
                    bundle.registerHighlightColorScheme(rollSel, 0.95f,
                        org.jvnet.substance.api.ComponentState.ROLLOVER_SELECTED);
                    bundle.registerHighlightColorScheme(pressed, 0.8f,
                        org.jvnet.substance.api.ComponentState.ARMED,
                        org.jvnet.substance.api.ComponentState.ROLLOVER_ARMED);
                    this.registerDecorationAreaSchemeBundle(
                        bundle, org.jvnet.substance.painter.decoration.DecorationAreaType.NONE);
                }
            }
        }
    }

    public String getDisplayName() {
        return "FlatUde";
    }
}
