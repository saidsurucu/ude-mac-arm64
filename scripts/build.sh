#!/bin/bash
#
# build.sh — UDE (Uyap Doküman Editörü) için native Apple Silicon (arm64) .app üretir.
#
# Yaklaşım: jpackage ile, arm64 Java 8 JRE'yi GÖMEREK gerçek bir native launcher'lı
# .app paketlenir. Böylece:
#   - Son kullanıcı ayrıca Java kurmak zorunda kalmaz (JRE gömülü).
#   - macOS dosya ilişkilendirmesi/çift-tık çalışır (script launcher'ın aksine,
#     native launcher "dosya aç" Apple Event'ini JVM'e iletir).
#
# Engeller ve çözümleri (detay: claudedocs/ARM-derleme-plani.md):
#   1) x64 launcher        -> jpackage native arm64 launcher (in-process JVM)
#   2) JNA (kullanılmıyor)  -> dokunulmaz
#   3) sqlite-jdbc 3.7.2    -> 3.46.x ile değiştirilir (arm64 dylib içerir)
#   + .udf dosya ilişkilendirmesi + ad-hoc codesign
#
# Kullanım:  scripts/build.sh <hedef>      (hedefsiz = all)
#
set -euo pipefail

# ----- Yollar & sabitler -----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD="$ROOT/build"
VENDOR="$ROOT/vendor"
DOWNLOADS="$ROOT/downloads"
SRC_APP_DIR="$BUILD/_src"           # kaynak .app (jar + kaynaklar) buraya açılır

APP_NAME="Uyap Doküman Editörü"     # kullanıcıya görünen ad (.app klasörü + display name)
APP="$BUILD/$APP_NAME.app"
ASCII_NAME="UyapDokumanEditoru"     # jpackage'a verilen ASCII ad (executable/CFBundleExecutable)
                                    # NOT: macOS codesign, app adındaki Türkçe karakterlerle
                                    # imzayı bozuyor → executable ASCII, görünen ad sonradan Türkçe.
BUNDLE_ID="tr.gov.uyap.editor"
MAIN_CLASS="tr.com.havelsan.uyap.system.editor.common.WPAppManager"

UDE_URL="${UDE_URL:-https://rayp.adalet.gov.tr/resimler/2/dosya/uyapdokumaneditoru01-06-20263-07-pm.zip}"
UDE_ZIP="${UDE_ZIP:-$DOWNLOADS/ude.zip}"

SQLITE_VER="${SQLITE_VER:-3.46.1.3}"
SQLITE_JAR="$VENDOR/sqlite-jdbc-$SQLITE_VER.jar"
SQLITE_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/$SQLITE_VER/sqlite-jdbc-$SQLITE_VER.jar"

# Gömülecek arm64 Java 8 JRE (runtime). 'jdk' hedefi Azul Zulu 8'i kurar.
JDK8_DEST="$HOME/Library/Java/JavaVirtualMachines/zulu-8-arm64.jdk"
# jpackage için 17+ JDK (build zamanı aracı). 'jpackage-jdk' hedefi Zulu 21 kurar.
JDK21_DEST="$HOME/Library/Java/JavaVirtualMachines/zulu-21-arm64.jdk"

c_ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
c_info() { printf '\033[36m▸\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m!\033[0m %s\n' "$*"; }
c_err()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; }
die()    { c_err "$*"; exit 1; }

arm8_home()  { /usr/libexec/java_home -v 1.8 -a arm64 2>/dev/null || true; }

find_jpackage() {
	local v jh
	for v in 25 24 23 22 21 20 19 18 17; do
		jh="$(/usr/libexec/java_home -v "$v" -a arm64 2>/dev/null || true)"
		[ -n "$jh" ] && [ -x "$jh/bin/jpackage" ] && { echo "$jh/bin/jpackage"; return 0; }
	done
	local home
	for home in $(/usr/libexec/java_home -V 2>&1 | grep -oE '/[^ ]+/Contents/Home' | sort -u); do
		[ -x "$home/bin/jpackage" ] && { echo "$home/bin/jpackage"; return 0; }
	done
	return 1
}

# Azul Zulu (aarch64) tar.gz indir + ~/Library/Java/...'ya kur
install_zulu() {  # $1=java_version  $2=hedef .jdk yolu
	local jv="$1" dest="$2"
	c_info "Azul Zulu $jv (aarch64) indiriliyor…"
	local url
	url="$(curl -s "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$jv&os=macos&arch=aarch64&archive_type=tar.gz&java_package_type=jdk&javafx_bundled=false&latest=true&release_status=ga&availability_types=CA&page=1&page_size=1" \
		| /usr/bin/python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["download_url"])')"
	[ -n "$url" ] || die "Zulu $jv indirme URL'si alınamadı."
	mkdir -p "$DOWNLOADS"; local tmp="$DOWNLOADS/zulu$jv.tgz"
	curl -fsSL -o "$tmp" "$url"
	local stage; stage="$(mktemp -d)"; tar xzf "$tmp" -C "$stage"
	local bundle; bundle="$(find "$stage" -maxdepth 1 -type d -name 'zulu*' | head -1)"
	[ -n "$bundle" ] || die "Zulu $jv arşiv yapısı beklenenden farklı."
	mkdir -p "$(dirname "$dest")"; rm -rf "$dest"; mv "$bundle" "$dest"; rm -rf "$stage"
}

# ----- Hedefler -----

check_deps() {
	c_info "Ön koşullar denetleniyor…"
	local t
	for t in curl unzip zip codesign plutil; do
		command -v "$t" >/dev/null 2>&1 || die "Gerekli araç yok: $t"
	done
	c_ok "Araçlar mevcut"
	local ok=0
	[ -n "$(arm8_home)" ] && c_ok "arm64 Java 8 (runtime): $(arm8_home)" || { c_warn "arm64 Java 8 YOK → scripts/build.sh jdk"; ok=1; }
	if jp="$(find_jpackage)"; then c_ok "jpackage: $jp"; else c_warn "jpackage'lı 17+ JDK YOK → scripts/build.sh jpackage-jdk"; ok=1; fi
	return $ok
}

jdk() {
	[ -n "$(arm8_home)" ] && { c_ok "arm64 Java 8 zaten kurulu."; return 0; }
	install_zulu 8 "$JDK8_DEST"
	[ -n "$(arm8_home)" ] && c_ok "Kuruldu: $JDK8_DEST" || die "Kurulum sonrası arm64 Java 8 görünmüyor."
}

jpackage_jdk() {
	find_jpackage >/dev/null 2>&1 && { c_ok "jpackage zaten var."; return 0; }
	install_zulu 21 "$JDK21_DEST"
	find_jpackage >/dev/null 2>&1 && c_ok "jpackage hazır: $JDK21_DEST" || die "jpackage hâlâ bulunamadı."
}

download() {
	c_info "Kaynak paket hazırlanıyor…"
	mkdir -p "$DOWNLOADS" "$BUILD"
	if [ ! -s "$UDE_ZIP" ]; then
		c_info "İndiriliyor: $UDE_URL"; curl -fL --retry 3 -o "$UDE_ZIP" "$UDE_URL"
	else
		c_ok "Önbellekten: $UDE_ZIP ($(du -h "$UDE_ZIP" | cut -f1))"
	fi
	rm -rf "$SRC_APP_DIR"; mkdir -p "$SRC_APP_DIR"
	local stage; stage="$(mktemp -d)"
	( cd "$stage" && unzip -q "$UDE_ZIP" )
	local src; src="$(find "$stage" -maxdepth 1 -type d -name '*.app' | head -1)"
	[ -n "$src" ] || die "Zip içinde .app bulunamadı."
	cp -R "$src" "$SRC_APP_DIR/app"
	rm -rf "$stage"
	find "$SRC_APP_DIR" -name '._*' -delete 2>/dev/null || true
	[ -s "$SRC_APP_DIR/app/Contents/Java/editor-app.jar" ] || die "editor-app.jar bulunamadı."
	c_ok "Kaynak açıldı: $SRC_APP_DIR/app"
}

deps() {
	c_info "sqlite-jdbc $SQLITE_VER hazırlanıyor…"
	mkdir -p "$VENDOR"
	[ -s "$SQLITE_JAR" ] || curl -fsSL -o "$SQLITE_JAR" "$SQLITE_URL"
	unzip -l "$SQLITE_JAR" | grep 'Mac/aarch64/libsqlitejdbc.dylib' >/dev/null \
		|| die "sqlite-jdbc $SQLITE_VER içinde arm64 dylib yok."
	c_ok "sqlite-jdbc hazır (arm64 dylib doğrulandı)"
}

patch_jar() {
	local JAR="$SRC_APP_DIR/app/Contents/Java/editor-app.jar"
	[ -s "$JAR" ] || die "Önce 'download' çalıştır."
	[ -s "$SQLITE_JAR" ] || die "Önce 'deps' çalıştır."
	c_info "editor-app.jar içinde sqlite 3.7.2 → $SQLITE_VER swap…"
	zip -q -d "$JAR" 'org/sqlite/*' 'native/*' 'META-INF/maven/org.xerial/*' >/dev/null 2>&1 || true
	local stage; stage="$(mktemp -d)"
	( cd "$stage" && unzip -qo "$SQLITE_JAR" -x 'META-INF/MANIFEST.MF' )
	( cd "$stage" && zip -q -r -X "$JAR" org META-INF sqlite-jdbc.properties )
	rm -rf "$stage"
	unzip -l "$JAR" | grep 'Mac/aarch64/libsqlitejdbc.dylib' >/dev/null || die "swap sonrası arm64 dylib yok!"
	unzip -p "$JAR" META-INF/MANIFEST.MF | grep 'WPAppManager' >/dev/null || die "Main-Class kayboldu!"
	c_ok "sqlite swap tamam"
}

package() {
	[ -d "$SRC_APP_DIR/app" ] || die "Önce 'download' çalıştır."
	local jp; jp="$(find_jpackage)" || die "jpackage yok → scripts/build.sh jpackage-jdk"
	local arm8; arm8="$(arm8_home)"; [ -n "$arm8" ] || die "arm64 Java 8 yok → scripts/build.sh jdk"
	[ -x "$arm8/jre/lib/jli/libjli.dylib" ] || die "arm64 Java 8 JRE layout beklenenden farklı: $arm8/jre"

	# runtime-image: jre + release (jre'de release yok → jpackage sürüm okuyamayıp imzayı yarıda kesiyor)
	local runtime; runtime="$BUILD/_runtime"; rm -rf "$runtime"
	cp -R "$arm8/jre" "$runtime"
	[ -f "$arm8/release" ] && cp "$arm8/release" "$runtime/release"

	c_info "jpackage girdisi hazırlanıyor…"
	local JAVA="$SRC_APP_DIR/app/Contents/Java"
	local RES="$SRC_APP_DIR/app/Contents/Resources"
	local in="$BUILD/_input"; rm -rf "$in"; mkdir -p "$in"
	cp "$JAVA/editor-app.jar" "$in/"
	cp "$JAVA/"*.gif "$in/" 2>/dev/null || true
	cp "$JAVA/"*.ico "$in/" 2>/dev/null || true
	cp "$JAVA/BENIOKU.txt" "$in/" 2>/dev/null || true
	local icns; icns="$(ls "$RES/"*.icns 2>/dev/null | head -1)"

	local assoc="$BUILD/_udf.properties"
	printf 'extension=udf\nmime-type=application/x-uyap-udf\ndescription=Uyap Doküman\n' > "$assoc"

	# UDE sürümünü kaynaktan al (jpackage Info.plist'i değiştirir; CFBundleVersion korunmalı)
	local ude_ver; ude_ver="$(plutil -extract CFBundleVersion raw "$SRC_APP_DIR/app/Contents/Info.plist" 2>/dev/null || echo 1.0)"

	c_info "jpackage ile .app paketleniyor (ASCII ad, arm64 Java 8 JRE gömülü, v$ude_ver)…"
	rm -rf "$APP" "$BUILD/$ASCII_NAME.app"
	"$jp" \
		--type app-image --name "$ASCII_NAME" \
		--app-version "$ude_ver" \
		--input "$in" \
		--main-jar editor-app.jar --main-class "$MAIN_CLASS" \
		--arguments getNewWPInstance --arguments EDITOR_TYPE_DOCUMENT \
		--runtime-image "$runtime" \
		--java-options -Xms512M --java-options -Xmx4096M \
		--java-options -Dsun.java2d.dpiaware=false --java-options -Dsun.java2d.uiScale=1 \
		--java-options '-splash:$APPDIR/dokuman_editor_splash_screen_animated.gif' \
		${icns:+--icon "$icns"} \
		--mac-package-identifier "$BUNDLE_ID" \
		--file-associations "$assoc" \
		--dest "$BUILD" 2>&1 | grep -viE 'NoSuchElementException|No value present' || true
	rm -f "$assoc"; rm -rf "$runtime"
	[ -d "$BUILD/$ASCII_NAME.app" ] || die "jpackage .app üretemedi."

	# Görünen adı Türkçe yap (executable ASCII kalır → codesign sorunsuz)
	local plist="$BUILD/$ASCII_NAME.app/Contents/Info.plist"
	plutil -replace CFBundleName -string "$APP_NAME" "$plist"
	plutil -replace CFBundleDisplayName -string "$APP_NAME" "$plist" 2>/dev/null \
		|| plutil -insert CFBundleDisplayName -string "$APP_NAME" "$plist"
	# .app klasörünü Türkçe'ye adlandır (mührü etkilemez; imza Contents/'i mühürler)
	mv "$BUILD/$ASCII_NAME.app" "$APP"
	c_ok "Paketlendi: $APP ($(du -sh "$APP" | cut -f1))"
}

sign() {
	[ -d "$APP" ] || die "Önce 'package' çalıştır."
	c_info "ad-hoc imzalanıyor…"
	find "$APP" -name '._*' -delete 2>/dev/null || true
	# jpackage'ın Java 8 runtime imzası yarım kalıyor → baştan ad-hoc imzala.
	# executable ASCII olduğu için --deep'siz tek imza yeterli ve geçerli.
	codesign --force -s - --identifier "$BUNDLE_ID" "$APP"
	codesign --verify --strict "$APP" 2>/dev/null \
		&& c_ok "İmza geçerli (adhoc, strict)" \
		|| die "İmza doğrulanamadı."
}

all() {
	check_deps || die "Ön koşul eksik (yukarıdaki uyarıya bak: jdk / jpackage-jdk)."
	download; deps; patch_jar; package; sign
	echo
	c_ok "BİTTİ → $APP"
	c_info "Çalıştır: open \"$APP\"   |   Kur: /Applications'a sürükle (çift-tık ile .udf açılır)"
	c_warn "E-imza ancak gerçek kart + arm64 PKCS#11 middleware ile test edilebilir."
}

clean()     { c_info "build/ temizleniyor…"; rm -rf "$BUILD"; c_ok "temiz"; }
distclean() { c_info "build/ + downloads/ + vendor jar temizleniyor…"; rm -rf "$BUILD" "$DOWNLOADS" "$SQLITE_JAR"; c_ok "temiz"; }

help() {
	cat <<EOF
build.sh — UDE native arm64 .app üretici (jpackage, JRE gömülü)

Hedefler:
  all          Tüm hattı çalıştır (varsayılan)
  check-deps   Araçları + arm64 Java 8 + jpackage'ı denetle
  jdk          arm64 Java 8 (gömülecek runtime) yoksa Azul Zulu 8 kur
  jpackage-jdk jpackage'lı 17+ JDK yoksa Azul Zulu 21 kur
  download     Paketi indir + kaynağı aç
  deps         sqlite-jdbc indir + arm64 dylib doğrula
  patch        editor-app.jar içinde sqlite swap
  package      jpackage ile .app üret (.udf ilişkilendirmeli, JRE gömülü)
  sign         ad-hoc codesign
  clean        build/ sil
  distclean    build/ + indirilenler + vendor jar sil

Ortam değişkenleri:
  UDE_URL / UDE_ZIP   Kaynak paket (yeni sürüm / yerel zip)
  SQLITE_VER          sqlite-jdbc sürümü (vars: $SQLITE_VER)
EOF
}

case "${1:-all}" in
	all)          all ;;
	check-deps)   check_deps ;;
	jdk)          jdk ;;
	jpackage-jdk) jpackage_jdk ;;
	download)     download ;;
	deps)         deps ;;
	patch)        patch_jar ;;
	package)      package ;;
	sign)         sign ;;
	clean)        clean ;;
	distclean)    distclean ;;
	help|-h|--help) help ;;
	*) die "Bilinmeyen hedef: $1  (scripts/build.sh help)" ;;
esac
