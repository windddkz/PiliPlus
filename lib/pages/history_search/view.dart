import 'package:PiliPlus/common/widgets/video_preload_scope.dart';
import 'package:PiliPlus/pages/rcmd/preload_candidates.dart';
import 'package:PiliPlus/models_new/history/data.dart';
import 'package:PiliPlus/models_new/history/list.dart';
import 'package:PiliPlus/pages/common/search/common_search_page.dart';
import 'package:PiliPlus/pages/history/widgets/item.dart';
import 'package:PiliPlus/pages/history_search/controller.dart';
import 'package:PiliPlus/utils/grid.dart';
import 'package:PiliPlus/utils/utils.dart';
import 'package:get/get.dart';
import 'package:material_ui/material_ui.dart';

class HistorySearchPage extends StatefulWidget {
  const HistorySearchPage({super.key});

  @override
  State<HistorySearchPage> createState() => _HistorySearchPageState();
}

class _HistorySearchPageState
    extends
        CommonSearchPageState<HistorySearchPage, HistoryData, HistoryItemModel>
    with GridMixin {
  @override
  final HistorySearchController controller = Get.put(
    HistorySearchController(),
    tag: Utils.generateRandomString(8),
  );

  @override
  Widget buildList(List<HistoryItemModel> list) {
    return VideoPreloadScope(
      candidates: [for (final item in list) historyPreloadCandidate(item)],
      positions: {
        for (final item in list)
          if (historyPreloadCandidate(item) case final candidate?)
            if (item.playbackProgress case final position? when position >= 0)
              candidate: position,
      },
      gridDelegate: gridDelegate,
      child: SliverGrid.builder(
        gridDelegate: gridDelegate,
        itemBuilder: (context, index) {
          if (index == list.length - 1) {
            controller.onLoadMore();
          }
          final item = list[index];
          return HistoryItem(
            item: item,
            ctr: controller,
            onDelete: (kid, business) =>
                controller.onDelHistory(index, kid, business),
          );
        },
        itemCount: list.length,
      ),
    );
  }
}
