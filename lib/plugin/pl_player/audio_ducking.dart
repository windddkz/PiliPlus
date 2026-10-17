import 'dart:math' as math;

import 'package:PiliPlus/plugin/pl_player/official_player.dart';
import 'package:media_kit/media_kit.dart';

/// Duck independently of system volume and the user's cubic volume control.
class AudioDucking {
  PlatformPlayer? _player;
  String? _originalGain;

  Future<void> apply(PlatformPlayer? player, bool ducked) async {
    if (player == null) return;
    if (player is OfficialPlayer) {
      await player.setDucked(ducked);
    } else if (player is NativePlayer) {
      if (_player != player) {
        _player = player;
        _originalGain = null;
      }
      if (ducked && _originalGain == null) {
        final gain = player.getProperty('volume-gain');
        final decibels = double.parse(gain);
        if (!decibels.isFinite) throw StateError('Invalid mpv volume gain');
        player.setProperty(
          'volume-gain',
          (decibels + 20 * math.log(.5) / math.ln10).toString(),
        );
        _originalGain = gain;
      } else if (!ducked && _originalGain != null) {
        player.setProperty('volume-gain', _originalGain!);
        _originalGain = null;
      }
    }
  }
}
