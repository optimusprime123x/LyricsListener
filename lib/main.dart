import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:lyricslistener/widgets/expressive_refresh_indicator.dart'
    as expressive_refresh;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:material_new_shapes/material_new_shapes.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

const MethodChannel _platformChannel = MethodChannel(
  'dev.optimus.lyricslistener/permissions',
);
const EventChannel _debugLogChannel = EventChannel(
  'dev.optimus.lyricslistener/debugLogs',
);

const int _android13ApiLevel = 33;

const _defaultSeedColor = Color(0xFF6750A4);

const String _seedColorKey = 'seed_color';
const String _rememberWindowPositionKey = 'remember_window_position';
const String _dynamicLyricsWindowColorsKey = 'lyrics_window_dynamic_colors';
const String _lyricsWindowTitleColorKey = 'lyrics_window_title_color';
const String _lyricsWindowBackgroundColorKey = 'lyrics_window_background_color';
const String _lyricsWindowHighlightColorKey = 'lyrics_window_highlight_color';

const Color _defaultLyricsWindowTitleColor = Color(0xFFE0E0E0);
const Color _defaultLyricsWindowBackgroundColor = Color(0xDD212121);
const Color _defaultLyricsWindowHighlightColor = Color(0x46C8C8C8);

const Curve expressiveSpringCurve = Curves.elasticOut;
const Curve expressiveStandardCurve = Curves.easeOutCubic;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final int? savedColorValue = prefs.getInt(_seedColorKey);
  final Color initialSeedColor = savedColorValue != null
      ? Color(savedColorValue)
      : _defaultSeedColor;

  runApp(MyApp(initialSeedColor: initialSeedColor));
}

class MyApp extends StatefulWidget {
  const MyApp({super.key, required this.initialSeedColor});

  final Color initialSeedColor;

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  ThemeMode _themeMode = ThemeMode.system;
  late Color _seedColor;

  @override
  void initState() {
    super.initState();
    _seedColor = widget.initialSeedColor;
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

  @override
  Widget build(BuildContext context) {
    final baseHeadlineTheme = GoogleFonts.robotoFlexTextTheme(
      Theme.of(context).textTheme,
    );
    final baseBodyTheme = GoogleFonts.manropeTextTheme(
      Theme.of(context).textTheme,
    );

    final baseLightColorScheme = ColorScheme.fromSeed(
      seedColor: _seedColor,
      brightness: Brightness.light,
    );

    final lightTextTheme = _buildExpressiveTextTheme(
      baseHeadlineTheme,
      baseBodyTheme,
      baseLightColorScheme.onSurface,
    );

    final lightTheme = ThemeData(
      colorScheme: baseLightColorScheme,
      useMaterial3: true,
      brightness: Brightness.light,
      textTheme: lightTextTheme,
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
        iconTheme: IconThemeData(color: baseLightColorScheme.onSurfaceVariant),
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
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
      ),
      expansionTileTheme: ExpansionTileThemeData(
        iconColor: baseLightColorScheme.primary,
        collapsedIconColor: baseLightColorScheme.onSurfaceVariant,
        textColor: baseLightColorScheme.primary,
        collapsedTextColor: baseLightColorScheme.onSurface,
        backgroundColor: baseLightColorScheme.surfaceContainerLow,
        collapsedBackgroundColor: baseLightColorScheme.surfaceContainer,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
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
          borderSide: BorderSide(color: baseLightColorScheme.primary, width: 2),
        ),
        filled: true,
        fillColor: baseLightColorScheme.surfaceContainerHighest,
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
        backgroundColor: baseLightColorScheme.inverseSurface,
        contentTextStyle: lightTextTheme.bodyMedium?.copyWith(
          color: baseLightColorScheme.onInverseSurface,
        ),
        actionTextColor: baseLightColorScheme.inversePrimary,
      ),
    );

    final baseDarkColorScheme = ColorScheme.fromSeed(
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
        iconTheme: IconThemeData(color: baseDarkColorScheme.onSurfaceVariant),
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
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
      ),
      expansionTileTheme: ExpansionTileThemeData(
        iconColor: baseDarkColorScheme.primary,
        collapsedIconColor: baseDarkColorScheme.onSurfaceVariant,
        textColor: baseDarkColorScheme.primary,
        collapsedTextColor: baseDarkColorScheme.onSurface,
        backgroundColor: baseDarkColorScheme.surfaceContainerLow,
        collapsedBackgroundColor: baseDarkColorScheme.surfaceContainer,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
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
          borderSide: BorderSide(color: baseDarkColorScheme.primary, width: 2),
        ),
        filled: true,
        fillColor: baseDarkColorScheme.surfaceContainerHighest,
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
        backgroundColor: baseDarkColorScheme.inverseSurface,
        contentTextStyle: darkTextTheme.bodyMedium?.copyWith(
          color: baseDarkColorScheme.onInverseSurface,
        ),
        actionTextColor: baseDarkColorScheme.inversePrimary,
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
      ),
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

class _DebugScreenState extends State<DebugScreen> {
  final List<String> _logs = [];
  StreamSubscription<dynamic>? _logSubscription;
  String? _errorMessage;
  bool _isStarting = true;
  bool _isStreaming = false;
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _startDebugSession();
  }

  @override
  void dispose() {
    _logSubscription?.cancel();
    _scrollController.dispose();
    super.dispose();
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

  Future<void> _startDebugSession() async {
    setState(() {
      _logs.clear();
      _errorMessage = null;
      _isStarting = true;
      _isStreaming = true;
    });

    await _logSubscription?.cancel();
    _logSubscription = _debugLogChannel.receiveBroadcastStream().listen(
      (event) {
        if (!mounted) return;
        setState(() {
          _logs.add(event.toString());
        });
        _scrollToBottom();
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

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final colorScheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Debug: Media Notification')),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Fetches the currently active media notification and streams service logs so you can follow the parsing flow.',
              style: textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            Row(
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
                const SizedBox(width: 12),
                OutlinedButton.icon(
                  onPressed: _isStarting || !_isStreaming
                      ? null
                      : _stopDebugSession,
                  icon: const Icon(Icons.stop_circle_outlined),
                  label: const Text('Stop stream'),
                ),
                const SizedBox(width: 12),
                if (_isStarting)
                  const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 3),
                  ),
              ],
            ),
            if (_errorMessage != null) ...[
              const SizedBox(height: 8),
              Text(
                'Error: $_errorMessage',
                style: textTheme.bodyMedium?.copyWith(color: colorScheme.error),
              ),
            ],
            const SizedBox(height: 12),
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  color: colorScheme.surfaceContainer,
                  borderRadius: BorderRadius.circular(28),
                  border: Border.all(color: colorScheme.outlineVariant),
                ),
                child: SelectionArea(
                  child: _logs.isEmpty
                      ? Center(
                          child: Text(
                            _isStarting
                                ? 'Listening for debug logs...'
                                : 'No logs yet. Start the stream.',
                            style: textTheme.bodyMedium?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                        )
                      : ListView.builder(
                          controller: _scrollController,
                          padding: const EdgeInsets.all(12),
                          itemCount: _logs.length,
                          itemBuilder: (context, index) {
                            return Text(
                              _logs[index],
                              style: textTheme.bodySmall?.copyWith(
                                fontFamily: 'monospace',
                              ),
                            );
                          },
                        ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class MainScreen extends StatefulWidget {
  const MainScreen({
    super.key,
    required this.toggleTheme,
    required this.seedColor,
    required this.onSeedColorChanged,
  });

  final VoidCallback toggleTheme;
  final Color seedColor;
  final ValueChanged<Color> onSeedColorChanged;

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
  int _cacheManagementTapCount = 0;
  Timer? _cacheManagementResetTimer;
  expressive_refresh.RefreshIndicatorStatus? _refreshStatus;

  bool _rememberLyricsWindowPosition = false;
  bool _dynamicLyricsWindowColors = true;

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
    Color(0x46C8C8C8),
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
    final dynamicLyricsColours =
        prefs.getBool(_dynamicLyricsWindowColorsKey) ?? true;
    final storedTitleColor = prefs.getInt(_lyricsWindowTitleColorKey);
    final storedBackgroundColor = prefs.getInt(_lyricsWindowBackgroundColorKey);
    final storedHighlightColor = prefs.getInt(_lyricsWindowHighlightColorKey);
    if (!mounted) return;
    setState(() {
      _rememberLyricsWindowPosition = rememberPosition;
      _dynamicLyricsWindowColors = dynamicLyricsColours;
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

  Future<void> _onDynamicLyricsWindowColorsChanged(bool value) async {
    setState(() {
      _dynamicLyricsWindowColors = value;
    });
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_dynamicLyricsWindowColorsKey, value);
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
    _cacheManagementResetTimer?.cancel();
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

  void _onCacheManagementTapped() {
    _cacheManagementResetTimer?.cancel();
    _cacheManagementResetTimer = Timer(const Duration(seconds: 3), () {
      if (mounted) {
        setState(() {
          _cacheManagementTapCount = 0;
        });
      }
    });

    setState(() {
      _cacheManagementTapCount++;
      if (_cacheManagementTapCount >= 7) {
        _cacheManagementTapCount = 0;
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
      margin: const EdgeInsets.only(bottom: 16.0),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 40, 16, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Support Lyric Listener!',
                  style: textTheme.titleLarge?.copyWith(
                    color: colorScheme.onSecondaryContainer,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'If you like this app, please consider supporting my work! Every little bit helps me continue to work on this, and keep it ad-free ♥',
                  style: textTheme.bodyMedium?.copyWith(
                    color: colorScheme.onSecondaryContainer,
                  ),
                ),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.tonalIcon(
                    icon: const Icon(Icons.favorite_rounded),
                    label: const Text('Support Me'),
                    onPressed: _launchDonateUrl,
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 12),
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
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(32),
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
        const SizedBox(height: 8),
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
            SvgPicture.asset(
              'assets/images/cat-left.svg',
              height: 70,
              colorFilter: ColorFilter.mode(
                colorScheme.secondary,
                BlendMode.srcIn,
              ),
            ),
            const SizedBox(width: 24),
            SvgPicture.asset(
              'assets/images/bird-right.svg',
              height: 70,
              colorFilter: ColorFilter.mode(
                colorScheme.secondary,
                BlendMode.srcIn,
              ),
            ),
          ],
        ),
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _buildSectionHeader(BuildContext context, String title) {
    final textTheme = Theme.of(context).textTheme;
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 24, 8, 12),
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
                            color: colorScheme.primary.withOpacity(0.25),
                            blurRadius: 8,
                            offset: const Offset(0, 2),
                          ),
                      ],
                    ),
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

    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      children: [
        _buildExpressiveSection(
          context,
          title: 'Appearance',
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Theme Color',
                    style: textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 12),
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
                                    color: Theme.of(context).colorScheme.primary
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
                'Album art tones will be used automatically when this is on. Changes will apply from next song onwards.',
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
                          const SizedBox(height: 12),
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
                    child: OutlinedButton.icon(
                      onPressed: _clearLyricsCache,
                      icon: const Icon(Icons.delete_outline_rounded),
                      label: const Text('Clear Lyrics Cache'),
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
                        'Lyrics popup is not shown while music is playing, and the notification says "Connecting listener…" or "Notification access missing. Tap to fix."',
                    answerParts: [
                      const TextSpan(
                        text: "Why this happens? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            'The persistent notification shows those messages when Android(for whatever reason) has revoked notification access.\n\n',
                      ),
                      const TextSpan(
                        text: "What to do? ",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const TextSpan(
                        text:
                            'Open the persistent Lyrics Listener notification and tap the Fix notification access action. This usually restores access immediately. If the shortcut does not work, follow these steps:\n1. Stop the Lyric Service.\n2. Tap ',
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
                            " to open the system Notification Access settings only if the notification shortcut fails.\n3. Turn OFF access for 'Lyric Listener'.\n4. Return to this app.\n5. Re-grant 'Notification Access' above.\n6. Launch the Lyric Service again.",
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
                            "You may try again after some days, or try a different song.",
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

          const Divider(height: 24, indent: 16, endIndent: 16),
          _buildSectionHeader(context, 'App Setup & Permissions'),
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
          const SizedBox(height: 12),

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
          const SizedBox(height: 8),
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
          const SizedBox(height: 8),
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
              borderRadius: BorderRadius.circular(28),
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

    return Scaffold(
      appBar: AppBar(
        centerTitle: true,
        title: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (_selectedIndex == 0) ...[
              Icon(Icons.lyrics_rounded, color: colorScheme.primary),
              const SizedBox(width: 12),
            ],
            Text(appBarTitle),
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
    );
  }
}
