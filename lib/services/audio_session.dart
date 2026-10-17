import 'dart:async';

import 'package:PiliPlus/services/playback_focus.dart';
import 'package:audio_session/audio_session.dart';
import 'package:flutter/foundation.dart';

class AudioSessionHandler extends PlaybackFocus {
  AudioSessionHandler() : this._(_configure());

  AudioSessionHandler._(Future<AudioSession> ready)
    : super(setActive: (active) async => (await ready).setActive(active)) {
    _observe(
      ready.then((session) {
        session.interruptionEventStream.listen((event) {
          _observe(
            event.begin
                ? switch (event.type) {
                    AudioInterruptionType.duck => duck(),
                    AudioInterruptionType.pause => interrupt(mayResume: true),
                    AudioInterruptionType.unknown => interrupt(
                      mayResume: false,
                    ),
                  }
                : endInterruption(),
          );
        });
        session.becomingNoisyEventStream.listen(
          (_) => _observe(becomingNoisy()),
        );
      }),
    );
  }

  static Future<AudioSession> _configure() async {
    final session = await AudioSession.instance;
    await session.configure(const AudioSessionConfiguration.music());
    return session;
  }

  static void _observe(Future<void> operation) {
    unawaited(
      operation.catchError((Object error, StackTrace stack) {
        FlutterError.reportError(
          FlutterErrorDetails(
            exception: error,
            stack: stack,
            library: 'audio focus',
          ),
        );
      }),
    );
  }
}
