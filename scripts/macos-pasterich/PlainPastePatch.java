import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Sağ tık menüsüne "Formatsız Yapıştır" ekler (build-zamanı bytecode yaması).
 *
 * Editörün sağ tık menüsü UDE'den DEĞİL, Substance/lafwidget'ten gelir:
 * org.jvnet.lafwidget.text.EditContextMenuWidget$1.handleMouseEvent bir JPopupMenu
 * kurar (Kes/Kopyala/Yapıştır/——/Sil/Tümünü Seç) ve JPopupMenu.show ile gösterir.
 * Bu sınıf OBFUSCATE DEĞİL → isimle hedeflenir.
 *
 * Yama: handleMouseEvent içindeki JPopupMenu.show(comp,x,y) çağrısı, önce
 * macospasterich.PlainPaste.addMenuItem(popup, comp) çağrılacak şekilde sarılır
 * ($0=popup, $1=comp). addMenuItem öğeyi indeks 3'e (Yapıştır'dan hemen sonra,
 * ayraçtan önce) ekler. Menü yapısı aynı bytecode'da sabit olduğundan güvenli.
 *
 * ÖN KOŞUL: macospasterich.PlainPaste sınıfı ÖNCE jar'a enjekte edilmiş olmalı
 * (apply_pasterich → apply_plainpaste sırası).
 *
 * Argümanlar: <editor-app.jar> <out-dir>
 */
public class PlainPastePatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: PlainPastePatch <jar> <out-dir>");
            System.exit(2);
        }
        String jar = args[0];
        File outDir = new File(args[1]);

        ClassPool pool = ClassPool.getDefault();
        pool.insertClassPath(jar);

        CtClass inner = pool.get("org.jvnet.lafwidget.text.EditContextMenuWidget$1");
        CtMethod hm = inner.getDeclaredMethod("handleMouseEvent");

        // İdempotans: zaten PlainPaste.addMenuItem çağrısı varsa atla.
        final boolean[] already = { false };
        hm.instrument(new ExprEditor() {
            public void edit(MethodCall mc) {
                if (mc.getClassName().equals("macospasterich.PlainPaste")
                        && mc.getMethodName().equals("addMenuItem")) already[0] = true;
            }
        });
        if (already[0]) {
            System.out.println("[PlainPastePatch] zaten yamalı, atlandı.");
            return;
        }

        hm.instrument(new ExprEditor() {
            public void edit(MethodCall mc) throws CannotCompileException {
                if (mc.getClassName().equals("javax.swing.JPopupMenu")
                        && mc.getMethodName().equals("show")) {
                    mc.replace("{ macospasterich.PlainPaste.addMenuItem($0, $1); $proceed($$); }");
                }
            }
        });

        writeClass(inner, outDir);
        System.out.println("[PlainPastePatch] sağ tık 'Formatsız Yapıştır' öğesi enjekte edildi.");
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
