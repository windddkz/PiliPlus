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
