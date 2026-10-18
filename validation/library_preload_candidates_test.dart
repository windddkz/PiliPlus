import 'package:PiliPlus/models_new/fav/fav_detail/media.dart';
import 'package:PiliPlus/models_new/fav/fav_detail/ugc.dart';
import 'package:PiliPlus/models_new/history/history.dart';
import 'package:PiliPlus/models_new/history/list.dart';
import 'package:PiliPlus/pages/rcmd/preload_candidates.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'history includes the actual part and filters non-video/unknown parts',
    () {
      final item = HistoryItemModel(
        history: History(bvid: 'BV-test', cid: 123, business: 'archive'),
        progress: 120,
      );
      expect(historyPreloadCandidate(item), (bvid: 'BV-test', cid: 123));
      expect(item.playbackProgress, 120000);
      item.progress = -1;
      expect(item.playbackProgress, 0);
      for (final business in [
        'live',
        'article',
        'article-list',
        'pgc',
        'cheese',
      ]) {
        item.history.business = business;
        expect(historyPreloadCandidate(item), isNull);
      }
      item.history.business = 'archive';
      item.history.cid = null;
      expect(historyPreloadCandidate(item), isNull);
    },
  );
  test('favorites filter deleted, audio, OGV and missing media metadata', () {
    final item = FavDetailItemModel(
      type: 2,
      attr: 0,
      bvid: 'BV-test',
      ugc: Ugc(firstCid: 123),
    );
    expect(favoritePreloadCandidate(item), (bvid: 'BV-test', cid: 123));
    for (final attr in [1, 9]) {
      item.attr = attr;
      expect(favoritePreloadCandidate(item), isNull);
    }
    item.attr = 0;
    for (final type in [12, 24]) {
      item.type = type;
      expect(favoritePreloadCandidate(item), isNull);
    }
    item
      ..type = 2
      ..ugc = null;
    expect(favoritePreloadCandidate(item), isNull);
  });
}
