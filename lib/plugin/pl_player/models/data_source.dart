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
  final DashTrack? videoTrack;
  final DashTrack? audioTrack;

  NetworkSource({
    required super.videoSource,
    required super.audioSource,
    this.videoTrack,
    this.audioTrack,
  });
}

class DashTrack {
  final String source;
  final int id;
  final int? bandwidth;
  final List<String> urls;

  DashTrack({
    required this.source,
    required this.id,
    required this.bandwidth,
    required Iterable<String> urls,
  }) : urls = List.unmodifiable(urls);

  Map<String, Object>? forSource(String? current) {
    // Manual URL edits must not inherit another track's alternatives or quality.
    if (current != source || id <= 0 || id > 0x7fffffff) return null;
    return {
      'source': source,
      'id': id,
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
