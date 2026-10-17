import 'package:PiliPlus/models/video/play/url.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'metadata distinguishes missing, zero, non-finite and per-resource scenes',
    () {
      final value = Volume.fromJson({
        'measured_i': -20.57,
        'measured_lra': 0,
        'measured_tp': double.nan,
        'measured_threshold': -30,
        'target_offset': .2,
        'target_i': -16,
        'multi_scene_args': {
          'undersized_target_i': '-28',
          'normal_target_i': '-16',
          'high_dynamic_target_i': '-22',
        },
      });
      expect(value.officialValues, [-20.57, 0, null, -30, .2, -16, null]);
      expect(value.multiSceneArgs!['high_dynamic_target_i'], '-22');
      expect(Volume.fromJson({}).officialValues, List<num?>.filled(7, null));
      expect(Volume.fromJson({'multi_scene_args': 'invalid'}).multiSceneArgs, {
        'invalid': 'invalid',
      });
      expect(Volume.fromJson({'measured_i': 'bad'}).officialValues.first, null);
    },
  );
}
