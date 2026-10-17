import 'package:PiliPlus/models_new/live/live_room_play_info/codec.dart';
import 'package:PiliPlus/models_new/live/live_room_play_info/format.dart';
import 'package:PiliPlus/models_new/live/live_room_play_info/stream.dart';
import 'package:PiliPlus/models_new/live/live_room_play_info/url_info.dart';
import 'package:PiliPlus/plugin/pl_player/models/data_source.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  CodecItem item(String codec, int quality, String path) => CodecItem(
    codecName: codec,
    currentQn: quality,
    acceptQn: [10000, 400],
    baseUrl: path,
    urlInfo: [UrlInfo(host: 'https://cdn.example.invalid', extra: '?owned=1')],
  );
  test('refresh matches actual protocol, format, codec and quality despite reordered lists', () {
    final expected = item('hevc', 10000, '/new.flv');
    final streams = [
      Stream(
        protocolName: 'http_hls',
        format: [
          Format(formatName: 'ts', codec: [item('hevc', 10000, '/wrong.m3u8')]),
        ],
      ),
      Stream(
        protocolName: 'http_stream',
        format: [
          Format(
            formatName: 'fmp4',
            codec: [item('hevc', 10000, '/wrong.mp4')],
          ),
          Format(
            formatName: 'flv',
            codec: [
              item('avc', 10000, '/wrong-codec.flv'),
              item('hevc', 400, '/wrong-quality.flv'),
              expected,
            ],
          ),
        ],
      ),
    ];
    expect(
      matchLiveCodec(
        streams,
        protocol: 'http_stream',
        format: 'flv',
        codec: 'hevc',
        quality: 10000,
      ),
      same(expected),
    );
    expect(
      matchLiveCodec(
        streams,
        protocol: 'http_stream',
        format: 'flv',
        codec: 'av1',
        quality: 10000,
      ),
      isNull,
    );
    expect(
      matchLiveCodec(
        streams,
        protocol: 'http_stream',
        format: 'flv',
        codec: 'hevc',
        quality: 20000,
      ),
      isNull,
    );
    expect(
      matchLiveCodec(
        streams,
        protocol: null,
        format: 'flv',
        codec: 'hevc',
        quality: 10000,
      ),
      isNull,
    );
  });
  test(
    'accepted qualities do not substitute for the actual returned quality',
    () {
      final streams = [
        Stream(
          protocolName: 'http_stream',
          format: [
            Format(
              formatName: 'flv',
              codec: [item('hevc', 400, '/downscaled.flv')],
            ),
          ],
        ),
      ];
      expect(
        matchLiveCodec(
          streams,
          protocol: 'http_stream',
          format: 'flv',
          codec: 'hevc',
          quality: 10000,
        ),
        isNull,
      );
    },
  );
  test(
    'live backup descriptors remain tied to the selected URL and quality',
    () {
      final track = DashTrack(
        source: 'https://cdn.example.invalid/live.flv?owned=1',
        id: 10000,
        bandwidth: null,
        urls: [
          'https://cdn.example.invalid/live.flv?owned=1',
          'https://backup.example.invalid/live.flv?owned=2',
        ],
      );
      expect(track.forSource(track.source)?['backups'], [
        'https://backup.example.invalid/live.flv?owned=2',
      ]);
      expect(
        track.forSource('https://another.example.invalid/live.flv'),
        isNull,
      );
    },
  );
}
