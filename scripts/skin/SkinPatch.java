import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UDE modern skin (SKIN=1) build-zamanı bytecode yamaları.
 * Bu aşamada: SubstanceLookAndFeel.setSkin(String) çağrıldığında
 * bizim FlatUdeSkin'i kur (UDE hangi skin adını verirse versin).
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class SkinPatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: SkinPatch <jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        // setSkin(String) -> bizim skin'i kur (yeniden-giriş korumalı)
        CtClass slaf = pool.get("org.jvnet.substance.SubstanceLookAndFeel");
        CtMethod setSkinStr = slaf.getMethod("setSkin", "(Ljava/lang/String;)Z");
        setSkinStr.insertBefore(
            "{ macosskin.DarkMode.trace(\"setSkin cagrildi arg=\" + $1 + \" installing=\" + macosskin.FlatUdeSkin.installing);"
          + "  if (!macosskin.FlatUdeSkin.installing) {"
          + "    macosskin.FlatUdeSkin.installing = true;"
          + "    try {"
          + "      org.jvnet.substance.api.SubstanceSkin __skin ="
          + "        macosskin.DarkMode.isDark()"
          + "          ? (org.jvnet.substance.api.SubstanceSkin) new macosskin.FlatUdeDarkSkin()"
          + "          : (org.jvnet.substance.api.SubstanceSkin) new macosskin.FlatUdeSkin();"
          + "      boolean __ok = org.jvnet.substance.SubstanceLookAndFeel.setSkin(__skin);"
          + "      javax.swing.UIManager.put(\"ScrollBarUI\", \"com.apple.laf.AquaScrollBarUI\");"
          + "      javax.swing.UIManager.put(\"SliderUI\", \"com.apple.laf.AquaSliderUI\");"
          + "      macosskin.WordTooltip.install();"
          + "      macosskin.WordCombo.install();"
          + "      macosskin.DarkMode.trace(\"skin kuruldu ok=\" + __ok + \" dark=\" + macosskin.DarkMode.isDark());"
          + "      try {"
          + "        org.jvnet.substance.fonts.FontSet __base ="
          + "          org.jvnet.substance.SubstanceLookAndFeel.getFontPolicy().getFontSet(\"Substance\", null);"
          + "        org.jvnet.substance.SubstanceLookAndFeel.setFontPolicy(new macosskin.FlatFontPolicy(__base));"
          + "      } catch (Throwable __ft) { System.err.println(\"[FlatUdeSkin] font policy failed: \" + __ft); }"
          + "      return __ok;"
          + "    } catch (Throwable __t) {"
          + "      macosskin.DarkMode.trace(\"skin install HATA: \" + __t);"
          + "      System.err.println(\"[FlatUdeSkin] skin install failed, kept original: \" + __t);"
          + "    } finally { macosskin.FlatUdeSkin.installing = false; }"
          + "  } }");
        writeClass(slaf, outDir);
        System.out.println("[SkinPatch] setSkin(String) -> FlatUdeSkin sarması uygulandı.");

        // Açılışta skin GARANTİLİ kurulur. UDE Substance'ı yalnızca
        // initValues/menuTheme tercihi doluysa kurar (au -> an.a(String) -> setSkin);
        // tema ayarındaki "standart" seçeneği bu tercihi SİLER ve uygulama kalıcı
        // olarak Aqua'ya düşer (bizim skin combo'da eşleşmediği için bu kolay
        // tetiklenir). Bu yüzden tercihten bağımsız olarak, UI başlangıcında
        // (WPAppManager.main -> invokeLater(new aF(args)), EDT) skin kurulur.
        // setSkin(SubstanceSkin) LAF Substance değilse UIManager.setLookAndFeel'i
        // kendisi çağırır (bytecode'dan doğrulandı).
        CtClass aF = pool.get("tr.com.havelsan.uyap.system.editor.common.aF");
        aF.getMethod("run", "()V").insertBefore(
            "{ try {"
          + "    if (!(javax.swing.UIManager.getLookAndFeel() instanceof org.jvnet.substance.SubstanceLookAndFeel)) {"
          + "      macosskin.FlatUdeSkin.installing = true;"
          + "      try {"
          + "        org.jvnet.substance.api.SubstanceSkin __skin ="
          + "          macosskin.DarkMode.isDark()"
          + "            ? (org.jvnet.substance.api.SubstanceSkin) new macosskin.FlatUdeDarkSkin()"
          + "            : (org.jvnet.substance.api.SubstanceSkin) new macosskin.FlatUdeSkin();"
          + "        org.jvnet.substance.SubstanceLookAndFeel.setSkin(__skin);"
          + "        javax.swing.UIManager.put(\"ScrollBarUI\", \"com.apple.laf.AquaScrollBarUI\");"
          + "      javax.swing.UIManager.put(\"SliderUI\", \"com.apple.laf.AquaSliderUI\");"
          + "        macosskin.WordTooltip.install();"
          + "        macosskin.WordCombo.install();"
          + "        try {"
          + "          org.jvnet.substance.fonts.FontSet __base ="
          + "            org.jvnet.substance.SubstanceLookAndFeel.getFontPolicy().getFontSet(\"Substance\", null);"
          + "          org.jvnet.substance.SubstanceLookAndFeel.setFontPolicy(new macosskin.FlatFontPolicy(__base));"
          + "        } catch (Throwable __ft) { macosskin.DarkMode.trace(\"acilis font policy: \" + __ft); }"
          + "        macosskin.DarkMode.trace(\"acilis skin kuruldu dark=\" + macosskin.DarkMode.isDark());"
          + "      } finally { macosskin.FlatUdeSkin.installing = false; }"
          + "    }"
          + "  } catch (Throwable __t) { macosskin.DarkMode.trace(\"acilis skin HATA: \" + __t); } }");
        writeClass(aF, outDir);
        System.out.println("[SkinPatch] aF.run() acilis skin kurulumu eklendi.");

        // Editor masaüstü arka planı: wp.p.E = teal(44,153,174) static sabiti.
        // clinit sonrası DarkMode kanvas rengiyle override (açık: nötr gri,
        // koyu: koyu gri; alan public static, final değil).
        try {
            CtClass wpP = pool.get("tr.com.havelsan.uyap.system.swing.wp.p");
            wpP.makeClassInitializer().insertAfter(
                "E = macosskin.DarkMode.canvasColor();");
            writeClass(wpP, outDir);
            System.out.println("[SkinPatch] wp.p.E teal -> nötr gri yaması uygulandı.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: teal yaması atlandı: " + t);
        }

        // Tercihlerden (tercihler.xml) yüklenen eski teal degerini de nötrle:
        // an sinifi pref'i okuyup E alanina putstatic yapar ve clinit varsayilanini ezer.
        // Yalniz eski teal (-13854290) ve bizim acik-gri kalici degerimiz (-1775637)
        // remap edilir; kullanicinin sectigi baska renkler dokunulmaz.
        try {
            CtClass an = pool.get("tr.com.havelsan.uyap.system.editor.common.an");
            an.instrument(new ExprEditor() {
                public void edit(FieldAccess f) throws javassist.CannotCompileException {
                    try {
                        if (f.isWriter() && "E".equals(f.getFieldName())
                                && "tr.com.havelsan.uyap.system.swing.wp.p".equals(
                                    f.getField().getDeclaringClass().getName())) {
                            f.replace(
                                "{ java.awt.Color __v = $1;"
                              + "  if (__v != null && (__v.getRGB() == -13854290 || __v.getRGB() == -1775637"
                              + "      || __v.getRGB() == -14803426 || __v.getRGB() == -13224394)) {"
                              + "    __v = macosskin.DarkMode.canvasColor();"
                              + "  }"
                              + "  $proceed(__v); }");
                        }
                    } catch (javassist.NotFoundException __nf) {
                    }
                }
            });
            writeClass(an, outDir);
            System.out.println("[SkinPatch] an pref-load teal koruması uygulandı.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: pref-load teal koruması atlandı: " + t);
        }

        // Cetvel (gui.eV) Word koyu paleti: rakamlar a()=siyah, tikler b()/c(),
        // işaretçiler d/e statikleri. Koyu modda Word'den ölçülen tonlara
        // kısa devre; açık modda orijinal gövde/alanlar aynen çalışır.
        // Zemin (beyaz setBackground) MacLook agent'ında düzeltilir.
        try {
            CtClass ruler = pool.get("tr.com.havelsan.uyap.system.editor.common.gui.eV");
            ruler.getMethod("a", "()Ljava/awt/Color;").insertBefore(
                "{ if (macosskin.DarkMode.isDark()) return new java.awt.Color(226, 226, 226); }");
            ruler.getMethod("b", "()Ljava/awt/Color;").insertBefore(
                "{ if (macosskin.DarkMode.isDark()) return new java.awt.Color(152, 152, 158, 250); }");
            ruler.getMethod("c", "()Ljava/awt/Color;").insertBefore(
                "{ if (macosskin.DarkMode.isDark()) return new java.awt.Color(152, 152, 158, 150); }");
            ruler.instrument(new ExprEditor() {
                public void edit(FieldAccess f) throws javassist.CannotCompileException {
                    try {
                        if (f.isReader() && "Ljava/awt/Color;".equals(f.getSignature())
                                && ("d".equals(f.getFieldName()) || "e".equals(f.getFieldName()))
                                && "tr.com.havelsan.uyap.system.editor.common.gui.eV".equals(
                                    f.getField().getDeclaringClass().getName())) {
                            f.replace(
                                "{ $_ = macosskin.DarkMode.isDark()"
                              + "    ? new java.awt.Color(220, 221, 221)"
                              + "    : ($r) $proceed(); }");
                        }
                        if (f.isReader() && "LIGHT_GRAY".equals(f.getFieldName())
                                && "java.awt.Color".equals(
                                    f.getField().getDeclaringClass().getName())) {
                            f.replace(
                                "{ $_ = macosskin.DarkMode.isDark()"
                              + "    ? new java.awt.Color(38, 38, 38)"
                              + "    : ($r) $proceed(); }");
                        }
                    } catch (javassist.NotFoundException __nf) {
                    }
                }
            });
            writeClass(ruler, outDir);
            System.out.println("[SkinPatch] cetvel renkleri koyu moda uyarlandı.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: cetvel yaması atlandı: " + t);
        }

        // Flamingo şerit sadeleştirme: grup başlık bandı ve çerçevesi kaldırılır
        // ("Pano/Font/Paragraf" kutuları 2007 Office izi). Flamingo obfuscate
        // değil; metot imzaları javap ile doğrulandı.
        try {
            CtClass bandUi = pool.get(
                "org.pushingpixels.flamingo.internal.ui.ribbon.BasicRibbonBandUI");
            bandUi.getMethod("paintBandTitle",
                "(Ljava/awt/Graphics;Ljava/awt/Rectangle;Ljava/lang/String;)V")
                .setBody("{ }");
            bandUi.getMethod("paintBandTitleBackground",
                "(Ljava/awt/Graphics;Ljava/awt/Rectangle;Ljava/lang/String;)V")
                .setBody("{ }");
            writeClass(bandUi, outDir);
            System.out.println("[SkinPatch] Flamingo band başlığı/çerçevesi kaldırıldı.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: Flamingo band yaması atlandı: " + t);
        }

        // Flamingo kontur rengi: getBorderColor() UIManager
        // TextField.inactiveForeground okur; koyu şemada bu AÇIK renktir ->
        // aktif sekme bembeyaz çerçeveli görünür. Tema-duyarlı grafit döndür.
        try {
            CtClass fu = pool.get(
                "org.pushingpixels.flamingo.internal.utils.FlamingoUtilities");
            fu.getMethod("getBorderColor", "()Ljava/awt/Color;")
                .setBody(
                    "{ return macosskin.DarkMode.isDark()"
                  + "    ? new java.awt.Color(74, 74, 74)"
                  + "    : new java.awt.Color(186, 192, 200); }");
            writeClass(fu, outDir);
            System.out.println("[SkinPatch] Flamingo kontur rengi grafite çekildi.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: kontur rengi yaması atlandı: " + t);
        }

        // Grup kutu KENARLIĞI da kalksın: RoundBorder.paintBorder no-op
        // (insets korunur, yalnız çizim gider; gruplar boşlukla ayrılır).
        try {
            CtClass rb = pool.get(
                "org.pushingpixels.flamingo.internal.ui.ribbon.BasicRibbonBandUI$RoundBorder");
            rb.getMethod("paintBorder",
                "(Ljava/awt/Component;Ljava/awt/Graphics;IIII)V")
                .setBody("{ }");
            writeClass(rb, outDir);
            System.out.println("[SkinPatch] Flamingo grup kenarlığı kaldırıldı.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: grup kenarlık yaması atlandı: " + t);
        }

        // Şerit komut butonları Word tarzı: seçili/hover/basılı durumda yuvarlak
        // köşeli düz dolgu (Word ölçümü: seçili #474747, hover #3D3D3D koyu modda).
        // Normal durumda arka plan YOK. Orb ve sekme butonları kendi override'larını
        // koruduğundan etkilenmez.
        try {
            CtClass cmdUi = pool.get(
                "org.pushingpixels.flamingo.internal.ui.common.BasicCommandButtonUI");
            cmdUi.getMethod("paintButtonBackground",
                "(Ljava/awt/Graphics;Ljava/awt/Rectangle;)V")
                .setBody(
                    "{ javax.swing.ButtonModel __m = this.commandButton.getActionModel();"
                  + "  boolean __press = __m.isArmed() || __m.isPressed();"
                  + "  boolean __sel = __m.isSelected();"
                  + "  boolean __roll = __m.isRollover();"
                  + "  if (__press || __sel || __roll) {"
                  + "    java.awt.Graphics2D __g = (java.awt.Graphics2D) $1.create();"
                  + "    __g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,"
                  + "        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);"
                  + "    boolean __dark = macosskin.DarkMode.isDark();"
                  + "    java.awt.Color __c = __press"
                  + "        ? (__dark ? new java.awt.Color(81, 81, 81) : new java.awt.Color(196, 196, 196))"
                  + "        : (__sel"
                  + "            ? (__dark ? new java.awt.Color(71, 71, 71) : new java.awt.Color(208, 208, 208))"
                  + "            : (__dark ? new java.awt.Color(61, 61, 61) : new java.awt.Color(224, 224, 224)));"
                  + "    __g.setColor(__c);"
                  + "    __g.fillRoundRect($2.x, $2.y, $2.width, $2.height, 8, 8);"
                  + "    __g.dispose();"
                  + "  } }");
            cmdUi.getMethod("paintButtonBackground",
                "(Ljava/awt/Graphics;Ljava/awt/Rectangle;[Ljavax/swing/ButtonModel;)V")
                .setBody(
                    "{ boolean __sel = false; boolean __roll = false; boolean __press = false;"
                  + "  for (int __i = 0; __i < $3.length; __i++) {"
                  + "    javax.swing.ButtonModel __m = $3[__i];"
                  + "    if (__m == null) continue;"
                  + "    if (__m.isSelected()) __sel = true;"
                  + "    if (__m.isRollover()) __roll = true;"
                  + "    if (__m.isArmed() || __m.isPressed()) __press = true;"
                  + "  }"
                  + "  if (__press || __sel || __roll) {"
                  + "    java.awt.Graphics2D __g = (java.awt.Graphics2D) $1.create();"
                  + "    __g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,"
                  + "        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);"
                  + "    boolean __dark = macosskin.DarkMode.isDark();"
                  + "    java.awt.Color __c = __press"
                  + "        ? (__dark ? new java.awt.Color(81, 81, 81) : new java.awt.Color(196, 196, 196))"
                  + "        : (__sel"
                  + "            ? (__dark ? new java.awt.Color(71, 71, 71) : new java.awt.Color(208, 208, 208))"
                  + "            : (__dark ? new java.awt.Color(61, 61, 61) : new java.awt.Color(224, 224, 224)));"
                  + "    __g.setColor(__c);"
                  + "    __g.fillRoundRect($2.x, $2.y, $2.width, $2.height, 8, 8);"
                  + "    __g.dispose();"
                  + "  } }");
            writeClass(cmdUi, outDir);
            System.out.println("[SkinPatch] komut buton durum dolguları Word tarzı yapıldı.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: komut buton dolgusu atlandı: " + t);
        }

        // Koyu modda ikon aydınlatma: Utils.b(String) ikon yükleyicisinin
        // dönüşünü IconDarken'dan geçir (apply_icons'un multi-res sarması bu
        // noktada jar'da; biz onun ÜSTÜNE eklenir). Açık modda no-op.
        try {
            CtClass utils = pool.get("tr.com.havelsan.uyap.system.editor.common.Utils");
            utils.getMethod("b", "(Ljava/lang/String;)Ljavax/swing/ImageIcon;")
                .insertAfter("{ $_ = macosskin.IconDarken.apply($_); }");
            utils.getMethod("a", "(Ljava/lang/String;)Ljavax/swing/ImageIcon;")
                .insertAfter("{ $_ = macosskin.IconDarken.apply($_); }");
            utils.getMethod("a", "(Ljava/lang/String;I)Ljavax/swing/ImageIcon;")
                .insertAfter("{ $_ = macosskin.IconDarken.apply($_); }");
            writeClass(utils, outDir);
            System.out.println("[SkinPatch] koyu mod ikon aydınlatma eklendi (b, a, a-int).");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: ikon aydınlatma atlandı: " + t);
        }

        // Sekme alanı Word tarzı: Office-2007'nin tam-genişlik çizgisi ve seçili
        // sekme konturu kalkar (paintTaskArea no-op); seçili sekme, metin altında
        // kısa yuvarlak çubukla vurgulanır (koyu: #E7E7E7, açık: koyu gri).
        try {
            CtClass ribbonUi = pool.get(
                "org.pushingpixels.flamingo.internal.ui.ribbon.BasicRibbonUI");
            ribbonUi.getMethod("paintTaskArea", "(Ljava/awt/Graphics;IIII)V")
                .setBody("{ }");
            writeClass(ribbonUi, outDir);
            System.out.println("[SkinPatch] sekme alanı çizgisi/konturu kaldırıldı.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: sekme alanı yaması atlandı: " + t);
        }
        try {
            CtClass tabUi = pool.get(
                "org.pushingpixels.flamingo.internal.ui.ribbon.BasicRibbonTaskToggleButtonUI");
            tabUi.getMethod("paintButtonBackground",
                "(Ljava/awt/Graphics;Ljava/awt/Rectangle;)V")
                .setBody(
                    "{ if (this.commandButton.getActionModel().isSelected()) {"
                  + "    int __vis = $2.height;"
                  + "    java.awt.Container __par = this.commandButton.getParent();"
                  + "    if (__par != null) {"
                  + "      int __pb = __par.getHeight() - this.commandButton.getY();"
                  + "      if (__pb > 0 && __pb < __vis) __vis = __pb;"
                  + "    }"
                  + "    java.awt.Graphics2D __g = (java.awt.Graphics2D) $1.create();"
                  + "    __g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,"
                  + "        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);"
                  + "    __g.setColor(macosskin.DarkMode.isDark()"
                  + "        ? new java.awt.Color(231, 231, 231)"
                  + "        : new java.awt.Color(60, 60, 60));"
                  + "    int __w = $2.width;"
                  + "    int __bw = Math.max(18, __w - 26);"
                  + "    int __bh = 3;"
                  + "    __g.fillRoundRect($2.x + (__w - __bw) / 2,"
                  + "        $2.y + __vis - __bh, __bw, __bh, __bh, __bh);"
                  + "    __g.dispose();"
                  + "    macosskin.DarkMode.trace(\"tabBar \" + this.commandButton.getText()"
                  + "        + \" vis=\" + __vis + \" h=\" + $2.height);"
                  + "  } }");
            writeClass(tabUi, outDir);
            System.out.println("[SkinPatch] seçili sekme alt çubuğu eklendi.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: sekme alt çubuğu atlandı: " + t);
        }

        // Orb (uygulama menü düğmesi) arka plan efektini düzleştir: görsel asset
        // (resources/ude.png) çizilir, arkasındaki degrade/parlama çizimi kalkar.
        try {
            CtClass orbUi = pool.get(
                "org.pushingpixels.flamingo.internal.ui.ribbon.appmenu.BasicRibbonApplicationMenuButtonUI");
            orbUi.getMethod("paintButtonBackground",
                "(Ljava/awt/Graphics;Ljava/awt/Rectangle;)V")
                .setBody("{ }");
            writeClass(orbUi, outDir);
            System.out.println("[SkinPatch] Orb arka plan efekti düzleştirildi.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: orb arka plan yaması atlandı: " + t);
        }

        // Zengin tooltip (RichTooltip) Word tarzı: paintBackground hardcoded
        // AÇIK gradyan basar (Label.disabledForeground.brighter() 0.9/0.4) ->
        // koyu modda açık metin + açık zemin = okunmaz. Tema-duyarlı düz dolgu
        // (Word ölçümü: koyu #2E3032, açık beyaz); metin renkleri temadan,
        // kontur getBorderColor() yamasından zaten doğru. Substance'ın kendi
        // RichTooltip delegate'i yok, Basic'i yamalamak yeterli.
        try {
            CtClass rtUi = pool.get(
                "org.pushingpixels.flamingo.internal.ui.common.BasicRichTooltipPanelUI");
            rtUi.getMethod("paintBackground", "(Ljava/awt/Graphics;)V")
                .setBody(
                    "{ java.awt.Graphics2D __g = (java.awt.Graphics2D) $1.create();"
                  + "  __g.setColor(macosskin.DarkMode.isDark()"
                  + "      ? new java.awt.Color(46, 48, 50)"
                  + "      : java.awt.Color.WHITE);"
                  + "  __g.fillRect(0, 0, this.richTooltipPanel.getWidth(),"
                  + "      this.richTooltipPanel.getHeight());"
                  + "  __g.dispose(); }");
            writeClass(rtUi, outDir);
            System.out.println("[SkinPatch] zengin tooltip Word tarzı düz dolguya çekildi.");
        } catch (Throwable t) {
            System.out.println("[SkinPatch] UYARI: zengin tooltip yaması atlandı: " + t);
        }
    }

    private static void writeClass(CtClass cc, File outDir) throws Exception {
        byte[] code = cc.toBytecode();
        File f = new File(outDir, cc.getName().replace('.', '/') + ".class");
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(code);
        }
    }
}
