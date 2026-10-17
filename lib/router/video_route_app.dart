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
    : super(
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
}
