import 'dart:convert';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'api_service.dart';
import 'local_video_store.dart';

/// 云端视频步骤的**本地持久化缓存**（双向同步，云端为权威）：
///
/// - 在线查看/编辑：云端为准，写穿本地缓存（`steps_{videoId}.json`）。
/// - 离线查看：读本地缓存（可离线浏览）。
/// - 离线编辑：本地立即生效 + 标记「待同步(pending)」，提示联网后自动同步。
/// - 联网同步：逐条推送 pending 到云端；
///   - 云端该步骤仍等于「上次同步值」（无人改）→ 推送成功，本地跟随云端刷新；
///   - 云端该步骤已被他人/Web 修改（≠上次同步值）→ **冲突，以云端为准**，丢弃本地待同步并提示；
///   - 推送失败（仍离线）→ 保留 pending 待重试；推送失败（业务/锁定）→ 保留待解锁后重试。
class LocalStepCacheStore {
  LocalStepCacheStore._();
  static final LocalStepCacheStore instance = LocalStepCacheStore._();

  Future<File> _file(int videoId) async {
    final dir = await LocalVideoStore.instance.ensureVideosDir();
    return File('${dir.path}${Platform.pathSeparator}steps_$videoId.json');
  }

  Future<LocalStepCache?> read(int videoId) async {
    try {
      final f = await _file(videoId);
      if (!await f.exists()) return null;
      final m = jsonDecode(await f.readAsString()) as Map<String, dynamic>;
      return LocalStepCache.fromJson(m);
    } catch (e) {
      debugPrint('read step cache failed: $e');
      return null;
    }
  }

  /// 删除某视频的本地步骤缓存（视频删除时联动清理，避免孤儿文件）
  Future<void> removeFor(int videoId) async {
    try {
      final f = await _file(videoId);
      if (await f.exists()) await f.delete();
    } catch (e) {
      debugPrint('remove step cache failed: $e');
    }
  }

  Future<void> _write(LocalStepCache cache) async {
    try {
      final f = await _file(cache.videoId);
      await f.writeAsString(jsonEncode(cache.toJson()));
    } catch (e) {
      debugPrint('write step cache failed: $e');
    }
  }

  /// 在线拉取步骤并写入本地缓存（云端为准）。失败抛异常（由调用方回退缓存）。
  Future<List<Map<String, dynamic>>> fetchAndCache(
      ApiService api, int videoId) async {
    final data = await api.getSteps(videoId);
    final steps = data
        .map((s) => Map<String, dynamic>.from(s as Map))
        .toList();
    await _write(LocalStepCache(
      videoId: videoId,
      steps: steps,
      syncedAt: DateTime.now().toIso8601String(),
      lastSynced: {for (final s in steps) (s['id'] as num).toInt(): _value(s)},
      pending: {},
    ));
    return steps;
  }

  /// 编辑步骤：在线写穿（云端成功→本地同步）；离线→本地生效+待同步。
  /// 返回 'cloudApplied' | 'offlineSaved'；业务错误（锁定/权限）抛异常且**不写本地**。
  Future<String> editStep(
    ApiService api,
    int videoId,
    int stepId, {
    required String summary,
    String? originalText,
  }) async {
    final cache = (await read(videoId)) ??
        LocalStepCache(
            videoId: videoId, steps: [], syncedAt: '', lastSynced: {}, pending: {});
    try {
      await api.updateVideoStep(stepId,
          summary: summary, originalText: originalText);
      // 云端成功 → 写穿本地
      _applyToLocal(cache, stepId, summary, originalText);
      cache.lastSynced[stepId] = {'summary': summary, 'originalText': originalText};
      cache.pending.remove(stepId);
      cache.syncedAt = DateTime.now().toIso8601String();
      await _write(cache);
      return 'cloudApplied';
    } on DioException catch (e) {
      if (_isNetworkError(e)) {
        // 离线 → 本地生效 + 待同步
        _applyToLocal(cache, stepId, summary, originalText);
        cache.pending[stepId] = {'summary': summary, 'originalText': originalText};
        await _write(cache);
        return 'offlineSaved';
      }
      rethrow; // 业务错误不写本地
    }
  }

  void _applyToLocal(LocalStepCache cache, int stepId, String summary,
      String? originalText) {
    final idx = cache.steps.indexWhere((s) => (s['id'] as num?)?.toInt() == stepId);
    if (idx >= 0) {
      cache.steps[idx]['summary'] = summary;
      if (originalText != null) cache.steps[idx]['originalText'] = originalText;
    } else {
      cache.steps.add({'id': stepId, 'summary': summary, 'originalText': originalText});
    }
  }

  /// 同步待同步编辑到云端（云端为准）。返回 {pushed, conflict, kept}。
  Future<Map<String, int>> trySyncPending(ApiService api, int videoId) async {
    final cache = await read(videoId);
    if (cache == null || cache.pending.isEmpty) {
      return const {'pushed': 0, 'conflict': 0, 'kept': 0};
    }
    // 拉取云端当前值（用于冲突判定）；仍离线则保留
    List<Map<String, dynamic>> cloudSteps;
    try {
      final data = await api.getSteps(videoId);
      cloudSteps = data.map((s) => Map<String, dynamic>.from(s as Map)).toList();
    } catch (_) {
      return {'pushed': 0, 'conflict': 0, 'kept': cache.pending.length};
    }
    final cloudByStep = {
      for (final s in cloudSteps) (s['id'] as num).toInt(): s
    };
    int pushed = 0, conflict = 0, kept = 0;
    for (final entry in cache.pending.entries) {
      final stepId = entry.key;
      final pend = entry.value;
      final lastSync = cache.lastSynced[stepId];
      final cloudStep = cloudByStep[stepId];
      final cloudVal = cloudStep == null ? null : _value(cloudStep);
      // 冲突：云端已被他人修改（≠上次同步值）→ 以云端为准，丢弃本地待同步
      if (lastSync != null &&
          cloudVal != null &&
          !_sameValue(cloudVal, lastSync)) {
        conflict++;
        cache.pending.remove(stepId);
        continue;
      }
      try {
        await api.updateVideoStep(stepId,
            summary: pend['summary'], originalText: pend['originalText']);
        pushed++;
        cache.pending.remove(stepId);
        cache.lastSynced[stepId] = {
          'summary': pend['summary'],
          'originalText': pend['originalText']
        };
      } catch (_) {
        kept++; // 推送失败（网络/业务）→ 保留待同步重试
      }
    }
    if (pushed > 0 || conflict > 0) {
      // 以云端为准刷新本地
      if (cloudSteps.isNotEmpty) cache.steps = cloudSteps;
      cache.syncedAt = DateTime.now().toIso8601String();
      await _write(cache);
    }
    return {'pushed': pushed, 'conflict': conflict, 'kept': kept};
  }

  Map<String, String?> _value(Map<String, dynamic> s) => {
        'summary': s['summary'] as String?,
        'originalText': s['originalText'] as String?,
      };

  bool _sameValue(Map<String, String?> a, Map<String, String?> b) =>
      (a['summary'] ?? '') == (b['summary'] ?? '') &&
      (a['originalText'] ?? '') == (b['originalText'] ?? '');

  bool _isNetworkError(DioException e) =>
      e.type == DioExceptionType.connectionTimeout ||
      e.type == DioExceptionType.connectionError ||
      e.type == DioExceptionType.receiveTimeout ||
      e.type == DioExceptionType.sendTimeout;
}

/// 单视频步骤缓存结构
class LocalStepCache {
  final int videoId;
  List<Map<String, dynamic>> steps; // 当前展示（本地视角）
  String syncedAt; // 最近与云端同步时间
  Map<int, Map<String, String?>> lastSynced; // stepId → 上次云端确认值（冲突判定）
  Map<int, Map<String, String?>> pending; // stepId → 待同步的离线编辑

  LocalStepCache({
    required this.videoId,
    required this.steps,
    required this.syncedAt,
    required this.lastSynced,
    required this.pending,
  });

  bool get hasPending => pending.isNotEmpty;

  Map<String, dynamic> toJson() => {
        'videoId': videoId,
        'syncedAt': syncedAt,
        'steps': steps,
        'lastSynced': {
          for (final e in lastSynced.entries) '${e.key}': e.value
        },
        'pending': {for (final e in pending.entries) '${e.key}': e.value},
      };

  factory LocalStepCache.fromJson(Map<String, dynamic> j) {
    List<Map<String, dynamic>> steps = [];
    if (j['steps'] is List) {
      steps = (j['steps'] as List)
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
    }
    Map<int, Map<String, String?>> lastSynced = {};
    if (j['lastSynced'] is Map) {
      (j['lastSynced'] as Map).forEach((k, v) {
        final m = Map<String, String?>.from((v as Map).map((kk, vv) => MapEntry(kk.toString(), vv?.toString())));
        lastSynced[int.parse(k.toString())] = m;
      });
    }
    Map<int, Map<String, String?>> pending = {};
    if (j['pending'] is Map) {
      (j['pending'] as Map).forEach((k, v) {
        final m = Map<String, String?>.from((v as Map).map((kk, vv) => MapEntry(kk.toString(), vv?.toString())));
        pending[int.parse(k.toString())] = m;
      });
    }
    return LocalStepCache(
      videoId: (j['videoId'] as num?)?.toInt() ?? 0,
      syncedAt: j['syncedAt'] as String? ?? '',
      steps: steps,
      lastSynced: lastSynced,
      pending: pending,
    );
  }
}
