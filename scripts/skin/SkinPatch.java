import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

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
            "{ if (!macosskin.FlatUdeSkin.installing) {"
          + "    macosskin.FlatUdeSkin.installing = true;"
          + "    try {"
          + "      return org.jvnet.substance.SubstanceLookAndFeel.setSkin(new macosskin.FlatUdeSkin());"
          + "    } catch (Throwable __t) {"
          + "      System.err.println(\"[FlatUdeSkin] skin install failed, kept original: \" + __t);"
          + "    } finally { macosskin.FlatUdeSkin.installing = false; }"
          + "  } }");
        writeClass(slaf, outDir);
        System.out.println("[SkinPatch] setSkin(String) -> FlatUdeSkin sarması uygulandı.");
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
