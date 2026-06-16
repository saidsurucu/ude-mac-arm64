/*
 * Native diyalog (NSSavePanel/NSOpenPanel) pano kısayolları.
 *
 * Sorun: AWT FileDialog'un gösterdiği native kaydetme panelinde Cmd+V/C/X/A
 * çalışmaz. macOS'ta bu kısayolları metin alanına ileten şey menü çubuğundaki
 * Edit menüsünün key-equivalent'larıdır; Java uygulamalarının NSMenu'sünde
 * Edit menüsü yoktur. Panel AWT/Swing olay zincirinin tamamen dışında
 * olduğundan javaagent'taki KeyEventDispatcher'lar da olayı göremez.
 *
 * Çözüm: NSEvent local monitor — YALNIZ key window bir NSSavePanel
 * (NSOpenPanel onun alt sınıfı) iken Cmd kısayolunu standart AppKit
 * eylemine (paste: vb., first responder'a) çevirip olayı yutar. Java
 * pencereleri odaktayken olaylara dokunulmaz; menü çubuğu değiştirilmez
 * (AWT'nin CMenuBar yönetimiyle çakışma riski yok).
 *
 * Yükleme: macos-textkeys agent'ı premain'de System.load eder; constructor
 * main queue'ya kurulum bırakır (NSApp henüz yoksa 1 sn arayla yeniden dener).
 */
#import <Cocoa/Cocoa.h>

static void udeInstallMonitor(int remaining) {
    if (NSApp == nil) {
        if (remaining > 0) {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)NSEC_PER_SEC),
                           dispatch_get_main_queue(),
                           ^{ udeInstallMonitor(remaining - 1); });
        }
        return;
    }
    [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
                                          handler:^NSEvent *(NSEvent *e) {
        NSEventModifierFlags mods = [e modifierFlags] & NSEventModifierFlagDeviceIndependentFlagsMask;
        if (!(mods & NSEventModifierFlagCommand)) return e;
        if (mods & (NSEventModifierFlagControl | NSEventModifierFlagOption)) return e;
        NSWindow *kw = [NSApp keyWindow];
        if (![kw isKindOfClass:[NSSavePanel class]]) return e;
        NSString *c = [[e charactersIgnoringModifiers] lowercaseString];
        SEL sel = NULL;
        if ([c isEqualToString:@"v"]) sel = @selector(paste:);
        else if ([c isEqualToString:@"c"]) sel = @selector(copy:);
        else if ([c isEqualToString:@"x"]) sel = @selector(cut:);
        else if ([c isEqualToString:@"a"]) sel = @selector(selectAll:);
        else if ([c isEqualToString:@"z"]) sel = (mods & NSEventModifierFlagShift)
                                              ? NSSelectorFromString(@"redo:") : @selector(undo:);
        if (sel == NULL) return e;
        [NSApp sendAction:sel to:nil from:nil];
        return nil;
    }];
}

/*
 * Başlık çubuğu metnini gizle (Dock pencere adları için).
 *
 * Sorun: SKIN agent'ı (MacLook) belge adını yerel NSWindow.title olarak KORUR
 * ki Dock sağ-tık menüsü / Pencere menüsü / Mission Control doğru adı göstersin.
 * Ama transparentTitleBar modunda macOS başlık METNİNİ pencere ortasına çizer ve
 * dar pencerede hızlı erişim ikonlarının üstüne düşürür. Zulu 11 AWT'de
 * NSWindow.titleVisibility erişimi yok (apple.awt.windowTitleVisible yalnız 17+).
 *
 * Çözüm: belge pencerelerinde (titlebarAppearsTransparent==YES) titleVisibility'yi
 * NSWindowTitleHidden yap — başlık METNİ çizilmez ama window.title (Dock/menü)
 * korunur. AWT setTitle: yalnız dizgeyi değiştirir, görünürlüğü sıfırlamaz; yine
 * de NSWindowDidUpdateNotification ile (çizimden ÖNCE, başlık titreşimi olmadan)
 * her güncellemede yeniden uygulanır (zaten gizliyse no-op). NSSavePanel/diyaloglar
 * (şeffaf başlık çubuğu yok) dokunulmaz → kendi başlıklarını korur.
 */
static void udeHideDocTitle(NSWindow *w) {
    if (w == nil || ![w isKindOfClass:[NSWindow class]]) return;
    if (![w titlebarAppearsTransparent]) return;
    if (([w styleMask] & NSWindowStyleMaskTitled) == 0) return;
    if ([w titleVisibility] != NSWindowTitleHidden)
        [w setTitleVisibility:NSWindowTitleHidden];
}

static void udeInstallTitleHider(void) {
    [[NSNotificationCenter defaultCenter]
        addObserverForName:NSWindowDidUpdateNotification
                    object:nil
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(NSNotification *n) { udeHideDocTitle((NSWindow *)[n object]); }];
    for (NSWindow *w in [NSApp windows]) udeHideDocTitle(w);  // ilk tarama
}

__attribute__((constructor))
static void ude_nativedialogkeys_init(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        udeInstallMonitor(120);
        udeInstallTitleHider();
    });
}
