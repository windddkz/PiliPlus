import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:PiliPlus/models/common/video/video_decode_type.dart';

import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:PiliPlus/plugin/pl_player/official_player.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
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
  NetworkSource preloadedSource() => NetworkSource(
    videoSource: 'https://example.invalid/video',
    audioSource: 'https://example.invalid/audio',
    officialPreloadToken: 'local_173',
    videoTrack: DashTrack(
      source: 'https://example.invalid/video',
      id: 80,
      bandwidth: 1234,
      urls: const ['https://example.invalid/video'],
    ),
    audioTrack: DashTrack(
      source: 'https://example.invalid/audio',
      id: 30251,
      bandwidth: 456,
      urls: const ['https://example.invalid/audio'],
    ),
  );
  Future<void> preload() => OfficialPlayer.preloadWhenIdle(
    preloadedSource(),
    position: const Duration(milliseconds: 12375),
    userAgent: 'test-agent',
    referer: 'https://example.invalid/',
  );
  NetworkSource ladderSource({
    Future<NetworkSource?> Function(NetworkSource)? refresh,
    void Function(NetworkSource, NetworkSource)? onRefreshed,
    void Function(NetworkSource, int?, bool)? onVideoQuality,
    String suffix = '',
  }) {
    final audio = preloadedSource().audioTrack!;
    final video = DashTrack(
      source: 'https://example.invalid/120$suffix',
      id: 120,
      bandwidth: 4000,
      codecs: 'avc1.640033',
      urls: const [],
    );
    final low = DashTrack(
      source: 'https://example.invalid/32$suffix',
      id: 32,
      bandwidth: 1000,
      codecs: 'avc1.640033',
      urls: const [],
    );
    return NetworkSource(
      videoSource: video.source,
      audioSource: audio.source,
      videoTrack: video,
      audioTrack: audio,
      videoTracks: [video, low],
      automaticVideo: true,
      refresh: refresh,
      onRefreshed: onRefreshed,
      onVideoQuality: onVideoQuality,
    );
  }

  Future<OfficialPlayer> preparedLadder({NetworkSource? source}) async {
    final player = await create();
    final ready = player.openSource(
      source ?? ladderSource(),
      userAgent: 'test',
      referer: 'test',
      audioOnly: false,
    );
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'prepared'});
    await ready;
    return player;
  }

  test('screenshot reads native image without changing player and removes temporary file', () async {
    final directory = await Directory.systemTemp.createTemp(
      'official-capture-test',
    );
    try {
      final recorder = ui.PictureRecorder();
      ui.Canvas(recorder)
          .drawColor(const ui.Color(0xffff0000), ui.BlendMode.src);
      final picture = recorder.endRecording();
      final image = await picture.toImage(3, 2);
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      image.dispose();
      picture.dispose();
      final file = File('${directory.path}/frame.png');
      await file.writeAsBytes(bytes!.buffer.asUint8List());
      final player = await create();
      messenger.setMockMethodCallHandler(
        commands,
        (call) async => call.method == 'screenshot' ? file.path : null,
      );
      final capture = await player.screenshot();
      expect((capture?.width, capture?.height), (3, 2));
      expect(file.existsSync(), false);
      capture?.dispose();
      await player.dispose();
    } finally {
      await directory.delete(recursive: true);
    }
  });

  test(
    'screenshot failure is local and a late reply after dispose is discarded',
    () async {
      final player = await create();
      messenger.setMockMethodCallHandler(commands, (call) async {
        if (call.method == 'screenshot') {
          throw PlatformException(code: 'capture_failed');
        }
        return null;
      });
      expect(await player.screenshot(), null);
      expect(player.processDead, false);
      final reply = Completer<String>();
      messenger.setMockMethodCallHandler(
        commands,
        (call) async => call.method == 'screenshot' ? reply.future : null,
      );
      final capture = player.screenshot();
      await Future<void>.delayed(Duration.zero);
      await player.dispose();
      final directory = await Directory.systemTemp.createTemp(
        'official-stale-capture',
      );
      try {
        final file = await File('${directory.path}/frame.png')
            .writeAsString('stale');
        reply.complete(file.path);
        expect(await capture, null);
        expect(file.existsSync(), false);
      } finally {
        await directory.delete(recursive: true);
      }
    },
  );

  test(
    'plain subtitles stay on official playback and follow seek/disable',
    () async {
      final player = await preparedLadder();
      await player.setSubtitleTrack(
        const SubtitleTrack(
          'memory://WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n你好\n第二行\n\n00:00:02.000 --> 00:00:04.000\n重叠',
          '中文',
          'zh',
          uri: true,
        ),
      );
      Future<void> at(int position) => emit({
        'event': 'state',
        'position': position,
        'duration': 10000,
        'playing': false,
        'speed': 1.0,
        'width': 640,
        'height': 360,
        'bufferedPercent': 100,
      });
      await at(1000);
      expect(player.state.subtitle.toString(), '你好\n第二行');
      await at(2000);
      expect(player.state.subtitle.toString(), '你好\n第二行\n重叠');
      await at(4000);
      expect(player.state.subtitle.isEmpty, true);
      await at(1000);
      expect(player.state.subtitle.toString(), '你好\n第二行');
      await player.setSubtitleTrack(SubtitleTrack.no());
      await at(2000);
      expect(player.state.subtitle.isEmpty, true);
      await player.dispose();
    },
  );

  test(
    'warmup retains validated device codec order across idle disposal',
    () async {
      messenger.setMockMethodCallHandler(commands, (call) async {
        calls.add(call);
        return switch (call.method) {
          'availability' => {'available': true},
          'create' => {'session': 7},
          'warmUp' => {
            'videoCodecs': ['AV1', 'HEVC', 'AVC'],
          },
          _ => null,
        };
      });
      await OfficialPlayer.warmUpWhenIdle();
      expect(OfficialPlayer.preferredVideoCodecs, [
        VideoDecodeFormatType.AV1,
        VideoDecodeFormatType.HEVC,
        VideoDecodeFormatType.AVC,
      ]);
      expect(
        () => OfficialPlayer.preferredVideoCodecs!.clear(),
        throwsUnsupportedError,
      );
      expect(calls.last.method, 'dispose');
    },
  );
  test(
    'missing or malformed capability reply does not replace a valid cache',
    () async {
      final player = await create();
      addTearDown(player.dispose);
      messenger.setMockMethodCallHandler(
        commands,
        (call) async => call.method == 'warmUp'
            ? {
                'videoCodecs': ['HEVC', 'AVC'],
              }
            : null,
      );
      await player.warmUp();
      final previous = OfficialPlayer.preferredVideoCodecs;
      for (final value in [
        null,
        [],
        ['AV1', 'AV1'],
        ['unknown'],
        [7],
        'AVC',
      ]) {
        messenger.setMockMethodCallHandler(
          commands,
          (call) async =>
              call.method == 'warmUp' ? {'videoCodecs': value} : null,
        );
        await player.warmUp();
        expect(OfficialPlayer.preferredVideoCodecs, same(previous));
      }
    },
  );
  test('disposed session cannot overwrite device codec capabilities', () async {
    final player = await create();
    final previous = OfficialPlayer.preferredVideoCodecs;
    final reply = Completer<Map<String, Object>>();
    messenger.setMockMethodCallHandler(
      commands,
      (call) async => call.method == 'warmUp' ? reply.future : null,
    );
    final pending = player.warmUp();
    await Future<void>.delayed(Duration.zero);
    await player.dispose();
    reply.complete({
      'videoCodecs': ['AVC'],
    });
    await pending;
    expect(OfficialPlayer.preferredVideoCodecs, same(previous));
  });
  test(
    'quality command waits for its terminal event and serializes requests',
    () async {
      final player = await preparedLadder();
      var complete = false;
      final first = player.setVideoQuality(32).then((_) => complete = true);
      final second = player.setVideoQuality(null);
      await Future<void>.delayed(Duration.zero);
      final command = calls.last.arguments as Map;
      expect(command['quality'], 32);
      expect(calls.where((c) => c.method == 'videoControl').length, 1);
      await emit({'event': 'quality', 'quality': 32, 'automaticVideo': false});
      await emit({
        'event': 'video_control',
        'requestId': command['requestId'],
        'success': true,
        'automaticVideo': false,
        'load': 99,
      });
      await emit({
        'event': 'video_control',
        'requestId': 999,
        'success': true,
        'automaticVideo': false,
      });
      expect(complete, false);
      await emit({
        'event': 'video_control',
        'requestId': command['requestId'],
        'success': true,
        'automaticVideo': false,
      });
      await first;
      await Future<void>.delayed(Duration.zero);
      final next = calls.last.arguments as Map;
      expect(next['automatic'], true);
      expect(next['requestId'], isNot(command['requestId']));
      await emit({
        'event': 'video_control',
        'requestId': next['requestId'],
        'success': true,
        'automaticVideo': true,
      });
      await second;
      expect(player.automaticVideo, true);
      await player.dispose();
    },
  );
  test(
    'quality cancellation fails the request without hiding actual quality',
    () async {
      final player = await preparedLadder();
      final pending = player.setVideoQuality(32);
      final failed = expectLater(pending, throwsA(isA<PlatformException>()));
      await Future<void>.delayed(Duration.zero);
      final request = (calls.last.arguments as Map)['requestId'];
      await emit({'event': 'quality', 'quality': 120, 'automaticVideo': true});
      await emit({
        'event': 'video_control',
        'requestId': request,
        'success': false,
        'automaticVideo': true,
      });
      await failed;
      expect(player.videoQuality, 120);
      expect(player.automaticVideo, true);
      await expectLater(player.setVideoQuality(999), throwsArgumentError);
      await player.dispose();
    },
  );
  test('disposing cancels in-flight and queued quality commands', () async {
    final player = await preparedLadder();
    final first = expectLater(player.setVideoQuality(32), throwsStateError);
    final second = expectLater(player.setVideoQuality(120), throwsStateError);
    await Future<void>.delayed(Duration.zero);
    await player.dispose();
    await first;
    await second;
    expect(calls.where((c) => c.method == 'videoControl').length, 1);
  });
  test('terminal quality event may precede the method reply', () async {
    final player = await preparedLadder();
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      if (call.method == 'videoControl') {
        await emit({
          'event': 'video_control',
          'requestId': (call.arguments as Map)['requestId'],
          'success': true,
          'automaticVideo': false,
        });
      }
      return null;
    });
    await player.setVideoQuality(32);
    expect(player.automaticVideo, false);
    await player.dispose();
  });
  test(
    'source replacement cancels quality request and ignores its late result',
    () async {
      final player = await preparedLadder();
      final failed = expectLater(player.setVideoQuality(32), throwsStateError);
      await Future<void>.delayed(Duration.zero);
      final request = (calls.last.arguments as Map)['requestId'];
      final ready = player.openSource(
        ladderSource(),
        userAgent: 'test',
        referer: 'test',
        audioOnly: false,
      );
      await failed;
      await Future<void>.delayed(Duration.zero);
      await emit({
        'event': 'video_control',
        'requestId': request,
        'success': true,
        'automaticVideo': false,
      });
      await emit({'event': 'prepared', 'load': 2});
      await ready;
      expect(player.videoQuality, null);
      await player.dispose();
    },
  );

  Future<void> finishQuality(int request, {bool automatic = false}) => emit({
    'event': 'video_control',
    'requestId': request,
    'success': true,
    'automaticVideo': automatic,
  });

  test(
    'successful quality updates the recoverable source and keeps its audio',
    () async {
      final changes = <(NetworkSource, NetworkSource)>[];
      final source = ladderSource(onRefreshed: (a, b) => changes.add((a, b)));
      final player = await preparedLadder(source: source);
      final fixed = player.setVideoQuality(32);
      await Future<void>.delayed(Duration.zero);
      await finishQuality((calls.last.arguments as Map)['requestId'] as int);
      await fixed;
      final low = changes.single.$2;
      expect(changes.single.$1, same(source));
      expect(low.videoTrack!.id, 32);
      expect(low.automaticVideo, false);
      expect(low.audioTrack, same(source.audioTrack));
      expect(low.videoTracks, source.videoTracks);
      final auto = player.setVideoQuality(null);
      await Future<void>.delayed(Duration.zero);
      await finishQuality(
        (calls.last.arguments as Map)['requestId'] as int,
        automatic: true,
      );
      await auto;
      expect(changes.last.$1, same(low));
      expect(changes.last.$2.automaticVideo, true);
      final ready = player.openSource(
        changes.last.$2,
        userAgent: 'test',
        referer: 'test',
        audioOnly: false,
      );
      await Future<void>.delayed(Duration.zero);
      final opening =
          calls.lastWhere((c) => c.method == 'open').arguments as Map;
      expect(opening['automaticVideo'], true);
      expect(opening['sourceTrack']['id'], 32);
      expect(opening['audioTrack']['id'], 30251);
      await emit({'event': 'prepared', 'load': 2});
      await ready;
      await player.dispose();
    },
  );

  for (final refreshFirst in [false, true]) {
    test(
      'quality commit and refresh application order: refreshFirst=$refreshFirst',
      () async {
        final response = Completer<NetworkSource?>();
        final changes = <(NetworkSource, NetworkSource)>[];
        void accept(NetworkSource a, NetworkSource b) => changes.add((a, b));
        final source = ladderSource(
          refresh: (_) => response.future,
          onRefreshed: accept,
        );
        final fresh = ladderSource(suffix: '?fresh', onRefreshed: accept);
        final player = await preparedLadder(source: source);
        var completed = false;
        final fixed = player.setVideoQuality(32).then((_) => completed = true);
        await Future<void>.delayed(Duration.zero);
        final id = (calls.last.arguments as Map)['requestId'] as int;
        await emit({'event': 'resource_refresh', 'requestId': 301});
        if (!refreshFirst) await finishQuality(id);
        expect(changes, isEmpty);
        expect(completed, false);
        response.complete(fresh);
        await Future<void>.delayed(Duration.zero);
        expect(changes, isEmpty); // Method reply is not resource application.
        await emit({'event': 'resource_updated', 'requestId': 301});
        if (refreshFirst) await finishQuality(id);
        await fixed;
        expect(changes.length, 2);
        expect(changes[0], (source, fresh));
        expect(changes[1].$1, same(fresh));
        expect(changes[1].$2.videoSource, 'https://example.invalid/32?fresh');
        expect(changes[1].$2.automaticVideo, false);
        expect(changes[1].$2.audioTrack, same(fresh.audioTrack));
        await player.dispose();
      },
    );
  }

  test(
    'refresh after quality change requests the committed source selection',
    () async {
      NetworkSource? refreshedFrom;
      final changes = <NetworkSource>[];
      final source = ladderSource(
        onRefreshed: (_, next) => changes.add(next),
        refresh: (current) async {
          refreshedFrom = current;
          return ladderSource(suffix: '?fresh')
              .withVideoSelection(current.videoTrack!.id);
        },
      );
      final player = await preparedLadder(source: source);
      final fixed = player.setVideoQuality(32);
      await Future<void>.delayed(Duration.zero);
      await finishQuality((calls.last.arguments as Map)['requestId'] as int);
      await fixed;
      await emit({'event': 'resource_refresh', 'requestId': 302});
      await Future<void>.delayed(Duration.zero);
      expect(refreshedFrom, same(changes.single));
      final request =
          calls.lastWhere((c) => c.method == 'resourceUpdate').arguments as Map;
      expect(request['sourceTrack']['id'], 32);
      expect(request['automaticVideo'], false);
      await emit({'event': 'resource_updated', 'requestId': 302});
      await player.dispose();
    },
  );

  test(
    'dispose cancels successful native switch waiting for resource application',
    () async {
      final changes = <NetworkSource>[];
      final response = Completer<NetworkSource?>();
      final player = await preparedLadder(
        source: ladderSource(
          refresh: (_) => response.future,
          onRefreshed: (_, next) => changes.add(next),
        ),
      );
      final fixed = player.setVideoQuality(32);
      final failed = expectLater(fixed, throwsStateError);
      await Future<void>.delayed(Duration.zero);
      final id = (calls.last.arguments as Map)['requestId'] as int;
      await emit({'event': 'resource_refresh', 'requestId': 303});
      await finishQuality(id);
      await player.dispose();
      await failed;
      response.complete(ladderSource());
      await Future<void>.delayed(Duration.zero);
      expect(changes, isEmpty);
    },
  );

  test(
    'lost quality reply blocks further commands until source is reopened',
    () async {
      final player = await preparedLadder();
      messenger.setMockMethodCallHandler(commands, (call) async {
        calls.add(call);
        if (call.method == 'videoControl') {
          throw PlatformException(code: 'lost_reply');
        }
        return null;
      });
      await expectLater(
        player.setVideoQuality(32),
        throwsA(isA<PlatformException>()),
      );
      await expectLater(
        player.setVideoQuality(120),
        throwsA(isA<PlatformException>()),
      );
      expect(calls.where((c) => c.method == 'videoControl').length, 1);
      await player.dispose();
    },
  );

  for (final waitingResource in [false, true]) {
    test(
      'quality timeout preserves source and rejects late completion: resource=$waitingResource',
      () async {
        final changes = <NetworkSource>[];
        final player = await preparedLadder(
          source: ladderSource(onRefreshed: (_, next) => changes.add(next)),
        );
        final result = player.setVideoQuality(32);
        final failed = expectLater(
          result,
          throwsA(
            isA<PlatformException>().having(
              (e) => e.code,
              'code',
              'official_video_switch_result_unavailable',
            ),
          ),
        );
        await Future<void>.delayed(Duration.zero);
        final request = (calls.last.arguments as Map)['requestId'];
        if (waitingResource) {
          await emit({'event': 'resource_refresh', 'requestId': 399});
          await emit({
            'event': 'video_control',
            'requestId': request,
            'success': true,
            'automaticVideo': false,
          });
        }
        await failed;
        await emit({
          'event': 'video_control',
          'requestId': request,
          'success': true,
          'automaticVideo': false,
        });
        await expectLater(
          player.setVideoQuality(120),
          throwsA(isA<PlatformException>()),
        );
        expect(changes, isEmpty);
        expect(calls.where((c) => c.method == 'videoControl').length, 1);
        await player.dispose();
      },
    );
  }

  test(
    'native mode without a quality never invents the requested grade',
    () async {
      final seen = <(NetworkSource, int?, bool)>[];
      final source = ladderSource(
        onVideoQuality: (source, qa, auto) => seen.add((source, qa, auto)),
      );
      final player = await preparedLadder(source: source);
      addTearDown(player.dispose);
      final grades = <(int, bool)>[];
      final sub = player.qualityUpdates.listen(grades.add);
      addTearDown(sub.cancel);
      Future<void> mode(bool automatic) => emit({
        'event': 'state',
        'position': 0,
        'duration': 60000,
        'playing': true,
        'speed': 1.0,
        'width': 1920,
        'height': 1080,
        'bufferedPercent': 0,
        'videoQuality': 0,
        'automaticVideo': automatic,
      });
      await mode(true);
      expect(player.automaticVideo, true);
      expect(player.videoQuality, null);
      expect(seen, [(source, null, true)]);
      expect(grades, isEmpty);
      await emit({'event': 'quality', 'quality': -1, 'automaticVideo': false});
      expect(player.automaticVideo, true);
      await emit({'event': 'quality', 'quality': 80, 'automaticVideo': true});
      expect(seen.last, (source, 80, true));
      expect(grades, [(80, true)]);
      await mode(true);
      expect(player.videoQuality, 80);
      expect(seen.length, 2);
      await mode(false);
      expect(seen.last, (source, 80, false));
      expect(grades.last, (80, false));
    },
  );

  test('actual quality callback follows current source through selection and replacement', () async {
    final seen = <(NetworkSource, int?, bool)>[];
    NetworkSource? committed;
    void quality(NetworkSource source, int? qa, bool auto) =>
        seen.add((source, qa, auto));
    final original = ladderSource(
      onVideoQuality: quality,
      onRefreshed: (_, next) => committed = next,
    );
    final player = await preparedLadder(source: original);
    addTearDown(player.dispose);
    await emit({'event': 'quality', 'quality': 120, 'automaticVideo': true});
    expect(seen.single, (original, 120, true));
    final fixed = player.setVideoQuality(32);
    await Future<void>.delayed(Duration.zero);
    await finishQuality((calls.last.arguments as Map)['requestId'] as int);
    await fixed;
    expect(committed!.onVideoQuality, same(original.onVideoQuality));
    await emit({'event': 'quality', 'quality': 32, 'automaticVideo': false});
    expect(seen.last, (committed!, 32, false));
    final next = ladderSource(onVideoQuality: quality);
    final opening = player.openSource(
      next,
      userAgent: 'test',
      referer: 'test',
      audioOnly: false,
    );
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'quality', 'quality': 120, 'automaticVideo': true});
    expect(seen.length, 2);
    await emit({
      'event': 'quality',
      'quality': 120,
      'automaticVideo': true,
      'load': 2,
    });
    expect(seen.last, (next, 120, true));
    await emit({'event': 'prepared', 'load': 2});
    await opening;
    await player.dispose();
  });

  test(
    'open and refresh preserve video ladder and independent lossless audio',
    () async {
      final player = await create();
      final source = ladderSource(refresh: (_) async => ladderSource());
      final ready = player.openSource(
        source,
        userAgent: 'test',
        referer: 'test',
        audioOnly: false,
      );
      await Future<void>.delayed(Duration.zero);
      final opening =
          calls.firstWhere((c) => c.method == 'open').arguments as Map;
      expect(opening['automaticVideo'], true);
      expect((opening['videoTracks'] as List).map((t) => t['id']), [120, 32]);
      expect(opening['audioTrack']['id'], 30251);
      expect(player.videoQuality, null);
      expect(player.automaticVideo, false);
      await emit({'event': 'prepared'});
      await ready;
      await emit({'event': 'resource_refresh', 'requestId': 301});
      await Future<void>.delayed(Duration.zero);
      final fresh =
          calls.firstWhere((c) => c.method == 'resourceUpdate').arguments
              as Map;
      expect(fresh['automaticVideo'], true);
      expect(fresh['videoTracks'], opening['videoTracks']);
      expect(fresh['audioTrack'], opening['audioTrack']);
      await player.dispose();
    },
  );
  test(
    'audio only excludes video ladder and keeps the requested audio track',
    () async {
      final player = await create();
      final source = ladderSource();
      final ready = player.openSource(
        source,
        userAgent: 'test',
        referer: 'test',
        audioOnly: true,
      );
      await Future<void>.delayed(Duration.zero);
      final opening =
          calls.firstWhere((c) => c.method == 'open').arguments as Map;
      expect(opening.containsKey('videoTracks'), false);
      expect(opening.containsKey('automaticVideo'), false);
      expect(opening['sourceTrack']['id'], 30251);
      await emit({'event': 'prepared'});
      await ready;
      await player.dispose();
    },
  );
  test('quality follows native updates and ignores duplicate or replaced source events', () async {
    final player = await create();
    final seen = <(int, bool)>[];
    final sub = player.qualityUpdates.listen(seen.add);
    var ready = player.openSource(
      ladderSource(),
      userAgent: 'test',
      referer: 'test',
      audioOnly: false,
    );
    await Future<void>.delayed(Duration.zero);
    await emit({
      'event': 'quality',
      'quality': 120,
      'automaticVideo': true,
      'load': 99,
    });
    expect(player.videoQuality, null);
    await emit({'event': 'quality', 'quality': 80, 'automaticVideo': true});
    await emit({'event': 'quality', 'quality': 80, 'automaticVideo': true});
    await emit({'event': 'quality', 'quality': 32, 'automaticVideo': false});
    expect(seen, [(80, true), (32, false)]);
    await emit({'event': 'prepared'});
    await ready;
    ready = player.openSource(
      ladderSource(),
      userAgent: 'test',
      referer: 'test',
      audioOnly: false,
    );
    await Future<void>.delayed(Duration.zero);
    expect(player.videoQuality, null);
    await emit({'event': 'quality', 'quality': 80, 'automaticVideo': true});
    expect(player.videoQuality, null);
    await emit({
      'event': 'quality',
      'quality': 120,
      'automaticVideo': false,
      'load': 2,
    });
    expect(player.videoQuality, 120);
    await emit({'event': 'prepared', 'load': 2});
    await ready;
    await sub.cancel();
    await player.dispose();
  });
  test('media preload hands off the session before real create and queued preloads yield', () async {
    final queued = Completer<void>();
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      return switch (call.method) {
        'availability' => {'available': true},
        'create' => {'session': 7},
        'preload' => queued.future,
        _ => null,
      };
    });
    final first = preload();
    await Future<void>.delayed(Duration.zero);
    final second = preload();
    final playing = create();
    await Future<void>.delayed(Duration.zero);
    expect(calls.map((c) => c.method), [
      'availability',
      'create',
      'warmUp',
      'preload',
    ]);
    final args = calls.last.arguments as Map;
    expect(args['preloadToken'], 'local_173');
    expect(args['position'], 12375);
    expect(args['audioTrack']['id'], 30251);
    expect(args['userAgent'], 'test-agent');
    queued.complete();
    await Future.wait([first, second]);
    final player = await playing;
    expect(calls.map((c) => c.method), [
      'availability',
      'create',
      'warmUp',
      'preload',
      'dispose',
      'availability',
      'create',
    ]);
    await player.dispose();
  });
  test('media preload does not steal an active player', () async {
    final player = await create();
    await preload();
    expect(calls.map((c) => c.method), ['availability', 'create', 'preload']);
    await player.dispose();
  });
  test(
    'failed preload releases its session and does not prevent normal creation',
    () async {
      messenger.setMockMethodCallHandler(commands, (call) async {
        calls.add(call);
        return switch (call.method) {
          'availability' => {'available': true},
          'create' => {'session': 7},
          'preload' => throw PlatformException(code: 'native_command_failed'),
          _ => null,
        };
      });
      await expectLater(preload(), throwsA(isA<PlatformException>()));
      expect(calls.last.method, 'dispose');
      final player = await create();
      await player.dispose();
    },
  );
  test(
    'normal open forwards the same local token and track metadata',
    () async {
      final player = await create();
      final prepared = player.openSource(
        preloadedSource(),
        userAgent: 'test-agent',
        referer: 'https://example.invalid/',
        audioOnly: false,
      );
      await Future<void>.delayed(Duration.zero);
      final args = calls.firstWhere((c) => c.method == 'open').arguments as Map;
      expect(args['preloadToken'], 'local_173');
      expect(args['audioTrack']['id'], 30251);
      await emit({'event': 'prepared'});
      await prepared;
      await player.dispose();
    },
  );
  test(
    'idle warm-up releases before real create, without opening media',
    () async {
      final warming = Completer<void>();
      messenger.setMockMethodCallHandler(commands, (call) async {
        calls.add(call);
        return switch (call.method) {
          'availability' => {'available': true},
          'create' => {'session': 7},
          'warmUp' => warming.future,
          _ => null,
        };
      });
      final first = OfficialPlayer.warmUpWhenIdle();
      final repeated = OfficialPlayer.warmUpWhenIdle();
      await Future<void>.delayed(Duration.zero);
      final real = create();
      await Future<void>.delayed(Duration.zero);
      expect(calls.map((c) => c.method), ['availability', 'create', 'warmUp']);
      warming.complete();
      await Future.wait([first, repeated]);
      final player = await real;
      expect(calls.map((c) => c.method), [
        'availability',
        'create',
        'warmUp',
        'dispose',
        'availability',
        'create',
      ]);
      await player.dispose();
    },
  );
  test('idle warm-up does not take a live player event channel', () async {
    final player = await create();
    await OfficialPlayer.warmUpWhenIdle();
    expect(calls.map((c) => c.method), ['availability', 'create']);
    await emit({'event': 'error', 'error': 'native_process_died', 'load': 0});
    expect(player.processDead, true);
    await player.dispose();
  });
  test('idle warm-up does not overtake a pending real create', () async {
    final ready = Completer<Map<String, Object?>>();
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      return switch (call.method) {
        'availability' => ready.future,
        'create' => {'session': 7},
        _ => null,
      };
    });
    final real = create();
    await OfficialPlayer.warmUpWhenIdle();
    ready.complete({'available': true});
    final player = await real;
    expect(calls.map((c) => c.method), ['availability', 'create']);
    await player.dispose();
  });
  test(
    'failed idle warm-up releases and does not fail a waiting create',
    () async {
      final warming = Completer<void>();
      messenger.setMockMethodCallHandler(commands, (call) async {
        calls.add(call);
        return switch (call.method) {
          'availability' => {'available': true},
          'create' => {'session': 7},
          'warmUp' => warming.future,
          _ => null,
        };
      });
      final idle = OfficialPlayer.warmUpWhenIdle();
      final failure = expectLater(idle, throwsA(isA<PlatformException>()));
      await Future<void>.delayed(Duration.zero);
      final real = create();
      warming.completeError(PlatformException(code: 'native_command_failed'));
      await failure;
      final player = await real;
      expect(calls.map((c) => c.method), [
        'availability',
        'create',
        'warmUp',
        'dispose',
        'availability',
        'create',
      ]);
      await player.dispose();
    },
  );
  test('unconfirmed idle release still blocks a waiting real create', () async {
    var stopped = false;
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      if (call.method == 'dispose' && !stopped) {
        throw PlatformException(code: 'stop_unconfirmed');
      }
      return switch (call.method) {
        'availability' => {'available': true},
        'create' => {'session': 7},
        _ => null,
      };
    });
    final idle = OfficialPlayer.warmUpWhenIdle();
    final failure = expectLater(idle, throwsA(isA<PlatformException>()));
    await expectLater(create(), throwsA(isA<PlatformException>()));
    await failure;
    expect(calls.where((c) => c.method == 'create').length, 1);
    stopped = true;
    await emit({'event': 'error', 'error': 'native_process_died', 'load': 0});
    final recovered = await create();
    await recovered.dispose();
  });
  test('warm-up never opens media or marks playback ready', () async {
    final p = await create();
    await p.warmUp();
    await emit({'event': 'runtime_ready', 'load': 0});
    expect(calls.map((c) => c.method), ['availability', 'create', 'warmUp']);
    expect(calls.last.arguments, {'session': 7});
    expect(p.state.playing, false);
    expect(p.isPreparing, false);
    expect(p.audioSession, null);
    await p.dispose();
    await expectLater(p.warmUp(), throwsStateError);
  });
  test('idle process death remains disposable before a new create', () async {
    final p = await create();
    await p.warmUp();
    await emit({'event': 'error', 'error': 'native_process_died', 'load': 0});
    expect(p.processDead, true);
    expect(p.state.playing, false);
    await p.dispose();
    final next = await create();
    expect(next.processDead, false);
    await next.dispose();
  });
  test('failed warm-up can be disposed without opening media', () async {
    final p = await create();
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      if (call.method == 'warmUp') {
        throw PlatformException(code: 'native_command_failed');
      }
      return null;
    });
    await expectLater(p.warmUp(), throwsA(isA<PlatformException>()));
    await p.dispose();
    expect(calls.any((c) => c.method == 'open' || c.method == 'play'), false);
    expect(calls.last.method, 'dispose');
  });
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

  test(
    'confirmed reclaim permits local pause and disposal without a dead service',
    () async {
      final p = await create();
      final errors = <String>[];
      final subscription = p.stream.error.listen(errors.add);
      final ready = open(p);
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'prepared'});
      await ready;
      p.state.playing = true;
      p.state.position = const Duration(seconds: 42);
      await emit({
        'event': 'error',
        'error': 'native_process_died',
        'processReclaimed': true,
      });
      expect(p.processReclaimed, true);
      final count = calls.length;
      await p.pause();
      expect(p.state.playing, false);
      expect(p.state.position, const Duration(seconds: 42));
      expect(errors.single, startsWith('official:native_process_died'));
      await subscription.cancel();
      await p.dispose();
      expect(calls.length, count);
    },
  );

  test('unclassified death is not reclaimable and stale events cannot mark a player', () async {
    final p = await create();
    final subscription = p.stream.error.listen((_) {});
    try {
      final ready = open(p);
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'prepared'});
      await ready;
      await emit({
        'session': 6,
        'event': 'error',
        'error': 'native_process_died',
        'processReclaimed': true,
      });
      expect(p.processReclaimed, false);
      await emit({'event': 'error', 'error': 'native_process_died'});
      expect(p.processReclaimed, false);
    } finally {
      await subscription.cancel();
      await p.dispose();
    }
  });

  test(
    'live source carries its mode, rejects seek, and VOD clears live state',
    () async {
      final p = await create();
      try {
        final ready = p.openSource(
          NetworkSource(
            videoSource: 'https://example.invalid/live.flv?test=1',
            audioSource: null,
          ),
          userAgent: 'test',
          referer: '',
          audioOnly: false,
          isLive: true,
        );
        await Future<void>.delayed(Duration.zero);
        expect(calls.last.arguments['isLive'], true);
        await emit({'event': 'prepared'});
        await ready;
        await expectLater(p.seek(const Duration(seconds: 3)), throwsStateError);
        expect(calls.where((c) => c.method == 'seek'), isEmpty);
        final vod = open(p);
        await Future<void>.delayed(Duration.zero);
        expect(calls.last.arguments['isLive'], false);
        await emit({'event': 'prepared', 'load': 2});
        await vod;
        final seeking = p.seek(const Duration(seconds: 3));
        await Future<void>.delayed(Duration.zero);
        expect(calls.last.method, 'seek');
        await emit({
          'event': 'seek_complete',
          'load': 2,
          'position': 3000,
          'target': 3000,
          'duration': 10000,
          'accurate': true,
          'timedOut': false,
        });
        await seeking;
      } finally {
        await p.dispose();
      }
    },
  );

  test(
    'live rejects split sources and initial seek before sending native open',
    () async {
      final p = await create();
      try {
        for (final split in [false, true]) {
          await expectLater(
            p.openSource(
              NetworkSource(
                videoSource: 'https://example.invalid/live',
                audioSource: split ? 'https://example.invalid/audio' : null,
              ),
              userAgent: 'test',
              referer: '',
              audioOnly: false,
              isLive: true,
              position: split ? null : const Duration(seconds: 3),
            ),
            throwsArgumentError,
          );
        }
        expect(calls.where((c) => c.method == 'open'), isEmpty);
      } finally {
        await p.dispose();
      }
    },
  );

  Future<void> failProbe({
    String detail = 'open_video_probe_IOException',
    bool always = false,
  }) async {
    var opens = 0;
    messenger.setMockMethodCallHandler(commands, (call) async {
      calls.add(call);
      if (call.method == 'open' && (++opens == 1 || always)) {
        throw PlatformException(code: 'native_command_failed', details: detail);
      }
      return switch (call.method) {
        'availability' => {'available': true},
        'create' => {'session': 7},
        _ => null,
      };
    });
  }

  NetworkSource probeSource({
    String url = 'old',
    int id = 80,
    Future<NetworkSource?> Function(NetworkSource)? refresh,
    void Function(NetworkSource, NetworkSource)? committed,
  }) => NetworkSource(
    videoSource: 'https://example.invalid/$url',
    audioSource: null,
    videoTrack: DashTrack(
      source: 'https://example.invalid/$url',
      id: id,
      bandwidth: 99,
      urls: [],
    ),
    refresh: refresh,
    onRefreshed: committed,
  );

  test('expired probe refreshes once, retains controls and commits after preparation', () async {
    await failProbe();
    final p = await create();
    var commits = 0;
    final opening = p.openSource(
      probeSource(
        refresh: (_) async => probeSource(url: 'fresh'),
        committed: (_, next) {
          commits++;
          expect(next.videoSource, endsWith('/fresh'));
        },
      ),
      position: const Duration(seconds: 7),
      userAgent: 'same-UA',
      referer: 'same-referer',
      audioOnly: true,
      loudnessMode: 'normal',
      loudnessValues: [1, 2, 3],
      loudnessScenes: {'normal_target_i': '-14'},
    );
    await Future<void>.delayed(Duration.zero);
    final opens = calls.where((c) => c.method == 'open').toList();
    expect(opens.length, 2);
    final first = Map<String, dynamic>.from(opens.first.arguments as Map);
    final second = Map<String, dynamic>.from(opens.last.arguments as Map);
    expect(second['load'], 2);
    expect(second['source'], endsWith('/fresh'));
    for (final key in ['source', 'sourceTrack', 'load']) {
      first.remove(key);
      second.remove(key);
    }
    expect(second, first);
    expect(commits, 0);
    await emit({'event': 'prepared', 'load': 1});
    expect(commits, 0);
    await emit({'event': 'prepared', 'load': 2});
    await emit({
      'event': 'seek_complete',
      'load': 2,
      'position': 7000,
      'target': 7000,
      'duration': 60000,
      'timedOut': false,
      'accurate': true,
    });
    await opening;
    expect(commits, 1);
    expect(p.resourceUnavailable, false);
    await p.dispose();
  });

  for (final mode in ['missing', 'mismatch', 'repeated', 'exception']) {
    test(
      'probe refresh $mode fails without stale-source fallback or endless retry',
      () async {
        await failProbe(always: true);
        final p = await create();
        var requests = 0;
        final source = probeSource(
          refresh: (_) async {
            requests++;
            if (mode == 'exception') throw StateError('private URL');
            return mode == 'missing'
                ? null
                : probeSource(url: 'fresh', id: mode == 'mismatch' ? 120 : 80);
          },
        );
        await expectLater(
          p.openSource(source, userAgent: '', referer: '', audioOnly: false),
          throwsA(predicate(OfficialPlayer.isResourceFailure)),
        );
        expect(requests, 1);
        expect(
          calls.where((c) => c.method == 'open').length,
          mode == 'repeated' ? 2 : 1,
        );
        expect(p.resourceUnavailable, true);
        expect(p.state.buffering, false);
        await p.dispose();
      },
    );
  }

  test(
    'dispose interrupts a pending initial source refresh without a late open',
    () async {
      await failProbe();
      final p = await create();
      final supplier = Completer<NetworkSource?>();
      final opening = p.openSource(
        probeSource(refresh: (_) => supplier.future),
        userAgent: '',
        referer: '',
        audioOnly: true,
      );
      final stopped = expectLater(opening, throwsStateError);
      await Future<void>.delayed(Duration.zero);
      await p.dispose();
      await stopped;
      supplier.complete(probeSource(url: 'late'));
      await Future<void>.delayed(Duration.zero);
      expect(calls.where((c) => c.method == 'open').length, 1);
    },
  );

  test('unsupported format does not invoke network refresh or change failure classification', () async {
    await failProbe(detail: 'open_video_probe_IllegalArgumentException');
    final p = await create();
    var requests = 0;
    await expectLater(
      p.openSource(
        probeSource(
          refresh: (_) async {
            requests++;
            return null;
          },
        ),
        userAgent: '',
        referer: '',
        audioOnly: true,
      ),
      throwsA(
        isA<PlatformException>().having(
          (e) => e.code,
          'code',
          'native_command_failed',
        ),
      ),
    );
    expect(requests, 0);
    expect(p.resourceUnavailable, false);
    await p.dispose();
  });

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
    expect(p.resourceUnavailable, true);
    expect(
      OfficialPlayer.isResourceFailure(
        PlatformException(code: 'native_process_died'),
      ),
      false,
    );
    await subscription.cancel();
    final retry = open(p);
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'prepared', 'load': 2});
    await retry;
    expect(p.resourceUnavailable, false);
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

  test(
    'native rate corrections reach paused clients and repeated requests',
    () async {
      final player = await create();
      final opening = open(player);
      await Future<void>.delayed(Duration.zero);
      await emit({'event': 'prepared'});
      await opening;
      final rates = <double>[];
      final closed = Completer<void>();
      final subscription = player.rateUpdates.listen(
        rates.add,
        onDone: closed.complete,
      );
      const nativeState = <String, Object?>{
        'event': 'state',
        'position': 2000,
        'duration': 30000,
        'playing': false,
        'speed': 3.0,
        'width': 640,
        'height': 360,
        'bufferedPercent': 80,
      };
      await player.setRate(4);
      await emit(nativeState);
      await emit(nativeState);
      expect(player.state.rate, 3);
      expect(rates, [3]);
      await emit({...nativeState, 'load': 0, 'speed': 2});
      await emit({...nativeState, 'session': 99, 'speed': 2});
      expect(player.state.rate, 3);
      expect(rates, [3]);
      // A repeated request must never replace the confirmed 3x with an unconfirmed 4x.
      await player.setRate(4);
      await emit(nativeState);
      expect(rates, [3]);
      await emit({...nativeState, 'speed': 1.0});
      expect(rates, [3, 1]);
      await player.setRate(1.3);
      await emit({...nativeState, 'speed': 1.2999999523162842});
      expect(player.state.rate, 1.3);
      expect(rates, [3, 1, 1.3]);
      await emit({...nativeState, 'speed': 2.0});
      expect(rates, [3, 1, 1.3, 2]);
      await player.dispose();
      await closed.future;
      await subscription.cancel();
    },
  );

  test('actual output and speed are distinct from policy and requests', () async {
    final player = await create();
    final opening = open(player);
    await Future<void>.delayed(Duration.zero);
    await emit({'event': 'prepared'});
    await opening;
    const state = <String, Object?>{
      'event': 'state',
      'position': 1000,
      'duration': 30000,
      'playing': true,
      'speed': 1.0,
      'width': 640,
      'height': 360,
      'bufferedPercent': 50,
      'dolbyEndpoint': 2,
    };
    await player.setRate(2);
    await emit({...state, 'speedPending': true});
    expect(player.requestedRate, 2);
    expect(player.state.rate, 1);
    expect(player.speedPending, true);
    expect(player.outputDescription, contains('尚未取得实际路由'));
    // Releasing a long press must submit 1x even before 2x reaches the output.
    await player.setRate(1);
    expect((calls.last.arguments as Map)['speed'], 1);
    await emit({
      ...state,
      'speedVerified': true,
      'speedError': -22,
      'actualRouteVerified': true,
      'actualRouteType': 2,
      'outputSampleRate': 48000,
      'outputChannels': 2,
      'outputEncoding': 2,
    });
    expect(player.speedDescription, contains('引擎生效'));
    expect(player.speedDescription, contains('拒绝'));
    expect(
      player.outputDescription,
      contains('扬声器 / 48000 Hz / 2 声道 / PCM 16位'),
    );
    await emit(state);
    expect(player.actualRouteVerified, false);
    expect(player.outputSampleRate, null);
    expect(player.speedVerified, false);
    await player.dispose();
  });

  test(
    'rate request bounds and command failures retain valid state',
    () async {
      final player = await create();
      for (final rate in [.25, .5, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0]) {
        expect(OfficialPlayer.supportsRate(rate), true);
        await player.setRate(rate);
        expect((calls.last.arguments as Map)['speed'], rate);
        expect(player.requestedRate, rate);
        expect(player.state.rate, 1);
      }
      final before = calls.length;
      for (final rate in [
        double.nan,
        double.infinity,
        0.0,
        -.5,
        .24,
        4.01,
        6.0,
      ]) {
        expect(OfficialPlayer.supportsRate(rate), false);
        await expectLater(player.setRate(rate), throwsArgumentError);
      }
      expect(calls.length, before);
      expect(player.requestedRate, 4.0);
      expect(player.state.rate, 1);
      messenger.setMockMethodCallHandler(commands, (call) async {
        if (call.method == 'speed') {
          throw PlatformException(code: 'speed_failed');
        }
        return null;
      });
      await expectLater(player.setRate(1.0), throwsA(isA<PlatformException>()));
      expect(player.requestedRate, 4.0);
      expect(player.state.rate, 1);
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
