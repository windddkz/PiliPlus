import 'package:PiliPlus/common/widgets/video_preload_scope.dart';
import 'package:PiliPlus/utils/id_utils.dart';
import 'package:PiliPlus/pages/main/controller.dart';
import 'package:PiliPlus/pages/home/controller.dart';
import 'package:PiliPlus/models/common/home_tab_type.dart';
import 'package:PiliPlus/pages/rank/controller.dart';
import 'package:PiliPlus/models/common/rank_type.dart';
import 'package:PiliPlus/common/widgets/flutter/refresh_indicator.dart';
import 'package:PiliPlus/common/widgets/loading_widget/http_error.dart';
import 'package:PiliPlus/common/widgets/video_card/video_card_h.dart';
import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/models/model_hot_video_item.dart';
import 'package:PiliPlus/pages/rank/zone/controller.dart';
import 'package:PiliPlus/pages/rank/zone/widget/pgc_rank_item.dart';
import 'package:PiliPlus/utils/grid.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

class ZonePage extends StatefulWidget {
  const ZonePage({super.key, this.rid, this.seasonType, this.inHome = false});
  final bool inHome;

  final int? rid;
  final int? seasonType;

  @override
  State<ZonePage> createState() => _ZonePageState();
}

class _ZonePageState extends State<ZonePage>
    with AutomaticKeepAliveClientMixin, GridMixin {
  late final ZoneController controller;

  @override
  void initState() {
    controller = Get.put(
      ZoneController(rid: widget.rid, seasonType: widget.seasonType),
      tag: '${widget.rid}${widget.seasonType}',
    );
    super.initState();
  }

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return refreshIndicator(
      onRefresh: controller.onRefresh,
      child: CustomScrollView(
        controller: controller.scrollController,
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.only(top: 7, bottom: 100),
            sliver: Obx(() => _buildBody(controller.loadingState.value)),
          ),
        ],
      ),
    );
  }

  Widget _buildBody(LoadingState<List<dynamic>?> loadingState) {
    return switch (loadingState) {
      Loading() => gridSkeleton,
      Success(:final response) =>
        response != null && response.isNotEmpty
            ? VideoPreloadScope(
                isActive: () {
                  if (widget.inHome) {
                    final home = Get.find<HomeController>();
                    final main = Get.find<MainController>();
                    if (main.navigationBars[main.selectedIndex.value] !=
                            .home ||
                        home.tabs[home.tabController.index] !=
                            HomeTabType.rank) {
                      return false;
                    }
                  }
                  final zone = RankType
                      .values[Get.find<RankController>().tabController.index];
                  return zone.rid == widget.rid &&
                      zone.seasonType == widget.seasonType;
                },
                activity: Listenable.merge([
                  if (widget.inHome) Get.find<HomeController>().tabController,
                  if (widget.inHome)
                    Get.find<MainController>().controller as Listenable,
                  Get.find<RankController>().tabController,
                ]),
                candidates: [
                  for (final item in response)
                    if (item is HotVideoItemModel &&
                        item.cid != null &&
                        item.cid! > 0 &&
                        (item.bvid != null || item.aid != null))
                      (
                        bvid: item.bvid ?? IdUtils.av2bv(item.aid!),
                        cid: item.cid!,
                      )
                    else
                      null,
                ],
                gridDelegate: gridDelegate,
                child: SliverGrid.builder(
                  gridDelegate: gridDelegate,
                  itemBuilder: (context, index) {
                    final item = response[index];
                    if (item is HotVideoItemModel) {
                      return VideoCardH(
                        videoItem: item,
                        onRemove: () => controller.loadingState
                          ..value.data!.removeAt(index)
                          ..refresh(),
                      );
                    }
                    return PgcRankItem(item: item);
                  },
                  itemCount: response.length,
                ),
              )
            : HttpError(onReload: controller.onReload),
      Error(:final errMsg) => HttpError(
        errMsg: errMsg,
        onReload: controller.onReload,
      ),
    };
  }
}
