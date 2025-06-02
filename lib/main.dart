import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_mobile_ads/google_mobile_ads.dart';

const int _android13ApiLevel = 33;

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  MobileAds.instance.initialize();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  ThemeMode _themeMode = ThemeMode.system;

  void _toggleTheme() {
    setState(() {
      _themeMode =
          _themeMode == ThemeMode.light ? ThemeMode.dark : ThemeMode.light;
    });
  }

  @override
  Widget build(BuildContext context) {
    final baseLightColorScheme = ColorScheme.fromSeed(
      seedColor: Colors.deepPurple,
      brightness: Brightness.light,
    );
    final baseDarkColorScheme = ColorScheme.fromSeed(
      seedColor: Colors.deepPurple,
      brightness: Brightness.dark,
    );

    final baseTextTheme = GoogleFonts.manropeTextTheme(
      Theme.of(context).textTheme, // Use context theme as base
    );

    // Apply colors to light text theme
    final lightTextTheme = baseTextTheme
        .copyWith(
          displayLarge: baseTextTheme.displayLarge?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          displayMedium: baseTextTheme.displayMedium?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          displaySmall: baseTextTheme.displaySmall?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          headlineLarge: baseTextTheme.headlineLarge?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          headlineMedium: baseTextTheme.headlineMedium?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          headlineSmall: baseTextTheme.headlineSmall?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          titleLarge: baseTextTheme.titleLarge?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          titleMedium: baseTextTheme.titleMedium?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          titleSmall: baseTextTheme.titleSmall?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          bodyLarge: baseTextTheme.bodyLarge?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          bodyMedium: baseTextTheme.bodyMedium?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          bodySmall: baseTextTheme.bodySmall?.copyWith(
            color: baseLightColorScheme.onSurfaceVariant,
          ),
          labelLarge: baseTextTheme.labelLarge?.copyWith(
            color: baseLightColorScheme.onPrimaryContainer,
            fontWeight: FontWeight.w500,
          ),
          labelMedium: baseTextTheme.labelMedium?.copyWith(
            color: baseLightColorScheme.onSurfaceVariant,
          ),
          labelSmall: baseTextTheme.labelSmall?.copyWith(
            color: baseLightColorScheme.onSurfaceVariant,
          ),
        )
        .apply(
          bodyColor: baseLightColorScheme.onSurface,
          displayColor: baseLightColorScheme.onSurface,
        );

    // Apply colors to dark text theme
    final darkTextTheme = baseTextTheme
        .copyWith(
          displayLarge: baseTextTheme.displayLarge?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          displayMedium: baseTextTheme.displayMedium?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          displaySmall: baseTextTheme.displaySmall?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          headlineLarge: baseTextTheme.headlineLarge?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          headlineMedium: baseTextTheme.headlineMedium?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          headlineSmall: baseTextTheme.headlineSmall?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          titleLarge: baseTextTheme.titleLarge?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          titleMedium: baseTextTheme.titleMedium?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          titleSmall: baseTextTheme.titleSmall?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          bodyLarge: baseTextTheme.bodyLarge?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          bodyMedium: baseTextTheme.bodyMedium?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          bodySmall: baseTextTheme.bodySmall?.copyWith(
            color: baseDarkColorScheme.onSurfaceVariant,
          ),
          labelLarge: baseTextTheme.labelLarge?.copyWith(
            color: baseDarkColorScheme.onPrimaryContainer,
            fontWeight: FontWeight.w500,
          ),
          labelMedium: baseTextTheme.labelMedium?.copyWith(
            color: baseDarkColorScheme.onSurfaceVariant,
          ),
          labelSmall: baseTextTheme.labelSmall?.copyWith(
            color: baseDarkColorScheme.onSurfaceVariant,
          ),
        )
        .apply(
          bodyColor: baseDarkColorScheme.onSurface,
          displayColor: baseDarkColorScheme.onSurface,
        );

    return MaterialApp(
      title: 'Lyric Listener',
      theme: ThemeData(
        colorScheme: baseLightColorScheme,
        useMaterial3: true,
        brightness: Brightness.light,
        textTheme: lightTextTheme,
        cardTheme: CardThemeData(
          elevation: 1,
          margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: BorderSide(
              color: baseLightColorScheme.outlineVariant.withOpacity(0.5),
            ),
          ),
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(10),
            ),
            textStyle: lightTextTheme.labelLarge,
          ),
        ),
        appBarTheme: AppBarTheme(
          backgroundColor: baseLightColorScheme.surfaceContainerHighest,
          elevation: 0,
          titleTextStyle: lightTextTheme.titleLarge?.copyWith(
            color: baseLightColorScheme.onSurface,
          ),
          iconTheme: IconThemeData(
            color: baseLightColorScheme.onSurfaceVariant,
          ),
        ),
        dividerTheme: DividerThemeData(
          space: 1, // This will be overridden by height typically
          thickness: 0.5,
          color: baseLightColorScheme.outlineVariant.withOpacity(0.7),
        ),
        listTileTheme: ListTileThemeData(
          iconColor: baseLightColorScheme.onSurfaceVariant,
          titleTextStyle:
              lightTextTheme.titleMedium, // Made slightly larger for clarity
          subtitleTextStyle: lightTextTheme.bodyMedium, // Made slightly larger
          minVerticalPadding: 16, // Increased padding
          dense: false,
        ),
        expansionTileTheme: ExpansionTileThemeData(
          iconColor: baseLightColorScheme.primary,
          collapsedIconColor: baseLightColorScheme.onSurfaceVariant,
          textColor: baseLightColorScheme.primary,
          collapsedTextColor: baseLightColorScheme.onSurface,
          backgroundColor: baseLightColorScheme.surfaceContainerLow,
          collapsedBackgroundColor: baseLightColorScheme.surfaceContainer,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          collapsedShape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: baseLightColorScheme.outline),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: baseLightColorScheme.outline),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
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
            borderRadius: BorderRadius.circular(10),
          ),
          backgroundColor: baseLightColorScheme.inverseSurface,
          contentTextStyle: lightTextTheme.bodyMedium?.copyWith(
            color: baseLightColorScheme.onInverseSurface,
          ),
          actionTextColor: baseLightColorScheme.inversePrimary,
        ),
      ),
      darkTheme: ThemeData(
        colorScheme: baseDarkColorScheme,
        useMaterial3: true,
        brightness: Brightness.dark,
        textTheme: darkTextTheme,
        cardTheme: CardThemeData(
          elevation: 1,
          margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: BorderSide(
              color: baseDarkColorScheme.outlineVariant.withOpacity(0.5),
            ),
          ),
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(10),
            ),
            textStyle: darkTextTheme.labelLarge,
          ),
        ),
        appBarTheme: AppBarTheme(
          backgroundColor: baseDarkColorScheme.surfaceContainerHighest,
          elevation: 0,
          titleTextStyle: darkTextTheme.titleLarge?.copyWith(
            color: baseDarkColorScheme.onSurface,
          ),
          iconTheme: IconThemeData(color: baseDarkColorScheme.onSurfaceVariant),
        ),
        dividerTheme: DividerThemeData(
          space: 1,
          thickness: 0.5,
          color: baseDarkColorScheme.outlineVariant.withOpacity(0.7),
        ),
        listTileTheme: ListTileThemeData(
          iconColor: baseDarkColorScheme.onSurfaceVariant,
          titleTextStyle: darkTextTheme.titleMedium,
          subtitleTextStyle: darkTextTheme.bodyMedium,
          minVerticalPadding: 16,
          dense: false,
        ),
        expansionTileTheme: ExpansionTileThemeData(
          iconColor: baseDarkColorScheme.primary,
          collapsedIconColor: baseDarkColorScheme.onSurfaceVariant,
          textColor: baseDarkColorScheme.primary,
          collapsedTextColor: baseDarkColorScheme.onSurface,
          backgroundColor: baseDarkColorScheme.surfaceContainerLow,
          collapsedBackgroundColor: baseDarkColorScheme.surfaceContainer,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          collapsedShape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: baseDarkColorScheme.outline),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide(color: baseDarkColorScheme.outline),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
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
            borderRadius: BorderRadius.circular(10),
          ),
          backgroundColor: baseDarkColorScheme.inverseSurface,
          contentTextStyle: darkTextTheme.bodyMedium?.copyWith(
            color: baseDarkColorScheme.onInverseSurface,
          ),
          actionTextColor: baseDarkColorScheme.inversePrimary,
        ),
      ),
      themeMode: _themeMode,
      home: HomeScreen(toggleTheme: _toggleTheme),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.toggleTheme});

  final VoidCallback toggleTheme;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  static const platform = MethodChannel(
    'dev.optimus.lyricslistener/permissions',
  );
  int? _androidSdkInt;
  bool _isLoadingAppStatus = true; // Initial state is loading

  bool _isNotificationAccessGranted = false;
  bool _canDrawOverlays = false;
  bool _isPostNotificationsGranted = false;
  bool _isBatteryOptimizationDisabled = false;
  bool _isServiceRunning = false;
  bool _canStartService = false;

  bool _isServiceActionInProgress = false;

  RewardedAd? _rewardedAd;
  bool _isRewardedAdLoaded = false;
  bool _isLoadingAd = false;

  // Use your production Ad Unit ID or a test ID
  // final String _rewardedAdUnitId = 'ca-app-pub-3940256099942544/5224354917'; // Test ID
  final String _rewardedAdUnitId =
      'ca-app-pub-2408734303848985/6810436417'; // Your provided ID

  @override
  void initState() {
    super.initState();
    print("HomeScreen initState: Called");
    WidgetsBinding.instance.addObserver(this);
    _loadInitialData().then((_) {
      // Load ad only after initial data is loaded and if not in loading state anymore
      if (mounted && !_isLoadingAppStatus) {
        _loadRewardedAd();
      }
    });
  }

  Future<void> _loadInitialData() async {
    print("HomeScreen _loadInitialData: Starting");
    if (!mounted) return;

    // Set loading state only if not already loading (e.g., on resume)
    // During initState, _isLoadingAppStatus is already true.
    if (!_isLoadingAppStatus) {
      setState(() {
        _isLoadingAppStatus = true;
      });
    }
    // Removed the problematic 'else if' block that caused crashes during initState

    try {
      await _getAndroidVersion();
      if (mounted) {
        await _checkPermissionsStatus(); // This updates _canStartService
      }
      if (mounted) await _checkServiceStatus();
    } catch (e, s) {
      print(
        "HomeScreen _loadInitialData: Error during loading sequence: $e\n$s",
      );
    } finally {
      if (mounted) {
        // Ensure _isLoadingAppStatus is set to false if it was true
        if (_isLoadingAppStatus) {
          setState(() {
            _isLoadingAppStatus = false;
          });
        }
        print(
          "HomeScreen _loadInitialData: Finally block. _isLoadingAppStatus: $_isLoadingAppStatus (after potential setState)",
        );
      }
    }
  }

  @override
  void dispose() {
    print("HomeScreen dispose: Called");
    WidgetsBinding.instance.removeObserver(this);
    _rewardedAd?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    print("HomeScreen didChangeAppLifecycleState: $state");
    if (state == AppLifecycleState.resumed) {
      if (!_isLoadingAppStatus && !_isServiceActionInProgress) {
        _loadInitialData();
      }
      if (!_isRewardedAdLoaded &&
          _rewardedAd == null &&
          !_isLoadingAd &&
          mounted) {
        _loadRewardedAd();
      }
    }
  }

  void _loadRewardedAd() {
    if (_isLoadingAd || _isRewardedAdLoaded || !mounted) {
      return;
    }
    print("HomeScreen _loadRewardedAd: Attempting to load rewarded ad.");
    setState(() {
      _isLoadingAd = true;
    });
    RewardedAd.load(
      adUnitId: _rewardedAdUnitId,
      request: const AdRequest(),
      rewardedAdLoadCallback: RewardedAdLoadCallback(
        onAdLoaded: (RewardedAd ad) {
          print(
            'HomeScreen _loadRewardedAd: Rewarded ad loaded: ${ad.adUnitId}',
          );
          if (!mounted) {
            ad.dispose();
            return;
          }
          _rewardedAd = ad;
          _setFullScreenContentCallback();
          setState(() {
            _isRewardedAdLoaded = true;
            _isLoadingAd = false;
          });
        },
        onAdFailedToLoad: (LoadAdError error) {
          print(
            'HomeScreen _loadRewardedAd: Rewarded ad failed to load: $error',
          );
          if (!mounted) return;
          _rewardedAd = null;
          setState(() {
            _isRewardedAdLoaded = false;
            _isLoadingAd = false;
          });
        },
      ),
    );
  }

  void _setFullScreenContentCallback() {
    if (_rewardedAd == null || !mounted) return;
    _rewardedAd!.fullScreenContentCallback = FullScreenContentCallback(
      onAdShowedFullScreenContent:
          (RewardedAd ad) =>
              print('HomeScreen: Ad showed full screen content.'),
      onAdImpression:
          (RewardedAd ad) => print('HomeScreen: Ad impression occurred.'),
      onAdFailedToShowFullScreenContent: (RewardedAd ad, AdError error) {
        print('HomeScreen: Ad failed to show full screen content: $error');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Oops! Failed to show the ad. Please try again.'),
            ),
          );
        }
        ad.dispose();
        if (mounted) {
          setState(() {
            _rewardedAd = null;
            _isRewardedAdLoaded = false;
          });
          _loadRewardedAd();
        }
      },
      onAdDismissedFullScreenContent: (RewardedAd ad) {
        print('HomeScreen: Ad dismissed full screen content.');
        ad.dispose();
        if (mounted) {
          setState(() {
            _rewardedAd = null;
            _isRewardedAdLoaded = false;
          });
          _loadRewardedAd();
        }
      },
      onAdClicked: (RewardedAd ad) => print('HomeScreen: Ad clicked.'),
    );
  }

  void _showRewardedAd() {
    if (_rewardedAd == null || !_isRewardedAdLoaded || !mounted) {
      print('HomeScreen _showRewardedAd: Ad not ready or not mounted.');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _isLoadingAd
                ? 'Ad is loading, please wait...'
                : 'Ad not ready yet. Please try again.',
          ),
        ),
      );
      if (!_isLoadingAd && _rewardedAd == null) {
        _loadRewardedAd();
      }
      return;
    }
    _rewardedAd!.show(
      onUserEarnedReward: (AdWithoutView ad, RewardItem reward) {
        print(
          'HomeScreen: User earned reward: ${reward.amount} ${reward.type}',
        );
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                'Thank You! ♥ Your support means a lot to me :) Come back and see another ad in a few days if you have the time!',
              ),
              duration: Duration(seconds: 5),
            ),
          );
        }
      },
    );
  }

  Future<void> _getAndroidVersion() async {
    if (!mounted) return;
    print("HomeScreen _getAndroidVersion: Starting");
    try {
      final int? version = await platform.invokeMethod('getAndroidVersion');
      if (mounted) {
        print("HomeScreen _getAndroidVersion: Received version: $version");
        _androidSdkInt = version;
      }
    } on PlatformException catch (e) {
      print('HomeScreen _getAndroidVersion: Failed - ${e.message}');
      if (mounted) {
        _androidSdkInt = null;
      }
    }
  }

  Future<void> _checkPermissionsStatus() async {
    if (!mounted) return;
    print("HomeScreen _checkPermissionsStatus: Starting");

    bool tempNotificationAccess = false;
    bool tempCanDrawOverlays = false;
    bool tempPostNotifications =
        (_androidSdkInt != null && _androidSdkInt! < _android13ApiLevel);
    bool tempBatteryOptDisabled = false;

    try {
      final results = await Future.wait([
        platform.invokeMethod('isNotificationAccessGranted').catchError((e) {
          print("Error isNotificationAccessGranted: $e");
          return false;
        }),
        platform.invokeMethod('canDrawOverlays').catchError((e) {
          print("Error canDrawOverlays: $e");
          return false;
        }),
        (_androidSdkInt != null && _androidSdkInt! >= _android13ApiLevel)
            ? platform.invokeMethod('isPostNotificationsGranted').catchError((
              e,
            ) {
              print("Error isPostNotificationsGranted: $e");
              return false;
            })
            : Future.value(tempPostNotifications),
        platform.invokeMethod('isIgnoringBatteryOptimizations').catchError((e) {
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
        'HomeScreen _checkPermissionsStatus: PlatformException - ${e.message}',
      );
    } catch (e) {
      print('HomeScreen _checkPermissionsStatus: General Exception - $e');
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
    print("HomeScreen _checkServiceStatus: Starting");
    bool tempIsServiceRunning = false;
    try {
      final bool? isRunning = await platform.invokeMethod<bool>(
        'isLyricServiceRunning',
      );
      if (isRunning != null) {
        tempIsServiceRunning = isRunning;
      }
    } on PlatformException catch (e) {
      print('HomeScreen _checkServiceStatus: Failed - ${e.message}');
    } catch (e) {
      print('HomeScreen _checkServiceStatus: General Error - $e');
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
    print("HomeScreen _handlePermissionRequest: Starting $opName");
    try {
      await requestFunction();
      print(
        "HomeScreen _handlePermissionRequest: $opName request sent. App will refresh on resume via _loadInitialData.",
      );
    } on PlatformException catch (e) {
      print(
        'HomeScreen _handlePermissionRequest: Failed during $opName - ${e.message}',
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
    print("HomeScreen _startLyricService: Attempting to start.");
    if (!mounted || _isServiceActionInProgress) return;

    setState(() {
      _isServiceActionInProgress = true;
    });

    try {
      await platform.invokeMethod('startLyricService');
      await Future.delayed(const Duration(milliseconds: 1500));
      if (mounted) await _checkServiceStatus();
    } on PlatformException catch (e) {
      print('HomeScreen _startLyricService: Failed - ${e.message}');
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
    print("HomeScreen _stopLyricService: Attempting to stop.");
    if (!mounted || _isServiceActionInProgress) return;

    setState(() {
      _isServiceActionInProgress = true;
    });

    try {
      await platform.invokeMethod('stopLyricService');
      await Future.delayed(const Duration(milliseconds: 1500));
      if (mounted) await _checkServiceStatus();
    } on PlatformException catch (e) {
      print('HomeScreen _stopLyricService: Failed - ${e.message}');
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
      () => platform.invokeMethod('requestNotificationAccess'),
      operationName: "Notification Access",
    );
  }

  Future<void> _requestOverlayPermission() async {
    await _handlePermissionRequest(
      () => platform.invokeMethod('requestOverlayPermission'),
      operationName: "Overlay Permission",
    );
  }

  Future<void> _requestPostNotificationsPermission() async {
    await _handlePermissionRequest(
      () => platform.invokeMethod('requestPostNotifications'),
      operationName: "Post Notifications Permission",
    );
  }

  Future<void> _requestDisableBatteryOptimization() async {
    await _handlePermissionRequest(
      () => platform.invokeMethod('requestDisableBatteryOptimization'),
      operationName: "Battery Optimization",
    );
  }

  Widget _buildPermissionStatusIcon(bool isGranted, {bool optional = false}) {
    final colorScheme = Theme.of(context).colorScheme;
    return Icon(
      isGranted
          ? Icons.check_circle_outline_rounded
          : (optional
              ? Icons.info_outline_rounded
              : Icons.error_outline_rounded),
      color:
          isGranted
              ? Colors.green.shade600
              : (optional ? colorScheme.tertiary : colorScheme.error),
      size: 24,
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
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: ElevatedButton(
          onPressed: isGranted ? null : onPressed,
          style: ElevatedButton.styleFrom(
            backgroundColor:
                isGranted
                    ? colorScheme.surfaceContainerHighest
                    : colorScheme.primaryContainer,
            foregroundColor:
                isGranted
                    ? colorScheme.onSurfaceVariant
                    : colorScheme.onPrimaryContainer,
            elevation: isGranted ? 0 : 1,
          ),
          child: Text(isGranted ? 'Granted' : 'Grant'),
        ),
        onTap: isGranted ? null : onPressed,
      ),
    );
  }

  Widget _buildSupportCard() {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Card(
      elevation: 2,
      margin: const EdgeInsets.only(bottom: 16.0), // Added margin for spacing
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Support Lyric Listener!',
              style: textTheme.titleLarge?.copyWith(color: colorScheme.primary),
            ),
            const SizedBox(height: 8),
            Text(
              'Hi, you can ignore this for now - but if you like this app, please consider supporting me by watching an ad or two.',
              style: textTheme.bodyMedium,
            ),
            const SizedBox(height: 16),
            Center(
              child: ElevatedButton.icon(
                icon: Icon(
                  _isLoadingAd
                      ? Icons.hourglass_empty_rounded
                      : Icons.play_circle_fill_rounded,
                ),
                label: Text(
                  _isLoadingAd
                      ? 'Loading Ad...'
                      : 'Support me by seeing an ad!',
                ),
                onPressed:
                    (_isRewardedAdLoaded && !_isLoadingAd)
                        ? _showRewardedAd
                        : null,
                style: ElevatedButton.styleFrom(
                  backgroundColor:
                      (_isRewardedAdLoaded && !_isLoadingAd)
                          ? colorScheme.primaryContainer
                          : colorScheme.surfaceContainerHighest,
                  foregroundColor:
                      (_isRewardedAdLoaded && !_isLoadingAd)
                          ? colorScheme.onPrimaryContainer
                          : colorScheme.onSurfaceVariant.withOpacity(0.7),
                ),
              ),
            ),
            if (!_isRewardedAdLoaded && !_isLoadingAd)
              Padding(
                padding: const EdgeInsets.only(top: 8.0),
                child: Center(
                  child: Text(
                    '(Ad not available right now, try again later)',
                    style: textTheme.bodySmall?.copyWith(
                      fontStyle: FontStyle.italic,
                      color: colorScheme.onSurfaceVariant.withOpacity(0.7),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildWelcomeSection(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Column(
      children: [
        Icon(
          Icons.music_note_rounded,
          size: 50, // Slightly smaller
          color: colorScheme.secondary,
        ),
        const SizedBox(height: 12),
        Text(
          'Welcome to Lyric Listener!',
          style: textTheme.headlineSmall?.copyWith(
            color: colorScheme.primary,
            fontWeight: FontWeight.bold,
          ),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0),
          child: Text(
            'Get ready for a purr-fectly synced lyric experience with your favorite tunes!',
            style: textTheme.titleMedium?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
            textAlign: TextAlign.center,
          ),
        ),
        const SizedBox(height: 20),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SvgPicture.asset(
              'assets/images/cat-left.svg',
              height: 65, // Slightly smaller
              colorFilter: ColorFilter.mode(
                colorScheme.primary.withOpacity(0.9),
                BlendMode.srcIn,
              ),
            ),
            const SizedBox(width: 24),
            SvgPicture.asset(
              'assets/images/bird-right.svg',
              height: 65, // Slightly smaller
              colorFilter: ColorFilter.mode(
                colorScheme.primary.withOpacity(0.9),
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
      padding: const EdgeInsets.only(top: 16.0, bottom: 8.0),
      child: Text(
        title,
        style: textTheme.titleLarge?.copyWith(
          color: colorScheme.secondary,
          fontWeight: FontWeight.w600,
        ),
        textAlign: TextAlign.center,
      ),
    );
  }

  Widget _buildFaqSection(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    // final colorScheme = Theme.of(context).colorScheme;

    return Padding(
      padding: const EdgeInsets.only(top: 16.0, bottom: 8.0),
      child: Card(
        // Wrap ExpansionTile in a Card for consistent styling
        margin: EdgeInsets.zero, // CardTheme handles margin
        child: ExpansionTile(
          title: Text(
            'Frequently Asked Questions',
            style: textTheme.titleMedium,
          ),
          initiallyExpanded: false,
          childrenPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 8,
          ),
          children: <Widget>[
            _buildFaqItem(
              context,
              question:
                  'Lyrics popup is not shown, and the notification says "Waiting for song..." or "Waiting for media app..."',
              answerParts: [
                const TextSpan(
                  text: "Why this happens? ",
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                const TextSpan(
                  text:
                      "Well, certain devices have evil task killers that stop processes without proper procedure, leading to problems when the app tries to restart.\n\n",
                ),
                const TextSpan(
                  text: "What to do? ",
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                const TextSpan(
                  text:
                      "Try stopping and restarting the service, and give it a few seconds. If the lyrics are still not shown, go to app info and clear data and grant permissions and start the service again.",
                ),
              ],
            ),
            const Divider(height: 16),
            _buildFaqItem(
              context,
              question:
                  'Youtube videos always say "Lyrics not found" or show incorrect lyrics',
              answerParts: [
                const TextSpan(
                  text: "Why this happens? ",
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                const TextSpan(
                  text:
                      "Youtube video notifications generally do not follow the proper naming scheme for songs, which hinders the app's ability to detect what is actually playing.\n\n",
                ),
                const TextSpan(
                  text: "What to do? ",
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                const TextSpan(
                  text:
                      "Nothing much on your side. I will try to improve this in the future, for now - official audios without extra words in the video name work best.",
                ),
              ],
            ),
            const Divider(height: 16),
            _buildFaqItem(
              context,
              question: 'Incorrect (or no) lyrics are displayed',
              answerParts: [
                const TextSpan(text: "Why this happens? "),
                const TextSpan(
                  text: "Perhaps the archives are incomplete",
                  style: TextStyle(decoration: TextDecoration.lineThrough),
                ),
                const TextSpan(
                  text:
                      ". The lyrics source possibly does not have lyrics of that particular song.\n\n",
                ),
                const TextSpan(
                  text: "What to do? ",
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                const TextSpan(
                  text: "Try again after some days, or try a different song.",
                ),
              ],
            ),
          ],
        ),
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
      "HomeScreen build: Called. _isLoadingAppStatus: $_isLoadingAppStatus, _isServiceRunning: $_isServiceRunning, _canStartService: $_canStartService, _isServiceActionInProgress: $_isServiceActionInProgress",
    );
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    Widget screenContent;

    if (_isLoadingAppStatus) {
      screenContent = const Center(
        child: Padding(
          padding: EdgeInsets.all(32.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 20),
              Text("Loading app status..."),
            ],
          ),
        ),
      );
    } else {
      screenContent = ListView(
        // Changed to ListView for better structure with sections
        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
        children: <Widget>[
          _buildSupportCard(),
          _buildWelcomeSection(context),

          const SizedBox(height: 16),
          const Divider(height: 24, indent: 16, endIndent: 16),
          _buildSectionHeader(context, 'App Setup & Permissions'),
          Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: 24.0,
              vertical: 4.0,
            ),
            child: Text(
              'Grant these permissions for the app to function correctly.',
              style: textTheme.bodyMedium?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ),
          const SizedBox(height: 12),

          // Required Permissions
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

          // Optional Settings
          Padding(
            padding: const EdgeInsets.only(top: 12.0, bottom: 4.0),
            child: Text(
              'Optional Enhancements',
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
              'Consider these for a more reliable experience.',
              style: textTheme.bodySmall?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ),
          const SizedBox(height: 8),
          _buildPermissionRequestTile(
            title: 'Disable Battery Optimization',
            subtitle:
                'Helps the service run reliably in the background (Recommended).',
            isGranted: _isBatteryOptimizationDisabled,
            onPressed: _requestDisableBatteryOptimization,
            optional: true,
          ),
          const SizedBox(height: 16),
          const Divider(height: 24, indent: 16, endIndent: 16),
          _buildSectionHeader(context, 'Lyric Service Control'),

          // Service Status and Control
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12.0),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text('Service Status:', style: textTheme.titleMedium),
                const SizedBox(width: 8),
                Icon(
                  _isServiceRunning
                      ? Icons.rocket_launch_rounded
                      : Icons.rocket_outlined,
                  color:
                      _isServiceRunning
                          ? Colors.green.shade600
                          : colorScheme.onSurface.withOpacity(0.6),
                  size: 22,
                ),
                const SizedBox(width: 4),
                Expanded(
                  child: Text(
                    _isServiceRunning
                        ? 'Active'
                        : (_canStartService
                            ? 'Ready to Launch'
                            : 'Awaiting Permissions'),
                    style: textTheme.bodyMedium?.copyWith(
                      fontStyle: FontStyle.italic,
                      color:
                          _isServiceRunning
                              ? Colors.green.shade600
                              : colorScheme.onSurface.withOpacity(0.7),
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Center(
            child: ElevatedButton.icon(
              icon:
                  _isServiceActionInProgress
                      ? SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color:
                              _isServiceRunning
                                  ? colorScheme.onErrorContainer
                                  : colorScheme.onPrimary,
                        ),
                      )
                      : Icon(
                        _isServiceRunning
                            ? Icons.stop_circle_outlined
                            : Icons.play_circle_outline_rounded,
                      ),
              label: Text(
                _isServiceActionInProgress
                    ? (_isServiceRunning ? 'Stopping...' : 'Starting...')
                    : (_isServiceRunning
                        ? 'Stop Lyric Service'
                        : 'Launch Lyric Service'),
              ),
              onPressed:
                  _isServiceActionInProgress
                      ? null
                      : (_isServiceRunning
                          ? _stopLyricService
                          : (_canStartService ? _startLyricService : null)),
              style: ElevatedButton.styleFrom(
                backgroundColor:
                    _isServiceRunning
                        ? colorScheme.errorContainer
                        : (_canStartService
                            ? colorScheme.primary
                            : colorScheme.surfaceContainerHighest.withOpacity(
                              0.5,
                            )),
                foregroundColor:
                    _isServiceRunning
                        ? colorScheme.onErrorContainer
                        : (_canStartService
                            ? colorScheme.onPrimary
                            : colorScheme.onSurfaceVariant.withOpacity(0.5)),
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 14,
                ),
              ).copyWith(
                elevation: WidgetStateProperty.resolveWith<double?>((
                  Set<WidgetState> states,
                ) {
                  if (states.contains(WidgetState.disabled) &&
                      !_isServiceActionInProgress) {
                    return 0;
                  }
                  return 2;
                }),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: colorScheme.surfaceContainer,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: colorScheme.outlineVariant.withOpacity(0.3),
              ),
            ),
            child: Text(
              _isServiceRunning
                  ? 'The lyric service is active. You can stop it here if needed. It will try to restart if music plays and permissions are granted (if not explicitly stopped via this button).'
                  : 'Once permissions are granted, launch the service. It will run in the background. In case it stops, come back here to launch it again!',
              style: textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ),

          const SizedBox(height: 16),
          const Divider(height: 24, indent: 16, endIndent: 16),
          _buildFaqSection(context), // Added FAQ Section

          const SizedBox(height: 20), // Bottom padding
        ],
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.lyrics_outlined, color: colorScheme.primary),
            const SizedBox(width: 8),
            const Text('Lyric Listener'),
          ],
        ),
        actions: [
          IconButton(
            icon: Icon(
              Theme.of(context).brightness == Brightness.dark
                  ? Icons.light_mode_outlined
                  : Icons.dark_mode_outlined,
            ),
            onPressed: widget.toggleTheme,
            tooltip: 'Toggle Theme',
          ),
        ],
      ),
      body: SafeArea(child: Center(child: screenContent)),
    );
  }
}
