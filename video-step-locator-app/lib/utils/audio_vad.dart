import 'dart:math';
import 'dart:typed_data';

/// 简易能量法语音活动检测（VAD）
/// 输入：PCM s16le 16kHz 单声道字节流（record 包流式输出）。
/// 原理：按分块计算 RMS 能量，超过阈值连续 N 块判定为"语音"，
///       低于阈值连续 M 块判定为"静音"（带迟滞，避免抖动）。
/// 自适应基线：实时跟踪环境/视频底噪，有效阈值 = max(基线×倍率, 设定阈值)。
/// 这样"视频自身的声音"会融入基线、不会持续误判为说话，只有用户说话（能量明显高于底噪）才触发。
class AudioVad {
  /// 语音能量阈值下限（PCM s16 幅度平方根）。真机按环境调整。
  double speechThreshold;

  /// 判定为语音所需的最小连续帧数（帧 = 每次 feed 的分块）
  int speechFrames;

  /// 判定为静音所需的最小连续帧数
  int silenceFrames;

  /// 自适应阈值倍率：阈值 = max(基线×倍率, speechThreshold)
  /// 视频播放时基线≈视频音量，倍率过高会导致用户命令难以触发（能量须超视频×倍率）→ 门控不转发、识别不出。
  /// 2026-08-11 调低 2.0 → 1.5 以兼顾「不误判视频声」与「命令能触发」。
  double baselineMargin;

  bool _speaking = false;
  int _speechCount = 0;
  int _silenceCount = 0;
  double _baseline = 0;
  bool _baselineInit = false;

  AudioVad({
    this.speechThreshold = 900.0,
    this.speechFrames = 2,
    this.silenceFrames = 10,
    this.baselineMargin = 1.5,
  });

  bool get speaking => _speaking;

  /// 喂入一块 PCM s16le 数据，返回当前是否处于"语音"态
  bool feed(Uint8List data) {
    if (data.isEmpty || data.lengthInBytes < 2) return _speaking;
    final n = data.lengthInBytes ~/ 2;
    final samples = data.buffer.asInt16List(data.offsetInBytes ~/ 2, n);
    double sum = 0;
    for (final s in samples) {
      sum += s * s;
    }
    final rms = sqrt(sum / n);

    // 自适应噪声基线：快速下降、缓慢上升，跟踪视频/环境底噪。
    // 语音瞬间的能量高，慢速上升可避免语音污染基线；环境变安静时快速回落。
    if (!_baselineInit) {
      _baseline = rms;
      _baselineInit = true;
    } else if (rms > _baseline) {
      _baseline += (rms - _baseline) * 0.15; // 缓慢上升（语音不算入基线）
    } else {
      _baseline += (rms - _baseline) * 0.7; // 快速下降
    }
    final threshold = max(_baseline * baselineMargin, speechThreshold);

    if (rms >= threshold) {
      _speechCount++;
      _silenceCount = 0;
      if (!_speaking && _speechCount >= speechFrames) {
        _speaking = true;
      }
    } else {
      _silenceCount++;
      _speechCount = 0;
      if (_speaking && _silenceCount >= silenceFrames) {
        _speaking = false;
      }
    }
    return _speaking;
  }

  void reset() {
    _speaking = false;
    _speechCount = 0;
    _silenceCount = 0;
    _baseline = 0;
    _baselineInit = false;
  }
}
