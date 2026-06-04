# UDE (Uyap Doküman Editörü) — native Apple Silicon (arm64) build
# Asıl mantık scripts/build.sh içinde. Bu Makefile ince bir sarmalayıcıdır.

SH := bash scripts/build.sh

.PHONY: all check-deps jdk jpackage-jdk download deps shim patch package sign clean distclean help

all: ## ARM64 .app'i üret (varsayılan)
	@$(SH) all

check-deps: ## Araçları, arm64 Java 11 ve jpackage'ı denetle
	@$(SH) check-deps

jdk: ## Gömülecek arm64 Java 11 yoksa Azul Zulu 11 kur
	@$(SH) jdk

jpackage-jdk: ## jpackage'lı 17+ JDK yoksa Azul Zulu 21 kur
	@$(SH) jpackage-jdk

download: ## Kaynak paketi indir ve aç
	@$(SH) download

deps: ## sqlite-jdbc'yi indir + arm64 dylib doğrula
	@$(SH) deps

shim: ## eawt-shim derle (Java 11 com.apple.eawt yerine)
	@$(SH) shim

patch: ## editor-app.jar yamala (sqlite swap + eawt çıkar)
	@$(SH) patch

package: ## jpackage ile .app üret (Java 11 + shim, .udf ilişkilendirmeli)
	@$(SH) package

sign: ## ad-hoc codesign
	@$(SH) sign

clean: ## build/ sil (indirilenleri korur)
	@$(SH) clean

distclean: ## build/ + indirilenler + vendor jar sil
	@$(SH) distclean

help: ## Bu yardım
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort
