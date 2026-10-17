import 'package:PiliPlus/utils/path_utils.dart';
import 'package:path/path.dart' as path;

sealed class DataSource {
  final String videoSource;
  final String? audioSource;

  DataSource({
    required this.videoSource,
    required this.audioSource,
  });
}

class NetworkSource extends DataSource {
  final String? officialPreloadToken;
  final DashTrack? videoTrack;
  final DashTrack? audioTrack;
  final List<DashTrack> videoTracks;
  final bool automaticVideo;
  final void Function(NetworkSource source, int? quality, bool automatic)?
  onVideoQuality;
  final Future<NetworkSource?> Function(NetworkSource source)? refresh;
  final void Function(NetworkSource previous, NetworkSource next)? onRefreshed;

  NetworkSource withVideoSelection(int? quality) {
    final selected = quality == null
        ? videoTrack
        : videoTracks.where((track) => track.id == quality).firstOrNull;
    if (selected == null || !videoTracks.contains(selected)) {
      throw ArgumentError.value(quality, 'quality', 'Unavailable video track');
    }
    return NetworkSource(
      videoSource: selected.source,
      audioSource: audioSource,
      officialPreloadToken: officialPreloadToken,
      videoTrack: selected,
      audioTrack: audioTrack,
      videoTracks: videoTracks,
      automaticVideo: quality == null,
      refresh: refresh,
      onRefreshed: onRefreshed,
      onVideoQuality: onVideoQuality,
    );
  }

  NetworkSource({
    required super.videoSource,
    required super.audioSource,
    this.officialPreloadToken,
    this.videoTrack,
    this.audioTrack,
    Iterable<DashTrack> videoTracks = const [],
    this.automaticVideo = false,
    this.refresh,
    this.onRefreshed,
    this.onVideoQuality,
  }) : videoTracks = List.unmodifiable(videoTracks);
}

class DashTrack {
  final String source;
  final int id;
  final int? bandwidth;
  final String? codecs;
  final List<String> urls;

  DashTrack({
    required this.source,
    required this.id,
    required this.bandwidth,
    this.codecs,
    required Iterable<String> urls,
  }) : urls = List.unmodifiable(urls);

  Map<String, Object>? forSource(String? current) {
    // Manual URL edits must not inherit another track's alternatives or quality.
    if (current != source || id <= 0 || id > 0x7fffffff) return null;
    return {
      'source': source,
      'id': id,
      'codecs': ?codecs,
      'bandwidth':
          bandwidth != null && bandwidth! >= 0 && bandwidth! <= 0x7fffffff
          ? bandwidth!
          : 0,
      'backups': urls
          .where((url) {
            if (url == source || url.length > 16384) return false;
            final uri = Uri.tryParse(url);
            return uri != null &&
                (uri.scheme == 'https' || uri.scheme == 'http') &&
                uri.host.isNotEmpty &&
                uri.userInfo.isEmpty &&
                !uri.hasFragment;
          })
          .toSet()
          .take(32)
          .toList(),
    };
  }
}

class FileSource extends DataSource {
  final String dir;
  final bool isMp4;

  FileSource({
    required this.dir,
    required this.isMp4,
    required bool hasDashAudio,
    required String typeTag,
  }) : super(
         videoSource: path.join(
           dir,
           typeTag,
           isMp4 ? PathUtils.videoNameType1 : PathUtils.videoNameType2,
         ),
         audioSource: isMp4 || !hasDashAudio
             ? null
             : path.join(dir, typeTag, PathUtils.audioNameType2),
       );
}
