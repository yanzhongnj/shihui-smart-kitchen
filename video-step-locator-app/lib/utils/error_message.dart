import 'dart:async';
import 'dart:io';
import 'package:dio/dio.dart';

/// 将各类异常转换为友好中文提示。
/// 重点：无法连接后端时给出清晰、不暴露技术细节的提示。
/// - 后端业务错误（HTTP 200 + code!=200 / 400/429/5xx）→ 优先取后端 message
/// - 连接失败 / 超时 / Socket 异常 → 「无法连接服务器，请检查网络」
String friendlyError(Object? e, {String fallback = '网络异常，请稍后重试'}) {
  if (e is DioException) {
    // 后端返回的业务错误信息（如「游客解析次数已达上限」「用户名或密码错误」）
    final respData = e.response?.data;
    if (respData is Map &&
        respData['message'] is String &&
        (respData['message'] as String).isNotEmpty) {
      final m = respData['message'] as String;
      if (!m.contains('DioException') &&
          !m.contains('SocketException') &&
          !m.contains('type ')) {
        return m;
      }
    }
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
        return '连接服务器超时，请检查网络后重试';
      case DioExceptionType.connectionError:
        return '无法连接服务器，请检查网络或确认后端已启动';
      case DioExceptionType.badCertificate:
        return '服务器证书校验失败';
      case DioExceptionType.cancel:
        return '请求已取消';
      case DioExceptionType.badResponse:
        final status = e.response?.statusCode;
        if (status == 401) return '登录已过期，请重新登录';
        if (status == 403) return '无权限执行该操作';
        if (status == 404) return '请求的资源不存在';
        if (status == 429) return '操作过于频繁，请稍后再试';
        if (status != null && status >= 500) return '服务器繁忙，请稍后重试';
        if (status != null) return '请求失败($status)，请稍后重试';
        if (e.message != null && e.message!.isNotEmpty) return e.message!;
        return fallback;
      case DioExceptionType.unknown:
        final err = e.error;
        if (err is SocketException) return '无法连接服务器，请检查网络后重试';
        if (err is HandshakeException) return '无法建立安全连接，请检查网络';
        if (err is TimeoutException) return '连接服务器超时，请稍后重试';
        return fallback;
    }
  }
  if (e is SocketException) return '无法连接服务器，请检查网络后重试';
  if (e is HandshakeException) return '无法建立安全连接，请检查网络';
  if (e is TimeoutException) return '连接服务器超时，请稍后重试';
  return fallback;
}
