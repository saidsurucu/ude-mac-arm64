package com.apple.eawt; public interface SystemSleepListener extends AppEventListener { void systemAboutToSleep(AppEvent.SystemSleepEvent e); void systemAwoke(AppEvent.SystemSleepEvent e); }
