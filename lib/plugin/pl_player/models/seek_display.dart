import 'package:get/get.dart';

/// Keep the user's latest target visible while the player finishes seeking.
class SeekDisplay {
  final target = Rxn<Duration>();
  int _revision = 0;

  int begin(Duration position) {
    target.value = position;
    return ++_revision;
  }

  void finish(int revision) {
    if (revision == _revision) target.value = null;
  }

  void reset() {
    ++_revision;
    target.value = null;
  }
}
