# macOS Dikte Düzeltmesi — Tasarım

**Tarih:** 2026-06-11
**Dal:** `feature/dictation-fix`
**Durum:** Onaylandı (implementasyon bekliyor)

## Sorun (kullanıcı geri bildirimi)

macOS dikte özelliği UDE'de konuşurken metni yazıya döküyor; ancak dikte
**kapatıldığında yazılan tüm metin kayboluyor**, ardından uygulama donuyor
(force quit gerekiyor). Aynı belge yeniden açıldığında "dosyayı düz metin
olarak kurtarmak ister misiniz" uyarısı çıkıyor (zorla kapatmanın doğal
sonucu — donma çözülünce kendiliğinden ortadan kalkar).

## Bağlam ve ön bulgular

- macOS dikte, metni klavye olayı olarak değil **input method (IME)
  protokolüyle** verir: konuşma sürerken geçici "composed text", dikte
  kapanırken "committed text" olarak `InputMethodEvent`'lerle akar.
- Jar taraması: UDE'nin kendi kodunda (`wp.*`, `common/text/*`)
  `InputMethodRequests`/`InputMethodEvent` izi **yok**; tek eşleşme gömülü
  eski Apple JDK6 kalıntısı `apple/awt/CInputMethod`/`CTextComponent`
  (çalışma anında kullanılmıyor olmalı; Zulu 11'de karşılığı
  `sun.lwawt.macosx.CInputMethod`). Yani dikte, Swing'in standart
  `JTextComponent` composed-text makinesinden geçiyor; UDE'nin özel
  `DocumentEx` + `wp.*` view katmanı bu yolu hesaba katmamış.
- Türkçe hukuk metinlerindeki şapkalı harfler (â/î/û — "hâkim",
  "vekâlet") **aynı IME yolundan** (ölü tuş `^`) girilir. Emoji seçici
  (⌃⌘Space) de committed-text yolunu kullanır. **Kısıt:** bu yol
  körlemesine kapatılamaz; düzeltme bu girişleri korumalı.

## Hipotezler

- **H1 — JDK native kilidi:** Zulu 11 `CInputMethod`'unda AppKit ↔ EDT
  kilitlenmesi (donmanın olası nedeni). Olaylar Swing'e hiç ulaşmadan
  donuyorsa uygulama-içi düzeltme yetmez.
- **H2 — Composed-text commit'i özel doküman katmanında patlıyor:** dikte
  kapanırken composed metin kaldırılıp committed metin yazılırken UDE
  `DocumentEx`/view katmanı istisna fırlatıyor → metin kaybı + EDT'de
  istisna/döngü kaynaklı donma.

Hedef (kullanıcı kararı): **dikte tam çalışsın** — metin kalıcı yazılsın,
donma olmasın. JRE yükseltme (Zulu 17/21) **son çare** olarak masada.

## Tasarım — Faz 1: Teşhis (`DictationProbe`)

Mevcut `macos-textkeys` javaagent ailesine eklenen küçük sınıf
(`scripts/macos-textkeys/macostextkeys/DictationProbe.java`,
`MacTextKeys.install()` çağırır):

- `Toolkit.addAWTEventListener(INPUT_METHOD_EVENT_MASK)`: her
  `InputMethodEvent` için olay tipi (TEXT_CHANGED / CARET_POSITION_CHANGED),
  committed karakter sayısı, composed metin içeriği/uzunluğu, hedef bileşenin
  **gerçek sınıf adı** ve caret konumu `~/Library/Logs/ude-dictation.txt`'ye
  yazılır (System.err uygulama tarafından yutuluyor — dosya loglama şart).
- EDT istisnaları: `Thread.setDefaultUncaughtExceptionHandler` (+
  `sun.awt.exception.handler` eşdeğeri) ile stack trace aynı dosyaya (H2
  kanıtı).
- Açılışta odaklanan editör bileşeninin sınıf zinciri ve
  `getInputMethodRequests()` dönüşü loglanır (Faz 2'nin yama hedefi).
- Donma teşhisi protokolde: donma anında bundled JDK `jstack <pid>`
  çıktısı alınır (H1 kanıtı: EDT'nin native'de nerede beklediği).

**Test protokolü:** build → yeni belgeye dikteyle birkaç cümle → dikteyi
kapat → (a) metin kayboldu mu, (b) donma var mı; donduysa `jstack`; log
dosyası incelenir. Canlı dikte testini repo sahibi yapar (sentetik klavye
olayları UDE'ye güvenilir ulaşmıyor; dikte sentezlenemez).

## Tasarım — Faz 2: Düzeltme (`DictationGuard`, en olası senaryo = H2)

`AWTEventListener` olay **tüketemez** (salt dinleyici) → düzeltme, Faz 1'in
işaret ettiği editör bileşeni sınıfında (`text.hj` ya da atası)
`processInputMethodEvent(InputMethodEvent)`'in **Javassist build-zamanı
override'ı** ile yapılır:

- Enjekte gövde: `if (macosdict.DictationGuard.handle(this, $1)) return;`
  ardından `super.processInputMethodEvent($1)` (statüko yolu). Yardımcı
  sınıf build sırasında jar'a eklenir; kendi paketinde (`macosdict`) durur
  ki `DICTFIX`, `SKIN`'den bağımsız açılıp kapanabilsin.
- **`DictationGuard.handle` mantığı** (bileşen başına durum,
  `WeakHashMap<JTextComponent, State>`):
  1. Daha önce bizim eklediğimiz geçici (composed) metin belgeden silinir
     (kayıtlı offset+uzunluk).
  2. Olaydaki committed karakterler `replaceSelection` ile **kalıcı düz
     metin** olarak yazılır.
  3. Kalan composed metin yine düz metin olarak geçici eklenir; offset ve
     uzunluk kaydedilir.
  4. `true` döner → olay tüketilir; Swing'in composed-text makinesi
     (UDE'nin taşıyamadığı attribute'lı yol) hiç çalışmaz.
- Dikte canlı görünür; dikte kapanınca metin zaten kalıcıdır.
- **Ölü tuşlar:** `^`+`a` → composed "^" geçici görünür, commit "â" kalıcı
  yazılır — aynı mantıktan bozulmadan geçer. Emoji seçici committed yoldan
  aynı şekilde çalışır.

### Dallanma noktaları (Faz 1 bulgusuna göre)

- **H1 doğrulanırsa** (olaylar Swing'e ulaşmadan native kilit):
  interceptor yetmez → bu iş "teşhis raporu + JRE yükseltme kararı" ile
  kapanır; Zulu 17/21 + `--add-opens` ayrı bir iş olarak planlanır.
- **Başka mekanizma çıkarsa** (örn. UDE `common/text/im` KeyListener'ının
  karışması): tasarım o noktada revize edilir, kullanıcı onayına dönülür.

## Hata yönetimi

- `DictationGuard.handle` baştan sona try/catch: istisna log dosyasına
  yazılır ve `false` döner → varsayılan işleme (bugünkü davranış). En kötü
  durum = statüko; asla daha kötüsü değil.
- Build bayrağı `DICTFIX=1` (varsayılan açık, `SKIN`'den bağımsız);
  gerekirse kullanıcıya `DICTFIX=0` build'i verilebilir.
- Teşhis logu üretimde sessiz; `-Dmacosdict.debug=1` ile açılır.

## Test

1. **Ekran-dışı otomatik ön doğrulama (DlgProbe deseni):** yamalı jar'a
   karşı sentetik `InputMethodEvent` dizisi (`component.dispatchEvent`)
   composed→committed senaryosunu oynatır; belge içeriği beklenenle
   karşılaştırılır. Build-test döngüsüne girmeden mantık doğrulanır.
2. **Canlı test (repo sahibi):** dikteyle çok cümleli yazım + dikteyi
   kapatma (metin kalıcı mı, donma var mı), â/î/û ölü tuşları, normal
   yazım, geri al (⌘Z), emoji seçici.
3. **Regresyon:** MacTextKeys / MacOptionChars / MacShortcutRemap
   davranışları (Option karakterleri aynı klavye yolunda — etkileşim
   kontrolü).

## Kapsam dışı

- JRE yükseltme (yalnız H1 kanıtlanırsa ayrı iş).
- Dikte dışındaki IME'lerle (ör. Çince/Japonca giriş) tam uyumluluk
  hedeflenmez; ama tasarım gereği aynı yol korunduğundan bozulma
  beklenmez.
- Kurtarma uyarısının kendisi (donmanın sonucu; ayrıca ele alınmaz).
