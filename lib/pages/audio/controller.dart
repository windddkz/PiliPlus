import 'dart:async';
import 'dart:io' show Platform;

import 'package:PiliPlus/common/constants.dart';
import 'package:PiliPlus/common/widgets/dialog/simple_dialog_option.dart';
import 'package:PiliPlus/grpc/audio.dart';
import 'package:PiliPlus/grpc/bilibili/app/listener/v1.pb.dart'
    show
        DetailItem,
        PlayURLResp,
        PlaylistSource,
        PlayInfo,
        ThumbUpReq_ThumbType,
        ListOrder,
        DashItem,
        ResponseUrl;
import 'package:PiliPlus/http/browser_ua.dart';
import 'package:PiliPlus/http/constants.dart';
import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/http/video.dart';
import 'package:PiliPlus/models/common/audio_normalization.dart';
import 'package:PiliPlus/models/common/video/video_type.dart';
import 'package:PiliPlus/models/video/play/url.dart' as http_model;
import 'package:PiliPlus/pages/common/common_intro_controller.dart'
    show FavMixin;
import 'package:PiliPlus/pages/dynamics_repost/view.dart';
import 'package:PiliPlus/pages/main_reply/view.dart';
import 'package:PiliPlus/pages/sponsor_block/block_mixin.dart';
import 'package:PiliPlus/pages/video/controller.dart';
import 'package:PiliPlus/pages/video/introduction/ugc/widgets/triple_mixin.dart';
import 'package:PiliPlus/plugin/pl_player/controller.dart';
import 'package:PiliPlus/plugin/pl_player/models/play_repeat.dart';
import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:PiliPlus/plugin/pl_player/official_player.dart';
import 'package:PiliPlus/plugin/pl_player/models/play_status.dart';
import 'package:PiliPlus/services/service_locator.dart';
import 'package:PiliPlus/services/playback_focus.dart';
import 'package:PiliPlus/plugin/pl_player/audio_ducking.dart';
import 'package:PiliPlus/services/shutdown_timer_service.dart';
import 'package:PiliPlus/utils/accounts.dart';
import 'package:PiliPlus/utils/android/android_helper.dart';
import 'package:PiliPlus/utils/connectivity_utils.dart';
import 'package:PiliPlus/utils/extension/iterable_ext.dart';
import 'package:PiliPlus/utils/extension/num_ext.dart';
import 'package:PiliPlus/utils/global_data.dart';
import 'package:PiliPlus/utils/id_utils.dart';
import 'package:PiliPlus/utils/page_utils.dart';
import 'package:PiliPlus/utils/platform_utils.dart';
import 'package:PiliPlus/utils/share_utils.dart';
import 'package:PiliPlus/utils/storage.dart';
import 'package:PiliPlus/utils/storage_key.dart';
import 'package:PiliPlus/utils/storage_pref.dart';
import 'package:PiliPlus/utils/utils.dart';
import 'package:PiliPlus/utils/video_utils.dart';
import 'package:fixnum/fixnum.dart' show Int64;
import 'package:flutter_smart_dialog/flutter_smart_dialog.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';
import 'package:media_kit/media_kit.dart';

class AudioController extends GetxController
    with
        GetTickerProviderStateMixin,
        TripleMixin,
        FavMixin,
        BlockConfigMixin,
        BlockMixin,
        AudioNormalizationMixin
    implements AudioFocusClient {
  final _audioDucking = AudioDucking();
  bool _closing = false;
  @override
  bool get audioFocusAvailable => !_closing && !isClosed && player != null;
  @override
  bool get audioFocusPlaying => isPlaying();
  @override
  Future<void> playForAudioFocus() async {
    await _commands;
    if (audioFocusAvailable && _wantsPlay) {
      if (player!.state.completed) await player!.seek(Duration.zero);
      if (audioFocusAvailable && _wantsPlay) await player?.play();
    }
  }

  @override
  Future<void> pauseForAudioFocus() async {
    await _commands;
    if (audioFocusAvailable) await player?.pause();
  }

  @override
  Future<void> setAudioDucked(bool ducked) =>
      _audioDucking.apply(player, ducked);
  late Int64 id;
  late Int64 oid;
  late List<Int64> subId;
  late int itemType;
  Int64? extraId;
  late final PlaylistSource from;
  @override
  late final bool isUgc = itemType == 1;

  final audioItem = Rxn<DetailItem>();

  @override
  PlatformPlayer? player;
  Future<void> _commands = Future.value();
  Future<void>? _videoHandoff;
  int _sourceRevision = 0;
  bool _wantsPlay = true;
  bool _opening = false;
  bool _forceMpv = false;
  String? _officialFallbackReason;
  String? _sourceUrl;
  DashTrack? _sourceTrack;
  String _sourceUa = Constants.userAgentApp;
  String? _sourceReferer;
  http_model.Volume? _sourceVolume;

  bool _currentSource(int revision) =>
      !_closing && !isClosed && revision == _sourceRevision;

  Future<T> _enqueue<T>(Future<T> Function() action) {
    final result = _commands.then((_) => action());
    _commands = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  void _reportError(Object error) {
    if (!_closing) {
      SmartDialog.showToast(
        error == 'official:resource_unavailable' ||
                OfficialPlayer.isResourceFailure(error)
            ? '播放地址失效，重新取流失败，请重试'
            : '音频播放失败：${OfficialPlayer.failureCode(error)}',
      );
    }
  }

  String get backendDescription => player is OfficialPlayer
      ? '官方内核'
      : _officialFallbackReason == null
      ? 'mpv'
      : 'mpv（回退：$_officialFallbackReason）';
  late int cacheAudioQa;

  late bool isDragging = false;
  final RxInt position = RxInt(0);
  final RxInt duration = RxInt(0);

  late final AnimationController animController;

  List<StreamSubscription>? _subscriptions;

  int? index;
  List<DetailItem>? playlist;

  late double speed = 1.0;

  void setSpeed(double value) {
    if (player != null) {
      speed = value;
      unawaited(
        _enqueue(() async {
          if (!audioFocusAvailable) return;
          await player?.setRate(value);
          _updatePlaybackState();
        }).catchError(_reportError),
      );
    }
  }

  late final Rx<PlayRepeat> playMode = Pref.audioPlayMode.obs;

  @override
  late final isLogin = Accounts.main.isLogin;

  Duration? _start;
  VideoDetailController? _videoDetailController;

  String? _prev;
  String? _next;
  bool get reachStart => _prev == null;

  ListOrder order = ListOrder.ORDER_NORMAL;

  double? _lastVolume;
  late final RxDouble desktopVolume = RxDouble(Pref.desktopVolume);

  Timer? _statusTimer;

  void _startStatusTimer() {
    _statusTimer?.cancel();
    _statusTimer = Timer(
      const Duration(milliseconds: 500),
      _updatePlaybackState,
    );
  }

  void _stopStatusTimer() {
    _statusTimer?.cancel();
    _statusTimer = null;
  }

  void toggleVolume() {
    if (_lastVolume == null) {
      _lastVolume = desktopVolume.value;
      setVolume(0, clearLastVolme: false);
    } else {
      setVolume(_lastVolume!);
    }
  }

  void setVolume(double volume, {bool clearLastVolme = true}) {
    if (clearLastVolme) {
      _lastVolume = null;
    }
    desktopVolume.value = volume;
    unawaited(
      _enqueue(() async {
        if (!audioFocusAvailable) return;
        if (volume > 1 && player is OfficialPlayer) {
          await _switchToMpv('播放器音量超过 100%');
        }
        await player?.setVolume(volume * 100);
      }).catchError(_reportError),
    );
  }

  void syncVolume([_]) {
    final volume = desktopVolume.value;
    PlPlayerController.instance
      ?..volume.value = volume
      ..videoPlayerController?.setVolume(volume * 100);
    GStorage.setting.put(SettingBoxKey.desktopVolume, volume.toPrecision(3));
  }

  @override
  void onInit() {
    super.onInit();
    animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 200),
    );
    _videoHandoff = PlPlayerController.instance?.suspendForAudioPage();
    unawaited(_videoHandoff?.catchError(_reportError));
    final args = Get.arguments;
    oid = Int64(args['oid']);
    final id = args['id'];
    this.id = id != null ? Int64(id) : oid;
    subId = (args['subId'] as List<int>?)?.map(Int64.new).toList() ?? [oid];
    itemType = args['itemType'];
    from = args['from'];
    _start = args['start'];
    final int? extraId = args['extraId'];
    if (extraId != null) {
      this.extraId = Int64(extraId);
    }
    if (args['heroTag'] case String heroTag) {
      try {
        _videoDetailController = Get.find<VideoDetailController>(tag: heroTag);
      } catch (_) {}
    }

    _queryPlayList(isInit: true);

    final String? audioUrl = args['audioUrl'];
    final hasAudioUrl = audioUrl != null;
    if (hasAudioUrl) {
      _querySponsorBlock();
      _onOpenMedia(
        audioUrl,
        track: args['audioTrack'] as DashTrack?,
        ua: BrowserUa.pc,
        referer: HttpString.baseUrl,
        volume:
            args['volume'] as http_model.Volume? ??
            _videoDetailController?.volume,
      );
    }
    ConnectivityUtils.isWiFi.then((isWiFi) {
      if (_closing) return;
      cacheAudioQa = isWiFi ? Pref.defaultAudioQa : Pref.defaultAudioQaCellular;
      if (!hasAudioUrl) {
        _queryPlayUrl();
      }
    });
    videoPlayerServiceHandler
      ?..onPlay = onPlay
      ..onPause = onPause
      ..onSeek = onSeek
      ..onSkipToNext = playNext
      ..onSkipToPrevious = playPrev;

    if (shutdownTimerService.isActive) {
      shutdownTimerService
        ..onPause = onPause
        ..isPlaying = isPlaying;
    }
  }

  bool isPlaying() {
    return player?.state.playing ?? false;
  }

  Future<void> onPlay() async {
    _wantsPlay = true;
    await _commands;
    if (!audioFocusAvailable || !_wantsPlay) return;
    await (audioSessionHandler?.play(this).then((_) {}) ?? playForAudioFocus());
  }

  Future<void> onPause() {
    _wantsPlay = false;
    return audioSessionHandler?.pause(this) ?? pauseForAudioFocus();
  }

  Future<void> onSeek(Duration duration) {
    final revision = _sourceRevision;
    return _enqueue(() async {
      if (!_currentSource(revision) || player == null) return;
      await player!.seek(duration);
      _updatePlaybackState(position: player!.state.position);
    }).catchError(_reportError);
  }

  void _updateCurrItem(DetailItem item) {
    audioItem.value = item;
    hasLike.value = item.stat.hasLike_7;
    coinNum.value = item.stat.hasCoin_8 ? 2 : 0;
    hasFav.value = item.stat.hasFav;
    videoPlayerServiceHandler?.onVideoDetailChange(
      item,
      (subId.firstOrNull ?? oid).toInt(),
      hashCode.toString(),
    );
  }

  Future<void> _queryPlayList({
    bool isInit = false,
    bool isLoadPrev = false,
    bool isLoadNext = false,
  }) async {
    final res = await AudioGrpc.audioPlayList(
      id: id,
      oid: isInit ? oid : null,
      subId: isInit ? subId : null,
      itemType: isInit ? itemType : null,
      from: isInit ? from : null,
      next: isLoadPrev
          ? _prev
          : isLoadNext
          ? _next
          : null,
      extraId: extraId,
      order: order,
    );
    if (_closing) return;
    if (res case Success(:final response)) {
      if (isInit) {
        late final paginationReply = response.paginationReply;
        _prev = response.reachStart ? null : paginationReply.prev;
        _next = response.reachEnd ? null : paginationReply.next;
        final index = response.list.indexWhere((e) => e.item.oid == oid);
        if (index != -1) {
          this.index = index;
          _updateCurrItem(response.list[index]);
          playlist = response.list;
        }
      } else if (isLoadPrev) {
        _prev = response.reachStart ? null : response.paginationReply.prev;
        if (response.list.isNotEmpty) {
          index += response.list.length;
          playlist?.insertAll(0, response.list);
        }
      } else if (isLoadNext) {
        _next = response.reachEnd ? null : response.paginationReply.next;
        if (response.list.isNotEmpty) {
          playlist?.addAll(response.list);
        }
      }
    } else {
      res.toast();
    }
  }

  @pragma('vm:notify-debugger-on-exception')
  void _querySponsorBlock() {
    if (isUgc && enableSponsorBlock) {
      try {
        final bvid = IdUtils.av2bv(oid.toInt());
        final cid = subId.first.toInt();
        querySponsorBlock(bvid: bvid, cid: cid);
      } catch (_) {}
    }
  }

  Future<bool> _queryPlayUrl() async {
    final revision = ++_sourceRevision;
    _wantsPlay = true;
    _querySponsorBlock();
    final web = await _ugcAudioSource();
    if (!_currentSource(revision)) return false;
    final audios = web?.dash?.audio;
    if (audios != null && audios.isNotEmpty) {
      final audio = audios.findClosestTarget(
        (item) => item.id <= cacheAudioQa,
        (a, b) => a.id > b.id ? a : b,
      );
      final source = _httpAudioSource(audio);
      position.value = 0;
      await _onOpenMedia(
        source.videoSource,
        track: source.videoTrack,
        ua: BrowserUa.pc,
        referer: HttpString.baseUrl,
        volume: web?.volume,
        revision: revision,
      );
      return _currentSource(revision);
    }
    final res = await AudioGrpc.audioPlayUrl(
      itemType: itemType,
      oid: oid,
      subId: subId,
    );
    if (!_currentSource(revision)) return false;
    if (res case Success(:final response)) {
      await _onPlay(response, revision);
      return _currentSource(revision);
    } else {
      res.toast();
      return false;
    }
  }

  Future<http_model.PlayUrlModel?> _ugcAudioSource() async {
    if (itemType != 1 || subId.isEmpty) return null;
    // The listener API can omit HiRes/Dolby tracks that the video API provides.
    final result = await VideoHttp.videoUrl(
      avid: oid.toInt(),
      cid: subId.first.toInt(),
      qn: 80,
      tryLook: false,
      videoType: VideoType.ugc,
      voiceBalance: true,
      language: _videoDetailController?.cid.value == subId.first.toInt()
          ? _videoDetailController?.currLang.value
          : null,
    );
    return result.dataOrNull;
  }

  NetworkSource _networkAudioSource(String url, DashTrack? track) =>
      NetworkSource(
        videoSource: url,
        audioSource: null,
        videoTrack: track,
        refresh: _refreshOfficialSource,
        onRefreshed: _acceptRefreshedSource,
      );

  NetworkSource _httpAudioSource(http_model.AudioItem audio) {
    final url = VideoUtils.getCdnUrl(audio.playUrls, isAudio: true);
    return _networkAudioSource(
      url,
      DashTrack(
        source: url,
        id: audio.id,
        bandwidth: audio.bandWidth,
        urls: audio.playUrls,
      ),
    );
  }

  Future<void> _onPlay(PlayURLResp data, int revision) async {
    final PlayInfo? playInfo = data.playerInfo.values.firstOrNull;
    if (playInfo != null) {
      http_model.Volume? volume;
      if (playInfo.hasVolume()) {
        final volumeInfo = playInfo.volume;
        volume = http_model.Volume(
          measuredI: volumeInfo.hasMeasuredI()
              ? volumeInfo.measuredI
              : double.nan,
          measuredLra: volumeInfo.hasMeasuredLra()
              ? volumeInfo.measuredLra
              : double.nan,
          measuredTp: volumeInfo.hasMeasuredTp()
              ? volumeInfo.measuredTp
              : double.nan,
          measuredThreshold: volumeInfo.hasMeasuredThreshold()
              ? volumeInfo.measuredThreshold
              : double.nan,
          targetOffset: volumeInfo.hasTargetOffset()
              ? volumeInfo.targetOffset
              : double.nan,
          targetI: volumeInfo.hasTargetI() ? volumeInfo.targetI : double.nan,
          targetTp: volumeInfo.hasTargetTp() ? volumeInfo.targetTp : double.nan,
          multiSceneArgs: Map.of(volumeInfo.multiSceneArgs),
        );
      }
      if (playInfo.hasPlayDash()) {
        final playDash = playInfo.playDash;
        final audios = playDash.audio;
        if (audios.isEmpty) {
          return;
        }
        position.value = 0;
        final audio = audios.findClosestTarget(
          (e) => e.id <= cacheAudioQa,
          (a, b) => a.id > b.id ? a : b,
        );
        final url = VideoUtils.getCdnUrl(audio.playUrls);
        await _onOpenMedia(
          url,
          track: DashTrack(
            source: url,
            id: audio.id,
            bandwidth: audio.bandwidth,
            urls: audio.playUrls,
          ),
          volume: volume,
          revision: revision,
        );
      } else if (playInfo.hasPlayUrl()) {
        final playUrl = playInfo.playUrl;
        final durls = playUrl.durl;
        if (durls.isEmpty) {
          return;
        }
        final durl = durls.first;
        position.value = 0;
        await _onOpenMedia(
          VideoUtils.getCdnUrl(durl.playUrls),
          volume: volume,
          revision: revision,
        );
      }
    }
  }

  Future<void> _onOpenMedia(
    String url, {
    DashTrack? track,
    String ua = Constants.userAgentApp,
    String? referer,
    http_model.Volume? volume,
    int? revision,
  }) async {
    final source = revision ?? ++_sourceRevision;
    try {
      await _enqueue(() async {
        if (!_currentSource(source)) return;
        await _initPlayerIfNeeded();
        if (!_currentSource(source) || player == null) return;
        _sourceUrl = url;
        _sourceTrack = track;
        _sourceUa = ua;
        _sourceReferer = referer;
        _sourceVolume = volume;
        final start = _start;
        _start = null;
        _opening = true;
        try {
          await _openCurrent(start);
        } catch (error) {
          if (!_currentSource(source) || player is! OfficialPlayer) rethrow;
          if (OfficialPlayer.isResourceFailure(error)) rethrow;
          await _switchToMpv(
            '官方开片失败：${OfficialPlayer.failureCode(error)}',
            position: start,
          );
        } finally {
          _opening = false;
        }
      });
      if (_currentSource(source) && _wantsPlay) await onPlay();
    } catch (error) {
      if (_currentSource(source)) _reportError(error);
    }
  }

  Future<void> _openCurrent(Duration? start) async {
    final current = player!;
    if (current is OfficialPlayer) {
      await current.openSource(
        _networkAudioSource(_sourceUrl!, _sourceTrack),
        position: start,
        userAgent: _sourceUa,
        referer: _sourceReferer ?? '',
        audioOnly: true,
        loudnessMode: Pref.officialLoudnessMode,
        loudnessValues: _sourceVolume?.officialValues,
        loudnessScenes: _sourceVolume?.multiSceneArgs,
      );
      if (audioFocusAvailable &&
          identical(player, current) &&
          current.initialSeekTimedOut) {
        SmartDialog.showToast('起播定位超时，将从实际到达的位置播放，可重新拖动');
      }
    } else if (current is NativePlayer) {
      current.setMediaHeader(
        userAgent: _sourceUa,
        headers: {'Referer': _sourceReferer ?? ''},
      );
      await current.open(
        Media(
          _sourceUrl!,
          start: start,
          extras: audioFilterExtras(_sourceVolume),
        ),
        play: false,
      );
    }
    if (!audioFocusAvailable || !identical(player, current)) return;
    await current.setRate(speed);
    await setAudioDucked(audioSessionHandler?.isDucked(this) ?? false);
  }

  Future<NetworkSource?> _refreshOfficialSource(NetworkSource source) async {
    final revision = _sourceRevision;
    final currentPlayer = player;
    final track = source.videoTrack;
    bool current() =>
        _currentSource(revision) &&
        identical(player, currentPlayer) &&
        _sourceUrl == source.videoSource;
    if (!current() || track == null) return null;
    final web = await _ugcAudioSource();
    if (!current()) return null;
    final webAudio = web?.dash?.audio
        ?.where((item) => item.id == track.id)
        .firstOrNull;
    if (webAudio != null) return _httpAudioSource(webAudio);
    final result = await AudioGrpc.audioPlayUrl(
      itemType: itemType,
      oid: oid,
      subId: subId,
    );
    if (!current()) return null;
    if (result case Success(:final response)) {
      final playInfo = response.playerInfo.values.firstOrNull;
      final audio = playInfo?.playDash.audio
          .where((item) => item.id == track.id)
          .firstOrNull;
      if (audio == null) return null;
      final url = VideoUtils.getCdnUrl(audio.playUrls);
      final next = _networkAudioSource(
        url,
        DashTrack(
          source: url,
          id: audio.id,
          bandwidth: audio.bandwidth,
          urls: audio.playUrls,
        ),
      );
      return next;
    }
    return null;
  }

  void _acceptRefreshedSource(NetworkSource previous, NetworkSource next) {
    if (_closing || isClosed || _sourceUrl != previous.videoSource) return;
    _sourceUrl = next.videoSource;
    _sourceTrack = next.videoTrack;
  }

  Future<PlatformPlayer> _switchToMpv(
    String reason, {
    Duration? position,
  }) async {
    final previous = player;
    if (previous is! OfficialPlayer) {
      if (previous != null) return previous;
      throw StateError('No audio player to restore');
    }
    final resume =
        _wantsPlay &&
        (audioSessionHandler?.wantsPlayback(this) ?? previous.state.playing);
    final start = position ?? previous.state.position;
    final volume = previous.state.volume;
    _removeListeners();
    await previous.dispose();
    player = null;
    if (_closing) throw StateError('Audio page closed during fallback');
    _forceMpv = true;
    _officialFallbackReason = reason;
    await _initPlayerIfNeeded();
    if (!audioFocusAvailable) {
      throw StateError('Audio page closed during fallback');
    }
    await _openCurrent(start);
    await player!.setVolume(volume);
    if (resume &&
        _wantsPlay &&
        (audioSessionHandler?.wantsPlayback(this) ?? true)) {
      await player!.play();
    }
    SmartDialog.showToast('已回退 mpv：$reason');
    return player!;
  }

  void _removeListeners() {
    _subscriptions?.forEach((e) => e.cancel());
    _subscriptions = null;
  }

  PlayerStatus _playerStatus = .paused;
  void _updatePlaybackState({Duration? position, String? debugLabel}) {
    if (!audioFocusAvailable) return;
    videoPlayerServiceHandler?.onUpdateState(
      _playerStatus,
      false,
      false,
      position: position ?? player!.state.position,
      speed: speed,
      debugLabel: debugLabel,
    );
  }

  Future<void> _initPlayerIfNeeded() async {
    if (player != null || _closing) return;
    await _videoHandoff;
    if (_closing) return;
    await OfficialPlayer.waitForDisposal();
    if (_closing) return;
    final volume = PlatformUtils.isDesktop
        ? desktopVolume.value * 100
        : Pref.playerVolume;
    if (Platform.isAndroid && Pref.officialAudioBackend && !_forceMpv) {
      if (enableAudioNormalization || volume > 100) {
        _forceMpv = true;
        _officialFallbackReason = '当前均衡或放大设置需要 mpv';
      } else {
        OfficialPlayer? candidate;
        try {
          candidate = await OfficialPlayer.create(
            (reason) => _enqueue(() {
              if (!audioFocusAvailable || !identical(player, candidate)) {
                throw StateError('Obsolete audio fallback');
              }
              return _switchToMpv(reason);
            }),
          );
          await candidate.setVolume(volume);
          player = candidate;
        } catch (error) {
          await candidate?.dispose();
          _forceMpv = true;
          _officialFallbackReason =
              '官方运行时不可用：${OfficialPlayer.failureCode(error)}';
          if (!_closing) SmartDialog.showToast('官方内核不可用，使用 mpv');
        }
      }
    }
    if (player == null && !_closing) {
      await OfficialPlayer.waitForDisposal();
      if (_closing) return;
      player = await Player.create(
        configuration: PlayerConfiguration(
          options: {
            if (Platform.isAndroid) 'ao': Pref.audioOutput,
            'volume': volume.toString(),
            ...Pref.initBuffer(),
          },
        ),
      );
    }
    if (_closing || isClosed) {
      await player?.dispose();
      player = null;
      return;
    }
    final current = player!;
    final stream = current.stream;
    _subscriptions = [
      stream.error.listen((error) {
        if (_closing || _opening || !identical(player, current)) return;
        if (error == 'official:resource_unavailable') {
          _wantsPlay = false;
          _reportError(error);
          return;
        }
        if (current is OfficialPlayer) {
          unawaited(
            _enqueue(() async {
              if (audioFocusAvailable && identical(player, current)) {
                await _switchToMpv(error);
              }
            }).catchError(_reportError),
          );
        }
      }),
      stream.position.listen((position) {
        if (!audioFocusAvailable || !identical(player, current)) return;
        if (isDragging) return;
        final seconds = position.inSeconds;
        if (seconds != this.position.value) {
          if (seconds == 0 && _playerStatus.isPlaying) {
            _updatePlaybackState(position: position);
          }
          this.position.value = seconds;
          _videoDetailController?.playedTime = position;
        }
      }),
      stream.duration.listen((duration) {
        if (!audioFocusAvailable || !identical(player, current)) return;
        this.duration.value = duration.inSeconds;
      }),
      stream.playing.listen((playing) {
        if (!audioFocusAvailable || !identical(player, current)) return;
        if (playing) {
          animController.forward();
          _playerStatus = .playing;
          _stopStatusTimer();
          _updatePlaybackState();
        } else {
          animController.reverse();
          _playerStatus = .paused;
          _startStatusTimer();
        }
      }),
      stream.buffering.listen((bool buffering) {
        if (!audioFocusAvailable || !identical(player, current)) return;
        if (!_playerStatus.isCompleted) {
          _stopStatusTimer();
          _updatePlaybackState();
        }
      }),
      stream.completed.listen((completed) {
        if (!audioFocusAvailable || !identical(player, current)) return;
        if (completed) {
          _videoDetailController?.playedTime = current.state.duration;
          unawaited(audioSessionHandler?.release(this));
          _playerStatus = .completed;
          _startStatusTimer();
          if (shutdownTimerService.isWaiting) {
            shutdownTimerService.handleWaiting();
          } else {
            switch (playMode.value) {
              case PlayRepeat.pause:
                break;
              case PlayRepeat.listOrder:
                playNext(nextPart: true);
                break;
              case PlayRepeat.singleCycle:
                onPlay();
                break;
              case PlayRepeat.listCycle:
                if (!playNext(nextPart: true)) {
                  if (index != null && index != 0 && playlist != null) {
                    playIndex(0);
                  } else {
                    onPlay();
                  }
                }
                break;
              case PlayRepeat.autoPlayRelated:
                break;
            }
          }
        }
      }),
    ];
  }

  @override
  Future<void> actionLikeVideo() async {
    if (!isLogin) {
      SmartDialog.showToast('账号未登录');
      return;
    }
    final newVal = !hasLike.value;
    final res = await AudioGrpc.audioThumbUp(
      oid: oid,
      subId: subId,
      itemType: itemType,
      type: newVal
          ? ThumbUpReq_ThumbType.LIKE
          : ThumbUpReq_ThumbType.CANCEL_LIKE,
    );
    if (res case Success(:final response)) {
      hasLike.value = newVal;
      try {
        audioItem.value!.stat
          ..hasLike_7 = newVal
          ..like += newVal ? 1 : -1;
        audioItem.refresh();
      } catch (_) {}
      SmartDialog.showToast(response.message);
    } else {
      res.toast();
    }
  }

  @override
  Future<void> actionTriple() async {
    if (!isLogin) {
      SmartDialog.showToast('账号未登录');
      return;
    }
    final res = await AudioGrpc.audioTripleLike(
      oid: oid,
      subId: subId,
      itemType: itemType,
    );
    if (res case Success(:final response)) {
      hasLike.value = true;
      if (response.coinOk && !hasCoin) {
        coinNum.value = 2;
        GlobalData().afterCoin(2);
        try {
          audioItem.value!.stat
            ..hasCoin_8 = true
            ..coin += 2;
          audioItem.refresh();
        } catch (_) {}
      }
      hasFav.value = true;
      if (!hasCoin) {
        SmartDialog.showToast('投币失败');
      } else {
        SmartDialog.showToast('三连成功');
      }
    } else {
      res.toast();
    }
  }

  @override
  int get copyright => audioItem.value?.arc.copyright ?? 1;

  @override
  Future<void> onPayCoin(int coin, bool coinWithLike) async {
    final res = await AudioGrpc.audioCoinAdd(
      oid: oid,
      subId: subId,
      itemType: itemType,
      num: coin,
      thumbUp: coinWithLike,
    );
    if (res.isSuccess) {
      final updateLike = !hasLike.value && coinWithLike;
      if (updateLike) {
        hasLike.value = true;
      }
      coinNum.value += coin;
      try {
        final stat = audioItem.value!.stat
          ..hasCoin_8 = true
          ..coin += coin;
        if (updateLike) {
          stat
            ..hasLike_7 = true
            ..like += 1;
        }
        audioItem.refresh();
      } catch (_) {}
      GlobalData().afterCoin(coin);
    } else {
      res.toast();
    }
  }

  @override
  void showFavBottomSheet(BuildContext context, {bool isLongPress = false}) {
    if (!isLogin) {
      SmartDialog.showToast('账号未登录');
      return;
    }
    if (enableQuickFav) {
      if (!isLongPress) {
        actionFavVideo(isQuick: true);
      } else {
        PageUtils.showFavBottomSheet(context: context, ctr: this);
      }
    } else if (!isLongPress) {
      PageUtils.showFavBottomSheet(context: context, ctr: this);
    }
  }

  void showReply() {
    MainReplyPage.toMainReplyPage(
      oid: oid.toInt(),
      replyType: isUgc ? 1 : 14,
    );
  }

  void actionShareVideo(BuildContext context) {
    final audioUrl = isUgc
        ? '${HttpString.baseUrl}/video/${IdUtils.av2bv(oid.toInt())}'
        : '${HttpString.baseUrl}/audio/au$oid';
    showDialog(
      context: context,
      builder: (_) => SimpleDialog(
        clipBehavior: Clip.hardEdge,
        contentPadding: const EdgeInsets.symmetric(vertical: 12),
        children: [
          DialogOption(
            child: const Text('复制链接', style: TextStyle(fontSize: 14)),
            onPressed: () {
              Get.back();
              Utils.copyText(audioUrl);
            },
          ),
          DialogOption(
            child: const Text('其它app打开', style: TextStyle(fontSize: 14)),
            onPressed: () {
              Get.back();
              PiliAndroidHelper.openUrl(audioUrl);
            },
          ),
          if (PlatformUtils.isMobile)
            DialogOption(
              child: const Text('分享视频', style: TextStyle(fontSize: 14)),
              onPressed: () {
                Get.back();
                if (audioItem.value case DetailItem(
                  :final arc,
                  :final owner,
                )) {
                  ShareUtils.shareText(
                    '${arc.title} '
                    'UP主: ${owner.name}'
                    ' - $audioUrl',
                  );
                }
              },
            ),
          if (isLogin)
            DialogOption(
              child: const Text('分享至动态', style: TextStyle(fontSize: 14)),
              onPressed: () {
                Get.back();
                if (audioItem.value case DetailItem(
                  :final arc,
                  :final owner,
                )) {
                  showModalBottomSheet(
                    context: context,
                    isScrollControlled: true,
                    useSafeArea: true,
                    builder: (context) => RepostPanel(
                      rid: oid.toInt(),
                      dynType: isUgc ? 8 : 256,
                      pic: arc.cover,
                      title: arc.title,
                      uname: owner.name,
                    ),
                  );
                }
              },
            ),
          if (isUgc && isLogin)
            DialogOption(
              child: const Text('分享至消息', style: TextStyle(fontSize: 14)),
              onPressed: () {
                Get.back();
                if (audioItem.value case DetailItem(
                  :final arc,
                  :final owner,
                )) {
                  try {
                    PageUtils.pmShare(
                      context,
                      content: {
                        "id": oid.toString(),
                        "title": arc.title,
                        "headline": arc.title,
                        "source": 5,
                        "thumb": arc.cover,
                        "author": owner.name,
                        "author_id": owner.mid.toString(),
                      },
                    );
                  } catch (e) {
                    SmartDialog.showToast(e.toString());
                  }
                }
              },
            ),
        ],
      ),
    );
  }

  Future<void>? playOrPause() {
    return isPlaying() ? onPause() : onPlay();
  }

  bool playPrev() {
    if (index != null && playlist != null && player != null) {
      final prev = index! - 1;
      if (prev >= 0) {
        playIndex(prev);
        return true;
      }
    }
    return false;
  }

  bool playNext({bool nextPart = false}) {
    if (nextPart) {
      if (audioItem.value case DetailItem(:final parts)) {
        if (parts.length > 1) {
          final subId = this.subId.firstOrNull;
          final nextIndex = parts.indexWhere((e) => e.subId == subId) + 1;
          if (nextIndex != 0 && nextIndex < parts.length) {
            final nextPart = parts[nextIndex];
            oid = nextPart.oid;
            this.subId = [nextPart.subId];
            _start = null;
            _videoDetailController = null;
            _queryPlayUrl().then((res) {
              if (res) {
                _videoDetailController = null;
              }
            });
            return true;
          }
        }
      }
    }
    if (index != null && playlist != null && player != null) {
      final next = index! + 1;
      if (next < playlist!.length) {
        if (next == playlist!.length - 1 && _next != null) {
          _queryPlayList(isLoadNext: true);
        }
        playIndex(next);
        return true;
      }
    }
    return false;
  }

  void playIndex(int index, {List<Int64>? subId}) {
    if (index == this.index && subId == null) return;
    this.index = index;
    final audioItem = playlist![index];
    final item = audioItem.item;
    oid = item.oid;
    this.subId =
        subId ??
        (item.subId.isNotEmpty ? item.subId : [audioItem.parts.first.subId]);
    itemType = item.itemType;
    _start = null;
    _videoDetailController = null;
    _queryPlayUrl().then((res) {
      if (res) {
        _videoDetailController = null;
        _updateCurrItem(audioItem);
      }
    });
  }

  @override
  (Object, int) get getFavRidType => (oid, isUgc ? 2 : 12);

  @override
  void updateFavCount(int count) {
    try {
      audioItem.value!.stat
        ..hasFav = count > 0
        ..favourite += count;
      audioItem.refresh();
    } catch (_) {}
  }

  Future<void> loadPrev(BuildContext context) async {
    if (_prev == null) return;
    final length = playlist!.length;
    await _queryPlayList(isLoadPrev: true);
    if (length != playlist!.length && context.mounted) {
      (context as Element).markNeedsBuild();
    }
  }

  Future<void> loadNext(BuildContext context) async {
    if (_next == null) return;
    final length = playlist!.length;
    await _queryPlayList(isLoadNext: true);
    if (length != playlist!.length && context.mounted) {
      (context as Element).markNeedsBuild();
    }
  }

  void onChangeOrder(ListOrder value) {
    if (order != value) {
      order = value;
      _queryPlayList(isInit: true);
    }
  }

  @override
  BlockConfigMixin get blockConfig => this;

  @override
  int get currPosInMilliseconds => player?.state.position.inMilliseconds ?? 0;

  @override
  int? get timeLength => player?.state.duration.inMilliseconds ?? 0;

  @override
  Future<void>? seekTo(Duration duration, {required bool isSeek}) =>
      onSeek(duration);

  @override
  bool get autoPlay => true;

  @override
  bool get preInitPlayer => true;

  void stopPlayback() {
    if (_closing) return;
    _closing = true;
    ++_sourceRevision;
    _wantsPlay = false;
    unawaited(audioSessionHandler?.release(this));
    _stopStatusTimer();
    shutdownTimerService
      ..onPause = null
      ..isPlaying = null
      ..reset();
    videoPlayerServiceHandler
      ?..onPlay = null
      ..onPause = null
      ..onSeek = null
      ..onSkipToNext = null
      ..onSkipToPrevious = null
      ..onVideoDetailDispose(hashCode.toString())
      ..clearIfNeeded();
    _removeListeners();
    final disposal = player?.dispose();
    final barrier = () async {
      await disposal;
      await _commands;
    }();
    OfficialPlayer.deferCreationUntil(barrier);
    unawaited(
      barrier.catchError((Object error) {
        debugPrint(
          'Audio teardown failed: ${OfficialPlayer.failureCode(error)}',
        );
      }),
    );
    player = null;
  }

  @override
  void onClose() {
    stopPlayback();
    animController.dispose();
    super.onClose();
  }
}

extension on DashItem {
  Iterable<String> get playUrls sync* {
    yield baseUrl;
    yield* backupUrl;
  }
}

extension on ResponseUrl {
  Iterable<String> get playUrls sync* {
    yield url;
    yield* backupUrl;
  }
}
