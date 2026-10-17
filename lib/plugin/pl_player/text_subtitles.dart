/// Plain WebVTT/SRT cues used by Bilibili subtitles and local text tracks.
class TextSubtitles {
  final List<({int start, int end, String text})> cues;
  TextSubtitles._(this.cues);

  factory TextSubtitles.parse(String input) {
    final cues = <({int start, int end, String text})>[];
    final timing = RegExp(
      r'^(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})$',
    );
    int timestamp(String value) {
      final match = timing.firstMatch(value);
      if (match == null) throw const FormatException('无效字幕时间');
      final minutes = int.parse(match[2]!);
      final seconds = int.parse(match[3]!);
      if (minutes >= 60 || seconds >= 60) {
        throw const FormatException('无效字幕时间');
      }
      return ((int.parse(match[1] ?? '0') * 60 + minutes) * 60 + seconds) *
              1000 +
          int.parse(match[4]!);
    }

    final text = input.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
    if (text.trimLeft().startsWith('[Script Info]')) {
      throw const FormatException('官方内核暂不支持 ASS 字幕样式，请使用 VTT 或 SRT');
    }
    for (final block in text.split(RegExp(r'\n[ \t]*\n'))) {
      final lines = block.split('\n');
      if (RegExp(r'^(NOTE|STYLE|REGION)(\s|$)').hasMatch(lines.first.trim())) {
        continue;
      }
      final index = lines.indexWhere((line) => line.contains(' --> '));
      if (index < 0) continue;
      final bounds = lines[index].trim().split(RegExp(r'\s+-->\s+'));
      if (bounds.length != 2) throw const FormatException('无效字幕时间范围');
      final start = timestamp(bounds[0]);
      final end = timestamp(bounds[1].split(RegExp(r'\s+')).first);
      if (end <= start) throw const FormatException('字幕结束时间不晚于开始时间');
      cues.add((
        start: start,
        end: end,
        text: lines.skip(index + 1).join('\n').trim(),
      ));
    }
    if (cues.isEmpty && text.trim().isNotEmpty && text.trim() != 'WEBVTT') {
      throw const FormatException('没有可读取的 VTT/SRT 字幕');
    }
    cues.sort((a, b) => a.start.compareTo(b.start));
    return TextSubtitles._(cues);
  }

  String at(Duration position) {
    final time = position.inMilliseconds;
    final active = <String>[];
    for (final cue in cues) {
      if (cue.start > time) break;
      if (time < cue.end) active.add(cue.text);
    }
    return active.join('\n');
  }
}
