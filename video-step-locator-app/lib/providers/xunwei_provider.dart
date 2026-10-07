import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../models/xunwei_message.dart';
import '../models/xunwei_session.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// 寻味（AI 对话式做菜视频搜索）状态管理
class XunweiProvider extends ChangeNotifier {
  final ApiService _api = ApiService();
  static const _storage = FlutterSecureStorage();
  static const _voiceConfirmKey = 'xunwei_voice_confirm';

  /// 语音输入后是否需确认（APP 系统配置，默认开启）
  Future<bool> isVoiceConfirmEnabled() async {
    final v = await _storage.read(key: _voiceConfirmKey);
    return v != '0';
  }

  Future<void> setVoiceConfirmEnabled(bool enable) async {
    await _storage.write(key: _voiceConfirmKey, value: enable ? '1' : '0');
  }

  List<XunweiSession> _sessions = [];
  int? _currentSessionId;
  List<XunweiMessage> _messages = [];
  bool _sending = false;
  String? _error;

  List<XunweiSession> get sessions => _sessions;
  int? get currentSessionId => _currentSessionId;
  List<XunweiMessage> get messages => _messages;
  bool get isSending => _sending;
  String? get error => _error;

  XunweiSession? get currentSession {
    for (final s in _sessions) {
      if (s.id == _currentSessionId) return s;
    }
    return null;
  }

  Future<void> loadSessions() async {
    try {
      final data = await _api.listXunweiSessions();
      _sessions = data
          .map((j) => XunweiSession.fromJson(j as Map<String, dynamic>))
          .toList();
    } catch (e) {
      _error = friendlyError(e, fallback: '加载会话失败，请稍后重试');
    }
    notifyListeners();
  }

  /// 创建新会话并打开
  Future<int> createSession() async {
    final id = await _api.createXunweiSession();
    await loadSessions();
    _currentSessionId = id;
    _messages = [];
    notifyListeners();
    return id;
  }

  Future<void> openSession(int id) async {
    _currentSessionId = id;
    await loadMessages(id);
    notifyListeners();
  }

  Future<void> loadMessages(int id) async {
    try {
      final data = await _api.getXunweiMessages(id);
      _messages = data
          .map((j) => XunweiMessage.fromJson(j as Map<String, dynamic>))
          .toList();
    } catch (e) {
      _error = friendlyError(e, fallback: '加载消息失败，请稍后重试');
    }
    notifyListeners();
  }

  /// 发送消息：先追加用户气泡，再追加 AI 回复
  Future<void> sendMessage(String text) async {
    final sid = _currentSessionId;
    if (sid == null || text.trim().isEmpty) return;
    _messages.add(XunweiMessage(id: 0, role: 'user', content: text.trim()));
    _sending = true;
    _error = null;
    notifyListeners();
    try {
      final res = await _api.sendXunweiMessage(sid, text.trim());
      final reply = res['reply'] as String? ?? '';
      final videos = (res['videos'] as List? ?? [])
          .whereType<Map<String, dynamic>>()
          .map(XunweiVideo.fromJson)
          .toList();
      _messages.add(XunweiMessage(
          id: 0, role: 'assistant', content: reply, videos: videos));
      // 会话摘要可能变化，刷新列表标题/预览
      await loadSessions();
    } catch (e) {
      _error = friendlyError(e, fallback: '发送失败，请稍后重试');
      _messages.add(XunweiMessage(
          id: 0,
          role: 'assistant',
          content: '抱歉，${friendlyError(e, fallback: '发送失败，请检查网络或稍后重试')}'));
    }
    _sending = false;
    notifyListeners();
  }

  Future<void> renameSession(int id, String title) async {
    await _api.renameXunweiSession(id, title);
    await loadSessions();
  }

  /// 拍照识菜闭环：上传图片 → 后端识别食材 + 寻味推荐；追加用户/助手气泡
  Future<Map<String, dynamic>?> recommendByImage(
      String filePath, String filename) async {
    final sid = _currentSessionId;
    _sending = true;
    _error = null;
    notifyListeners();
    try {
      final res = await _api.xunweiImageRecommend(filePath, filename,
          sessionId: sid);
      final ingredients = (res['ingredients'] as List? ?? [])
          .whereType<String>()
          .toList();
      _messages.add(XunweiMessage(
          id: 0,
          role: 'user',
          content: ingredients.isEmpty
              ? '📷 拍照识菜'
              : '📷 拍照识菜：${ingredients.join('、')}'));
      final reply = res['reply'] as String? ?? '';
      final videos = (res['videos'] as List? ?? [])
          .whereType<Map<String, dynamic>>()
          .map(XunweiVideo.fromJson)
          .toList();
      _messages.add(XunweiMessage(
          id: 0, role: 'assistant', content: reply, videos: videos));
      // 会话摘要可能变化，刷新列表
      await loadSessions();
      return res;
    } catch (e) {
      _error = friendlyError(e, fallback: '拍照识菜失败，请稍后重试');
      _messages.add(XunweiMessage(
          id: 0,
          role: 'assistant',
          content: '抱歉，${friendlyError(e, fallback: '拍照识菜失败，请检查网络或稍后重试')}'));
      return null;
    } finally {
      _sending = false;
      notifyListeners();
    }
  }

  /// 确保存在一个寻味会话（供纠偏结果存入会话）；无当前会话则新建，返回 sessionId
  Future<int?> ensureSession() async {
    if (_currentSessionId != null) return _currentSessionId;
    try {
      return await createSession();
    } catch (_) {
      return null;
    }
  }

  /// 成品拍照纠偏：上传实拍成品照 + 参照图（关联视频封面 / 手动上传）→ 返回纠偏报告；失败返回 null 并置 _error
  /// [sessionId] 传寻味会话 ID 时纠偏报告以纯文本写入该会话（A 方案）
  Future<Map<String, dynamic>?> correctByImage({
    required String filePath,
    required String filename,
    String? referencePath,
    String? referenceFilename,
    int? videoId,
    String? title,
    int? sessionId,
  }) async {
    _error = null;
    notifyListeners();
    try {
      return await _api.xunweiImageCorrect(
        filePath,
        filename,
        referencePath: referencePath,
        referenceFilename: referenceFilename,
        videoId: videoId,
        title: title,
        sessionId: sessionId,
      );
    } catch (e) {
      _error = friendlyError(e, fallback: '成品纠偏失败，请稍后重试');
      notifyListeners();
      return null;
    }
  }

  Future<void> deleteSession(int id) async {
    await _api.deleteXunweiSession(id);
    if (_currentSessionId == id) {
      _currentSessionId = null;
      _messages = [];
    }
    await loadSessions();
    notifyListeners();
  }

  Future<List<dynamic>> getMemory() async => _api.getXunweiMemory();

  Future<void> clearMemory() async {
    await _api.clearXunweiMemory();
  }

  Future<void> deleteMemory(int id) async => _api.deleteXunweiMemory(id);

  Future<void> updateMemory(int id, String value) async =>
      _api.updateXunweiMemory(id, value);

  /// 偏好自进化开关
  Future<bool> isAutoEvolveEnabled() async {
    final s = await _api.getXunweiMemorySettings();
    return s['autoEvolve'] != false;
  }

  Future<void> setAutoEvolveEnabled(bool enable) async =>
      _api.updateXunweiMemorySettings(enable);
}
