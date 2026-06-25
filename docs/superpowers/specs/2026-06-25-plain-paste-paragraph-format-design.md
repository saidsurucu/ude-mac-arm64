# Formatsız Yapıştır — paragraf biçimi imleçten (PLAINPASTE iyileştirme)

Tarih: 2026-06-25
Durum: tasarım onaylandı (kullanıcı + agy + codex bağımsız gözden geçirme)
İlgili: [PLAINPASTE](2026-06-16-plain-paste-design.md), [PASTERICH](2026-06-12-paste-rich-text-design.md)

## Sorun

Formatsız Yapıştır (PLAINPASTE) karakter stilini düşürüyor ama gövde
paragraflarının **paragraf biçimini** (hizalama / aralık / girinti) hâlâ
**kaynaktan** alıyor. Kullanıcı UDE'yi iki-yana-yaslı + kendi satır/paragraf
aralığıyla kullanıyor; yapıştırınca metin **sağa-yaslı + kaynağın aralığıyla**
geliyor. İstenen: "sadece değeri yapıştır".

Kök neden: `NativeInsert.insertParagraph` (satır 177) paragraf özniteliklerini
`paraAttrs(para)` (satır 334–358) ile kuruyor; bu fonksiyon `setAlignment`,
`setSpaceAbove/Below`, girintileri **kaynak** `Paragraph` modelinden okur.
Düz-karakter modu (`CURSOR_ATTRS != null`) yalnız `charAttrs`'ı imlece
indirgiyor; `paraAttrs` etkilenmiyor.

## Hedef davranış (onaylanan kapsam: "Seçenek A")

Düz modda:
- **Gövde paragraflarının** hizalama / satır-paragraf aralığı / girintisi
  **imlecin bulunduğu paragraftan** alınır (kullanıcının iki-yana-yaslı + kendi
  aralığı kazanır).
- **Liste işaretleri** (bullet/numara), **tablo**, **imaj** yapısı **korunur**.
- Hücre **paragraf düzeni** kaynaktan kalır (hücre metni iki yana yaslamak
  istenmeyen sürpriz olurdu).
- Karakter stili her yerde imlecinki olmaya devam eder (mevcut PLAINPASTE
  davranışı; tablo hücresi metni dahil — `fillCell → charAttrs → CURSOR_ATTRS`).

Tamamen düz metin (tablo→metin, liste işareti kalkar) ve yalnız-hizalama
varyantları **kapsam dışı** (kullanıcı reddetti).

## Tasarım

Tek dosya: `scripts/macos-pasterich/macospasterich/NativeInsert.java`.

### 1. İmleç paragraf biçimi anlık görüntüsü — `CURSOR_PARA_ATTRS`

`CURSOR_ATTRS` deseninin paragraf eşdeğeri. Yeni statik alan:

```java
private static AttributeSet CURSOR_PARA_ATTRS;   // düz modda gövde paragraf tabanı
```

`insert(editor, model, cursorAttrs)` içinde, `cursorAttrs != null` iken,
**herhangi bir ekleme yapılmadan ÖNCE** imlecin paragraf biçimi yakalanır ve
try/finally ile set/temizlenir (EDT tek iş parçacıklı, `CURSOR_ATTRS` ile aynı
yaşam döngüsü).

**Yakalama noktası (KRİTİK — agy + codex ortak bulgusu):**
- Snapshot, `insert()`'in ekleme için kullandığı **aynı `start` ofsetinden**
  alınır (tutarlılık: biçim, metnin gerçekten **ineceği** paragraftan okunmalı).
  `insert()` `start`'ı `getCaretPosition()` ile hesaplıyor; snapshot da bu
  değerden okunur. Seçim **yok** iken (tipik yapıştırma) `caret == selStart ==
  selEnd` → fark yok.
- Yakalama, mevcut `start == 0 && body[0] instanceof Table` durumundaki
  `doc.insertString(0, "\n", DEFAULT_BREAK)` sentinel'inden **ÖNCE** ve `start`
  o sentinel ile 1'e itilmeden önce yapılır; aksi halde yeni eklenen boş satırın
  (ezilmiş) öznitelikleri okunur.

> Not: agy/codex `min(selStart, selEnd)` önerdi; bu, seçimi **silen** standart
> paste semantiği varsayımıdır. Mevcut PASTERICH yolu seçimi silmiyor ve metni
> `getCaretPosition()`'a ekliyor → snapshot da ekleme ofsetiyle (caret)
> hizalanır. Seçim varken davranış (seçim silinmeden ekleme) zaten mevcut
> PASTERICH davranışıdır; bu iş onu değiştirmez. Snapshot ekleme noktasıyla
> tutarlı olduğu sürece doğru paragrafı verir.

### 2. Beyaz liste (allowlist) — char/yapısal sızıntı yok

agy + codex'in ortak ısrarı: paragraf elementinin **tüm** özniteliklerini
kopyalamak (kara liste) char öznitelikleri (`FontFamily/FontSize/Bold/…`) ve
miras anahtarlarını (`ResolveAttribute`, `NameAttribute`) sızdırır → karakter
stili `CURSOR_ATTRS`'tan gelmeli, paragraf override onu ezmemeli. Bu yüzden
**yalnız** şu paragraf-düzen anahtarları kopyalanır:

```
StyleConstants.Alignment
StyleConstants.LeftIndent
StyleConstants.RightIndent
StyleConstants.FirstLineIndent
StyleConstants.SpaceAbove
StyleConstants.SpaceBelow
StyleConstants.LineSpacing
StyleConstants.TabSet
```

`LineSpacing` + `TabSet` ilk tasarımda atlanmıştı (codex yakaladı; kullanıcının
"tırnak aralığı" satır aralığıdır → `LineSpacing` şart). Beyaz liste sayesinde
`ResolveAttribute/NameAttribute/ElementName` ve liste anahtarları
**otomatik** elenir; ayrı silme listesi gerekmez.

Yakalama, imleç paragraf elementinin **resolved** öznitelik kümesinden okunur
(`StyledDocument.getParagraphElement(start).getAttributes()` → her anahtar için
`isDefined` ise `copyAttributes`). Yardımcı: `copyParaFormat(AttributeSet src)`.

### 3. Paragraf öznitelik birleşimi — `insertParagraph`

Düz modda gövde paragrafları (`insertParagraph`, satır 177) için yeni taban:

```
CURSOR_PARA_ATTRS  (imlecin hizalama/aralık/girinti/tabset'i)
  + kaynak liste işaretleri (Bulleted/Numbered/BulletType/NumberType/
    ListLevel/ListId — paraAttrs'tan yalnız liste dalı)
```

`replace=true` korunur (liste işaretinin sonraki paragrafa sızmaması için
zaten şart). Birleşim:
- `CURSOR_PARA_ATTRS == null` (normal rich paste): mevcut `paraAttrs(para)`
  aynen kullanılır — davranış değişmez.
- `CURSOR_PARA_ATTRS != null` (düz mod): yeni `paraAttrsPlain(para)` döner →
  `CURSOR_PARA_ATTRS` kopyası + kaynak liste anahtarları (tip kuralları korunur:
  `ListLevel=Integer`, `ListId=Long` → ClassCastException riski yok, codex OK).

`insertTable` hücre paragrafları **mevcut `paraAttrs(p)`'i** kullanmaya devam
eder (kaynak düzen). `breakAttrs` (char düzeyi) ve `stringFlavor` yedeği
(`PlainPaste.insertPlainString`, paragraf özniteliği set etmez → metin imlecin
paragrafına girer) değişmez.

### 4. Doğrulama riski — UDE'nin aralık anahtarı

Tek açık belirsizlik: UDE kullanıcının satır aralığını bellek-içi paragraf
`AttributeSet`'inde **standart** `StyleConstants.LineSpacing` altında mı yoksa
**özel** bir anahtarda mı tutuyor. Mevcut `paraAttrs` ve UDE render'ı standart
`StyleConstants` üzerinden çalıştığı için beyaz listenin yeterli olması beklenir.
Yine de implementasyon planı, gerçek iki-yana-yaslı bir UDE paragrafının
öznitelik anahtarlarını **dynamic-attach probe** ile sayar; özel bir aralık
anahtarı çıkarsa beyaz listeye eklenir (proje kültürü: tahmin değil ölçüm).

## Test

`tests/PlainPasteStripTest.java`'a (javac+java elle) senaryo eklenir:

1. Headless `DefaultStyledDocument` + boş paragraf `StyleConstants.ALIGN_JUSTIFIED`
   + örnek `LineSpacing`/`SpaceBelow` ile kurulur; imleç bu paragrafa konur.
2. Kaynak model: `ALIGN_RIGHT` + farklı aralık taşıyan bir gövde paragrafı +
   bir liste öğesi.
3. Düz modda `NativeInsert.insert(..., cursorAttrs)` çağrılır.
4. Doğrulanır: eklenen gövde paragrafının `Alignment == JUSTIFIED`,
   `LineSpacing/SpaceBelow == imleç değeri`; kaynağın `ALIGN_RIGHT`'ı **düşmüş**;
   karakter stili imlecinki; liste öğesinin işareti (Bulleted/Numbered)
   **korunmuş**.

Canlı GUI doğrulaması kullanıcı tarafından elle (proje tercihi).

## Build / dağıtım

Kod değişikliği yalnız `NativeInsert.java`; `apply_pasterich` zaten
`macospasterich/*.java`'yı derleyip jar'a enjekte eder → ek build adımı yok.
`PlainPastePatch`/`apply_plainpaste` ve `textkeys` (⌘⇧V) yolları etkilenmez.
Teşhis: `UDE_PLAINPASTELOG=1` → `~/Library/Logs/ude-plainpaste.txt`.

## Kapsam dışı (YAGNI)

- Tamamen düz metin (tablo→sekme-ayraçlı metin, liste işareti kaldırma).
- Tablo hücresi paragraf düzenini imlece çekme.
- Kaynak `lineSpacing/tabStops`'un normal (rich) yapıştırmada uygulanması —
  mevcut `paraAttrs`'taki ayrı bir boşluk; bu işin parçası değil.
