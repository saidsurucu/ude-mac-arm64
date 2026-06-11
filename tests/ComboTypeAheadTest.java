package macosskin;

import java.util.Locale;

import javax.swing.DefaultComboBoxModel;

/** WordCombo type-ahead yardımcıları için elle çalıştırılan test
 *  (paket-içi erişim için macosskin paketinde).
 *  Derle+çalıştır:
 *    javac -d /tmp/ctt scripts/skin/macosskin/WordCombo.java \
 *      scripts/skin/macosskin/DarkMode.java tests/ComboTypeAheadTest.java
 *    java -cp /tmp/ctt macosskin.ComboTypeAheadTest
 *  Çıktı "OK" değilse FAIL. */
public class ComboTypeAheadTest {
    static int fails = 0;

    static void check(String name, boolean cond) {
        if (!cond) { System.err.println("FAIL: " + name); fails++; }
    }

    public static void main(String[] args) {
        DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>(new String[] {
            "Arial", "C059", "Century Gothic", "Century Schoolbook", "courier", "Impact"
        });

        // 1) Tek harf ilk eşleşmeyi bulur (kullanıcı senaryosu: 'c' -> C059)
        check("c -> C059", WordCombo.UI.findPrefixMatch(m, "c") == 1);

        // 2) Önek uzadıkça eşleşme ilerler
        check("ce -> Century Gothic", WordCombo.UI.findPrefixMatch(m, "ce") == 2);
        check("century s -> Century Schoolbook",
            WordCombo.UI.findPrefixMatch(m, "century s") == 3);

        // 3) Büyük/küçük harf duyarsız
        check("CENTURY S", WordCombo.UI.findPrefixMatch(m, "CENTURY S") == 3);
        check("COUR -> courier", WordCombo.UI.findPrefixMatch(m, "COUR") == 4);

        // 4) Eşleşme yoksa -1
        check("zz -> -1", WordCombo.UI.findPrefixMatch(m, "zz") == -1);
        check("bos -> -1", WordCombo.UI.findPrefixMatch(m, "") == -1);

        // 5) Türkçe locale tuzağı: 'i' -> 'Impact' (I/i eşleşmesi locale'den bağımsız)
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check("tr locale i -> Impact", WordCombo.UI.findPrefixMatch(m, "i") == 5);
        } finally {
            Locale.setDefault(old);
        }

        // 6) Tampon: süre dolmadan harf eklenir, dolunca sıfırlanıp yeni harfle başlar
        check("buffer append", WordCombo.UI.nextBuffer("ce", 'n', 500, 1000).equals("cen"));
        check("buffer reset", WordCombo.UI.nextBuffer("ce", 's', 1500, 1000).equals("s"));
        check("buffer first", WordCombo.UI.nextBuffer("", 'c', 0, 1000).equals("c"));

        if (fails == 0) System.out.println("OK");
        else { System.out.println(fails + " FAIL"); System.exit(1); }
    }
}
