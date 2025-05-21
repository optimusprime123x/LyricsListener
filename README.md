# Lyric Listener

## Android app to display beautiful, synced lyrics for your currently playing music.

<img src="https://github.com/user-attachments/assets/745ad5e3-70d2-4504-b189-c13f5eaef1bb" alt="icon" width="256" height="256"/>

### Features
- Synchronized (and plain) Lyrics Overlay: See synced lyrics for currently playing media, displayed over your music app. (Spotify, apple music, poweramp, and others supported. Youtube might cause issues due to search issues)
- Modern UI: Material 3 design, light & dark themes, and smooth animations for a delightful experience.
- Background Service: Lyrics overlay persists as you navigate between apps.
- Permissions Helper: Guided UI for requesting all required Android permissions. (see below)

 ### Permissions Explained
- Notification Access: Needed to detect currently playing track and fetch correct lyrics.
- Overlay Permission: Allows the app to display lyrics on top of other apps.
- Post Notifications: Required for persistent notifications.
- Disable Battery Optimization: Optional, but recommended for uninterrupted background lyric service.

### Known Issues 
- Switching between two music-player apps - seek the currrent song a bit ahead to fix.
- YouTube support might be limited due to search issues.



