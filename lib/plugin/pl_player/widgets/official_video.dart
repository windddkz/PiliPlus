import 'package:PiliPlus/plugin/pl_player/official_player.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

class OfficialVideo extends StatelessWidget {
  const OfficialVideo({
    super.key,
    required this.player,
    this.aspectRatio,
    this.fit = BoxFit.contain,
    this.alignment = Alignment.center,
  });
  final OfficialPlayer player;
  final double? aspectRatio;
  final BoxFit fit;
  final Alignment alignment;

  @override
  Widget build(BuildContext context) => StreamBuilder<(int, int)>(
    stream: player.stream.size,
    initialData: (player.state.width, player.state.height),
    builder: (context, snapshot) {
      final (w, h) = snapshot.data!;
      final source = Size(
        aspectRatio == null
            ? (w > 0 ? w.toDouble() : 16)
            : (h > 0 ? h.toDouble() : 9) * aspectRatio!,
        h > 0 ? h.toDouble() : 9,
      );
      return LayoutBuilder(
        builder: (context, constraints) {
          final fitted = applyBoxFit(fit, source, constraints.biggest);
          if (fitted.source.isEmpty) return const SizedBox.shrink();
          final scaleX = source.width / fitted.source.width;
          final scaleY = source.height / fitted.source.height;
          final pixelRatio = MediaQuery.devicePixelRatioOf(context);
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!context.mounted) return;
            player.updateVideoTransform(
              scaleX,
              scaleY,
              ((1 - scaleX) *
                      fitted.destination.width *
                      alignment.x *
                      pixelRatio /
                      2)
                  .round(),
              ((1 - scaleY) *
                      fitted.destination.height *
                      alignment.y *
                      pixelRatio /
                      2)
                  .round(),
            );
          });
          // Keep the native window bounded; the renderer crops the source inside it.
          return Align(
            alignment: alignment,
            child: SizedBox(
              width: fitted.destination.width,
              height: fitted.destination.height,
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
            ),
          );
        },
      );
    },
  );
}
