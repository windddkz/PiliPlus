import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:PiliPlus/models/common/video/video_decode_type.dart';
import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';

import 'package:PiliPlus/plugin/pl_player/text_subtitles.dart';

/// The Android process owns audio, video and their clock; Dart only sends controls.
class OfficialPlayer extends PlatformPlayer {
  static bool supportsRate(double rate) =>
      rate.isFinite && rate >= .25 && rate <= 4;

  final _rateUpdates = StreamController<double>.broadcast();
  Stream<double> get rateUpdates => _rateUpdates.stream;
  final _qualityUpdates = StreamController<(int, bool)>.broadcast();
  Stream<(int, bool)> get qualityUpdates => _qualityUpdates.stream;
  int? videoQuality;
  bool automaticVideo = false;
  Future<void> _videoControlTail = Future<void>.value();
  Completer<void>? _videoControlResult;
  int _videoControlSerial = 0;
  int? _videoControlRequest;
  bool _videoControlAutomatic = false;
  bool _videoControlUncertain = false;

  void _acceptQuality(dynamic quality, dynamic automatic) {
    if (quality is! int || quality < 0 || automatic is! bool) return;
    // Native can confirm the mode before reporting the first actual quality.
    final actual = quality > 0 ? quality : videoQuality;
    if (videoQuality == actual && automaticVideo == automatic) return;
    videoQuality = actual;
    automaticVideo = automatic;
    if (actual != null) _qualityUpdates.add((actual, automatic));
    final source = _networkSource;
    if (source != null) source.onVideoQuality?.call(source, actual, automatic);
  }

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
  NetworkSource? _networkSource;
  bool _isLive = false;
  bool _audioOnly = false;
  int? _refreshRequest;
  (int, Completer<void>)? _resourceApplied;
  int _lastRefreshRequest = 0;
  (int, NetworkSource, NetworkSource)? _pendingResource;
  int _refreshAttempts = 0;
  Duration _refreshPosition = Duration.zero;
  static bool isResourceFailure(Object error) =>
      error is PlatformException && error.code == 'resource_unavailable';
  Completer<void>? _preparing;
  bool get isPreparing => _preparing != null;
  bool _nativePrepared = false;
  bool resourceUnavailable = false;
  bool initialSeekTimedOut = false;
  (int, double, double, int, int, bool)? _videoTransform;
  int? _initialSeekTarget;
  Future<void> _seekTail = Future<void>.value();
  Completer<void>? _seekResult;
  int? _seekTarget;
  bool _seekUncertain = false;
  Future<void>? _disposing;
  bool _disposed = false;
  bool _dead = false;
  bool get processDead => _dead;
  bool processReclaimed = false;
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
    'dolby_codec_bypass' => '官方响度：杜比音轨沿用官方跳过规则',
    'pending' => '官方响度：等待当前音源准备',
    'missing_metadata' => '官方响度：缺少当前音源元数据',
    'zero_excluded_by_official_gate' => '官方响度：零值被官方启用条件排除',
    'below_official_threshold' => '官方响度：低于官方启用门限',
    _ => '官方响度：元数据无效，未请求处理',
  };
  TextSubtitles? _subtitles;
  int _subtitleRevision = 0;

  void _updateSubtitle() {
    final next = Subtitle.raw(_subtitles?.at(state.position) ?? '');
    if (state.subtitle == next) return;
    state.subtitle = next;
    subtitleController.add(next);
  }

  double requestedRate = 1;
  bool speedVerified = false;
  bool speedPending = false;
  int speedError = 0;
  bool actualRouteVerified = false;
  int? actualRouteType, outputSampleRate, outputChannels, outputEncoding;
  String get speedDescription =>
      '倍速：请求 $requestedRate，${speedVerified ? "引擎生效" : "播放器报告"} ${state.rate}'
      '${speedPending ? "（切换中）" : ""}${speedError != 0 ? "（音频引擎拒绝本次切换）" : ""}';
  String get outputDescription {
    final device = actualRouteVerified
        ? switch (actualRouteType) {
            1 => '听筒',
            2 => '扬声器',
            3 || 4 => '有线耳机',
            7 || 8 || 26 || 27 || 30 => '蓝牙',
            11 || 12 || 22 => 'USB',
            _ => '设备类型 $actualRouteType',
          }
        : '尚未取得实际路由';
    final format = switch (outputEncoding) {
      2 => 'PCM 16位',
      4 => 'PCM float',
      21 => 'PCM 24位',
      22 => 'PCM 32位',
      null => null,
      _ => '格式 $outputEncoding',
    };
    return '实际输出：$device'
        '${outputSampleRate == null ? "" : " / $outputSampleRate Hz / $outputChannels 声道 / $format"}';
  }

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

  static Future<void>? _pendingDisposal;
  static final _stoppingPlayers = <OfficialPlayer>{};
  static final _livePlayers = <OfficialPlayer>{};
  static Future<void>? _idleWarmup;
  static int _creatingPlayers = 0;
  static int _preloadScopeRevision = 0;
  static String _preloadScope = '${DateTime.now().microsecondsSinceEpoch}_0';
  static String? _preloadCacheScope;

  static void invalidatePreloads({String? cacheScope}) {
    _preloadCacheScope = cacheScope;
    _preloadScope =
        '${DateTime.now().microsecondsSinceEpoch}_${++_preloadScopeRevision}';
    unawaited(enablePreloads(false).catchError((Object _) {}));
  }

  static Future<void> warmUpWhenIdle() {
    if (_idleWarmup case final pending?) return pending;
    if (_creatingPlayers != 0 || _livePlayers.isNotEmpty) {
      return Future.value();
    }
    final pending = _idleWarmup = _warmUpAndRelease().then((_) {});
    void clear() {
      if (identical(_idleWarmup, pending)) _idleWarmup = null;
    }

    unawaited(pending.then((_) => clear(), onError: (Object _) => clear()));
    return pending;
  }

  static Future<String> preloadWhenIdle(
    NetworkSource source, {
    required String candidate,
    required Duration position,
    required String userAgent,
    required String referer,
  }) async {
    while (_idleWarmup != null) {
      final pending = _idleWarmup!;
      try {
        await pending;
      } catch (_) {}
    }
    if (_creatingPlayers != 0) return 'deferred';
    final active = _livePlayers
        .where((p) => p._disposing == null && !p._dead && !p._disposed)
        .firstOrNull;
    if (active != null) {
      return active._preload(source, position, userAgent, referer, candidate);
    }
    var state = 'deferred';
    final pending = _idleWarmup =
        _warmUpAndRelease(
          preload: source,
          candidate: candidate,
          position: position,
          userAgent: userAgent,
          referer: referer,
        ).then((value) {
          state = value;
        });
    try {
      await pending;
    } finally {
      if (identical(_idleWarmup, pending)) _idleWarmup = null;
    }
    return state;
  }

  Future<String> _preload(
    NetworkSource source,
    Duration position,
    String userAgent,
    String referer,
    String? candidate,
  ) async {
    if (_disposed || _disposing != null || _dead) return 'deferred';
    return await _channel.invokeMethod<String>('preload', {
          'session': session,
          'preloadCandidate': candidate,
          ..._sourceArguments(source, false),
          'position': position.inMilliseconds,
          'userAgent': userAgent,
          'referer': referer,
        }) ??
        'deferred';
  }

  static Future<void> enablePreloads(
    bool enabled, {
    List<String> candidates = const [],
    int priorityCount = 6,
  }) async {
    while (_idleWarmup != null) {
      try {
        await _idleWarmup;
      } catch (_) {}
    }
    if (_creatingPlayers != 0) return;
    final active = _livePlayers
        .where((p) => p._disposing == null && !p._dead && !p._disposed)
        .firstOrNull;
    if (active != null) {
      await active._send('preloadControl', {
        'enabled': enabled,
        'candidates': candidates,
        'priorityCount': priorityCount,
      });
      return;
    }
    final pending = _idleWarmup = _warmUpAndRelease(
      enablePreloads: enabled,
      candidates: candidates,
      priorityCount: priorityCount,
    ).then((_) {});
    try {
      await pending;
    } finally {
      if (identical(_idleWarmup, pending)) _idleWarmup = null;
    }
  }

  static Future<String> _warmUpAndRelease({
    NetworkSource? preload,
    String? candidate,
    List<String> candidates = const [],
    int priorityCount = 6,
    Duration position = Duration.zero,
    String userAgent = '',
    String referer = '',
    bool? enablePreloads,
  }) async {
    OfficialPlayer? player;
    try {
      player = await _create((reason) => Future.error(StateError(reason)));
      await player.warmUp();
      if (enablePreloads != null) {
        await player._send('preloadControl', {
          'enabled': enablePreloads,
          'candidates': candidates,
          'priorityCount': priorityCount,
        });
      }
      return preload == null
          ? 'idle'
          : await player._preload(
              preload,
              position,
              userAgent,
              referer,
              candidate,
            );
    } finally {
      await player?.dispose();
    }
  }

  static Future<void> waitForDisposal() async {
    await _pendingDisposal;
    for (final player in _stoppingPlayers.toList()) {
      if (!player._dead) throw PlatformException(code: 'stop_unconfirmed');
      await player.dispose();
    }
  }

  // The Android service owns one session, shared by video and audio pages.
  static void deferCreationUntil(Future<void> disposal) {
    final previous = _pendingDisposal;
    final barrier = previous == null
        ? disposal
        : Future.wait<void>([previous, disposal]).then((_) {});
    _pendingDisposal = barrier;
    void clear() {
      if (identical(_pendingDisposal, barrier)) _pendingDisposal = null;
    }

    unawaited(
      barrier.then(
        (_) => clear(),
        onError: (Object _, StackTrace _) => clear(),
      ),
    );
  }

  static Future<OfficialPlayer> create(
    Future<PlatformPlayer> Function(String reason) fallback,
  ) async {
    _creatingPlayers++;
    try {
      // Optional preparation may fail; an unconfirmed stop still blocks _create.
      try {
        await _idleWarmup;
      } catch (_) {}
      return await _create(fallback);
    } finally {
      _creatingPlayers--;
    }
  }

  static Future<OfficialPlayer> _create(
    Future<PlatformPlayer> Function(String reason) fallback,
  ) async {
    await waitForDisposal();
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
      _livePlayers.add(player);
      return player;
    } catch (_) {
      await player._events.cancel();
      await player._closeStreams();
      rethrow;
    }
  }

  static List<VideoDecodeFormatType>? _preferredVideoCodecs;
  static List<VideoDecodeFormatType>? get preferredVideoCodecs =>
      _preferredVideoCodecs;

  Future<void> warmUp() async {
    if (_disposed || _disposing != null) {
      throw StateError('Official player is disposed');
    }
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'warmUp',
      {'session': session},
    );
    if (_disposed || _disposing != null || _dead) return;
    final names = result?['videoCodecs'];
    if (names is! List ||
        names.isEmpty ||
        names.length > VideoDecodeFormatType.values.length ||
        names.toSet().length != names.length ||
        !names.every(
          (name) => VideoDecodeFormatType.values.any((c) => c.name == name),
        )) {
      return;
    }
    _preferredVideoCodecs = List.unmodifiable(
      names.map((name) => VideoDecodeFormatType.values.byName(name as String)),
    );
  }

  Future<void> _send(String method, [Map<String, Object?> data = const {}]) {
    if (_disposed || (_disposing != null && method != 'dispose')) {
      return Future.error(StateError('Official player is disposed'));
    }
    return _channel.invokeMethod<void>(method, {'session': session, ...data});
  }

  static Map<String, Object?> _sourceArguments(
    DataSource source,
    bool audioOnly,
  ) => {
    'preloadScope': _preloadScope,
    if (_preloadCacheScope != null) 'cacheScope': _preloadCacheScope,
    'source': audioOnly && source.audioSource != null
        ? source.audioSource
        : source.videoSource,
    if (!audioOnly) 'audio': source.audioSource,
    if (source is NetworkSource) ...{
      if (source.officialPreloadToken != null)
        'preloadToken': source.officialPreloadToken,
      'sourceTrack': audioOnly && source.audioSource != null
          ? source.audioTrack?.forSource(source.audioSource)
          : source.videoTrack?.forSource(source.videoSource),
      if (!audioOnly)
        'audioTrack': source.audioTrack?.forSource(source.audioSource),
      if (!audioOnly &&
          (source.videoTracks.isNotEmpty || source.automaticVideo)) ...{
        'videoTracks': [
          for (final track in source.videoTracks) track.forSource(track.source),
        ],
        'automaticVideo': source.automaticVideo,
      },
    },
  };

  static bool _isProbeFailure(Object error) =>
      error is PlatformException &&
      error.code == 'native_command_failed' &&
      error.details is String &&
      RegExp(
        r'^open_(video|audio)_probe_(IOException|EOFException|SocketTimeoutException|SocketException|ConnectException|UnknownHostException|SSLException|SSLHandshakeException)$',
      ).hasMatch(error.details as String);

  Future<void> openSource(
    DataSource source, {
    Duration? position,
    required String userAgent,
    required String referer,
    required bool audioOnly,
    bool isLive = false,
    bool flipX = false,
    bool flipY = false,
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
    if (isLive &&
        (source is! NetworkSource ||
            source.audioSource != null ||
            (position != null && position != Duration.zero))) {
      throw ArgumentError(
        'Live playback requires one network source without seeking',
      );
    }
    _cancelVideoControl(StateError('Source changed during quality switch'));
    _videoControlTail = Future<void>.value();
    _videoControlUncertain = false;
    ++_subtitleRevision;
    _subtitles = null;
    _updateSubtitle();
    var load = ++_load;
    _isLive = isLive;
    final original = source;
    _networkSource = source is NetworkSource ? source : null;
    videoQuality = null;
    automaticVideo = false;
    _audioOnly = audioOnly;
    _refreshRequest = null;
    _lastRefreshRequest = 0;
    _pendingResource = null;
    _refreshAttempts = 0;
    _refreshPosition = position ?? Duration.zero;
    _cancelSeek(StateError('Source changed during seek'));
    _seekTail = Future<void>.value();
    _seekUncertain = false;
    _nativePrepared = false;
    resourceUnavailable = false;
    initialSeekTimedOut = false;
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
    state.width = state.height = 0;
    sizeController.add((0, 0));
    state.buffer = Duration.zero;
    state.playing = false;
    completedController.add(false);
    state.buffering = true;
    loudnessState = loudnessMode == 'off' ? 'disabled' : 'pending';
    requestedAudioFilter = '';
    audioCodec = '';
    bufferingController.add(true);
    try {
      for (var attempt = 0; ; attempt++) {
        try {
          await _send('open', {
            'load': load,
            'audioOnly': audioOnly,
            'isLive': isLive,
            'flipX': flipX,
            'flipY': flipY,
            ..._sourceArguments(source, audioOnly),
            'position': position?.inMilliseconds ?? 0,
            'speed': requestedRate,
            'volume': _outputVolume(state.volume),
            'loudnessMode': loudnessMode,
            'loudnessValues': loudnessValues,
            'loudnessScenes': loudnessScenes,
            'userAgent': userAgent,
            'referer': referer,
          });
          break;
        } catch (error) {
          if (source is! NetworkSource || !_isProbeFailure(error)) rethrow;
          NetworkSource? next;
          if (attempt == 0 && source.refresh != null) {
            try {
              next = await Future.any<NetworkSource?>([
                source.refresh!(source).timeout(const Duration(seconds: 20)),
                prepared.then((_) => null),
              ]);
            } catch (_) {
              // Do not expose signed URLs or request exceptions in playback errors.
            }
          }
          if (_disposed || _disposing != null || load != _load) {
            throw StateError('Source refresh canceled');
          }
          if (ready.isCompleted) await prepared;
          if (next == null ||
              next.videoTrack?.id != source.videoTrack?.id ||
              next.audioTrack?.id != source.audioTrack?.id) {
            throw PlatformException(code: 'resource_unavailable');
          }
          source = next;
          _networkSource = next;
          load = ++_load;
        }
      }
      await prepared.timeout(const Duration(seconds: 25));
      if (original is NetworkSource && !identical(original, source)) {
        original.onRefreshed?.call(original, _networkSource!);
      }
    } catch (error) {
      if (isResourceFailure(error) && !_disposed && _disposing == null) {
        resourceUnavailable = true;
        state.buffering = false;
        bufferingController.add(false);
      }
      rethrow;
    } finally {
      _preparing = null;
    }
  }

  void _event(dynamic raw) {
    if (_disposed || raw is! Map || raw['session'] != session) return;
    if (raw.containsKey('load') && raw['load'] != _load) return;
    final event = Map<String, dynamic>.from(raw);
    switch (event['event']) {
      case 'video_size':
        state.width = (event['width'] as num).toInt();
        state.height = (event['height'] as num).toInt();
        sizeController.add((state.width, state.height));
      case 'video_control':
        final pending = _videoControlResult;
        if (pending == null ||
            pending.isCompleted ||
            event['requestId'] != _videoControlRequest ||
            event['success'] is! bool ||
            event['automaticVideo'] is! bool) {
          return;
        }
        if (event['success'] == true &&
            event['automaticVideo'] == _videoControlAutomatic) {
          automaticVideo = _videoControlAutomatic;
          pending.complete();
        } else {
          pending.completeError(
            PlatformException(code: 'official_video_switch_failed'),
          );
        }
      case 'quality':
        _acceptQuality(event['quality'], event['automaticVideo']);
      case 'resource_refresh':
        final request = event['requestId'];
        if (request is int &&
            request > _lastRefreshRequest &&
            _disposing == null) {
          _finishResourceApplication(StateError('Resource request replaced'));
          final applied = Completer<void>();
          _resourceApplied = (request, applied);
          unawaited(applied.future.catchError((Object _) {}));
          _lastRefreshRequest = request;
          unawaited(_refreshSource(request, _load));
        }
      case 'resource_updated':
        final pending = _pendingResource;
        if (pending != null &&
            event['requestId'] == pending.$1 &&
            _disposing == null) {
          _pendingResource = null;
          _networkSource = pending.$3;
          pending.$2.onRefreshed?.call(pending.$2, pending.$3);
          if (_resourceApplied?.$1 == pending.$1) _finishResourceApplication();
        }
      case 'resource_error':
        _nativePrepared = false;
        resourceUnavailable = true;
        _refreshRequest = null;
        _pendingResource = null;
        state.playing = false;
        state.buffering = false;
        playingController.add(false);
        bufferingController.add(false);
        final error = PlatformException(code: 'resource_unavailable');
        _cancelSeek(error);
        _cancelVideoControl(error);
        if (_preparing case final pending? when !pending.isCompleted) {
          pending.completeError(error);
        } else {
          errorController.add('official:resource_unavailable');
        }
      case 'prepared':
        _nativePrepared = true;
        _finishPreparation();
        state.buffering = false;
        bufferingController.add(false);
      case 'error':
        final code = event['error'] as String? ?? 'native_error';
        _nativePrepared = false;
        _cancelSeek(PlatformException(code: code));
        _cancelVideoControl(PlatformException(code: code));
        _dead = code == 'native_process_died' || code == 'binding_died';
        processReclaimed =
            code == 'native_process_died' &&
            event['processReclaimed'] == true &&
            _preparing == null;
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
        _updateSubtitle();
        positionController.add(state.position);
        final error = event['timedOut'] == true
            ? PlatformException(code: 'official_seek_timeout')
            : null;
        if (error == null &&
            duration > 0 &&
            position < duration &&
            state.completed) {
          state.completed = false;
          completedController.add(false);
        }
        if (initial) {
          _initialSeekTarget = null;
          // A completed native seek can miss its target without failing the prepared player.
          initialSeekTimedOut = error != null;
          _finishPreparation();
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
        final reportedRate = (event['speed'] as num).toDouble();
        final rate = (reportedRate - requestedRate).abs() < 1e-6
            ? requestedRate
            : reportedRate;
        // Native uses float32; round-trip noise must not turn 1.3x into a long decimal in the UI.
        final rateChanged = (rate - state.rate).abs() > 1e-6;
        state.rate = rate;
        if (rateChanged) _rateUpdates.add(rate);
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
        _acceptQuality(event['videoQuality'], event['automaticVideo']);
        audioCodec = event['audioCodec'] as String? ?? '';
        loudnessState = event['loudnessState'] as String? ?? 'disabled';
        requestedAudioFilter = event['requestedAudioFilter'] as String? ?? '';
        audioSession = event['audioSession'] as int?;
        speedVerified = event['speedVerified'] == true;
        speedPending = event['speedPending'] == true;
        speedError = (event['speedError'] as num?)?.toInt() ?? 0;
        actualRouteVerified = event['actualRouteVerified'] == true;
        actualRouteType = (event['actualRouteType'] as num?)?.toInt();
        outputSampleRate = (event['outputSampleRate'] as num?)?.toInt();
        outputChannels = (event['outputChannels'] as num?)?.toInt();
        outputEncoding = (event['outputEncoding'] as num?)?.toInt();
        dolbyEndpoint = (event['dolbyEndpoint'] as num?)?.toInt();
        dolbyControlsApplicable = event['dolbyControlsApplicable'] == true;
        routeEvidence = event['routeEvidence'] as String? ?? 'unavailable';
        _updateSubtitle();
        positionController.add(state.position);
        durationController.add(state.duration);
        if (playingChanged) playingController.add(playing);
        bufferController.add(state.buffer);
        sizeController.add((state.width, state.height));
        audioParamsController.add(state.audioParams);
    }
  }

  Future<void> _refreshSource(int request, int load) async {
    _refreshRequest = request;
    bool current() =>
        !_disposed &&
        _disposing == null &&
        load == _load &&
        _refreshRequest == request;
    NetworkSource? replacement;
    final source = _networkSource;
    try {
      if (state.position > _refreshPosition) _refreshAttempts = 0;
      _refreshPosition = state.position;
      if (++_refreshAttempts <= 3) {
        if (source != null) replacement = await source.refresh?.call(source);
      }
    } catch (_) {
      // Signed URLs and request exceptions must not be exposed in playback errors.
    }
    if (!current()) return;
    try {
      if (source != null && replacement != null) {
        _pendingResource = (request, source, replacement);
      }
      await _send('resourceUpdate', {
        'load': load,
        'requestId': request,
        'available': replacement != null,
        if (replacement != null) ..._sourceArguments(replacement, _audioOnly),
      });
    } catch (_) {
      if (_pendingResource?.$1 == request) _pendingResource = null;
      if (current() && _resourceApplied?.$1 == request) {
        _finishResourceApplication(
          PlatformException(code: 'resource_update_unconfirmed'),
        );
      }
      // A canceled or expired native request must not affect the next source.
    } finally {
      if (current()) _refreshRequest = null;
    }
  }

  void _finishResourceApplication([Object? error]) {
    final pending = _resourceApplied;
    _resourceApplied = null;
    if (pending == null || pending.$2.isCompleted) return;
    if (error == null) {
      pending.$2.complete();
    } else {
      pending.$2.completeError(error);
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
  Future<void> setMirror(bool horizontal, bool vertical) => _send('mirror', {
    'load': _load,
    'flipX': horizontal,
    'flipY': vertical,
  });
  Future<void> updateVideoTransform(
    double x,
    double y,
    int dx,
    int dy, {
    bool preserveAspect = true,
  }) async {
    if (!_nativePrepared || _disposed || _disposing != null || _dead) return;
    if (!x.isFinite || !y.isFinite || x <= 0 || y <= 0) {
      throw ArgumentError('Invalid video scale');
    }
    final request = (_load, x, y, dx, dy, preserveAspect);
    if (_videoTransform == request) return;
    _videoTransform = request;
    try {
      await _send('viewport', {
        'load': _load,
        'scaleX': x,
        'scaleY': y,
        'offsetX': dx,
        'offsetY': dy,
        'preserveAspect': preserveAspect,
      });
    } catch (error) {
      if (!_disposed && _disposing == null && _load == request.$1) {
        _videoTransform = null;
        errorController.add('official:viewport_${failureCode(error)}');
      }
    }
  }

  @override
  Future<void> pause() async {
    if (processReclaimed) {
      state.playing = false;
      playingController.add(false);
      return;
    }
    await _send('pause');
  }

  @override
  Future<void> playOrPause() => state.playing ? pause() : play();
  @override
  Future<void> seek(Duration duration) {
    if (_isLive) return Future.error(StateError('Live playback cannot seek'));
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

  /// Null enables native automatic quality; a fixed target waits for its terminal callback.
  Future<void> setVideoQuality(int? quality) {
    final load = _load;
    final result = _videoControlTail.then(
      (_) => _setVideoQuality(quality, load),
    );
    _videoControlTail = result.catchError((Object _) {});
    return result;
  }

  Future<void> _setVideoQuality(int? quality, int load) async {
    final source = _networkSource;
    if (_disposed ||
        _disposing != null ||
        _dead ||
        load != _load ||
        !_nativePrepared ||
        _preparing != null ||
        _isLive ||
        _audioOnly ||
        source == null ||
        source.videoTracks.isEmpty) {
      throw StateError('Quality control requires the current prepared ladder');
    }
    if (_videoControlUncertain) {
      throw PlatformException(code: 'official_video_switch_result_unavailable');
    }
    if (quality != null &&
        !source.videoTracks.any((track) => track.id == quality)) {
      throw ArgumentError.value(quality, 'quality');
    }
    final pending = _videoControlResult = Completer<void>();
    final request = _videoControlRequest = ++_videoControlSerial;
    _videoControlAutomatic = quality == null;
    unawaited(pending.future.catchError((Object _) {}));
    try {
      try {
        await _send('videoControl', {
          'load': load,
          'requestId': request,
          'quality': quality ?? 0,
          'automatic': quality == null,
        });
      } catch (_) {
        // A lost reply cannot prove that native did not accept the command.
        if (load == _load && !pending.isCompleted) {
          _videoControlUncertain = true;
        }
        rethrow;
      }
      await pending.future.timeout(
        const Duration(seconds: 20),
        onTimeout: () {
          if (load == _load) _videoControlUncertain = true;
          throw PlatformException(
            code: 'official_video_switch_result_unavailable',
          );
        },
      );
      // Keep the source identity stable until an in-flight URL replacement is applied.
      while (_resourceApplied != null) {
        final updating = _resourceApplied!;
        try {
          await updating.$2.future.timeout(
            const Duration(seconds: 21),
            onTimeout: () => throw PlatformException(
              code: 'official_video_switch_result_unavailable',
            ),
          );
        } catch (_) {
          if (load == _load) _videoControlUncertain = true;
          rethrow;
        }
      }
      if (load != _load ||
          _disposed ||
          _disposing != null ||
          _dead ||
          !_nativePrepared) {
        throw StateError('Playback changed during quality switch');
      }
      final previous = _networkSource!;
      final next = previous.withVideoSelection(quality);
      _networkSource = next;
      previous.onRefreshed?.call(previous, next);
    } finally {
      if (identical(_videoControlResult, pending)) {
        _videoControlResult = null;
        _videoControlRequest = null;
      }
    }
  }

  void _cancelVideoControl(Object error) {
    _finishResourceApplication(error);
    if (_videoControlResult case final pending? when !pending.isCompleted) {
      pending.completeError(error);
    }
  }

  @override
  Future<void> setRate(double rate) async {
    if (!supportsRate(rate)) {
      throw ArgumentError.value(rate, 'rate');
    }
    final load = _load;
    await _send('speed', {'speed': rate});
    if (!_disposed && _disposing == null && load == _load) {
      requestedRate = rate;
    }
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
    if (_disposed || _disposing != null) {
      throw StateError('Player disposed');
    }
    final revision = ++_subtitleRevision;
    if (track.id == 'no' || track.id == 'auto') {
      _subtitles = null;
      _updateSubtitle();
      return;
    }
    final id = track.id;
    final content = id.startsWith('memory://')
        ? id.substring('memory://'.length)
        : await File(id.startsWith('file:') ? Uri.parse(id).toFilePath() : id)
              .readAsString();
    if (revision != _subtitleRevision || _disposed || _disposing != null) {
      return;
    }
    final subtitles = TextSubtitles.parse(content);
    _subtitles = subtitles;
    _updateSubtitle();
  }

  @override
  Future<ui.Image?> screenshot() async {
    if (_disposed || _disposing != null || _dead) return null;
    final load = _load;
    File? file;
    try {
      final path = await _channel.invokeMethod<String>('screenshot', {
        'session': session,
        'load': load,
      });
      if (path == null) return null;
      file = File(path);
      if (_disposed || _disposing != null || load != _load) return null;
      final codec = await ui.instantiateImageCodec(await file.readAsBytes());
      try {
        final frame = await codec.getNextFrame();
        if (_disposed || _disposing != null || load != _load) {
          frame.image.dispose();
          return null;
        }
        return frame.image;
      } finally {
        codec.dispose();
      }
    } on Exception {
      return null;
    } finally {
      if (file != null) {
        try {
          await file.delete();
        } on FileSystemException {
          /* Already removed. */
        }
      }
    }
  }

  Future<void> _closeStreams() async {
    await _rateUpdates.close();
    await _qualityUpdates.close();
    await super.dispose();
  }

  @override
  Future<void> dispose() {
    if (_disposing case final pending?) return pending;
    _stoppingPlayers.add(this);
    final disposal = _disposing = _dispose()
        .then((_) async {
          await _rateUpdates.close();
          await _qualityUpdates.close();
          await super.dispose();
          _stoppingPlayers.remove(this);
          _livePlayers.remove(this);
        })
        .onError<Object>((error, stack) {
          // A later Binder death can confirm a previously timed-out native stop.
          _disposing = null;
          Error.throwWithStackTrace(error, stack);
        });
    deferCreationUntil(disposal);
    return disposal;
  }

  Future<void> _dispose() async {
    ++_subtitleRevision;
    _subtitles = null;
    _cancelSeek(StateError('Playback disposed during seek'));
    _cancelVideoControl(StateError('Playback disposed during quality switch'));
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
