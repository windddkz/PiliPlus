import 'package:PiliPlus/plugin/pl_player/official_player.dart';
import 'package:PiliPlus/plugin/pl_player/widgets/official_video.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const commands = MethodChannel('piliplus/official_player');
  const events = MethodChannel('piliplus/official_player/events');
  late OfficialPlayer player;
  final calls = <MethodCall>[];
  setUp(() async {
    calls.clear();
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    messenger
      ..setMockMethodCallHandler(
        commands,
        (call) async {
          calls.add(call);
          return switch (call.method) {
            'availability' => {'available': true},
            'create' => {'session': 7},
            _ => null,
          };
        },
      )
      ..setMockMethodCallHandler(events, (_) async => null)
      ..setMockMethodCallHandler(
        SystemChannels.platform_views,
        (call) async => null,
      );
    player = await OfficialPlayer.create((reason) => throw StateError(reason));
    player.state.width = 400;
    player.state.height = 200;
  });
  tearDown(() async {
    await player.dispose();
    debugDefaultTargetPlatformOverride = null;
    messenger
      ..setMockMethodCallHandler(commands, null)
      ..setMockMethodCallHandler(events, null)
      ..setMockMethodCallHandler(SystemChannels.platform_views, null);
  });
  testWidgets(
    'native surface fit uses display geometry and preserves its identity',
    (tester) async {
      await messenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeSuccessEnvelope({
          'session': 7,
          'event': 'prepared',
        }),
        (_) {},
      );
      await tester.pump();
      Future<Rect> layout(
        BoxFit fit, {
        double? ratio,
        Alignment alignment = Alignment.center,
      }) async {
        await tester.pumpWidget(
          Directionality(
            textDirection: TextDirection.ltr,
            child: MediaQuery(
              data: const MediaQueryData(devicePixelRatio: 2),
              child: Center(
                child: SizedBox(
                  width: 300,
                  height: 300,
                  child: OfficialVideo(
                    player: player,
                    fit: fit,
                    aspectRatio: ratio,
                    alignment: alignment,
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pump();
        expect(tester.takeException(), isNull);
        return tester.getRect(find.byType(PlatformViewLink));
      }

      final contain = await layout(BoxFit.contain);
      expect(
        calls
            .lastWhere((c) => c.method == 'viewport')
            .arguments['preserveAspect'],
        true,
      );
      final state = tester.state(find.byType(PlatformViewLink));
      expect(contain.size, const Size(300, 150));
      expect((await layout(BoxFit.cover)).size, const Size(300, 300));
      expect(
        calls
            .lastWhere((c) => c.method == 'viewport')
            .arguments['preserveAspect'],
        false,
      );
      expect((await layout(BoxFit.fill)).size, const Size(300, 300));
      expect(
        calls
            .lastWhere((c) => c.method == 'viewport')
            .arguments['preserveAspect'],
        false,
      );
      expect((await layout(BoxFit.fitWidth)).size, const Size(300, 150));
      expect((await layout(BoxFit.fitHeight)).size, const Size(300, 300));
      expect((await layout(BoxFit.none)).size, const Size(300, 200));
      expect((await layout(BoxFit.scaleDown)).size, const Size(300, 150));
      expect(
        (await layout(BoxFit.contain, ratio: 4 / 3)).size,
        const Size(300, 225),
      );
      expect(
        calls
            .lastWhere((c) => c.method == 'viewport')
            .arguments['preserveAspect'],
        false,
      );
      expect(
        (await layout(BoxFit.contain, ratio: 16 / 9)).size,
        const Size(300, 168.75),
      );
      expect(tester.state(find.byType(PlatformViewLink)), same(state));
      final top = await layout(BoxFit.contain, alignment: Alignment.topCenter);
      expect(top.top, contain.top - 75);
      await tester.runAsync(
        () => messenger.handlePlatformMessage(
          events.name,
          const StandardMethodCodec().encodeSuccessEnvelope({
            'session': 7,
            'event': 'state',
            'position': 0,
            'duration': 1000,
            'playing': false,
            'speed': 1,
            'width': 100,
            'height': 50,
            'bufferedPercent': 100,
          }),
          (_) {},
        ),
      );
      await tester.pump();
      expect((await layout(BoxFit.scaleDown)).size, const Size(100, 50));
      await tester.pumpWidget(const SizedBox.shrink());
      debugDefaultTargetPlatformOverride = null;
    },
  );
}
