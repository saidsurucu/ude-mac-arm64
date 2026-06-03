#!/bin/bash
#
# build.sh — UDE (Uyap Doküman Editörü) için native Apple Silicon (arm64) .app üretir.
#
# Engeller ve çözümleri (detay: claudedocs/ARM-derleme-plani.md):
#   1) x64 launcher        -> arm64'ü garantileyen özel kabuk launcher (scripts/launcher.sh)
#   2) JNA (kullanılmıyor)  -> dokunulmaz
#   3) sqlite-jdbc 3.7.2    -> 3.46.x ile değiştirilir (arm64 dylib içerir)
#   + ad-hoc codesign
#
# Kullanım:  scripts/build.sh <hedef>      (hedefsiz = all)
# Hedefler:  all check-deps jdk download deps launcher patch sign clean distclean help
#
set -euo pipefail

# ----- Yollar & sabitler -----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD="$ROOT/build"
VENDOR="$ROOT/vendor"
DOWNLOADS="$ROOT/downloads"

APP_NAME="Uyap Doküman Editörü.app"
APP="$BUILD/$APP_NAME"

# Kaynak paket (sürüme özel — yeni sürümde UDE_URL'i override et veya UDE_ZIP ile yerel zip ver)
UDE_URL="${UDE_URL:-https://rayp.adalet.gov.tr/resimler/2/dosya/uyapdokumaneditoru01-06-20263-07-pm.zip}"
UDE_ZIP="${UDE_ZIP:-$DOWNLOADS/ude.zip}"

# sqlite-jdbc (arm64 native içeren modern sürüm)
SQLITE_VER="${SQLITE_VER:-3.46.1.3}"
SQLITE_JAR="$VENDOR/sqlite-jdbc-$SQLITE_VER.jar"
SQLITE_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/$SQLITE_VER/sqlite-jdbc-$SQLITE_VER.jar"

# arm64 JDK 8 (yoksa 'jdk' hedefi Azul Zulu'yu kurar)
JDK_DEST="$HOME/Library/Java/JavaVirtualMachines/zulu-8-arm64.jdk"

c_ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
c_info() { printf '\033[36m▸\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m!\033[0m %s\n' "$*"; }
c_err()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; }
die()    { c_err "$*"; exit 1; }

arm_java_home() { /usr/libexec/java_home -v 1.8 -a arm64 2>/dev/null || true; }

# ----- Hedefler -----

check_deps() {
	c_info "Ön koşullar denetleniyor…"
	for t in curl unzip zip codesign plutil; do
		command -v "$t" >/dev/null 2>&1 || die "Gerekli araç yok: $t"
	done
	c_ok "Araçlar mevcut (curl, unzip, zip, codesign, plutil)"
	local jh; jh="$(arm_java_home)"
	if [ -n "$jh" ]; then
		c_ok "arm64 JDK 8 bulundu: $jh"
	else
		c_warn "arm64 JDK 8 YOK. Çalıştır: scripts/build.sh jdk  (Azul Zulu 8 aarch64 kurar)"
		return 1
	fi
}

jdk() {
	if [ -n "$(arm_java_home)" ]; then c_ok "arm64 JDK 8 zaten kurulu."; return 0; fi
	c_info "Azul Zulu 8 (aarch64) indiriliyor…"
	local url
	url="$(curl -s "https://api.azul.com/metadata/v1/zulu/packages/?java_version=8&os=macos&arch=aarch64&archive_type=tar.gz&java_package_type=jdk&javafx_bundled=false&latest=true&release_status=ga&availability_types=CA&page=1&page_size=1" \
		| /usr/bin/python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["download_url"])')"
	[ -n "$url" ] || die "Zulu indirme URL'si alınamadı."
	local tmp="$DOWNLOADS/zulu8.tgz"; mkdir -p "$DOWNLOADS"
	curl -fsSL -o "$tmp" "$url"
	local stage; stage="$(mktemp -d)"
	tar xzf "$tmp" -C "$stage"
	local bundle; bundle="$(find "$stage" -maxdepth 1 -type d -name 'zulu*' | head -1)"
	[ -n "$bundle" ] || die "Zulu arşiv yapısı beklenenden farklı."
	mkdir -p "$(dirname "$JDK_DEST")"
	rm -rf "$JDK_DEST"; mv "$bundle" "$JDK_DEST"
	rm -rf "$stage"
	[ -n "$(arm_java_home)" ] && c_ok "Kuruldu: $JDK_DEST" || die "Kurulum sonrası java_home arm64 göremedi."
}

download() {
	c_info "Kaynak paket hazırlanıyor…"
	mkdir -p "$DOWNLOADS" "$BUILD"
	if [ ! -s "$UDE_ZIP" ]; then
		c_info "İndiriliyor: $UDE_URL"
		curl -fL --retry 3 -o "$UDE_ZIP" "$UDE_URL"
	else
		c_ok "Önbellekten: $UDE_ZIP ($(du -h "$UDE_ZIP" | cut -f1))"
	fi
	c_info "build/ içine taze kopya açılıyor…"
	rm -rf "$APP"
	local stage; stage="$(mktemp -d)"
	( cd "$stage" && unzip -q "$UDE_ZIP" )
	local src; src="$(find "$stage" -maxdepth 1 -type d -name '*.app' | head -1)"
	[ -n "$src" ] || die "Zip içinde .app bulunamadı."
	cp -R "$src" "$APP"
	rm -rf "$stage"
	find "$APP" -name '._*' -delete 2>/dev/null || true
	c_ok "Kaynak .app: $APP"
}

deps() {
	c_info "sqlite-jdbc $SQLITE_VER hazırlanıyor…"
	mkdir -p "$VENDOR"
	if [ ! -s "$SQLITE_JAR" ]; then
		curl -fsSL -o "$SQLITE_JAR" "$SQLITE_URL"
	fi
	unzip -l "$SQLITE_JAR" | grep 'Mac/aarch64/libsqlitejdbc.dylib' >/dev/null \
		|| die "sqlite-jdbc $SQLITE_VER içinde arm64 dylib yok."
	c_ok "sqlite-jdbc hazır (arm64 dylib doğrulandı): $SQLITE_JAR"
}

launcher() {
	[ -d "$APP" ] || die "Önce 'download' çalıştır."
	c_info "arm64 launcher kuruluyor…"
	local L="$APP/Contents/MacOS/JavaAppLauncher"
	rm -f "$L"
	cp "$SCRIPT_DIR/launcher.sh" "$L"
	chmod +x "$L"
	# Stub'ın eski sürümleri binary plist okuyamaz; XML'e çevir (zararsız)
	plutil -convert xml1 "$APP/Contents/Info.plist" >/dev/null 2>&1 || true
	c_ok "Launcher: $(file -b "$L" | cut -d, -f1)"
}

patch_jar() {
	[ -d "$APP" ] || die "Önce 'download' çalıştır."
	[ -s "$SQLITE_JAR" ] || die "Önce 'deps' çalıştır."
	local JAR="$APP/Contents/Java/editor-app.jar"
	[ -s "$JAR" ] || die "editor-app.jar yok: $JAR"
	c_info "editor-app.jar içinde sqlite 3.7.2 → $SQLITE_VER swap…"
	# 1) eski sqlite izlerini sil
	zip -q -d "$JAR" 'org/sqlite/*' 'native/*' 'META-INF/maven/org.xerial/*' >/dev/null 2>&1 || true
	# 2) yeni sqlite içeriğini ekle (MANIFEST hariç — editor Main-Class korunur)
	local stage; stage="$(mktemp -d)"
	( cd "$stage" && unzip -qo "$SQLITE_JAR" -x 'META-INF/MANIFEST.MF' )
	( cd "$stage" && zip -q -r -X "$JAR" org META-INF sqlite-jdbc.properties )
	rm -rf "$stage"
	# 3) doğrula
	unzip -l "$JAR" | grep 'Mac/aarch64/libsqlitejdbc.dylib' >/dev/null || die "swap sonrası arm64 dylib yok!"
	unzip -p "$JAR" META-INF/MANIFEST.MF | grep 'WPAppManager' >/dev/null || die "Main-Class kayboldu!"
	c_ok "sqlite swap tamam (arm64 dylib + Main-Class korundu)"
}

sign() {
	[ -d "$APP" ] || die "Önce 'download' çalıştır."
	c_info "ad-hoc imzalanıyor (--deep YOK)…"
	find "$APP" -name '._*' -delete 2>/dev/null || true
	codesign --force -s - --identifier tr.gov.uyap.editor "$APP"
	codesign --verify "$APP" 2>/dev/null && c_ok "İmza geçerli (adhoc)" || die "İmza doğrulanamadı."
}

all() {
	check_deps || die "Ön koşul eksik (yukarıdaki uyarıya bak)."
	download
	deps
	launcher
	patch_jar
	sign
	echo
	c_ok "BİTTİ → $APP"
	c_info "Çalıştır: open \"$APP\"   |   Kur: /Applications'a sürükle"
	c_warn "E-imza ancak gerçek kart + arm64 PKCS#11 middleware ile test edilebilir."
}

clean()     { c_info "build/ temizleniyor…"; rm -rf "$BUILD"; c_ok "temiz"; }
distclean() { c_info "build/ + downloads/ + vendor jar temizleniyor…"; rm -rf "$BUILD" "$DOWNLOADS" "$SQLITE_JAR"; c_ok "temiz"; }

help() {
	cat <<EOF
build.sh — UDE native arm64 .app üretici

Hedefler:
  all          Tüm hattı çalıştır (varsayılan)
  check-deps   Araçları + arm64 JDK 8'i denetle
  jdk          arm64 JDK 8 yoksa Azul Zulu 8 kur
  download     Paketi indir/önbellekten al, build/'e taze aç
  deps         sqlite-jdbc $SQLITE_VER indir + arm64 dylib doğrula
  launcher     arm64 launcher'ı bundle'a kur
  patch        editor-app.jar içinde sqlite swap
  sign         ad-hoc codesign
  clean        build/ sil
  distclean    build/ + indirilenler + vendor jar sil
  help         Bu yardım

Ortam değişkenleri:
  UDE_URL=...     Kaynak paket URL'si (yeni sürüm için)
  UDE_ZIP=...     Yerel kaynak zip yolu (indirme yerine)
  SQLITE_VER=...  sqlite-jdbc sürümü (vars: $SQLITE_VER)
EOF
}

# ----- Dispatcher -----
case "${1:-all}" in
	all)        all ;;
	check-deps) check_deps ;;
	jdk)        jdk ;;
	download)   download ;;
	deps)       deps ;;
	launcher)   launcher ;;
	patch)      patch_jar ;;
	sign)       sign ;;
	clean)      clean ;;
	distclean)  distclean ;;
	help|-h|--help) help ;;
	*) die "Bilinmeyen hedef: $1  (scripts/build.sh help)" ;;
esac
