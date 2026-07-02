package macoslinespacing;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Giriş sekmesi "Paragraf" bandına Word-tarzı Satır Aralığı dropdown'u.
 * Flamingo'ya derleme bağımlılığı YOK — tamamı yansıma (agent app-cp'siz).
 * Kurulum FOCUS_GAINED'de idempotan (footnote ensureRibbon deseni): bileşen
 * ağacı yerine ribbon MODELİNDEN band bulunur (darkpage dersi).
 */
public final class LineSpacingMenu {

    private static final String GUARD = "macoslinespacing.btn";
    /** Son odaklanan UDE editörü — menü eylemi popup odağı çalmışken bile hedefi bilir. */
    private static WeakReference<JTextComponent> lastEditor = new WeakReference<>(null);

    private LineSpacingMenu() {}

    public static void install() {
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                @Override public void eventDispatched(AWTEvent e) {
                    if (e.getID() != FocusEvent.FOCUS_GAINED) return;
                    Object src = e.getSource();
                    if (!(src instanceof JTextComponent)) return;
                    JTextComponent tc = (JTextComponent) src;
                    if (isEditor(tc)) lastEditor = new WeakReference<>(tc);
                    java.awt.Window w = SwingUtilities.getWindowAncestor(tc);
                    if (w instanceof JFrame) {
                        try { ensureRibbon((JFrame) w); }
                        catch (Throwable t) { LsLog.log("ensureRibbon: " + t); }
                    }
                }
            }, AWTEvent.FOCUS_EVENT_MASK);
            LsLog.log("install tamam");
        } catch (Throwable t) {
            LsLog.log("install: " + t);
        }
    }

    /** UDE belge editörü mü? (MacShortcutRemap.isEditor deseni) */
    private static boolean isEditor(Component c) {
        for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals("tr.com.havelsan.uyap.system.swing.wp.a.f")
                    || n.equals("tr.com.havelsan.uyap.system.editor.common.text.hj")) return true;
        }
        return false;
    }

    /** Giriş > Paragraf bandına butonu bir kez ekler (clientProperty guard). */
    private static void ensureRibbon(JFrame f) throws Exception {
        Component ribbon = findByClassName(f, "JRibbon");
        if (ribbon == null) return;
        JComponent r = (JComponent) ribbon;
        if (Boolean.TRUE.equals(r.getClientProperty(GUARD))) return;

        Object targetBand = null;
        int taskCount = (Integer) ribbon.getClass().getMethod("getTaskCount").invoke(ribbon);
        for (int i = 0; i < taskCount && targetBand == null; i++) {
            Object task = ribbon.getClass().getMethod("getTask", int.class).invoke(ribbon, i);
            String tTitle = (String) task.getClass().getMethod("getTitle").invoke(task);
            if (!"Giriş".equals(tTitle)) continue;
            java.util.List<?> bands = (java.util.List<?>)
                    task.getClass().getMethod("getBands").invoke(task);
            for (Object band : bands) {
                String bTitle = (String) band.getClass().getMethod("getTitle").invoke(band);
                if ("Paragraf".equals(bTitle)) { targetBand = band; break; }
            }
        }
        if (targetBand == null) { LsLog.log("Paragraf bandı bulunamadı"); return; }

        ClassLoader cl = ribbon.getClass().getClassLoader();
        Class<?> riCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.icon.ResizableIcon", false, cl);
        Object icon = makeIcon(riCls);

        Class<?> btnCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.JCommandButton", false, cl);
        Object btn = btnCls.getConstructor(String.class, riCls)
                .newInstance("Satır Aralığı", icon);

        Class<?> kindCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.JCommandButton$CommandButtonKind",
                false, cl);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object popupOnly = Enum.valueOf((Class<? extends Enum>) kindCls, "POPUP_ONLY");
        btnCls.getMethod("setCommandButtonKind", kindCls).invoke(btn, popupOnly);
        ((JComponent) btn).setToolTipText("Satırlar arasındaki mesafeyi ayarlar");

        Class<?> cbCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.popup.PopupPanelCallback", false, cl);
        Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{cbCls},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                        if ("getPopupPanel".equals(m.getName())) {
                            try { return buildPopup(cl); }
                            catch (Throwable t) { LsLog.log("buildPopup: " + t); return null; }
                        }
                        if ("equals".equals(m.getName())) return p == a[0];
                        if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                        return null;
                    }
                });
        btnCls.getMethod("setPopupCallback", cbCls).invoke(btn, callback);

        Class<?> prioCls = Class.forName(
                "org.pushingpixels.flamingo.api.ribbon.RibbonElementPriority", false, cl);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object medium = Enum.valueOf((Class<? extends Enum>) prioCls, "MEDIUM");
        Class<?> acbCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.AbstractCommandButton", false, cl);
        targetBand.getClass().getMethod("addCommandButton", acbCls, prioCls)
                .invoke(targetBand, btn, medium);
        r.putClientProperty(GUARD, Boolean.TRUE);
        LsLog.log("buton eklendi (Giriş > Paragraf)");
    }

    /** Popup menü: 6 seçenek; mevcut değer "✓ " önekiyle işaretli. */
    private static Object buildPopup(ClassLoader cl) throws Exception {
        Class<?> menuCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.popup.JCommandPopupMenu", false, cl);
        Object menu = menuCls.getConstructor().newInstance();
        Class<?> itemCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.JCommandMenuButton", false, cl);
        Class<?> riCls = Class.forName(
                "org.pushingpixels.flamingo.api.common.icon.ResizableIcon", false, cl);

        JTextComponent editor = lastEditor.get();
        float cur = (editor != null) ? LineSpacingApply.current(editor) : 1.0f;

        for (int i = 0; i < LineSpacingApply.VALUES.length; i++) {
            final float value = LineSpacingApply.VALUES[i];
            String label = (LineSpacingApply.matches(cur, value) ? "✓ " : "   ")
                    + LineSpacingApply.LABELS[i];
            Object item = itemCls.getConstructor(String.class, riCls)
                    .newInstance(label, null);
            ActionListener al = ev -> {
                try {
                    JTextComponent tc = lastEditor.get();
                    if (tc != null && tc.isShowing()) {
                        boolean ok = LineSpacingApply.apply(tc, value);
                        LsLog.log("uygula " + value + " ok=" + ok
                                + " sel=[" + tc.getSelectionStart() + "," + tc.getSelectionEnd() + "]");
                        tc.requestFocusInWindow(); // odak editöre dönsün (renk modu dersi)
                    } else {
                        LsLog.log("uygula " + value + ": editör yok");
                    }
                    hidePopups(cl);
                } catch (Throwable t) { LsLog.log("uygula: " + t); }
            };
            itemCls.getMethod("addActionListener", ActionListener.class).invoke(item, al);
            menuCls.getMethod("addMenuButton", itemCls).invoke(menu, item);
        }
        return menu;
    }

    private static void hidePopups(ClassLoader cl) {
        try {
            Class<?> pm = Class.forName(
                    "org.pushingpixels.flamingo.api.common.popup.PopupPanelManager", false, cl);
            Object mgr = pm.getMethod("defaultManager").invoke(null);
            pm.getMethod("hidePopups", Component.class).invoke(mgr, (Component) null);
        } catch (Throwable t) { LsLog.log("hidePopups: " + t); }
    }

    /** Vektör ikon: iki yönlü dikey ok + metin satırları (Word glyph'i).
     *  Proxy ile ResizableIcon (footnote deseni) — her DPI'de keskin; renk
     *  bileşenin foreground'ından → koyu modda kendiliğinden uyar. */
    private static Object makeIcon(Class<?> riCls) {
        final Dimension[] dim = { new Dimension(32, 32) };
        return Proxy.newProxyInstance(riCls.getClassLoader(), new Class<?>[]{riCls},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        switch (m.getName()) {
                            case "setDimension": dim[0] = (Dimension) a[0]; return null;
                            case "getIconWidth": return dim[0].width;
                            case "getIconHeight": return dim[0].height;
                            case "paintIcon":
                                paintGlyph((Component) a[0], (Graphics) a[1],
                                        (Integer) a[2], (Integer) a[3], dim[0]);
                                return null;
                            case "equals": return p == a[0];
                            case "hashCode": return System.identityHashCode(p);
                            default: return null;
                        }
                    }
                });
    }

    private static void paintGlyph(Component c, Graphics g, int x, int y, Dimension d) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = (c != null && c.getForeground() != null)
                    ? c.getForeground() : new Color(0x44, 0x44, 0x44);
            g2.setColor(fg);
            int w = d.width, h = d.height;
            int pad = Math.max(2, w / 8);
            int ax = x + pad + 2;               // ok sütunu
            int tx = x + w / 2 - 1;             // metin çizgileri başlangıcı
            int top = y + pad, bot = y + h - pad;
            // dikey çift ok
            g2.drawLine(ax, top, ax, bot);
            g2.drawLine(ax - 3, top + 3, ax, top);
            g2.drawLine(ax + 3, top + 3, ax, top);
            g2.drawLine(ax - 3, bot - 3, ax, bot);
            g2.drawLine(ax + 3, bot - 3, ax, bot);
            // metin satırları (4 çizgi, eşit aralık)
            int lines = 4;
            for (int i = 0; i < lines; i++) {
                int ly = top + (bot - top) * i / (lines - 1);
                g2.drawLine(tx, ly, x + w - pad, ly);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Basit sınıf-adı araması (MacLook.findByClassName deseni). */
    private static Component findByClassName(Component root, String simpleName) {
        if (root.getClass().getSimpleName().equals(simpleName)) return root;
        if (root instanceof Container) {
            for (Component k : ((Container) root).getComponents()) {
                Component hit = findByClassName(k, simpleName);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
