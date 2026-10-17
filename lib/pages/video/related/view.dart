import 'dart:async';

import 'package:flutter/rendering.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:PiliPlus/common/widgets/route_aware_mixin.dart';
import 'package:PiliPlus/http/video.dart';
import 'package:PiliPlus/pages/rcmd/preload_candidates.dart';
import 'package:PiliPlus/utils/id_utils.dart';
import 'package:PiliPlus/common/widgets/loading_widget/http_error.dart';
import 'package:PiliPlus/common/widgets/video_card/video_card_h.dart';
import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/models/model_hot_video_item.dart';
import 'package:PiliPlus/pages/video/related/controller.dart';
import 'package:PiliPlus/utils/extension/get_ext.dart';
import 'package:PiliPlus/utils/grid.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

class RelatedVideoPanel extends StatefulWidget {
  const RelatedVideoPanel({super.key, required this.heroTag});
  final String heroTag;
  @override
  State<RelatedVideoPanel> createState() => _RelatedVideoPanelState();
}

class _RelatedVideoPanelState extends State<RelatedVideoPanel>
    with GridMixin, RouteAware, RouteAwareMixin, WidgetsBindingObserver {
  late final RelatedController _relatedController;
  Timer? _preloadTimer, _scrollTimer;
  ScrollPosition? _scroll;
  List<HotVideoItemModel>? _candidates;
  bool _checking = false;

  bool get _active =>
      mounted &&
      VideoHttp.prefetchEnabled &&
      ModalRoute.of(context)?.isCurrent == true &&
      WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;

  void _schedulePreload() {
    _scrollTimer?.cancel();
    _scrollTimer = Timer(const Duration(milliseconds: 250), _refill);
  }

  Future<void> _refill() async {
    if (!_active || _candidates == null) {
      VideoHttp.preloadQueue.pause(this);
      return;
    }
    if (_checking || _scroll?.isScrollingNotifier.value == true) return;
    _checking = true;
    try {
      final network = await Connectivity().checkConnectivity();
      if (!_active || !network.contains(ConnectivityResult.wifi)) {
        VideoHttp.preloadQueue.pause(this);
        return;
      }
      if (!mounted) return;
      final items = _candidates!;
      final layout = gridDelegate.layoutCache;
      final render = context.findRenderObject();
      final near = layout != null && render is RenderSliver
          ? visibleFeedIndices(
              layout,
              render.constraints.scrollOffset,
              MediaQuery.sizeOf(context).height * 2,
              items.length,
              null,
            )
          : const <int>[];
      final ordered = <int>{...near, ...Iterable.generate(items.length)};
      VideoHttp.preloadQueue.refill(this, [
        for (final index in ordered)
          if (items[index].cid != null &&
              items[index].cid! > 0 &&
              (items[index].bvid != null || items[index].aid != null))
            (
              bvid: items[index].bvid ?? IdUtils.av2bv(items[index].aid!),
              cid: items[index].cid!,
            ),
      ]);
    } catch (_) {
      VideoHttp.preloadQueue.pause(this);
    } finally {
      _checking = false;
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final scroll = Scrollable.maybeOf(context)?.position;
    if (!identical(_scroll, scroll)) {
      _scroll?.removeListener(_schedulePreload);
      _scroll = scroll?..addListener(_schedulePreload);
    }
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
    _preloadTimer?.cancel();
    _scrollTimer?.cancel();
    _scroll?.removeListener(_schedulePreload);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _preloadTimer = Timer.periodic(
      const Duration(seconds: 10),
      (_) => _refill(),
    );
    _relatedController = Get.putOrFind(
      RelatedController.new,
      tag: widget.heroTag,
    );
  }

  @override
  Widget build(BuildContext context) {
    return SliverPadding(
      padding: const EdgeInsets.only(top: 7, bottom: 100),
      sliver: Obx(() => _buildBody(_relatedController.loadingState.value)),
    );
  }

  Widget _buildBody(LoadingState<List<HotVideoItemModel>?> loadingState) {
    final items = loadingState.dataOrNull;
    if (!identical(_candidates, items)) {
      _candidates = items;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_refill());
      });
    }
    return switch (loadingState) {
      Loading() => gridSkeleton,
      Success(:final response) =>
        response != null && response.isNotEmpty
            ? SliverGrid.builder(
                gridDelegate: gridDelegate,
                itemBuilder: (context, index) {
                  return VideoCardH(
                    videoItem: response[index],
                    onRemove: () => _relatedController.loadingState
                      ..value.data!.removeAt(index)
                      ..refresh(),
                  );
                },
                itemCount: response.length,
              )
            : const SliverToBoxAdapter(),
      Error(:final errMsg) => HttpError(
        errMsg: errMsg,
        onReload: _relatedController.onReload,
      ),
    };
  }
}
