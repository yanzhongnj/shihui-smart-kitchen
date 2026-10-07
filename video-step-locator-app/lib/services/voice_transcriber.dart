import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:record/record.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'api_service.dart';

/// 按住说话（Push-To-Talk）转文字服务。
///
/// 复用后端 /ws/transcribe/{sessionId} 纯转写语音链路（本地 FunASR / 阿里云 ASR）：
/// 与视频语音控制 /ws/voice 隔离——本通道只做文字转写，不匹配控制指令。
/// 后端 WS 每条 RESULT 均携带完整识别文本 text，取 text 即为转写结果。
///
/// 用法：
///   按住 → `await begin()`（开始录音+连接语音通道）
///   松开 → `final text = await end()`（停止采集、等尾句结果、返回识别文本）
class VoiceTranscriber {
  final ApiService _api = ApiService();
  final AudioRecorder _recorder = AudioRecorder();

  WebSocketChannel? _ws;
  StreamSubscription<Uint8List>? _audioSub;
  final List<String> _heard = [];
  String _sessionId = '';
  bool _recording = false;

  bool get isRecording => _recording;

  /// 开始录音（按住）：申请权限 → 建 WS → 启动 PCM 流并转发给后端。
  Future<void> begin() async {
    if (_recording) return;
    _recording = true;
    _heard.clear();

    if (!await _recorder.hasPermission()) {
      _recording = false;
      throw Exception('需要麦克风权限');
    }

    _sessionId = 'xw_${DateTime.now().millisecondsSinceEpoch}';
    final token = await _api.getToken();
    final wsUrl = ApiService.transcribeWsUrl(_sessionId, token ?? '');
    try {
      _ws = WebSocketChannel.connect(Uri.parse(wsUrl));
      _ws!.stream.listen(_onWsMessage, onError: (_) {}, onDone: () {});
    } catch (e) {
      _recording = false;
      throw Exception('语音通道连接失败');
    }

    // 等 WS 建立后再开始采集，避免开头 PCM 无接收端
    await Future.delayed(const Duration(milliseconds: 350));

    try {
      final stream = await _recorder.startStream(const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000,
        numChannels: 1,
        androidConfig: AndroidRecordConfig(
          audioSource: AndroidAudioSource.voiceRecognition,
        ),
      ));
      _audioSub = stream.listen((data) => _ws?.sink.add(data));
    } catch (e) {
      _cleanup();
      throw Exception('录音启动失败: $e');
    }
  }

  /// 结束录音（松开）：停止采集，但保留 WS 收尾句 RESULT，返回识别文本。
  Future<String> end() async {
    if (!_recording) return '';
    _recording = false;

    // 1) 停止发送 PCM（后端据此判断说话结束）
    await _audioSub?.cancel();
    _audioSub = null;
    try {
      await _recorder.stop();
    } catch (_) {}

    // 2) 通知后端「说话结束」，令其向 FunASR 发送 is_speaking=false，立即产出最终结果
    try {
      _ws?.sink.add('{"type":"stop"}');
    } catch (_) {}

    // 3) 等待首个结果（FunASR 在 is_speaking=false 后几百 ms 出 offline 最终结果），最多 4s
    final deadline = DateTime.now().add(const Duration(seconds: 4));
    while (DateTime.now().isBefore(deadline) && _heard.isEmpty) {
      await Future.delayed(const Duration(milliseconds: 150));
    }

    // 4) 收尾：等新结果停止（连续 250ms 无新句即结束），最多再等 2s
    final tailDeadline = DateTime.now().add(const Duration(seconds: 2));
    while (DateTime.now().isBefore(tailDeadline)) {
      final n = _heard.length;
      await Future.delayed(const Duration(milliseconds: 250));
      if (_heard.length == n) break;
    }

    _cleanup();
    return _heard.join('');
  }

  void _onWsMessage(dynamic data) {
    if (data is! String) return;
    try {
      final j = jsonDecode(data) as Map<String, dynamic>;
      if (j['type'] == 'RESULT') {
        final t = (j['text'] as String? ?? '').trim();
        if (t.isNotEmpty && !_heard.contains(t)) {
          _heard.add(t);
        }
      }
    } catch (_) {}
  }

  /// 清理：取消音频流、关闭 WS、复位状态。
  void dispose() {
    _cleanup();
  }

  void _cleanup() {
    try {
      _audioSub?.cancel();
    } catch (_) {}
    _audioSub = null;
    try {
      _ws?.sink.close();
    } catch (_) {}
    _ws = null;
  }
}
