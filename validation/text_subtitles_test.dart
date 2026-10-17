import 'package:flutter_test/flutter_test.dart';
import 'package:PiliPlus/plugin/pl_player/text_subtitles.dart';

void main() {
  test('SRT CRLF, cue identifiers, boundaries and overlapping lines', () {
    final subtitles = TextSubtitles.parse(
      '1\r\n00:00:01,250 --> 00:00:02,500\r\n第一行\r\n第二行\r\n\r\n'
      '2\r\n00:00:02,000 --> 00:00:03,000\r\n重叠',
    );
    expect(subtitles.at(const Duration(milliseconds: 1249)), '');
    expect(subtitles.at(const Duration(milliseconds: 1250)), '第一行\n第二行');
    expect(subtitles.at(const Duration(milliseconds: 2000)), '第一行\n第二行\n重叠');
    expect(subtitles.at(const Duration(milliseconds: 2500)), '重叠');
    expect(subtitles.at(const Duration(milliseconds: 3000)), '');
  });
  test('VTT header, comments, cue settings and unordered cue timestamps', () {
    final subtitles = TextSubtitles.parse(
      '\uFEFFWEBVTT\n\nNOTE ignored\n00:01.000 --> 00:02.000\ncomment\n\n'
      'cue-2\n00:03.000 --> 00:04.000 align:start\n后\n\n'
      '00:01.000 --> 00:02.000\n先',
    );
    expect(subtitles.at(const Duration(seconds: 1)), '先');
    expect(subtitles.at(const Duration(seconds: 3)), '后');
  });
  test('rejects malformed timing and unsupported styled tracks', () {
    for (final text in [
      '00:60:00.000 --> 00:61:00.000\nx',
      '00:00:03.000 --> 00:00:01.000\nx',
      '[Script Info]\nTitle: example',
      'not a subtitle',
    ]) {
      expect(() => TextSubtitles.parse(text), throwsFormatException);
    }
    expect(TextSubtitles.parse('WEBVTT\n\n').at(Duration.zero), '');
  });
}
