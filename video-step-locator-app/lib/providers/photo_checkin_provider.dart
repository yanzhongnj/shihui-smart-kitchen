import 'package:flutter/foundation.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// 拍照打卡（饮食记录 · 拍照识别）状态管理。
/// 流程：上传照片识别（多菜预览）→ 用户确认/改选 → 写 diet_records + EXP+5。
class PhotoCheckinProvider extends ChangeNotifier {
  final ApiService _api = ApiService();

  bool _uploading = false;
  bool _confirming = false;
  String? _error;
  Map<String, dynamic>? _preview;
  Map<String, dynamic>? _result;

  bool get uploading => _uploading;
  bool get confirming => _confirming;
  String? get error => _error;
  Map<String, dynamic>? get preview => _preview;
  Map<String, dynamic>? get result => _result;

  /// 上传照片识别（多菜预览）
  Future<Map<String, dynamic>?> checkinByPhoto({
    required String filePath,
    required String filename,
    String? mealType,
  }) async {
    _uploading = true;
    _error = null;
    _preview = null;
    _result = null;
    notifyListeners();
    try {
      final res = await _api.photoCheckin(
        filePath: filePath,
        filename: filename,
        mealType: mealType,
      );
      _preview = res;
      return res;
    } catch (e) {
      _error = friendlyError(e, fallback: '拍照识别失败，请稍后重试');
      return null;
    } finally {
      _uploading = false;
      notifyListeners();
    }
  }

  /// 确认 / 手动改选打卡
  Future<Map<String, dynamic>?> confirm(
      int photoId, List<Map<String, dynamic>> items,
      {bool revise = false}) async {
    _confirming = true;
    _error = null;
    notifyListeners();
    try {
      final res = revise
          ? await _api.revisePhotoCheckin(photoId, items)
          : await _api.confirmPhotoCheckin(photoId, items);
      _result = res;
      return res;
    } catch (e) {
      _error = friendlyError(
          e, fallback: revise ? '修改失败，请稍后重试' : '确认打卡失败，请稍后重试');
      return null;
    } finally {
      _confirming = false;
      notifyListeners();
    }
  }

  void clear() {
    _preview = null;
    _result = null;
    _error = null;
    notifyListeners();
  }
}
