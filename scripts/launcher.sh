#!/bin/bash
#
# Uyap Doküman Editörü — Apple Silicon (arm64) launcher
#
# Bu betik .app içinde CFBundleExecutable olarak çalışır. Sistemde kurulu
# NATIVE arm64 bir Java 8 bulur ve editörü Rosetta olmadan başlatır.
# JVM parametreleri orijinal Info.plist ile birebir aynıdır.
#
set -u

SELF="$(cd "$(dirname "$0")" && pwd)"        # .../Contents/MacOS
CONTENTS="$(cd "$SELF/.." && pwd)"           # .../Contents

# 1) NATIVE arm64 Java 8 bul (default java_home x64 verebileceği için -a arm64 zorunlu)
JH="$(/usr/libexec/java_home -v 1.8 -a arm64 2>/dev/null)"

# 2) Yedek: .app içine gömülü runtime (varsa)
if [ -z "${JH}" ] && [ -x "$CONTENTS/PlugIns/zulu-8.jdk/Contents/Home/bin/java" ]; then
	JH="$CONTENTS/PlugIns/zulu-8.jdk/Contents/Home"
fi

if [ -z "${JH}" ] || [ ! -x "$JH/bin/java" ]; then
	osascript -e 'display alert "Uyap Doküman Editörü" message "Apple Silicon (arm64) için Java 8 bulunamadı.\n\nLütfen arm64 bir JDK 8 kurun (ör. Azul Zulu 8 veya Liberica 8 aarch64)."' >/dev/null 2>&1
	exit 1
fi

# Orijinal launcher Contents dizininde çalışıyordu; göreli kaynak yüklemeleri için koru
cd "$CONTENTS" || exit 1

exec "$JH/bin/java" \
	-splash:"$CONTENTS/Java/dokuman_editor_splash_screen_animated.gif" \
	-Xms512M \
	-Xmx4096M \
	-Dsun.java2d.dpiaware=false \
	-Dsun.java2d.uiScale=1 \
	-cp "$CONTENTS/Java/editor-app.jar" \
	tr.com.havelsan.uyap.system.editor.common.WPAppManager \
	getNewWPInstance EDITOR_TYPE_DOCUMENT
