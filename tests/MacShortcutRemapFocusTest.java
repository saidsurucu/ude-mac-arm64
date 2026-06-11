package macostextkeys;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTextField;

/*
 * MacShortcutRemap odak testi: Cmd+V odaktaki metin ALANINA mı, yoksa menüdeki
 * (her zaman editöre işleyen) "Yapıştır" eylemine mi gider?
 *
 * Hata raporu: arama kutusu / e-imza formu odaktayken Cmd+V editöre yapıştırıyor.
 *
 * Çalıştırma (elle, repo kökünden):
 *   javac -encoding UTF-8 -d /tmp/msr-test \
 *     scripts/macos-textkeys/macostextkeys/*.java tests/stubs/f.java \
 *     tests/MacShortcutRemapFocusTest.java
 *   java -cp /tmp/msr-test macostextkeys.MacShortcutRemapFocusTest
 */
public final class MacShortcutRemapFocusTest {

    static int menuClicks = 0;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(MacShortcutRemapFocusTest::run);
        System.exit(failures == 0 ? 0 : 1);
    }

    static void run() {
        try {
            JFrame frame = new JFrame("msr-test");
            JMenuBar mb = new JMenuBar();
            JMenu duzen = new JMenu("Düzenle");
            JMenuItem yapistir = new JMenuItem("Yapıştır");
            yapistir.addActionListener(e -> menuClicks++);
            duzen.add(yapistir);
            JMenuItem tumunuSec = new JMenuItem("Tümünü Seç");
            tumunuSec.addActionListener(e -> menuClicks++);
            duzen.add(tumunuSec);
            mb.add(duzen);
            frame.setJMenuBar(mb);

            JTextField field = new JTextField(20);
            tr.com.havelsan.uyap.system.swing.wp.a.f editor =
                new tr.com.havelsan.uyap.system.swing.wp.a.f();
            JPanel panel = new JPanel();
            panel.add(field);
            panel.add(editor);
            frame.setContentPane(panel);
            frame.pack();

            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection("PANO"), null);

            // 1) Odak düz metin alanında: yapıştırma ALANA gitmeli, menü tıklanmamalı.
            menuClicks = 0;
            field.setText("");
            MacShortcutRemap.dispatch(cmdKey(field, KeyEvent.VK_V));
            check("alan odakli: metin alana yapisti", "PANO".equals(field.getText()));
            check("alan odakli: menu tiklanmadi", menuClicks == 0);

            // 2) Odak editörde (wp.a.f türevi): menüdeki zengin yapıştırma kullanılmalı.
            menuClicks = 0;
            MacShortcutRemap.dispatch(cmdKey(editor, KeyEvent.VK_V));
            check("editor odakli: menu tiklandi", menuClicks == 1);

            // 3) Cmd+A alan odaklıyken alanı seçmeli (menüdeki "Tümünü Seç" değil).
            menuClicks = 0;
            field.setText("abc");
            MacShortcutRemap.dispatch(cmdKey(field, KeyEvent.VK_A));
            check("alan odakli: Cmd+A alani secti", "abc".equals(field.getSelectedText()));
            check("alan odakli: Cmd+A menusu tiklanmadi", menuClicks == 0);

            frame.dispose();
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }
    }

    static KeyEvent cmdKey(Component src, int keyCode) {
        return new KeyEvent(src, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                InputEvent.META_DOWN_MASK, keyCode, KeyEvent.CHAR_UNDEFINED);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "OK   " : "FAIL ") + name);
        if (!ok) failures++;
    }
}
