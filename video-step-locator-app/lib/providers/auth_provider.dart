import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../models/user.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

class AuthProvider extends ChangeNotifier {
  final ApiService _api = ApiService();
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  User? _user;
  bool _loading = false;
  String? _error;

  User? get user => _user;
  bool get isLoggedIn => _user != null;
  bool get isLoading => _loading;
  String? get error => _error;
  String get username => _user?.nickname ?? _user?.username ?? '';

  Future<bool> login(String username, String password) async {
    _loading = true;
    _error = null;
    notifyListeners();

    try {
      final data = await _api.login(username, password);
      _user = User.fromJson(data['user']);
      _loading = false;
      notifyListeners();
      return true;
    } catch (e) {
      _error = friendlyError(e, fallback: '登录失败，请稍后重试');
      _loading = false;
      notifyListeners();
      return false;
    }
  }

  Future<bool> register(String username, String email, String password) async {
    _loading = true;
    _error = null;
    notifyListeners();

    try {
      final data = await _api.register(username, email, password);
      _user = User.fromJson(data['user']);
      _loading = false;
      notifyListeners();
      return true;
    } catch (e) {
      _error = friendlyError(e, fallback: '注册失败，请稍后重试');
      _loading = false;
      notifyListeners();
      return false;
    }
  }

  Future<void> logout() async {
    await _storage.delete(key: 'jwt_token');
    _user = null;
    notifyListeners();
  }

  /// 启动时恢复登录态：本地存有 token 则拉取当前用户（跨重启保持登录）
  Future<void> restoreSession() async {
    final token = await _storage.read(key: 'jwt_token');
    if (token == null || token.isEmpty) return;
    try {
      // 限时恢复：后端不可达时不长时间挂起（不阻塞首帧，仅影响登录态刷新时机）
      final data = await _api.getProfile().timeout(const Duration(seconds: 8));
      if (data != null) {
        _user = User.fromJson(data);
        notifyListeners();
      } else {
        // token 已失效：按游客处理，token 由拦截器在后续 401 时清理
        _user = null;
      }
    } catch (e) {
      // 网络异常等：按游客处理，保留 token
      _user = null;
    }
  }
}
