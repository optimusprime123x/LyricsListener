import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyricslistener/main.dart';
import 'package:lyricslistener/pages/add_custom_lyrics_page.dart';
import 'package:lyricslistener/pages/lyrics_sources_page.dart';
import 'package:lyricslistener/pages/manage_lyrics_page.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_platform_interface.dart';

class _ControlledStore extends InMemorySharedPreferencesStore {
  _ControlledStore() : super.empty();
  bool failWrites = false;
  bool failReads = false;
  Completer<bool>? writeResult;

  @override
  Future<Map<String, Object>> getAll() async {
    if (failReads) throw PlatformException(code: 'storage_unavailable');
    return super.getAll();
  }

  @override
  Future<bool> setValue(String valueType, String key, Object value) async {
    if (failWrites) return false;
    if (writeResult != null && !await writeResult!.future) return false;
    return super.setValue(valueType, key, value);
  }
}

void main() {
  const channel = MethodChannel('dev.optimus.lyricslistener/permissions');
  const keys = [
    'lyrics_source_lrclib_enabled',
    'lyrics_source_musixmatch_enabled',
    'lyrics_source_others_enabled',
  ];

  setUp(() => SharedPreferences.setMockInitialValues({}));

  Future<void> openPage(
    WidgetTester tester, {
    Size size = const Size(800, 2800),
    double textScale = 1,
    Brightness brightness = Brightness.light,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'getCachedLyricsList') return [];
      if (call.method == 'getLyricsCacheSize') return 0;
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
      MaterialApp(
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF6750A4),
            brightness: brightness,
          ),
        ),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => const LyricsSourcesPage(),
                  ),
                ),
                child: const Text('Open sources'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open sources'));
    await tester.pumpAndSettle();
  }

  SwitchListTile sourceTile(WidgetTester tester, String key) =>
      tester.widget<SwitchListTile>(find.byKey(ValueKey(key)));

  testWidgets(
    'cache stays enabled; online choices auto-save and survive reopening',
    (tester) async {
      await openPage(tester);
      final cache = sourceTile(tester, 'lyrics_source_cache');
      expect(cache.value, isTrue);
      expect(cache.onChanged, isNull);
      expect(find.text('Save changes'), findsNothing);
      for (final key in keys) {
        expect(sourceTile(tester, key).value, isTrue);
        await tester.tap(find.byKey(ValueKey(key)));
        await tester.pumpAndSettle();
        expect((await SharedPreferences.getInstance()).getBool(key), isFalse);
      }
      expect(
        find.text(
          'Only cached and custom lyrics will be available while all online sources are off.',
        ),
        findsOneWidget,
      );
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(LyricsSourcesPage), findsNothing);
      await tester.tap(find.text('Open sources'));
      await tester.pumpAndSettle();
      for (final key in keys) {
        expect(sourceTile(tester, key).value, isFalse);
      }
      await tester.tap(
        find.byKey(const ValueKey('lyrics_source_lrclib_enabled')),
      );
      await tester.pumpAndSettle();
      expect(
        (await SharedPreferences.getInstance()).getBool(keys.first),
        isTrue,
      );
      expect(sourceTile(tester, keys[1]).value, isFalse);
    },
  );

  testWidgets('Back also preserves auto-saved changes', (tester) async {
    await openPage(tester);
    await tester.tap(find.byKey(ValueKey(keys.last)));
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('Open sources'));
    await tester.pumpAndSettle();
    expect(sourceTile(tester, keys.last).value, isFalse);
  });

  testWidgets(
    'pending writes prevent repeated toggles and complete after leaving',
    (tester) async {
      final store = _ControlledStore()..writeResult = Completer<bool>();
      SharedPreferencesStorePlatform.instance = store;
      await openPage(tester);
      await tester.tap(find.byKey(ValueKey(keys.first)));
      await tester.pump();
      expect(sourceTile(tester, keys.first).onChanged, isNull);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(LyricsSourcesPage), findsNothing);
      store.writeResult!.complete(true);
      await tester.pumpAndSettle();
      expect(
        (await SharedPreferences.getInstance()).getBool(keys.first),
        isFalse,
      );
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Open sources'));
      await tester.pumpAndSettle();
      expect(sourceTile(tester, keys.first).value, isFalse);
    },
  );

  testWidgets(
    'failed writes restore the last confirmed choice and can be retried',
    (tester) async {
      final store = _ControlledStore()..failWrites = true;
      SharedPreferencesStorePlatform.instance = store;
      await openPage(tester);
      await tester.tap(find.byKey(ValueKey(keys.first)));
      await tester.pumpAndSettle();
      expect(sourceTile(tester, keys.first).value, isTrue);
      expect(
        find.text('Could not save this source. Try again.'),
        findsOneWidget,
      );
      expect(
        (await SharedPreferences.getInstance()).getBool(keys.first),
        isNot(false),
      );
      store.failWrites = false;
      await tester.tap(find.byKey(ValueKey(keys.first)));
      await tester.pumpAndSettle();
      expect(sourceTile(tester, keys.first).value, isFalse);
    },
  );

  testWidgets('failed preference loads keep toggles disabled and offer Retry', (
    tester,
  ) async {
    final store = _ControlledStore()..failReads = true;
    SharedPreferencesStorePlatform.instance = store;
    await openPage(tester);
    expect(
      find.text('Could not load your source preferences.'),
      findsOneWidget,
    );
    expect(sourceTile(tester, keys.first).onChanged, isNull);
    store.failReads = false;
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();
    expect(sourceTile(tester, keys.first).onChanged, isNotNull);
  });

  testWidgets('guidance links open Manage Lyrics and Add custom lyrics', (
    tester,
  ) async {
    await openPage(tester);
    await tester.tap(find.text('What is this page for?'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Manage Lyrics'));
    await tester.pumpAndSettle();
    expect(find.byType(ManageLyricsPage), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('Custom Lyrics'));
    await tester.pumpAndSettle();
    expect(find.byType(AddCustomLyricsPage), findsOneWidget);
  });

  for (final size in [
    const Size(320, 740),
    const Size(840, 700),
    const Size(1440, 900),
  ]) {
    for (final brightness in Brightness.values) {
      testWidgets('responsive layout at $size, $brightness, large text', (
        tester,
      ) async {
        await openPage(
          tester,
          size: size,
          textScale: 2,
          brightness: brightness,
        );
        await tester.ensureVisible(find.text('What is this page for?'));
        await tester.tap(find.text('What is this page for?'));
        await tester.pumpAndSettle();
        for (var i = 0; i < 12; i++) {
          await tester.drag(
            find.byType(CustomScrollView).first,
            const Offset(0, -500),
          );
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
        }
      });
    }
  }

  testWidgets(
    'source controls and links have accessible labels, targets and contrast',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        await openPage(tester);
        await tester.tap(find.text('What is this page for?'));
        await tester.pumpAndSettle();
        await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
        await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
        await expectLater(tester, meetsGuideline(textContrastGuideline));
      } finally {
        semantics.dispose();
      }
    },
  );

  testWidgets('Settings Lyrics entry opens the source screen', (tester) async {
    await openPage(tester);
    await tester.pumpWidget(
      const MyApp(
        initialSeedColor: Color(0xFF6750A4),
        initialMaterialYouThemingEnabled: true,
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.settings_outlined));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Lyrics Sources'), 600);
    await tester.tap(find.text('Lyrics Sources'));
    await tester.pumpAndSettle();
    expect(find.byType(LyricsSourcesPage), findsOneWidget);
  });

  testWidgets('FAQ links open Lyrics Sources and Add custom lyrics', (
    tester,
  ) async {
    await openPage(tester);
    await tester.pumpWidget(
      const MyApp(
        initialSeedColor: Color(0xFF6750A4),
        initialMaterialYouThemingEnabled: true,
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.help_outline_rounded));
    await tester.pumpAndSettle();

    Future<void> tapLink(String text) async {
      final paragraphFinder = find.byWidgetPredicate(
        (widget) =>
            widget is RichText &&
            widget.text.toPlainText().contains('You can try a different song'),
      );
      await tester.ensureVisible(paragraphFinder);
      final paragraph = tester.renderObject<RenderParagraph>(paragraphFinder);
      final content = paragraph.text.toPlainText();
      final start = content.lastIndexOf(text);
      expect(start, greaterThanOrEqualTo(0));
      final box = paragraph
          .getBoxesForSelection(
            TextSelection(baseOffset: start, extentOffset: start + text.length),
          )
          .first;
      await tester.tapAt(paragraph.localToGlobal(box.toRect().center));
      await tester.pumpAndSettle();
    }

    await tapLink('source');
    expect(find.byType(LyricsSourcesPage), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tapLink('Custom Lyrics page');
    expect(find.byType(AddCustomLyricsPage), findsOneWidget);
  });
}
