package macostextkeys;

import javax.swing.text.Element;
import javax.swing.text.StyledDocument;

/**
 * Backspace/Delete ile tablo silme. UDE tabloları yalnız araç çubuğundaki
 * "Tablo Sil" (DocumentEx.f(int)) ile kaldırılabiliyordu; bu sınıf düz
 * Backspace/Delete'i Word benzeri tablo silmeye yönlendirir, tablo yoksa
 * orijinal aksiyona devreder. Tespit saf javax.swing.text; yalnız silme
 * çağrısı reflection (agent app-classpath'siz derlenir).
 */
public final class TableDelete {

    private TableDelete() {}

    /** Tespit için minimal doküman görünümü (test edilebilirlik). */
    public interface DocView {
        /** pos'taki yaprak (karakter) eleman; yoksa null. */
        Element charAt(int pos);
        /** [start, start+len) metni; hata olursa "". */
        String text(int start, int len);
    }

    /** e'den yukarı çıkıp adı "table" olan ilk atayı döndürür; yoksa null. */
    static Element tableAncestor(Element e) {
        while (e != null) {
            if ("table".equals(e.getName())) return e;
            e = e.getParentElement();
        }
        return null;
    }

    /** [s,e) aralığını TAM kapsayan ilk tabloyu döndürür; yoksa null. */
    static Element firstTableInRange(DocView dv, int s, int e) {
        int p = s;
        while (p < e) {
            Element el = dv.charAt(p);
            Element t = tableAncestor(el);
            if (t != null && t.getStartOffset() >= s && t.getEndOffset() <= e) return t;
            int next = (el != null) ? el.getEndOffset() : p + 1;
            p = (next > p) ? next : p + 1;
        }
        return null;
    }

    /** Tablo metni boşluk/kontrol dışında boşsa true. */
    static boolean isEmptyTable(DocView dv, Element t) {
        String s = dv.text(t.getStartOffset(), t.getEndOffset() - t.getStartOffset());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isISOControl(c)) return false;
        }
        return true;
    }

    /**
     * Backspace için silinecek tablonun "içi" konumu (f'e verilecek) veya -1.
     * -1 → tablo silme yok, orijinal Backspace'e devret.
     */
    public static int targetForBackspace(DocView dv, int caret, int selStart, int selEnd) {
        if (selStart != selEnd) {
            Element t = firstTableInRange(dv, selStart, selEnd);
            return t != null ? t.getStartOffset() : -1;
        }
        Element inTable = tableAncestor(dv.charAt(caret));
        if (inTable != null) {
            return isEmptyTable(dv, inTable) ? inTable.getStartOffset() : -1;
        }
        if (caret > 0) {
            Element pt = tableAncestor(dv.charAt(caret - 1));
            if (pt != null && caret >= pt.getEndOffset()) return pt.getStartOffset();
        }
        return -1;
    }

    /** Delete için: seçim tam-kapsama veya içeride-boş; adjacency yok. */
    public static int targetForDelete(DocView dv, int caret, int selStart, int selEnd) {
        if (selStart != selEnd) {
            Element t = firstTableInRange(dv, selStart, selEnd);
            return t != null ? t.getStartOffset() : -1;
        }
        Element inTable = tableAncestor(dv.charAt(caret));
        if (inTable != null) {
            return isEmptyTable(dv, inTable) ? inTable.getStartOffset() : -1;
        }
        return -1;
    }
}
