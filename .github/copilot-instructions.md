# LyricsListener - GitHub Copilot Instructions

## Project Overview
LyricsListener is a Flutter-based Android application that displays synchronized lyrics in an overlay window. The app monitors currently playing media and fetches synced lyrics from external APIs to show them on top of other applications.

## Architecture
- **Frontend**: Flutter/Dart with Material 3 expressive design
- **Backend**: Kotlin native code for Android services
- **Communication**: Method channels and event channels for Flutter-Kotlin interop
- **Key Components**:
  - `LyricService`: NotificationListenerService that monitors media playback
  - Overlay window system using WindowManager
  - Lyrics fetching and caching mechanism
  - Permissions management system

## Design Guidelines

### Material 3 Expressive Design
- Follow Material 3 design principles with expressive, dynamic theming
- Use `ColorScheme` from seed colors for consistent theming
- Support both light and dark themes
- Implement smooth animations and transitions
- Use Google Fonts for typography consistency

### UI/UX Standards
- Maintain consistency with the expressive Material 3 style
- Ensure proper contrast ratios for accessibility
- Use dynamic colors where appropriate
- Implement smooth, delightful animations
- Follow Android's permission request best practices

## Code Conventions

### Dart/Flutter
- Follow the official Dart style guide
- Use `flutter_lints` package rules (configured in `analysis_options.yaml`)
- Prefer single quotes for strings (unless interpolating)
- Use `const` constructors where possible for performance
- Keep widgets small and focused - extract reusable components
- Use meaningful variable and function names
- Organize imports: Dart SDK, Flutter SDK, packages, relative imports

### Kotlin
- Follow Kotlin coding conventions
- Use coroutines for asynchronous operations
- Package name: `dev.optimus.lyricslistener`
- Use data classes for models
- Leverage Kotlin's null safety features
- Use extension functions when appropriate
- Keep Android service code well-organized and documented

### Method Channel Communication
- Channel names should use the pattern: `dev.optimus.lyricslistener/<feature>`
- Document expected message formats
- Handle errors gracefully on both sides
- Use EventChannel for streaming data (e.g., debug logs)
- Use MethodChannel for request-response patterns

## Project Structure
```
lib/
  main.dart              # Main application entry point
android/
  app/src/main/kotlin/dev/optimus/lyricslistener/
    MainActivity.kt      # Flutter activity
    LyricService.kt      # Background service for lyrics
    LyricsAdapter.kt     # RecyclerView adapter
    LyricsCacheManager.kt # Lyrics caching
    LyricsData.kt        # Data models
    BootReceiver.kt      # Boot receiver
```

## Android-Specific Requirements

### Permissions
The app requires several critical permissions:
1. **Notification Access**: To detect currently playing media
2. **Overlay Permission**: To display lyrics window over other apps
3. **Post Notifications**: For persistent notifications
4. **Battery Optimization**: Optional but recommended for background service

Always provide clear explanations when requesting permissions.

### Minimum SDK
- Minimum SDK: API 26 (Android 8.0)
- Target SDK: Latest stable Android version
- Handle API level differences appropriately (e.g., Android 13+ notification permissions)

## Development Commands

### Setup
```bash
flutter pub get                  # Install dependencies
```

### Build
```bash
flutter build apk --release      # Build release APK
flutter build apk --debug        # Build debug APK
```

### Testing
```bash
flutter test                     # Run unit tests
flutter analyze                  # Run static analysis
```

### Linting
```bash
flutter analyze                  # Static analysis with flutter_lints
```

## Key Features to Maintain

### Synchronized Lyrics
- Fetch synced lyrics from external APIs
- Parse and display line-by-line synchronization
- Handle both synced and plain lyrics
- Implement smooth scrolling and highlighting
- Cache lyrics for offline use

### Overlay Window
- Draggable and resizable overlay
- Remember window position (user preference)
- Dynamic colors based on album art or user preference
- Show currently playing track info
- Minimize/maximize functionality

### Background Service
- Persistent notification for foreground service
- Monitor media session changes
- Handle app switching gracefully
- Implement proper service lifecycle management

### Theming
- Seed color customization
- Dynamic color scheme generation
- Light/dark mode support
- Custom colors for lyrics window (title, background, highlight)

## Dependencies
- `flutter_svg`: For SVG asset rendering
- `google_fonts`: For custom typography
- `shared_preferences`: For storing user preferences
- `url_launcher`: For opening external links
- Ktor (Kotlin): For HTTP requests in native code
- Kotlinx Serialization: For JSON parsing

## Common Patterns

### Shared Preferences Keys
Use descriptive constant names with the pattern: `_<feature>Key`
```dart
const String _seedColorKey = 'seed_color';
const String _rememberWindowPositionKey = 'remember_window_position';
```

### Method Channel Communication
```dart
const MethodChannel _platformChannel = 
    MethodChannel('dev.optimus.lyricslistener/permissions');
```

### Color Definitions
Define default colors as constants:
```dart
const Color _defaultSeedColor = Color(0xFF6750A4);
const Color _defaultLyricsWindowBackgroundColor = Color(0xDD212121);
```

## Known Limitations
- Android-only (no iOS support)
- YouTube support may be limited due to search API issues
- Requires notification listener permission (system-level permission)
- Battery optimization should be disabled for best performance

## Testing Considerations
- Test permission flows thoroughly
- Verify overlay window behavior across different Android versions
- Test with various music players (Spotify, Apple Music, Poweramp, etc.)
- Validate lyrics synchronization accuracy
- Test theme switching and color customization

## Security & Privacy
- Do not store user credentials
- Handle sensitive permissions with care
- Minimize data collection
- Respect user privacy - only access what's needed for functionality
- Clear explanations for each permission requirement

## Performance
- Use `const` constructors to reduce rebuilds
- Cache lyrics to minimize network requests
- Implement efficient scrolling for lyrics display
- Optimize overlay window rendering
- Handle memory management in background service

## Contribution Guidelines
- Write clean, maintainable code
- Follow existing code style and patterns
- Test changes across different Android versions
- Update documentation for significant changes
- Ensure Material 3 design consistency
