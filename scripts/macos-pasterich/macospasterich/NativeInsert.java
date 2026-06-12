package macospasterich;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import macospasterich.UdeDoc.Block;
import macospasterich.UdeDoc.Paragraph;
import macospasterich.UdeDoc.Run;
import macospasterich.UdeDoc.Table;
import macospasterich.UdeDoc.TableCell;
import macospasterich.UdeDoc.TableRow;
import macospasterich.UdeDoc.TextRun;
import macospasterich.UdeDoc.TextStyle;

/**
 * UDE belge modelini canlı editörün belgesine caret'e YEREL ekler — copy→paste
 * (EditorDataFlavor) tabloları düzleştirdiğinden o yol terk edildi. Tablolar
 * UDE'nin kendi tablo-kurma primitifiyle (DocumentEx.a) gerçek tablo olarak
 * oluşturulur, hücreler içerikle doldurulur; paragraflar StyleConstants
 * karakter/paragraf öznitelikleriyle eklenir.
 *
 * Saf java.* + reflection (UDE iç tipleri derleme-zamanı gerekmez): DocumentEx.a,
 * ae.x/w/z, Utils.a(int[]) reflection ile çözülür.
 */
final class NativeInsert {

    /** editor (hj/JTextComponent) belgesine caret'ten itibaren modeli ekler. */
    static boolean insert(Object editor, UdeDoc.Document model) {
        try {
            javax.swing.text.JTextComponent tc = (javax.swing.text.JTextComponent) editor;
            StyledDocument doc = (StyledDocument) tc.getDocument();
            int pos = tc.getCaretPosition();
            insertBlocks(editor, doc, trimEmpties(model.body), pos);
            return true;
        } catch (Throwable t) {
            PrLog.log("NativeInsert.insert", t);
            return false;
        }
    }

    /**
     * Baştaki/sondaki boş paragrafları atar ve ardışık 2+ boş paragrafı tek'e
     * indirir (Word fazladan boş satır üretiyor). Boş = liste değil + tüm run'lar
     * boş/yalnız-boşluk. Tablo/resimli paragraflar korunur.
     */
    private static List<Block> trimEmpties(List<Block> blocks) {
        List<Block> out = new ArrayList<>();
        boolean prevEmpty = false;
        for (Block b : blocks) {
            boolean empty = isEmptyPara(b);
            if (empty && (out.isEmpty() || prevEmpty)) continue;   // baş + ardışık
            out.add(b);
            prevEmpty = empty;
        }
        while (!out.isEmpty() && isEmptyPara(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);                            // son
        }
        return out;
    }

    private static boolean isEmptyPara(Block b) {
        if (!(b instanceof Paragraph)) return false;
        Paragraph p = (Paragraph) b;
        if (p.list != null) return false;
        for (Run r : p.runs) {
            if (r instanceof UdeDoc.ImageRun) return false;
            if (r instanceof TextRun && !clean(((TextRun) r).text).trim().isEmpty()) return false;
        }
        return true;
    }

    /** Blokları sırayla pos'a ekler; eklenen toplam uzunluğu döndürür. */
    private static int insertBlocks(Object editor, StyledDocument doc, List<Block> blocks, int pos) throws Exception {
        int start = pos;
        for (Block b : blocks) {
            if (b instanceof Paragraph) {
                pos = insertParagraph(editor, doc, (Paragraph) b, pos);
            } else if (b instanceof Table) {
                pos = insertTable(editor, doc, (Table) b, pos);
            } else if (b instanceof UdeDoc.PageBreak) {
                doc.insertString(pos, "\n", null);
                pos += 1;
            }
        }
        return pos - start;
    }

    private static int insertParagraph(Object editor, StyledDocument doc, Paragraph para, int pos) throws Exception {
        int paraStart = pos;
        for (Run r : para.runs) {
            if (r instanceof TextRun) {
                TextRun tr = (TextRun) r;
                String t = clean(tr.text);
                doc.insertString(pos, t, charAttrs(tr.style));
                pos += t.length();
            } else if (r instanceof UdeDoc.ImageRun) {
                pos += insertImage(editor, doc, (UdeDoc.ImageRun) r, pos);
            }
            // TabRun şimdilik atlanır
        }
        // paragraf öznitelikleri (hizalama + girintiler + aralık)
        if (pos > paraStart) doc.setParagraphAttributes(paraStart, pos - paraStart, paraAttrs(para), false);
        // paragraf sonlandırıcı
        doc.insertString(pos, "\n", null);
        pos += 1;
        return pos;
    }

    private static int insertTable(Object editor, StyledDocument doc, Table table, int pos) throws Exception {
        int before = doc.getLength();
        int rows = table.rows.size();
        int cols = table.columns;
        int[] colW = new int[cols];
        for (int i = 0; i < cols; i++) {
            colW[i] = (i < table.columnWidths.size()) ? table.columnWidths.get(i) : 100;
        }
        String[] colStyles = new String[cols];
        for (int i = 0; i < cols; i++) colStyles[i] = "hvl-default";

        // tablo öznitelikleri (ae.x=ad, ae.w=genişlikler, ae.z=kenarlık)
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        Object widthsStr = utilsWidths(colW);
        aeSet("x", attrs, "Sabit");
        if (widthsStr != null) aeSet("w", attrs, (String) widthsStr);
        aeSet("z", attrs, table.border.name);   // borderCell / borderNone

        // YEREL tablo kur
        tableBuild(doc, pos, "Sabit", rows, cols, colW, colStyles, attrs);

        // hücreleri bul (table→row→cell→ilk paragraf offset) ve içerikle doldur.
        // Tablo, pos'tan itibaren eklendi; tablo elementini bul.
        Element root = doc.getDefaultRootElement();
        Element tableEl = findTableAt(root, pos);
        if (tableEl != null) {
            // hücre içeriklerini SONDAN başa yaz (offset kayması olmasın)
            List<int[]> jobs = new ArrayList<>();   // {cellParaOffset, cellIndex}
            List<TableCell> cells = new ArrayList<>();
            collectCells(tableEl, table, jobs, cells);
            for (int i = jobs.size() - 1; i >= 0; i--) {
                int off = jobs.get(i)[0];
                fillCell(editor, doc, cells.get(i), off);
            }
        }
        // tablo + sonrası: yeni belge uzunluğu farkı kadar ilerle
        int added = doc.getLength() - before;
        return pos + added;
    }

    /** Hücre içeriğini (paragraflar) hücrenin ilk-paragraf offset'ine yazar. */
    private static void fillCell(Object editor, StyledDocument doc, TableCell cell, int offset) throws Exception {
        int pos = offset;
        boolean first = true;
        for (Block b : cell.content) {
            if (!(b instanceof Paragraph)) continue;
            Paragraph p = (Paragraph) b;
            if (!first) { doc.insertString(pos, "\n", null); pos += 1; }
            first = false;
            int ps = pos;
            for (Run r : p.runs) {
                if (r instanceof TextRun) {
                    TextRun tr = (TextRun) r;
                    String t = clean(tr.text);
                    doc.insertString(pos, t, charAttrs(tr.style));
                    pos += t.length();
                } else if (r instanceof UdeDoc.ImageRun) {
                    pos += insertImage(editor, doc, (UdeDoc.ImageRun) r, pos);
                }
            }
            if (pos > ps) doc.setParagraphAttributes(ps, pos - ps, paraAttrs(p), false);
        }
    }

    /** pos'u kapsayan en içteki 'table' elementini bulur. */
    private static Element findTableAt(Element e, int pos) {
        if ("table".equals(e.getName()) && e.getStartOffset() <= pos && pos <= e.getEndOffset()) {
            return e;
        }
        for (int i = 0; i < e.getElementCount(); i++) {
            Element c = e.getElement(i);
            if (c.getStartOffset() <= pos && pos <= c.getEndOffset()) {
                Element r = findTableAt(c, pos);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Tablo elementindeki hücreleri model sırasıyla eşleyip ilk-paragraf offset'lerini toplar. */
    private static void collectCells(Element tableEl, Table model, List<int[]> jobs, List<TableCell> cells) {
        List<Element> domCells = new ArrayList<>();
        collectCellElements(tableEl, domCells);
        List<TableCell> modelCells = new ArrayList<>();
        for (TableRow r : model.rows) modelCells.addAll(r.cells);
        int n = Math.min(domCells.size(), modelCells.size());
        for (int i = 0; i < n; i++) {
            Element ce = domCells.get(i);
            if (ce.getElementCount() > 0) {
                jobs.add(new int[]{ ce.getElement(0).getStartOffset() });
                cells.add(modelCells.get(i));
            }
        }
    }

    private static void collectCellElements(Element e, List<Element> out) {
        if ("cell".equals(e.getName())) { out.add(e); return; }
        for (int i = 0; i < e.getElementCount(); i++) collectCellElements(e.getElement(i), out);
    }

    /**
     * Resim ekler: base64 → BufferedImage → sayfaya sığdır → editor.a(img,w,h)
     * (UDE'nin caret'e resim-ekleme primitifi; canlı editör caret'i text.l olduğundan
     * çalışır). Eklenen karakter sayısını (genelde 1) döndürür; başarısızsa 0.
     */
    private static int insertImage(Object editor, StyledDocument doc, UdeDoc.ImageRun ir, int pos) {
        try {
            if (ir.data == null || ir.data.isEmpty()) return 0;
            byte[] bytes = java.util.Base64.getDecoder().decode(ir.data.trim());
            java.awt.image.BufferedImage img =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) { PrLog.log("resim decode edilemedi"); return 0; }
            // Word'ün görüntü boyutunu (HTML width/height) onurlandır; yoksa doğal boyut.
            double w, h;
            if (ir.width > 1 && ir.height > 1) { w = ir.width; h = ir.height; }
            else { w = img.getWidth(); h = img.getHeight(); }
            double maxW = 480;   // sayfa yazılabilir genişliğine kaba sığdırma (pt)
            if (w > maxW) { h = h * maxW / w; w = maxW; }
            // caret'i ekleme noktasına al; UDE resmi caret'e ekler
            ((javax.swing.text.JTextComponent) editor).setCaretPosition(pos);
            int before = doc.getLength();
            if (imageInsertM == null) {
                imageInsertM = editor.getClass().getMethod("a",
                        java.awt.image.BufferedImage.class, float.class, float.class);
            }
            imageInsertM.invoke(editor, img, (float) w, (float) h);
            int delta = doc.getLength() - before;
            return delta > 0 ? delta : 0;
        } catch (Throwable t) {
            PrLog.log("insertImage", t);
            return 0;
        }
    }

    private static Method imageInsertM;

    /**
     * HTML metin kuralı: paragraf-içi satır sonları (\r\n) BOŞLUKTUR, satır sonu
     * değil (yalnız <br>/blok sınırları satır kırar). insertString \n'i yeni
     * paragraf sayar → Word liste itemlerini ("1.\nAd") ikiye böler. Bu yüzden
     * run metnindeki \r\n dizilerini tek boşluğa indir.
     */
    private static String clean(String t) {
        if (t == null) return "";
        return t.replaceAll("[\\r\\n]+", " ");
    }

    /** Paragraf öznitelikleri: hizalama + girintiler + aralık (StyleConstants). */
    private static SimpleAttributeSet paraAttrs(Paragraph p) {
        SimpleAttributeSet pa = new SimpleAttributeSet();
        StyleConstants.setAlignment(pa, p.alignment);
        if (p.leftIndent != 0) StyleConstants.setLeftIndent(pa, (float) p.leftIndent);
        if (p.rightIndent != 0) StyleConstants.setRightIndent(pa, (float) p.rightIndent);
        if (p.firstLineIndent != 0) StyleConstants.setFirstLineIndent(pa, (float) p.firstLineIndent);
        if (p.spaceBefore != 0) StyleConstants.setSpaceAbove(pa, (float) p.spaceBefore);
        if (p.spaceAfter != 0) StyleConstants.setSpaceBelow(pa, (float) p.spaceAfter);
        return pa;
    }

    // ---- karakter öznitelikleri (StyleConstants) ----
    private static AttributeSet charAttrs(TextStyle s) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, s.fontFamily);
        StyleConstants.setFontSize(a, (int) Math.round(s.fontSize));
        if (s.bold) StyleConstants.setBold(a, true);
        if (s.italic) StyleConstants.setItalic(a, true);
        if (s.underline) StyleConstants.setUnderline(a, true);
        if (s.color != -16777216) StyleConstants.setForeground(a, new Color(s.color, true));
        if (s.backgroundColor != -1) StyleConstants.setBackground(a, new Color(s.backgroundColor, true));
        return a;
    }

    // ---- reflection köprüleri (UDE iç tipleri) ----
    private static Method tableBuildM;
    private static Method aeXM, aeWM, aeZM;
    private static Method utilsWidthsM;

    private static void tableBuild(StyledDocument doc, int pos, String name, int rows, int cols,
                                   int[] colW, String[] colStyles, SimpleAttributeSet attrs) throws Exception {
        if (tableBuildM == null) {
            for (Method m : doc.getClass().getMethods()) {
                if (!m.getName().equals("a")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 7 && p[0] == int.class && p[1] == String.class && p[2] == int.class
                        && p[3] == int.class && p[4] == int[].class && p[5] == String[].class
                        && SimpleAttributeSet.class.isAssignableFrom(p[6])) {
                    tableBuildM = m; break;
                }
            }
            if (tableBuildM == null) throw new NoSuchMethodException("DocumentEx tablo-kurma metodu yok");
        }
        tableBuildM.invoke(doc, pos, name, rows, cols, colW, colStyles, attrs);
    }

    private static void aeSet(String which, MutableAttributeSet attrs, String val) {
        try {
            Method m = which.equals("x") ? aeXM : which.equals("w") ? aeWM : aeZM;
            if (m == null) {
                Class<?> ae = Class.forName("tr.com.havelsan.uyap.system.swing.wp.model.ae");
                m = ae.getMethod(which, MutableAttributeSet.class, String.class);
                if (which.equals("x")) aeXM = m; else if (which.equals("w")) aeWM = m; else aeZM = m;
            }
            m.invoke(null, attrs, val);
        } catch (Throwable t) {
            PrLog.log("aeSet " + which, t);
        }
    }

    private static Object utilsWidths(int[] colW) {
        try {
            if (utilsWidthsM == null) {
                Class<?> u = Class.forName("tr.com.havelsan.uyap.system.editor.common.Utils");
                utilsWidthsM = u.getMethod("a", int[].class);
            }
            return utilsWidthsM.invoke(null, (Object) colW);
        } catch (Throwable t) {
            PrLog.log("utilsWidths", t);
            return null;
        }
    }

    private NativeInsert() {
    }
}
