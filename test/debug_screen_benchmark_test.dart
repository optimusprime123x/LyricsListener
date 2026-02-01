import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyricslistener/main.dart';

void main() {
  const methodChannel = MethodChannel('dev.optimus.lyricslistener/permissions');
  const eventChannel = EventChannel('dev.optimus.lyricslistener/debugLogs');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (MethodCall methodCall) async {
      if (methodCall.method == 'startDebugActiveMediaNotification') {
        return null;
      }
      return null;
    });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(MethodChannel(eventChannel.name),
            (MethodCall methodCall) async {
      if (methodCall.method == 'listen') {
        return null; // Success
      } else if (methodCall.method == 'cancel') {
        return null;
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(MethodChannel(eventChannel.name), null);
  });

  void sendLog(String log) {
    const StandardMethodCodec codec = StandardMethodCodec();
    final data = codec.encodeSuccessEnvelope(log);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      eventChannel.name,
      data,
      (ByteData? data) {},
    );
  }

  testWidgets('DebugScreen performance benchmark', (WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: DebugScreen()));
    await tester.pump(); // Start _startDebugSession
    await tester.pump(const Duration(milliseconds: 100)); // Allow async completions

    // It should be streaming now.
    // Verify we are not in starting state
    expect(find.text('Starting...'), findsNothing);

    // Simulate logs
    final stopwatch = Stopwatch()..start();

    // Add 2000 logs. 1000 was fast, maybe too fast to measure significant diff?
    // Let's try 1000 first, and pump every time to force rebuild.
    for (int i = 0; i < 1000; i++) {
      sendLog('Log message $i: This is a sample log message to test rendering performance. It has some length to it to make layout work a bit.');
      await tester.pump();
    }

    stopwatch.stop();
    print('Time to render 1000 logs: ${stopwatch.elapsedMilliseconds} ms');
  });
}
