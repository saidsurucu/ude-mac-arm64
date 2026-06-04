package com.apple.eawt; public interface ScreenSleepListener extends AppEventListener { void screenAboutToSleep(AppEvent.ScreenSleepEvent e); void screenAwoke(AppEvent.ScreenSleepEvent e); }
