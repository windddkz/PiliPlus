import 'dart:async';

import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/http/play_url_prefetch.dart';
import 'package:PiliPlus/models/video/play/url.dart';
import 'package:flutter_test/flutter_test.dart';

PlayUrlModel fixture({String? url, int position = 0}) => PlayUrlModel.fromJson({
  'last_play_time': position,
  if (url != null)
    'durl': [
      {'url': url},
    ],
}, retainSnapshot: true);

void main() {
  const key = (bvid: 'BV-test', cid: 1, tryLook: false, voiceBalance: true);
  late Object account;
  late DateTime now;
  late PlayUrlPrefetch cache;
  setUp(() {
    account = Object();
    now = DateTime.utc(2026);
    cache = PlayUrlPrefetch(now: () => now);
  });
  test(
    'visible candidate retains one response without downloading again',
    () async {
      final model = fixture();
      var loads = 0;
      await cache.preload(account, key, () async {
        loads++;
        return Success(model);
      });
      for (var i = 0; i < 7; i++) {
        now = now.add(const Duration(seconds: 10));
        expect(await cache.retain(account, key), isNot(same(model)));
      }
      expect(loads, 1);
      now = now.add(PlayUrlPrefetch.lifetime);
      expect(await cache.retain(account, key), isNull);
      expect(await cache.take(account, key), isNull);
    },
  );
  test('retention never revives an expired URL or crosses accounts', () async {
    final deadline =
        now.add(const Duration(seconds: 25)).millisecondsSinceEpoch ~/ 1000;
    final model = fixture(url: 'https://media.invalid/a?deadline=$deadline');
    await cache.preload(account, key, () async => Success(model));
    now = now.add(const Duration(seconds: 10));
    expect(await cache.retain(account, key), isNot(same(model)));
    now = now.add(const Duration(seconds: 10));
    expect(await cache.retain(account, key), isNull);
    expect(await cache.take(account, key), isNull);
    await cache.preload(account, key, () async => Success(fixture()));
    expect(await cache.retain(Object(), key), isNull);
  });
  test(
    'concurrent readers keep independent models and retain the entry',
    () async {
      final model = fixture();
      await cache.preload(account, key, () async => Success(model));
      final retained = cache.retain(account, key);
      final taken = cache.take(account, key);
      expect(await retained, isNotNull);
      expect(await taken, isNot(same(model)));
      expect(await cache.take(account, key), isNotNull);
    },
  );
  test('retention cannot claim an unfinished request', () async {
    final ready = Completer<LoadingState<PlayUrlModel>>();
    final loading = cache.preload(account, key, () => ready.future);
    expect(await cache.retain(account, key), isNull);
    ready.complete(Success(fixture()));
    await loading;
    expect(await cache.retain(account, key), isNotNull);
  });
  test(
    'in-flight response supports multiple isolated consumers and one load',
    () async {
      final ready = Completer<LoadingState<PlayUrlModel>>();
      var calls = 0;
      Future<LoadingState<PlayUrlModel>> load() {
        calls++;
        return ready.future;
      }

      final first = cache.preload(account, key, load);
      final duplicate = cache.preload(account, key, load);
      final taken = cache.take(account, key);
      final secondTake = cache.take(account, key);
      final model = fixture(position: 12345);
      ready.complete(Success(model));
      final one = await taken;
      final two = await secondTake;
      expect(one?.lastPlayTime, 12345);
      expect(two, isNot(same(one)));
      one!.lastPlayTime = 99;
      expect(two?.lastPlayTime, 12345);
      await Future.wait([first, duplicate]);
      expect(calls, 1);
      expect(await cache.take(account, key), isNotNull);
    },
  );
  test(
    'different account and late response cannot reuse a prior account',
    () async {
      final ready = Completer<LoadingState<PlayUrlModel>>();
      final preload = cache.preload(account, key, () => ready.future);
      final taken = cache.take(account, key);
      expect(await cache.take(Object(), key), isNull);
      ready.complete(Success(fixture()));
      await preload;
      expect(await taken, isNull);
    },
  );
  test('expiry applies both before and after waiting', () async {
    await cache.preload(account, key, () async => Success(fixture()));
    now = now.add(PlayUrlPrefetch.lifetime);
    expect(await cache.take(account, key), isNull);
    final ready = Completer<LoadingState<PlayUrlModel>>();
    final preload = cache.preload(account, key, () => ready.future);
    final taken = cache.take(account, key);
    now = now.add(PlayUrlPrefetch.lifetime);
    ready.complete(Success(fixture()));
    await preload;
    expect(await taken, isNull);
  });
  test('API error and thrown failure allow a fresh retry', () async {
    await cache.preload(account, key, () async => const Error('failed'));
    expect(await cache.take(account, key), isNull);
    await cache.preload(account, key, () => throw StateError('failed'));
    expect(await cache.take(account, key), isNull);
    final model = fixture();
    await cache.preload(account, key, () async => Success(model));
    expect(await cache.take(account, key), isNot(same(model)));
  });
  test(
    'old failure cannot remove a replacement after account change',
    () async {
      final ready = Completer<LoadingState<PlayUrlModel>>();
      final old = cache.preload(account, key, () => ready.future);
      final nextAccount = Object();
      final model = fixture();
      await cache.preload(nextAccount, key, () async => Success(model));
      ready.complete(const Error('old'));
      await old;
      expect(await cache.take(nextAccount, key), isNot(same(model)));
    },
  );
  test('capacity stays bounded without evicting in-flight requests', () async {
    final ready = Completer<LoadingState<PlayUrlModel>>();
    var calls = 0;
    final jobs = <Future<void>>[];
    for (var cid = 1; cid <= 4; cid++) {
      jobs.add(
        cache.preload(
          account,
          (bvid: 'BV-test', cid: cid, tryLook: false, voiceBalance: true),
          () {
            calls++;
            return ready.future;
          },
        ),
      );
    }
    expect(calls, 3);
    ready.complete(Success(fixture()));
    await Future.wait(jobs);
    const fourth = (
      bvid: 'BV-test',
      cid: 4,
      tryLook: false,
      voiceBalance: true,
    );
    await cache.preload(account, fourth, () async => Success(fixture()));
    expect(await cache.take(account, key), isNotNull);
    expect(await cache.take(account, fourth), isNotNull);
  });
  test('cid, login preview and loudness flags must match', () async {
    final model = fixture();
    await cache.preload(account, key, () async => Success(model));
    expect(
      await cache.take(account, (
        bvid: key.bvid,
        cid: 2,
        tryLook: false,
        voiceBalance: true,
      )),
      isNull,
    );
    expect(
      await cache.take(account, (
        bvid: key.bvid,
        cid: 1,
        tryLook: true,
        voiceBalance: true,
      )),
      isNull,
    );
    expect(
      await cache.take(account, (
        bvid: key.bvid,
        cid: 1,
        tryLook: false,
        voiceBalance: false,
      )),
      isNull,
    );
    expect(await cache.take(account, key), isNot(same(model)));
  });
  test('malformed expiry cannot break normal playback fallback', () async {
    final model = fixture(url: 'https://media.invalid/a?deadline=%FF');
    await cache.preload(account, key, () async => Success(model));
    expect(await cache.take(account, key), isNull);
  });
  test('near-expired media URL rejects the complete response', () async {
    final deadline =
        now.add(const Duration(seconds: 4)).millisecondsSinceEpoch ~/ 1000;
    final model = fixture(url: 'https://media.invalid/a?deadline=$deadline');
    await cache.preload(account, key, () async => Success(model));
    expect(await cache.take(account, key), isNull);
  });
  test(
    'clock reversal and replacement login identity invalidate cached data',
    () async {
      await cache.preload(account, key, () async => Success(fixture()));
      now = now.subtract(const Duration(seconds: 1));
      expect(await cache.take(account, key), isNull);
      await cache.preload(account, key, () async => Success(fixture()));
      expect(await cache.take(Object(), key), isNull);
    },
  );
  test(
    'bounded snapshots evict oldest, even when accessed repeatedly',
    () async {
      await cache.preload(account, key, () async => Success(fixture()));
      for (var cid = 2; cid <= PlayUrlPrefetch.capacity + 1; cid++) {
        await cache.preload(account, (
          bvid: 'BV-test',
          cid: cid,
          tryLook: false,
          voiceBalance: true,
        ), () async => Success(fixture()));
      }
      expect(await cache.take(account, key), isNull);
      const last = (
        bvid: 'BV-test',
        cid: PlayUrlPrefetch.capacity + 1,
        tryLook: false,
        voiceBalance: true,
      );
      expect(await cache.retain(account, last), isNotNull);
      now = now.add(PlayUrlPrefetch.lifetime);
      expect(await cache.retain(account, last), isNull);
    },
  );
  test(
    'track and nested metadata mutations cannot escape a playback copy',
    () async {
      final raw = {
        'dash': {
          'video': [
            {
              'id': 80,
              'baseUrl': 'https://media.invalid/v',
              'segment_base': {'index_range': '1-2'},
            },
          ],
          'audio': [
            {'id': 30280, 'baseUrl': 'https://media.invalid/a'},
          ],
        },
      };
      await cache.preload(
        account,
        key,
        () async => Success(PlayUrlModel.fromJson(raw, retainSnapshot: true)),
      );
      final first = (await cache.take(account, key))!;
      first.dash!.video!.single.segmentBase!['index_range'] = 'broken';
      first.dash!.audio!.clear();
      first.dash!.video!.clear();
      final second = (await cache.take(account, key))!;
      expect(second.dash!.video!.single.segmentBase!['index_range'], '1-2');
      expect(second.dash!.audio, hasLength(1));
      expect(second.officialPreloadToken, first.officialPreloadToken);
    },
  );
  test('active playback keeps its snapshot until idle expiry', () async {
    final model = fixture();
    cache.remember(account, key, model);
    final token = model.officialPreloadToken!;
    for (var minute = 5; minute <= 30; minute += 5) {
      now = now.add(const Duration(minutes: 5));
      cache.recordPosition(account, token, minute * 60000);
    }
    expect((await cache.take(account, key))?.lastPlayTime, 1800000);
    now = now.add(PlayUrlPrefetch.lifetime);
    expect(await cache.take(account, key), isNull);
  });

  test(
    'completion resets resume position while retaining media identity',
    () async {
      final model = fixture();
      cache.remember(account, key, model);
      final token = model.officialPreloadToken!;
      cache.recordPosition(account, token, 21933, completed: true);
      final replay = await cache.take(account, key);
      expect(replay?.lastPlayTime, 0);
      expect(replay?.officialPreloadToken, token);
      cache.recordPosition(account, token, 21933, completed: true);
      expect((await cache.retain(account, key))?.lastPlayTime, 0);
      cache.recordPosition(account, token, 500);
      expect((await cache.take(account, key))?.lastPlayTime, 500);
    },
  );
  test(
    'refills follow the latest played or sought position and completed replay',
    () async {
      final model = fixture();
      cache.remember(account, key, model);
      final token = model.officialPreloadToken!;
      for (final position in [10000, 30000, 3000, 120000]) {
        cache.recordPosition(account, token, position);
        final preload = (await cache.retain(account, key))!;
        expect(preload.lastPlayTime, position);
        expect(preload.officialPreloadToken, token);
        expect((await cache.take(account, key))!.lastPlayTime, position);
      }
      cache.recordPosition(account, token, 120000, completed: true);
      expect((await cache.retain(account, key))!.lastPlayTime, 0);
      expect((await cache.take(account, key))!.lastPlayTime, 0);
    },
  );

  test(
    'cold playback is retained and progress is bound to account and token',
    () async {
      var changes = 0;
      cache = PlayUrlPrefetch(
        now: () => now,
        onAccountChanged: () {
          changes++;
        },
      );
      final original = fixture(position: 500);
      cache.remember(account, key, original);
      final token = original.officialPreloadToken!;
      cache.recordPosition(account, token, 12345);
      expect((await cache.take(account, key))!.lastPlayTime, 12345);
      cache.recordPosition(account, 'another-video', 42);
      expect((await cache.retain(account, key))!.lastPlayTime, 12345);
      final nextAccount = Object();
      cache.remember(nextAccount, key, fixture(position: 400));
      cache.recordPosition(nextAccount, token, 99999);
      expect((await cache.take(nextAccount, key))!.lastPlayTime, 400);
      expect(changes, 2);
    },
  );
}
