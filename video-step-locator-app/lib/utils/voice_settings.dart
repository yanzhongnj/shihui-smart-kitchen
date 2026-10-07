import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 语音控制策略与参数设置（本地设备级）
/// 方案 A：语音开启时视频保持播放（默认），串音提示/忽略
/// 方案 B：语音触发自动闪避 + 客户端 VAD（说话时压低视频音量，固定时长后强制恢复）
class VoiceSettings {
  static const _storage = FlutterSecureStorage();

  static const _kStrategy = 'voice_strategy';
  static const _kThreshold = 'voice_vad_threshold';
  static const _kDuckHold = 'voice_duck_hold_ms';
  static const _kMinAudible = 'voice_min_audible_ms';
  static const _kDuckVolume = 'voice_duck_volume';

  static const String strategyA = 'A';
  static const String strategyB = 'B';

  // 默认参数
  static const double defaultThreshold = 900; // VAD 语音能量阈值
  static const int defaultDuckHoldMs = 1500; // 闪避保持时长（到点强制恢复）
  static const int defaultMinAudibleMs = 1200; // 两次闪避间最小可听间隔
  static const double defaultDuckVolume = 0.0; // 闪避时视频音量

  /// 读取策略（'A'/'B'），默认 A
  static Future<String> getStrategy() async {
    try {
      final v = await _storage.read(key: _kStrategy);
      if (v == strategyA || v == strategyB) return v!;
    } catch (_) {}
    return strategyA;
  }

  static Future<void> setStrategy(String s) async {
    try {
      await _storage.write(
          key: _kStrategy, value: s == strategyB ? strategyB : strategyA);
    } catch (_) {}
  }

  static Future<double> getVadThreshold() async =>
      double.tryParse(await _read(_kThreshold)) ?? defaultThreshold;
  static Future<int> getDuckHoldMs() async =>
      int.tryParse(await _read(_kDuckHold)) ?? defaultDuckHoldMs;
  static Future<int> getMinAudibleMs() async =>
      int.tryParse(await _read(_kMinAudible)) ?? defaultMinAudibleMs;
  static Future<double> getDuckVolume() async =>
      double.tryParse(await _read(_kDuckVolume)) ?? defaultDuckVolume;

  static Future<void> setParams({
    double? threshold,
    int? duckHoldMs,
    int? minAudibleMs,
    double? duckVolume,
  }) async {
    try {
      if (threshold != null) {
        await _storage.write(key: _kThreshold, value: threshold.toString());
      }
      if (duckHoldMs != null) {
        await _storage.write(key: _kDuckHold, value: duckHoldMs.toString());
      }
      if (minAudibleMs != null) {
        await _storage.write(key: _kMinAudible, value: minAudibleMs.toString());
      }
      if (duckVolume != null) {
        await _storage.write(key: _kDuckVolume, value: duckVolume.toString());
      }
    } catch (_) {}
  }

  static Future<String> _read(String key) async {
    try {
      return await _storage.read(key: key) ?? '';
    } catch (_) {
      return '';
    }
  }
}

