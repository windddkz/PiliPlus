import 'package:PiliPlus/models/common/video/video_decode_type.dart';
import 'package:PiliPlus/utils/storage.dart';
import 'package:PiliPlus/utils/storage_key.dart';
import 'package:PiliPlus/utils/storage_pref.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive_ce/hive.dart';

class Settings extends Fake implements Box<dynamic> {
  final entries = <dynamic, dynamic>{};
  @override
  dynamic get(dynamic key, {dynamic defaultValue}) =>
      entries.containsKey(key) ? entries[key] : defaultValue;
}

void main() {
  final settings = Settings();
  GStorage.setting = settings;
  setUp(settings.entries.clear);
  test('unknown capability leaves legacy default and storage untouched', () {
    expect(Pref.playbackCodecs(), [
      VideoDecodeFormatType.AVC,
      VideoDecodeFormatType.AV1,
    ]);
    expect(Pref.playbackCodecs(cellular: true), Pref.preferCodecs);
    expect(settings.entries, isEmpty);
  });
  test('explicit WiFi codec order also supplies the cellular default', () {
    settings.entries[SettingBoxKey.preferCodecs] = ['HEVC', 'AVC'];
    expect(Pref.playbackCodecs(), [
      VideoDecodeFormatType.HEVC,
      VideoDecodeFormatType.AVC,
    ]);
    expect(Pref.playbackCodecs(cellular: true), Pref.playbackCodecs());
    expect(Pref.officialCodecDefault(), false);
    expect(Pref.officialCodecDefault(cellular: true), false);
  });
  test('cellular override has its own order without changing WiFi', () {
    settings.entries.addAll({
      SettingBoxKey.preferCodecs: ['AV1', 'HEVC'],
      SettingBoxKey.preferCodecsCellular: ['AVC'],
    });
    expect(Pref.playbackCodecs(), [
      VideoDecodeFormatType.AV1,
      VideoDecodeFormatType.HEVC,
    ]);
    expect(Pref.playbackCodecs(cellular: true), [VideoDecodeFormatType.AVC]);
  });
  test('explicit empty preference preserves existing fallback semantics', () {
    settings.entries[SettingBoxKey.preferCodecs] = <String>[];
    expect(Pref.playbackCodecs(), isEmpty);
    expect(Pref.officialCodecDefault(), false);
  });
  test(
    'mpv and software decoding do not opt in to official codec defaults',
    () {
      settings.entries[SettingBoxKey.officialAudioBackend] = false;
      expect(Pref.officialCodecDefault(), false);
      expect(Pref.playbackCodecs(), Pref.preferCodecs);
      settings.entries[SettingBoxKey.officialAudioBackend] = true;
      settings.entries[SettingBoxKey.enableHA] = false;
      expect(Pref.officialCodecDefault(), false);
      expect(Pref.playbackCodecs(), Pref.preferCodecs);
    },
  );
}
