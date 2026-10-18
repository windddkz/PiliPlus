import 'package:PiliPlus/http/preload_queue.dart';
import 'package:PiliPlus/models_new/history/list.dart';
import 'package:PiliPlus/models_new/fav/fav_detail/media.dart';
import 'package:PiliPlus/utils/id_utils.dart';

import 'dart:math';

import 'package:flutter/rendering.dart';

Iterable<int> visibleFeedIndices(
  SliverGridLayout layout,
  double offset,
  double viewport,
  int length,
  int? separator,
) sync* {
  if (length <= 0 || viewport <= 0 || offset + viewport <= 0) return;
  final hasSeparator =
      separator != null && separator >= 0 && separator <= length;
  final start = max(0.0, offset);
  final end = offset + viewport;
  final last = min(
    length - 1 + (hasSeparator ? 1 : 0),
    layout.getMaxChildIndexForScrollOffset(end),
  );
  for (
    var index = layout.getMinChildIndexForScrollOffset(start);
    index <= last;
    index++
  ) {
    final tile = layout.getGeometryForChildIndex(index);
    if (tile.scrollOffset >= end ||
        tile.scrollOffset + tile.mainAxisExtent <= start ||
        (hasSeparator && index == separator)) {
      continue;
    }
    yield hasSeparator && index > separator ? index - 1 : index;
  }
}

PreloadCandidate? historyPreloadCandidate(HistoryItemModel item) {
  final history = item.history;
  if (history.business != null && history.business != 'archive') return null;
  final cid = history.cid;
  final bvid =
      history.bvid ??
      ((history.oid ?? 0) > 0 ? IdUtils.av2bv(history.oid!) : null);
  if (cid == null || cid <= 0 || bvid == null || bvid.isEmpty) return null;
  return (bvid: bvid, cid: cid);
}

PreloadCandidate? favoritePreloadCandidate(FavDetailItemModel item) {
  if (const [1, 9].contains(item.attr) ||
      (item.type != null && item.type != 2)) {
    return null;
  }
  final cid = item.ugc?.firstCid;
  final bvid = item.bvid;
  if (cid == null || cid <= 0 || bvid == null || bvid.isEmpty) return null;
  return (bvid: bvid, cid: cid);
}
