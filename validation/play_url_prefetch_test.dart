import 'dart:async';

import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/http/play_url_prefetch.dart';
import 'package:PiliPlus/models/video/play/url.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final key = (bvid: 'BV-test', cid: 1, tryLook: false, voiceBalance: true);
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
      final model = PlayUrlModel();
      var loads = 0;
      await cache.preload(account, key, () async {
        loads++;
        return Success(model);
      });
      for (var i = 0; i < 7; i++) {
        now = now.add(const Duration(seconds: 10));
        expect(await cache.retain(account, key), same(model));
      }
      expect(loads, 1);
      now = now.add(const Duration(seconds: 30));
      expect(await cache.retain(account, key), isNull);
      expect(await cache.take(account, key), isNull);
    },
  );
  test('retention never revives an expired URL or crosses accounts', () async {
    final deadline =
        now.add(const Duration(seconds: 25)).millisecondsSinceEpoch ~/ 1000;
    final model = PlayUrlModel()
      ..durl = [Durl(url: 'https://media.invalid/a?deadline=$deadline')];
    await cache.preload(account, key, () async => Success(model));
    now = now.add(const Duration(seconds: 10));
    expect(await cache.retain(account, key), same(model));
    now = now.add(const Duration(seconds: 10));
    expect(await cache.retain(account, key), isNull);
    expect(await cache.take(account, key), isNull);
    await cache.preload(account, key, () async => Success(PlayUrlModel()));
    expect(await cache.retain(Object(), key), isNull);
  });
  test('playback consumption wins over a pending retention', () async {
    final model = PlayUrlModel();
    await cache.preload(account, key, () async => Success(model));
    final retained = cache.retain(account, key);
    final taken = cache.take(account, key);
    expect(await retained, isNull);
    expect(await taken, same(model));
    expect(await cache.take(account, key), isNull);
  });
  test('retention cannot claim an unfinished request', () async {
    final ready = Completer<LoadingState<PlayUrlModel>>();
    final loading = cache.preload(account, key, () => ready.future);
    expect(await cache.retain(account, key), isNull);
    ready.complete(Success(PlayUrlModel()));
    await loading;
    expect(await cache.retain(account, key), isNotNull);
  });
  test(
    'in-flight response has one consumer and duplicate preload joins',
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
      expect(await cache.take(account, key), isNull);
      final model = PlayUrlModel()..lastPlayTime = 12345;
      ready.complete(Success(model));
      expect(await taken, same(model));
      await Future.wait([first, duplicate]);
      expect(calls, 1);
      expect(await cache.take(account, key), isNull);
    },
  );
  test(
    'different account and late response cannot reuse a prior account',
    () async {
      final ready = Completer<LoadingState<PlayUrlModel>>();
      final preload = cache.preload(account, key, () => ready.future);
      final taken = cache.take(account, key);
      expect(await cache.take(Object(), key), isNull);
      ready.complete(Success(PlayUrlModel()));
      await preload;
      expect(await taken, isNull);
    },
  );
  test('expiry applies both before and after waiting', () async {
    await cache.preload(account, key, () async => Success(PlayUrlModel()));
    now = now.add(const Duration(seconds: 30));
    expect(await cache.take(account, key), isNull);
    final ready = Completer<LoadingState<PlayUrlModel>>();
    final preload = cache.preload(account, key, () => ready.future);
    final taken = cache.take(account, key);
    now = now.add(const Duration(seconds: 30));
    ready.complete(Success(PlayUrlModel()));
    await preload;
    expect(await taken, isNull);
  });
  test('API error and thrown failure allow a fresh retry', () async {
    await cache.preload(account, key, () async => const Error('failed'));
    expect(await cache.take(account, key), isNull);
    await cache.preload(account, key, () => throw StateError('failed'));
    expect(await cache.take(account, key), isNull);
    final model = PlayUrlModel();
    await cache.preload(account, key, () async => Success(model));
    expect(await cache.take(account, key), same(model));
  });
  test(
    'old failure cannot remove a replacement after account change',
    () async {
      final ready = Completer<LoadingState<PlayUrlModel>>();
      final old = cache.preload(account, key, () => ready.future);
      final nextAccount = Object();
      final model = PlayUrlModel();
      await cache.preload(nextAccount, key, () async => Success(model));
      ready.complete(const Error('old'));
      await old;
      expect(await cache.take(nextAccount, key), same(model));
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
    ready.complete(Success(PlayUrlModel()));
    await Future.wait(jobs);
    final fourth = (
      bvid: 'BV-test',
      cid: 4,
      tryLook: false,
      voiceBalance: true,
    );
    await cache.preload(account, fourth, () async => Success(PlayUrlModel()));
    expect(await cache.take(account, key), isNull);
    expect(await cache.take(account, fourth), isNotNull);
  });
  test('cid, login preview and loudness flags must match', () async {
    final model = PlayUrlModel();
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
    expect(await cache.take(account, key), same(model));
  });
  test('malformed expiry cannot break normal playback fallback', () async {
    final model = PlayUrlModel()
      ..durl = [Durl(url: 'https://media.invalid/a?deadline=%FF')];
    await cache.preload(account, key, () async => Success(model));
    expect(await cache.take(account, key), isNull);
  });
  test('near-expired media URL rejects the complete response', () async {
    final deadline =
        now.add(const Duration(seconds: 4)).millisecondsSinceEpoch ~/ 1000;
    final model = PlayUrlModel()
      ..durl = [Durl(url: 'https://media.invalid/a?deadline=$deadline')];
    await cache.preload(account, key, () async => Success(model));
    expect(await cache.take(account, key), isNull);
  });
  test(
    'clock reversal and replacement login identity invalidate cached data',
    () async {
      await cache.preload(account, key, () async => Success(PlayUrlModel()));
      now = now.subtract(const Duration(seconds: 1));
      expect(await cache.take(account, key), isNull);
      await cache.preload(account, key, () async => Success(PlayUrlModel()));
      expect(await cache.take(Object(), key), isNull);
    },
  );
}
