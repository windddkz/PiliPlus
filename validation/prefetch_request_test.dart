import 'dart:typed_data';

import 'package:PiliPlus/http/retry_interceptor.dart';
import 'package:PiliPlus/utils/accounts/account_manager/account_mgr.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

class FailingAdapter implements HttpClientAdapter {
  int attempts = 0;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? body,
    Future<void>? cancel,
  ) async {
    attempts++;
    throw DioException.connectionError(
      requestOptions: options,
      reason: 'offline',
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  test(
    'background failure skips retries; ordinary requests retain retries',
    () async {
      for (final prefetch in [true, false]) {
        final adapter = FailingAdapter();
        final dio = Dio()..httpClientAdapter = adapter;
        dio.interceptors.add(RetryInterceptor(dio, 1, 0));
        await expectLater(
          dio.get<void>(
            'https://media.invalid',
            options: Options(extra: {'playUrlPrefetch': prefetch}),
          ),
          throwsA(isA<DioException>()),
        );
        expect(adapter.attempts, prefetch ? 1 : 2);
        dio.close(force: true);
      }
    },
  );
  test('background failure needs no UI or initialized account settings', () {
    AccountManager.toast(
      DioException(
        requestOptions: RequestOptions(
          path: 'https://media.invalid',
          extra: {'playUrlPrefetch': true},
        ),
      ),
    );
  });
}
