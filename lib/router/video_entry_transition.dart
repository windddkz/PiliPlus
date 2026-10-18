import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

/// One navigation owns this painted thumbnail; capture never waits on the network.
class VideoEntrySnapshot {
  VideoEntrySnapshot._(this._image, this.rect, this.screenSize);

  ui.Image? _image;
  ui.Image? get image => _image;
  final Rect rect;
  final Size screenSize;
  final targetKey = GlobalKey();

  static VideoEntrySnapshot? capture(GlobalKey? key) {
    final context = key?.currentContext;
    if (kIsWeb ||
        defaultTargetPlatform != TargetPlatform.android ||
        context == null ||
        !context.mounted ||
        MediaQuery.disableAnimationsOf(context)) {
      return null;
    }
    final boundary = context.findRenderObject();
    if (boundary is! RenderRepaintBoundary ||
        !boundary.attached ||
        !boundary.hasSize ||
        boundary.size.isEmpty) {
      return null;
    }
    final size = MediaQuery.sizeOf(context);
    final rect = boundary.localToGlobal(Offset.zero) & boundary.size;
    if (!rect.isFinite || !(Offset.zero & size).overlaps(rect)) return null;
    try {
      return VideoEntrySnapshot._(
        boundary.toImageSync(
          pixelRatio: MediaQuery.devicePixelRatioOf(context).clamp(1.0, 2.0),
        ),
        rect,
        size,
      );
    } catch (_) {
      // A recycled or not-yet-painted cell uses ordinary navigation.
      return null;
    }
  }

  void dispose() {
    _image?.dispose();
    _image = null;
  }
}

class VideoEntryTransition extends StatefulWidget {
  const VideoEntryTransition({
    super.key,
    required this.snapshot,
    required this.animation,
    required this.child,
  });

  final VideoEntrySnapshot snapshot;
  final Animation<double> animation;
  final Widget child;

  @override
  State<VideoEntryTransition> createState() => _VideoEntryTransitionState();
}

class _VideoEntryTransitionState extends State<VideoEntryTransition> {
  Rect? _target;
  bool _finished = false;

  @override
  void initState() {
    super.initState();
    widget.animation.addStatusListener(_statusChanged);
  }

  void _finish() {
    if (_finished) return;
    _finished = true;
    final snapshot = widget.snapshot;
    // Remove the last RawImage from the render tree before releasing its image.
    WidgetsBinding.instance.addPostFrameCallback((_) => snapshot.dispose());
  }

  void _statusChanged(AnimationStatus status) {
    if (status != AnimationStatus.forward) _finish();
  }

  @override
  void dispose() {
    widget.animation.removeStatusListener(_statusChanged);
    _finish();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.animation,
    child: widget.child,
    builder: (context, child) {
      final snapshot = widget.snapshot;
      final size = MediaQuery.sizeOf(context);
      final disabled = MediaQuery.disableAnimationsOf(context);
      if (size != snapshot.screenSize || disabled) _finish();
      final root = context.findRenderObject();
      final target = snapshot.targetKey.currentContext?.findRenderObject();
      if (_target == null &&
          root is RenderBox &&
          root.hasSize &&
          target is RenderBox &&
          target.attached &&
          target.hasSize) {
        _target =
            root.globalToLocal(target.localToGlobal(Offset.zero)) & target.size;
      }
      final t = Curves.easeInOutCubic.transform(widget.animation.value);
      final image = snapshot.image;
      final begin = root is RenderBox && root.hasSize
          ? root.globalToLocal(snapshot.rect.topLeft) & snapshot.rect.size
          : snapshot.rect;
      return Stack(
        fit: StackFit.expand,
        children: [
          // Keep the real player at its final size and paint it during entry.
          FadeTransition(
            opacity: disabled
                ? const AlwaysStoppedAnimation(1.0)
                : widget.animation,
            child: child,
          ),
          if (!_finished && image != null)
            Positioned.fromRect(
              rect: Rect.lerp(begin, _target ?? begin, t)!,
              child: IgnorePointer(
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(12 * (1 - t)),
                  child: ColoredBox(
                    color: Colors.black,
                    child: RawImage(image: image, fit: BoxFit.contain),
                  ),
                ),
              ),
            ),
        ],
      );
    },
  );
}
