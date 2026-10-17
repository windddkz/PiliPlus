import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/models/video/play/url.dart';

typedef PlayUrlKey = ({String bvid, int cid, bool tryLook, bool voiceBalance});

/// Retains server snapshots; every caller gets its own mutable playback model.
class PlayUrlPrefetch {
  PlayUrlPrefetch({DateTime Function()? now, this.onAccountChanged})
    : _now = now ?? DateTime.now;

  final DateTime Function() _now;
  final void Function()? onAccountChanged;
  final _entries = <PlayUrlKey, _Entry>{};
  Object? _account;
  int _generation = 0;
  int _active = 0;
  static int _serial = 0;
  static const capacity = 128;
  static const lifetime = Duration(minutes: 20);

  void _useAccount(Object account) {
    if (!identical(_account, account)) {
      _account = account;
      _generation++;
      _entries.clear();
      onAccountChanged?.call();
    }
  }

  bool _fresh(_Entry entry) {
    final age = _now().difference(entry.touched);
    return !age.isNegative && age < lifetime;
  }

  void remember(Object account, PlayUrlKey key, PlayUrlModel model) {
    _useAccount(account);
    model.officialPreloadToken ??=
        '${DateTime.now().microsecondsSinceEpoch}_${++_serial}';
    final snapshot = model.copyForPlayback();
    if (snapshot == null) return;
    _entries.remove(key);
    _makeRoom();
    _entries[key] = _Entry(_now(), Future.value(snapshot))
      ..done = true
      ..token = snapshot.officialPreloadToken;
  }

  void recordPosition(
    Object account,
    String token,
    int milliseconds, {
    bool completed = false,
  }) {
    _useAccount(account);
    if (milliseconds < 0) return;
    for (final entry in _entries.values) {
      if (entry.token == token && _fresh(entry)) {
        entry
          ..position = completed ? 0 : milliseconds
          ..touched = _now();
        return;
      }
    }
  }

  void _makeRoom() {
    if (_entries.length < capacity) return;
    final oldest = _entries.keys.firstWhere((key) => _entries[key]!.done);
    _entries.remove(oldest);
  }

  Future<void> preload(
    Object account,
    PlayUrlKey key,
    Future<LoadingState<PlayUrlModel>> Function() load,
  ) async {
    _useAccount(account);
    _entries.removeWhere((_, entry) => entry.done && !_fresh(entry));
    if (_entries[key] case final existing?) {
      await existing.result;
      return;
    }
    if (_active >= 3) return;
    _makeRoom();
    _active++;
    final entry = _Entry(_now(), _load(load));
    _entries[key] = entry;
    final result = await entry.result;
    entry
      ..done = true
      ..token = result?.officialPreloadToken;
    _active--;
    if (result == null && identical(_entries[key], entry)) _entries.remove(key);
  }

  Future<PlayUrlModel?> _load(
    Future<LoadingState<PlayUrlModel>> Function() load,
  ) async {
    try {
      final result = await load();
      final model = result.dataOrNull;
      if (model == null) return null;
      model.officialPreloadToken ??=
          '${DateTime.now().microsecondsSinceEpoch}_${++_serial}';
      return model.copyForPlayback();
    } catch (_) {
      return null;
    }
  }

  Future<PlayUrlModel?> take(Object account, PlayUrlKey key) async {
    _useAccount(account);
    final generation = _generation;
    final entry = _entries[key];
    if (entry == null || !_fresh(entry)) return null;
    final result = await entry.result;
    if (generation != _generation ||
        !identical(_entries[key], entry) ||
        !_fresh(entry) ||
        result == null) {
      return null;
    }
    if (!_usable(result)) return null;
    _entries.remove(key);
    _entries[key] = entry;
    return result.copyForPlayback()
      ?..lastPlayTime = entry.position ?? result.lastPlayTime;
  }

  Future<PlayUrlModel?> retain(Object account, PlayUrlKey key) async {
    _useAccount(account);
    final entry = _entries[key];
    if (entry == null || !entry.done || !_fresh(entry)) return null;
    final result = await entry.result;
    if (!identical(_account, account) || !identical(_entries[key], entry)) {
      return null;
    }
    if (!_fresh(entry) || result == null || !_usable(result)) {
      _entries.remove(key);
      return null;
    }
    return result.copyForPlayback()
      ?..lastPlayTime = entry.position ?? result.lastPlayTime;
  }

  bool _usable(PlayUrlModel result) {
    final urls = [
      for (final item in [...?result.dash?.video, ...?result.dash?.audio])
        ...item.playUrls,
      for (final item in result.durl ?? <Durl>[]) ...item.playUrls,
    ];
    final deadline =
        _now().add(const Duration(seconds: 5)).millisecondsSinceEpoch ~/ 1000;
    try {
      for (final url in urls) {
        final uri = Uri.tryParse(url);
        if (uri == null) return false;
        final value = uri.queryParameters['deadline'];
        if (value != null && (int.tryParse(value) ?? 0) <= deadline) {
          return false;
        }
      }
    } on FormatException {
      return false;
    }
    return true;
  }
}

class _Entry {
  _Entry(this.touched, this.result);
  DateTime touched;
  final Future<PlayUrlModel?> result;
  bool done = false;
  String? token;
  int? position;
}
