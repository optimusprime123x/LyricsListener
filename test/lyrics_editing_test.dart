import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyricslistener/lyrics_timestamp.dart';
import 'package:lyricslistener/pages/add_custom_lyrics_page.dart';
import 'package:lyricslistener/pages/edit_lyrics_page.dart';

void main() {
  const channel = MethodChannel('dev.optimus.lyricslistener/permissions');
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('formatting and parsing timestamps preserves every millisecond', () {
    for (final timestamp in [0, 1, 9, 999, 12345, 60000, 7234567]) {
      expect(parseLyricsTimestamp(formatLyricsTimestamp(timestamp)), timestamp);
    }
    expect(parseLyricsTimestamp('0:12'), 12000);
    expect(parseLyricsTimestamp('00:12.3'), 12300);
    expect(parseLyricsTimestamp('00:12:345'), 12345);
    for (final invalid in ['wrong', '-1:12', '00:60.000', '00:12.3456']) {
      expect(parseLyricsTimestamp(invalid), isNull);
    }
    expect(countLrcTimestamps('[ti:Song]\n［0：12］［0：24．5］ Hello'), 2);
  });

  Future<void> openEditor(
    WidgetTester tester,
    List<Map<String, Object>> lines,
    void Function(Map<dynamic, dynamic>) onSave,
  ) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'getCachedLyricsContent') return {'lines': lines};
      if (call.method == 'updateCachedLyrics') {
        onSave((call.arguments as Map)['data'] as Map);
        return true;
      }
      return null;
    });
    await tester.pumpWidget(
      const MaterialApp(
        home: EditLyricsPage(
          cacheKey: 'song-key',
          title: 'Song',
          artist: 'Artist',
          type: 'synced',
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('saving unchanged lyrics retains millisecond precision', (
    tester,
  ) async {
    Map<dynamic, dynamic>? saved;
    await openEditor(tester, [
      {'timestamp': 12345, 'text': 'Hello'},
    ], (data) => saved = data);
    expect(find.text('00:12.345'), findsOneWidget);
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(saved!['lines'], [
      {'timestamp': 12345, 'text': 'Hello'},
    ]);
  });

  testWidgets('invalid timestamps stay editable and never reach storage', (
    tester,
  ) async {
    var saves = 0;
    await openEditor(tester, [
      {'timestamp': 12345, 'text': 'Hello'},
    ], (_) => saves++);
    for (final invalid in ['wrong', '-1:12', '00:60.000']) {
      await tester.enterText(find.byType(TextField).first, invalid);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(saves, 0);
      expect(find.text('Invalid time'), findsOneWidget);
    }
    await tester.enterText(find.byType(TextField).first, '0:12.345');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(saves, 1);
  });

  testWidgets('changing timing saves the corresponding lines in time order', (
    tester,
  ) async {
    Map<dynamic, dynamic>? saved;
    await openEditor(tester, [
      {'timestamp': 12345, 'text': 'First'},
      {'timestamp': 23456, 'text': 'Second'},
    ], (data) => saved = data);
    await tester.enterText(find.byType(TextField).first, '00:30.789');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(saved!['lines'], [
      {'timestamp': 23456, 'text': 'Second'},
      {'timestamp': 30789, 'text': 'First'},
    ]);
  });

  Future<void> openCustomPage(WidgetTester tester) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: AddCustomLyricsPage()));
    await tester.enterText(find.byType(TextField).at(0), '夜に駆ける');
    await tester.enterText(find.byType(TextField).at(1), 'YOASOBI');
  }

  for (final lrc in [
    '[0:12] Hello',
    '[00:12] Hello',
    '[00:12.3] Hello',
    '[00:12:345] Hello',
    '［0：12．345］ Hello',
    '[ti:Song]\n[0:12][0:24.50] Hello',
  ]) {
    testWidgets('custom LRC is passed to the native validator: $lrc', (
      tester,
    ) async {
      Map<dynamic, dynamic>? submitted;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        if (call.method == 'addCustomLyrics') {
          submitted = call.arguments as Map;
          return {'status': 'saved', 'lineCount': 1};
        }
        return null;
      });
      await openCustomPage(tester);
      await tester.enterText(find.byType(TextField).at(2), lrc);
      await tester.ensureVisible(find.text('Save custom lyrics'));
      await tester.tap(find.text('Save custom lyrics'));
      await tester.pumpAndSettle();
      expect(submitted!['lrc'], lrc);
      expect(submitted!['title'], '夜に駆ける');
    });
  }

  testWidgets('a failed native save keeps the entered lyrics for retry', (
    tester,
  ) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      throw PlatformException(
        code: 'ERROR_ADD_CUSTOM_LYRICS',
        message: 'Could not save custom lyrics.',
      );
    });
    await openCustomPage(tester);
    await tester.enterText(find.byType(TextField).at(2), '[0:12] Hello');
    await tester.ensureVisible(find.text('Save custom lyrics'));
    await tester.tap(find.text('Save custom lyrics'));
    await tester.pumpAndSettle();
    expect(find.text('Error: Could not save custom lyrics.'), findsOneWidget);
    expect(find.text('[0:12] Hello'), findsOneWidget);
    expect(find.text('Save custom lyrics'), findsOneWidget);
  });
}
