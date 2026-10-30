import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyricslistener/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  const channel = MethodChannel('dev.optimus.lyricslistener/permissions');

  Future<void> openSettings(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 4000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'getAndroidVersion') return 33;
      return false;
    });
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
    await tester.ensureVisible(find.text('Minimise to Bubble'));
  }

  Finder bubbleSwitch() => find.ancestor(
    of: find.text('Minimise to Bubble'),
    matching: find.byType(SwitchListTile),
  );

  testWidgets('Minimise to Bubble defaults on and saves the user choice', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    await openSettings(tester);
    expect(tester.widget<SwitchListTile>(bubbleSwitch()).value, isTrue);
    expect(
      find.text(
        'Collapse the floating lyrics window to a bubble instead of entirely hiding it, for easy re-opening',
      ),
      findsOneWidget,
    );
    await tester.tap(find.text('Minimise to Bubble'));
    await tester.pumpAndSettle();
    expect(tester.widget<SwitchListTile>(bubbleSwitch()).value, isFalse);
    expect(
      (await SharedPreferences.getInstance()).getBool('minimise_to_bubble'),
      isFalse,
    );
    await tester.tap(find.text('Minimise to Bubble'));
    await tester.pumpAndSettle();
    expect(
      (await SharedPreferences.getInstance()).getBool('minimise_to_bubble'),
      isTrue,
    );
  });

  testWidgets('Minimise to Bubble respects a saved disabled preference', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({'minimise_to_bubble': false});
    await openSettings(tester);
    expect(tester.widget<SwitchListTile>(bubbleSwitch()).value, isFalse);
  });

  for (final setting in [
    (title: 'Remember lyrics window position', key: 'remember_window_position'),
    (title: 'Hide lyrics window on pause', key: 'hide_lyrics_window_on_pause'),
  ]) {
    Finder settingSwitch() => find.ancestor(
      of: find.text(setting.title),
      matching: find.byType(SwitchListTile),
    );

    testWidgets('${setting.title} defaults on and saves the user choice', (
      tester,
    ) async {
      SharedPreferences.setMockInitialValues({});
      await openSettings(tester);
      expect(tester.widget<SwitchListTile>(settingSwitch()).value, isTrue);
      await tester.tap(find.text(setting.title));
      await tester.pumpAndSettle();
      expect(tester.widget<SwitchListTile>(settingSwitch()).value, isFalse);
      expect(
        (await SharedPreferences.getInstance()).getBool(setting.key),
        isFalse,
      );
      await tester.tap(find.text(setting.title));
      await tester.pumpAndSettle();
      expect(tester.widget<SwitchListTile>(settingSwitch()).value, isTrue);
      expect(
        (await SharedPreferences.getInstance()).getBool(setting.key),
        isTrue,
      );
    });

    testWidgets('${setting.title} respects a saved disabled preference', (
      tester,
    ) async {
      SharedPreferences.setMockInitialValues({setting.key: false});
      await openSettings(tester);
      expect(tester.widget<SwitchListTile>(settingSwitch()).value, isFalse);
    });
  }
}
