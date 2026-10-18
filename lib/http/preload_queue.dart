import 'dart:async';

/// Page-owned candidates are cheap; only a bounded priority window reaches native IO.
typedef PreloadCandidate = ({String bvid, int cid});

class PreloadQueue {
  PreloadQueue({required this.load, required this.enable, this.window = 64})
    : assert(window > 0);
  final Future<String> Function(PreloadCandidate, int?) load;
  final Future<void> Function(bool, List<PreloadCandidate>, int) enable;
  final int window;
  Object? _owner;
  List<PreloadCandidate> _pending = [];
  Map<PreloadCandidate, int> _positions = {};
  int _revision = 0;
  int _priorityCount = 6;
  bool _running = false;
  bool _enabled = false;
  bool _controlDirty = true;
  List<PreloadCandidate> _candidates = [];
  int get candidateCount => _candidates.length;
  final Set<PreloadCandidate> _visited = {};

  void update(
    Object owner,
    Iterable<PreloadCandidate> candidates, {
    int priorityCount = 6,
    Map<PreloadCandidate, int> positions = const {},
  }) {
    assert(priorityCount >= 0);
    _priorityCount = priorityCount;
    final ordered = _candidates = candidates.toSet().toList();
    if (!identical(_owner, owner)) _visited.clear();
    _positions = {
      for (final item in ordered.take(window))
        if (positions[item] case final position? when position >= 0)
          item: position,
    };
    _owner = owner;
    _controlDirty = true;
    _pending = ordered
        .take(window)
        .where((item) => !_visited.contains(item))
        .toList();
    ++_revision;
    _run();
  }

  void refill(
    Object owner,
    Iterable<PreloadCandidate> candidates, {
    int priorityCount = 6,
    Map<PreloadCandidate, int> positions = const {},
  }) {
    _visited.clear();
    update(
      owner,
      candidates,
      priorityCount: priorityCount,
      positions: positions,
    );
  }

  void pause(Object owner) {
    if (!identical(owner, _owner)) return;
    _owner = null;
    _candidates = [];
    _pending = [];
    _positions = {};
    _visited.clear();
    ++_revision;
    _run();
  }

  void _run() {
    if (_running) return;
    _running = true;
    unawaited(
      _drain().whenComplete(() {
        _running = false;
        if (_pending.isNotEmpty) _run();
      }),
    );
  }

  Future<void> _drain() async {
    try {
      while (true) {
        final revision = _revision;
        final wanted = _owner != null;
        if (_controlDirty || _enabled != wanted) {
          _controlDirty = false;
          final candidates = _candidates.take(window).toList();
          await enable(
            wanted,
            candidates,
            _priorityCount.clamp(0, candidates.length),
          );
          _enabled = wanted;
          if (revision != _revision) continue;
        }
        if (!wanted || _pending.isEmpty) return;
        final candidate = _pending.removeAt(0);
        try {
          await load(candidate, _positions[candidate]);
        } catch (_) {
          // A page refill retries this optional request; playback remains independent.
        }
        if (revision == _revision) _visited.add(candidate);
      }
    } catch (_) {
      // Retry the control command on the next page update, avoiding an unbounded failure loop.
      _controlDirty = true;
      _pending = [];
    }
  }
}
