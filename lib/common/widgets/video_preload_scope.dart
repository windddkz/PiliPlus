import 'dart:async';
import 'dart:math' as math;

import 'package:PiliPlus/common/widgets/route_aware_mixin.dart';
import 'package:PiliPlus/http/preload_queue.dart';
import 'package:PiliPlus/http/video.dart';
import 'package:PiliPlus/pages/rcmd/preload_candidates.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

/// Keeps layout order and route ownership in the same place for video grids.
class VideoPreloadScope extends StatefulWidget {
  const VideoPreloadScope({
    super.key,
    required this.candidates,
    required this.gridDelegate,
    required this.child,
    this.isActive,
    this.activity,
    this.separator,
  });

  final List<PreloadCandidate?> candidates;
  final SliverGridDelegate gridDelegate;
  final Widget child;
  final bool Function()? isActive;
  final Listenable? activity;
  final int? separator;

  @override
  State<VideoPreloadScope> createState() => _VideoPreloadScopeState();
}

class _VideoPreloadScopeState extends State<VideoPreloadScope>
    with RouteAware, RouteAwareMixin, WidgetsBindingObserver {
  Timer? _timer, _settle;
  ScrollPosition? _scroll;
  bool _checking = false, _again = false;

  bool get _active =>
      mounted &&
      VideoHttp.prefetchEnabled &&
      ModalRoute.of(context)?.isCurrent == true &&
      WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed &&
      TickerMode.valuesOf(context).enabled &&
      (widget.isActive?.call() ?? true);

  void _schedule() {
    _settle?.cancel();
    _settle = Timer(const Duration(milliseconds: 250), _refill);
  }

  Future<void> _refill() async {
    if (!_active || widget.candidates.isEmpty) {
      VideoHttp.preloadQueue.pause(this);
      return;
    }
    if (_scroll?.isScrollingNotifier.value == true) return;
    if (_checking) {
      _again = true;
      return;
    }
    _checking = true;
    try {
      final network = await Connectivity().checkConnectivity();
      if (!_active || !network.contains(ConnectivityResult.wifi)) {
        VideoHttp.preloadQueue.pause(this);
        return;
      }
      if (!mounted) return;
      final render = context.findRenderObject();
      if (render is! RenderSliver || !render.attached) return;
      final candidates = widget.candidates;
      final layout = widget.gridDelegate.getLayout(render.constraints);
      final visible = visibleFeedIndices(
        layout,
        render.constraints.scrollOffset,
        render.constraints.remainingPaintExtent,
        candidates.length,
        widget.separator,
      );
      final visibleCount = {for (final index in visible) ?candidates[index]}
          .length;
      final near = visibleFeedIndices(
        layout,
        render.constraints.scrollOffset,
        MediaQuery.sizeOf(context).height * 2,
        candidates.length,
        widget.separator,
      );
      final ordered = <int>{...near, ...Iterable.generate(candidates.length)};
      VideoHttp.preloadQueue.refill(this, [
        for (final index in ordered) ?candidates[index],
      ], priorityCount: math.max(6, visibleCount));
    } catch (_) {
      VideoHttp.preloadQueue.pause(this);
    } finally {
      _checking = false;
      if (_again && mounted) {
        _again = false;
        _schedule();
      }
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.activity?.addListener(_schedule);
    _timer = Timer.periodic(const Duration(seconds: 10), (_) => _refill());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final scroll = Scrollable.maybeOf(context)?.position;
    if (!identical(_scroll, scroll)) {
      _scroll?.removeListener(_schedule);
      _scroll = scroll?..addListener(_schedule);
    }
    _schedule();
  }

  @override
  void didUpdateWidget(VideoPreloadScope oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.activity, widget.activity)) {
      oldWidget.activity?.removeListener(_schedule);
      widget.activity?.addListener(_schedule);
    }
    _schedule();
  }

  @override
  void didPopNext() => unawaited(_refill());
  @override
  void didPushNext() => VideoHttp.preloadQueue.pause(this);
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_refill());
    } else {
      VideoHttp.preloadQueue.pause(this);
    }
  }

  @override
  void dispose() {
    VideoHttp.preloadQueue.pause(this);
    _timer?.cancel();
    _settle?.cancel();
    _scroll?.removeListener(_schedule);
    widget.activity?.removeListener(_schedule);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
