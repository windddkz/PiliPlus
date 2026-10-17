import 'package:PiliPlus/models/common/video/audio_quality.dart';
import 'package:PiliPlus/models/common/video/video_decode_type.dart';
import 'package:PiliPlus/models/video/play/url.dart';
import 'package:PiliPlus/utils/extension/iterable_ext.dart';
import 'package:PiliPlus/utils/video_utils.dart';

class DashSelection {
  const DashSelection(this.video, this.audio, this.codec);

  final VideoItem video;
  final AudioItem? audio;
  final VideoDecodeFormatType codec;

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
