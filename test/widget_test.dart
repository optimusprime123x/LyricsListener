import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:lyricslistener/main.dart';
import 'package:lyricslistener/widgets/expressive_refresh_indicator.dart'
    as expressive_refresh;

void main() {
  const MethodChannel channel = MethodChannel('dev.optimus.lyricslistener/permissions');

  setUp(() {
    // Set a large screen size to ensure all ListView items are rendered
    TestWidgetsFlutterBinding.ensureInitialized();
  });

  tearDown(() {
  });

  void registerMock(WidgetTester tester) {
    tester.view.physicalSize = const Size(1080, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'getAndroidVersion':
            return 33; // Android 13
          case 'isNotificationAccessGranted':
            return true;
          case 'canDrawOverlays':
            return true;
          case 'isPostNotificationsGranted':
            return true;
          case 'isIgnoringBatteryOptimizations':
            return false;
          case 'isLyricServiceRunning':
            return false;
          case 'clearLyricsCache':
            return 0;
          default:
            return null;
        }
      },
    );
    addTearDown(() {
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      );
    });
  }

  testWidgets('MyApp initializes with seed color', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));

    // Verify app builds without errors and MaterialApp is present
    expect(find.byType(MaterialApp), findsOneWidget);
  });

  testWidgets('HomeScreen displays welcome section', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Check for welcome text in HomeScreen
    expect(find.text('Welcome to Lyric Listener!'), findsOneWidget);
    expect(find.text('A purr-fectly synced lyric experience for your favorite tunes!'), findsOneWidget);
  });

  testWidgets('Theme toggle button works', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Find and tap theme toggle button in AppBar
    final themeButton = find.byIcon(Icons.dark_mode_rounded);
    expect(themeButton, findsOneWidget);

    // First tap: System (Light) -> Light (no change)
    await tester.tap(themeButton);
    await tester.pumpAndSettle();

    // Second tap: Light -> Dark (icon changes to light_mode_rounded)
    await tester.tap(themeButton);
    await tester.pumpAndSettle();

    // Verify theme change (icon should switch to light mode)
    expect(find.byIcon(Icons.light_mode_rounded), findsOneWidget);
  });

  testWidgets('Expressive theme shapes are applied', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));

    final materialApp = tester.widget<MaterialApp>(find.byType(MaterialApp));
    final theme = materialApp.theme;
    final cardShape = theme?.cardTheme.shape as RoundedRectangleBorder?;
    final cardRadius = cardShape?.borderRadius as BorderRadius?;
    final filledButtonShape =
        theme?.filledButtonTheme.style?.shape?.resolve({});

    // Updated expectations for Material 3 Expressive (28 radius)
    expect(cardRadius?.topLeft.x, 28);
    expect(filledButtonShape, isA<RoundedRectangleBorder>());
    expect(
      (filledButtonShape as RoundedRectangleBorder).borderRadius,
      BorderRadius.circular(28),
    );
  });

  testWidgets('Permission status icons display correctly', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Since we mocked permissions to true, we expect check circles
    expect(find.byIcon(Icons.check_circle_rounded), findsWidgets);
    // We mocked battery optimization to false (default/optional), so it might show info or error depending on implementation.
    expect(find.byIcon(Icons.info_outline_rounded), findsOneWidget);
  });

  testWidgets('Service control buttons are present', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Mocked 'isLyricServiceRunning' to false.
    // Expect 'Launch Lyric Service'
    expect(find.text('Launch Lyric Service'), findsOneWidget);
    // 'Stop Lyric Service' should NOT be present
    expect(find.text('Stop Lyric Service'), findsNothing);
  });

  testWidgets('Settings tab is accessible', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Find and tap Settings tab in NavigationBar
    // Using icon to be more specific as "Settings" might appear in title if we were already there (but we start at Home)
    final settingsTab = find.byIcon(Icons.settings_outlined);
    expect(settingsTab, findsOneWidget);

    await tester.tap(settingsTab);
    await tester.pumpAndSettle();

    // Check for settings content
    expect(find.text('Theme Color'), findsOneWidget);
    expect(find.text('Dynamic lyrics window colours'), findsOneWidget);
  });

  testWidgets('Help tab is accessible', (WidgetTester tester) async {
    registerMock(tester);
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Find and tap Help tab in NavigationBar
    final helpTab = find.byIcon(Icons.help_outline_rounded);
    expect(helpTab, findsOneWidget);

    await tester.tap(helpTab);
    await tester.pumpAndSettle();

    // Check for FAQ items.
    expect(find.textContaining('Lyrics popup is not shown'), findsOneWidget);
  });

  testWidgets('Refresh subtitle appears immediately', (WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    addTearDown(tester.view.resetPhysicalSize);

    final refreshKey = GlobalKey<expressive_refresh.ExpressiveRefreshIndicatorState>();
    final completer = Completer<void>();

    await tester.pumpWidget(
      MaterialApp(
        home: expressive_refresh.ExpressiveRefreshIndicator.contained(
          key: refreshKey,
          onRefresh: () => completer.future,
          statusText: 'Inactive',
          subtitleText: 'Launch service below to enjoy synced lyrics!',
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            children: const [SizedBox(height: 400)],
          ),
        ),
      ),
    );

    refreshKey.currentState!.show();
    await tester.pump(const Duration(seconds: 1));

    final subtitleFinder = find.text('Launch service below to enjoy synced lyrics!');
    expect(subtitleFinder, findsOneWidget);

    final opacityWidget = tester.widget<AnimatedOpacity>(
      find.ancestor(of: subtitleFinder, matching: find.byType(AnimatedOpacity)),
    );
    expect(opacityWidget.duration, Duration.zero);

    expect(
      find.descendant(
        of: find.byType(expressive_refresh.ExpressiveRefreshIndicator),
        matching: find.byType(FractionalTranslation),
      ),
      findsNothing,
    );

    completer.complete();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
