import 'package:PiliPlus/models/common/video/video_decode_type.dart';
import 'package:PiliPlus/models/common/video/video_quality.dart';
import 'package:PiliPlus/models/video/play/selection.dart';
import 'package:PiliPlus/models/video/play/url.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:PiliPlus/models/common/video/cdn_type.dart';
import 'package:PiliPlus/utils/video_utils.dart';

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
    VideoUtils.cdnService = CDNService.baseUrl;
    VideoUtils.disableAudioCDN = false;
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
  DashTrack track(VideoItem video, {String? source}) => DashTrack(
    source: source ?? video.baseUrl!,
    id: video.id,
    bandwidth: video.bandWidth,
    codecs: video.codecs,
    urls: video.playUrls,
  );
  test('video ladder preserves fixed selected source and filters codecs', () {
    final selected = track(
      resource.dash!.video![1],
      source: 'https://chosen.invalid/80',
    );
    final tracks = DashSelection.videoLadder(resource, selected);
    expect(tracks.map((t) => t.id), [80, 64]);
    expect(tracks.first, same(selected));
    expect(tracks.first.source, 'https://chosen.invalid/80');
    expect(resource.dash!.audio!.first.id, 30251);
    expect(tracks.clear, throwsUnsupportedError);
    final lower = track(resource.dash!.video!.last);
    final lowerList = DashSelection.videoLadder(resource, lower);
    expect(lowerList.last, same(lower));
    expect(lower.id, 64);
  });
  test('supplemented qualities do not invalidate the preloaded ladder', () {
    resource.officialPreloadVideos = List.unmodifiable(resource.dash!.video!);
    final selected = track(resource.dash!.video![1]);
    final before = DashSelection.videoLadder(resource, selected);
    final extra = video(32, 'avc1.640');
    resource.dash!.video!.add(extra);
    final after = DashSelection.videoLadder(resource, selected);
    expect(
      after.map((t) => t.forSource(t.source)),
      before.map((t) => t.forSource(t.source)),
    );
    final newlySelected = track(extra);
    expect(
      DashSelection.videoLadder(resource, newlySelected).map((t) => t.id),
      [80, 64, 32],
    );
  });
  test('HDR families remain separate and unknown codecs keep legacy path', () {
    for (final quality in [125, 126, 129]) {
      final hdr = video(quality, 'hev1.2');
      resource.dash!.video!.add(hdr);
      expect(DashSelection.videoLadder(resource, track(hdr)).map((t) => t.id), [
        quality,
      ]);
    }
    final sdr = track(resource.dash!.video![2]);
    expect(DashSelection.videoLadder(resource, sdr).map((t) => t.id), [
      120,
      80,
    ]);
    for (final codec in ['unknown', 'avc1', 'av01']) {
      final other = video(32, codec);
      resource.dash!.video!.add(other);
      expect(DashSelection.videoLadder(resource, track(other)), isEmpty);
    }
  });
  test('ladder deduplicates quality and skips missing alternatives', () {
    final duplicate = video(64, 'avc1.640');
    resource.dash!.video!.add(duplicate);
    final missing = video(32, 'avc1.640')..baseUrl = null;
    resource.dash!.video!.add(missing);
    final tracks = DashSelection.videoLadder(
      resource,
      track(resource.dash!.video![1]),
    );
    expect(tracks.map((t) => t.id), [80, 64]);
    expect(tracks.last.source, resource.dash!.video![4].baseUrl);
  });
  test('refresh updates every allowed track without expanding the ladder', () {
    final previous = DashSelection.videoLadder(
      resource,
      track(resource.dash!.video![1]),
    );
    for (final item in resource.dash!.video!) {
      item.baseUrl = item.baseUrl!.replaceFirst(
        'media.invalid',
        'fresh.invalid',
      );
      item.backupUrl = ['https://backup.invalid/${item.id}'];
    }
    resource.dash!.video!.add(video(32, 'avc1.640'));
    final fresh = DashSelection.refreshVideoLadder(resource, previous)!;
    expect(fresh.map((t) => t.id), [80, 64]);
    expect(
      fresh.every((t) => t.source.startsWith('https://fresh.invalid/')),
      true,
    );
    expect(
      fresh.every((t) => !t.urls.any((u) => u.contains('media.invalid'))),
      true,
    );
    expect(previous.every((t) => t.source.contains('media.invalid')), true);
    expect(fresh.clear, throwsUnsupportedError);
  });
  test(
    'refresh rejects missing quality, changed codec profile or missing URL',
    () {
      final previous = DashSelection.videoLadder(
        resource,
        track(resource.dash!.video![1]),
      );
      final last = resource.dash!.video!.removeLast();
      expect(DashSelection.refreshVideoLadder(resource, previous), isNull);
      resource.dash!.video!.add(last);
      last.codecs = 'avc1.641';
      expect(DashSelection.refreshVideoLadder(resource, previous), isNull);
      last
        ..codecs = 'avc1.640'
        ..baseUrl = null;
      expect(DashSelection.refreshVideoLadder(resource, previous), isNull);
      expect(DashSelection.refreshVideoLadder(resource, const []), isEmpty);
    },
  );
}
