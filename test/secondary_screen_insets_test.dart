import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyricslistener/main.dart';
import 'package:lyricslistener/pages/edit_lyrics_page.dart';
import 'package:lyricslistener/pages/manage_lyrics_page.dart';

void main() {
  const channel = MethodChannel('dev.optimus.lyricslistener/permissions');
  const logs = MethodChannel('dev.optimus.lyricslistener/debugLogs');
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(logs, (_) async => null);
    messenger.setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'getCachedLyricsList':
          return [
            {
              'cacheKey': 'song',
              'title': 'Song',
              'artist': 'Artist',
              'type': 'synced',
            },
          ];
        case 'getLyricsCacheSize':
          return 1024;
        case 'getCachedLyricsContent':
          return {
            'lyrics': 'Hello',
            'lines': [
              {'timestamp': 12345, 'text': 'Hello'},
            ],
          };
        case 'getMusixmatchTokenAvailable':
          return true;
        case 'getMusixmatchTokenPreview':
          return 'abcd';
        case 'getMusixmatchTokenFetchResult':
          return {'seq': 0};
        default:
          return null;
      }
    });
  });

  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(logs, null);
  });

  final screens = <String, Widget>{
    'manager': const ManageLyricsPage(),
    'synced editor': const EditLyricsPage(
      cacheKey: 'song',
      title: 'Song',
      artist: 'Artist',
      type: 'synced',
    ),
    'plain editor': const EditLyricsPage(
      cacheKey: 'song',
      title: 'Song',
      artist: 'Artist',
      type: 'plain',
    ),
    'debug': const DebugScreen(),
  };
  const sideInsets = {
    'left cutout': EdgeInsets.only(left: 48, bottom: 24),
    'right navigation bar': EdgeInsets.only(right: 48, bottom: 24),
  };

  for (final screen in screens.entries) {
    for (final inset in sideInsets.entries) {
      testWidgets('${screen.key} controls avoid the ${inset.key}', (
        tester,
      ) async {
        const size = Size(900, 500);
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(
          MaterialApp(
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(padding: inset.value, viewPadding: inset.value),
              child: child!,
            ),
            home: screen.value,
          ),
        );
        await tester.pumpAndSettle();

        final controls = screen.key == 'debug'
            ? find.byWidgetPredicate(
                (widget) => widget is ButtonStyleButton || widget is FilterChip,
              )
            : find.byType(TextField);
        expect(controls, findsWidgets);
        for (final control in controls.evaluate()) {
          final rect = tester.getRect(find.byWidget(control.widget));
          expect(
            rect.left,
            greaterThanOrEqualTo(inset.value.left),
            reason: 'Control overlaps the left system inset',
          );
          expect(
            rect.right,
            lessThanOrEqualTo(size.width - inset.value.right),
            reason: 'Control overlaps the right system inset',
          );
        }
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      });
    }
  }
}
