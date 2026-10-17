import 'dart:async';

abstract interface class AudioFocusClient {
  bool get audioFocusAvailable;
  bool get audioFocusPlaying;
  Future<void> playForAudioFocus();
  Future<void> pauseForAudioFocus();
  Future<void> setAudioDucked(bool ducked);
}

/// One app-wide focus owner, including the independent audio page.
class PlaybackFocus {
  PlaybackFocus({required this.setActive});

  final Future<bool> Function(bool) setActive;
  AudioFocusClient? _owner;
  AudioFocusClient? _intent;
  AudioFocusClient? _resume;
  bool _ducked = false;
  int _revision = 0;
  Future<void> _pending = Future.value();

  Future<T> _enqueue<T>(Future<T> Function() action) {
    final result = _pending.then((_) => action());
    // A failed command must not prevent subsequent pause/release commands.
    _pending = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  bool wantsPlayback(AudioFocusClient client) => _intent == client;

  bool isDucked(AudioFocusClient client) => _owner == client && _ducked;

  Future<void> _setDucked(AudioFocusClient client, bool value) async {
    if (_owner != client || _ducked == value) return;
    if (client.audioFocusAvailable) await client.setAudioDucked(value);
    _ducked = value;
  }

  Future<bool> play(AudioFocusClient client) {
    final revision = ++_revision;
    _intent = client;
    _resume = null;
    return _enqueue(() async {
      if (revision != _revision || !client.audioFocusAvailable) {
        if (revision == _revision) _intent = null;
        return false;
      }
      final previous = _owner;
      if (previous != null && previous != client) {
        if (previous.audioFocusAvailable) await previous.pauseForAudioFocus();
        await _setDucked(previous, false);
      }
      if (revision != _revision || !client.audioFocusAvailable) {
        if (revision == _revision) _intent = null;
        return false;
      }
      _owner = client;
      try {
        final granted = await setActive(true);
        if (!granted || revision != _revision || !client.audioFocusAvailable) {
          if (client.audioFocusAvailable) await client.pauseForAudioFocus();
          await _setDucked(client, false);
          _owner = null;
          if (granted) await setActive(false);
          if (revision == _revision) _intent = null;
          return false;
        }
        await client.playForAudioFocus();
        return true;
      } catch (_) {
        if (client.audioFocusAvailable) await client.pauseForAudioFocus();
        await _setDucked(client, false);
        _owner = null;
        await setActive(false);
        if (revision == _revision) _intent = null;
        rethrow;
      }
    });
  }

  Future<void> pause(AudioFocusClient client) {
    if (_intent == client) {
      ++_revision;
      _intent = null;
    }
    if (_resume == client) _resume = null;
    return _enqueue(() async {
      if (client.audioFocusAvailable) await client.pauseForAudioFocus();
      if (_owner == client) {
        await _setDucked(client, false);
        _owner = null;
        await setActive(false);
      }
    });
  }

  /// Invalidate pending starts before the caller disposes its backend.
  Future<void> release(AudioFocusClient client) {
    if (_intent == client) {
      ++_revision;
      _intent = null;
    }
    if (_resume == client) _resume = null;
    return _enqueue(() async {
      if (_owner == client) {
        await _setDucked(client, false);
        _owner = null;
        await setActive(false);
      }
    });
  }

  Future<void> duck() {
    final client = _owner;
    return _enqueue(() async {
      if (client != null && client.audioFocusPlaying) {
        await _setDucked(client, true);
      }
    });
  }

  Future<void> interrupt({required bool mayResume}) {
    final client = _owner;
    if (client == null) return Future.value();
    if (!mayResume) {
      _resume = null;
    } else if ((_intent == null || _intent == client) &&
        (client.audioFocusPlaying || _intent == client)) {
      _resume = client;
    }
    if (_intent == client) {
      ++_revision;
      _intent = null;
    }
    return _enqueue(() async {
      if (_owner != client) return;
      if (client.audioFocusAvailable) await client.pauseForAudioFocus();
      await _setDucked(client, false);
      if (!mayResume) {
        _owner = null;
        await setActive(false);
      }
    });
  }

  Future<void> endInterruption() async {
    final client = _resume;
    _resume = null;
    if (client != null) {
      await play(client);
    } else {
      final owner = _owner;
      await _enqueue(() async {
        if (owner != null) await _setDucked(owner, false);
      });
    }
  }

  Future<void> becomingNoisy() {
    final client = _intent ?? _owner;
    return client == null ? Future.value() : pause(client);
  }
}
