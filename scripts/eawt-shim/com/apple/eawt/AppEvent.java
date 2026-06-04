package com.apple.eawt;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Java 9+'da kaldırılan com.apple.eawt.AppEvent için saf-Java stub.
 * UDE'nin gömülü (Java 8 native bağımlı) sürümü yerine konur.
 */
public class AppEvent {

    public static class FilesEvent extends AppEvent {
        private final List<File> files;
        FilesEvent(List<File> files) {
            this.files = (files == null) ? Collections.<File>emptyList() : files;
        }
        public List<File> getFiles() { return files; }
    }

    public static class OpenFilesEvent extends FilesEvent {
        private final String searchTerm;
        public OpenFilesEvent(List<File> files, String searchTerm) {
            super(files);
            this.searchTerm = searchTerm;
        }
        public OpenFilesEvent(List<File> files) { this(files, ""); }
        public String getSearchTerm() { return searchTerm; }
    }

    public static class PrintFilesEvent extends FilesEvent {
        public PrintFilesEvent(List<File> files) { super(files); }
    }

    public static class OpenURIEvent extends AppEvent {
        private final java.net.URI uri;
        public OpenURIEvent(java.net.URI uri) { this.uri = uri; }
        public java.net.URI getURI() { return uri; }
    }

    public static class AboutEvent extends AppEvent {}
    public static class PreferencesEvent extends AppEvent {}
    public static class QuitEvent extends AppEvent {}
    public static class AppForegroundEvent extends AppEvent {}
    public static class AppHiddenEvent extends AppEvent {}
    public static class AppReOpenedEvent extends AppEvent {}
    public static class ScreenSleepEvent extends AppEvent {}
    public static class SystemSleepEvent extends AppEvent {}
    public static class UserSessionEvent extends AppEvent {}
    public static class FullScreenEvent extends AppEvent {}
}
