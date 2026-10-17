import 'package:PiliPlus/http/loading_state.dart';
import 'package:PiliPlus/models/video/play/url.dart';

typedef PlayUrlKey = ({String bvid, int cid, bool tryLook, bool voiceBalance});

/// Single-use responses: playback mutates the model while selecting tracks.
class PlayUrlPrefetch {
  PlayUrlPrefetch({DateTime Function()? now}) : _now = now ?? DateTime.now;

  final DateTime Function() _now;
  final _entries = <PlayUrlKey, _Entry>{};
  Object? _account;
  int _generation = 0;
  int _active = 0;

  void _useAccount(Object account) {
    if (!identical(_account, account)) {
      _account = account;
      _generation++;
      _entries.clear();
    }
  }

  bool _fresh(_Entry entry) {
    final age = _now().difference(entry.created);
    return !age.isNegative && age < const Duration(seconds: 30);
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
    if (_entries.length >= 3) {
      final oldest = _entries.keys.firstWhere((key) => _entries[key]!.done);
      _entries.remove(oldest);
    }
    _active++;
    final entry = _Entry(_now(), _load(load));
    _entries[key] = entry;
    final result = await entry.result;
    entry.done = true;
    _active--;
    if (result == null && identical(_entries[key], entry)) _entries.remove(key);
  }

  Future<PlayUrlModel?> _load(
    Future<LoadingState<PlayUrlModel>> Function() load,
  ) async {
    try {
      final result = await load();
      return result.dataOrNull;
    } catch (_) {
      return null;
    }
  }

  Future<PlayUrlModel?> take(Object account, PlayUrlKey key) async {
    _useAccount(account);
    final generation = _generation;
    final entry = _entries.remove(key);
    if (entry == null || !_fresh(entry)) return null;
    final result = await entry.result;
    if (generation != _generation || !_fresh(entry) || result == null) {
      return null;
    }
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
        if (uri == null) return null;
        final value = uri.queryParameters['deadline'];
        if (value != null && (int.tryParse(value) ?? 0) <= deadline) {
          return null;
        }
      }
    } on FormatException {
      return null;
    }
    return result;
  }
}

class _Entry {
  _Entry(this.created, this.result);
  final DateTime created;
  final Future<PlayUrlModel?> result;
  bool done = false;
}
