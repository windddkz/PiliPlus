import 'dart:async';

import 'package:PiliPlus/http/preload_queue.dart';
import 'package:flutter_test/flutter_test.dart';

PreloadCandidate candidate(int n) => (bvid: 'BV$n', cid: n);
Future<void> settle() => Future<void>.delayed(Duration.zero);

void main() {
  test(
    'all candidates enter the queue; IO window is deduplicated and prioritized',
    () async {
      final loaded = <PreloadCandidate>[];
      final controls = <bool>[];
      final queue = PreloadQueue(
        load: (item) async {
          loaded.add(item);
          return 'queued';
        },
        enable: (on) async {
          controls.add(on);
        },
        window: 4,
      );
      final page = Object();
      queue.update(page, [
        candidate(8),
        candidate(8),
        ...List.generate(10, candidate),
      ]);
      await settle();
      expect(queue.candidateCount, 10);
      expect(loaded, [candidate(8), candidate(0), candidate(1), candidate(2)]);
      expect(controls, [true]);
      queue.update(page, [candidate(7), ...List.generate(10, candidate)]);
      await settle();
      expect(loaded.last, candidate(7));
      queue.pause(page);
      await settle();
      expect(controls, [true, true, false]);
      expect(queue.candidateCount, 0);
    },
  );

  test('return refills candidates while late old-page work cannot drain its old queue', () async {
    final ready = Completer<String>();
    final loaded = <PreloadCandidate>[];
    final controls = <bool>[];
    final home = Object(), detail = Object();
    final queue = PreloadQueue(
      load: (item) {
        loaded.add(item);
        return loaded.length == 1 ? ready.future : Future.value('ready');
      },
      enable: (on) async {
        controls.add(on);
      },
    );
    queue.update(home, [candidate(1), candidate(2)]);
    await settle();
    queue.pause(home);
    queue.update(detail, [candidate(3)]);
    queue.pause(home);
    ready.complete('queued');
    await settle();
    expect(loaded, [candidate(1), candidate(3)]);
    queue.refill(home, [candidate(2), candidate(1)]);
    await settle();
    expect(loaded, [candidate(1), candidate(3), candidate(2), candidate(1)]);
    queue.pause(home);
    await settle();
    expect(controls.last, false);
  });

  test('failed control does not spin and can be retried on refill', () async {
    var calls = 0, loads = 0;
    final queue = PreloadQueue(
      load: (_) async {
        loads++;
        return 'stored';
      },
      enable: (_) async {
        if (++calls == 1) throw StateError('offline');
      },
    );
    final page = Object();
    queue.update(page, [candidate(1)]);
    await settle();
    expect(calls, 1);
    expect(loads, 0);
    queue.refill(page, [candidate(1)]);
    await settle();
    expect(calls, 2);
    expect(loads, 1);
  });
}
