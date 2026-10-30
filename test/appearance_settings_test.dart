import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyricslistener/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  const channel = MethodChannel('dev.optimus.lyricslistener/permissions');

  Future<void> openSettings(
    WidgetTester tester, {
    Size size = const Size(800, 3000),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => call.method == 'getAndroidVersion' ? 33 : false,
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(
      MyApp(
        initialSeedColor: const Color(0xFF6750A4),
        initialMaterialYouThemingEnabled: true,
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.settings_outlined));
    await tester.pumpAndSettle();
  }

  testWidgets('static colours follow their toggle before the blur setting', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    await openSettings(tester);
    expect(find.text('Appearance - App'), findsOneWidget);
    expect(find.text('Appearance - Lyrics Window'), findsOneWidget);
    expect(find.text('Static lyrics window colours'), findsNothing);
    await tester.tap(find.text('Dynamic lyrics window colours'));
    await tester.pumpAndSettle();
    final toggle = tester.getRect(find.text('Dynamic lyrics window colours'));
    final colours = tester.getRect(find.text('Static lyrics window colours'));
    final highlight = tester.getRect(find.text('Highlight'));
    final blur = tester.getRect(find.text('Blur effect on lyrics window'));
    expect(colours.top, greaterThan(toggle.bottom));
    expect(blur.top, greaterThan(highlight.bottom));
    expect(
      (await SharedPreferences.getInstance()).getBool(
        'lyrics_window_dynamic_colors',
      ),
      isFalse,
    );
  });

  testWidgets('transparency defaults to zero, saves and survives reopening', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    await openSettings(tester);
    final slider = find.byType(Slider);
    expect(tester.widget<Slider>(slider).value, 0);
    await tester.ensureVisible(slider);
    await tester.tapAt(tester.getCenter(slider));
    await tester.pumpAndSettle();
    final saved = (await SharedPreferences.getInstance()).getInt(
      'lyrics_window_transparency',
    );
    expect(saved, inInclusiveRange(40, 50));
    await tester.pumpWidget(const SizedBox.shrink());
    await openSettings(tester);
    expect(tester.widget<Slider>(slider).value, saved!.toDouble());
    expect(tester.widget<Slider>(slider).max, 90);
  });

  for (final stored in [-10, 100]) {
    testWidgets('out-of-range transparency $stored is clamped', (tester) async {
      SharedPreferences.setMockInitialValues({
        'lyrics_window_transparency': stored,
      });
      await openSettings(tester);
      expect(
        tester.widget<Slider>(find.byType(Slider)).value,
        stored.clamp(0, 90),
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('lyrics appearance fits a narrow screen with large text', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      'lyrics_window_dynamic_colors': false,
    });
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await openSettings(tester, size: const Size(320, 740));
    await tester.scrollUntilVisible(find.byType(Slider), 350);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byType(Slider).hitTestable(), findsOneWidget);
  });

  testWidgets(
    'appearance settings meet accessible target and label guidelines',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final semantics = tester.ensureSemantics();
      try {
        await openSettings(tester);
        await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
        await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
        await expectLater(tester, meetsGuideline(textContrastGuideline));
      } finally {
        semantics.dispose();
      }
    },
  );
}
