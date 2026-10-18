import 'package:PiliPlus/router/video_entry_transition.dart';
import 'package:PiliPlus/router/video_route_app.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  tearDown(Get.reset);
  for (final mode in ['complete', 'cancel', 'rotate']) {
    testWidgets(
      'entry paints live content and releases snapshot: $mode',
      (tester) async {
        final cover = GlobalKey();
        final updates = ValueNotifier(0);
        addTearDown(updates.dispose);
        VideoEntrySnapshot? snapshot;
        await tester.pumpWidget(
          VideoRouteApp(
            initialRoute: '/',
            getPages: [
              GetPage(
                name: '/',
                page: () => Align(
                  alignment: Alignment.bottomLeft,
                  child: RepaintBoundary(
                    key: cover,
                    child: const SizedBox(
                      width: 160,
                      height: 90,
                      child: ColoredBox(color: Colors.orange),
                    ),
                  ),
                ),
              ),
              GetPage(
                name: '/videoV',
                page: () => Column(
                  children: [
                    SizedBox(
                      key: snapshot!.targetKey,
                      width: 800,
                      height: 400,
                      child: ValueListenableBuilder<int>(
                        valueListenable: updates,
                        builder: (_, value, _) => ColoredBox(
                          color: value == 0 ? Colors.blue : Colors.green,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        );
        await tester.pumpAndSettle();
        snapshot = VideoEntrySnapshot.capture(cover);
        expect(snapshot, isNotNull);
        expect(snapshot!.image, isNotNull);
        final result = Get.toNamed<dynamic>(
          '/videoV',
          arguments: {'videoEntry': snapshot},
        );
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 50));
        expect((Get.routing.route! as GetPageRoute).allowSnapshotting, isFalse);
        expect(
          (Get.routing.route! as GetPageRoute).transitionDuration,
          const Duration(milliseconds: 200),
        );
        expect(
          tester.widget<RawImage>(find.byType(RawImage)).fit,
          BoxFit.contain,
        );
        updates.value = 1;
        await tester.pump(const Duration(milliseconds: 16));
        expect(
          find.byWidgetPredicate(
            (widget) => widget is ColoredBox && widget.color == Colors.green,
          ),
          findsOneWidget,
        );
        if (mode == 'cancel') Get.back<dynamic>(result: 42);
        if (mode == 'rotate') {
          tester.view.physicalSize =
              const Size(600, 800) * tester.view.devicePixelRatio;
          addTearDown(tester.view.resetPhysicalSize);
          await tester.pump();
          expect(find.byType(RawImage), findsNothing);
        }
        await tester.pumpAndSettle();
        expect(find.byType(RawImage), findsNothing);
        expect(snapshot.image, isNull);
        if (mode != 'cancel') {
          expect(
            find.byWidgetPredicate(
              (widget) => widget is ColoredBox && widget.color == Colors.green,
            ),
            findsOneWidget,
          );
          Get.back<dynamic>(result: 42);
          await tester.pumpAndSettle();
        }
        expect(await result, 42);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
      variant: TargetPlatformVariant.only(TargetPlatform.android),
    );
  }
  testWidgets('missing, unpainted and reduced-motion cells skip the snapshot', (
    tester,
  ) async {
    expect(VideoEntrySnapshot.capture(GlobalKey()), isNull);
    final key = GlobalKey();
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(
          size: Size(800, 600),
          disableAnimations: true,
        ),
        child: Directionality(
          textDirection: TextDirection.ltr,
          child: RepaintBoundary(
            key: key,
            child: const SizedBox(width: 100, height: 100),
          ),
        ),
      ),
    );
    expect(VideoEntrySnapshot.capture(key), isNull);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));
}
