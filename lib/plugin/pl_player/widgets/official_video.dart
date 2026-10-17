import 'package:PiliPlus/plugin/pl_player/official_player.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

class OfficialVideo extends StatelessWidget {
  const OfficialVideo({super.key, required this.player, this.aspectRatio});
  final OfficialPlayer player;
  final double? aspectRatio;

  @override
  Widget build(BuildContext context) => StreamBuilder<(int, int)>(
    stream: player.stream.size,
    initialData: (player.state.width, player.state.height),
    builder: (context, snapshot) {
      final (w, h) = snapshot.data!;
      return AspectRatio(
        aspectRatio: aspectRatio ?? (w > 0 && h > 0 ? w / h : 16 / 9),
        child: PlatformViewLink(
          key: ValueKey(player.session),
          viewType: 'piliplus/official_surface',
          surfaceFactory: (context, controller) => AndroidViewSurface(
            controller: controller as AndroidViewController,
            gestureRecognizers: const {},
            hitTestBehavior: PlatformViewHitTestBehavior.opaque,
          ),
          onCreatePlatformView: (params) {
            final controller =
                PlatformViewsService.initExpensiveAndroidView(
                    id: params.id,
                    viewType: 'piliplus/official_surface',
                    layoutDirection: TextDirection.ltr,
                    creationParams: {'session': player.session},
                    creationParamsCodec: const StandardMessageCodec(),
                  )
                  ..addOnPlatformViewCreatedListener(
                    params.onPlatformViewCreated,
                  )
                  ..create();
            return controller;
          },
        ),
      );
    },
  );
}
