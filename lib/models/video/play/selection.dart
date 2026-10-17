import 'package:PiliPlus/models/common/video/audio_quality.dart';
import 'package:PiliPlus/models/common/video/video_decode_type.dart';
import 'package:PiliPlus/models/video/play/url.dart';
import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:PiliPlus/utils/extension/iterable_ext.dart';
import 'package:PiliPlus/utils/video_utils.dart';

class DashSelection {
  const DashSelection(this.video, this.audio, this.codec);

  final VideoItem video;
  final AudioItem? audio;
  final VideoDecodeFormatType codec;

  static bool supportsAutomatic(List<DashTrack> ladder) =>
      ladder.length > 1 &&
      ladder.every(
        (track) =>
            track.bandwidth != null &&
            track.bandwidth! > 0 &&
            track.bandwidth! <= 0x7fffffff,
      );

  static int _colorFamily(int quality) =>
      const {125, 126, 129}.contains(quality) ? quality : 0;

  static VideoDecodeFormatType? _codec(String? value) {
    if (value == null) return null;
    for (final type in VideoDecodeFormatType.values) {
      if (type.codes.any((prefix) => value.startsWith('$prefix.'))) return type;
    }
    return null;
  }

  static DashTrack _videoTrack(VideoItem video) => DashTrack(
    source: VideoUtils.getCdnUrl(video.playUrls),
    id: video.id,
    bandwidth: video.bandWidth,
    codecs: video.codecs,
    urls: video.playUrls,
  );

  static List<DashTrack> videoLadder(
    PlayUrlModel resource,
    DashTrack selected,
  ) {
    final codec = _codec(selected.codecs);
    if (codec == null) return const [];
    bool matches(VideoItem video) =>
        video.id == selected.id &&
        video.codecs == selected.codecs &&
        video.bandWidth == selected.bandwidth;
    final snapshot = resource.officialPreloadVideos;
    // Detail-page supplementation must not silently invalidate an existing native preload.
    final videos = snapshot != null && snapshot.any(matches)
        ? snapshot
        : resource.dash?.video;
    if (videos == null || !videos.any(matches)) return const [];
    final tracks = <int, DashTrack>{selected.id: selected};
    for (final video in videos) {
      if (tracks.containsKey(video.id) ||
          _codec(video.codecs) != codec ||
          _colorFamily(video.id) != _colorFamily(selected.id) ||
          video.playUrls.isEmpty) {
        continue;
      }
      tracks[video.id] = _videoTrack(video);
    }
    if (tracks.length > 20) return const [];
    return List.unmodifiable(
      tracks.values.toList()..sort((a, b) => b.id.compareTo(a.id)),
    );
  }

  static List<DashTrack>? refreshVideoLadder(
    PlayUrlModel resource,
    List<DashTrack> previous,
  ) {
    final tracks = <DashTrack>[];
    for (final old in previous) {
      final candidate = resource.dash?.video
          ?.where(
            (video) => video.id == old.id && video.codecs == old.codecs,
          )
          .firstOrNull;
      if (candidate == null || candidate.playUrls.isEmpty) return null;
      tracks.add(_videoTrack(candidate));
    }
    return List.unmodifiable(tracks);
  }

  static DashSelection select(
    PlayUrlModel resource, {
    required int videoQuality,
    required int audioQuality,
    required List<VideoDecodeFormatType> preferredCodecs,
  }) {
    final quality = resource.findAvailableVideoQuality(videoQuality);
    final videos = resource.dash!.video!
        .where((video) => video.id == quality)
        .toList();
    // Advertised support_formats can omit or include codecs absent from this response.
    final codec = VideoUtils.selectCodec(
      videos.map((video) => video.codecs!),
      preferredCodecs,
    );
    final video = videos.firstWhere(
      (video) => codec.codes.any(video.codecs!.startsWith),
    );
    final audioList = resource.dash?.audio;
    AudioItem? audio;
    if (audioList != null && audioList.isNotEmpty) {
      final ids = audioList.map((audio) => audio.id).toList();
      var id = ids.findClosestTarget(
        (id) => id <= audioQuality,
        (a, b) => a > b ? a : b,
      );
      if (!ids.contains(audioQuality) && ids.any((id) => id > audioQuality)) {
        id = AudioQuality.k192.code;
      }
      audio = audioList.firstWhere(
        (audio) => audio.id == id,
        orElse: () => audioList.first,
      );
    }
    return DashSelection(video, audio, codec);
  }
}
