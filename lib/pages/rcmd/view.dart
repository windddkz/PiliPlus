import 'dart:async';

import 'package:PiliPlus/common/skeleton/video_card_v.dart';
import 'package:PiliPlus/common/sliver_single_child_delegate.dart';
import 'package:PiliPlus/common/style.dart';
import 'package:PiliPlus/common/widgets/flutter/refresh_indicator.dart';
import 'package:PiliPlus/common/widgets/loading_widget/http_error.dart';
import 'package:PiliPlus/common/widgets/route_aware_mixin.dart';
import 'package:PiliPlus/common/widgets/video_card/video_card_v.dart';
import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/http/preload_queue.dart';
import 'package:PiliPlus/http/video.dart';
import 'package:PiliPlus/models/model_rec_video_item.dart';
import 'package:PiliPlus/pages/home/controller.dart';
import 'package:PiliPlus/pages/main/controller.dart';
import 'package:PiliPlus/pages/rcmd/controller.dart';
import 'package:PiliPlus/pages/rcmd/preload_candidates.dart';
import 'package:PiliPlus/utils/grid.dart';
import 'package:PiliPlus/utils/id_utils.dart';
import 'package:PiliPlus/utils/storage_pref.dart';
import 'package:get/get.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:material_ui/material_ui.dart';

class RcmdPage extends StatefulWidget {
  const RcmdPage({super.key});

  @override
  State<RcmdPage> createState() => _RcmdPageState();
}

class _RcmdPageState extends State<RcmdPage>
    with
        AutomaticKeepAliveClientMixin,
        RouteAware,
        RouteAwareMixin,
        WidgetsBindingObserver {
  final controller = Get.put(RcmdController());
  List<dynamic>? _prefetchList;
  Timer? _prefetchTimer;
  Timer? _scrollPrefetchTimer;
  bool _prefetching = false;
  bool _prefetchAgain = false;
  int _prefetchRevision = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    controller.scrollController.addListener(_schedulePrefetch);
    _prefetchTimer = Timer.periodic(
      const Duration(seconds: 10),
      (_) => _retainPrefetch(),
    );
  }

  void _schedulePrefetch() {
    _prefetchRevision++;
    _scrollPrefetchTimer?.cancel();
    _scrollPrefetchTimer = Timer(
      const Duration(milliseconds: 250),
      _retainPrefetch,
    );
  }

  void _retainPrefetch() {
    final items = _prefetchList;
    if (items == null || !_canPrefetch) {
      VideoHttp.preloadQueue.pause(this);
      return;
    }
    if (_prefetching) {
      _prefetchAgain = true;
      return;
    }
    _prefetching = true;
    unawaited(
      _prefetch(items).catchError((Object _) {}).whenComplete(() {
        _prefetching = false;
        if (_prefetchAgain) {
          _prefetchAgain = false;
          _retainPrefetch();
        }
      }),
    );
  }

  @override
  void didPopNext() => _retainPrefetch();

  @override
  void didPushNext() => VideoHttp.preloadQueue.pause(this);

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _retainPrefetch();
    } else {
      VideoHttp.preloadQueue.pause(this);
    }
  }

  @override
  void dispose() {
    VideoHttp.preloadQueue.pause(this);
    _prefetchTimer?.cancel();
    _scrollPrefetchTimer?.cancel();
    controller.scrollController.removeListener(_schedulePrefetch);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  bool get _canPrefetch {
    if (!mounted ||
        !VideoHttp.prefetchEnabled ||
        !controller.scrollController.hasClients ||
        controller.scrollController.position.isScrollingNotifier.value ||
        WidgetsBinding.instance.lifecycleState != AppLifecycleState.resumed ||
        ModalRoute.of(context)?.isCurrent != true) {
      return false;
    }
    final main = Get.find<MainController>();
    final home = Get.find<HomeController>();
    return main.navigationBars[main.selectedIndex.value] == .home &&
        !home.tabController.indexIsChanging &&
        home.tabs[home.tabController.index] == .rcmd;
  }

  Future<void> _prefetch(List<dynamic> items) async {
    if (!_canPrefetch) return;
    final revision = _prefetchRevision;
    final network = await Connectivity().checkConnectivity();
    if (!network.contains(ConnectivityResult.wifi) || !_canPrefetch) {
      VideoHttp.preloadQueue.pause(this);
      return;
    }
    final layout = gridDelegate.layoutCache;
    if (layout == null) return;
    final position = controller.scrollController.position;
    final visible = visibleFeedIndices(
      layout,
      position.pixels - Style.cardSpace,
      position.viewportDimension * 2,
      items.length,
      controller.lastRefreshAt,
    );
    final ordered = <int>{...visible, ...Iterable.generate(items.length)};
    final candidates = <PreloadCandidate>[];
    for (final index in ordered) {
      if (revision != _prefetchRevision || !identical(items, _prefetchList)) {
        return;
      }
      final item = items[index];
      if (item is BaseRcmdVideoItemModel &&
          item.goto == 'av' &&
          item.cid != null &&
          item.cid! > 0 &&
          (item.bvid != null || item.aid != null)) {
        candidates.add((
          bvid: item.bvid ?? IdUtils.av2bv(item.aid!),
          cid: item.cid!,
        ));
      }
    }
    VideoHttp.preloadQueue.refill(this, candidates);
  }

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final colorScheme = ColorScheme.of(context);
    return Container(
      clipBehavior: .hardEdge,
      margin: const .symmetric(horizontal: Style.safeSpace),
      decoration: const BoxDecoration(borderRadius: Style.mdRadius),
      child: refreshIndicator(
        onRefresh: controller.onRefresh,
        child: CustomScrollView(
          controller: controller.scrollController,
          physics: const AlwaysScrollableScrollPhysics(),
          slivers: [
            SliverPadding(
              padding: const .only(top: Style.cardSpace, bottom: 100),
              sliver: Obx(
                () => _buildBody(colorScheme, controller.loadingState.value),
              ),
            ),
          ],
        ),
      ),
    );
  }

  late final gridDelegate = SliverGridDelegateWithExtentAndRatio(
    mainAxisSpacing: Style.cardSpace,
    crossAxisSpacing: Style.cardSpace,
    maxCrossAxisExtent: Pref.recommendCardWidth,
    childAspectRatio: Style.aspectRatio,
    mainAxisExtent: MediaQuery.textScalerOf(context).scale(90),
  );

  Widget _buildBody(
    ColorScheme colorScheme,
    LoadingState<List<dynamic>?> loadingState,
  ) {
    if (loadingState case Success(:final response?)) {
      final newList = !identical(_prefetchList, response);
      _prefetchList = response;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !identical(_prefetchList, response)) return;
        _schedulePrefetch();
        // New results are ready after layout; keep the timer for scroll settling.
        if (newList) _retainPrefetch();
      });
    } else {
      _prefetchList = null;
      _prefetchRevision++;
    }
    return switch (loadingState) {
      Loading() => _buildSkeleton,
      Success(:final response) =>
        response != null && response.isNotEmpty
            ? SliverGrid.builder(
                gridDelegate: gridDelegate,
                itemBuilder: (context, index) {
                  if (index == response.length - 1) {
                    controller.onLoadMore();
                  }
                  if (controller.lastRefreshAt != null) {
                    if (controller.lastRefreshAt == index) {
                      return GestureDetector(
                        onTap: () => controller
                          ..animateToTop()
                          ..onRefresh(),
                        child: Card(
                          child: Container(
                            alignment: Alignment.center,
                            padding: const .symmetric(horizontal: 10),
                            child: Text(
                              '上次看到这里\n点击刷新',
                              textAlign: .center,
                              style: TextStyle(
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                          ),
                        ),
                      );
                    }
                    final actualIndex = index > controller.lastRefreshAt!
                        ? index - 1
                        : index;
                    return VideoCardV(
                      videoItem: response[actualIndex],
                      onRemove: () {
                        if (controller.lastRefreshAt != null &&
                            actualIndex < controller.lastRefreshAt!) {
                          controller.lastRefreshAt =
                              controller.lastRefreshAt! - 1;
                        }
                        controller.loadingState
                          ..value.data!.removeAt(actualIndex)
                          ..refresh();
                      },
                    );
                  } else {
                    return VideoCardV(
                      videoItem: response[index],
                      onRemove: () => controller.loadingState
                        ..value.data!.removeAt(index)
                        ..refresh(),
                    );
                  }
                },
                itemCount: controller.lastRefreshAt != null
                    ? response.length + 1
                    : response.length,
              )
            : HttpError(onReload: controller.onReload),
      Error(:final errMsg) => HttpError(
        errMsg: errMsg,
        onReload: controller.onReload,
      ),
    };
  }

  Widget get _buildSkeleton => SliverGrid(
    gridDelegate: gridDelegate,
    delegate: const SliverSingleChildDelegate(
      count: 10,
      child: VideoCardVSkeleton(),
    ),
  );
}
