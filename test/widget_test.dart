// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:lyricslistener/main.dart';

void main() {
  testWidgets('MyApp initializes with seed color', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));

    // Verify app builds without errors and MaterialApp is present
    expect(find.byType(MaterialApp), findsOneWidget);
  });

  testWidgets('HomeScreen displays welcome section', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Check for welcome text in HomeScreen
    expect(find.text('Welcome to Lyric Listener!'), findsOneWidget);
    expect(find.text('A purr-fectly synced lyric experience for your favorite tunes!'), findsOneWidget);
  });

  testWidgets('Theme toggle button works', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Find and tap theme toggle button in AppBar
    final themeButton = find.byIcon(Icons.dark_mode_rounded);
    expect(themeButton, findsOneWidget);
    await tester.tap(themeButton);
    await tester.pump();

    // Verify theme change (icon should switch to light mode)
    expect(find.byIcon(Icons.light_mode_rounded), findsOneWidget);
  });

  testWidgets('Expressive theme shapes are applied', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));

    final materialApp = tester.widget<MaterialApp>(find.byType(MaterialApp));
    final theme = materialApp.theme;
    final cardShape = theme?.cardTheme.shape as RoundedRectangleBorder?;
    final cardRadius = cardShape?.borderRadius as BorderRadius?;
    final filledButtonShape =
        theme?.filledButtonTheme.style?.shape?.resolve({});

    expect(cardRadius?.topLeft.x, 24);
    expect(filledButtonShape, isA<RoundedRectangleBorder>());
    expect(
      (filledButtonShape as RoundedRectangleBorder).borderRadius,
      BorderRadius.circular(20),
    );
  });

  testWidgets('Permission status icons display correctly', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Check for permission status icons (assuming some are present based on state)
    expect(find.byIcon(Icons.check_circle_rounded), findsWidgets); // Granted permissions
    expect(find.byIcon(Icons.error_outline_rounded), findsWidgets); // Missing permissions
  });

  testWidgets('Service control buttons are present', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Check for service control elements
    expect(find.text('Launch Lyric Service'), findsOneWidget);
    expect(find.text('Stop Lyric Service'), findsOneWidget);
  });

  testWidgets('Customization section expands', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Find and tap customization expansion tile
    final customizationTile = find.text('Customization & Appearance');
    expect(customizationTile, findsOneWidget);
    await tester.tap(customizationTile);
    await tester.pump();

    // Check for expanded content
    expect(find.text('Theme Color'), findsOneWidget);
    expect(find.text('Dynamic lyrics window colours'), findsOneWidget);
  });

  testWidgets('Help section is accessible', (WidgetTester tester) async {
    const initialSeedColor = Color(0xFF6750A4);
    await tester.pumpWidget(MyApp(initialSeedColor: initialSeedColor));
    await tester.pumpAndSettle(); // Wait for async loading to settle

    // Find and tap help expansion tile
    final helpTile = find.text('Help and Support');
    expect(helpTile, findsOneWidget);
    await tester.tap(helpTile);
    await tester.pump();

    // Check for FAQ items
    expect(find.text('Lyrics popup is not shown'), findsOneWidget);
  });
}
