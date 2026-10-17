import 'package:PiliPlus/router/video_route_app.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

class _PaintProbe extends LeafRenderObjectWidget {
  const _PaintProbe(this.value, this.painted);
  final int value;
  final List<int> painted;
  @override
  RenderObject createRenderObject(BuildContext context) =>
      _ProbeBox(value, painted);
  @override
  void updateRenderObject(BuildContext context, _ProbeBox renderObject) {
    renderObject
      ..value = value
      ..markNeedsPaint();
  }
}

class _ProbeBox extends RenderBox {
  _ProbeBox(this.value, this.painted);
  int value;
  final List<int> painted;
  @override
  void performLayout() => size = constraints.constrain(const Size(100, 100));
  @override
  void paint(PaintingContext context, Offset offset) {
    painted.add(value);
    context.canvas.drawRect(
      offset & size,
      Paint()..color = const Color(0xFF112233),
    );
  }
}

void main() {
  tearDown(Get.reset);

  testWidgets(
    'video paints updates during transition and preserves navigation',
    (tester) async {
      final changes = ValueNotifier(0);
      addTearDown(changes.dispose);
      final painted = <int>[];
      final argument = Object();
      var bound = 0;
      final app = VideoRouteApp(
        initialRoute: '/',
        defaultTransition: Transition.native,
        getPages: [
          GetPage(name: '/', page: () => const SizedBox.expand()),
          GetPage(
            name: '/videoV',
            binding: BindingsBuilder(() => bound++),
            page: () => ValueListenableBuilder<int>(
              valueListenable: changes,
              builder: (context, value, child) => _PaintProbe(value, painted),
            ),
          ),
          GetPage(name: '/other', page: () => const SizedBox.expand()),
        ],
      );
      await tester.pumpWidget(app);
      await tester.pumpAndSettle();
      final result = Get.toNamed<dynamic>(
        '/videoV?sample=yes',
        arguments: argument,
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 90));
      final route = Get.routing.route! as GetPageRoute;
      expect(route.allowSnapshotting, isFalse);
      expect(route.animation!.value, lessThan(1));
      expect(Get.arguments, same(argument));
      expect(Get.parameters['sample'], 'yes');
      expect(bound, 1);
      changes.value = 7;
      await tester.pump(const Duration(milliseconds: 16));
      expect(painted, contains(7));
      await tester.pumpAndSettle();
      Get.back<dynamic>(result: 42);
      await tester.pumpAndSettle();
      expect(await result, 42);
      Get.toNamed<void>('/other');
      await tester.pumpAndSettle();
      expect((Get.routing.route! as GetPageRoute).allowSnapshotting, isTrue);
      await tester.pumpWidget(const SizedBox.shrink());
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets('non-Android video route keeps its original snapshot policy', (
    tester,
  ) async {
    await tester.pumpWidget(
      VideoRouteApp(
        initialRoute: '/',
        getPages: [
          GetPage(name: '/', page: () => const SizedBox.expand()),
          GetPage(name: '/videoV', page: () => const SizedBox.expand()),
        ],
      ),
    );
    await tester.pumpAndSettle();
    Get.toNamed<void>('/videoV');
    await tester.pumpAndSettle();
    expect((Get.routing.route! as GetPageRoute).allowSnapshotting, isTrue);
    await tester.pumpWidget(const SizedBox.shrink());
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));
}
