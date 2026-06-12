package macosantet;

/* Antet deposu + contain-fit. Swing'e bağımlılığı YOK (jar'sız test edilir).
 * Klasör/IO metotları Task 2'de eklenir. */
public final class AntetStore {

    /* A4, punto (pt): 21.0x29.7 cm * 28.3464566 */
    public static final double A4_W = 595.0;
    public static final double A4_H = 842.0;

    private AntetStore() {}

    /* Orana sadık, sayfanın İÇİNE sığdır (contain). Küçük resim büyütülür;
     * sonuç hiçbir eksende sayfayı aşmaz, en az 1 px. */
    public static int[] computeFit(int iw, int ih, double pw, double ph) {
        double s = Math.min(pw / iw, ph / ih);
        int w = (int) Math.round(iw * s);
        int h = (int) Math.round(ih * s);
        w = Math.max(1, Math.min(w, (int) Math.ceil(pw)));
        h = Math.max(1, Math.min(h, (int) Math.ceil(ph)));
        return new int[] { w, h };
    }
}
