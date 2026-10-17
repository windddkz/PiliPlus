import 'dart:async';

import 'package:PiliPlus/services/playback_focus.dart';
import 'package:flutter_test/flutter_test.dart';

class Client implements AudioFocusClient {
  Client(this.name, this.events);
  final String name;
  final List<String> events;
  @override
  bool audioFocusAvailable = true;
  @override
  bool audioFocusPlaying = false;
  bool ducked = false;
  bool failPlay = false;
  @override
  Future<void> playForAudioFocus() async {
    events.add('$name:play');
    if (failPlay) throw StateError('output failure');
    audioFocusPlaying = true;
  }

  @override
  Future<void> pauseForAudioFocus() async {
    events.add('$name:pause');
    audioFocusPlaying = false;
  }

  @override
  Future<void> setAudioDucked(bool value) async {
    events.add('$name:duck:$value');
    ducked = value;
  }
}

void main() {
  late List<String> events;
  late PlaybackFocus focus;
  late Client video;
  late Client audio;
  setUp(() {
    events = [];
    video = Client('video', events);
    audio = Client('audio', events);
    focus = PlaybackFocus(
      setActive: (active) async {
        events.add('focus:$active');
        return true;
      },
    );
  });

  test(
    'grant focus before output and stop previous owner before handover',
    () async {
      await focus.play(video);
      await focus.play(audio);
      expect(events, [
        'focus:true',
        'video:play',
        'video:pause',
        'focus:true',
        'audio:play',
      ]);
      await focus.release(video);
      expect(events.last, 'audio:play');
      expect(audio.audioFocusPlaying, true);
    },
  );
  test('old owner disposal does not cancel a queued new owner', () async {
    await focus.play(video);
    final starting = focus.play(audio);
    final releasing = focus.release(video);
    video.audioFocusAvailable = false;
    expect(await starting, true);
    await releasing;
    expect(audio.audioFocusPlaying, true);
    expect(focus.wantsPlayback(audio), true);
    expect(events.last, 'audio:play');
  });
  test('denied repeated request stops already playing output', () async {
    var allow = true;
    focus = PlaybackFocus(setActive: (_) async => allow);
    await focus.play(video);
    allow = false;
    expect(await focus.play(video), false);
    expect(video.audioFocusPlaying, false);
    expect(focus.wantsPlayback(video), false);
  });
  test('denial produces no output', () async {
    focus = PlaybackFocus(setActive: (_) async => false);
    expect(await focus.play(video), false);
    expect(events, ['video:pause']);
  });
  test('pause during pending focus grant cannot resurrect playback', () async {
    final granted = Completer<bool>();
    focus = PlaybackFocus(
      setActive: (active) async {
        events.add('focus:$active');
        return active ? granted.future : true;
      },
    );
    final playing = focus.play(video);
    await Future<void>.delayed(Duration.zero);
    final pausing = focus.pause(video);
    granted.complete(true);
    expect(await playing, false);
    await pausing;
    expect(events, ['focus:true', 'video:pause', 'focus:false', 'video:pause']);
  });
  test('dispose during pending grant cannot call disposed output', () async {
    final granted = Completer<bool>();
    focus = PlaybackFocus(
      setActive: (active) async => active ? granted.future : true,
    );
    final playing = focus.play(video);
    await Future<void>.delayed(Duration.zero);
    final releasing = focus.release(video);
    video.audioFocusAvailable = false;
    granted.complete(true);
    expect(await playing, false);
    await releasing;
    expect(events, isEmpty);
  });
  test(
    'repeated duck/end events are idempotent and do not modify user volume',
    () async {
      await focus.play(video);
      await focus.duck();
      await focus.duck();
      await focus.endInterruption();
      await focus.endInterruption();
      expect(events.where((e) => e.contains('duck')), [
        'video:duck:true',
        'video:duck:false',
      ]);
    },
  );
  test(
    'duck then transient loss clears duck and resumes only intended owner',
    () async {
      await focus.play(audio);
      await focus.duck();
      await focus.interrupt(mayResume: true);
      await focus.interrupt(mayResume: true);
      expect(audio.audioFocusPlaying, false);
      expect(audio.ducked, false);
      await focus.endInterruption();
      expect(audio.audioFocusPlaying, true);
      expect(events.where((e) => e == 'audio:play'), hasLength(2));
    },
  );
  test('user pause during interruption cancels automatic resume', () async {
    await focus.play(video);
    await focus.interrupt(mayResume: true);
    await focus.pause(video);
    await focus.endInterruption();
    expect(video.audioFocusPlaying, false);
    expect(events.where((e) => e == 'video:play'), hasLength(1));
  });
  test('permanent loss and noisy route never automatically resume', () async {
    await focus.play(video);
    await focus.interrupt(mayResume: false);
    await focus.endInterruption();
    expect(video.audioFocusPlaying, false);
    await focus.play(audio);
    await focus.becomingNoisy();
    await focus.endInterruption();
    expect(audio.audioFocusPlaying, false);
  });
  test('new owner cancels old transient resume', () async {
    await focus.play(video);
    await focus.interrupt(mayResume: true);
    await focus.play(audio);
    await focus.endInterruption();
    expect(video.audioFocusPlaying, false);
    expect(audio.audioFocusPlaying, true);
  });
  test('playback failure releases focus and next owner can start', () async {
    video.failPlay = true;
    await expectLater(focus.play(video), throwsStateError);
    expect(events.last, 'focus:false');
    await focus.play(audio);
    expect(audio.audioFocusPlaying, true);
  });
}
