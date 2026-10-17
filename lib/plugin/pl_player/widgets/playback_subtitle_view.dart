/*
Adapted from media_kit SubtitleView for PlatformPlayer streams.
MIT License

Copyright (c) 2021 & onwards Hitesh Kumar Saini <saini123hitesh@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';

import 'package:media_kit_video/media_kit_video.dart'
    show SubtitleViewConfiguration;

class PlaybackSubtitleView extends StatefulWidget {
  final PlatformPlayer player;

  final SubtitleViewConfiguration configuration;

  final bool enableDragSubtitle;

  final ValueChanged<EdgeInsets>? onUpdatePadding;

  const PlaybackSubtitleView({
    super.key,
    required this.player,
    required this.configuration,
    this.enableDragSubtitle = false,
    this.onUpdatePadding,
  });

  @override
  PlaybackSubtitleViewState createState() => PlaybackSubtitleViewState();
}

class PlaybackSubtitleViewState extends State<PlaybackSubtitleView> {
  late Subtitle subtitle = widget.player.state.subtitle;
  late EdgeInsets padding = widget.configuration.padding;
  late Duration duration = const Duration(milliseconds: 100);

  // The [StreamSubscription] to listen to the subtitle changes.
  StreamSubscription<Subtitle>? subscription;

  @override
  void initState() {
    subscription = widget.player.stream.subtitle.listen((value) {
      setState(() {
        subtitle = value;
      });
    });
    super.initState();
  }

  @override
  void dispose() {
    subscription?.cancel();
    super.dispose();
  }

  void setPadding(
    EdgeInsets padding, {
    Duration duration = const Duration(milliseconds: 100),
  }) {
    if (this.duration != duration) {
      setState(() {
        this.duration = duration;
      });
    }
    setState(() {
      this.padding = padding;
    });
  }

  @override
  void didUpdateWidget(PlaybackSubtitleView oldWidget) {
    super.didUpdateWidget(oldWidget);
    padding = widget.configuration.padding;
    if (!identical(oldWidget.player, widget.player)) {
      subscription?.cancel();
      subtitle = widget.player.state.subtitle;
      subscription = widget.player.stream.subtitle.listen((value) {
        setState(() => subtitle = value);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.configuration.visible) return const SizedBox.shrink();
    Widget text(TextStyle style) => Text(
      subtitle.toString(),
      style: style,
      textAlign: widget.configuration.textAlign,
      textScaler: TextScaler.noScaling,
    );

    Widget subtitleView() {
      if (widget.configuration.strokeStyle != null) {
        return Stack(
          clipBehavior: Clip.none,
          children: [
            text(widget.configuration.strokeStyle!),
            text(widget.configuration.style),
          ],
        );
      }
      return text(widget.configuration.style);
    }

    return AnimatedContainer(
      margin: padding,
      duration: duration,
      alignment: Alignment.bottomCenter,
      child: widget.enableDragSubtitle
          ? GestureDetector(
              behavior: HitTestBehavior.opaque,
              onVerticalDragUpdate: (details) {
                double bottom = clampDouble(
                  padding.bottom - details.delta.dy,
                  0,
                  200,
                );
                padding = padding.copyWith(bottom: bottom);
                setState(() {});
              },
              onVerticalDragEnd: (details) {
                widget.onUpdatePadding?.call(padding);
              },
              child: subtitleView(),
            )
          : subtitleView(),
    );
  }
}
