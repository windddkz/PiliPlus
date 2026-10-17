import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:PiliPlus/plugin/pl_player/widgets/playback_subtitle_view.dart';

class _Player extends PlatformPlayer {
  _Player() : super(configuration: const PlayerConfiguration());
  void show(String text) {
    state.subtitle = Subtitle.raw(text);
    subtitleController.add(state.subtitle);
  }
}

void main() {
  testWidgets('subtitle view follows either backend and drops the old stream', (
    tester,
  ) async {
    final first = _Player()..state.subtitle = const Subtitle.raw('首条字幕');
    final second = _Player()..state.subtitle = const Subtitle.raw('新视频');
    Widget view(PlatformPlayer player, {bool visible = true}) => MaterialApp(
      home: PlaybackSubtitleView(
        player: player,
        configuration: SubtitleViewConfiguration(visible: visible),
      ),
    );
    await tester.pumpWidget(view(first));
    expect(find.text('首条字幕'), findsOneWidget);
    first.show('更新字幕');
    await tester.pump();
    expect(find.text('更新字幕'), findsOneWidget);
    await tester.pumpWidget(view(second));
    first.show('迟到的旧字幕');
    await tester.pump();
    expect(find.text('新视频'), findsOneWidget);
    expect(find.text('迟到的旧字幕'), findsNothing);
    await tester.pumpWidget(view(second, visible: false));
    expect(find.text('新视频'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    await first.dispose();
    await second.dispose();
  });
}
