import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:google_mobile_ads/google_mobile_ads.dart'; // Import AdMob

// Android 13 API Level
const int _android13ApiLevel = 33;

void main() {
  WidgetsFlutterBinding.ensureInitialized(); // Ensure bindings are initialized
  MobileAds.instance.initialize(); // Initialize AdMob
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
      Theme.of(context).textTheme,
    );

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
          space: 1,
          thickness: 0.5,
          color: baseLightColorScheme.outlineVariant,
        ),
        listTileTheme: ListTileThemeData(
          iconColor: baseLightColorScheme.onSurfaceVariant,
          titleTextStyle: lightTextTheme.titleSmall,
          subtitleTextStyle: lightTextTheme.bodySmall,
          minVerticalPadding: 12,
          dense: false,
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
          actionTextColor:
              baseLightColorScheme.inversePrimary, // For SnackBar actions
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
          color: baseDarkColorScheme.outlineVariant,
        ),
        listTileTheme: ListTileThemeData(
          iconColor: baseDarkColorScheme.onSurfaceVariant,
          titleTextStyle: darkTextTheme.titleSmall,
          subtitleTextStyle: darkTextTheme.bodySmall,
          minVerticalPadding: 12,
          dense: false,
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
          actionTextColor:
              baseDarkColorScheme.inversePrimary, // For SnackBar actions
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
  bool _isLoadingAppStatus = true;

  bool _isNotificationAccessGranted = false;
  bool _canDrawOverlays = false;
  bool _isPostNotificationsGranted = false;
  bool _isBatteryOptimizationDisabled = false;
  bool _isServiceRunning = false;
  bool _canStartService = false;

  // --- AdMob Rewarded Ad State ---
  RewardedAd? _rewardedAd;
  bool _isRewardedAdLoaded = false;
  bool _isLoadingAd = false; // To show loading indicator for ad

  // Android NOT TEST Ad Unit ID for Rewarded Ads
  final String _rewardedAdUnitId = 'ca-app-pub-2408734303848985/6810436417';

  @override
  void initState() {
    super.initState();
    print("HomeScreen initState: Called");
    WidgetsBinding.instance.addObserver(this);
    _loadInitialData().then((_) {
      if (mounted && !_isLoadingAppStatus) {
        // Load ad only if initial setup didn't fail
        _loadRewardedAd();
      }
    });
  }

  Future<void> _loadInitialData() async {
    print("HomeScreen _loadInitialData: Starting");
    if (mounted && !_isLoadingAppStatus) {
      setState(() {
        _isLoadingAppStatus = true;
      });
    } else if (!mounted && !_isLoadingAppStatus) {
      _isLoadingAppStatus = true;
    }

    try {
      await _getAndroidVersion();
      if (mounted) {
        await _checkPermissionsStatus();
      }
      if (mounted) {
        await _checkServiceStatus();
      }
    } catch (e, s) {
      print(
        "HomeScreen _loadInitialData: Error during loading sequence: $e\n$s",
      );
      // Potentially show an error message to the user if critical data failed to load
    } finally {
      if (mounted) {
        setState(() {
          _isLoadingAppStatus = false;
        });
        print(
          "HomeScreen _loadInitialData: Finally block executed. _isLoadingAppStatus: $_isLoadingAppStatus",
        );
      }
    }
  }

  @override
  void dispose() {
    print("HomeScreen dispose: Called");
    WidgetsBinding.instance.removeObserver(this);
    _rewardedAd?.dispose(); // Dispose ad
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    print("HomeScreen didChangeAppLifecycleState: $state");
    if (state == AppLifecycleState.resumed) {
      if (!_isLoadingAppStatus) {
        _loadInitialData(); // This will re-check permissions and service status
      }
      // Optionally, attempt to reload ad if not already loaded or loading
      if (!_isRewardedAdLoaded &&
          _rewardedAd == null &&
          !_isLoadingAd &&
          mounted) {
        _loadRewardedAd();
      }
    }
  }

  // --- AdMob Rewarded Ad Methods ---
  void _loadRewardedAd() {
    if (_isLoadingAd || _isRewardedAdLoaded || !mounted) {
      return; // Don't load if already loading/loaded or not mounted
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
          _setFullScreenContentCallback(); // IMPORTANT: Set callbacks after loading
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
          _rewardedAd = null; // Ensure it's null on failure
          setState(() {
            _isRewardedAdLoaded = false;
            _isLoadingAd = false;
          });
          // Optionally, show a subtle message or log, but don't be too intrusive
          // if (mounted) {
          //   ScaffoldMessenger.of(context).showSnackBar(
          //     SnackBar(content: Text('Ad failed to load. Please check your connection.')),
          //   );
          // }
        },
      ),
    );
  }

  void _setFullScreenContentCallback() {
    if (_rewardedAd == null || !mounted) return;

    _rewardedAd!.fullScreenContentCallback = FullScreenContentCallback(
      onAdShowedFullScreenContent: (RewardedAd ad) {
        print('HomeScreen: Ad showed full screen content.');
      },
      onAdImpression: (RewardedAd ad) {
        print('HomeScreen: Ad impression occurred.');
      },
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
          _loadRewardedAd(); // Attempt to load a new ad
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
          _loadRewardedAd(); // Load the next ad, allowing user to watch another
        }
      },
      onAdClicked: (RewardedAd ad) {
        print('HomeScreen: Ad clicked.');
      },
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
        _loadRewardedAd(); // Try to load if it's completely missing and not already loading
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
              duration: Duration(
                seconds: 5,
              ), // Longer duration for this message
            ),
          );
        }
        // Ad is disposed and new one is loaded in onAdDismissedFullScreenContent
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
      if (_androidSdkInt == null) {
        print(
          "HomeScreen _checkPermissionsStatus: Android SDK version unknown for permission checks.",
        );
      }

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
        "Permissions updated: NA: $_isNotificationAccessGranted, DO: $_canDrawOverlays, PN: $_isPostNotificationsGranted, BO: $_isBatteryOptimizationDisabled",
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
    try {
      await platform.invokeMethod('startLyricService');
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
        if (mounted) {
          setState(() {
            _isServiceRunning = false;
          });
        }
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

  Widget _buildServiceStatusRow() {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 16.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text('Lyric Service:', style: textTheme.titleMedium),
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
                      ? '(Ready to Launch)'
                      : '(Awaiting Permissions)'),
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
    );
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

  // --- New Widget for Support Card ---
  Widget _buildSupportCard() {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Card(
      elevation: 2,
      margin: const EdgeInsets.symmetric(
        vertical: 12.0,
        horizontal: 8.0,
      ), // Adjusted margin
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
              // Center the button
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
                  padding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 12,
                  ),
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

  @override
  Widget build(BuildContext context) {
    print(
      "HomeScreen build: Called. _isLoadingAppStatus: $_isLoadingAppStatus",
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
      screenContent = SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: <Widget>[
            // --- SUPPORT CARD AT THE TOP ---
            _buildSupportCard(),
            const SizedBox(height: 16), // Spacing after support card

            Icon(
              Icons.music_note_rounded,
              size: 60,
              color: colorScheme.secondary,
            ),
            const SizedBox(height: 16),
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
            const SizedBox(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                SvgPicture.asset(
                  'assets/images/cat-left.svg',
                  height: 80,
                  colorFilter: ColorFilter.mode(
                    colorScheme.primary.withOpacity(0.9),
                    BlendMode.srcIn,
                  ),
                ),
                const SizedBox(width: 32),
                SvgPicture.asset(
                  'assets/images/bird-right.svg',
                  height: 80,
                  colorFilter: ColorFilter.mode(
                    colorScheme.primary.withOpacity(0.9),
                    BlendMode.srcIn,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 32),
            Divider(height: 32, indent: 16, endIndent: 16),
            Text(
              'Required Permissions',
              style: textTheme.titleLarge?.copyWith(
                color: colorScheme.secondary,
                fontWeight: FontWeight.w600,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 4),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32.0),
              child: Text(
                'These are needed for the app to function correctly.',
                style: textTheme.bodyMedium?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 16),
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
            const SizedBox(height: 20),
            Divider(height: 32, indent: 16, endIndent: 16),
            Text(
              'Optional Settings',
              style: textTheme.titleLarge?.copyWith(
                color: colorScheme.secondary,
                fontWeight: FontWeight.w600,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 4),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32.0),
              child: Text(
                'Enhance your experience with these settings.',
                style: textTheme.bodyMedium?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 16),
            _buildPermissionRequestTile(
              title: 'Disable Battery Optimization',
              subtitle:
                  'Helps the service run reliably in the background (Recommended).',
              isGranted: _isBatteryOptimizationDisabled,
              onPressed: _requestDisableBatteryOptimization,
              optional: true,
            ),
            _buildServiceStatusRow(),
            const SizedBox(height: 20),
            ElevatedButton.icon(
              icon: Icon(
                _isServiceRunning
                    ? Icons.stop_circle_outlined
                    : Icons.play_circle_outline_rounded,
              ),
              label: Text(
                _isServiceRunning
                    ? 'Service is Active'
                    : 'Launch Lyric Service',
              ),
              onPressed:
                  (_canStartService && !_isServiceRunning
                      ? _startLyricService
                      : null),
              style: ElevatedButton.styleFrom(
                backgroundColor:
                    _isServiceRunning
                        ? colorScheme.tertiaryContainer
                        : (_canStartService
                            ? colorScheme.primary
                            : colorScheme.surfaceContainerHighest),
                foregroundColor:
                    _isServiceRunning
                        ? colorScheme.onTertiaryContainer
                        : (_canStartService
                            ? colorScheme.onPrimary
                            : colorScheme.onSurfaceVariant.withOpacity(0.5)),
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 14,
                ),
              ).copyWith(
                elevation: WidgetStateProperty.all(
                  _canStartService && !_isServiceRunning ? 2 : 0,
                ),
              ),
            ),
            const SizedBox(height: 24),
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
                'Once permissions are granted and the service is launched, you can close this screen. The lyric service will continue running in the background. In case it stops, come back here to launch the service again!',
                style: textTheme.bodySmall,
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 20),
          ],
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.lyrics_outlined, color: colorScheme.primary),
            const SizedBox(width: 8),
            Text('Lyric Listener'),
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
