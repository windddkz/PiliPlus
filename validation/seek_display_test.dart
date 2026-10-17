import 'package:PiliPlus/plugin/pl_player/models/seek_display.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('intermediate decoder positions cannot replace the selected target', () {
    final display = SeekDisplay();
    var actual = const Duration(seconds: 28);
    Duration visible() => display.target.value ?? actual;
    final request = display.begin(const Duration(milliseconds: 3627));
    for (final seconds in [28, 0, 1, 3]) {
      actual = Duration(seconds: seconds);
      expect(visible().inMilliseconds, 3627);
    }
    actual = const Duration(milliseconds: 3629);
    display.finish(request);
    expect(visible(), actual);
  });

  test('earlier completions cannot reveal an intermediate queued target', () {
    final display = SeekDisplay();
    final first = display.begin(const Duration(seconds: 80));
    final second = display.begin(const Duration(seconds: 20));
    final last = display.begin(const Duration(seconds: 80));
    display.finish(first);
    display.finish(second);
    expect(display.target.value, const Duration(seconds: 80));
    display.finish(last);
    expect(display.target.value, isNull);
  });

  test(
    'source reset invalidates an old completion even for the same target',
    () {
      final display = SeekDisplay();
      final old = display.begin(const Duration(seconds: 20));
      display.reset();
      expect(display.target.value, isNull);
      final current = display.begin(const Duration(seconds: 20));
      display.finish(old);
      expect(display.target.value, const Duration(seconds: 20));
      // Failure and timeout use the same terminal cleanup as success.
      display.finish(current);
      expect(display.target.value, isNull);
    },
  );
}
