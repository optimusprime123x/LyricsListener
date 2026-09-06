import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyricslistener/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Layout regression tests for states that are awkward to reproduce on a
/// device: narrow screens, large font scale, a token regenerate that fails,
/// a full 500-line log buffer, and the Material You divider transition.
///
/// Pass `--dart-define=SCREENSHOT_DIR=/some/dir` together with
/// `--update-goldens` to also write PNG screenshots of each state.
void main() {
  const String screenshotDir = String.fromEnvironment('SCREENSHOT_DIR');
  const MethodChannel methodChannel = MethodChannel(
    'dev.optimus.lyricslistener/permissions',
  );
  const EventChannel eventChannel = EventChannel(
    'dev.optimus.lyricslistener/debugLogs',
  );

  int tokenFetchSeq = 0;
  String? tokenFetchError;
  String? tokenPreview = 'abcd…wxyz';

  // Real fonts from the Flutter SDK cache so widths match a device. The
  // default test font draws every glyph as a wide box and overstates overflow.
  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final String flutterRoot =
        Platform.environment['FLUTTER_ROOT'] ??
        File(
          Platform.resolvedExecutable,
        ).parent.parent.parent.parent.parent.path;
    final Directory fonts = Directory(
      '$flutterRoot/bin/cache/artifacts/material_fonts',
    );
    if (!fonts.existsSync()) return;
    Future<void> load(String family, List<String> files) async {
      final FontLoader loader = FontLoader(family);
      for (final String name in files) {
        final File file = File('${fonts.path}/$name');
        if (!file.existsSync()) return;
        loader.addFont(
          file.readAsBytes().then((bytes) => ByteData.sublistView(bytes)),
        );
      }
      await loader.load();
    }

    await load('Roboto', [
      'Roboto-Regular.ttf',
      'Roboto-Medium.ttf',
      'Roboto-Bold.ttf',
    ]);
    await load('MaterialIcons', ['MaterialIcons-Regular.otf']);
    // The app's theme uses google_fonts, which names families per weight
    // (e.g. Manrope_regular, Manrope_700). Stand in with Roboto so widths are
    // realistic instead of the box-glyph test font.
    for (final String family in ['Manrope', 'RobotoFlex']) {
      await load('${family}_regular', ['Roboto-Regular.ttf']);
      for (final String weight in ['500', '600']) {
        await load('${family}_$weight', ['Roboto-Medium.ttf']);
      }
      for (final String weight in ['700', '800', '900']) {
        await load('${family}_$weight', ['Roboto-Bold.ttf']);
      }
    }
  });

  // Registered once and never removed: a DebugScreen from one test is disposed
  // by the next test's pumpWidget, and its stream cancel must still be handled.
  setUpAll(() {
    final TestDefaultBinaryMessenger messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(methodChannel, (MethodCall call) async {
      switch (call.method) {
        case 'getAndroidVersion':
          return 33;
        case 'isNotificationAccessGranted':
        case 'canDrawOverlays':
        case 'isPostNotificationsGranted':
          return true;
        case 'isIgnoringBatteryOptimizations':
        case 'isLyricServiceRunning':
          return false;
        case 'getCachedLyricsList':
          return <Map<String, dynamic>>[];
        case 'getMusixmatchTokenAvailable':
          return tokenPreview != null;
        case 'getMusixmatchTokenPreview':
          return tokenPreview;
        case 'getMusixmatchTokenFetchResult':
          return <String, Object?>{
            'seq': tokenFetchSeq,
            'error': tokenFetchError,
          };
        case 'regenerateMusixmatchToken':
        case 'startDebugActiveMediaNotification':
          return null;
        case 'saveDebugLog':
          return '/storage/emulated/0/Android/data/dev.optimus.lyricslistener/'
              'files/debug_logs/lyric-listener-debug-20260906-120000.txt';
        default:
          return null;
      }
    });
    messenger.setMockMethodCallHandler(
      MethodChannel(eventChannel.name),
      (MethodCall call) async => null,
    );
  });

  Future<void> screenshot(WidgetTester tester, String name) async {
    if (screenshotDir.isEmpty) return;
    await expectLater(
      find.byType(MaterialApp),
      matchesGoldenFile('$screenshotDir/$name.png'),
    );
  }

  final List<FlutterErrorDetails> layoutErrors = [];

  /// Runs [body] while collecting every framework error (overflows included)
  /// instead of only the first one, then restores the test binding's handler.
  Future<void> capturingErrors(Future<void> Function() body) async {
    layoutErrors.clear();
    final FlutterExceptionHandler? original = FlutterError.onError;
    FlutterError.onError = (FlutterErrorDetails details) {
      // google_fonts tries to download the app's fonts; there is no network
      // in the test sandbox and that failure is not a layout problem.
      if (details.exceptionAsString().contains('Failed to load font')) return;
      layoutErrors.add(details);
    };
    try {
      await body();
    } finally {
      FlutterError.onError = original;
    }
  }

  void expectNoLayoutErrors(String state) {
    final List<String> summaries = [
      for (final FlutterErrorDetails details in layoutErrors)
        () {
          final List<String> lines = details.toString().split('\n');
          final int marker = lines.indexWhere(
            (line) => line.contains('error-causing widget'),
          );
          final String widget = marker >= 0
              ? lines
                    .skip(marker + 1)
                    .take(3)
                    .map((line) => line.trim())
                    .join(' ')
              : '(unknown widget)';
          return '${details.exceptionAsString()} | $widget';
        }(),
    ];
    layoutErrors.clear();
    expect(
      summaries,
      isEmpty,
      reason: 'Layout errors in state "$state":\n${summaries.join('\n')}',
    );
  }

  void resetState() {
    tokenFetchSeq = 0;
    tokenFetchError = null;
    tokenPreview = 'abcd…wxyz';
    SharedPreferences.setMockInitialValues({});
  }

  void setScreen(WidgetTester tester, Size logicalSize) {
    tester.view.physicalSize = logicalSize;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  Widget wrap(Widget home, {double textScale = 1.0}) {
    return MaterialApp(
      theme: ThemeData(colorSchemeSeed: const Color(0xFF6750A4)),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(
          context,
        ).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: home,
    );
  }

  void sendLog(String line) {
    const StandardMethodCodec codec = StandardMethodCodec();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          eventChannel.name,
          codec.encodeSuccessEnvelope(line),
          (ByteData? data) {},
        );
  }

  String logcat(String level, String message, {int second = 0}) {
    final String ss = second.toString().padLeft(2, '0');
    return '09-06 12:34:$ss.123 $level/LyricService( 4242): $message';
  }

  final List<String> sampleLines = [
    '--------- beginning of main',
    logcat(
      'I',
      'onStartCommand (Instance: 1234) with action: '
          'dev.optimus.lyricslistener.ACTION_DEBUG_ACTIVE_NOTIFICATION, '
          'flags: 0, startId: 7. isServiceManuallyStarted: true, '
          'ServiceJob Active: true',
    ),
    logcat(
      'D',
      'scheduleListenerHealthCheck: next check in 15000ms',
      second: 1,
    ),
    logcat('D', 'Found 3 active notifications.', second: 1),
    logcat(
      'D',
      'Processing most recent media notification from com.spotify.music '
          '(postTime: 1725600000000, when: 1725600000000)',
      second: 1,
    ),
    logcat('W', 'onPlaybackStateChanged: Ignoring.', second: 2),
    logcat(
      'I',
      "New song detected (Title: 'Blinding Lights', Artist: 'The Weeknd', "
          'Token: android.media.session.MediaSession\$Token@1a2b3c)',
      second: 2,
    ),
    logcat(
      'D',
      "Fetching lyrics for 'Blinding Lights' by 'The Weeknd' (Token: "
          'android.media.session.MediaSession\$Token@1a2b3c, '
          'Media Duration: 200040)',
      second: 2,
    ),
    logcat(
      'D',
      'Fetching from Musixmatch: https://apic.musixmatch.com/ws/1.1/'
          'macro.subtitles.get?usertoken=abcd…wxyz&q_track=Blinding+Lights'
          '&q_artist=The+Weeknd&app_id=mac-ios-v2.0&subtitle_format=json'
          '&selected_language=en&part=subtitle_translated',
      second: 3,
    ),
    logcat(
      'D',
      'Rebinding lyrics window references to an attached overlay for '
          'showLyricsWindow.',
      second: 3,
    ),
    logcat(
      'D',
      "Found 42 synced lines from Musixmatch for 'Blinding Lights'. "
          'Translation available: false',
      second: 4,
    ),
    logcat(
      'W',
      'updateLyricsWindowColors: Low contrast (1.8) between title and '
          'background. Adjusting.',
      second: 4,
    ),
    logcat(
      'W',
      'requestNotificationListenerRebind: Notification access not currently '
          'granted. Reason: health check',
      second: 5,
    ),
    logcat(
      'E',
      'Failed to get Musixmatch user token. Keeping existing token: true',
      second: 6,
    ),
    logcat('D', "Cache hit for 'Save Your Tears' by 'The Weeknd'", second: 7),
    logcat('D', "No lyrics found on Musixmatch for 'Obscure Track'", second: 8),
    logcat(
      'I',
      'Successfully acquired Musixmatch user token (abcd…wxyz). '
          'Replaced existing: true',
      second: 9,
    ),
  ];

  Future<void> pumpDebugScreen(
    WidgetTester tester, {
    required Size size,
    double textScale = 1.0,
  }) async {
    resetState();
    setScreen(tester, size);
    await tester.pumpWidget(wrap(const DebugScreen(), textScale: textScale));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    for (final line in sampleLines) {
      sendLog(line);
    }
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));
  }

  /// Taps Regenerate, then makes the mocked service report [error] (null for
  /// success) with [newPreview] as the resulting masked token.
  Future<void> regenerate(
    WidgetTester tester, {
    required String? error,
    String? newPreview,
  }) async {
    await tester.tap(find.text('Regenerate'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    tokenFetchSeq += 1;
    tokenFetchError = error;
    if (newPreview != null) tokenPreview = newPreview;
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
  }

  Finder richText(String fragment) =>
      find.textContaining(fragment, findRichText: true);

  testWidgets('classifier shows important lines and hides noise', (
    tester,
  ) async {
    await capturingErrors(() async {
      await pumpDebugScreen(tester, size: const Size(412, 915));
      expectNoLayoutErrors('initial');

      expect(richText('New song detected'), findsOneWidget);
      expect(richText('Found 42 synced lines'), findsOneWidget);
      expect(
        richText('Notification access not currently granted'),
        findsOneWidget,
      );
      expect(richText('Cache hit'), findsOneWidget);
      expect(richText('Failed to get Musixmatch user token'), findsOneWidget);
      // Noise is hidden until "Show all" is on.
      expect(richText('onPlaybackStateChanged'), findsNothing);
      expect(richText('scheduleListenerHealthCheck'), findsNothing);
      expect(richText('Low contrast'), findsNothing);
      expect(richText('beginning of main'), findsNothing);
      expect(find.text('Show all (${sampleLines.length})'), findsOneWidget);
      expect(
        find.text('Musixmatch token: Available (abcd…wxyz)'),
        findsOneWidget,
      );
      await screenshot(tester, 'debug_412_important');

      await tester.tap(find.byType(FilterChip));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expectNoLayoutErrors('show all');
      expect(richText('onPlaybackStateChanged'), findsOneWidget);
      await screenshot(tester, 'debug_412_show_all');
    });
  });

  testWidgets('regenerate reports failure with a snackbar', (tester) async {
    await capturingErrors(() async {
      await pumpDebugScreen(tester, size: const Size(412, 915));

      await tester.tap(find.text('Regenerate'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expectNoLayoutErrors('regenerating');
      expect(find.text('Musixmatch token: Regenerating...'), findsOneWidget);
      await screenshot(tester, 'debug_412_regenerating');

      tokenFetchSeq = 1;
      tokenFetchError =
          'Connect timeout has expired '
          '[url=https://apic.musixmatch.com/ws/1.1/token.get, '
          'connect_timeout=unknown ms]';
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expectNoLayoutErrors('regenerate failed');
      expect(find.textContaining('Token regeneration failed'), findsOneWidget);
      expect(find.textContaining('Last token fetch failed'), findsOneWidget);
      expect(
        find.text('Musixmatch token: Available (abcd…wxyz)'),
        findsOneWidget,
      );
      await screenshot(tester, 'debug_412_regenerate_failed');
    });
  });

  testWidgets('regenerate reports success and new preview', (tester) async {
    await capturingErrors(() async {
      await pumpDebugScreen(tester, size: const Size(412, 915));
      await regenerate(tester, error: null, newPreview: 'zyxw…dcba');
      expectNoLayoutErrors('regenerate succeeded');
      expect(
        find.textContaining('New Musixmatch token acquired (zyxw…dcba)'),
        findsOneWidget,
      );
      expect(
        find.text('Musixmatch token: Available (zyxw…dcba)'),
        findsOneWidget,
      );
      expect(find.textContaining('Last token fetch failed'), findsNothing);
    });
  });

  testWidgets('full 500-line buffer with show all does not overflow', (
    tester,
  ) async {
    await capturingErrors(() async {
      await pumpDebugScreen(tester, size: const Size(412, 915));
      for (int i = 0; i < 600; i++) {
        sendLog(
          logcat(
            'D',
            'Verbose filler line $i with a fairly long payload to exercise '
                'wrapping in the monospace log view.',
            second: i % 60,
          ),
        );
      }
      await tester.pump();
      await tester.pump();
      // 617 lines were sent; the ring buffer keeps the newest 500, which
      // evicts all of the important sample lines, so only the chip shows it.
      expect(
        find.descendant(
          of: find.byType(FilterChip),
          matching: find.text('Show all (500)'),
        ),
        findsOneWidget,
      );
      await tester.tap(find.byType(FilterChip));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expectNoLayoutErrors('show all with 500 lines');
      await screenshot(tester, 'debug_412_full_buffer');
    });
  });

  testWidgets('selection survives newly appended log lines', (tester) async {
    await capturingErrors(() async {
      await pumpDebugScreen(tester, size: const Size(412, 915));
      final Finder selectable = find.byType(SelectableText);
      expect(selectable, findsOneWidget);

      // Long-press selects the word under the finger; aim at the first line.
      await tester.longPressAt(
        tester.getTopLeft(selectable) + const Offset(20, 8),
      );
      await tester.pumpAndSettle();
      EditableTextState state = tester.state(find.byType(EditableText));
      final TextSelection before = state.textEditingValue.selection;
      expect(
        before.isCollapsed,
        isFalse,
        reason: 'long-press should select a word',
      );

      sendLog(
        logcat(
          'I',
          "New song detected (Title: 'Appended', Artist: 'Later')",
          second: 30,
        ),
      );
      await tester.pump();
      await tester.pump();
      state = tester.state(find.byType(EditableText));
      expect(
        state.textEditingValue.selection,
        before,
        reason: 'selection must survive appended lines',
      );
      // Updates are held back while a selection is active.
      expect(richText('Appended'), findsNothing);
      expect(
        find.textContaining('paused while text is selected'),
        findsOneWidget,
      );

      // A tap collapses the selection and resumes live updates.
      await tester.tapAt(tester.getTopLeft(selectable) + const Offset(20, 8));
      await tester.pumpAndSettle();
      expect(richText('Appended'), findsOneWidget);
      expect(
        find.textContaining('paused while text is selected'),
        findsNothing,
      );
    });
  });

  testWidgets('save log shows the path', (tester) async {
    await capturingErrors(() async {
      await pumpDebugScreen(tester, size: const Size(412, 915));
      await tester.tap(find.byTooltip('Save full log to file'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expectNoLayoutErrors('save snackbar');
      expect(
        find.textContaining('Log saved to /storage/emulated/0'),
        findsOneWidget,
      );
      await screenshot(tester, 'debug_412_saved');
    });
  });

  for (final (Size size, double scale, String label) in [
    (const Size(320, 568), 1.0, 'narrow_320'),
    (const Size(360, 640), 1.0, 'small_360'),
    (const Size(412, 915), 1.3, 'scale_1_3'),
    (const Size(412, 915), 2.0, 'scale_2_0'),
    (const Size(915, 412), 1.0, 'landscape'),
  ]) {
    testWidgets('debug screen layout at $label', (tester) async {
      await capturingErrors(() async {
        await pumpDebugScreen(tester, size: size, textScale: scale);
        expectNoLayoutErrors(label);
        await screenshot(tester, 'debug_$label');

        await regenerate(tester, error: 'Connect timeout has expired');
        expectNoLayoutErrors('$label regenerate failed');
        await screenshot(tester, 'debug_${label}_failed');
      });
    });
  }

  testWidgets('settings divider animates with the color picker', (
    tester,
  ) async {
    await capturingErrors(() async {
      resetState();
      setScreen(tester, const Size(412, 915));
      await tester.pumpWidget(
        const MyApp(
          initialSeedColor: Color(0xFF6750A4),
          initialMaterialYouThemingEnabled: false,
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.settings_outlined));
      await tester.pumpAndSettle();
      expectNoLayoutErrors('settings picker visible');
      expect(find.text('Theme Color'), findsOneWidget);
      await screenshot(tester, 'settings_picker_visible');

      final Finder materialYouSwitch = find.widgetWithText(
        SwitchListTile,
        'Material You theming',
      );
      expect(materialYouSwitch, findsOneWidget);
      await tester.tap(materialYouSwitch);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 120));
      expectNoLayoutErrors('settings mid transition');
      await screenshot(tester, 'settings_mid_transition');

      await tester.pumpAndSettle();
      expectNoLayoutErrors('settings picker hidden');
      expect(find.text('Theme Color'), findsNothing);
      await screenshot(tester, 'settings_picker_hidden');
    });
  });
}
