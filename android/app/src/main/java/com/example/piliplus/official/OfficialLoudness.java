package com.example.piliplus.official;

import java.util.Map;

/** app.in 6.6.0 oK1/b + IjkMediaPlayerItem business gates, before its original DSP. */
final class OfficialLoudness {
    final String filter;
    final String reason;
    final double target;
    final double appInGainDb;
    private OfficialLoudness(String filter, String reason, double target, double appInGainDb) {
        this.filter = filter;
        this.reason = reason;
        this.target = target;
        this.appInGainDb = appInGainDb;
    }
    private static OfficialLoudness off(String reason) {
        return new OfficialLoudness("", reason, Double.NaN, 0);
    }
    // measured I/LRA/TP/threshold, target offset/I/TP: original parameter order.
    static OfficialLoudness resolve(String mode, double[] values, Map<String, String> scenes) {
        if ("off".equals(mode))
            return off("disabled");
        if (!"normal".equals(mode) && !"high_dynamic".equals(mode))
            return off("invalid_mode");
        if (values == null || values.length != 7)
            return off("missing_metadata");
        double[] v = values.clone();
        double threshold = -28;
        if (scenes != null && !scenes.isEmpty()) {
            try {
                threshold = Double.parseDouble(scenes.get("undersized_target_i"));
                v[5] = Double.parseDouble(scenes.get(
                        "normal".equals(mode) ? "normal_target_i" : "high_dynamic_target_i"));
            } catch (RuntimeException e) {
                return off("invalid_scene_targets");
            }
            if (!Double.isFinite(threshold) || !Double.isFinite(v[5]))
                return off("invalid_scene_targets");
        }
        double[] min = {-99, 0, -99, -99, -99, -70, -9};
        double[] max = {0, 99, 99, 0, 99, -5, 0};
        for (int i = 0; i < v.length; i++) {
            if (!Double.isFinite(v[i]))
                return off("missing_or_nonfinite_metadata");
            // The official Java copy gate leaves near-zero fields as NaN.
            if (Math.abs(v[i]) <= .0001)
                return off("zero_excluded_by_official_gate");
            if (v[i] < min[i] || v[i] > max[i])
                return off("metadata_out_of_range");
        }
        if (v[0] < threshold)
            return off("below_official_threshold");
        String filter = "aresample=ochl=stereo:resampler=soxr,ijkloudnorm=measured_i=" + v[0]
                + ":measured_lra=" + v[1] + ":measured_tp=" + v[2] + ":measured_thresh=" + v[3]
                + ":offset=" + v[4] + ":I=" + v[5] + ":tp=" + v[6] + ":print_format=summary";
        // app.in 6.6.0 native defaults: gain cap 10dB, peak budget 3dB; intl uses a different algorithm.
        double gainDb = v[2] == 99 ? 0 : Math.min(v[5] - v[0], 10);
        if (gainDb > 0)
            gainDb = Math.min(gainDb, Math.max(Math.max(v[6], 3) - v[2], 0));
        return new OfficialLoudness(filter,
                scenes == null || scenes.isEmpty() ? "requested_without_scenes"
                                                   : "requested_with_scenes",
                v[5], gainDb);
    }
}
