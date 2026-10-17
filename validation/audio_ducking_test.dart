import 'package:PiliPlus/plugin/pl_player/audio_ducking.dart';
import 'package:media_kit/media_kit.dart';
import 'package:flutter_test/flutter_test.dart';

class Mpv extends Fake implements NativePlayer {
  Mpv(this.gain);
  String gain;
  int writes = 0;
  @override
  String getProperty(String property) {
    expect(property, 'volume-gain');
    return gain;
  }

  @override
  void setProperty(
    String property,
    String value, {
    bool waitForInitialization = true,
  }) {
    expect(property, 'volume-gain');
    gain = value;
    writes++;
  }
}

void main() {
  test(
    'mpv adds one -6.02dB duck then restores original gain exactly',
    () async {
      final mpv = Mpv('2.125');
      final duck = AudioDucking();
      await duck.apply(mpv, true);
      await duck.apply(mpv, true);
      expect(double.parse(mpv.gain), closeTo(2.125 - 6.02059991328, 1e-9));
      expect(mpv.writes, 1);
      await duck.apply(mpv, false);
      await duck.apply(mpv, false);
      expect(mpv.gain, '2.125');
      expect(mpv.writes, 2);
    },
  );
  test(
    'new backend has independent gain and invalid gain is not propagated',
    () async {
      final duck = AudioDucking();
      final first = Mpv('1');
      final second = Mpv('-2');
      await duck.apply(first, true);
      await duck.apply(second, true);
      await duck.apply(second, false);
      expect(second.gain, '-2');
      final invalid = Mpv('NaN');
      await expectLater(duck.apply(invalid, true), throwsStateError);
      expect(invalid.writes, 0);
    },
  );
}
