import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:lyricslistener/pages/manage_lyrics_page.dart';
import 'package:lyricslistener/pages/add_custom_lyrics_page.dart';
import 'package:lyricslistener/widgets/expressive_page_transitions.dart';
import 'package:lyricslistener/widgets/expressive_refresh_indicator.dart'
    as expressive_refresh;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:material_new_shapes/material_new_shapes.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:url_launcher/url_launcher.dart';

const MethodChannel _platformChannel = MethodChannel(
  'dev.optimus.lyricslistener/permissions',
);
const EventChannel _debugLogChannel = EventChannel(
  'dev.optimus.lyricslistener/debugLogs',
);

const int _android13ApiLevel = 33;
const int _android12ApiLevel = 31;

const _defaultSeedColor = Color(0xFF6750A4);

const String _seedColorKey = 'seed_color';
const String _materialYouThemingKey = 'material_you_theming';
const String _rememberWindowPositionKey = 'remember_window_position';
const String _hideWindowOnPauseKey = 'hide_lyrics_window_on_pause';
const String _dynamicLyricsWindowColorsKey = 'lyrics_window_dynamic_colors';
const String _simulateLegacyOverlayKey = 'simulate_legacy_overlay';
const String _lyricsWindowTitleColorKey = 'lyrics_window_title_color';
const String _lyricsWindowBackgroundColorKey = 'lyrics_window_background_color';
const String _lyricsWindowHighlightColorKey = 'lyrics_window_highlight_color';
const String _lyricsWindowSizeKey = 'lyrics_window_size';

const Color _defaultLyricsWindowTitleColor = Color(0xF0E8E8E8);
const Color _defaultLyricsWindowBackgroundColor = Color(0xE6181818);
const Color _defaultLyricsWindowHighlightColor = Color(0x55C8C8C8);

const Curve expressiveSpringCurve = Curves.elasticOut;
const Curve expressiveStandardCurve = Curves.easeOutCubic;

bool _isFlutterTestEnvironment() {
  try {
    return Platform.environment.containsKey('FLUTTER_TEST');
  } catch (_) {
    return false;
  }
}

class _MascotSvgColorMapper extends ColorMapper {
  _MascotSvgColorMapper(this.colorScheme);

  final ColorScheme colorScheme;

  @override
  Color substitute(
    String? id,
    String elementName,
    String attributeName,
    Color color,
  ) {
    switch (color.value) {
      case 0xFF12314D:
      case 0xFF12316D:
        return colorScheme.onSurfaceVariant;
      case 0xFF32759E:
        return colorScheme.secondary;
      case 0xFF3795B6:
      case 0xFF3A7DA6:
      case 0xFF3F98BC:
        return colorScheme.secondaryContainer;
      case 0xFF48A1C2:
        return colorScheme.primaryContainer;
      case 0xFFA2D9E7:
        return colorScheme.surface;
      case 0xFFEC8356:
        return colorScheme.tertiary;
      case 0xFF2F2E41:
        return colorScheme.onSurface;
      case 0xFF3F3D56:
        return colorScheme.onSurfaceVariant;
      case 0xFF6C63FF:
        return colorScheme.primary;
      default:
        return color;
    }
  }
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final int? savedColorValue = prefs.getInt(_seedColorKey);
  final bool initialMaterialYouThemingEnabled =
      prefs.getBool(_materialYouThemingKey) ?? true;
  final Color initialSeedColor = savedColorValue != null
      ? Color(savedColorValue)
      : _defaultSeedColor;

  runApp(
    MyApp(
      initialSeedColor: initialSeedColor,
      initialMaterialYouThemingEnabled: initialMaterialYouThemingEnabled,
    ),
  );
}

class MyApp extends StatefulWidget {
  const MyApp({
    super.key,
    required this.initialSeedColor,
    required this.initialMaterialYouThemingEnabled,
  });

  final Color initialSeedColor;
  final bool initialMaterialYouThemingEnabled;

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  ThemeMode _themeMode = ThemeMode.system;
  late Color _seedColor;
  late bool _isMaterialYouThemingEnabled;

  @override
  void initState() {
    super.initState();
    _seedColor = widget.initialSeedColor;
    _isMaterialYouThemingEnabled = widget.initialMaterialYouThemingEnabled;
  }

  void _toggleTheme() {
    setState(() {
      _themeMode = _themeMode == ThemeMode.light
          ? ThemeMode.dark
          : ThemeMode.light;
    });
  }

  void _changeSeedColor(Color color) async {
    setState(() {
      _seedColor = color;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_seedColorKey, color.value);
  }

  void _setMaterialYouThemingEnabled(bool value) async {
    setState(() {
      _isMaterialYouThemingEnabled = value;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_materialYouThemingKey, value);
  }

  @override
  Widget build(BuildContext context) {
    final baseHeadlineTheme = GoogleFonts.robotoFlexTextTheme(
      Theme.of(context).textTheme,
    );
    final baseBodyTheme = GoogleFonts.manropeTextTheme(
      Theme.of(context).textTheme,
    );
    return DynamicColorBuilder(
      builder: (lightDynamic, darkDynamic) {
        final bool useDynamicColors =
            _isMaterialYouThemingEnabled &&
            lightDynamic != null &&
            darkDynamic != null;

        final baseLightColorScheme = useDynamicColors
            ? lightDynamic
            : ColorScheme.fromSeed(
                seedColor: _seedColor,
                brightness: Brightness.light,
              );

        final lightTextTheme = _buildExpressiveTextTheme(
          baseHeadlineTheme,
          baseBodyTheme,
          baseLightColorScheme.onSurface,
        );
        final testSplashFactory = _isFlutterTestEnvironment()
            ? InkRipple.splashFactory
            : null;

        final lightTheme = ThemeData(
          colorScheme: baseLightColorScheme,
          useMaterial3: true,
          brightness: Brightness.light,
          textTheme: lightTextTheme,
          splashFactory: testSplashFactory,
          pageTransitionsTheme: expressivePageTransitionsTheme,
          cardTheme: CardThemeData(
            elevation: 1,
            shadowColor: baseLightColorScheme.primary.withValues(alpha: 0.16),
            margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
              side: BorderSide(color: baseLightColorScheme.outlineVariant),
            ),
          ),
          elevatedButtonTheme: ElevatedButtonThemeData(
            style: ElevatedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(28),
              ),
              textStyle: lightTextTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          filledButtonTheme: FilledButtonThemeData(
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(28),
              ),
              textStyle: lightTextTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          outlinedButtonTheme: OutlinedButtonThemeData(
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(28),
              ),
              textStyle: lightTextTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          appBarTheme: AppBarTheme(
            backgroundColor: baseLightColorScheme.surfaceContainer,
            elevation: 0,
            titleTextStyle: lightTextTheme.titleLarge?.copyWith(
              color: baseLightColorScheme.onSurface,
            ),
            iconTheme: IconThemeData(
              color: baseLightColorScheme.onSurfaceVariant,
            ),
          ),
          dividerTheme: DividerThemeData(
            thickness: 1,
            color: baseLightColorScheme.outlineVariant,
          ),
          listTileTheme: ListTileThemeData(
            iconColor: baseLightColorScheme.primary,
            titleTextStyle: lightTextTheme.titleMedium,
            subtitleTextStyle: lightTextTheme.bodyMedium,
            minVerticalPadding: 16,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
          ),
          expansionTileTheme: ExpansionTileThemeData(
            iconColor: baseLightColorScheme.primary,
            collapsedIconColor: baseLightColorScheme.onSurfaceVariant,
            textColor: baseLightColorScheme.primary,
            collapsedTextColor: baseLightColorScheme.onSurface,
            backgroundColor: baseLightColorScheme.surfaceContainerLow,
            collapsedBackgroundColor: baseLightColorScheme.surfaceContainer,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
            collapsedShape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
          ),
          inputDecorationTheme: InputDecorationTheme(
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(28),
              borderSide: BorderSide.none,
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(28),
              borderSide: BorderSide(color: baseLightColorScheme.outline),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(28),
              borderSide: BorderSide(
                color: baseLightColorScheme.primary,
                width: 2,
              ),
            ),
            filled: true,
            fillColor: baseLightColorScheme.surfaceContainerHighest,
          ),
          snackBarTheme: SnackBarThemeData(
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
            backgroundColor: baseLightColorScheme.inverseSurface,
            contentTextStyle: lightTextTheme.bodyMedium?.copyWith(
              color: baseLightColorScheme.onInverseSurface,
            ),
            actionTextColor: baseLightColorScheme.inversePrimary,
          ),
          dialogTheme: DialogThemeData(
            shape: const RoundedRectangleBorder(
              borderRadius: BorderRadius.only(
                topLeft: Radius.circular(14),
                topRight: Radius.circular(28),
                bottomLeft: Radius.circular(28),
                bottomRight: Radius.circular(28),
              ),
            ),
            backgroundColor: baseLightColorScheme.surfaceContainerHigh,
            titleTextStyle: lightTextTheme.headlineSmall?.copyWith(
              color: baseLightColorScheme.onSurface,
              fontWeight: FontWeight.w700,
            ),
          ),
          navigationBarTheme: NavigationBarThemeData(
            indicatorShape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
            indicatorColor: baseLightColorScheme.secondaryContainer,
            backgroundColor: baseLightColorScheme.surfaceContainer,
            labelTextStyle: WidgetStateProperty.resolveWith((states) {
              if (states.contains(WidgetState.selected)) {
                return lightTextTheme.labelMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: baseLightColorScheme.onSurface,
                );
              }
              return lightTextTheme.labelMedium?.copyWith(
                color: baseLightColorScheme.onSurfaceVariant,
              );
            }),
          ),
          bottomSheetTheme: BottomSheetThemeData(
            shape: const RoundedRectangleBorder(
              borderRadius: BorderRadius.only(
                topLeft: Radius.circular(28),
                topRight: Radius.circular(28),
              ),
            ),
            backgroundColor: baseLightColorScheme.surfaceContainerLow,
          ),
        );

        final baseDarkColorScheme = useDynamicColors
            ? darkDynamic
            : ColorScheme.fromSeed(
                seedColor: _seedColor,
                brightness: Brightness.dark,
              );
        final darkTextTheme = _buildExpressiveTextTheme(
          baseHeadlineTheme,
          baseBodyTheme,
          baseDarkColorScheme.onSurface,
        );

        final darkTheme = ThemeData(
          colorScheme: baseDarkColorScheme,
          useMaterial3: true,
          brightness: Brightness.dark,
          textTheme: darkTextTheme,
          splashFactory: testSplashFactory,
          pageTransitionsTheme: expressivePageTransitionsTheme,
          cardTheme: CardThemeData(
            elevation: 1,
            shadowColor: baseDarkColorScheme.primary.withValues(alpha: 0.24),
            margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
              side: BorderSide(color: baseDarkColorScheme.outlineVariant),
            ),
          ),
          elevatedButtonTheme: ElevatedButtonThemeData(
            style: ElevatedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(28),
              ),
              textStyle: darkTextTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          filledButtonTheme: FilledButtonThemeData(
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(28),
              ),
              textStyle: darkTextTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          outlinedButtonTheme: OutlinedButtonThemeData(
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(28),
              ),
              textStyle: darkTextTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          appBarTheme: AppBarTheme(
            backgroundColor: baseDarkColorScheme.surfaceContainer,
            elevation: 0,
            titleTextStyle: darkTextTheme.titleLarge?.copyWith(
              color: baseDarkColorScheme.onSurface,
            ),
            iconTheme: IconThemeData(
              color: baseDarkColorScheme.onSurfaceVariant,
            ),
          ),
          dividerTheme: DividerThemeData(
            thickness: 1,
            color: baseDarkColorScheme.outlineVariant,
          ),
          listTileTheme: ListTileThemeData(
            iconColor: baseDarkColorScheme.primary,
            titleTextStyle: darkTextTheme.titleMedium,
            subtitleTextStyle: darkTextTheme.bodyMedium,
            minVerticalPadding: 16,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
          ),
          expansionTileTheme: ExpansionTileThemeData(
            iconColor: baseDarkColorScheme.primary,
            collapsedIconColor: baseDarkColorScheme.onSurfaceVariant,
            textColor: baseDarkColorScheme.primary,
            collapsedTextColor: baseDarkColorScheme.onSurface,
            backgroundColor: baseDarkColorScheme.surfaceContainerLow,
            collapsedBackgroundColor: baseDarkColorScheme.surfaceContainer,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
            collapsedShape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
          ),
          inputDecorationTheme: InputDecorationTheme(
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(28),
              borderSide: BorderSide.none,
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(28),
              borderSide: BorderSide(color: baseDarkColorScheme.outline),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(28),
              borderSide: BorderSide(
                color: baseDarkColorScheme.primary,
                width: 2,
              ),
            ),
            filled: true,
            fillColor: baseDarkColorScheme.surfaceContainerHighest,
          ),
          snackBarTheme: SnackBarThemeData(
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
            backgroundColor: baseDarkColorScheme.inverseSurface,
            contentTextStyle: darkTextTheme.bodyMedium?.copyWith(
              color: baseDarkColorScheme.onInverseSurface,
            ),
            actionTextColor: baseDarkColorScheme.inversePrimary,
          ),
          dialogTheme: DialogThemeData(
            shape: const RoundedRectangleBorder(
              borderRadius: BorderRadius.only(
                topLeft: Radius.circular(14),
                topRight: Radius.circular(28),
                bottomLeft: Radius.circular(28),
                bottomRight: Radius.circular(28),
              ),
            ),
            backgroundColor: baseDarkColorScheme.surfaceContainerHigh,
            titleTextStyle: darkTextTheme.headlineSmall?.copyWith(
              color: baseDarkColorScheme.onSurface,
              fontWeight: FontWeight.w700,
            ),
          ),
          navigationBarTheme: NavigationBarThemeData(
            indicatorShape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(28),
            ),
            indicatorColor: baseDarkColorScheme.secondaryContainer,
            backgroundColor: baseDarkColorScheme.surfaceContainer,
            labelTextStyle: WidgetStateProperty.resolveWith((states) {
              if (states.contains(WidgetState.selected)) {
                return darkTextTheme.labelMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: baseDarkColorScheme.onSurface,
                );
              }
              return darkTextTheme.labelMedium?.copyWith(
                color: baseDarkColorScheme.onSurfaceVariant,
              );
            }),
          ),
          bottomSheetTheme: BottomSheetThemeData(
            shape: const RoundedRectangleBorder(
              borderRadius: BorderRadius.only(
                topLeft: Radius.circular(28),
                topRight: Radius.circular(28),
              ),
            ),
            backgroundColor: baseDarkColorScheme.surfaceContainerLow,
          ),
        );

        return MaterialApp(
          title: 'Lyric Listener',
          theme: lightTheme,
          darkTheme: darkTheme,
          themeMode: _themeMode,
          home: MainScreen(
            toggleTheme: _toggleTheme,
            seedColor: _seedColor,
            onSeedColorChanged: _changeSeedColor,
            isMaterialYouThemingEnabled: _isMaterialYouThemingEnabled,
            onMaterialYouThemingChanged: _setMaterialYouThemingEnabled,
          ),
        );
      },
    );
  }

  TextTheme _buildExpressiveTextTheme(
    TextTheme headlineTheme,
    TextTheme bodyTheme,
    Color onSurfaceColor,
  ) {
    return bodyTheme
        .copyWith(
          displayLarge: headlineTheme.displayLarge?.copyWith(
            fontWeight: FontWeight.bold,
            color: onSurfaceColor,
          ),
          displayMedium: headlineTheme.displayMedium?.copyWith(
            fontWeight: FontWeight.bold,
            color: onSurfaceColor,
          ),
          displaySmall: headlineTheme.displaySmall?.copyWith(
            fontWeight: FontWeight.bold,
            color: onSurfaceColor,
          ),
          headlineLarge: headlineTheme.headlineLarge?.copyWith(
            fontWeight: FontWeight.bold,
            color: onSurfaceColor,
          ),
          headlineMedium: headlineTheme.headlineMedium?.copyWith(
            fontWeight: FontWeight.w600,
            color: onSurfaceColor,
          ),
          headlineSmall: headlineTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.w600,
            color: onSurfaceColor,
          ),
          titleLarge: headlineTheme.titleLarge,
          titleMedium: bodyTheme.titleMedium,
          titleSmall: bodyTheme.titleSmall,
          bodyLarge: bodyTheme.bodyLarge,
          bodyMedium: bodyTheme.bodyMedium,
          bodySmall: bodyTheme.bodySmall,
          labelLarge: bodyTheme.labelLarge,
          labelMedium: bodyTheme.labelMedium,
          labelSmall: bodyTheme.labelSmall,
        )
        .apply(bodyColor: onSurfaceColor, displayColor: onSurfaceColor);
  }
}

class DebugScreen extends StatefulWidget {
  const DebugScreen({super.key});

  @override
  State<DebugScreen> createState() => _DebugScreenState();
}

/// Categories of log lines that are worth showing on screen. Anything that
/// doesn't fit one of these is kept in the full log but hidden by default.
enum _DebugLogKind { error, token, notification, lyrics, service }

class _DebugLogEntry {
  const _DebugLogEntry({
    required this.raw,
    required this.time,
    required this.level,
    required this.message,
    required this.kind,
  });

  /// The unmodified logcat line, used for the full log export.
  final String raw;

  /// HH:MM:SS, or empty when the line wasn't a standard logcat line.
  final String time;

  /// Logcat priority letter (D, I, W, E) or '?' when unknown.
  final String level;
  final String message;
  final _DebugLogKind? kind;

  bool get isImportant => kind != null;

  // logcat -v time: "09-06 12:34:56.789 D/LyricService( 1234): message"
  static final RegExp _logcatLine = RegExp(
    r'^\d{2}-\d{2} (\d{2}:\d{2}:\d{2})\.\d+ ([VDIWEF])/\S+?\(\s*\d+\): (.*)$',
  );
  static final RegExp _warnErrorPattern = RegExp(r'failed|error');
  static final RegExp _noisePattern = RegExp(
    r'ignoring|stale|overlay|rebind|health|heartbeat|highlight|contrast|'
    r'window position|layout|lyrics ?window|lyricsview|album art|colou?rs',
  );
  // Lines that must never be hidden by the noise filter.
  static final RegExp _priorityPattern = RegExp(
    r'access|permission|not granted|listener (connected|disconnected)|'
    r'new song|switching session',
  );
  static final RegExp _tokenPattern = RegExp(r'musixmatch (user )?token');
  static final RegExp _notificationPattern = RegExp(
    r'notification|new song|switching session|mediacontroller|media ?session|'
    r'metadata|listener (connected|disconnected)|access',
  );
  static final RegExp _lyricsPattern = RegExp(
    r'lyrics|lrclib|musixmatch|cache hit',
  );
  static final RegExp _servicePattern = RegExp(
    r'action_|onstartcommand|ondestroy|performstopactions|service',
  );

  factory _DebugLogEntry.parse(String raw) {
    final match = _logcatLine.firstMatch(raw);
    final level = match?.group(2) ?? '?';
    final message = match?.group(3) ?? raw;
    return _DebugLogEntry(
      raw: raw,
      time: match?.group(1) ?? '',
      level: level,
      message: message,
      kind: _classify(level, message),
    );
  }

  static _DebugLogKind? _classify(String level, String message) {
    if (message.startsWith('--------- beginning of')) return null;
    final lower = message.toLowerCase();
    if (level == 'E' || lower.contains('exception')) return _DebugLogKind.error;
    if (level == 'W' && _warnErrorPattern.hasMatch(lower)) {
      return _DebugLogKind.error;
    }
    if (_tokenPattern.hasMatch(lower)) return _DebugLogKind.token;
    if (_priorityPattern.hasMatch(lower)) return _DebugLogKind.notification;
    if (_noisePattern.hasMatch(lower)) return null;
    if (_notificationPattern.hasMatch(lower)) return _DebugLogKind.notification;
    if (_lyricsPattern.hasMatch(lower)) return _DebugLogKind.lyrics;
    if (_servicePattern.hasMatch(lower)) return _DebugLogKind.service;
    return null;
  }
}

class _DebugScreenState extends State<DebugScreen> {
  static const int _maxDebugLogLines = 500;
  static const double _minLogViewHeight = 240;

  final List<_DebugLogEntry> _entries = [];
  StreamSubscription<dynamic>? _logSubscription;
  String? _errorMessage;
  bool? _isMusixmatchTokenAvailable;
  String? _musixmatchTokenPreview;
  bool _isCheckingMusixmatchToken = false;
  bool _isRegeneratingMusixmatchToken = false;
  String? _musixmatchTokenStatusError;
  String? _musixmatchTokenFetchError;
  bool _isStarting = true;
  bool _isStreaming = false;
  bool _showAllLogs = false;
  final ScrollController _scrollController = ScrollController();
  final FocusNode _logFocusNode = FocusNode();

  // SelectableText drops its selection whenever the span it is given changes.
  // While the user has a selection, keep showing the span they selected in
  // and resume live updates once the selection collapses or focus is lost.
  bool _hasLogSelection = false;
  TextSpan? _frozenLogSpan;
  TextSpan? _lastLogSpan;

  @override
  void initState() {
    super.initState();
    _logFocusNode.addListener(_handleLogFocusChanged);
    _refreshMusixmatchTokenStatus();
    _startDebugSession();
  }

  @override
  void dispose() {
    _logSubscription?.cancel();
    _scrollController.dispose();
    _logFocusNode
      ..removeListener(_handleLogFocusChanged)
      ..dispose();
    super.dispose();
  }

  void _handleLogFocusChanged() {
    if (!_logFocusNode.hasFocus) _setLogSelection(false);
  }

  void _handleLogSelectionChanged(
    TextSelection selection,
    SelectionChangedCause? cause,
  ) {
    _setLogSelection(!selection.isCollapsed);
  }

  void _setLogSelection(bool hasSelection) {
    if (hasSelection == _hasLogSelection) return;
    setState(() {
      _hasLogSelection = hasSelection;
      _frozenLogSpan = hasSelection ? _lastLogSpan : null;
    });
  }

  Future<void> _stopDebugSession() async {
    await _logSubscription?.cancel();
    if (!mounted) return;
    setState(() {
      _logSubscription = null;
      _isStreaming = false;
      _isStarting = false;
    });
  }

  Future<void> _refreshMusixmatchTokenStatus({bool showLoading = true}) async {
    if (showLoading && mounted) {
      setState(() {
        _isCheckingMusixmatchToken = true;
        _musixmatchTokenStatusError = null;
      });
    }

    try {
      final bool? isAvailable = await _platformChannel.invokeMethod<bool>(
        'getMusixmatchTokenAvailable',
      );
      final String? preview = await _platformChannel.invokeMethod<String>(
        'getMusixmatchTokenPreview',
      );
      if (!mounted) return;
      setState(() {
        _isMusixmatchTokenAvailable = isAvailable ?? false;
        _musixmatchTokenPreview = preview;
        _isCheckingMusixmatchToken = false;
        _musixmatchTokenStatusError = null;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _isCheckingMusixmatchToken = false;
        _musixmatchTokenStatusError = e.message ?? e.code;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isCheckingMusixmatchToken = false;
        _musixmatchTokenStatusError = e.toString();
      });
    }
  }

  Future<Map<Object?, Object?>?> _fetchTokenFetchResult() {
    return _platformChannel.invokeMethod<Map<Object?, Object?>>(
      'getMusixmatchTokenFetchResult',
    );
  }

  Future<void> _regenerateMusixmatchToken() async {
    if (_isRegeneratingMusixmatchToken) return;
    setState(() {
      _isRegeneratingMusixmatchToken = true;
      _musixmatchTokenStatusError = null;
      _musixmatchTokenFetchError = null;
    });

    try {
      final int seqBefore = (await _fetchTokenFetchResult())?['seq'] as int? ?? 0;
      await _platformChannel.invokeMethod('regenerateMusixmatchToken');

      // The service fetches the token asynchronously. Poll until it reports
      // an outcome (sequence number changes) or we give up.
      const Duration pollInterval = Duration(milliseconds: 500);
      const Duration timeout = Duration(seconds: 12);
      final Stopwatch elapsed = Stopwatch()..start();
      Map<Object?, Object?>? outcome;
      while (elapsed.elapsed < timeout) {
        await Future<void>.delayed(pollInterval);
        if (!mounted) return;
        final result = await _fetchTokenFetchResult();
        if ((result?['seq'] as int? ?? 0) != seqBefore) {
          outcome = result;
          break;
        }
      }
      if (!mounted) return;
      await _refreshMusixmatchTokenStatus(showLoading: false);
      if (!mounted) return;

      if (outcome == null) {
        _showSnack(
          'No response from the service after ${timeout.inSeconds}s. '
          'Check the log for details.',
        );
      } else {
        final String? error = outcome['error'] as String?;
        setState(() {
          _musixmatchTokenFetchError = error;
        });
        _showSnack(
          error == null
              ? 'New Musixmatch token acquired'
                    '${_musixmatchTokenPreview == null ? '' : ' ($_musixmatchTokenPreview)'}.'
              : 'Token regeneration failed: $error',
        );
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _musixmatchTokenStatusError = e.message ?? e.code;
      });
      _showSnack('Could not request a new token: ${e.message ?? e.code}');
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _musixmatchTokenStatusError = e.toString();
      });
      _showSnack('Could not request a new token: $e');
    } finally {
      if (mounted) {
        setState(() {
          _isRegeneratingMusixmatchToken = false;
        });
      }
    }
  }

  Future<void> _startDebugSession() async {
    setState(() {
      _entries.clear();
      _hasLogSelection = false;
      _frozenLogSpan = null;
      _errorMessage = null;
      _isStarting = true;
      _isStreaming = true;
    });

    await _logSubscription?.cancel();
    _logSubscription = _debugLogChannel.receiveBroadcastStream().listen(
      (event) {
        if (!mounted) return;
        _handleLogLine(event.toString());
      },
      onError: (error) {
        if (!mounted) return;
        setState(() {
          _errorMessage = error.toString();
        });
      },
    );

    try {
      if (!Platform.isAndroid) {
        throw PlatformException(
          code: 'UNAVAILABLE',
          message: 'Debug session is only available on Android devices.',
        );
      }

      await _platformChannel.invokeMethod('startDebugActiveMediaNotification');
      unawaited(
        Future<void>.delayed(const Duration(seconds: 1), () {
          return _refreshMusixmatchTokenStatus(showLoading: false);
        }),
      );
    } on MissingPluginException catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage =
            'Debug channel not available: ${e.message ?? e.toString()}';
        _isStreaming = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = e.message ?? e.code;
        _isStreaming = false;
      });
    } finally {
      if (mounted) {
        setState(() {
          _isStarting = false;
        });
      }
    }
  }

  void _handleLogLine(String line) {
    final entry = _DebugLogEntry.parse(line);
    setState(() {
      _entries.add(entry);
      if (_entries.length > _maxDebugLogLines) {
        _entries.removeAt(0);
      }
    });
    if (entry.kind == _DebugLogKind.token) {
      // Token acquired/failed: ask the native side for the real state.
      unawaited(_refreshMusixmatchTokenStatus(showLoading: false));
    }
    if (entry.isImportant || _showAllLogs) {
      _scrollToBottom();
    }
  }

  /// Follows new lines only while the view is already near the bottom, so a
  /// user reading or selecting older lines isn't yanked away.
  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      final position = _scrollController.position;
      final bool nearBottom =
          position.maxScrollExtent - position.pixels < 80;
      if (!nearBottom) return;
      _scrollController.animateTo(
        position.maxScrollExtent,
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOut,
      );
    });
  }

  String _fullLogText() {
    final buffer = StringBuffer()
      ..writeln('Lyric Listener debug log')
      ..writeln('Captured: ${DateTime.now().toIso8601String()}')
      ..writeln('Musixmatch token: ${_musixmatchTokenPreview ?? 'unavailable'}')
      ..writeln('Lines: ${_entries.length} (max $_maxDebugLogLines)')
      ..writeln();
    for (final entry in _entries) {
      buffer.writeln(entry.raw);
    }
    return buffer.toString();
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(content: Text(message), duration: const Duration(seconds: 6)),
      );
  }

  Future<void> _copyFullLog() async {
    await Clipboard.setData(ClipboardData(text: _fullLogText()));
    _showSnack('Copied ${_entries.length} log lines to clipboard.');
  }

  Future<void> _saveFullLog() async {
    try {
      final String? path = await _platformChannel.invokeMethod<String>(
        'saveDebugLog',
        {'content': _fullLogText()},
      );
      _showSnack(path == null ? 'Log saved.' : 'Log saved to $path');
    } on PlatformException catch (e) {
      _showSnack('Could not save log: ${e.message ?? e.code}');
    } catch (e) {
      _showSnack('Could not save log: $e');
    }
  }

  void _clearLogs() {
    setState(() {
      _entries.clear();
      _hasLogSelection = false;
      _frozenLogSpan = null;
    });
  }

  static String _kindLabel(_DebugLogEntry entry) => switch (entry.kind) {
    _DebugLogKind.error => 'ERR',
    _DebugLogKind.token => 'TOKEN',
    _DebugLogKind.notification => 'NOTIF',
    _DebugLogKind.lyrics => 'LYRICS',
    _DebugLogKind.service => 'SVC',
    null => entry.level,
  };

  static Color _kindColor(_DebugLogKind? kind, ColorScheme colorScheme) =>
      switch (kind) {
        _DebugLogKind.error => colorScheme.error,
        _DebugLogKind.token => colorScheme.tertiary,
        _DebugLogKind.notification => colorScheme.primary,
        _DebugLogKind.lyrics => colorScheme.secondary,
        _DebugLogKind.service => colorScheme.onSurface,
        null => colorScheme.onSurfaceVariant,
      };

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final colorScheme = Theme.of(context).colorScheme;
    final bool isTokenAvailable = _isMusixmatchTokenAvailable == true;
    final bool isTokenUnavailable =
        _isMusixmatchTokenAvailable == false && !_isCheckingMusixmatchToken;
    final String tokenSuffix = _musixmatchTokenPreview == null
        ? ''
        : ' ($_musixmatchTokenPreview)';
    final String musixmatchTokenStatusText = _isRegeneratingMusixmatchToken
        ? 'Musixmatch token: Regenerating...'
        : _isCheckingMusixmatchToken
        ? 'Musixmatch token: Checking...'
        : _isMusixmatchTokenAvailable == null
        ? 'Musixmatch token: Unknown'
        : isTokenAvailable
        ? 'Musixmatch token: Available$tokenSuffix'
        : 'Musixmatch token: Unavailable';
    final Color musixmatchTokenStatusColor = _isCheckingMusixmatchToken
        ? colorScheme.primary
        : isTokenAvailable
        ? colorScheme.primary
        : isTokenUnavailable
        ? colorScheme.error
        : colorScheme.onSurfaceVariant;
    final IconData musixmatchTokenStatusIcon = _isCheckingMusixmatchToken
        ? Icons.hourglass_top_rounded
        : isTokenAvailable
        ? Icons.check_circle_rounded
        : isTokenUnavailable
        ? Icons.error_outline_rounded
        : Icons.help_outline_rounded;

    final List<_DebugLogEntry> visibleEntries = _showAllLogs
        ? _entries
        : _entries.where((entry) => entry.isImportant).toList();
    final TextStyle? logStyle = textTheme.bodySmall?.copyWith(
      fontFamily: 'monospace',
    );
    // Beside the status text the Regenerate button leaves too little room on
    // narrow screens or at large font scales; stack it underneath instead.
    final bool stackTokenControls =
        MediaQuery.sizeOf(context).width < 360 ||
        MediaQuery.textScalerOf(context).scale(1.0) >= 1.4;
    final Widget tokenStatusRow = Row(
      children: [
        Icon(
          musixmatchTokenStatusIcon,
          size: 18,
          color: musixmatchTokenStatusColor,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            musixmatchTokenStatusText,
            style: textTheme.bodyMedium?.copyWith(
              color: musixmatchTokenStatusColor,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ],
    );
    final Widget regenerateButton = TextButton.icon(
      onPressed: _isRegeneratingMusixmatchToken || _isCheckingMusixmatchToken
          ? null
          : _regenerateMusixmatchToken,
      icon: _isRegeneratingMusixmatchToken
          ? const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.autorenew_rounded, size: 18),
      label: const Text('Regenerate'),
    );

    final Widget header = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: colorScheme.surfaceContainer,
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: colorScheme.outlineVariant),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (stackTokenControls) ...[
                    Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: tokenStatusRow,
                    ),
                    Align(
                      alignment: Alignment.centerRight,
                      child: regenerateButton,
                    ),
                  ] else
                    Row(
                      children: [
                        Expanded(child: tokenStatusRow),
                        regenerateButton,
                      ],
                    ),
                  if (_musixmatchTokenFetchError != null) ...[
                    const SizedBox(height: 4),
                    Text(
                      'Last token fetch failed: $_musixmatchTokenFetchError',
                      style: textTheme.bodySmall?.copyWith(
                        color: colorScheme.error,
                      ),
                    ),
                  ],
                  if (_musixmatchTokenStatusError != null) ...[
                    const SizedBox(height: 4),
                    Text(
                      'Status check error: $_musixmatchTokenStatusError',
                      style: textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 10),
            Text(
              'Streams service logs. Only notification, lyrics lookup, token and '
              'error lines are shown; the full log (last $_maxDebugLogLines '
              'lines) can be copied or saved to a file.',
              style: textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 10),
            // Wraps rather than Rows so the controls flow onto a second line
            // on narrow screens or with a large font scale instead of
            // overflowing.
            Wrap(
              spacing: 12,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                FilledButton.icon(
                  onPressed: _isStarting ? null : _startDebugSession,
                  icon: const Icon(Icons.bug_report_outlined),
                  label: Text(
                    _isStarting
                        ? 'Starting...'
                        : _isStreaming
                        ? 'Rescan & start'
                        : 'Start log stream',
                  ),
                ),
                OutlinedButton.icon(
                  onPressed: _isStarting || !_isStreaming
                      ? null
                      : _stopDebugSession,
                  icon: const Icon(Icons.stop_circle_outlined),
                  label: const Text('Stop stream'),
                ),
                if (_isStarting)
                  const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 3),
                  ),
              ],
            ),
            Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                FilterChip(
                  label: Text('Show all (${_entries.length})'),
                  selected: _showAllLogs,
                  onSelected: (value) {
                    setState(() {
                      _showAllLogs = value;
                    });
                    _scrollToBottom();
                  },
                ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      tooltip: 'Copy full log',
                      icon: const Icon(Icons.copy_rounded),
                      onPressed: _entries.isEmpty ? null : _copyFullLog,
                    ),
                    IconButton(
                      tooltip: 'Save full log to file',
                      icon: const Icon(Icons.save_alt_rounded),
                      onPressed: _entries.isEmpty ? null : _saveFullLog,
                    ),
                    IconButton(
                      tooltip: 'Clear',
                      icon: const Icon(Icons.delete_sweep_outlined),
                      onPressed: _entries.isEmpty ? null : _clearLogs,
                    ),
                  ],
                ),
              ],
            ),
            if (_hasLogSelection) ...[
              const SizedBox(height: 4),
              Text(
                'Log view paused while text is selected. Tap the log to resume.',
                style: textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            if (_errorMessage != null) ...[
              const SizedBox(height: 6),
              Text(
                'Error: $_errorMessage',
                style: textTheme.bodyMedium?.copyWith(color: colorScheme.error),
              ),
            ],
      ],
    );

    final TextSpan liveLogSpan = TextSpan(
      style: logStyle,
      children: [
        for (int i = 0; i < visibleEntries.length; i++)
          ..._buildEntrySpans(
            visibleEntries[i],
            colorScheme,
            isLast: i == visibleEntries.length - 1,
          ),
      ],
    );
    final TextSpan? frozenLogSpan = _hasLogSelection ? _frozenLogSpan : null;
    final TextSpan displayedLogSpan = frozenLogSpan ?? liveLogSpan;
    _lastLogSpan = displayedLogSpan;
    final bool showLogText = visibleEntries.isNotEmpty || frozenLogSpan != null;

    final Widget logView = Container(
                width: double.infinity,
                decoration: BoxDecoration(
                  color: colorScheme.surfaceContainer,
                  borderRadius: BorderRadius.circular(28),
                  border: Border.all(color: colorScheme.outlineVariant),
                ),
                child: !showLogText
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Text(
                            _isStarting
                                ? 'Listening for debug logs...'
                                : _entries.isEmpty
                                ? 'No logs yet. Start the stream.'
                                : '${_entries.length} verbose lines captured, '
                                      'nothing important yet. Turn on "Show all" '
                                      'to see them.',
                            textAlign: TextAlign.center,
                            style: textTheme.bodyMedium?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ),
                      )
                    // A single SelectableText keeps selection anchored to
                    // character offsets, so appending lines while a selection
                    // is active doesn't re-select the whole view the way a
                    // SelectionArea over a rebuilding ListView did.
                    : SingleChildScrollView(
                        controller: _scrollController,
                        padding: EdgeInsets.fromLTRB(
                          12,
                          12,
                          12,
                          12 + MediaQuery.paddingOf(context).bottom,
                        ),
                        child: SelectableText.rich(
                          displayedLogSpan,
                          focusNode: _logFocusNode,
                          onSelectionChanged: _handleLogSelectionChanged,
                        ),
                      ),
    );

    // The log normally fills whatever the header leaves. When the header is
    // tall (large font scale, landscape) the log keeps a usable minimum
    // height and the whole page scrolls instead.
    return Scaffold(
      appBar: AppBar(title: const Text('Debug: Media Notification')),
      body: CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            sliver: SliverToBoxAdapter(child: header),
          ),
          SliverFillRemaining(
            hasScrollBody: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
              child: SizedBox(height: _minLogViewHeight, child: logView),
            ),
          ),
        ],
      ),
    );
  }

  List<InlineSpan> _buildEntrySpans(
    _DebugLogEntry entry,
    ColorScheme colorScheme, {
    required bool isLast,
  }) {
    final Color kindColor = _kindColor(entry.kind, colorScheme);
    return [
      if (entry.time.isNotEmpty)
        TextSpan(
          text: '${entry.time} ',
          style: TextStyle(color: colorScheme.onSurfaceVariant),
        ),
      TextSpan(
        text: '${_kindLabel(entry)} ',
        style: TextStyle(color: kindColor, fontWeight: FontWeight.w700),
      ),
      TextSpan(
        text: entry.message,
        style: entry.isImportant
            ? null
            : TextStyle(color: colorScheme.onSurfaceVariant),
      ),
      if (!isLast) const TextSpan(text: '\n'),
    ];
  }
}

class MainScreen extends StatefulWidget {
  const MainScreen({
    super.key,
    required this.toggleTheme,
    required this.seedColor,
    required this.onSeedColorChanged,
    required this.isMaterialYouThemingEnabled,
    required this.onMaterialYouThemingChanged,
  });

  final VoidCallback toggleTheme;
  final Color seedColor;
  final ValueChanged<Color> onSeedColorChanged;
  final bool isMaterialYouThemingEnabled;
  final ValueChanged<bool> onMaterialYouThemingChanged;

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> with WidgetsBindingObserver {
  int _selectedIndex = 0;
  int? _androidSdkInt;
  bool _isLoadingAppStatus = true; // Initial state is loading

  bool _isNotificationAccessGranted = false;
  bool _canDrawOverlays = false;
  bool _isPostNotificationsGranted = false;
  bool _isBatteryOptimizationDisabled = false;
  bool _isServiceRunning = false;
  bool _canStartService = false;

  bool _isServiceActionInProgress = false;
  bool _supportCardVisible = false;
  bool _welcomeVisible = false;
  int _debugTriggerTapCount = 0;
  Timer? _debugTriggerResetTimer;
  expressive_refresh.RefreshIndicatorStatus? _refreshStatus;

  bool _rememberLyricsWindowPosition = false;
  bool _hideLyricsWindowOnPause = false;
  bool _dynamicLyricsWindowColors = true;
  bool _isLyricsWindowBlurEnabled = true;
  String _lyricsWindowSize = 'compact';

  Color _lyricsWindowTitleColor = _defaultLyricsWindowTitleColor;
  Color _lyricsWindowBackgroundColor = _defaultLyricsWindowBackgroundColor;
  Color _lyricsWindowHighlightColor = _defaultLyricsWindowHighlightColor;

  static const List<Color> _predefinedSeedColors = [
    Color(0xFF6750A4),
    Color(0xFF006D60),
    Color(0xFFB95D12),
    Color(0xFF984061),
    Color(0xFF416FDF),
    Color(0xFF556614),
  ];
  static const double _seedColorChipSize = 44;
  static const double _seedColorChipRadius = 22;

  static const List<Color> _lyricsWindowBackgroundOptions = [
    Color(0xDD1a1a2e),
    Color(0xF02d5a27),
    Color(0xE63f2d7d),
    Color(0xE6006d60),
    Color(0xE6bf360c),
    Color(0xE64a4a4a),
    Color(0xF0f5f5f5),
    Color(0xE6ffffff),
    Color(0xE3000000),
    Color(0xE65d4037),
    Color(0xE6001f3f),
    Color(0xE6556b1f),
    Color(0xE67d2020),
    Color(0xE64a90e2),
    Color(0xE67b68ee),
    Color(0xE600ced1),
    Color(0xE6ffa07a),
    Color(0xE620b2aa),
    Color(0xE687ceeb),
    Color(0xE6daa520),
    Color(0xE6ff6347),
    Color(0xE640e0d0),
    Color(0xE6ee82ee),
    Color(0xE690ee90),
    Color(0xE6ffb6c1),
    Color(0xE6ffa500),
    Color(0xE600ffff),
    Color(0xE60000ff),
    Color(0xE6ffff00),
    Color(0xE6ff0000),
    Color(0xE6800080),
    Color(0xE6008000),
    Color(0xE6008080),
    Color(0xE6c0c0c0),
    Color(0xE6f0f8ff),
  ];

  static const List<Color> _lyricsWindowTitleOptions = [
    Color(0xFFFFFFFF),
    Color(0xFFE0E0E0),
    Color(0xFF1C1B1F),
    Color(0xFF000000),
    Color(0xFF6750A4),
    Color(0xFF006D60),
    Color(0xFFB3261E),
    Color(0xFF4CAF50),
    Color(0xFF2196F3),
    Color(0xFFFF9800),
    Color(0xFF9C27B0),
    Color(0xFFF44336),
    Color(0xFF607D8B),
    Color(0xFF795548),
    Color(0xFF00BCD4),
    Color(0xFF8BC34A),
    Color(0xFFE91E63),
    Color(0xFF3F51B5),
    Color(0xFFFF5722),
    Color(0xFF009688),
    Color(0xFFCDDC39),
    Color(0xFFFFEB3B),
    Color(0xFF4CAF50),
    Color(0xFF03A9F4),
    Color(0xFF9C27B0),
    Color(0xFFFF9800),
    Color(0xFF607D8B),
    Color(0xFF795548),
    Color(0xFF00BCD4),
    Color(0xFF8BC34A),
    Color(0xFFE91E63),
    Color(0xFF3F51B5),
    Color(0xFFFF5722),
    Color(0xFF009688),
    Color(0xFFCDDC39),
    Color(0xFFFFEB3B),
  ];

  static const List<Color> _lyricsWindowHighlightOptions = [
    Color(0x55C8C8C8),
    Color(0x4DFFFFFF),
    Color(0x4D000000),
    Color(0x4D6750A4),
    Color(0x4D006D60),
    Color(0x4DB3261E),
    Color(0x4D4CAF50),
    Color(0x666750A4),
    Color(0x4D2196F3),
    Color(0x4DFF9800),
    Color(0x4D9C27B0),
    Color(0x4DF44336),
    Color(0x4D607D8B),
    Color(0x4D795548),
    Color(0x4D00BCD4),
    Color(0x4D8BC34A),
    Color(0x4DE91E63),
    Color(0x4D3F51B5),
    Color(0x4DFF5722),
    Color(0x4D009688),
    Color(0x4DCDDC39),
    Color(0x4DFFEB3B),
    Color(0x4D4CAF50),
    Color(0x4D03A9F4),
    Color(0x4D9C27B0),
    Color(0x4DFF9800),
    Color(0x4D607D8B),
    Color(0x4D795548),
    Color(0x4D00BCD4),
    Color(0x4D8BC34A),
    Color(0x4DE91E63),
    Color(0x4D3F51B5),
    Color(0x4DFF5722),
    Color(0x4D009688),
    Color(0x4DCDDC39),
    Color(0x4DFFEB3B),
  ];
  static final List<RoundedPolygon> _refreshPolygons = [
    MaterialShapes.circle,
    MaterialShapes.softBurst,
    MaterialShapes.gem,
    MaterialShapes.flower,
  ];

  @override
  void initState() {
    super.initState();
    print("MainScreen initState: Called");
    WidgetsBinding.instance.addObserver(this);
    _loadCustomizationPreferences();
    _loadInitialData();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      setState(() {
        _supportCardVisible = true;
        _welcomeVisible = true;
      });
    });
  }

  Future<void> _loadCustomizationPreferences() async {
    final prefs = await SharedPreferences.getInstance();
    final rememberPosition = prefs.getBool(_rememberWindowPositionKey) ?? false;
    final hideOnPause = prefs.getBool(_hideWindowOnPauseKey) ?? false;
    final dynamicLyricsColours =
        prefs.getBool(_dynamicLyricsWindowColorsKey) ?? true;
    final simulateLegacyOverlay =
        prefs.getBool(_simulateLegacyOverlayKey) ?? false;
    final storedTitleColor = prefs.getInt(_lyricsWindowTitleColorKey);
    final storedBackgroundColor = prefs.getInt(_lyricsWindowBackgroundColorKey);
    final storedHighlightColor = prefs.getInt(_lyricsWindowHighlightColorKey);
    final lyricsWindowSize = prefs.getString(_lyricsWindowSizeKey) ?? 'compact';
    if (!mounted) return;
    setState(() {
      _rememberLyricsWindowPosition = rememberPosition;
      _hideLyricsWindowOnPause = hideOnPause;
      _dynamicLyricsWindowColors = dynamicLyricsColours;
      _isLyricsWindowBlurEnabled = !simulateLegacyOverlay;
      _lyricsWindowSize = lyricsWindowSize;
      _lyricsWindowTitleColor = Color(
        storedTitleColor ?? _defaultLyricsWindowTitleColor.value,
      );
      _lyricsWindowBackgroundColor = Color(
        storedBackgroundColor ?? _defaultLyricsWindowBackgroundColor.value,
      );
      _lyricsWindowHighlightColor = Color(
        storedHighlightColor ?? _defaultLyricsWindowHighlightColor.value,
      );
    });
  }

  Future<void> _onRememberWindowPositionChanged(bool value) async {
    setState(() {
      _rememberLyricsWindowPosition = value;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_rememberWindowPositionKey, value);
  }

  Future<void> _onHideWindowOnPauseChanged(bool value) async {
    setState(() {
      _hideLyricsWindowOnPause = value;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_hideWindowOnPauseKey, value);
  }

  Future<void> _onDynamicLyricsWindowColorsChanged(bool value) async {
    setState(() {
      _dynamicLyricsWindowColors = value;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_dynamicLyricsWindowColorsKey, value);
  }

  Future<void> _onLyricsWindowBlurEnabledChanged(bool value) async {
    setState(() {
      _isLyricsWindowBlurEnabled = value;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_simulateLegacyOverlayKey, !value);
  }

  Future<void> _onLyricsWindowTitleColorChanged(Color color) async {
    setState(() {
      _lyricsWindowTitleColor = color;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_lyricsWindowTitleColorKey, color.value);
  }

  Future<void> _onLyricsWindowBackgroundColorChanged(Color color) async {
    setState(() {
      _lyricsWindowBackgroundColor = color;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_lyricsWindowBackgroundColorKey, color.value);
  }

  Future<void> _onLyricsWindowHighlightColorChanged(Color color) async {
    setState(() {
      _lyricsWindowHighlightColor = color;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_lyricsWindowHighlightColorKey, color.value);
  }

  Future<void> _onLyricsWindowSizeChanged(String size) async {
    setState(() {
      _lyricsWindowSize = size;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_lyricsWindowSizeKey, size);
  }

  Future<void> _loadInitialData({bool showLoading = true}) async {
    print("MainScreen _loadInitialData: Starting");
    if (!mounted) return;

    if (showLoading && !_isLoadingAppStatus) {
      setState(() {
        _isLoadingAppStatus = true;
      });
    }

    try {
      await _getAndroidVersion();
      if (mounted) {
        await _checkPermissionsStatus();
      }
      if (mounted) await _checkServiceStatus();
    } catch (e, s) {
      print(
        "MainScreen _loadInitialData: Error during loading sequence: $e\n$s",
      );
    } finally {
      if (mounted) {
        if (showLoading && _isLoadingAppStatus) {
          setState(() {
            _isLoadingAppStatus = false;
          });
        }
        print(
          "MainScreen _loadInitialData: Finally block. _isLoadingAppStatus: $_isLoadingAppStatus (after potential setState)",
        );
      }
    }
  }

  Future<void> _handleRefresh() async {
    await _loadInitialData(showLoading: false);
  }

  @override
  void dispose() {
    print("MainScreen dispose: Called");
    _debugTriggerResetTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    print("MainScreen didChangeAppLifecycleState: $state");
    if (state == AppLifecycleState.resumed) {
      if (!_isLoadingAppStatus && !_isServiceActionInProgress) {
        _loadInitialData();
      }
    }
  }

  void _onItemTapped(int index) {
    setState(() {
      _selectedIndex = index;
    });
  }

  Future<void> _launchDonateUrl() async {
    final Uri donateUrl = Uri.parse('https://prancingunicorn.pages.dev/donate');
    try {
      await launchUrl(donateUrl, mode: LaunchMode.externalApplication);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not open the donation page.')),
        );
      }
    }
  }

  Future<void> _getAndroidVersion() async {
    if (!mounted) return;
    print("MainScreen _getAndroidVersion: Starting");
    try {
      final int? version = await _platformChannel.invokeMethod(
        'getAndroidVersion',
      );
      if (mounted) {
        print("MainScreen _getAndroidVersion: Received version: $version");
        _androidSdkInt = version;
      }
    } on PlatformException catch (e) {
      print('MainScreen _getAndroidVersion: Failed - ${e.message}');
      if (mounted) {
        _androidSdkInt = null;
      }
    }
  }

  Future<void> _checkPermissionsStatus() async {
    if (!mounted) return;
    print("MainScreen _checkPermissionsStatus: Starting");

    bool tempNotificationAccess = false;
    bool tempCanDrawOverlays = false;
    bool tempPostNotifications =
        (_androidSdkInt != null && _androidSdkInt! < _android13ApiLevel);
    bool tempBatteryOptDisabled = false;

    try {
      final results = await Future.wait([
        _platformChannel.invokeMethod('isNotificationAccessGranted').catchError(
          (e) {
            print("Error isNotificationAccessGranted: $e");
            return false;
          },
        ),
        _platformChannel.invokeMethod('canDrawOverlays').catchError((e) {
          print("Error canDrawOverlays: $e");
          return false;
        }),
        (_androidSdkInt != null && _androidSdkInt! >= _android13ApiLevel)
            ? _platformChannel
                  .invokeMethod('isPostNotificationsGranted')
                  .catchError((e) {
                    print("Error isPostNotificationsGranted: $e");
                    return false;
                  })
            : Future.value(tempPostNotifications),
        _platformChannel
            .invokeMethod('isIgnoringBatteryOptimizations')
            .catchError((e) {
              print("Error isIgnoringBatteryOptimizations: $e");
              return false;
            }),
      ]);

      tempNotificationAccess = results[0] as bool;
      tempCanDrawOverlays = results[1] as bool;
      tempPostNotifications = results[2] as bool;
      tempBatteryOptDisabled = results[3] as bool;
    } on PlatformException catch (e) {
      print(
        'MainScreen _checkPermissionsStatus: PlatformException - ${e.message}',
      );
    } catch (e) {
      print('MainScreen _checkPermissionsStatus: General Exception - $e');
    }

    if (mounted) {
      setState(() {
        _isNotificationAccessGranted = tempNotificationAccess;
        _canDrawOverlays = tempCanDrawOverlays;
        _isPostNotificationsGranted = tempPostNotifications;
        _isBatteryOptimizationDisabled = tempBatteryOptDisabled;
        _canStartService =
            _isNotificationAccessGranted &&
            _canDrawOverlays &&
            _isPostNotificationsGranted;
      });
      print(
        "Permissions updated: NA: $_isNotificationAccessGranted, DO: $_canDrawOverlays, PN: $_isPostNotificationsGranted, BO: $_isBatteryOptimizationDisabled, CanStart: $_canStartService",
      );
    }
  }

  Future<void> _checkServiceStatus() async {
    if (!mounted) return;
    print("MainScreen _checkServiceStatus: Starting");
    bool tempIsServiceRunning = false;
    try {
      final bool? isRunning = await _platformChannel.invokeMethod<bool>(
        'isLyricServiceRunning',
      );
      if (isRunning != null) {
        tempIsServiceRunning = isRunning;
      }
    } on PlatformException catch (e) {
      print('MainScreen _checkServiceStatus: Failed - ${e.message}');
    } catch (e) {
      print('MainScreen _checkServiceStatus: General Error - $e');
    }
    if (mounted) {
      setState(() {
        _isServiceRunning = tempIsServiceRunning;
      });
      print("Service status updated: Running: $_isServiceRunning");
    }
  }

  Future<void> _handlePermissionRequest(
    Future<dynamic> Function() requestFunction, {
    String? operationName,
  }) async {
    String opName = operationName ?? "Operation";
    print("MainScreen _handlePermissionRequest: Starting $opName");
    try {
      await requestFunction();
      print(
        "MainScreen _handlePermissionRequest: $opName request sent. App will refresh on resume via _loadInitialData.",
      );
    } on PlatformException catch (e) {
      print(
        'MainScreen _handlePermissionRequest: Failed during $opName - ${e.message}',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '$opName request failed: ${e.message ?? "Unknown error"}',
            ),
          ),
        );
      }
    }
  }

  Future<void> _startLyricService() async {
    print("MainScreen _startLyricService: Attempting to start.");
    if (!mounted || _isServiceActionInProgress) return;

    setState(() {
      _isServiceActionInProgress = true;
    });

    try {
      await _platformChannel.invokeMethod('startLyricService');
      await Future.delayed(const Duration(milliseconds: 1500));
      if (mounted) await _checkServiceStatus();
    } on PlatformException catch (e) {
      print('MainScreen _startLyricService: Failed - ${e.message}');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Failed to start service: ${e.message ?? "Unknown error"}',
            ),
          ),
        );
        await _checkServiceStatus();
      }
    } finally {
      if (mounted) {
        setState(() {
          _isServiceActionInProgress = false;
        });
      }
    }
  }

  Future<void> _stopLyricService() async {
    print("MainScreen _stopLyricService: Attempting to stop.");
    if (!mounted || _isServiceActionInProgress) return;

    setState(() {
      _isServiceActionInProgress = true;
    });

    try {
      await _platformChannel.invokeMethod('stopLyricService');
      await Future.delayed(const Duration(milliseconds: 1500));
      if (mounted) await _checkServiceStatus();
    } on PlatformException catch (e) {
      print('MainScreen _stopLyricService: Failed - ${e.message}');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Failed to stop service: ${e.message ?? "Unknown error"}',
            ),
          ),
        );
        await _checkServiceStatus();
      }
    } finally {
      if (mounted) {
        setState(() {
          _isServiceActionInProgress = false;
        });
      }
    }
  }

  Future<void> _requestNotificationAccess() async {
    await _handlePermissionRequest(
      () => _platformChannel.invokeMethod('requestNotificationAccess'),
      operationName: "Notification Access",
    );
  }

  Future<void> _openNotificationSettings() async {
    print("MainScreen _openNotificationSettings: Attempting to open.");
    try {
      await _platformChannel.invokeMethod('openNotificationSettings');
    } on PlatformException catch (e) {
      print('MainScreen _openNotificationSettings: Failed - ${e.message}');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Could not open settings: ${e.message ?? "Unknown error"}',
            ),
          ),
        );
      }
    }
  }

  Future<void> _requestOverlayPermission() async {
    await _handlePermissionRequest(
      () => _platformChannel.invokeMethod('requestOverlayPermission'),
      operationName: "Overlay Permission",
    );
  }

  Future<void> _requestPostNotificationsPermission() async {
    await _handlePermissionRequest(
      () => _platformChannel.invokeMethod('requestPostNotifications'),
      operationName: "Post Notifications Permission",
    );
  }

  Future<void> _requestDisableBatteryOptimization() async {
    await _handlePermissionRequest(
      () => _platformChannel.invokeMethod('requestDisableBatteryOptimization'),
      operationName: "Battery Optimization",
    );
  }

  Future<void> _clearLyricsCache() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        final colorScheme = Theme.of(context).colorScheme;
        return AlertDialog(
          title: const Text('Clear all lyrics'),
          content: const Text(
            'Are you sure? This will delete all stored lyrics. '
            'Cached lyrics load faster and reduce network usage.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              style: FilledButton.styleFrom(
                backgroundColor: colorScheme.error,
                foregroundColor: colorScheme.onError,
              ),
              child: const Text('Clear all'),
            ),
          ],
        );
      },
    );

    if (confirmed != true) return;

    try {
      final int? clearedCount = await _platformChannel.invokeMethod<int>(
        'clearLyricsCache',
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              clearedCount != null && clearedCount > 0
                  ? 'Cleared $clearedCount cached lyrics'
                  : 'Cache cleared',
            ),
          ),
        );
      }
    } on PlatformException catch (e) {
      print('_clearLyricsCache: Failed - ${e.message}');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Failed to clear cache: ${e.message ?? "Unknown error"}',
            ),
          ),
        );
      }
    }
  }

  void _onDebugTriggerTapped() {
    _debugTriggerResetTimer?.cancel();
    _debugTriggerResetTimer = Timer(const Duration(seconds: 3), () {
      if (mounted) {
        setState(() {
          _debugTriggerTapCount = 0;
        });
      }
    });

    setState(() {
      _debugTriggerTapCount++;
      if (_debugTriggerTapCount >= 7) {
        _debugTriggerTapCount = 0;
        Navigator.of(
          context,
        ).push(MaterialPageRoute(builder: (_) => const DebugScreen()));
      }
    });
  }

  Widget _buildPermissionStatusIcon(bool isGranted, {bool optional = false}) {
    final colorScheme = Theme.of(context).colorScheme;
    final Color iconColor;
    if (isGranted) {
      iconColor = colorScheme.primary;
    } else if (optional) {
      iconColor = colorScheme.onSurfaceVariant;
    } else {
      iconColor = colorScheme.error;
    }

    return Icon(
      isGranted
          ? Icons.check_circle_rounded
          : (optional
                ? Icons.info_outline_rounded
                : Icons.error_outline_rounded),
      color: iconColor,
      size: 28,
    );
  }

  Widget _buildRestrictedSettingsNote() {
    if (_androidSdkInt != null &&
        _androidSdkInt! >= _android13ApiLevel &&
        !_isNotificationAccessGranted) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
        child: Text(
          'Note: On Android 13+, for apps not installed from an app store, '
          'you might need to manually "Allow restricted settings" for this app in its App Info page '
          'before Notification Access can be granted.',
          style: Theme.of(
            context,
          ).textTheme.bodySmall?.copyWith(fontStyle: FontStyle.italic),
          textAlign: TextAlign.center,
        ),
      );
    }
    return const SizedBox.shrink();
  }

  Widget _buildPermissionRequestTile({
    required String title,
    required String subtitle,
    required bool isGranted,
    required VoidCallback onPressed,
    bool optional = false,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      child: ListTile(
        leading: _buildPermissionStatusIcon(isGranted, optional: optional),
        title: Text(title, style: Theme.of(context).textTheme.titleSmall),
        subtitle: Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
        trailing: FilledButton.tonal(
          onPressed: isGranted ? null : onPressed,
          style: ButtonStyle(
            padding: WidgetStateProperty.all(
              const EdgeInsets.symmetric(horizontal: 16),
            ),
            backgroundColor: WidgetStateProperty.resolveWith<Color?>((
              Set<WidgetState> states,
            ) {
              if (states.contains(WidgetState.disabled)) {
                return colorScheme.surfaceContainerHighest;
              }
              return null;
            }),
            foregroundColor: WidgetStateProperty.resolveWith<Color?>((
              Set<WidgetState> states,
            ) {
              if (states.contains(WidgetState.disabled)) {
                return colorScheme.onSurfaceVariant;
              }
              return null;
            }),
          ),
          child: Text(isGranted ? 'Granted' : 'Grant'),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        onTap: isGranted ? null : onPressed,
      ),
    );
  }

  Widget _buildSupportCard() {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Card(
      elevation: 0,
      color: colorScheme.secondaryContainer,
      margin: const EdgeInsets.only(bottom: 10.0),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(14),
          topRight: Radius.circular(32),
          bottomLeft: Radius.circular(32),
          bottomRight: Radius.circular(28),
        ),
      ),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 24, 14, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Support Lyric Listener!',
                  style: textTheme.titleLarge?.copyWith(
                    color: colorScheme.onSecondaryContainer,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'If you like this app, please consider supporting my work! Every little bit helps me continue to work on this, and keep it ad-free ♥',
                  style: textTheme.bodyMedium?.copyWith(
                    color: colorScheme.onSecondaryContainer,
                  ),
                ),
                const SizedBox(height: 10),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.tonalIcon(
                    icon: const Icon(Icons.favorite_rounded),
                    label: const Text('Support Me'),
                    onPressed: _launchDonateUrl,
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 8),
                      textStyle: textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildWelcomeSection(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Column(
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: colorScheme.primaryContainer,
            borderRadius: const BorderRadius.only(
              topLeft: Radius.circular(20),
              topRight: Radius.circular(32),
              bottomLeft: Radius.circular(32),
              bottomRight: Radius.circular(20),
            ),
            boxShadow: [
              BoxShadow(
                color: colorScheme.primary.withValues(alpha: 0.25),
                blurRadius: 16,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Icon(
            Icons.music_note_rounded,
            size: 52,
            color: colorScheme.onPrimaryContainer,
          ),
        ),
        const SizedBox(height: 16),
        Text(
          'Welcome to Lyric Listener!',
          style: textTheme.headlineSmall?.copyWith(
            color: colorScheme.primary,
            fontWeight: FontWeight.w700,
            fontVariations: const [
              FontVariation('wght', 760),
              FontVariation('wdth', 110),
            ],
          ),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 6),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0),
          child: Text(
            'A purr-fectly synced lyric experience for your favorite tunes!',
            style: textTheme.titleMedium?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
            textAlign: TextAlign.center,
          ),
        ),
        const SizedBox(height: 24),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Transform.translate(
              offset: const Offset(-16, 0),
              child: SvgPicture.asset(
                'assets/images/cat-left.svg',
                height: 100,
                colorMapper: _MascotSvgColorMapper(colorScheme),
              ),
            ),
            const SizedBox(width: 0),
            Transform.translate(
              offset: const Offset(-38, 0),
              child: SizedBox(
                width: 94,
                height: 82,
                child: ClipRect(
                  child: Transform.translate(
                    offset: const Offset(-10, 0),
                    child: SvgPicture.asset(
                      'assets/images/cat-right.svg',
                      fit: BoxFit.cover,
                      colorFilter: ColorFilter.mode(
                        colorScheme.secondary,
                        BlendMode.srcIn,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
      ],
    );
  }

  Widget _buildSectionHeader(
    BuildContext context,
    String title, {
    double topPadding = 24,
    double bottomPadding = 12,
  }) {
    final textTheme = Theme.of(context).textTheme;
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: EdgeInsets.fromLTRB(8, topPadding, 8, bottomPadding),
      child: Text(
        title,
        style: textTheme.titleLarge?.copyWith(
          color: colorScheme.primary,
          fontWeight: FontWeight.w700,
          fontVariations: const [
            FontVariation('wght', 700),
            FontVariation('wdth', 108),
          ],
        ),
        textAlign: TextAlign.center,
      ),
    );
  }

  String _formatColorLabel(Color color) {
    final hex = color.value.toRadixString(16).padLeft(8, '0').toUpperCase();
    return '#$hex';
  }

  Future<Color?> _showColorPickerDialog({
    required BuildContext context,
    required String title,
    required List<Color> options,
    required Color currentColor,
    required Color defaultColor,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    return showDialog<Color>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: Text(title),
          content: SingleChildScrollView(
            child: Wrap(
              spacing: 12,
              runSpacing: 12,
              children: options.map((color) {
                final isSelected = color.value == currentColor.value;
                return GestureDetector(
                  onTap: () => Navigator.of(dialogContext).pop(color),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 600),
                    curve: expressiveSpringCurve,
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: color,
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: isSelected
                            ? colorScheme.primary
                            : colorScheme.outlineVariant,
                        width: isSelected ? 3 : 1.5,
                      ),
                      boxShadow: [
                        if (isSelected)
                          BoxShadow(
                            color: colorScheme.primary.withValues(alpha: 0.25),
                            blurRadius: 8,
                            offset: const Offset(0, 2),
                          ),
                      ],
                    ),
                    child: isSelected
                        ? Center(
                            child: Icon(
                              Icons.check_rounded,
                              color:
                                  ThemeData.estimateBrightnessForColor(color) ==
                                      Brightness.dark
                                  ? Colors.white
                                  : Colors.black,
                              size: 22,
                            ),
                          )
                        : null,
                  ),
                );
              }).toList(),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(defaultColor),
              child: const Text('Reset to default'),
            ),
          ],
        );
      },
    );
  }

  Widget _buildLyricsWindowColorTile({
    required BuildContext context,
    required String title,
    required String subtitle,
    required Color color,
    required List<Color> options,
    required Color defaultColor,
    required Future<void> Function(Color) onColorChanged,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Padding(
      padding: const EdgeInsets.only(bottom: 12.0),
      child: Material(
        color: colorScheme.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(28),
        child: InkWell(
          borderRadius: BorderRadius.circular(28),
          onTap: () async {
            final selectedColor = await _showColorPickerDialog(
              context: context,
              title: title,
              options: options,
              currentColor: color,
              defaultColor: defaultColor,
            );
            if (selectedColor != null) {
              await onColorChanged(selectedColor);
            }
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '$subtitle • ${_formatColorLabel(color)}',
                        style: textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 16),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 600),
                      curve: expressiveSpringCurve,
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: color,
                        borderRadius: BorderRadius.circular(28),
                        border: Border.all(
                          color: colorScheme.outlineVariant,
                          width: 1.5,
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: colorScheme.primary.withValues(alpha: 0.18),
                            blurRadius: 10,
                            offset: const Offset(0, 4),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Tap to change',
                      style: textTheme.labelSmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSettingsView(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final colorScheme = Theme.of(context).colorScheme;
    final bool isMaterialYouThemingEnabled = widget.isMaterialYouThemingEnabled;

    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      children: [
        _buildExpressiveSection(
          context,
          title: 'Appearance',
          children: [
            AnimatedSwitcher(
              duration: const Duration(milliseconds: 250),
              switchInCurve: Curves.easeOut,
              switchOutCurve: Curves.easeIn,
              child: isMaterialYouThemingEnabled
                  ? const SizedBox.shrink(key: ValueKey('color-picker-hidden'))
                  : Column(
                      key: const ValueKey('color-picker-visible'),
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 12,
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Theme Color',
                                style: textTheme.titleSmall?.copyWith(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const SizedBox(height: 10),
                              Wrap(
                                spacing: 16.0,
                                runSpacing: 12.0,
                                children: _predefinedSeedColors.map((color) {
                                  final isSelected = widget.seedColor == color;
                                  return InkWell(
                                    borderRadius: BorderRadius.circular(
                                      _seedColorChipRadius,
                                    ),
                                    onTap: () => widget.onSeedColorChanged(color),
                                    child: Tooltip(
                                      message:
                                          'Set theme color to #${color.value.toRadixString(16).substring(2).toUpperCase()}',
                                      child: AnimatedScale(
                                        duration: const Duration(milliseconds: 700),
                                        curve: const ElasticOutCurve(0.8),
                                        scale: isSelected ? 1.15 : 1.0,
                                        child: AnimatedContainer(
                                          duration: const Duration(milliseconds: 700),
                                          curve: const ElasticOutCurve(0.8),
                                          width: _seedColorChipSize,
                                          height: _seedColorChipSize,
                                          decoration: BoxDecoration(
                                            color: color,
                                            shape: BoxShape.circle,
                                            border: Border.all(
                                              color: Theme.of(
                                                context,
                                              ).colorScheme.outlineVariant,
                                              width: isSelected ? 2.5 : 1.5,
                                            ),
                                            boxShadow: [
                                              BoxShadow(
                                                color: Theme.of(context)
                                                    .colorScheme
                                                    .primary
                                                    .withValues(
                                                      alpha: isSelected ? 0.35 : 0.15,
                                                    ),
                                                blurRadius: isSelected ? 12 : 8,
                                                offset: const Offset(0, 4),
                                              ),
                                            ],
                                          ),
                                          child: isSelected
                                              ? Center(
                                                  child: Icon(
                                                    Icons.check_rounded,
                                                    color:
                                                        ThemeData.estimateBrightnessForColor(
                                                              color,
                                                            ) ==
                                                            Brightness.dark
                                                        ? Colors.white
                                                        : Colors.black,
                                                    size: 24,
                                                  ),
                                                )
                                              : null,
                                        ),
                                      ),
                                    ),
                                  );
                                }).toList(),
                              ),
                            ],
                          ),
                        ),
                        const Divider(indent: 16, endIndent: 16),
                      ],
                    ),
            ),
            SwitchListTile.adaptive(
              value: isMaterialYouThemingEnabled,
              onChanged: widget.onMaterialYouThemingChanged,
              title: Text(
                'Material You theming',
                style: textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: Text(
                "Use dynamic colors from your device's wallpaper as the theme color",
                style: textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            const Divider(indent: 16, endIndent: 16),
            SwitchListTile.adaptive(
              value: _dynamicLyricsWindowColors,
              onChanged: _onDynamicLyricsWindowColorsChanged,
              title: Text(
                'Dynamic lyrics window colours',
                style: textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: Text(
                'Album art will be used for the lyrics window. Changes will apply from next song onwards.',
                style: textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            if (_androidSdkInt != null && _androidSdkInt! >= _android12ApiLevel)
              const Divider(indent: 16, endIndent: 16),
            if (_androidSdkInt != null && _androidSdkInt! >= _android12ApiLevel)
              SwitchListTile.adaptive(
                value: _isLyricsWindowBlurEnabled,
                onChanged: _onLyricsWindowBlurEnabledChanged,
                title: Text(
                  'Blur effect on lyrics window',
                  style: textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
                ),
                contentPadding: const EdgeInsets.symmetric(horizontal: 16),
              ),
            if (_androidSdkInt != null && _androidSdkInt! >= _android12ApiLevel)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                child: Text(
                  'On newer (Android 12+) devices, the lyrics window gets a more polished look with a blur over the album art. Changes will apply from next song onwards.',
                  style: textTheme.bodySmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            AnimatedSwitcher(
              duration: const Duration(milliseconds: 600),
              switchInCurve: expressiveStandardCurve,
              switchOutCurve: Curves.easeInCubic,
              child: _dynamicLyricsWindowColors
                  ? const SizedBox.shrink()
                  : Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        key: const ValueKey('static-lyrics-colours'),
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Static lyrics window colours',
                            style: textTheme.titleSmall?.copyWith(
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 10),
                          _buildLyricsWindowColorTile(
                            context: context,
                            title: 'Title & icons',
                            subtitle:
                                'The song title, artist name and lyrics window controls',
                            color: _lyricsWindowTitleColor,
                            options: _lyricsWindowTitleOptions,
                            defaultColor: _defaultLyricsWindowTitleColor,
                            onColorChanged: _onLyricsWindowTitleColorChanged,
                          ),
                          _buildLyricsWindowColorTile(
                            context: context,
                            title: 'Background',
                            subtitle:
                                'The floating lyrics window background colour',
                            color: _lyricsWindowBackgroundColor,
                            options: _lyricsWindowBackgroundOptions,
                            defaultColor: _defaultLyricsWindowBackgroundColor,
                            onColorChanged:
                                _onLyricsWindowBackgroundColorChanged,
                          ),
                          _buildLyricsWindowColorTile(
                            context: context,
                            title: 'Highlight',
                            subtitle: 'Used for the active lyric line',
                            color: _lyricsWindowHighlightColor,
                            options: _lyricsWindowHighlightOptions,
                            defaultColor: _defaultLyricsWindowHighlightColor,
                            onColorChanged:
                                _onLyricsWindowHighlightColorChanged,
                          ),
                        ],
                      ),
                    ),
            ),
            SwitchListTile.adaptive(
              value: _rememberLyricsWindowPosition,
              onChanged: _onRememberWindowPositionChanged,
              title: Text(
                'Remember lyrics window position',
                style: textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Text(
                'When enabled, the floating lyrics window reopens where you last placed it.',
                style: textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            SwitchListTile.adaptive(
              value: _hideLyricsWindowOnPause,
              onChanged: _onHideWindowOnPauseChanged,
              title: Text(
                'Hide lyrics window on pause',
                style: textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Text(
                'When enabled, the lyrics window will automatically be hidden when music is paused and re-appear when it starts playing again.',
                style: textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            const Divider(indent: 16, endIndent: 16),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Lyrics window size',
                    style: textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Default size when the lyrics window opens. You can still cycle through sizes using the expand button.',
                    style: textTheme.bodySmall?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 12),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(value: 'mini', label: Text('Minimal')),
                      ButtonSegment(value: 'compact', label: Text('Compact')),
                      ButtonSegment(value: 'expanded', label: Text('Expanded')),
                    ],
                    selected: {_lyricsWindowSize},
                    onSelectionChanged: (selected) {
                      _onLyricsWindowSizeChanged(selected.first);
                    },
                    showSelectedIcon: false,
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        _buildExpressiveSection(
          context,
          title: 'Lyrics',
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.tonalIcon(
                      onPressed: () {
                        Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) => const ManageLyricsPage(),
                          ),
                        );
                      },
                      icon: const Icon(Icons.library_music_rounded),
                      label: const Text('Manage lyrics'),
                      style: FilledButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: () {
                        Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) => const AddCustomLyricsPage(),
                          ),
                        );
                      },
                      icon: const Icon(Icons.add_rounded),
                      label: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Text('Add custom lyrics'),
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: colorScheme.secondaryContainer,
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: Text(
                              'Beta',
                              style: textTheme.labelSmall?.copyWith(
                                color: colorScheme.onSecondaryContainer,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ],
                      ),
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _clearLyricsCache,
                      icon: const Icon(Icons.delete_outline_rounded),
                      label: const Text('Clear all lyrics'),
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        side: BorderSide(color: colorScheme.error),
                        foregroundColor: colorScheme.error,
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: 8.0),
                    child: Text(
                      'Clears all cached lyrics. Cached lyrics load faster and reduce network usage.',
                      style: textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildHelpView(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      children: <Widget>[
        _buildExpressiveSection(
          context,
          title: 'Frequently Asked Questions',
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  _buildFaqItem(
                    context,
                    question:
                        'Lyrics popup is not shown, or shows waiting for song even when music is playing.',
                    answerParts: [
                      const TextSpan(
                        text: "Why this happens? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            'Notification access might be missing, see below.\n\n',
                      ),
                      const TextSpan(
                        text: "What to do? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            'Open the persistent Lyrics Listener notification and tap the Fix notification access action. This usually restores access immediately. If the shortcut is not shown, or does not work, follow these steps:\n1. Stop the Lyric Service.\n 2. Tap ',
                      ),
                      TextSpan(
                        text: 'here',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.primary,
                          decoration: TextDecoration.underline,
                        ),
                        recognizer: TapGestureRecognizer()
                          ..onTap = _openNotificationSettings,
                      ),
                      const TextSpan(
                        text:
                            " to open the system Notification Access settings.\n3. Turn OFF access for 'Lyric Listener'.\n4. Return to this app.\n5. Re-grant 'Notification Access' above.\n6. Launch the Lyric Service again.",
                      ),
                    ],
                  ),
                  const Divider(height: 24),
                  _buildFaqItem(
                    context,
                    question:
                        'Youtube videos show "Lyrics not found" or incorrect lyrics',
                    answerParts: [
                      const TextSpan(
                        text: "Why this happens? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            "Youtube video notifications often don't have standard song titles, making it hard to find the right lyrics.\n\n",
                      ),
                      const TextSpan(
                        text: "What to do? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            "This is being worked on. Later versions should be better, but synced lyrics may not be perfect if the video length differs from the actual song.",
                      ),
                    ],
                  ),
                  const Divider(height: 24),
                  _buildFaqItem(
                    context,
                    question: 'Incorrect (or no) lyrics are displayed',
                    answerParts: [
                      const TextSpan(
                        text: "Why this happens? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            "The lyrics source(s) may not have lyrics for that particular song.\n\n",
                      ),
                      const TextSpan(
                        text: "What to do? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            "You can try a different song, or a few days later, or if you have the LRC files for that song, add it yourself from Settings > Lyrics > Add custom lyrics.",
                      ),
                    ],
                  ),
                  const Divider(height: 24),
                  _buildFaqItem(
                    context,
                    question: 'Need help with a different issue?',
                    answerParts: [
                      TextSpan(
                        text: 'Contact Support',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.primary,
                          decoration: TextDecoration.underline,
                        ),
                        recognizer: TapGestureRecognizer()
                          ..onTap = () async {
                            final Uri emailLaunchUri = Uri(
                              scheme: 'mailto',
                              path: 'adrestaia47@gmail.com',
                              queryParameters: {
                                'subject': 'LyricListener App Support',
                              },
                            );
                            if (await canLaunchUrl(emailLaunchUri)) {
                              await launchUrl(emailLaunchUri);
                            } else {
                              if (mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(
                                    content: Text('Could not open email app.'),
                                  ),
                                );
                              }
                            }
                          },
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildExpressiveSection(
    BuildContext context, {
    required String title,
    required List<Widget> children,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Text(
            title,
            style: textTheme.titleMedium?.copyWith(
              color: colorScheme.primary,
              fontWeight: FontWeight.bold,
              fontVariations: const [FontVariation('wght', 720)],
            ),
          ),
        ),
        Card(
          elevation: 0,
          color: colorScheme.surfaceContainerHigh,
          margin: EdgeInsets.zero,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: children,
          ),
        ),
      ],
    );
  }

  Widget _buildHomeView(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final colorScheme = Theme.of(context).colorScheme;

    return expressive_refresh.ExpressiveRefreshIndicator.contained(
      onRefresh: _handleRefresh,
      onStatusChange: (status) {
        if (!mounted || _refreshStatus == status) return;
        setState(() {
          _refreshStatus = status;
        });
      },
      statusText: _isServiceRunning ? 'Active' : 'Inactive',
      subtitleText: _isServiceRunning
          ? 'Enjoy synced lyrics :)'
          : 'Launch service below to enjoy synced lyrics!',
      color: colorScheme.primary,
      backgroundColor: colorScheme.surfaceContainerHighest,
      polygons: _refreshPolygons,
      indicatorConstraints: const BoxConstraints(
        minWidth: 56,
        minHeight: 56,
        maxWidth: 56,
        maxHeight: 56,
      ),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(
          parent: BouncingScrollPhysics(),
        ),
        padding: const EdgeInsets.fromLTRB(16.0, 36.0, 16.0, 12.0),
        children: <Widget>[
          AnimatedOpacity(
            opacity: _supportCardVisible ? 1.0 : 0.0,
            duration: const Duration(milliseconds: 700),
            curve: expressiveStandardCurve,
            child: _buildSupportCard(),
          ),
          AnimatedOpacity(
            opacity: _welcomeVisible ? 1.0 : 0.0,
            duration: const Duration(milliseconds: 600),
            curve: expressiveStandardCurve,
            child: _buildWelcomeSection(context),
          ),

          const Divider(height: 12, indent: 16, endIndent: 16),
          _buildSectionHeader(
            context,
            'App Setup & Permissions',
            topPadding: 8,
            bottomPadding: 8,
          ),
          Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: 24.0,
              vertical: 4.0,
            ),
            child: Text(
              'Grant these required permissions for the app to function.',
              style: textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ),
          const SizedBox(height: 10),

          if (_androidSdkInt != null && _androidSdkInt! >= _android13ApiLevel)
            _buildPermissionRequestTile(
              title: 'Post Notifications (Android 13+)',
              subtitle: 'Allows the app to show its persistent notification.',
              isGranted: _isPostNotificationsGranted,
              onPressed: _requestPostNotificationsPermission,
            ),
          _buildPermissionRequestTile(
            title: 'Notification Access',
            subtitle: 'Lets the app read music player notifications.',
            isGranted: _isNotificationAccessGranted,
            onPressed: _requestNotificationAccess,
          ),
          _buildRestrictedSettingsNote(),
          _buildPermissionRequestTile(
            title: 'Display Over Other Apps',
            subtitle: 'Enables showing lyrics on top of other apps.',
            isGranted: _canDrawOverlays,
            onPressed: _requestOverlayPermission,
          ),
          const SizedBox(height: 16),

          Padding(
            padding: const EdgeInsets.only(top: 12.0, bottom: 4.0),
            child: Text(
              'Optional Setting',
              style: textTheme.titleMedium?.copyWith(
                color: colorScheme.secondary,
                fontWeight: FontWeight.w500,
              ),
              textAlign: TextAlign.center,
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: 24.0,
              vertical: 4.0,
            ),
            child: Text(
              'Consider this for a more reliable experience on some devices.',
              style: textTheme.bodySmall?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ),
          const SizedBox(height: 6),
          _buildPermissionRequestTile(
            title: 'Disable Battery Optimization',
            subtitle: 'Helps the service run reliably in the background.',
            isGranted: _isBatteryOptimizationDisabled,
            onPressed: _requestDisableBatteryOptimization,
            optional: true,
          ),
          const SizedBox(height: 16),
          const Divider(height: 24, indent: 16, endIndent: 16),
          _buildSectionHeader(context, 'Lyric Service Control'),

          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12.0),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text('Service Status:', style: textTheme.titleMedium),
                const SizedBox(width: 12),
                Icon(
                  _isServiceRunning
                      ? Icons.rocket_launch_rounded
                      : Icons.rocket_outlined,
                  color: _isServiceRunning
                      ? colorScheme.primary
                      : colorScheme.onSurfaceVariant,
                  size: 24,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    _isServiceRunning
                        ? 'Active'
                        : (_canStartService
                              ? 'Ready to Launch'
                              : 'Awaiting Permissions'),
                    style: textTheme.bodyLarge?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: _isServiceRunning
                          ? colorScheme.primary
                          : colorScheme.onSurfaceVariant,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          Center(
            child: FilledButton(
              onPressed: _isServiceActionInProgress
                  ? null
                  : (_isServiceRunning
                        ? _stopLyricService
                        : (_canStartService ? _startLyricService : null)),
              style:
                  FilledButton.styleFrom(
                    backgroundColor: _isServiceRunning
                        ? colorScheme.error
                        : (_canStartService
                              ? colorScheme.primary
                              : colorScheme.surfaceContainerHighest),
                    foregroundColor: _isServiceRunning
                        ? colorScheme.onError
                        : (_canStartService
                              ? colorScheme.onPrimary
                              : colorScheme.onSurfaceVariant),
                  ).copyWith(
                    elevation: WidgetStateProperty.resolveWith<double?>((
                      Set<WidgetState> states,
                    ) {
                      if (states.contains(WidgetState.disabled) &&
                          !_isServiceActionInProgress) {
                        return 0;
                      }
                      return 4;
                    }),
                  ),
              child: AnimatedScale(
                scale: _isServiceActionInProgress ? 0.95 : 1.0,
                duration: const Duration(milliseconds: 600),
                curve: expressiveSpringCurve,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _isServiceActionInProgress
                        ? SizedBox(
                            width: 24,
                            height: 24,
                            child: CircularProgressIndicator(
                              strokeWidth: 3,
                              color: _isServiceRunning
                                  ? colorScheme.onError
                                  : colorScheme.onPrimary,
                            ),
                          )
                        : Icon(
                            _isServiceRunning
                                ? Icons.stop_circle_outlined
                                : Icons.play_circle_outline_rounded,
                            size: 28,
                          ),
                    const SizedBox(width: 8),
                    Text(
                      _isServiceActionInProgress
                          ? (_isServiceRunning ? 'Stopping...' : 'Starting...')
                          : (_isServiceRunning
                                ? 'Stop Lyric Service'
                                : 'Launch Lyric Service'),
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: colorScheme.surfaceContainerLow,
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(28),
                topRight: Radius.circular(14),
                bottomLeft: Radius.circular(14),
                bottomRight: Radius.circular(28),
              ),
            ),
            child: Text(
              _isServiceRunning
                  ? 'The lyric service is active. Stop it here if needed. It may restart if music plays and permissions are granted.'
                  : 'Once permissions are granted, launch the service. It will run in the background. If it stops, come back here to launch it again!',
              style: textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ),
          const SizedBox(height: 20),
        ],
      ),
    );
  }

  Widget _buildFaqItem(
    BuildContext context, {
    required String question,
    required List<TextSpan> answerParts,
  }) {
    final textTheme = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            question,
            style: textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 4),
          RichText(
            text: TextSpan(
              style: textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                height: 1.5,
              ),
              children: answerParts,
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    print(
      "MainScreen build: Called. _isLoadingAppStatus: $_isLoadingAppStatus, _isServiceRunning: $_isServiceRunning, _canStartService: $_canStartService, _isServiceActionInProgress: $_isServiceActionInProgress",
    );
    final colorScheme = Theme.of(context).colorScheme;

    Widget body;

    if (_isLoadingAppStatus) {
      body = const Center(
        child: Padding(
          padding: EdgeInsets.all(32.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 20),
              Text("Loading App Status..."),
            ],
          ),
        ),
      );
    } else {
      Widget child;
      switch (_selectedIndex) {
        case 0:
          child = _buildHomeView(context);
          break;
        case 1:
          child = _buildSettingsView(context);
          break;
        case 2:
          child = _buildHelpView(context);
          break;
        default:
          child = _buildHomeView(context);
      }

      body = AnimatedSwitcher(
        duration: const Duration(milliseconds: 400),
        switchInCurve: expressiveStandardCurve,
        switchOutCurve: Curves.easeInCubic,
        transitionBuilder: (Widget child, Animation<double> animation) {
          return FadeTransition(
            opacity: animation,
            child: ScaleTransition(
              scale: Tween<double>(begin: 0.92, end: 1.0).animate(animation),
              child: child,
            ),
          );
        },
        child: KeyedSubtree(key: ValueKey<int>(_selectedIndex), child: child),
      );
    }

    String appBarTitle;
    switch (_selectedIndex) {
      case 1:
        appBarTitle = 'Settings';
        break;
      case 2:
        appBarTitle = 'Help';
        break;
      case 0:
      default:
        appBarTitle = 'Lyric Listener';
        break;
    }

    return PopScope<void>(
      canPop: _selectedIndex == 0,
      onPopInvoked: (didPop) {
        if (didPop || _selectedIndex == 0) return;
        setState(() {
          _selectedIndex = 0;
        });
      },
      child: Scaffold(
        appBar: AppBar(
          centerTitle: true,
          title: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (_selectedIndex == 0) ...[
                Icon(Icons.lyrics_rounded, color: colorScheme.primary),
                const SizedBox(width: 12),
              ],
              GestureDetector(
                onTap: _selectedIndex == 1 ? _onDebugTriggerTapped : null,
                child: Text(appBarTitle),
              ),
            ],
          ),
          actions: [
            IconButton(
              icon: Icon(
                Theme.of(context).brightness == Brightness.dark
                    ? Icons.light_mode_rounded
                    : Icons.dark_mode_rounded,
              ),
              onPressed: widget.toggleTheme,
              tooltip: 'Toggle Theme',
            ),
          ],
        ),
        body: SafeArea(child: body),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _selectedIndex,
          onDestinationSelected: _onItemTapped,
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.home_outlined),
              selectedIcon: Icon(Icons.home_rounded),
              label: 'Home',
            ),
            NavigationDestination(
              icon: Icon(Icons.settings_outlined),
              selectedIcon: Icon(Icons.settings_rounded),
              label: 'Settings',
            ),
            NavigationDestination(
              icon: Icon(Icons.help_outline_rounded),
              selectedIcon: Icon(Icons.help_rounded),
              label: 'Help',
            ),
          ],
        ),
      ),
    );
  }
}
