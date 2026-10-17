import 'dart:async';

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
  Future<void> emit(Map<String, Object?> data) async {
    await messenger.handlePlatformMessage(
      events.name,
      const StandardMethodCodec().encodeSuccessEnvelope({
        'session': 7,
        'load': 1,
        ...data,
      }),
      (_) {},
    );
    await Future<void>.delayed(Duration.zero);
  }

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
      ..setMockMethodCallHandler(commands, null)
      ..setMockMethodCallHandler(events, null);
  });
  Future<OfficialPlayer> create() => OfficialPlayer.create(
    (reason) => throw StateError('Unexpected fallback: $reason'),
  );
  test(
    'fallback retains failure stage without source URLs or exception messages',
    () {
      expect(
        OfficialPlayer.failureCode(
          PlatformException(
            code: 'open_audio_probe_IOException',
            message: 'https://example.invalid/private-audio?token=secret',
          ),
        ),
        'open_audio_probe_IOException',
      );
      expect(
        OfficialPlayer.failureCode(TimeoutException('private source')),
        'prepare_timeout',
      );
      expect(
        OfficialPlayer.failureCode(StateError('private source')),
        'StateError',
      );
    },
  );
  Future<void> open(OfficialPlayer player) => player.openSource(
    NetworkSource(
      videoSource: 'https://example.invalid/owned-video',
      audioSource: 'https://example.invalid/owned-audio',
    ),
    userAgent: 'test',
    referer: 'https://example.invalid/',
    audioOnly: false,
  );

  test('resource refresh commits only after native acceptance and ignores duplicate requests', () async {
    final p = await create();
    final result = Completer<NetworkSource?>();
    var requests = 0;
    var commits = 0;
    final source = NetworkSource(
      videoSource: 'https://example.invalid/video',
      audioSource: 'https://example.invalid/audio',
      refresh: (_) {
        requests++;
        return result.future;
      },
      onRefreshed: (previous, next) {
        commits++;
      },
    );
    final opening = p.openSource(
      source,
      userAgent: 'test',
      referer: '',
      audioOnly: true,
    );
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'prepared'});
    await opening;
    await emit({'event': 'resource_refresh', 'requestId': 41});
    await emit({'event': 'resource_refresh', 'requestId': 41});
    expect(requests, 1);
    await p.pause();
    result.complete(
      NetworkSource(
        videoSource: 'https://example.invalid/fresh-video',
        audioSource: 'https://example.invalid/fresh-audio',
        audioTrack: DashTrack(
          source: 'https://example.invalid/fresh-audio',
          id: 30251,
          bandwidth: 999,
          urls: ['https://example.invalid/backup-audio'],
        ),
      ),
    );
    await Future<void>.delayed(Duration.zero);
    final answer =
        calls.singleWhere((c) => c.method == 'resourceUpdate').arguments as Map;
    expect(answer['source'], 'https://example.invalid/fresh-audio');
    expect(answer.containsKey('audio'), false);
    expect(answer['sourceTrack']['id'], 30251);
    expect(answer['requestId'], 41);
    expect(answer['load'], 1);
    expect(p.state.position, Duration.zero);
    expect(commits, 0);
    await emit({'event': 'resource_updated', 'requestId': 40});
    expect(commits, 0);
    await emit({'event': 'resource_updated', 'requestId': 41});
    expect(commits, 1);
    await emit({'event': 'resource_updated', 'requestId': 41});
    await emit({'event': 'resource_refresh', 'requestId': 41});
    expect(commits, 1);
    expect(requests, 1);
    await p.dispose();
  });

  test('a newer native request supersedes a pending refresh without changing source early', () async {
    final p = await create();
    final first = Completer<NetworkSource?>();
    final second = Completer<NetworkSource?>();
    final committed = <String>[];
    var requests = 0;
    final source = NetworkSource(
      videoSource: 'https://example.invalid/old',
      audioSource: null,
      refresh: (_) => ++requests == 1 ? first.future : second.future,
      onRefreshed: (_, next) => committed.add(next.videoSource),
    );
    final opening = p.openSource(
      source,
      userAgent: 'test',
      referer: '',
      audioOnly: true,
    );
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'prepared'});
    await opening;
    await emit({'event': 'resource_refresh', 'requestId': 1});
    await emit({'event': 'resource_refresh', 'requestId': 2});
    expect(requests, 2);
    first.complete(
      NetworkSource(
        videoSource: 'https://example.invalid/late',
        audioSource: null,
      ),
    );
    second.complete(
      NetworkSource(
        videoSource: 'https://example.invalid/current',
        audioSource: null,
      ),
    );
    await Future<void>.delayed(Duration.zero);
    final replies = calls
        .where((call) => call.method == 'resourceUpdate')
        .toList();
    expect(replies.length, 1);
    expect(replies.single.arguments['requestId'], 2);
    expect(committed, isEmpty);
    await emit({'event': 'resource_updated', 'requestId': 1});
    expect(committed, isEmpty);
    await emit({'event': 'resource_updated', 'requestId': 2});
    expect(committed, ['https://example.invalid/current']);
    await p.dispose();
  });

  test(
    'refresh from a replaced source cannot send its late response',
    () async {
      final p = await create();
      final pending = Completer<NetworkSource?>();
      final opening = p.openSource(
        NetworkSource(
          videoSource: 'https://example.invalid/old',
          audioSource: null,
          refresh: (_) => pending.future,
        ),
        userAgent: 'test',
        referer: '',
        audioOnly: true,
      );
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'prepared'});
      await opening;
      await emit({'event': 'resource_refresh', 'requestId': 51});
      final next = open(p);
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'prepared', 'load': 2});
      await next;
      pending.complete(
        NetworkSource(
          videoSource: 'https://example.invalid/too-late',
          audioSource: null,
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(calls.where((c) => c.method == 'resourceUpdate'), isEmpty);
      await p.dispose();
    },
  );

  test('fruitless refresh is bounded and resource failure clears buffering without fallback', () async {
    final p = await create();
    var requests = 0;
    late NetworkSource source;
    source = NetworkSource(
      videoSource: 'https://example.invalid/audio',
      audioSource: null,
      refresh: (_) async {
        requests++;
        return source;
      },
    );
    final opening = p.openSource(
      source,
      userAgent: 'test',
      referer: '',
      audioOnly: true,
    );
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'prepared'});
    await opening;
    final errors = <String>[];
    final subscription = p.stream.error.listen(errors.add);
    for (var i = 1; i <= 4; i++) {
      await emit({'event': 'resource_refresh', 'requestId': i});
    }
    expect(requests, 3);
    expect(
      calls
          .where((c) => c.method == 'resourceUpdate')
          .map((c) => c.arguments['available']),
      [true, true, true, false],
    );
    await emit({'event': 'buffering', 'buffering': true});
    await emit({'event': 'resource_error', 'error': 'resource_unavailable'});
    expect(p.state.buffering, false);
    expect(p.state.playing, false);
    expect(errors, ['official:resource_unavailable']);
    expect(
      OfficialPlayer.isResourceFailure(
        PlatformException(code: 'native_process_died'),
      ),
      false,
    );
    await subscription.cancel();
    await p.dispose();
  });

  test(
    'viewport waits for preparation, deduplicates and follows source load',
    () async {
      final p = await create();
      await p.updateVideoTransform(2, 1, 10, 0);
      expect(calls.where((c) => c.method == 'viewport'), isEmpty);
      var opening = open(p);
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'prepared'});
      await opening;
      await p.updateVideoTransform(2, 1, 10, 0);
      await p.updateVideoTransform(2, 1, 10, 0);
      var viewport = calls.where((c) => c.method == 'viewport').toList();
      expect(viewport.length, 1);
      expect(viewport.single.arguments, {
        'session': 7,
        'load': 1,
        'scaleX': 2.0,
        'scaleY': 1.0,
        'offsetX': 10,
        'offsetY': 0,
      });
      await expectLater(
        p.updateVideoTransform(double.nan, 1, 0, 0),
        throwsArgumentError,
      );
      opening = open(p);
      await Future<void>.delayed(Duration.zero);
      await p.updateVideoTransform(3, 1, 0, 0);
      expect(calls.where((c) => c.method == 'viewport').length, 1);
      await emit({'event': 'prepared', 'load': 2});
      await opening;
      await p.updateVideoTransform(2, 1, 10, 0);
      viewport = calls.where((c) => c.method == 'viewport').toList();
      expect(viewport.length, 2);
      expect(viewport.last.arguments['load'], 2);
      await p.dispose();
      await p.updateVideoTransform(1, 1, 0, 0);
      expect(calls.where((c) => c.method == 'viewport').length, 2);
    },
  );

  test(
    'mirror axes follow the source load and default to reset on a new source',
    () async {
      final p = await create();
      final first = p.openSource(
        NetworkSource(
          videoSource: 'https://example.invalid/video',
          audioSource: null,
        ),
        userAgent: 'test',
        referer: 'https://example.invalid/',
        audioOnly: false,
        flipX: true,
        flipY: true,
      );
      await emit({'event': 'prepared'});
      await first;
      final initial =
          calls.lastWhere((c) => c.method == 'open').arguments as Map;
      expect(initial['flipX'], true);
      expect(initial['flipY'], true);
      await p.setMirror(false, true);
      expect(calls.last.arguments, {
        'session': 7,
        'load': 1,
        'flipX': false,
        'flipY': true,
      });
      final second = open(p);
      await emit({'event': 'prepared', 'load': 2});
      await second;
      final reset = calls.lastWhere((c) => c.method == 'open').arguments as Map;
      expect(reset['flipX'], false);
      expect(reset['flipY'], false);
      await p.setMirror(true, false);
      expect(calls.last.arguments, {
        'session': 7,
        'load': 2,
        'flipX': true,
        'flipY': false,
      });
      await p.dispose();
      final count = calls.length;
      await expectLater(p.setMirror(false, false), throwsStateError);
      expect(calls.length, count);
    },
  );

  test('safe exit diagnostics survive fallback, private details do not', () {
    expect(
      OfficialPlayer.failureCode(
        PlatformException(
          code: 'native_process_died',
          details: 'timeout_open/graph_adapter/reason_2/status_9',
        ),
      ),
      'native_process_died (timeout_open/graph_adapter/reason_2/status_9)',
    );
    expect(
      OfficialPlayer.failureCode(
        PlatformException(
          code: 'native_command_failed',
          details: 'open_audio_probe_IOException',
        ),
      ),
      'native_command_failed (open_audio_probe_IOException)',
    );
    expect(
      OfficialPlayer.failureCode(
        PlatformException(
          code: 'native_process_died',
          details: 'https://example.invalid/?token=secret',
        ),
      ),
      'native_process_died',
    );
  });
  test(
    'death details reach open failure and disposal still recognizes death',
    () async {
      final player = await create();
      final opening = open(player);
      final failed = expectLater(
        opening,
        throwsA(
          isA<PlatformException>().having(
            OfficialPlayer.failureCode,
            'diagnostic',
            'native_process_died (unexpected_exit/player_create/reason_5/status_11)',
          ),
        ),
      );
      await Future<void>.delayed(Duration.zero);
      await emit({
        'event': 'error',
        'error': 'native_process_died',
        'diagnostic': 'unexpected_exit/player_create/reason_5/status_11',
      });
      await failed;
      await player.dispose();
      expect(calls.where((call) => call.method == 'dispose'), isEmpty);
    },
  );

  test(
    'duck preserves user volume and survives volume changes and source reload',
    () async {
      final player = await create();
      await player.setVolume(80);
      await player.setDucked(true);
      await player.setDucked(true);
      expect(player.state.volume, 80);
      expect((calls.last.arguments as Map)['volume'], closeTo(.256, 1e-9));
      await player.setVolume(50);
      expect((calls.last.arguments as Map)['volume'], closeTo(.0625, 1e-9));
      final opening = open(player);
      await Future<void>.delayed(Duration.zero);
      expect((calls.last.arguments as Map)['volume'], closeTo(.0625, 1e-9));
      await emit({'event': 'prepared'});
      await opening;
      await player.setDucked(false);
      expect(player.state.volume, 50);
      expect((calls.last.arguments as Map)['volume'], closeTo(.125, 1e-9));
      await player.dispose();
    },
  );

  test('volume changed while duck command is pending remains ducked', () async {
    final player = await create();
    final pending = Completer<void>();
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      if (call.method == 'volume' && (call.arguments as Map)['volume'] == .5) {
        await pending.future;
      }
      return null;
    });
    final ducking = player.setDucked(true);
    await player.setVolume(50);
    expect((calls.last.arguments as Map)['volume'], closeTo(.0625, 1e-9));
    pending.complete();
    await ducking;
    await player.setDucked(false);
    expect((calls.last.arguments as Map)['volume'], closeTo(.125, 1e-9));
    await player.dispose();
  });

  test(
    'new source sends fresh loudness metadata and clears prior diagnostics',
    () async {
      final player = await create();
      final first = player.openSource(
        NetworkSource(
          videoSource: 'https://example.invalid/a',
          audioSource: null,
        ),
        userAgent: 'test',
        referer: '',
        audioOnly: true,
        loudnessMode: 'normal',
        loudnessValues: [-20.57, 3, -18.06, -30.71, .2, -16, -1.5],
        loudnessScenes: {
          'normal_target_i': '-16',
          'undersized_target_i': '-28',
        },
      );
      await Future<void>.delayed(Duration.zero);
      final sent = calls.last.arguments as Map;
      expect(sent['audioOnly'], true);
      expect(sent['loudnessMode'], 'normal');
      expect((sent['loudnessValues'] as List).first, -20.57);
      await emit({'event': 'prepared'});
      await first;
      await emit({
        'event': 'state',
        'position': 0,
        'duration': 30000,
        'playing': false,
        'speed': 1.0,
        'bufferedPercent': 0,
        'width': 640,
        'height': 360,
        'loudnessState': 'requested_with_scenes',
        'audioCodec': 'flac',
        'requestedAudioFilter':
            'aresample=ochl=stereo:resampler=soxr,ijkloudnorm=...',
      });
      expect(player.requestedAudioFilter, isNotEmpty);
      expect(player.audioDescription, '实际音频编码：flac');
      final second = player.openSource(
        NetworkSource(
          videoSource: 'https://example.invalid/b',
          audioSource: null,
        ),
        userAgent: 'test',
        referer: '',
        audioOnly: true,
        loudnessMode: 'normal',
      );
      await Future<void>.delayed(Duration.zero);
      expect(player.requestedAudioFilter, isEmpty);
      expect(player.loudnessState, 'pending');
      expect(player.audioDescription, '实际音频编码：等待当前音源信息');
      expect((calls.last.arguments as Map)['loudnessValues'], null);
      await emit({'event': 'prepared', 'load': 2});
      await second;
      await emit({
        'event': 'state',
        'load': 1,
        'position': 0,
        'duration': 30000,
        'playing': false,
        'speed': 1.0,
        'bufferedPercent': 0,
        'width': 640,
        'height': 360,
        'loudnessState': 'requested_with_scenes',
        'requestedAudioFilter': 'stale',
        'audioCodec': 'flac',
      });
      expect(player.requestedAudioFilter, isEmpty);
      expect(player.audioCodec, isEmpty);
      await player.dispose();
    },
  );

  test('reject unavailable ABI before creating native resources', () async {
    messenger.setMockMethodCallHandler(commands, (call) async {
      expect(call.method, 'availability');
      return {'available': false, 'reason': 'arm64_required'};
    });
    await expectLater(
      create(),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'arm64_required',
        ),
      ),
    );
  });

  test(
    'wait for current source preparation and discard stale source events',
    () async {
      final player = await create();
      var ready = false;
      final opening = open(player).then((_) => ready = true);
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'prepared', 'load': 0});
      expect(ready, false);
      await emit({'event': 'prepared'});
      await opening;
      expect(ready, true);
      await emit({'event': 'state', 'load': 0, 'position': 9999});
      expect(player.state.position, Duration.zero);
      final playingEvents = <bool>[];
      final playingSubscription = player.stream.playing.listen(
        playingEvents.add,
      );
      final nativeState = <String, Object?>{
        'event': 'state',
        'position': 2000,
        'duration': 30000,
        'playing': true,
        'speed': 1.5,
        'width': 640,
        'height': 360,
        'bufferedPercent': 80,
        'sampleRate': 48000,
        'channelLayout': 'stereo',
        'audioCodec': 'avcodec, aac',
        'audioSession': 5,
        'dolbyEndpoint': 1,
        'routeEvidence': 'connected_devices_inference',
      };
      await emit(nativeState);
      expect(player.state.position.inMilliseconds, 2000);
      expect(player.state.buffer.inMilliseconds, 24000);
      expect(player.state.rate, 1.5);
      expect(player.state.audioParams.sampleRate, 48000);
      expect(player.dolbyEndpoint, 1);
      expect(player.routeDescription, '端点配置：扬声器（连接设备推定）；当前音轨不使用 Dolby 处理');
      await emit({
        ...nativeState,
        'audioCodec': 'avcodec, eac3',
        'dolbyControlsApplicable': true,
      });
      expect(player.routeDescription, '端点配置：扬声器（连接设备推定）');
      await emit({...nativeState, 'playing': false});
      await emit({...nativeState, 'playing': false});
      expect(playingEvents, [true, false]);
      await playingSubscription.cancel();
      await player.dispose();
    },
  );

  test('native errors before open acknowledgement are retained', () async {
    final acknowledge = Completer<void>();
    messenger.setMockMethodCallHandler(commands, (call) async {
      if (call.method == 'availability') return {'available': true};
      if (call.method == 'create') return {'session': 7, 'texture': 42};
      if (call.method == 'open') await acknowledge.future;
      return null;
    });
    final player = await create();
    final opening = open(player);
    final failure = expectLater(opening, throwsA(isA<PlatformException>()));
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'error', 'error': 'IOException'});
    acknowledge.complete();
    await failure;
    await player.dispose();
  });

  test(
    'volume follows existing cubic scale and invalid values never reach native',
    () async {
      final player = await create();
      await player.setVolume(50);
      expect((calls.last.arguments as Map)['volume'], .125);
      final before = calls.length;
      await expectLater(player.setVolume(double.nan), throwsArgumentError);
      await expectLater(player.setRate(double.infinity), throwsArgumentError);
      expect(calls.length, before);
      await player.dispose();
    },
  );

  test('dispose is idempotent and waits for confirmed native close', () async {
    final closed = Completer<void>();
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      if (call.method == 'availability') return {'available': true};
      if (call.method == 'create') return {'session': 7, 'texture': 42};
      if (call.method == 'dispose') await closed.future;
      return null;
    });
    final player = await create();
    var disposed = false;
    final first = player.dispose();
    expect(identical(first, player.dispose()), true);
    unawaited(first.then((_) => disposed = true));
    await Future<void>.delayed(Duration.zero);
    expect(disposed, false);
    await expectLater(player.play(), throwsStateError);
    closed.complete();
    await first;
    expect(calls.where((c) => c.method == 'dispose'), hasLength(1));
    await expectLater(player.play(), throwsStateError);
  });

  test(
    'another page waits for native stop before creating its session',
    () async {
      final stopped = Completer<void>();
      messenger.setMockMethodCallHandler(commands, (call) async {
        calls.add(call);
        if (call.method == 'availability') return {'available': true};
        if (call.method == 'create') return {'session': 7};
        if (call.method == 'dispose') await stopped.future;
        return null;
      });
      final video = await create();
      final closing = video.dispose();
      final audio = create();
      await Future<void>.delayed(Duration.zero);
      expect(calls.where((c) => c.method == 'create'), hasLength(1));
      stopped.complete();
      await closing;
      final next = await audio;
      expect(calls.where((c) => c.method == 'create'), hasLength(2));
      await next.dispose();
    },
  );

  test(
    'page teardown includes late creation and rejects unconfirmed release',
    () async {
      final pageStopped = Completer<void>();
      OfficialPlayer.deferCreationUntil(pageStopped.future);
      final creating = create();
      final failed = expectLater(creating, throwsStateError);
      await Future<void>.delayed(Duration.zero);
      expect(calls.where((c) => c.method == 'create'), isEmpty);
      pageStopped.completeError(StateError('native release not confirmed'));
      await failed;
      expect(calls.where((c) => c.method == 'create'), isEmpty);
      final retry = await create();
      await retry.dispose();
    },
  );

  test(
    'unconfirmed stop fails and later process death allows cleanup',
    () async {
      messenger.setMockMethodCallHandler(commands, (call) async {
        if (call.method == 'availability') return {'available': true};
        if (call.method == 'create') return {'session': 7, 'texture': 42};
        if (call.method == 'dispose') {
          throw PlatformException(code: 'stop_unconfirmed');
        }
        return null;
      });
      final player = await create();
      await expectLater(player.dispose(), throwsA(isA<PlatformException>()));
      await Future<void>.delayed(Duration.zero);
      await expectLater(create(), throwsA(isA<PlatformException>()));
      await expectLater(
        OfficialPlayer.waitForDisposal(),
        throwsA(isA<PlatformException>()),
      );
      await emit({'event': 'error', 'error': 'native_process_died', 'load': 0});
      await OfficialPlayer.waitForDisposal();
      await player.dispose();
      final next = await create();
      await emit({'event': 'error', 'error': 'native_process_died', 'load': 0});
      await next.dispose();
      await expectLater(player.play(), throwsStateError);
    },
  );

  test(
    'disposing during prepare cancels open and ignores late prepared',
    () async {
      final player = await create();
      final opening = open(player);
      final failure = expectLater(opening, throwsStateError);
      await Future<void>.delayed(Duration.zero);
      await player.dispose();
      await failure;
      await emit({'event': 'prepared'});
      await expectLater(open(player), throwsStateError);
    },
  );
}
