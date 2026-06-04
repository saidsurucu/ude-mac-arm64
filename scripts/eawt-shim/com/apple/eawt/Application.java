package com.apple.eawt;

import java.awt.Image;
import java.awt.PopupMenu;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * com.apple.eawt.Application — Java 11 stub.
 *
 * Java 11'de bu sınıfın YENİ API'si (java.awt.desktop tabanlı) var ama UDE ESKİ
 * API'yi (com.apple.eawt.OpenFilesHandler) kullanıyor. Bu stub, --patch-module ile
 * Java 11'in sürümünün yerine geçer ve ESKİ API'yi sağlar. Dosya açma kaydı,
 * Java 11'in kendi native dispatcher'ına (_AppEventHandler.openFilesDispatcher)
 * REFLECTION ile yapılır → çift-tık native olarak çalışmaya devam eder.
 */
public class Application {

    private static final Application INSTANCE = new Application();

    public static Application getApplication() { return INSTANCE; }

    public Application() { }

    // ESKİ API (UDE bunu çağırır): com.apple.eawt.OpenFilesHandler
    public void setOpenFileHandler(final OpenFilesHandler handler) {
        if (handler == null) { registerOpenFiles(null); return; }
        // ESKİ handler'ı, native dispatcher'ın beklediği java.awt.desktop.OpenFilesHandler'a uyarla
        java.awt.desktop.OpenFilesHandler adapter = new java.awt.desktop.OpenFilesHandler() {
            public void openFiles(java.awt.desktop.OpenFilesEvent e) {
                List<File> files = new ArrayList<File>();
                if (e.getFiles() != null) files.addAll(e.getFiles());
                handler.openFiles(new AppEvent.OpenFilesEvent(files, e.getSearchTerm()));
            }
        };
        registerOpenFiles(adapter);
    }

    // YENİ API (Java 11'in java.awt.Desktop'u içeriden bunu çağırır)
    public void setOpenFileHandler(java.awt.desktop.OpenFilesHandler handler) {
        registerOpenFiles(handler);
    }

    // Java 11'in gerçek native dosya-açma dispatcher'ına reflection ile kaydet
    private static void registerOpenFiles(Object desktopHandler) {
        try {
            Class<?> aeh = Class.forName("com.apple.eawt._AppEventHandler");
            Method getInstance = aeh.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object inst = getInstance.invoke(null);
            Field f = aeh.getDeclaredField("openFilesDispatcher");
            f.setAccessible(true);
            Object dispatcher = f.get(inst);
            // setHandler bir üst sınıfta deklare olabilir → hiyerarşiyi gez
            Method setHandler = null;
            for (Class<?> dc = dispatcher.getClass(); dc != null && setHandler == null; dc = dc.getSuperclass()) {
                try { setHandler = dc.getDeclaredMethod("setHandler", Object.class); } catch (NoSuchMethodException ns) { }
            }
            setHandler.setAccessible(true);
            setHandler.invoke(dispatcher, desktopHandler);
        } catch (Throwable t) {
            // Yedek: java.awt.Desktop (native dispatcher erişilemezse)
            try {
                if (desktopHandler instanceof java.awt.desktop.OpenFilesHandler)
                    java.awt.Desktop.getDesktop().setOpenFileHandler((java.awt.desktop.OpenFilesHandler) desktopHandler);
            } catch (Throwable t2) { }
        }
    }

    // Dock ikonu (Taskbar Application.setDockIconImage'ı çağırmaz → döngü yok)
    public void setDockIconImage(Image image) {
        try { java.awt.Taskbar.getTaskbar().setIconImage(image); } catch (Throwable t) { }
    }
    public Image getDockIconImage() {
        try { return java.awt.Taskbar.getTaskbar().getIconImage(); } catch (Throwable t) { return null; }
    }
    public void setDockIconBadge(String badge) {
        try { java.awt.Taskbar.getTaskbar().setIconBadge(badge); } catch (Throwable t) { }
    }
    public void setDockMenu(PopupMenu menu) {
        try { java.awt.Taskbar.getTaskbar().setMenu(menu); } catch (Throwable t) { }
    }
    public PopupMenu getDockMenu() {
        try { return java.awt.Taskbar.getTaskbar().getMenu(); } catch (Throwable t) { return null; }
    }

    // Diğer handler'lar: java.awt.Desktop'a köprülemek DÖNGÜ yaratır (Desktop → bu sınıf),
    // o yüzden no-op. UDE/WebLaF bunlar olmadan da çalışır.
    public void setAboutHandler(AboutHandler h) { }
    public void setPreferencesHandler(PreferencesHandler h) { }
    public void setQuitHandler(QuitHandler h) { }
    public void setQuitStrategy(QuitStrategy s) { }
    public void setOpenURIHandler(OpenURIHandler h) { }
    public void setPrintFileHandler(PrintFilesHandler h) { }
    public void addAppEventListener(AppEventListener l) { }
    public void removeAppEventListener(AppEventListener l) { }
    public void addApplicationListener(ApplicationListener l) { }
    public void removeApplicationListener(ApplicationListener l) { }
    public void setEnabledAboutMenu(boolean e) { }
    public void setEnabledPreferencesMenu(boolean e) { }
    public boolean isAboutMenuItemPresent() { return false; }
    public void addAboutMenuItem() { }
    public void removeAboutMenuItem() { }
    public boolean isPreferencesMenuItemPresent() { return false; }
    public void addPreferencesMenuItem() { }
    public void removePreferencesMenuItem() { }
    public void requestForeground(boolean allWindows) {
        try { java.awt.Desktop.getDesktop().requestForeground(allWindows); } catch (Throwable t) { }
    }
    public void requestUserAttention(boolean critical) { }
    public void openHelpViewer() { }
    public void setDefaultMenuBar(javax.swing.JMenuBar menuBar) {
        try { java.awt.Desktop.getDesktop().setDefaultMenuBar(menuBar); } catch (Throwable t) { }
    }
    public void disableSuddenTermination() { }
    public void enableSuddenTermination() { }
}
