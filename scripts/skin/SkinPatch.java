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
                              + "  if (__v != null && (__v.getRGB() == -13854290 || __v.getRGB() == -1775637)) {"
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
