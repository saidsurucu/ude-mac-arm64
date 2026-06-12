# Dipnot desteği (FOOTNOTE=1) — Tasarım

**Tarih:** 2026-06-12
**Durum:** Onaylandı

## Problem

UDF formatında native dipnot kavramı yok. Kullanıcının udf-converter-go projesi
Word→UDF dönüşümünde dipnotları düz içerik olarak (üst simge işaret + sayfa
dibinde ayraç + 10pt blok) gömüyor; ancak dipnot eklemek/değiştirmek için
Word'de düzenleyip yeniden çevirmek gerekiyor. Hedef: yamalı UDE editöründe
dipnot ekleme/düzenleme native olsun; kaydedilen dosya **düz UDF** kalsın ve
yamasız editörlerde de dipnot gibi görünsün.

## Yaklaşım kararı

**Seçilen (A):** Dipnotlar gerçek belge içeriğidir; yamalı editör onları
sezgisel tanır, Word-gibi yerinde düzenletir ve debounce'lu reflow ile doğru
sayfa diplerinde tutar. Kaydedilen dosya = ekrandaki içerik (yamasız editörde
birebir aynı görünüm).

**Elenenler:**
- View katmanına gerçek dipnot bölgesi (layout yer ayırtma): obfuscate `wp.*`
  sayfalama motorunda ameliyat; risk/efor A'nın katları, görsel kazanç ~0.
- Belge sonu endnote: kullanıcı sayfa altı istedi.
- Marker tabanlı round-trip (yan dosya / özel attribute): kullanıcı sezgisel
  tanımayı seçti — yamasız kayıttan geçmiş ve converter'dan çıkmış dosyalarda
  da çalışır.

## 1. Blok formatı ve sezgisel tanıma

Kaydedilen format udf-converter-go çıktısıyla bilinçli olarak aynı:

```
…gövde, içinde ¹ üst simge işaret…
                                  ← dolgu boş satırlar (dibe itme)
———————————————                   ← ayraç: 15 em dash, 10pt
¹ Dipnot metni. 10pt Times New Roman, düz metin.
² İkinci dipnot…
[sabit sayfa sonu]
…sonraki sayfa gövdesi…
```

Tanıma kuralları (açılışta + her reflow geçişinde):

1. **Ayraç:** içeriği yalnız `—` (em dash) olan, ≥10 karakterli, ≤12pt paragraf.
   (Tam-15 şartı yok ki bozulmuş/eski dosyalar da yakalansın.)
2. **Dipnot paragrafı:** ayraçtan sonra, üst simge rakam(lar)
   (`⁰¹²³⁴⁵⁶⁷⁸⁹`) + boşluk ile başlayan ≤12pt paragraflar; ilk uymayan
   paragrafta blok biter.
3. **İşaret:** gövdede geçen üst simge rakam dizisi, **yalnız numarası bir blok
   paragrafıyla eşleşiyorsa**. Eşleşmeyen üst simgeler (m² gibi) dokunulmaz.
4. Ayraçtan önceki boş paragraflar = dolgu; bloktan sonraki sabit sayfa sonu =
   bizim — ikisi de reflow'da silinip yeniden hesaplanır.

Bilinçli sınır: gövdede kullanıcının kendi yazdığı üst simge sayı, aynı
numaralı dipnot varsa yanlış pozitif olabilir — hukuk metinlerinde nadir,
kabul edildi.

## 2. Ekleme, silme, numaralandırma (Word davranışı)

- **Ekleme:** Şerit "Dipnot Ekle" → caret'e sıradaki üst simge numara
  (`AbstractDocument.replace`, atomik) → sayfanın bloğuna (yoksa oluştur)
  `ⁿ ` önekli boş paragraf → caret dipnot paragrafına taşınır, kullanıcı
  doğrudan yazar.
- **Numaralandırma:** belge genelinde işaret sırasına göre 1..n; reflow her
  geçişte işaretleri ve blok numaralarını yeniden yazar.
- **Silme:** işaret silinirse sahipsiz dipnot paragrafı kaldırılır (Word
  davranışı; undo iki adımı da geri alır). Dipnot metni silinip işaret
  kalırsa boş `ⁿ ` paragrafı korunur. Son dipnot gidince ayraç+dolgu+sayfa
  sonu komple kalkar.
- **Yerinde düzenleme:** dipnot metni sıradan içerik; reflow `ⁿ ` önekini ve
  ayracı doğrular, bozulmuşsa onarır.
- **Gezinme:** işarete çift tık → dipnota kaydır; dipnot numarasına çift tık
  → işarete kaydır.

## 3. Reflow algoritması

**Tetikleyiciler:** (a) belge değişikliğinden ~1.5 sn sonra (debounce, Swing
Timer, EDT); (b) "Dipnot Ekle" sonrası hemen; (c) kayıttan hemen önce senkron
(save action'a Javassist insertBefore); (d) şeritte elle "Dipnotları Yerleştir".

**Geçiş:**
1. `inReflow` yeniden-giriş kilidi (kendi mutasyonlarımız olay üretir).
2. **Söküm:** tüm bloklar, dolgular ve bizim sayfa sonları belgeden çıkar;
   dipnot metinleri numara sırasıyla hafızaya — belge "saf gövde" olur.
3. **Numara senkronu:** işaretler belge sırasına göre 1..n; sahipsiz dipnot
   atılır, işaretsiz numara boş paragraf olarak korunur.
4. **Sayfa ölçümü:** her işaretin y'si `modelToView` ile; sayfa
   yüksekliği/kenar boşlukları editörün sayfa ayarından (Go sabitleri yedek).
5. **Yerleştirme:** dipnotu olan her sayfada, son gövde karakterinden sonra:
   dolgu boş satırlar → ayraç → dipnot paragrafları → sabit sayfa sonu.
   Dolgu = kalan boşluk − blok yüksekliği (gerçek `modelToView` ölçümü,
   karakter tahmini değil).
6. **Sabit nokta:** blok yer kaplayınca gövde taşar, işaret sayfası
   değişebilir → 4-5 en çok 3 kez yinelenir, sonra son durum kabul.
7. **Caret + undo:** tüm mutasyonlar tek `CompoundEdit`; caret offset
   delta'larla düzeltilir, caret dipnottaysa paragrafın yeni konumuna taşınır.

Belge değişmemişse (hash) geçiş erken çıkar. Mutasyonlar hep
`AbstractDocument.replace` + `StyledEditorKit.getInputAttributes` deseni
(TEXTREPLACE'te kanıtlı; moveDot NPE yasağı). EDT dışında belge mutasyonu yok.

## 4. Entegrasyon

- **Build bayrağı:** `FOOTNOTE=1` (varsayılan açık; 0 her şeyi kapatır).
- **Kod:** yeni paket `macosfootnote`, `macos-textkeys` agent jar'ında.
  - `FootnoteModel` — tanıma/tarama (saf mantık, GUI'siz test edilebilir)
  - `FootnoteReflow` — bölüm 3 geçişi
  - `FootnoteActions` — ekle / çift-tık gezinme / elle yerleştir
  - `FootnoteInstall` — editörü bileşen ağacında türle bulup dinleyici takar
- **Javassist (`apply_footnote`):** yalnız (a) kayıt action'ına senkron reflow
  insertBefore, (b) gerekirse sayfa-ayarı erişim köprüsü. Kalan her şey
  agent'ta reflection ile (tip + görünen-metin araması; LiveToggle dersleri).
- **Şerit:** "Dipnot Ekle" + "Dipnotları Yerleştir" ribbon MODELİNDEN
  (darkpage deseni — ağaç yalnız seçili sekmeyi içerir tuzağı). Hedef: Ekle
  sekmesinde uygun band; bulunamazsa darkpage bandına düşer. İkonlar Fluent
  (mapping.tsv'ye iki satır). Klavye kısayolu v1'de yok.
- **Teşhis:** `UDE_FNLOG=1` → `~/Library/Logs/ude-footnote.txt` (TrLog deseni).

## 5. Hata durumları ve bilinçli sınırlar

- **Tanıma şüphesi:** ayraç var ama hiçbir paragraf desene uymuyorsa blok
  dipnot SAYILMAZ, dokunulmaz. Güvenlik kuralı: emin olmadığımızı asla
  silmeyiz/taşımayız.
- **Reflow istisnası:** try/catch; hata olursa CompoundEdit `undo()` ile geri
  alınır, log'a yazılır — yarım yerleşim asla kalmaz.
- **`modelToView` null** (görünüm hazır değil): sessiz erteleme, sonraki
  debounce'ta tekrar.
- **>9 dipnot:** çok haneli üst simge destekli.
- **Sayfaya sığmayan blok:** dolgu 0'a iner, taşan kısım sonraki sayfaya akar;
  dipnot bölünmez.

**v1 sınırları:**
- Dipnotlu sayfalar kaydedilen dosyada sabit sayfa sonu içerir; yamasız
  editörde oraya metin eklenirse yerleşim bozulur — yamalı editörde tekrar
  açılınca reflow düzeltir.
- Dipnot metni düz metin (10pt TNR); zengin biçimlendirme v1 dışı.
- Baskı/PDF: dipnotlar belge içeriği olduğundan kendiliğinden doğru çıkar.

## 6. Test stratejisi

- `tests/FootnoteModelTest.java` (javac+java elle): tanıma — ayraç
  varyantları, sahte üst simge (m²), çok haneli numara, önek onarımı, boş
  dipnot.
- `tests/FootnoteRenumberTest.java`: numara senkronu — araya ekleme, işaret
  silme, sahipsiz içerik.
- **Reflow canlı:** dynamic attach probe (DictSim/TreeProbe deseni) — blok
  konumu `modelToView` ile ölçülür.
- **Round-trip:** kaydet → zip'ten content.xml'de sıra assert
  (gövde→dolgu→ayraç→dipnot→sayfa sonu) → yeniden aç, model aynı mı.
- **Converter interop:** udf-converter-go çıktısı bir UDF açılıp tanıma
  doğrulanır.
- Son GUI doğrulaması elle kullanıcıda (tercih; sentetik klavye güvenilmez).
