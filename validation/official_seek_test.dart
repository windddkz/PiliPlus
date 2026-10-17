import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:PiliPlus/plugin/pl_player/official_player.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const commands = MethodChannel('piliplus/official_player');
  const events = MethodChannel('piliplus/official_player/events');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  Future<void> emit(Map<String, Object?> value) async {
    await messenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope({
        'session': 7,
        'load': 1,
        ...value,
      }),
      (_) {},
    );
    await Future<void>.delayed(Duration.zero);
  }

  Future<void> result(
    int target,
    int actual, {
    bool timeout = false,
    int load = 1,
    int duration = 0,
  }) => emit({
    'event': 'seek_complete',
    'target': target,
    'duration': duration,
    'position': actual,
    'accurate': true,
    'timedOut': timeout,
    'load': load,
  });
  Future<void> open(OfficialPlayer p, {int position = 0}) => p.openSource(
    NetworkSource(
      videoSource: 'https://example.invalid/owned-video',
      audioSource: null,
    ),
    position: Duration(milliseconds: position),
    userAgent: 'test',
    referer: 'https://example.invalid/',
    audioOnly: false,
  );
  Future<OfficialPlayer> prepared() async {
    final p = await OfficialPlayer.create(
      (_) => throw StateError('Unexpected fallback'),
    );
    final opening = open(p);
    await emit({'event': 'prepared'});
    await opening;
    return p;
  }

  Iterable<MethodCall> seeks() => calls.where((c) => c.method == 'seek');
  setUp(() {
    calls.clear();
    messenger
      ..setMockMethodCallHandler(events, (_) async => null)
      ..setMockMethodCallHandler(commands, (call) async {
        calls.add(call);
        return switch (call.method) {
          'availability' => {'available': true},
          'create' => {'session': 7},
          _ => null,
        };
      });
  });
  tearDown(() {
    messenger
      ..setMockMethodCallHandler(events, null)
      ..setMockMethodCallHandler(commands, null);
  });

  test(
    'seek waits for actual result, publishes actual frame position',
    () async {
      final p = await prepared();
      bool done = false;
      final seek = p
          .seek(const Duration(milliseconds: 29875))
          .then((_) => done = true);
      await Future<void>.delayed(Duration.zero);
      expect(done, false);
      await result(29875, 29883);
      await seek;
      expect(p.state.position.inMilliseconds, 29883);
      await p.dispose();
    },
  );
  for (final initial in [false, true]) {
    test(
      'clamped end target completes ${initial ? 'initial' : 'ordinary'} seek',
      () async {
        final p = initial
            ? await OfficialPlayer.create(
                (_) => throw StateError('Unexpected fallback'),
              )
            : await prepared();
        bool done = false;
        final pending =
            (initial
                    ? open(p, position: 35000)
                    : p.seek(const Duration(seconds: 35)))
                .then<void>((_) => done = true, onError: (Object _) {});
        try {
          if (initial) await emit({'event': 'prepared'});
          await Future<void>.delayed(Duration.zero);
          await result(30000, 30000, duration: 30000);
          expect(done, true);
          expect(p.state.position.inMilliseconds, 30000);
        } finally {
          await p.dispose();
          await pending;
        }
      },
    );
  }
  test('unknown duration does not clamp the requested target', () async {
    final p = await prepared();
    final pending = p.seek(const Duration(seconds: 5));
    await Future<void>.delayed(Duration.zero);
    await result(5000, 5000, duration: -1);
    await pending;
    expect(p.state.position.inMilliseconds, 5000);
    await p.dispose();
  });
  test('confirmed seek before EOF clears completed state and stream', () async {
    final p = await prepared();
    final completed = <bool>[];
    final sub = p.stream.completed.listen(completed.add);
    await emit({'event': 'completed'});
    final pending = p.seek(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(p.state.completed, true);
    await result(0, 0, duration: 30000);
    await pending;
    expect(p.state.completed, false);
    expect(completed, [true, false]);
    expect(p.state.playing, false);
    await sub.cancel();
    await p.dispose();
  });
  test('unrelated, timed out and EOF seeks do not clear completion', () async {
    final p = await prepared();
    await emit({'event': 'completed'});
    final failed = expectLater(
      p.seek(Duration.zero),
      throwsA(isA<PlatformException>()),
    );
    await Future<void>.delayed(Duration.zero);
    await result(1000, 1000, duration: 30000);
    expect(p.state.completed, true);
    await result(0, 0, duration: 30000, timeout: true);
    await failed;
    expect(p.state.completed, true);
    final end = p.seek(const Duration(seconds: 35));
    await Future<void>.delayed(Duration.zero);
    await result(30000, 30000, duration: 30000);
    await end;
    expect(p.state.completed, true);
    await p.dispose();
  });
  test('duration cannot make an unrelated in-range target match', () async {
    final p = await prepared();
    bool done = false;
    final pending = p.seek(const Duration(seconds: 5)).then((_) => done = true);
    await Future<void>.delayed(Duration.zero);
    await result(30000, 30000, duration: 30000);
    expect(done, false);
    await result(5000, 5000, duration: 30000);
    await pending;
    await p.dispose();
  });
  test('native timeout is nonfatal and next seek can succeed', () async {
    final p = await prepared();
    final errors = <String>[];
    final sub = p.stream.error.listen(errors.add);
    final failed = expectLater(
      p.seek(const Duration(milliseconds: 29875)),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'official_seek_timeout',
        ),
      ),
    );
    await Future<void>.delayed(Duration.zero);
    await result(29875, 216, timeout: true);
    await failed;
    expect(p.state.position.inMilliseconds, 216);
    expect(errors, isEmpty);
    final seek = p.seek(const Duration(seconds: 1));
    await Future<void>.delayed(Duration.zero);
    await result(1000, 1000);
    await seek;
    await sub.cancel();
    await p.dispose();
  });
  test('same-target repeated seeks run one at a time', () async {
    final p = await prepared();
    final first = p.seek(const Duration(seconds: 5));
    final second = p.seek(const Duration(seconds: 5));
    bool done = false;
    final observed = second.then((_) => done = true);
    await Future<void>.delayed(Duration.zero);
    expect(seeks().length, 1);
    await result(5000, 5000);
    await first;
    expect(seeks().length, 2);
    expect(done, false);
    await result(5000, 5016);
    await observed;
    expect(p.state.position.inMilliseconds, 5016);
    await p.dispose();
  });
  test('new source cancels active and queued seeks; old load cannot finish new seek', () async {
    final p = await prepared();
    final first = expectLater(
      p.seek(const Duration(seconds: 1)),
      throwsStateError,
    );
    final second = expectLater(
      p.seek(const Duration(seconds: 2)),
      throwsStateError,
    );
    await Future<void>.delayed(Duration.zero);
    final opening = open(p);
    await emit({'event': 'prepared', 'load': 2});
    await opening;
    await first;
    await second;
    expect(seeks().length, 1);
    bool done = false;
    final current = p.seek(const Duration(seconds: 1)).then((_) => done = true);
    await Future<void>.delayed(Duration.zero);
    await result(1000, 1000);
    expect(done, false);
    await result(1000, 1000, load: 2);
    await current;
    await p.dispose();
  });
  test('dispose cancels both pending and queued seeks', () async {
    final p = await prepared();
    final first = expectLater(
      p.seek(const Duration(seconds: 1)),
      throwsStateError,
    );
    final second = expectLater(
      p.seek(const Duration(seconds: 2)),
      throwsStateError,
    );
    await Future<void>.delayed(Duration.zero);
    await p.dispose();
    await first;
    await second;
    expect(seeks().length, 1);
  });
  test('initial position must finish before preparation is accepted', () async {
    final p = await OfficialPlayer.create(
      (_) => throw StateError('Unexpected fallback'),
    );
    bool done = false;
    final opening = open(p, position: 25000).then((_) => done = true);
    await emit({'event': 'prepared'});
    expect(done, false);
    await result(25000, 25000);
    await opening;
    await p.dispose();
  });
  test(
    'initial seek timeout fails preparation instead of claiming ready',
    () async {
      final p = await OfficialPlayer.create(
        (_) => throw StateError('Unexpected fallback'),
      );
      final failed = expectLater(
        open(p, position: 25000),
        throwsA(isA<PlatformException>()),
      );
      await emit({'event': 'prepared'});
      await result(25000, 216, timeout: true);
      await failed;
      await p.dispose();
    },
  );
  test('unrelated target cannot finish the active seek', () async {
    final p = await prepared();
    bool done = false;
    final seeking = p.seek(const Duration(seconds: 5)).then((_) => done = true);
    await Future<void>.delayed(Duration.zero);
    await result(1000, 1000);
    expect(done, false);
    expect(p.state.position, Duration.zero);
    await result(5000, 5000);
    await seeking;
    await p.dispose();
  });
  test(
    'native death cancels queued seeks without sending another command',
    () async {
      final p = await prepared();
      final first = expectLater(
        p.seek(const Duration(seconds: 1)),
        throwsA(isA<PlatformException>()),
      );
      final second = expectLater(
        p.seek(const Duration(seconds: 2)),
        throwsStateError,
      );
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'error', 'error': 'native_process_died'});
      await first;
      await second;
      expect(seeks().length, 1);
      await p.dispose();
    },
  );
  test('command rejection does not stall the next seek', () async {
    final p = await prepared();
    bool reject = true;
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      if (call.method == 'seek' && reject) {
        reject = false;
        throw PlatformException(code: 'native_command_failed');
      }
      return null;
    });
    await expectLater(
      p.seek(const Duration(seconds: 1)),
      throwsA(isA<PlatformException>()),
    );
    final next = p.seek(const Duration(seconds: 2));
    await Future<void>.delayed(Duration.zero);
    await result(2000, 2000);
    await next;
    await p.dispose();
  });
  test('missing callback blocks further seeks until a new source', () async {
    final p = await prepared();
    await expectLater(
      p.seek(const Duration(seconds: 1)),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'official_seek_result_unavailable',
        ),
      ),
    );
    await expectLater(
      p.seek(const Duration(seconds: 1)),
      throwsA(isA<PlatformException>()),
    );
    expect(seeks().length, 1);
    await result(1000, 1000);
    await expectLater(
      p.seek(const Duration(seconds: 1)),
      throwsA(isA<PlatformException>()),
    );
    final opening = open(p);
    await emit({'event': 'prepared', 'load': 2});
    await opening;
    final seek = p.seek(const Duration(seconds: 1));
    await Future<void>.delayed(Duration.zero);
    await result(1000, 1000, load: 2);
    await seek;
    await p.dispose();
  });
}
