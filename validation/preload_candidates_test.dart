import 'package:PiliPlus/pages/rcmd/preload_candidates.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const layout = SliverGridRegularTileLayout(
    crossAxisCount: 2,
    mainAxisStride: 110,
    crossAxisStride: 100,
    childMainAxisExtent: 100,
    childCrossAxisExtent: 100,
    reverseCrossAxis: false,
  );
  test('visible window excludes screen cache, gaps and exact boundaries', () {
    expect(visibleFeedIndices(layout, -10, 110, 20, null), [0, 1]);
    expect(visibleFeedIndices(layout, 100, 10, 20, null), isEmpty);
    expect(visibleFeedIndices(layout, 110, 220, 20, null), [2, 3, 4, 5]);
    expect(visibleFeedIndices(layout, 99, 12, 20, null), [0, 1, 2, 3]);
  });
  test('saved-feed separator maps back to data, including start and end', () {
    expect(visibleFeedIndices(layout, 110, 220, 8, 3), [2, 3, 4]);
    expect(visibleFeedIndices(layout, 0, 100, 8, 0), [0]);
    expect(visibleFeedIndices(layout, 220, 100, 4, 4), isEmpty);
  });
  test('short, empty and overscrolled feeds stay bounded', () {
    expect(visibleFeedIndices(layout, 0, 1000, 3, null), [0, 1, 2]);
    expect(visibleFeedIndices(layout, 0, 100, 0, null), isEmpty);
    expect(visibleFeedIndices(layout, -200, 100, 3, null), isEmpty);
    expect(visibleFeedIndices(layout, 1000, 100, 3, null), isEmpty);
    expect(visibleFeedIndices(layout, 0, 0, 3, null), isEmpty);
  });
}
