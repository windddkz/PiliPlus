import 'package:PiliPlus/models/common/video/video_decode_type.dart';
import 'package:PiliPlus/models/common/video/video_quality.dart';
import 'package:PiliPlus/models/video/play/selection.dart';
import 'package:PiliPlus/models/video/play/url.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  VideoItem video(int quality, String codec) => VideoItem(
    id: quality,
    quality: VideoQuality.fromCode(quality),
    codecs: codec,
    baseUrl: 'https://media.invalid/$quality/$codec',
  );
  AudioItem audio(int id) => AudioItem.fromJson({
    'id': id,
    'base_url': 'https://media.invalid/audio/$id',
  });
  late PlayUrlModel resource;
  setUp(() {
    resource = PlayUrlModel(
      acceptQuality: [120, 80, 64],
      dash: Dash(
        video: [
          video(120, 'hev1.2'),
          video(80, 'avc1.640'),
          video(80, 'hev1.1'),
          video(80, 'av01.0'),
          video(64, 'avc1.640'),
        ],
        audio: [
          audio(30251),
          audio(30250),
          audio(30255),
          audio(30280),
          audio(30232),
        ],
      ),
    );
  });
  DashSelection select({
    int qa = 80,
    int aq = 30251,
    List<VideoDecodeFormatType> codecs = const [
      VideoDecodeFormatType.HEVC,
      VideoDecodeFormatType.AVC,
    ],
  }) => DashSelection.select(
    resource,
    videoQuality: qa,
    audioQuality: aq,
    preferredCodecs: codecs,
  );
  test(
    'initial selection preserves requested HiRes and actual HEVC object',
    () {
      final selection = select();
      expect(selection.video, same(resource.dash!.video![2]));
      expect(selection.audio, same(resource.dash!.audio!.first));
      expect(selection.codec, VideoDecodeFormatType.HEVC);
    },
  );
  test('all available audio preferences select exactly that track', () {
    for (final track in resource.dash!.audio!) {
      expect(select(aq: track.id).audio, same(track));
    }
  });
  test('unavailable enhanced audio keeps existing AAC fallback', () {
    resource.dash!.audio!.removeWhere((audio) => audio.id == 30251);
    expect(select().audio!.id, 30280);
    resource.dash!.audio!.removeWhere((audio) => audio.id == 30280);
    expect(select().audio, same(resource.dash!.audio!.first));
  });
  test('quality and codec preferences apply to available tracks only', () {
    expect(select(qa: 125).video.id, 120);
    expect(select(qa: 74).video.id, 64);
    expect(select(codecs: [VideoDecodeFormatType.AV1]).video.codecs, 'av01.0');
    resource.supportFormats = [
      FormatItem(quality: 80, codecs: ['dvh1.0']),
    ];
    expect(select().codec, VideoDecodeFormatType.HEVC);
  });
  test('absent advertised codec list does not discard real audio or video', () {
    expect(resource.supportFormats, isNull);
    expect(select().video.id, 80);
    expect(select().audio!.id, 30251);
    resource.dash!.audio = null;
    expect(select().audio, isNull);
  });
  test('selection does not mutate source order or history', () {
    final originalVideos = resource.dash!.video!.toList();
    final originalAudios = resource.dash!.audio!.toList();
    resource.lastPlayTime = 12345;
    select(qa: 64, aq: 30232);
    expect(resource.dash!.video, originalVideos);
    expect(resource.dash!.audio, originalAudios);
    expect(resource.lastPlayTime, 12345);
  });
}
