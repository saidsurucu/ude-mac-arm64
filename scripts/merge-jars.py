#!/usr/bin/env python3
"""UDE'nin bölünmüş jar'larını tek editor-app.jar'a birleştirir.

5.4.19'dan itibaren satıcı tek "editor-app.jar"ı yediye böldü
(editor_laf, editor_lib, editor_lib2, editor_utility, jai_hvl, jdom, updater).
Tüm yamalarımız tek jar varsayıyor → sınıf yolu SIRASIYLA birleştiririz;
aynı adlı giriş birden çok jar'da varsa İLK gelen kazanır (JVM'in kendi
sınıf-yolu davranışı).

KRİTİK: birleştirme zip→zip yapılır, diske AÇILMAZ. macOS dosya sistemi
büyük/küçük harf duyarsızdır; obfuscate sınıf adlarında yüzlerce çakışma var
(kx/kX gibi) → unzip ile açmak sınıfları sessizce yer.

Kullanım: merge-jars.py <çıktı.jar> <Main-Class> <jar1> [jar2 ...]
"""
import sys
import zipfile


def is_signature(name):
    return name.startswith('META-INF/') and name.upper().endswith(('.SF', '.RSA', '.DSA'))


def main(argv):
    if len(argv) < 4:
        sys.exit('kullanım: merge-jars.py <çıktı.jar> <Main-Class> <jar>...')
    out_path, main_class, jars = argv[1], argv[2], argv[3:]
    seen = set()
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as out:
        manifest = 'Manifest-Version: 1.0\nMain-Class: %s\n\n' % main_class
        out.writestr('META-INF/MANIFEST.MF', manifest)
        seen.add('META-INF/MANIFEST.MF')
        for jar in jars:
            with zipfile.ZipFile(jar) as src:
                for info in src.infolist():
                    name = info.filename
                    if name.endswith('/') or name in seen or is_signature(name):
                        continue
                    seen.add(name)
                    out.writestr(info, src.read(name))
    print(len(seen))


if __name__ == '__main__':
    main(sys.argv)
