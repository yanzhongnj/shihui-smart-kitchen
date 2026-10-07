import 'package:flutter/foundation.dart';
import '../models/creation_models.dart';
import '../services/api_service.dart';
import '../services/local_video_store.dart';
import '../utils/error_message.dart';

/// 酿味（AI 菜品创作）状态管理（P0）
class CreationProvider extends ChangeNotifier {
  final ApiService _api = ApiService();

  List<CreationSession> _sessions = [];
  int? _currentSessionId;
  List<CreationMessage> _messages = [];
  bool _sending = false;
  bool _confirming = false;
  String? _error;

  /// 最近一次 AI 确认消息的意图快照（供确认卡展示/确认用）
  String? _pendingIntentJson;
  String? _lastAction;

  List<CreationItem> _creations = [];
  bool _creationsLoading = false;
  int _creationsPage = 1;
  int _creationsTotal = 0;
  bool _creationsHasMore = true;

  List<CreationSession> get sessions => _sessions;
  int? get currentSessionId => _currentSessionId;
  List<CreationMessage> get messages => _messages;
  bool get isSending => _sending;
  bool get isConfirming => _confirming;
  String? get error => _error;
  String? get lastAction => _lastAction;
  String? get pendingIntentJson => _pendingIntentJson;

  List<CreationItem> get creations => _creations;
  bool get creationsLoading => _creationsLoading;
  int get creationsTotal => _creationsTotal;
  bool get creationsHasMore => _creationsHasMore;

  CreationSession? get currentSession {
    for (final s in _sessions) {
      if (s.id == _currentSessionId) return s;
    }
    return null;
  }

  // ---------------- 会话 ----------------

  Future<void> loadSessions() async {
    try {
      final data = await _api.listCreationSessions();
      _sessions = data
          .map((j) => CreationSession.fromJson(j as Map<String, dynamic>))
          .toList();
    } catch (e) {
      _error = friendlyError(e, fallback: '加载创作会话失败');
    }
    notifyListeners();
  }

  /// 新建创作会话并打开
  Future<int> createSession() async {
    final id = await _api.createCreationSession();
    await loadSessions();
    _currentSessionId = id;
    _messages = [];
    _pendingIntentJson = null;
    _lastAction = null;
    notifyListeners();
    return id;
  }

  Future<void> openSession(int id) async {
    _currentSessionId = id;
    _lastAction = null;
    await loadMessages(id);
  }

  Future<void> loadMessages(int id) async {
    try {
      final data = await _api.getCreationMessages(id);
      _messages = data
          .map((j) => CreationMessage.fromJson(j as Map<String, dynamic>))
          .toList();
    } catch (e) {
      _error = friendlyError(e, fallback: '加载消息失败');
    }
    notifyListeners();
  }

  /// 发送消息：追加用户气泡 + AI 回复；返回响应（含 action），供页面展示确认卡。
  /// [imagePath]/[videoId]/[url] 支持多源附件（P1）。
  Future<Map<String, dynamic>> sendMessage(String text,
      {String? imagePath,
      String? imageFilename,
      int? videoId,
      String? url,
      String? voiceText}) async {
    final sid = _currentSessionId;
    final hasAttach = imagePath != null ||
        videoId != null ||
        (url != null && url.trim().isNotEmpty) ||
        (voiceText != null && voiceText.trim().isNotEmpty);
    if (sid == null || (text.trim().isEmpty && !hasAttach)) return {};
    _messages.add(CreationMessage(id: 0, role: 'user', content: text.trim()));
    _sending = true;
    _error = null;
    notifyListeners();
    try {
      final res = hasAttach
          ? await _api.sendCreationMessageWithAttachments(sid, text.trim(),
              imagePath: imagePath,
              imageFilename: imageFilename,
              videoId: videoId,
              url: url,
              voiceText: voiceText)
          : await _api.sendCreationMessage(sid, text.trim());
      final action = res['action'] as String? ?? 'ask';
      final reply = res['reply'] as String? ?? '';
      final intentJson = res['intentJson'] as String? ?? '';
      _lastAction = action;
      _pendingIntentJson = intentJson;
      _messages.add(CreationMessage(
          id: 0, role: 'assistant', content: reply, intentJson: intentJson));
      await loadSessions(); // 刷新标题/状态徽标
      return res;
    } catch (e) {
      _error = friendlyError(e, fallback: '发送失败');
      _messages.add(CreationMessage(
          id: 0,
          role: 'assistant',
          content: '抱歉，${friendlyError(e, fallback: '发送失败，请稍后重试')}'));
      return {};
    } finally {
      _sending = false;
      notifyListeners();
    }
  }

  /// 确认创作意图 → 生成菜谱，返回详情
  Future<Map<String, dynamic>?> confirm() async {
    final sid = _currentSessionId;
    if (sid == null) return null;
    _confirming = true;
    _error = null;
    notifyListeners();
    try {
      final detail = await _api.confirmCreation(sid);
      final title = detail['title'] as String? ?? '新菜谱';
      _messages.add(CreationMessage(
          id: 0,
          role: 'assistant',
          content: '已生成新菜谱「$title」！可打开详情开始跟做，或继续对话迭代。'));
      _lastAction = 'confirm';
      await loadSessions();
      return detail;
    } catch (e) {
      _error = friendlyError(e, fallback: '生成菜谱失败');
      _messages.add(CreationMessage(
          id: 0,
          role: 'assistant',
          content: '抱歉，${friendlyError(e, fallback: '生成菜谱失败，请稍后重试')}'));
      // 生成失败（如安全校验拦截）→ 回到对话态、收起确认卡，请用户调整后重新发送
      _lastAction = 'ask';
      return null;
    } finally {
      _confirming = false;
      notifyListeners();
    }
  }

  // ---------------- 我的创作 ----------------

  Future<void> loadCreations({bool refresh = false}) async {
    if (refresh) {
      _creationsPage = 1;
      _creationsHasMore = true;
    }
    if (_creationsLoading || !_creationsHasMore) return;
    _creationsLoading = true;
    notifyListeners();
    try {
      final data = await _api.getCreations(page: _creationsPage, size: 20);
      final items = (data['items'] as List? ?? [])
          .whereType<Map<String, dynamic>>()
          .map(CreationItem.fromJson)
          .toList();
      _creationsTotal = (data['total'] as num?)?.toInt() ?? 0;
      if (refresh) {
        _creations = items;
      } else {
        _creations.addAll(items);
      }
      _creationsHasMore = _creations.length < _creationsTotal;
      _creationsPage++;
    } catch (e) {
      _error = friendlyError(e, fallback: '加载我的创作失败');
    } finally {
      _creationsLoading = false;
      notifyListeners();
    }
  }

  Future<Map<String, dynamic>> getDetail(int id) async =>
      _api.getCreationDetail(id);

  /// 编辑单步内容（摘要/详情/计时），返回更新后的详情（页面可据此刷新）
  Future<Map<String, dynamic>> updateStep(int id, int stepIndex,
          {String? summary, String? detail, int? timerMin}) async =>
      _api.updateCreationStep(id, stepIndex,
          summary: summary, detail: detail, timerMin: timerMin);

  Future<void> deleteCreation(int id) async {
    await _api.deleteCreation(id);
    _creations.removeWhere((c) => c.id == id);
    notifyListeners();
  }

  /// 删除创作会话（含关联创作/视频/TTS，后端级联清理）；从会话列表移除
  Future<void> deleteSession(int id) async {
    await _api.deleteCreationSession(id);
    _sessions.removeWhere((s) => s.id == id);
    if (_currentSessionId == id) {
      _currentSessionId = null;
      _messages = [];
    }
    notifyListeners();
  }

  Future<Map<String, dynamic>> derive(int id) async {
    final r = await _api.deriveCreation(id);
    await loadSessions();
    return r;
  }

  /// 菜谱不满意迭代
  Future<Map<String, dynamic>> revise(int id, String feedback) async {
    final r = await _api.reviseCreation(id, feedback);
    if (r['action'] == 'revise') {
      // 刷新详情由页面拉取
    }
    return r;
  }

  Future<List<dynamic>> versions(int id) async =>
      _api.getCreationVersions(id);

  Future<Map<String, dynamic>> revert(int id, int version) async {
    final r = await _api.revertCreation(id, version);
    return r;
  }

  Future<Map<String, dynamic>> cook(int id) async => _api.cookCreation(id);

  /// 触发 AI 视频教程生成（P2/P3）
  Future<Map<String, dynamic>> generateVideo(int id, {bool confirm = false}) async =>
      _api.generateCreationVideo(id, confirm: confirm);

  /// 视频单段重创：只重做指定步骤片段，其余段复用后重拼
  Future<Map<String, dynamic>> regenerateVideoStep(int id, int stepIndex) async =>
      _api.regenerateCreationVideo(id, stepIndex);

  /// 今日视频生成配额用量
  Future<Map<String, dynamic>> videoUsage() async =>
      _api.getCreationVideoUsage();

  /// 视频生成任务进度
  Future<Map<String, dynamic>> videoTasks(int id) async =>
      _api.getCreationVideoTasks(id);

  /// AI 视频播放 URL
  Future<String> videoUrl(int id) => _api.creationVideoUrl(id);

  /// AI 整片本地缓存（本地优先）：命中本地缓存返回路径；否则下载到本地缓存（失败返回 null）
  Future<String?> cacheAiVideoLocal(int id) async {
    final url = await _api.creationVideoUrl(id);
    final store = LocalVideoStore.instance;
    await store.init();
    final cached = store.findAiMedia(id, 'video', null);
    if (cached != null) return cached.filePath;
    return store.cacheAiMedia(_api,
        creationId: id, type: 'video', url: url, ext: 'mp4');
  }

  /// 单步分段本地缓存（本地优先）
  Future<String?> cacheAiSegmentLocal(int id, int stepIndex) async {
    final url = await _api.creationSegmentUrl(id, stepIndex);
    final store = LocalVideoStore.instance;
    await store.init();
    final cached = store.findAiMedia(id, 'segment', stepIndex);
    if (cached != null) return cached.filePath;
    return store.cacheAiMedia(_api,
        creationId: id, type: 'segment', stepIndex: stepIndex, url: url, ext: 'mp4');
  }

  /// 某步骤视频分段预览 URL
  Future<String> segmentUrl(int id, int stepIndex) =>
      _api.creationSegmentUrl(id, stepIndex);

  // TTS 语音讲解（阿里云 qwen3-tts-instruct-flash）
  Future<Map<String, dynamic>> generateTts(int id) async =>
      _api.generateCreationTts(id);
  Future<Map<String, dynamic>> ttsStatus(int id) async =>
      _api.getCreationTts(id);
  Future<String> ttsUrl(int id, int stepIndex) =>
      _api.creationTtsUrl(id, stepIndex);

  /// 生成创作分享链接
  Future<Map<String, dynamic>> shareCreation(int id, {int? expireDays}) =>
      _api.shareCreation(id, expireDays: expireDays);

  /// 导出 AI 视频为普通视频（含讲解音轨）
  Future<Map<String, dynamic>> exportVideoToRegular(int id) async =>
      _api.exportCreationVideo(id);

  /// 发布菜谱到常规菜谱库
  Future<Map<String, dynamic>> publishRecipeToLibrary(int id) async =>
      _api.publishCreationToLibrary(id);

  Future<void> feedback(int id, String rating, String comment) async =>
      _api.submitCreationFeedback(id, rating, comment);

  // ---- 酱汁设计（P0，纯 LLM） ----
  Future<Map<String, dynamic>> designSauce(int id, {String? demand}) async =>
      _api.designSauce(id, demand: demand);
  Future<Map<String, dynamic>> reviseSauce(int id, String demand) async =>
      _api.reviseSauce(id, demand);
  Future<Map<String, dynamic>> confirmSauce(int id, Map<String, dynamic> sauce) async =>
      _api.confirmSauce(id, sauce);

  // ---- 酱汁效果图（P1，文生图 + 成本控制） ----
  Future<Map<String, dynamic>> generateEffectImage(int id,
          {bool confirm = false, String? style}) async =>
      _api.generateEffectImage(id, confirm: confirm, style: style);
  Future<Map<String, dynamic>> effectImageTasks(int id) async =>
      _api.getEffectImageTasks(id);
  Future<Map<String, dynamic>> effectImageUsage() async =>
      _api.getEffectImageUsage();
  Future<String> effectImageUrl(int id, {String? version}) =>
      _api.creationEffectImageUrl(id, version: version);
}
