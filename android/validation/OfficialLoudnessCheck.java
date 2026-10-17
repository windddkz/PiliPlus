package com.example.piliplus.official;

import java.util.Map;

public final class OfficialLoudnessCheck {
    public static void main(String[] args) {
        double[] valid = {-20.57,3,-18.06,-30.71,.2,-16,-1.5};
        OfficialLoudness normal = OfficialLoudness.resolve("normal", valid, null);
        assert normal.filter.equals("aresample=ochl=stereo:resampler=soxr,ijkloudnorm=measured_i=-20.57:measured_lra=3.0:measured_tp=-18.06:measured_thresh=-30.71:offset=0.2:I=-16.0:tp=-1.5:print_format=summary");
        Map<String,String> scenes=Map.of("undersized_target_i","-28","normal_target_i","-16","high_dynamic_target_i","-20.5");
        assert OfficialLoudness.resolve("high_dynamic",valid,scenes).target == -20.5;
        assert Math.abs(normal.appInGainDb - 4.57) < 1e-12;
        assert Math.abs(OfficialLoudness.resolve("high_dynamic",valid,scenes).appInGainDb - .07) < 1e-12;
        double[] peakLimited = valid.clone(); peakLimited[2] = 5;
        assert OfficialLoudness.resolve("normal",peakLimited,null).appInGainDb == 0;
        peakLimited[2] = 2;
        assert OfficialLoudness.resolve("normal",peakLimited,null).appInGainDb == 1;
        peakLimited[0] = -28; peakLimited[5] = -5; peakLimited[2] = -18;
        assert OfficialLoudness.resolve("normal",peakLimited,null).appInGainDb == 10;
        peakLimited[0] = -10; peakLimited[5] = -16; peakLimited[2] = 5;
        assert OfficialLoudness.resolve("normal",peakLimited,null).appInGainDb == -6;
        assert OfficialLoudness.resolve("off",valid,null).appInGainDb == 0;
        // BV1Sc411U7m6 public playurl with voice_balance=1, 2026-10-11.
        double[] ferry = {-12.7,4.2,1.6,-22.9,-.5,-14,-1};
        Map<String,String> ferryScenes=Map.of("undersized_target_i","-28","normal_target_i","-14","high_dynamic_target_i","-24");
        assert Math.abs(OfficialLoudness.resolve("normal",ferry,ferryScenes).appInGainDb + 1.3) < 1e-12;
        assert Math.abs(OfficialLoudness.resolve("high_dynamic",ferry,ferryScenes).appInGainDb + 11.3) < 1e-12;
        assert valid[5] == -16;
        assert OfficialLoudness.resolve("normal",valid,Map.of("normal_target_i","-16")).filter.isEmpty();
        assert OfficialLoudness.resolve("normal",null,null).reason.equals("missing_metadata");
        assert OfficialLoudness.resolve("off",valid,null).reason.equals("disabled");
        assert OfficialLoudness.resolve("invalid",valid,null).filter.isEmpty();
        for(int i=0;i<7;i++) {
            double[] v=valid.clone();v[i]=Double.NaN;
            assert OfficialLoudness.resolve("normal",v,null).filter.isEmpty();
            v[i]=Double.POSITIVE_INFINITY;
            assert OfficialLoudness.resolve("normal",v,null).filter.isEmpty();
            v[i]=0;
            assert OfficialLoudness.resolve("normal",v,null).reason.equals("zero_excluded_by_official_gate");
        }
        double[] quiet=valid.clone();quiet[0]=-28.01;
        assert OfficialLoudness.resolve("normal",quiet,null).reason.equals("below_official_threshold");
        quiet[0]=-28;
        assert !OfficialLoudness.resolve("normal",quiet,null).filter.isEmpty();
        double[] sentinel=valid.clone();sentinel[2]=99;
        assert !OfficialLoudness.resolve("normal",sentinel,null).filter.isEmpty();
        // Native init handles measuredTP=99 as unity; do not invent a boost here.
        assert OfficialLoudness.resolve("normal",sentinel,null).appInGainDb == 0;
        sentinel[2]=100;
        assert OfficialLoudness.resolve("normal",sentinel,null).reason.equals("metadata_out_of_range");
        System.out.println("Official copy gates, scene targets, bounds, sentinel and no mutation passed");
    }
}
