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
**herhangi bir ekleme yapılmadan ÖNCE** imlecin paragraf biçimi yakalanır.
`CURSOR_ATTRS` ile **aynı `try/finally`** içinde set edilir ve `finally`'de
**önceki değere restore edilir** (`prev` deseni; iç içe/yarıda kalan çağrıda
sızmasın — agy bulgusu). `start` `try` içinde (`getCaretPosition()`, mevcut
satır 68) hesaplandığı için snapshot da orada, sentinel'den önce alınır:

```java
AttributeSet prevChar = CURSOR_ATTRS, prevPara = CURSOR_PARA_ATTRS;
CURSOR_ATTRS = cursorAttrs;
try {
    JTextComponent tc = (JTextComponent) editor;
    StyledDocument doc = (StyledDocument) tc.getDocument();
    int start = tc.getCaretPosition();                       // mevcut satır 68
    if (cursorAttrs != null) CURSOR_PARA_ATTRS = snapshotParaFormat(doc, start);
    // ... start==0 tablo sentinel (satır 77-80) ve ekleme bundan SONRA ...
} finally { CURSOR_ATTRS = prevChar; CURSOR_PARA_ATTRS = prevPara; }
```

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

Yakalama (`snapshotParaFormat(tc, start)`): `StyledDocument.getParagraphElement(
start).getAttributes()` alınır; her beyaz-liste anahtarı için değer
**`as.getAttribute(key)`** ile okunur, `null` değilse kopyalanır.

> KRİTİK (codex bulgusu): `isDefined(key)` **yalnız lokal** attr'a bakar;
> kullanıcının hizalaması/aralığı bir adlandırılmış **stilden miras** geliyorsa
> (`ResolveAttribute` zinciri) `isDefined` `false` döner → biçim kaçar.
> `getAttribute(key)` resolver zincirini izleyip **effective** değeri verir;
> `replace=true` ile bu değer paragrafa lokal olarak yazılır. Bu yüzden
> `getAttribute(key) != null` kullanılır, `isDefined` DEĞİL.

### 3. Paragraf öznitelik birleşimi — `insertParagraph`

Düz modda gövde paragrafları (`insertParagraph`, satır 177) için yeni
`paraAttrsPlain(para)`. `replace=true` korunur (liste işaretinin sonraki
paragrafa sızmaması için zaten şart).

- `CURSOR_PARA_ATTRS == null` (normal rich paste): mevcut `paraAttrs(para)`
  aynen kullanılır — davranış değişmez.
- `CURSOR_PARA_ATTRS != null`, **liste DEĞİL** (`para.list == null`): 8 anahtarın
  hepsi `CURSOR_PARA_ATTRS`'tan → hizalama + aralık + girinti + tabset hepsi
  imleçten.
- `CURSOR_PARA_ATTRS != null`, **liste** (`para.list != null`): imleçten **yalnız
  hizalama/aralık** (`Alignment, SpaceAbove, SpaceBelow, LineSpacing`); girintiler
  ve tabset (`LeftIndent, RightIndent, FirstLineIndent, TabSet`) **kaynaktan**
  (`paraAttrs(para)`); üstüne kaynak liste işaretleri.

> KRİTİK (agy + codex bulgusu): Liste girintilerini imleçten almak **asılı
> girintiyi (hanging indent)** ezer → madde işareti/numara ile metin hizası
> bozulur (imleç paragrafı genelde 0 girinti taşır). Liste girintisi yapısaldır,
> kaynaktan korunur. Liste metninin **hizalaması** ise imleçten alınır
> (kullanıcı iki-yana-yaslı çalışıyor; istenen budur).

Liste anahtarları (`Bulleted/Numbered/BulletType/NumberType/ListLevel/ListId`)
mevcut `paraAttrs` liste dalından, tip kurallarıyla (`ListLevel=Integer`,
`ListId=Long`) eklenir → `replace=true` ClassCastException riski yok (codex OK).
**Tam `paraAttrs(para)` taban olarak KULLANILMAZ** — kullanılırsa kaynak
hizalama/aralık sızar (codex uyarısı).

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

`tests/PlainPasteStripTest.java`'a (javac+java elle) senaryolar eklenir.
Ortak kurulum: headless `DefaultStyledDocument`; imleç paragrafı
`ALIGN_JUSTIFIED` + örnek `LineSpacing`/`SpaceBelow`/`LeftIndent`/`TabSet`;
imleç bu paragrafta. Kaynak model `ALIGN_RIGHT` + farklı aralık taşır.

1. **Gövde paragrafı (liste değil):** düz mod sonrası `Alignment == JUSTIFIED`,
   `LineSpacing/SpaceBelow/LeftIndent/TabSet == imleç değeri`; kaynağın
   `ALIGN_RIGHT` + kaynak aralığı **düşmüş**; karakter stili imlecinki.
2. **Liste öğesi:** işaret (`Bulleted`/`Numbered` + `ListId=Long`/`ListLevel=
   Integer`) **korunmuş**; `Alignment` imleçten (JUSTIFIED); **`LeftIndent`/
   `FirstLineIndent` KAYNAKTAN** (imleç sıfır girintisiyle **ezilmemiş** — hanging
   indent regresyon testi).
3. **Miras (resolved) imleç biçimi:** imleç paragrafının hizalaması lokal değil
   bir parent stilden gelirken (`ResolveAttribute`), snapshot yine JUSTIFIED
   yakalar (`getAttribute` vs `isDefined` regresyonu).
4. **Offset-0 tablo sentinel:** kaynak ilk bloğu tablo, imleç offset 0 → snapshot
   sentinel `insertString(0,"\n")`'den **önce** alınır; imleç biçimi doğru okunur,
   eklenen gövde paragrafları imleç hizalamasını alır.
5. **Tablo hücresi:** hücre paragraf düzeni **kaynaktan** kalır (imleç JUSTIFIED'a
   çekilmez); hücre metin stili imlecinki (mevcut PLAINPASTE davranışı).
6. **Normal rich paste regresyonu:** `cursorAttrs == null` ile `CURSOR_PARA_ATTRS`
   null kalır; paragraf biçimi kaynaktan (`paraAttrs`) — davranış değişmemiş.

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
