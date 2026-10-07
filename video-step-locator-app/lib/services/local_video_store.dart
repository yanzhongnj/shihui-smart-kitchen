import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import '../models/download_task.dart';
import 'api_service.dart';

/// 本地视频记录（下载到手机本地的视频）
class LocalVideo {
  final String id;
  final String title;
  final String filePath;
  final String? sourceUrl;
  final int sizeBytes;
  final String createdAt;
  final int? downloadTaskId;

  LocalVideo({
    required this.id,
    required this.title,
    required this.filePath,
    this.sourceUrl,
    this.sizeBytes = 0,
    required this.createdAt,
    this.downloadTaskId,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'filePath': filePath,
        'sourceUrl': sourceUrl,
        'sizeBytes': sizeBytes,
        'createdAt': createdAt,
        'downloadTaskId': downloadTaskId,
      };

  factory LocalVideo.fromJson(Map<String, dynamic> j) => LocalVideo(
        id: j['id'],
        title: j['title'] ?? '视频',
        filePath: j['filePath'],
        sourceUrl: j['sourceUrl'],
        sizeBytes: (j['sizeBytes'] as num?)?.toInt() ?? 0,
        createdAt: j['createdAt'] ?? '',
        downloadTaskId: (j['downloadTaskId'] as num?)?.toInt(),
      );

  String get displayTitle =>
      title.replaceAll(RegExp(r'\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$',
          caseSensitive: false), '');

  String get fileSizeText {
    if (sizeBytes <= 0) return '';
    return sizeBytes > 1048576
        ? '${(sizeBytes / 1048576).toStringAsFixed(1)}MB'
        : '${(sizeBytes / 1024).toStringAsFixed(1)}KB';
  }
}

/// 本地视频存储：文件放在应用文档目录 local_videos/，索引存于 index.json。
/// 新流程：链接下载的视频先保存到手机本地（游客与注册用户均可本地播放）；
/// 需上云时通过「上传视频」把本地文件上传到服务器。
class LocalVideoStore {
  LocalVideoStore._();
  static final LocalVideoStore instance = LocalVideoStore._();

  final List<LocalVideo> _videos = [];
  final List<LocalMedia> _media = [];
  Directory? _dir;
  bool _loaded = false;

  List<LocalVideo> get videos => List.unmodifiable(_videos);
  List<LocalMedia> get media => List.unmodifiable(_media);
  bool get isLoaded => _loaded;

  Future<Directory> _ensureDir() async {
    final docs = await getApplicationDocumentsDirectory();
    final dir =
        Directory('${docs.path}${Platform.pathSeparator}local_videos');
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }
    return dir;
  }

  /// 本地视频目录（供本地下载服务把 HLS/直链下载产物写入同一 local_videos/ 目录）
  Future<Directory> ensureVideosDir() async {
    await init();
    return _ensureDir();
  }

  Future<void> init() async {
    if (_loaded) return;
    _dir = await _ensureDir();
    final indexFile =
        File('${_dir!.path}${Platform.pathSeparator}index.json');
    if (await indexFile.exists()) {
      try {
        final list = jsonDecode(await indexFile.readAsString()) as List;
        _videos
          ..clear()
          ..addAll(list.map(
              (e) => LocalVideo.fromJson(e as Map<String, dynamic>)));
      } catch (_) {}
    }
    // AI 创作媒体本地缓存索引（index_media.json）
    final mediaFile =
        File('${_dir!.path}${Platform.pathSeparator}index_media.json');
    if (await mediaFile.exists()) {
      try {
        final list = jsonDecode(await mediaFile.readAsString()) as List;
        _media
          ..clear()
          ..addAll(list.map(
              (e) => LocalMedia.fromJson(e as Map<String, dynamic>)));
      } catch (_) {}
    }
    _loaded = true;
  }

  Future<void> _saveIndex() async {
    final indexFile =
        File('${_dir!.path}${Platform.pathSeparator}index.json');
    await indexFile.writeAsString(
        jsonEncode(_videos.map((v) => v.toJson()).toList()));
  }

  /// 注册一条本地视频（文件已保存在本地后调用）
  Future<LocalVideo> add({
    required String title,
    required String filePath,
    String? sourceUrl,
    int? downloadTaskId,
  }) async {
    await init();
    final f = File(filePath);
    int size = 0;
    try {
      size = await f.length();
    } catch (_) {}
    final v = LocalVideo(
      id: 'local_${DateTime.now().millisecondsSinceEpoch}',
      title: title,
      filePath: filePath,
      sourceUrl: sourceUrl,
      sizeBytes: size,
      createdAt: DateTime.now().toIso8601String(),
      downloadTaskId: downloadTaskId,
    );
    _videos.insert(0, v);
    await _saveIndex();
    return v;
  }

  Future<void> remove(String id) async {
    await init();
    final idx = _videos.indexWhere((v) => v.id == id);
    if (idx < 0) return;
    final v = _videos.removeAt(idx);
    try {
      final f = File(v.filePath);
      if (await f.exists()) await f.delete();
    } catch (_) {}
    await _saveIndex();
  }

  /// 修改本地视频标题（仅显示用，不影响文件与去重）
  Future<void> rename(String id, String newTitle) async {
    await init();
    final idx = _videos.indexWhere((v) => v.id == id);
    if (idx < 0) return;
    final v = _videos[idx];
    final t = newTitle.trim();
    if (t.isEmpty || t == v.title) return;
    _videos[idx] = LocalVideo(
      id: v.id,
      title: t,
      filePath: v.filePath,
      sourceUrl: v.sourceUrl,
      sizeBytes: v.sizeBytes,
      createdAt: v.createdAt,
      downloadTaskId: v.downloadTaskId,
    );
    await _saveIndex();
  }

  /// 删除指定下载任务对应的本地视频（重新下载时先清理旧文件）
  Future<void> removeByTask(int taskId) async {
    await init();
    final matches =
        _videos.where((v) => v.downloadTaskId == taskId).toList();
    for (final v in matches) {
      await remove(v.id);
    }
  }

  /// 按下载任务 id 查找本地视频（任务播放优先本地文件）
  LocalVideo? byTask(int taskId) {
    for (final v in _videos) {
      if (v.downloadTaskId == taskId) return v;
    }
    return null;
  }

  /// 按来源链接查找本地视频（本地下载去重：同一链接不重复下载）
  LocalVideo? bySourceUrl(String url) {
    if (url.isEmpty) return null;
    for (final v in _videos) {
      if (v.sourceUrl == url) return v;
    }
    return null;
  }

  /// 从后端下载任务取回文件并保存为本地视频（新流程核心：下载→本地）
  Future<LocalVideo> pullFromDownload(
    ApiService api,
    DownloadTask task, {
    String? deviceId,
  }) async {
    await init();
    final dir = await _ensureDir();
    final safeTitle =
        task.title.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_').trim();
    final ext = (task.format != null && task.format!.isNotEmpty)
        ? task.format!
        : 'mp4';
    final savePath =
        '${dir.path}${Platform.pathSeparator}${DateTime.now().millisecondsSinceEpoch}_${safeTitle.isEmpty ? 'video' : safeTitle}.$ext';
    await api.downloadToLocal(task.id, savePath, deviceId: deviceId);
    return add(
      title: task.title,
      filePath: savePath,
      sourceUrl: task.sourceUrl,
      downloadTaskId: task.id,
    );
  }

  // ==================== AI 创作媒体本地缓存（整片/讲解/分段） ====================

  Future<void> _saveMediaIndex() async {
    final mediaFile =
        File('${_dir!.path}${Platform.pathSeparator}index_media.json');
    await mediaFile.writeAsString(
        jsonEncode(_media.map((m) => m.toJson()).toList()));
  }

  /// 查找 AI 创作媒体本地缓存（无则返回 null）
  LocalMedia? findAiMedia(int creationId, String type, int? stepIndex) {
    final key = LocalMedia.makeKey(creationId, type, stepIndex);
    for (final m in _media) {
      if (m.key == key) return m;
    }
    return null;
  }

  /// AI 创作媒体本地优先：命中缓存直接用；否则下载到本地缓存后返回路径（失败返回 null）
  Future<String?> cacheAiMedia(
    ApiService api, {
    required int creationId,
    required String type, // 'video' | 'tts' | 'segment'
    int? stepIndex,
    required String url,
    String ext = 'mp4',
  }) async {
    await init();
    final existing = findAiMedia(creationId, type, stepIndex);
    if (existing != null) return existing.filePath;
    final dir = await _ensureDir();
    final key = LocalMedia.makeKey(creationId, type, stepIndex);
    final savePath =
        '${dir.path}${Platform.pathSeparator}${key}_${DateTime.now().millisecondsSinceEpoch}.$ext';
    try {
      await api.downloadUrlToFile(url, savePath);
    } catch (e) {
      debugPrint('cache Ai media failed: $e');
      try {
        final f = File(savePath);
        if (await f.exists()) await f.delete();
      } catch (_) {}
      return null;
    }
    int size = 0;
    try {
      size = await File(savePath).length();
    } catch (_) {}
    final m = LocalMedia(
      key: key,
      creationId: creationId,
      type: type,
      stepIndex: stepIndex,
      filePath: savePath,
      sizeBytes: size,
      createdAt: DateTime.now().toIso8601String(),
    );
    _media.removeWhere((x) => x.key == key);
    _media.add(m);
    await _saveMediaIndex();
    return savePath;
  }

  /// 删除某创作的全部本地媒体（含文件）
  Future<void> removeAiMediaFor(int creationId) async {
    await init();
    final removed = _media.where((m) => m.creationId == creationId).toList();
    for (final m in removed) {
      try {
        final f = File(m.filePath);
        if (await f.exists()) await f.delete();
      } catch (_) {}
    }
    _media.removeWhere((m) => m.creationId == creationId);
    await _saveMediaIndex();
  }
}

/// AI 创作本地媒体记录（整片视频 / 每步讲解 / 单步分段）
class LocalMedia {
  final String key;
  final int creationId;
  final String type; // 'video' | 'tts' | 'segment'
  final int? stepIndex;
  final String filePath;
  final int sizeBytes;
  final String createdAt;

  LocalMedia({
    required this.key,
    required this.creationId,
    required this.type,
    this.stepIndex,
    required this.filePath,
    this.sizeBytes = 0,
    required this.createdAt,
  });

  static String makeKey(int creationId, String type, int? stepIndex) =>
      'creation_${creationId}_$type${stepIndex != null ? '_$stepIndex' : ''}';

  Map<String, dynamic> toJson() => {
        'key': key,
        'creationId': creationId,
        'type': type,
        'stepIndex': stepIndex,
        'filePath': filePath,
        'sizeBytes': sizeBytes,
        'createdAt': createdAt,
      };

  factory LocalMedia.fromJson(Map<String, dynamic> j) => LocalMedia(
        key: j['key'],
        creationId: (j['creationId'] as num?)?.toInt() ?? 0,
        type: j['type'] ?? 'video',
        stepIndex: (j['stepIndex'] as num?)?.toInt(),
        filePath: j['filePath'],
        sizeBytes: (j['sizeBytes'] as num?)?.toInt() ?? 0,
        createdAt: j['createdAt'] ?? '',
      );
}
