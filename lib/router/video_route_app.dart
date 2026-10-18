import 'package:PiliPlus/router/video_entry_transition.dart';
import 'package:flutter/foundation.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

class VideoRouteApp extends GetMaterialApp {
  const VideoRouteApp({
    super.key,
    super.title,
    super.theme,
    super.darkTheme,
    super.themeMode,
    super.localizationsDelegates,
    super.locale,
    super.fallbackLocale,
    super.supportedLocales,
    super.initialRoute,
    super.getPages,
    super.defaultTransition,
    super.builder,
    super.navigatorObservers,
    super.scrollBehavior,
  });

  @override
  Route<dynamic> generator(RouteSettings settings) {
    final route = super.generator(settings);
    if (!kIsWeb &&
        defaultTargetPlatform == TargetPlatform.android &&
        route is GetPageRoute &&
        Uri.tryParse(route.settings.name ?? '')?.path == '/videoV') {
      return _LiveVideoRoute(route);
    }
    return route;
  }
}

class _LiveVideoRoute extends GetPageRoute<dynamic> {
  _LiveVideoRoute(GetPageRoute<dynamic> route)
    : entry = route.settings.arguments is Map
          ? (route.settings.arguments as Map)['videoEntry']
                as VideoEntrySnapshot?
          : null,
      super(
        settings: route.settings,
        parameter: route.parameter,
        barrierDismissible: route.barrierDismissible,
        barrierColor: route.barrierColor,
        binding: route.binding,
        bindings: route.bindings,
        routeName: route.routeName,
        page: route.page,
        barrierLabel: route.barrierLabel,
        maintainState: route.maintainState,
        middlewares: route.middlewares,
      );

  // A transition snapshot prevents a newly created SurfaceView from being painted and attached.
  @override
  bool get allowSnapshotting => false;

  final VideoEntrySnapshot? entry;

  @override
  Duration get transitionDuration => entry == null
      ? super.transitionDuration
      : const Duration(milliseconds: 200);

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    final snapshot = entry;
    return snapshot == null
        ? super.buildTransitions(context, animation, secondaryAnimation, child)
        : VideoEntryTransition(
            snapshot: snapshot,
            animation: animation,
            child: child,
          );
  }

  @override
  void dispose() {
    entry?.dispose();
    super.dispose();
  }
}
