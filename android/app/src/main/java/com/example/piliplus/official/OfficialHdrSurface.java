package com.example.piliplus.official;

import android.annotation.TargetApi;
import android.os.Bundle;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceControl;

/** Own the decoder target across Activity windows without converting HDR buffers to RGB. */
@TargetApi(33)
final class OfficialHdrSurface implements AutoCloseable {
    private final SurfaceControl layer;
    final Surface surface;
    OfficialHdrSurface() {
        layer = new SurfaceControl.Builder()
                        .setName("PiliPlus HDR video")
                        .setBufferSize(1, 1)
                        .build();
        surface = new Surface(layer);
    }
    // Native setGeometry accepts mirror bits; the Java Rotation annotation omits them.
    @android.annotation.SuppressLint("WrongConstant")
    void update(SurfaceControl parent, int windowWidth, int windowHeight, int videoWidth,
            int videoHeight, Bundle options) {
        boolean visible = parent != null && parent.isValid() && windowWidth > 0 && windowHeight > 0
                && videoWidth > 0 && videoHeight > 0;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.reparent(layer, parent).setLayer(layer, 1).setVisibility(layer, visible);
            if (visible) {
                float x = options.getFloat("scaleX", 1) * (options.getBoolean("flipX") ? -1 : 1);
                float y = options.getFloat("scaleY", 1) * (options.getBoolean("flipY") ? -1 : 1);
                // Geometry transforms children too; buffer-only transforms cannot mirror the BLAST child.
                int transform = (x < 0 ? SurfaceControl.BUFFER_TRANSFORM_MIRROR_HORIZONTAL : 0)
                        | (y < 0 ? SurfaceControl.BUFFER_TRANSFORM_MIRROR_VERTICAL : 0);
                float fittedWidth = windowWidth, fittedHeight = windowHeight;
                if (options.getBoolean("preserveAspect", true)) {
                    float scale = Math.min((float) windowWidth / videoWidth,
                            (float) windowHeight / videoHeight);
                    fittedWidth = videoWidth * scale;
                    fittedHeight = videoHeight * scale;
                }
                transaction
                        .setGeometry(layer, new Rect(0, 0, 1, 1),
                                new Rect(0, 0, Math.max(1, Math.round(Math.abs(x) * fittedWidth)),
                                        Math.max(1, Math.round(Math.abs(y) * fittedHeight))),
                                transform)
                        .setPosition(layer,
                                (windowWidth - x * fittedWidth) / 2 + options.getFloat("offsetX", 0),
                                (windowHeight - y * fittedHeight) / 2 + options.getFloat("offsetY", 0));
            }
            transaction.apply();
        }
    }
    @Override
    public void close() {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(layer, false).reparent(layer, null).apply();
        } finally {
            surface.release();
            layer.release();
        }
    }
}
