import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';

/// The Android process owns audio, video and their clock; Dart only sends controls.
class OfficialPlayer extends PlatformPlayer {
  OfficialPlayer._(this.fallback)
    : super(configuration: const PlayerConfiguration());

  static const _channel = MethodChannel('piliplus/official_player');
  static const _eventChannel = EventChannel('piliplus/official_player/events');
  static String failureCode(Object error) => switch (error) {
    PlatformException(:final code, :final details)
        when details is String &&
            RegExp(r'^[a-zA-Z0-9_/-]{1,200}$').hasMatch(details) =>
      '$code ($details)',
    PlatformException(:final code) => code,
    TimeoutException() => 'prepare_timeout',
    _ => error.runtimeType.toString(),
  };
  final Future<PlatformPlayer> Function(String reason) fallback;
  late StreamSubscription<dynamic> _events;
  int session = 0;
  int _load = 0;
  Completer<void>? _preparing;
  bool _nativePrepared = false;
  int? _initialSeekTarget;
  Future<void> _seekTail = Future<void>.value();
  Completer<void>? _seekResult;
  int? _seekTarget;
  bool _seekUncertain = false;
  Future<void>? _disposing;
  bool _disposed = false;
  bool _dead = false;
  bool _ducked = false;
  int _volumeRevision = 0;
  double _outputVolume(double volume) =>
      math.pow(volume / 100, 3).toDouble() * (_ducked ? .5 : 1);
  String audioCodec = '';
  String get audioDescription =>
      '实际音频编码：${audioCodec.isEmpty ? '等待当前音源信息' : audioCodec}';
  String loudnessState = 'disabled';
  String requestedAudioFilter = '';
  String get loudnessDescription => switch (loudnessState) {
    'requested_with_scenes' => '官方响度：已请求场景目标处理',
    'requested_without_scenes' => '官方响度：已请求音源目标处理',
    'disabled' => '官方响度：关闭',
    'pending' => '官方响度：等待当前音源准备',
    'missing_metadata' => '官方响度：缺少当前音源元数据',
    'zero_excluded_by_official_gate' => '官方响度：零值被官方启用条件排除',
    'below_official_threshold' => '官方响度：低于官方启用门限',
    _ => '官方响度：元数据无效，未请求处理',
  };
  int? audioSession;
  int? dolbyEndpoint;
  String routeEvidence = 'unavailable';
  bool dolbyControlsApplicable = false;
  String get routeDescription {
    final endpoint = switch (dolbyEndpoint) {
      1 => '扬声器',
      2 => '耳机',
      _ => '未知',
    };
    final evidence = switch (routeEvidence) {
      'media_policy_prediction' => '系统路由预测',
      'connected_devices_inference' => '连接设备推定',
      'test_override' => '测试指定',
      _ => '路由不可用',
    };
    final branch = audioCodec.isNotEmpty && !dolbyControlsApplicable
        ? '；当前音轨不使用 Dolby 处理'
        : '';
    return '端点配置：$endpoint（$evidence）$branch';
  }

  static Future<OfficialPlayer> create(
    Future<PlatformPlayer> Function(String reason) fallback,
  ) async {
    final availability = await _channel.invokeMapMethod<String, dynamic>(
      'availability',
    );
    if (availability?['available'] != true) {
      throw PlatformException(
        code: availability?['reason'] as String? ?? 'unavailable',
      );
    }
    final player = OfficialPlayer._(fallback);
    // No playback events are produced until open, after session has been assigned.
    player._events = _eventChannel.receiveBroadcastStream().listen(
      player._event,
    );
    try {
      final result = await _channel.invokeMapMethod<String, dynamic>('create');
      player.session = result!['session'] as int;
      player.completer.complete();
      return player;
    } catch (_) {
      await player._events.cancel();
      await player._closeStreams();
      rethrow;
    }
  }

  Future<void> _send(String method, [Map<String, Object?> data = const {}]) {
    if (_disposed || (_disposing != null && method != 'dispose')) {
      return Future.error(StateError('Official player is disposed'));
    }
    return _channel.invokeMethod<void>(method, {'session': session, ...data});
  }

  Future<void> openSource(
    DataSource source, {
    Duration? position,
    required String userAgent,
    required String referer,
    required bool audioOnly,
    String loudnessMode = 'off',
    List<num?>? loudnessValues,
    Map<String, String>? loudnessScenes,
  }) async {
    if (_disposed || _disposing != null) {
      throw StateError('Official player is disposing or disposed');
    }
    if (_preparing != null) {
      throw StateError('An official source is already preparing');
    }
    ++_load;
    _cancelSeek(StateError('Source changed during seek'));
    _seekTail = Future<void>.value();
    _seekUncertain = false;
    _nativePrepared = false;
    _initialSeekTarget = (position?.inMilliseconds ?? 0) > 0
        ? position!.inMilliseconds
        : null;
    final ready = _preparing = Completer<void>();
    // Register an error observer before the native reply; preparation can fail first.
    final prepared = ready.future;
    unawaited(prepared.catchError((Object _) {}));
    state.completed = false;
    state.position = position ?? Duration.zero;
    state.duration = Duration.zero;
    state.buffer = Duration.zero;
    state.playing = false;
    completedController.add(false);
    state.buffering = true;
    loudnessState = loudnessMode == 'off' ? 'disabled' : 'pending';
    requestedAudioFilter = '';
    audioCodec = '';
    bufferingController.add(true);
    try {
      await _send('open', {
        'load': _load,
        'audioOnly': audioOnly,
        'source': audioOnly && source.audioSource != null
            ? source.audioSource
            : source.videoSource,
        if (!audioOnly) 'audio': source.audioSource,
        'position': position?.inMilliseconds ?? 0,
        'speed': state.rate,
        'volume': _outputVolume(state.volume),
        'loudnessMode': loudnessMode,
        'loudnessValues': loudnessValues,
        'loudnessScenes': loudnessScenes,
        'userAgent': userAgent,
        'referer': referer,
      });
      await prepared.timeout(const Duration(seconds: 25));
    } finally {
      _preparing = null;
    }
  }

  void _event(dynamic raw) {
    if (_disposed || raw is! Map || raw['session'] != session) return;
    if (raw.containsKey('load') && raw['load'] != _load) return;
    final event = Map<String, dynamic>.from(raw);
    switch (event['event']) {
      case 'prepared':
        _nativePrepared = true;
        _finishPreparation();
        state.buffering = false;
        bufferingController.add(false);
      case 'error':
        final code = event['error'] as String? ?? 'native_error';
        _nativePrepared = false;
        _cancelSeek(PlatformException(code: code));
        _dead = code == 'native_process_died' || code == 'binding_died';
        if (_preparing case final pending? when !pending.isCompleted) {
          pending.completeError(
            PlatformException(code: code, details: event['diagnostic']),
          );
        } else {
          errorController.add(
            'official:${failureCode(PlatformException(code: code, details: event['diagnostic']))}',
          );
        }
      case 'seek_complete':
        final target = event['target'];
        final position = event['position'];
        final duration = event['duration'];
        if (target is! int ||
            position is! int ||
            position < 0 ||
            duration is! int ||
            event['accurate'] != true ||
            event['timedOut'] is! bool) {
          return;
        }
        // The native player reports the duration when a request exceeds EOF.
        bool matches(int? requested) =>
            requested != null &&
            target ==
                (duration > 0 ? math.min(requested, duration) : requested);
        final initial = matches(_initialSeekTarget);
        if (!initial && !matches(_seekTarget)) return;
        state.position = Duration(milliseconds: position);
        positionController.add(state.position);
        final error = event['timedOut'] == true
            ? PlatformException(code: 'official_seek_timeout')
            : null;
        if (initial) {
          _initialSeekTarget = null;
          if (error != null) {
            if (_preparing case final pending? when !pending.isCompleted) {
              pending.completeError(error);
            }
          } else {
            _finishPreparation();
          }
        } else if (_seekResult case final pending? when !pending.isCompleted) {
          if (error == null) {
            pending.complete();
          } else {
            pending.completeError(error);
          }
        }
      case 'completed':
        state.completed = true;
        completedController.add(true);
        state.playing = false;
        playingController.add(false);
      case 'buffering':
        state.buffering = event['buffering'] == true;
        bufferingController.add(state.buffering);
      case 'state':
        state.position = Duration(
          milliseconds: (event['position'] as num).toInt(),
        );
        state.duration = Duration(
          milliseconds: (event['duration'] as num).toInt(),
        );
        final playing = event['playing'] == true;
        final playingChanged = state.playing != playing;
        state.playing = playing;
        state.rate = (event['speed'] as num).toDouble();
        state.width = (event['width'] as num).toInt();
        state.height = (event['height'] as num).toInt();
        state.buffer = Duration(
          milliseconds:
              (state.duration.inMilliseconds *
                      (event['bufferedPercent'] as num) /
                      100)
                  .round(),
        );
        state.audioParams = AudioParams(
          sampleRate: (event['sampleRate'] as num?)?.toInt(),
          channels: event['channelLayout'] as String?,
        );
        audioCodec = event['audioCodec'] as String? ?? '';
        loudnessState = event['loudnessState'] as String? ?? 'disabled';
        requestedAudioFilter = event['requestedAudioFilter'] as String? ?? '';
        audioSession = event['audioSession'] as int?;
        dolbyEndpoint = (event['dolbyEndpoint'] as num?)?.toInt();
        dolbyControlsApplicable = event['dolbyControlsApplicable'] == true;
        routeEvidence = event['routeEvidence'] as String? ?? 'unavailable';
        positionController.add(state.position);
        durationController.add(state.duration);
        if (playingChanged) playingController.add(playing);
        bufferController.add(state.buffer);
        sizeController.add((state.width, state.height));
        audioParamsController.add(state.audioParams);
    }
  }

  void _finishPreparation() {
    if (_nativePrepared && _initialSeekTarget == null) {
      if (_preparing case final pending? when !pending.isCompleted) {
        pending.complete();
      }
    }
  }

  void _cancelSeek(Object error) {
    if (_seekResult case final pending? when !pending.isCompleted) {
      pending.completeError(error);
    }
  }

  @override
  Future<void> play() => _send('play');
  @override
  Future<void> pause() => _send('pause');
  @override
  Future<void> playOrPause() => state.playing ? pause() : play();
  @override
  Future<void> seek(Duration duration) {
    final load = _load;
    final result = _seekTail.then((_) => _seek(duration, load));
    // The native callback has no request ID, so allow only one outstanding seek.
    _seekTail = result.catchError((Object _) {});
    return result;
  }

  Future<void> _seek(Duration duration, int load) async {
    if (_disposed ||
        _disposing != null ||
        _dead ||
        load != _load ||
        !_nativePrepared ||
        _preparing != null) {
      throw StateError('Seek requires the current prepared player');
    }
    if (duration.isNegative) throw ArgumentError.value(duration, 'duration');
    if (_seekUncertain) {
      throw PlatformException(code: 'official_seek_result_unavailable');
    }
    final pending = _seekResult = Completer<void>();
    _seekTarget = duration.inMilliseconds;
    unawaited(pending.future.catchError((Object _) {}));
    try {
      await _send('seek', {'position': _seekTarget});
      await pending.future.timeout(
        const Duration(seconds: 20),
        onTimeout: () {
          // Do not let a late callback complete a later request for the same target.
          _seekUncertain = true;
          throw PlatformException(code: 'official_seek_result_unavailable');
        },
      );
    } finally {
      if (identical(_seekResult, pending)) {
        _seekResult = null;
        _seekTarget = null;
      }
    }
  }

  @override
  Future<void> setRate(double rate) async {
    if (!rate.isFinite || rate < .25 || rate > 4) {
      throw ArgumentError.value(rate, 'rate');
    }
    await _send('speed', {'speed': rate});
    state.rate = rate;
  }

  @override
  Future<void> setVolume(double volume) async {
    if (!volume.isFinite || volume < 0) {
      throw ArgumentError.value(volume, 'volume');
    }
    if (volume > 100) {
      await (await fallback('播放器音量超过 100%')).setVolume(volume);
      return;
    }
    final previous = state.volume;
    final revision = ++_volumeRevision;
    state.volume = volume;
    try {
      await _send('volume', {'volume': _outputVolume(volume)});
    } catch (_) {
      if (revision == _volumeRevision) state.volume = previous;
      rethrow;
    }
  }

  Future<void> setDucked(bool ducked) async {
    if (_ducked == ducked) return;
    final previous = _ducked;
    final revision = ++_volumeRevision;
    _ducked = ducked;
    try {
      await _send('volume', {'volume': _outputVolume(state.volume)});
    } catch (_) {
      if (revision == _volumeRevision) _ducked = previous;
      rethrow;
    }
  }

  @override
  Future<void> setSubtitleTrack(SubtitleTrack track) async {
    if (track.id == 'no') return;
    await (await fallback('字幕渲染')).setSubtitleTrack(track);
  }

  @override
  Future<ui.Image?> screenshot() async => (await fallback('视频截图')).screenshot();

  Future<void> _closeStreams() => super.dispose();
  @override
  Future<void> dispose() => _disposing ??= _dispose()
      .then((_) => super.dispose())
      .onError<Object>((error, stack) {
        // A later Binder death can confirm a previously timed-out native stop.
        _disposing = null;
        Error.throwWithStackTrace(error, stack);
      });
  Future<void> _dispose() async {
    _cancelSeek(StateError('Playback disposed during seek'));
    if (!_dead) {
      try {
        await _send('dispose');
      } on PlatformException catch (error) {
        if (error.code != 'native_process_died' &&
            error.code != 'binding_died') {
          rethrow;
        }
      }
    }
    _disposed = true;
    if (_preparing case final pending? when !pending.isCompleted) {
      pending.completeError(StateError('Playback disposed during preparation'));
    }
    await _events.cancel();
  }
}
