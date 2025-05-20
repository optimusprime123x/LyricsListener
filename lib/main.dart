import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// Android 13 API Level
const int _android13ApiLevel = 33;

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // Default to system theme
  ThemeMode _themeMode = ThemeMode.system;

  void _toggleTheme() {
    setState(() {
      _themeMode = _themeMode == ThemeMode.light ? ThemeMode.dark : ThemeMode.light;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Lyric Listener',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.deepPurple,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        brightness: Brightness.dark,
      ),
      themeMode: _themeMode, // Use the state variable for themeMode
      home: HomeScreen(toggleTheme: _toggleTheme), // Pass the toggle function to HomeScreen
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
  static const platform = MethodChannel('com.example.myapp/permissions');
  int? _androidSdkInt; // To store the Android SDK version
  bool _isLoadingAndroidVersion = true;

  bool _isNotificationAccessGranted = false;
  bool _canDrawOverlays = false;
  bool _isPostNotificationsGranted = false;
  bool _isLoadingPermissions = true;
  bool _isServiceRunning = false;
  bool _canStartService = false; // Add state to control service start button

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _getAndroidVersion();
    _checkPermissionsStatus();
     _checkServiceStatus(); // Initial service status check
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissionsStatus();
       _checkServiceStatus();
    }
  }

  Future<void> _getAndroidVersion() async {
    try {
      final int? version = await platform.invokeMethod('getAndroidVersion');
      if (mounted) {
        setState(() {
          _androidSdkInt = version;
          _isLoadingAndroidVersion = false;
        });
      }
    } on PlatformException catch (e) {
      print('Failed to get Android version: ${e.message}');
      if (mounted) {
        setState(() {
          _isLoadingAndroidVersion = false;
        });
      }
    }
  }

  Future<void> _checkPermissionsStatus() async {
    if (!mounted) return;
    setState(() {
      _isLoadingPermissions = true;
    });
    try {
      final bool notificationAccess = await platform.invokeMethod('isNotificationAccessGranted');
      final bool canDrawOverlays = await platform.invokeMethod('canDrawOverlays');
      bool postNotificationsGranted = true; // Assume true for older Android
      if (_androidSdkInt != null && _androidSdkInt! >= _android13ApiLevel) {
         postNotificationsGranted = await platform.invokeMethod('isPostNotificationsGranted');
      }

      if (mounted) {
        setState(() {
          _isNotificationAccessGranted = notificationAccess;
          _canDrawOverlays = canDrawOverlays;
          _isPostNotificationsGranted = postNotificationsGranted;
          _isLoadingPermissions = false;
          // Determine if service can be started based on permissions
          _canStartService = _isNotificationAccessGranted && _canDrawOverlays && _isPostNotificationsGranted;
        });
         _checkServiceStatus(); // Update service status icon after checking permissions
      }
    } on PlatformException catch (e) {
      print('Failed to check permissions: ${e.message}');
      if (mounted) {
        setState(() {
          _isLoadingPermissions = false;
        });
      }
    }
  }

   Future<void> _checkServiceStatus() async {
       if (!mounted) return;
       try {
         final bool isRunning = await platform.invokeMethod('isLyricServiceRunning');
         if (mounted) {
           setState(() {
             _isServiceRunning = isRunning;
           });
         }
       } on PlatformException catch (e) {
         print('Failed to check service status: ${e.message}');
          if (mounted) {
           setState(() {
             _isServiceRunning = false;
           });
         }
       }
     }

   Future<void> _startLyricService() async {
      try {
        await platform.invokeMethod('startLyricService');
         _checkServiceStatus();
      } on PlatformException catch (e) {
        print('Failed to start lyric service: ${e.message}');
         if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Failed to start lyric service: ${e.message}')),
          );
        }
         if (mounted) {
          setState(() {
            _isServiceRunning = false;
          });
        }
      }
   }

  Future<void> _requestNotificationAccess() async {
    try {
      await platform.invokeMethod('requestNotificationAccess');
    } on PlatformException catch (e) {
      print('Failed to open notification settings: ${e.message}');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to open notification settings: ${e.message}')),
        );
      }
    }
  }

  Future<void> _requestOverlayPermission() async {
    try {
      await platform.invokeMethod('requestOverlayPermission');
    } on PlatformException catch (e) {
      print('Failed to open overlay settings: ${e.message}');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to open overlay settings: ${e.message}')),
        );
      }
    }
  }

   Future<void> _requestPostNotificationsPermission() async {
     try {
       await platform.invokeMethod('requestPostNotifications');
     } on PlatformException catch (e) {
       print('Failed to request post notifications permission: ${e.message}');
        if (mounted) {
         ScaffoldMessenger.of(context).showSnackBar(
           SnackBar(content: Text('Failed to request notification permission: ${e.message}')),
         );
       }
     }
   }

  Widget _buildPermissionStatusIcon(bool isGranted) {
    if (_isLoadingPermissions) {
      return const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2));
    }
    return Icon(
      isGranted ? Icons.check_circle : Icons.warning,
      color: isGranted ? Colors.green : Colors.amber,
      size: 20,
    );
  }

   Widget _buildRestrictedSettingsNote() {
    if (_androidSdkInt != null && _androidSdkInt! >= _android13ApiLevel && !_isNotificationAccessGranted) {
      return Padding(
        padding: const EdgeInsets.only(top: 10.0, bottom: 5.0),
        child: Text(
          'Note: On Android 13+, for apps not installed from an app store, '
          'you might need to manually "Allow restricted settings" for this app in its App Info page '
          'before Notification Access can be granted.',
          style: TextStyle(
            fontSize: 13,
            fontStyle: FontStyle.italic,
            color: Theme.of(context).colorScheme.onSurface.withOpacity(0.7),
          ),
          textAlign: TextAlign.center,
        ),
      );
    }
    return const SizedBox.shrink();
  }

  Widget _buildServiceStatusRow() {
     if (_isLoadingPermissions || _isLoadingAndroidVersion) {
       return const SizedBox.shrink();
     }

     return Padding(
       padding: const EdgeInsets.only(top: 20.0),
       child: Row(
         mainAxisAlignment: MainAxisAlignment.center,
         children: [
           Text(
             'Lyric Service Status:',
             style: TextStyle(
               fontSize: 16,
               fontWeight: FontWeight.bold,
                color: Theme.of(context).colorScheme.onSurface,
             ),
           ),
           const SizedBox(width: 8),
           Icon(
             _isServiceRunning ? Icons.check_circle : Icons.info,
             color: _isServiceRunning ? Colors.green : Theme.of(context).colorScheme.onSurface.withOpacity(0.6),
             size: 20,
           ),
            const SizedBox(width: 4),
             if (!_isServiceRunning)
              Expanded(
                child: Text(
                  _canStartService ? '(Ready to start)' : '(Awaiting permissions)',
                   style: TextStyle(
                    fontSize: 14,
                     fontStyle: FontStyle.italic,
                     color: Theme.of(context).colorScheme.onSurface.withOpacity(0.6),
                   ),
                   overflow: TextOverflow.ellipsis,
                 ),
              ),
         ],
       ),
     );
   }


  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        backgroundColor: colorScheme.inversePrimary,
        title: const Text('Lyric Listener'),
        actions: [
          IconButton(
            icon: Icon(
              theme.brightness == Brightness.dark ? Icons.wb_sunny : Icons.nightlight_round,
            ),
            onPressed: widget.toggleTheme,
            tooltip: 'Toggle Theme',
          ),
        ],
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: SingleChildScrollView(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: <Widget>[
                Icon(Icons.music_note, size: 80, color: colorScheme.primary),
                const SizedBox(height: 20),
                Text(
                  'Welcome to Lyric Listener!',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                    color: colorScheme.onSurface,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 10),
                Text(
                  'This app needs permissions to listen for music notifications and display lyrics over other apps.',
                  style: TextStyle(
                    fontSize: 16,
                    color: colorScheme.onSurface.withOpacity(0.8),
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 30),
                 if (_androidSdkInt == null || _androidSdkInt! >= _android13ApiLevel)
                 Padding(
                   padding: const EdgeInsets.only(bottom: 10.0),
                   child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        ElevatedButton(
                          onPressed: _requestPostNotificationsPermission,
                          child: const Text('Grant Notification Permission (Runtime)'),
                        ),
                        const SizedBox(width: 8),
                        _buildPermissionStatusIcon(_isPostNotificationsGranted),
                      ],
                    ),
                 ),
                Row(
                   mainAxisAlignment: MainAxisAlignment.center,
                   children: [
                     ElevatedButton(
                       onPressed: _requestNotificationAccess,
                       child: const Text('Grant Notification Access'),
                     ),
                     const SizedBox(width: 8),
                     _buildPermissionStatusIcon(_isNotificationAccessGranted),
                   ],
                 ),
                if (_isLoadingAndroidVersion)
                  const Padding(
                    padding: EdgeInsets.all(8.0),
                    child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
                  )
                else
                  _buildRestrictedSettingsNote(),
                const SizedBox(height: 10),
                 Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                       ElevatedButton(
                        onPressed: _requestOverlayPermission,
                        child: const Text('Grant Display Over Other Apps Permission'),
                      ),
                      const SizedBox(width: 8),
                      _buildPermissionStatusIcon(_canDrawOverlays),
                    ],
                  ),

                _buildServiceStatusRow(),

                const SizedBox(height: 20),
                ElevatedButton(
                   onPressed: _canStartService && !_isServiceRunning ? _startLyricService : null,
                   child: Text(_isServiceRunning ? 'Lyric Service Running' : 'Start Lyric Service'),
                ),

                const SizedBox(height: 20),
                Text(
                  'After granting permissions and starting the service, you can leave the app. It will run in the background.',
                  style: TextStyle(
                    fontSize: 14,
                    fontStyle: FontStyle.italic,
                    color: colorScheme.onSurface.withOpacity(0.7),
                  ),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
