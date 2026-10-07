import 'dart:async';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:ffmpeg_kit_flutter_new_https/ffmpeg_kit.dart';
import 'package:ffmpeg_kit_flutter_new_https/ffmpeg_kit_config.dart';
import 'package:ffmpeg_kit_flutter_new_https/ffmpeg_session.dart';
import 'package:ffmpeg_kit_flutter_new_https/ffprobe_kit.dart';
import 'package:ffmpeg_kit_flutter_new_https/return_code.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_hls_parser/flutter_hls_parser.dart';
import 'api_service.dart';
import 'local_video_store.dart';

/// 方案乙「A+B 轻量结合」本地下载服务（Android 落地；iOS 代码兼容、暂不验证）。
///
/// - hls(m3u8)：Dart(`flutter_hls_parser`) 解析 master → 选 ≤1080p 最高变体 →
///   FFmpeg 本地下载+AES-128 解密+合并成单个 mp4（进度本地、与后端无关）。
/// - mp4/audio：dio 直链下载（带防盗链头，字节进度）。
///
/// 产物经 [LocalVideoStore.add] 登记到 `local_videos/`，无缝衔接「我的视频 /
/// 显式上传云端 / 智能处理」链路。
class HlsDownloadService {
  HlsDownloadService._();
  static final HlsDownloadService instance = HlsDownloadService._();

  bool _ready = false;
  Future<void>? _initFuture;

  /// 进行中的 FFmpeg 会话（按任务 key），用于取消
  final Map<String, FFmpegSession> _sessions = {};
  /// 进行中的 dio 下载（按任务 key），用于取消
  final Map<String, CancelToken> _tokens = {};

  Future<void> _ensureReady() async {
    if (_ready) return;
    _initFuture ??= FFmpegKitConfig.init().then((_) => _ready = true);
    await _initFuture;
  }

  /// 本地下载入口：将 resolve 出的直连地址下载到手机本地并登记。
  ///
  /// [onProgress] 0..1；[onStage] resolving|downloading|merging|done。
  /// 失败会清理不完整文件并 rethrow。
  Future<LocalVideo> downloadToLocal({
    required String directUrl,
    required String mediaType, // 'hls' | 'mp4' | 'audio'
    Map<String, String> headers = const {},
    required String title,
    String? taskKey,
    String? sourceUrl, // 稳定来源链接（去重键），默认回退 directUrl
    void Function(double progress)? onProgress,
    void Function(String stage)? onStage,
  }) async {
    await _ensureReady();
    await LocalVideoStore.instance.init();
    final dir = await LocalVideoStore.instance.ensureVideosDir();
    final safeTitle = title.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_').trim();
    final key = taskKey ?? 'dl_${DateTime.now().millisecondsSinceEpoch}';
    final savePath =
        '${dir.path}${Platform.pathSeparator}${DateTime.now().millisecondsSinceEpoch}_${safeTitle.isEmpty ? 'video' : safeTitle}.mp4';
    try {
      if (mediaType == 'hls') {
        onStage?.call('resolving');
        final mediaUri = await _pickVariant(directUrl, headers);
        onStage?.call('downloading');
        await _ffmpegDownload(
            key, mediaUri.toString(), headers, savePath, onProgress);
      } else {
        onStage?.call('downloading');
        await _plainDownload(
            key, directUrl, headers, savePath, onProgress);
      }
      onStage?.call('done');
      return await LocalVideoStore.instance.add(
          title: title,
          filePath: savePath,
          sourceUrl: sourceUrl ?? directUrl);
    } catch (e) {
      // 清理不完整/失败产物
      try {
        final f = File(savePath);
        if (await f.exists()) await f.delete();
      } catch (_) {}
      _sessions.remove(key);
      _tokens.remove(key);
      rethrow;
    }
  }

  /// 取消指定本地下载任务（FFmpeg 会话或 dio CancelToken）
  Future<void> cancel(String taskKey) async {
    final s = _sessions.remove(taskKey);
    if (s != null) {
      try {
        await FFmpegKit.cancel(s.getSessionId());
      } catch (e) {
        debugPrint('ffmpeg cancel failed: $e');
      }
    }
    final t = _tokens.remove(taskKey);
    t?.cancel();
  }

  /// A+B 轻量：拉取 m3u8 → 若 master 选 ≤[maxHeight] 的最高变体（返回其 media 清单 URL）；
  /// media 清单直接返回。解析失败/无变体时回退原 URL（交给 FFmpeg 自行处理）。
  Future<Uri> _pickVariant(String m3u8Url, Map<String, String> headers,
      {int maxHeight = 1080}) async {
    final text = await _fetchText(m3u8Url, headers);
    final HlsPlaylist playlist;
    try {
      playlist = await HlsPlaylistParser.create()
          .parseString(Uri.parse(m3u8Url), text);
    } on ParserException catch (e) {
      debugPrint('m3u8 parse failed, fallback original url: ${e.message}');
      return Uri.parse(m3u8Url);
    }
    if (playlist is HlsMasterPlaylist) {
      final variants = playlist.variants;
      if (variants.isEmpty) return Uri.parse(m3u8Url);
      Variant? best;
      int bestHeight = -1;
      for (final v in variants) {
        final h = v.format.height ?? 0;
        if (h > 0 && h <= maxHeight && h > bestHeight) {
          bestHeight = h;
          best = v;
        }
      }
      best ??= variants.first; // 全部超限或无法判定 → 取首个
      return _absUri(Uri.parse(m3u8Url), best.url);
    }
    return Uri.parse(m3u8Url);
  }

  Uri _absUri(Uri base, Uri uri) =>
      uri.hasScheme ? uri : base.resolve(uri.toString());

  /// 拉取 m3u8 文本（带防盗链头）
  Future<String> _fetchText(String url, Map<String, String> headers) async {
    final dio = Dio();
    final res = await dio.get<String>(url,
        options: Options(
            headers: _mediaHeaders(headers),
            responseType: ResponseType.plain,
            followRedirects: true));
    return res.data ?? '';
  }

  /// FFmpeg 本地下载+合并：-headers -i <media_m3u8> -c copy -movflags +faststart out.mp4
  Future<void> _ffmpegDownload(
    String key,
    String mediaUrl,
    Map<String, String> headers,
    String savePath,
    void Function(double)? onProgress,
  ) async {
    final headerStr = _headerLine(headers);
    final args = <String>[
      if (headerStr.isNotEmpty) '-headers',
      if (headerStr.isNotEmpty) headerStr,
      '-i',
      mediaUrl,
      '-c',
      'copy',
      '-movflags',
      '+faststart',
      '-y',
      savePath,
    ];
    // 用 ffprobe 探测总时长（毫秒）供进度换算；失败则进度退化为不确定
    final totalMs = await _probeDurationMs(mediaUrl, headerStr);
    final completer = Completer<FFmpegSession>();
    final session = await FFmpegKit.executeWithArgumentsAsync(
      args,
      (s) {
        if (!completer.isCompleted) completer.complete(s);
      },
      null,
      (stat) {
        if (totalMs != null && totalMs > 0 && onProgress != null) {
          onProgress((stat.getTime() / totalMs).clamp(0.0, 1.0));
        }
      },
    );
    _sessions[key] = session;
    final finished = await completer.future.timeout(
      const Duration(minutes: 30),
      onTimeout: () {
        // 超时视为失败并尝试取消
        unawaited(FFmpegKit.cancel(session.getSessionId()));
        throw TimeoutException('FFmpeg 下载超时');
      },
    );
    final rc = await finished.getReturnCode();
    _sessions.remove(key);
    if (ReturnCode.isCancel(rc)) {
      throw Exception('已取消下载');
    }
    if (!ReturnCode.isSuccess(rc)) {
      throw Exception('FFmpeg 下载/合并失败 (code=${rc?.getValue()})');
    }
  }

  /// ffprobe 探测 m3u8 总时长（毫秒），供 FFmpeg 进度换算；失败返回 null（不确定进度）
  Future<int?> _probeDurationMs(String mediaUrl, String headerStr) async {
    try {
      final session = await FFprobeKit
          .getMediaInformationFromCommandArguments(<String>[
        '-v', 'error', '-hide_banner', '-print_format', 'json',
        '-show_format', '-show_streams',
        if (headerStr.isNotEmpty) '-headers',
        if (headerStr.isNotEmpty) headerStr,
        '-i', mediaUrl,
      ]).timeout(const Duration(seconds: 20));
      final duration = double.tryParse(
              session.getMediaInformation()?.getDuration() ?? '') ??
          -1;
      if (duration > 0) return (duration * 1000).round();
    } catch (e) {
      debugPrint('ffprobe duration failed, use indeterminate progress: $e');
    }
    return null;
  }

  /// 直链 mp4/audio：dio 下载（带防盗链头，字节进度）
  Future<void> _plainDownload(
    String key,
    String url,
    Map<String, String> headers,
    String savePath,
    void Function(double)? onProgress,
  ) async {
    final api = ApiService();
    final token = CancelToken();
    _tokens[key] = token;
    await api.downloadUrlToFile(
      url,
      savePath,
      headers: headers,
      onReceiveProgress: (received, total) {
        if (onProgress != null && total > 0) {
          onProgress((received / total).clamp(0.0, 1.0));
        }
      },
    );
    _tokens.remove(key);
  }

  /// 归一化请求头：去掉 Range/Accept-Encoding 干扰，补默认移动端 UA
  Map<String, String> _mediaHeaders(Map<String, String> headers) {
    final out = <String, String>{};
    headers.forEach((k, v) {
      final lk = k.toLowerCase();
      if (lk != 'range' && lk != 'accept-encoding' && k.isNotEmpty) {
        out[k] = v;
      }
    });
    out.putIfAbsent(
        'User-Agent',
        () =>
            'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36');
    return out;
  }

  /// FFmpeg -headers 需要的 CRLF 分隔头字符串
  String _headerLine(Map<String, String> headers) {
    final parts = <String>[];
    _mediaHeaders(headers).forEach((k, v) => parts.add('$k: $v'));
    return parts.join('\r\n');
  }
}
