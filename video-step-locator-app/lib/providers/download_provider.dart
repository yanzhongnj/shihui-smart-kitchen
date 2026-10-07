import 'dart:async';
import 'package:flutter/foundation.dart';
import '../models/download_task.dart';
import '../services/api_service.dart';
import '../services/hls_download_service.dart';
import '../services/local_video_store.dart';
import '../utils/error_message.dart';

/// 方案乙「本地下载」单个条目的状态
class LocalDownloadItem {
  final String id;
  final String title;
  String status; // pending | downloading | completed | failed | cancelled
  double progress; // 0..1
  String stage; // resolving | downloading | merging | done
  String? error;
  String? note; // 附加说明（如「已下载过，未重复下载」）

  LocalDownloadItem({
    required this.id,
    required this.title,
    this.status = 'pending',
    this.progress = 0,
    this.stage = '',
    this.error,
    this.note,
  });

  bool get isActive => status == 'pending' || status == 'downloading';
  String get stageText => switch (stage) {
        'resolving' => '正在解析直连地址…',
        'merging' => '正在下载并合并视频…',
        'downloading' => '正在下载…',
        'done' => '完成',
        _ => '',
      };
}

class DownloadProvider extends ChangeNotifier {
  final ApiService _api = ApiService();

  String _deviceId = '';
  Map<String, dynamic>? _parseResult;
  List<DownloadTask> _tasks = [];
  bool _parsing = false;
  String? _error;
  final List<LocalDownloadItem> _localDownloads = [];
  bool _localDownloading = false;

  String get deviceId => _deviceId;
  Map<String, dynamic>? get parseResult => _parseResult;
  List<DownloadTask> get tasks => _tasks;
  bool get isParsing => _parsing;
  String? get error => _error;
  List<LocalDownloadItem> get localDownloads =>
      List.unmodifiable(_localDownloads);
  bool get isLocalDownloading => _localDownloading;

  bool get localDownloadAllDone => _localDownloads.isNotEmpty &&
      _localDownloads.every((x) => !x.isActive);

  /// 游客设备标识（无账号时后端按此配额）
  void setDeviceId(String id) {
    _deviceId = id;
  }

  /// 解析视频链接 → 返回条目列表
  Future<void> parse(String url) async {
    _parsing = true;
    _error = null;
    notifyListeners();
    try {
      final data = await _api.parseUrl(url,
          deviceId: _deviceId.isEmpty ? null : _deviceId);
      _parseResult = data;
    } catch (e) {
      _error = friendlyError(e, fallback: '链接解析失败，请确认链接是否为有效的视频链接');
      _parseResult = null;
    }
    _parsing = false;
    notifyListeners();
  }

  /// 勾选条目 → 创建下载任务
  Future<List<DownloadTask>> start(
      String pageUrl, List<Map<String, dynamic>> entries, String format) async {
    final data = await _api.startDownload(pageUrl, entries, format,
        deviceId: _deviceId.isEmpty ? null : _deviceId);
    final list = data.map((j) => DownloadTask.fromJson(j)).toList();
    _tasks = [...list, ..._tasks.where((t) => !list.any((n) => n.id == t.id))];
    notifyListeners();
    return list;
  }

  Future<void> fetchTasks() async {
    try {
      final data = await _api.getDownloadTasks(
          deviceId: _deviceId.isEmpty ? null : _deviceId);
      _tasks = data.map((j) => DownloadTask.fromJson(j)).toList();
      notifyListeners();
    } catch (e) {
      debugPrint('fetch download tasks failed: $e');
    }
    // 本地优先：已完成的下载任务自动取回文件到手机本地（有去重/防重入保护）
    unawaited(pullCompletedToLocal());
  }

  bool _pulling = false;

  /// 下载任务完成后自动取回文件到手机本地（本地优先缓存；游客与注册用户均可）
  Future<void> pullCompletedToLocal() async {
    if (_pulling) return;
    _pulling = true;
    try {
      final store = LocalVideoStore.instance;
      await store.init();
      for (final t in _tasks) {
        if (t.status == 'completed' && t.duplicate != true) {
          if (store.byTask(t.id) == null) {
            try {
              await store.pullFromDownload(_api, t,
                  deviceId: _deviceId.isEmpty ? null : _deviceId);
            } catch (e) {
              debugPrint('pull local failed for task ${t.id}: $e');
            }
          }
        }
      }
    } finally {
      _pulling = false;
    }
  }

  /// 刷新单个任务进度（轮询用）
  void patchTask(DownloadTask task) {
    final idx = _tasks.indexWhere((t) => t.id == task.id);
    if (idx >= 0) {
      _tasks[idx] = task;
      notifyListeners();
    }
  }

  /// 清空解析结果（下载完成后调用，避免返回页面残留可重复下载的解析结果）
  void clearParse() {
    _parseResult = null;
    _error = null;
    notifyListeners();
  }

  /// 本地取回文件地址（注册用户 token 或游客 deviceId）
  String fileUrl(DownloadTask task) =>
      _api.downloadTaskFileUrl(task.id,
          deviceId: _deviceId.isEmpty ? null : _deviceId);

  /// 在线预览代理地址（注册用户 token 或游客 deviceId）
  String previewUrl(String mediaUrl) =>
      _api.previewUrl(mediaUrl,
          deviceId: _deviceId.isEmpty ? null : _deviceId);

  /// 强制重新下载已完成任务：旧 AV1/HEVC 文件 Android 无法播放，
  /// 重下为 H.264 可播放版本；先清掉旧本地文件，完成后会自动重新取回。
  Future<void> redownload(DownloadTask task) async {
    try {
      await LocalVideoStore.instance.removeByTask(task.id);
    } catch (e) {
      debugPrint('remove local for task ${task.id} failed: $e');
    }
    await _api.redownloadTask(task.id,
        deviceId: _deviceId.isEmpty ? null : _deviceId);
    await fetchTasks();
  }

  // ==================== 方案乙：手机本地下载（后端只解析直连地址，进度纯本地） ====================

  /// 对勾选条目逐条「后端解析直连地址 → 手机本地下载」，进度本地、与后端无关。
  /// 同一来源链接已下载过 → 跳过，不重复下载（本地去重，见 LocalVideoStore.bySourceUrl）。
  Future<void> startLocalDownloads(
      String pageUrl, List<Map<String, dynamic>> entries) async {
    if (_localDownloading) return;
    _localDownloading = true;
    _localDownloads.clear();
    await LocalVideoStore.instance.init();
    notifyListeners();
    for (var i = 0; i < entries.length; i++) {
      final e = entries[i];
      final id = 'ld_${DateTime.now().millisecondsSinceEpoch}_$i';
      final title = (e['title'] ?? '视频').toString();
      final srcUrl = (e['url'] ?? '').toString().trim();
      final item = LocalDownloadItem(id: id, title: title);
      _localDownloads.add(item);
      notifyListeners();
      // 去重：同一来源链接已下载过 → 不重复下载
      if (srcUrl.isNotEmpty &&
          LocalVideoStore.instance.bySourceUrl(srcUrl) != null) {
        item.status = 'completed';
        item.progress = 1;
        item.stage = 'done';
        item.note = '已下载过，未重复下载';
        notifyListeners();
        continue;
      }
      try {
        // 1) 后端解析直连地址 + 防盗链头 + 类型
        item.stage = 'resolving';
        notifyListeners();
        final direct = await _api.resolveDirect(srcUrl,
            deviceId: _deviceId.isEmpty ? null : _deviceId);
        final directUrl = direct['url'] as String;
        final mediaType = (direct['mediaType'] ?? 'mp4') as String;
        final rawHeaders = direct['headers'];
        final headers = rawHeaders is Map
            ? rawHeaders.map((k, v) => MapEntry(k.toString(), v.toString()))
            : <String, String>{};
        // 2) 手机本地下载（hls 走 FFmpeg + master 选 ≤1080p；mp4/audio 走 dio）
        item.status = 'downloading';
        notifyListeners();
        await HlsDownloadService.instance.downloadToLocal(
          directUrl: directUrl,
          mediaType: mediaType,
          headers: headers,
          title: title,
          taskKey: id,
          sourceUrl: srcUrl.isNotEmpty ? srcUrl : null,
          onProgress: (p) {
            item.progress = p;
            notifyListeners();
          },
          onStage: (s) {
            item.stage = s;
            notifyListeners();
          },
        );
        item.status = 'completed';
        item.progress = 1;
        item.stage = 'done';
      } catch (err) {
        item.status = 'failed';
        item.error = friendlyError(err, fallback: '本地下载失败');
      }
      notifyListeners();
    }
    _localDownloading = false;
    notifyListeners();
  }

  /// 取消单个本地下载任务
  void cancelLocalDownload(String id) {
    unawaited(HlsDownloadService.instance.cancel(id));
    final idx = _localDownloads.indexWhere((x) => x.id == id);
    if (idx >= 0) {
      _localDownloads[idx].status = 'cancelled';
      _localDownloads[idx].error = '已取消';
      notifyListeners();
    }
  }

  /// 取消全部进行中的本地下载任务
  void cancelAllLocalDownloads() {
    for (final it in _localDownloads) {
      if (it.isActive) {
        unawaited(HlsDownloadService.instance.cancel(it.id));
        it.status = 'cancelled';
        it.error = '已取消';
      }
    }
    notifyListeners();
  }
}
