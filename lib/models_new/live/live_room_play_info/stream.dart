import 'package:PiliPlus/models_new/live/live_room_play_info/format.dart';
import 'package:PiliPlus/models_new/live/live_room_play_info/codec.dart';

CodecItem? matchLiveCodec(
  Iterable<Stream> streams, {
  required String? protocol,
  required String? format,
  required String? codec,
  required int quality,
}) {
  if (protocol == null || format == null || codec == null || quality <= 0) {
    return null;
  }
  for (final stream in streams) {
    if (stream.protocolName != protocol) continue;
    for (final entry in stream.format) {
      if (entry.formatName != format) continue;
      for (final item in entry.codec) {
        if (item.codecName == codec && item.currentQn == quality) return item;
      }
    }
  }
  return null;
}

class Stream {
  String? protocolName;
  List<Format> format;

  Stream({this.protocolName, required this.format});

  factory Stream.fromJson(Map<String, dynamic> json) => Stream(
    protocolName: json['protocol_name'],
    format: (json['format'] as List<dynamic>)
        .map((e) => Format.fromJson(e as Map<String, dynamic>))
        .toList(),
  );
}
