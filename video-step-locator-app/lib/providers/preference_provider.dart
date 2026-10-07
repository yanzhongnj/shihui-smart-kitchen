import 'package:flutter/foundation.dart';
import '../models/user_preference.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

class PreferenceProvider extends ChangeNotifier {
  final ApiService _api = ApiService();

  UserPreference? _pref;
  bool _loading = false;
  String? _error;

  UserPreference? get pref => _pref;
  bool get isLoading => _loading;
  String? get error => _error;

  Future<void> fetch() async {
    _loading = true;
    _error = null;
    notifyListeners();
    try {
      final data = await _api.getPreferences();
      _pref = UserPreference.fromJson(data);
    } catch (e) {
      _error = friendlyError(e, fallback: '加载口味设置失败，请稍后重试');
      debugPrint('fetch preferences failed: $e');
    }
    _loading = false;
    notifyListeners();
  }

  Future<bool> save(UserPreference p) async {
    _loading = true;
    _error = null;
    notifyListeners();
    try {
      final data = await _api.updatePreferences(p.toJson());
      _pref = UserPreference.fromJson(data);
      _loading = false;
      notifyListeners();
      return true;
    } catch (e) {
      _error = friendlyError(e, fallback: '保存口味设置失败，请稍后重试');
      _loading = false;
      notifyListeners();
      return false;
    }
  }
}
