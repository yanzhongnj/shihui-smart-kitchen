import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:record/record.dart';
import 'package:video_player/video_player.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../models/video_step.dart';
import '../services/api_service.dart';
import '../services/local_video_store.dart';
import '../services/local_step_cache.dart';
import '../utils/audio_vad.dart';
import '../utils/error_message.dart';
import '../utils/voice_settings.dart';
import 'correction_report_page.dart';

/// 视频详情播放器（参照 Web 端功能）：
/// - 播放/暂停、进度拖拽、倍速（0.5~2x）
/// - 步骤列表：播放时高亮当前步骤、点击跳转、单步循环
/// - 语音/文本命令控制：播放、暂停、上一步、下一步、循环第N步、
///   停止循环、从头、快进/后退N秒、N倍速
class VideoPlayerPage extends StatefulWidget {
  final int videoId;
  final String? initialTitle;
  final String? localPath;

  /// AI 创作视频：非空时播放该 URL（复用体验播放器），并跳过 /api/videos 相关能力（收藏/成品纠偏/后端循环持久化）
  final String? aiVideoUrl;

  /// AI 创作视频自带步骤时间戳（[{index,start,end,summary}] → VideoStep）
  final List<VideoStep>? aiSteps;

  /// AI 创作视频语音讲解：已生成讲解的步骤序号集合（播放器按步骤切换时同步朗读）
  final Set<int>? aiNarrationSteps;

  const VideoPlayerPage({
    super.key,
    required this.videoId,
    this.initialTitle,
    this.localPath,
    this.aiVideoUrl,
    this.aiSteps,
    this.aiNarrationSteps,
  });

  @override
  State<VideoPlayerPage> createState() => _VideoPlayerPageState();
}

class _VideoPlayerPageState extends State<VideoPlayerPage>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  final ApiService _api = ApiService();

  /// AI 创作视频模式（自定义 URL/本地缓存 + 自带步骤）
  bool get _isAi =>
      widget.aiVideoUrl != null ||
      widget.aiSteps != null ||
      widget.aiNarrationSteps != null;

  VideoPlayerController? _controller;
  List<VideoStep> _steps = [];
  final Set<int> _loopingStepIds = {};
  int _activeStepIndex = -1;
  bool _videoReady = false;
  String? _error;
  double _speed = 1.0;
  double _position = 0;
  double _duration = 0;
  Timer? _ticker;
  bool _loopSeekGuard = false;
  String? _voiceFeedback;
  bool _voiceResultExpanded = false; // 语音识别结果面板展开/收起
  bool _stepsExpanded = true;
  bool _offlineCache = false; // 是否展示离线缓存的步骤
  // 真语音（麦克风 → WebSocket → FunASR）
  AudioRecorder? _recorder;
  StreamSubscription<Uint8List>? _audioSub;
  WebSocketChannel? _voiceWs;
  bool _listening = false;
  bool _reconnecting = false; // 断线重连中标志，防止 onError/onDone 触发重连风暴
  bool _wasPlayingBeforeVoice = false; // 开语音前视频是否在播放（关语音时恢复）
  // 语音控制策略（A=保持播放 / B=自动闪避+VAD）
  String _voiceStrategy = VoiceSettings.strategyA;
  AudioVad? _vad; // 方案 B 的 VAD
  bool _ducked = false; // 方案 B：视频是否已被闪避（压低音量）
  double _originalVolume = 1.0; // 方案 B：闪避前原音量
  DateTime _lastUnDuck = DateTime.now();
  Timer? _duckTimer; // 方案 B：闪避保持计时器（到点强制恢复音量）
  bool _prevSpeaking = false; // 方案 B：上一帧 VAD 是否处于说话态（onset 触发用）
  DateTime _vadStartedAt = DateTime.now(); // 方案 B：VAD 会话开始时间（预热期用）
  // ASR 省资源：仅在"说话"时把音频转发给后端，静音/仅视频声不转发（避免浪费 ASR）
  static const int _preRollBytes = 11200; // ~350ms 预滚缓冲（16kHz s16 单声道）
  final List<Uint8List> _vadBuffer = []; // 静音时缓存的预滚音频
  bool _vadForwarding = false; // 是否正在向后端转发
  // 可调参数（方案 B）
  double _vadThreshold = VoiceSettings.defaultThreshold;
  int _duckHoldMs = VoiceSettings.defaultDuckHoldMs;
  int _minAudibleMs = VoiceSettings.defaultMinAudibleMs;
  double _duckVolume = VoiceSettings.defaultDuckVolume;
  bool _isGuest = false;
  bool _isFav = false;
  bool _wasPlayingBeforeBackground = false;
  // TTS 语音讲解（AI 创作视频，阿里云 qwen3-tts-instruct-flash）
  late bool _narrationOn;
  AudioPlayer? _narrationPlayer;
  int _narrationActiveStep = -1;
  final Map<int, String> _ttsUrlCache = {}; // 优化：TTS URL 按步骤缓存，循环回跳不再重复请求
  int _narrationPlayingStep = -1; // 优化：讲解播放器当前实际在播的步骤（-1=无）

  // 麦克风状态动画（识别中脉冲）
  late final AnimationController _micCtrl;
  late final Animation<double> _micScale;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadVoiceStrategy();
    _micCtrl = AnimationController(
        vsync: this, duration: const Duration(milliseconds: 600));
    _micScale = Tween<double>(begin: 1.0, end: 1.3).animate(
        CurvedAnimation(parent: _micCtrl, curve: Curves.easeInOut));
    // 语音讲解：AI 创作视频且已有讲解时默认开启
    _narrationOn = _narrationAvailable;
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // 1) 最关键：先释放视频播放器，避免 ExoPlayer 音频残留导致后台继续出声。
    //    必须放在最前面，且用 try/catch 包裹——否则后面任何清理步骤抛异常
    //    都会中断 dispose，导致 controller.dispose() 不被调用、音频泄漏。
    final c = _controller;
    if (c != null) {
      try {
        c.pause();
      } catch (_) {}
      try {
        c.dispose();
      } catch (_) {}
      _controller = null;
    }
    // 2) 其它资源清理，各自 try/catch，互不干扰。
    _duckTimer?.cancel();
    _ticker?.cancel();
    try {
      _stopListening();
    } catch (_) {}
    try {
      _micCtrl.dispose();
    } catch (_) {}
    // 3) 释放语音讲解播放器
    try {
      _narrationPlayer?.stop();
    } catch (_) {}
    try {
      _narrationPlayer?.dispose();
    } catch (_) {}
    _narrationPlayer = null;
    super.dispose();
  }

  /// 切换到其他 App / 后台时停止播放（回到前台后若之前正在播放则恢复）
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden) {
      final c = _controller;
      _wasPlayingBeforeBackground = c?.value.isPlaying ?? false;
      try {
        c?.pause();
      } catch (_) {}
    } else if (state == AppLifecycleState.resumed) {
      if (_wasPlayingBeforeBackground) {
        _wasPlayingBeforeBackground = false;
        try {
          _controller?.play();
        } catch (_) {}
      }
    }
  }

  Future<void> _load() async {
    try {
      final token = await _api.getToken();
      _isGuest = token == null || token.isEmpty;
      if (!_isGuest && !_isAi) {
        // 加载收藏状态（AI 创作视频无 /api/videos 实体，跳过）
        _api.isFavorite(widget.videoId).then((v) {
          if (mounted) setState(() => _isFav = v);
        });
      }
      final String? aiUrl = widget.aiVideoUrl;
      final VideoPlayerController controller;
      if (widget.localPath != null) {
        controller = VideoPlayerController.file(File(widget.localPath!));
      } else if (aiUrl != null) {
        // AI 创作视频：本地优先——命中本地缓存直接用；否则先下载到本地缓存再播
        final String? cachedPath = await _resolveAiVideoLocal(aiUrl);
        controller = cachedPath != null
            ? VideoPlayerController.file(File(cachedPath))
            : VideoPlayerController.networkUrl(
                Uri.parse(aiUrl),
                // mixWithOthers: 视频播放不独占音频焦点，允许「录音 + 视频播放」并存。
                // 关键：HarmonyOS 检测到媒体独占音频焦点时会限制/暂停麦克风采集，
                // 此配置可让播放器与其他音频（麦克风录音）共存。
                videoPlayerOptions: VideoPlayerOptions(mixWithOthers: true),
              );
      } else {
        controller = VideoPlayerController.networkUrl(
          Uri.parse(_api.videoStreamUrl(widget.videoId, token: token)),
          // mixWithOthers: 同上（注释保留）
          videoPlayerOptions: VideoPlayerOptions(mixWithOthers: true),
        );
      }
      _controller = controller;
      await controller.initialize();
      if (!mounted) return;
      setState(() {
        _videoReady = true;
        _duration = controller.value.duration.inMilliseconds / 1000.0;
      });
      controller.play();
      // 加载步骤
      _loadSteps();
      _startTicker();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '视频加载失败，请检查网络或稍后重试');
      });
    }
  }

  /// AI 创作整片本地优先：命中本地缓存直接返回路径；否则下载到本地缓存（失败回退 null → 网络流）
  Future<String?> _resolveAiVideoLocal(String aiUrl) async {
    try {
      final store = LocalVideoStore.instance;
      await store.init();
      final cached = store.findAiMedia(widget.videoId, 'video', null);
      if (cached != null) return cached.filePath;
      return await store.cacheAiMedia(_api,
          creationId: widget.videoId, type: 'video', url: aiUrl, ext: 'mp4');
    } catch (_) {
      return null;
    }
  }

  /// 视频加载失败后重试：释放旧播放器，重新加载
  Future<void> _retryLoad() async {
    if (mounted) {
      setState(() {
        _error = null;
        _videoReady = false;
      });
    }
    try {
      await _controller?.dispose();
    } catch (_) {}
    _controller = null;
    await _load();
  }

  Future<void> _loadSteps() async {
    try {
      final List<VideoStep> steps;
      bool offline = false;
      if (_isAi) {
        steps = widget.aiSteps ?? [];
      } else {
        final cache = LocalStepCacheStore.instance;
        List<Map<String, dynamic>> raw;
        try {
          // 在线：以云端为准并写本地缓存
          raw = await cache.fetchAndCache(_api, widget.videoId);
        } catch (_) {
          // 离线：回退本地缓存（无缓存则抛回原异常）
          final cached = await cache.read(widget.videoId);
          if (cached == null || cached.steps.isEmpty) rethrow;
          raw = cached.steps;
          offline = true;
        }
        // 有离线待同步编辑时尝试同步（云端为准）
        unawaited(_syncPendingSteps());
        steps = raw.map((s) => VideoStep.fromJson(s)).toList();
      }
      // 从后端读取循环状态（loop_count>0 已映射为 isLooping）
      final looping = steps.where((s) => s.isLooping).map((s) => s.id).toSet();
      if (!mounted) return;
      setState(() {
        _steps = steps;
        _loopingStepIds.addAll(looping);
        _offlineCache = offline;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '步骤加载失败，请稍后重试');
      });
    }
  }

  /// 同步离线待同步的步骤编辑到云端（云端为准）
  Future<void> _syncPendingSteps() async {
    if (_isAi || widget.videoId <= 0) return;
    try {
      final r = await LocalStepCacheStore.instance
          .trySyncPending(_api, widget.videoId);
      final pushed = r['pushed'] ?? 0;
      final conflict = r['conflict'] ?? 0;
      if (pushed > 0 || conflict > 0) {
        if (!mounted) return;
        _toast(conflict > 0
            ? '有 $conflict 条离线修改与云端冲突，已以云端为准'
            : '已同步 $pushed 条离线修改到云端');
        _loadSteps(); // 刷新展示
      }
    } catch (_) {}
  }

  void _startTicker() {
    _ticker?.cancel();
    _ticker = Timer.periodic(const Duration(milliseconds: 400), (_) {
      final c = _controller;
      if (c == null || !_videoReady) return;
      final pos = c.value.position.inMilliseconds / 1000.0;
      final dur = c.value.duration.inMilliseconds / 1000.0;

      // 进度同步
      if ((pos - _position).abs() > 0.1 || (dur - _duration).abs() > 0.1) {
        setState(() {
          _position = pos;
          _duration = dur;
        });
      }

      // 当前步骤高亮
      int found = -1;
      for (int i = 0; i < _steps.length; i++) {
        final s = _steps[i];
        if (pos >= s.startTime && pos <= s.endTime) {
          found = i;
          break;
        }
      }
      // 尾部无步骤覆盖（最后步骤之后到视频结束）：保持最后一步高亮，避免高亮丢失/闪烁
      if (found == -1 && _steps.isNotEmpty && pos > _steps.last.endTime) {
        found = _steps.length - 1;
      }
      if (found != _activeStepIndex) {
        setState(() => _activeStepIndex = found);
      }

      // 单步循环：仅在该步骤"结束时间后的小窗口内"触发（约 1.5s），
      // 防止处于循环态的步骤从任意更靠后的位置（用户跳到后面/视频已在后面）劫持回开头，
      // 否则会出现"进度条在后面、步骤却回到第一步循环"的不同步。
      if (_loopingStepIds.isNotEmpty) {
        for (final s in _steps) {
          if (_loopingStepIds.contains(s.id) &&
              pos >= s.endTime &&
              pos <= s.endTime + 1.5) {
            if (!_loopSeekGuard) {
              _loopSeekGuard = true;
              c.seekTo(Duration(milliseconds: (s.startTime * 1000).round()));
              c.play();
              // 单步循环回到步骤起点时，重播该步讲解
              if (_narrationOn && _isAi) {
                unawaited(_playStepNarration(s.stepIndex));
              }
              Timer(const Duration(milliseconds: 700), () {
                _loopSeekGuard = false;
              });
            }
            break;
          }
        }
      }

      // TTS 语音讲解：随视频步骤切换同步朗读（AI 创作视频）
      if (_narrationOn && _isAi) {
        final playing = c.value.isPlaying;
        if (!playing) {
          if (_narrationPlayer?.state == PlayerState.playing) {
            unawaited(_pauseNarration());
          }
        } else if (found != _narrationActiveStep) {
          _narrationActiveStep = found;
          final has = found >= 0 &&
              (widget.aiNarrationSteps?.contains(_steps[found].stepIndex) ??
                  false);
          if (has) {
            unawaited(_playStepNarration(_steps[found].stepIndex));
          } else {
            unawaited(_stopNarration());
          }
        }
      }
    });
  }

  /// AI 创作视频是否已有语音讲解
  bool get _narrationAvailable =>
      _isAi &&
      widget.aiNarrationSteps != null &&
      widget.aiNarrationSteps!.isNotEmpty;

  /// 惰性创建讲解播放器（不与视频争抢音频焦点，允许混音）
  Future<void> _ensureNarrationPlayer() async {
    if (_narrationPlayer != null) return;
    final p = AudioPlayer();
    try {
      await p.setAudioContext(AudioContext(
        android: AudioContextAndroid(
          audioFocus: AndroidAudioFocus.none,
          contentType: AndroidContentType.music,
          usageType: AndroidUsageType.media,
          isSpeakerphoneOn: true,
        ),
      ));
    } catch (_) {}
    // 讲解自然播完时复位"正在播的步骤"，让循环回跳可再次触发重播
    p.onPlayerComplete.listen((_) {
      if (_narrationPlayingStep != -1) {
        _narrationPlayingStep = -1;
      }
    });
    _narrationPlayer = p;
  }

  /// 播放指定步骤的讲解音频（失败静默，不打断视频）
  Future<void> _playStepNarration(int stepIndex) async {
    try {
      // 优化：讲解播放器当前正在播的就是该步 → 跳过重播，避免打断
      if (_narrationPlayingStep == stepIndex &&
          _narrationPlayer?.state == PlayerState.playing) {
        return;
      }
      await _ensureNarrationPlayer();
      final p = _narrationPlayer!;
      // 优化：TTS URL 按步骤缓存，循环回跳不再重复请求
      final url = _ttsUrlCache[stepIndex] ??=
          await _api.creationTtsUrl(widget.videoId, stepIndex);
      // 讲解本地优先：命中本地缓存直接播；否则下载到本地缓存再播（失败回退网络流）
      String? localPath;
      try {
        final store = LocalVideoStore.instance;
        await store.init();
        final cached = store.findAiMedia(widget.videoId, 'tts', stepIndex);
        localPath = cached != null
            ? cached.filePath
            : await store.cacheAiMedia(_api,
                creationId: widget.videoId,
                type: 'tts',
                stepIndex: stepIndex,
                url: url,
                ext: 'wav');
      } catch (_) {}
      await p.stop();
      if (localPath != null) {
        await p.play(DeviceFileSource(localPath));
      } else {
        await p.play(UrlSource(url));
      }
      _narrationPlayingStep = stepIndex;
    } catch (e) {
      // 讲解加载失败静默
    }
  }

  Future<void> _pauseNarration() async {
    try {
      await _narrationPlayer?.pause();
    } catch (_) {}
  }

  Future<void> _stopNarration() async {
    try {
      await _narrationPlayer?.stop();
    } catch (_) {}
    _narrationPlayingStep = -1;
  }

  void _jumpTo(int index) {
    final c = _controller;
    if (c == null || index < 0 || index >= _steps.length) return;
    final s = _steps[index];
    // 记住跳转前的播放状态
    final wasPlaying = c.value.isPlaying;
    // 手动跳转：复位循环守卫，避免被在途的自动循环 seek 干扰
    _loopSeekGuard = false;
    // 跳转到步骤开始
    c.seekTo(Duration(milliseconds: (s.startTime * 1000).round()));
    // 保持之前的播放状态：跳转前在播放则继续播放（seek 后播放器可能暂停，需恢复）；
    // 之前暂停则保持暂停。mixWithOthers 已支持「录音 + 播放」共存，不再因语音模式强制暂停。
    if (wasPlaying) {
      c.play();
    }
    if (mounted) setState(() => _activeStepIndex = index);
  }

  void _seekClamped(double seconds) {
    final c = _controller;
    if (c == null) return;
    final dur = c.value.duration.inMilliseconds / 1000.0;
    final t = seconds.clamp(0.0, dur > 0 ? dur : seconds);
    c.seekTo(Duration(milliseconds: (t * 1000).round()));
  }

  void _setSpeed(double speed) {
    final c = _controller;
    if (c == null) return;
    setState(() => _speed = speed);
    c.setPlaybackSpeed(speed);
  }

  Future<void> _toggleLoop(VideoStep step) async {
    if (_isAi) {
      // AI 创作视频：循环为本地态（无 /api/videos 步骤实体，不写后端）,
      // 注意：只有一个循环步骤，点击其他步骤时自动取消之前的循环
      setState(() {
        if (_loopingStepIds.contains(step.id)) {
          //_loopingStepIds.remove(step.id);
          _loopingStepIds.clear();
        } else {
          _loopingStepIds.clear();
          _loopingStepIds.add(step.id);
        }
      });
      return;
    }
    try {
      final data = await _api.toggleLoop(step.id);
      final looping = data['isLooping'] == true;
      if (!mounted) return;
      setState(() {
        if (looping) {
          _loopingStepIds.clear();
          _loopingStepIds.add(step.id);
        } else {
          // 注意：只有一个循环步骤，点击其他步骤时自动取消之前的循环
          //_loopingStepIds.remove(step.id);
          _loopingStepIds.clear();
        }
      });
    } catch (e) {
      _toast('循环设置失败: $e');
    }
  }

  // ============ 语音/文本命令 ============

  /// 开始/停止真语音（麦克风 → WebSocket → FunASR 识别 → 执行）
  Future<void> _toggleVoice() async {
    if (_listening) {
      _stopListening();
      return;
    }
    try {
      await _startVoiceSession();
    } catch (e) {
      _toast('启动语音失败: $e');
    }
  }

  /// 建立一次完整语音会话（麦克风 + WebSocket + 订阅）。
  /// 首次开启与断线自动重连都复用此方法。
  Future<void> _startVoiceSession() async {
    final recorder = AudioRecorder();
    // 记录录音前播放状态：HarmonyOS 录音与视频播放互斥，录音期间需暂停视频
    _wasPlayingBeforeVoice = _controller?.value.isPlaying ?? false;
    try {
      if (!await recorder.hasPermission()) {
        _toast('需要麦克风权限才能使用语音');
        return;
      }
      final token = await _api.getToken();
      final sessionId = 'voice_${DateTime.now().millisecondsSinceEpoch}';
      final wsUrl = ApiService.voiceWsUrl(sessionId, token ?? '');
      final ws = WebSocketChannel.connect(Uri.parse(wsUrl));
      _voiceWs = ws;

      ws.stream.listen((msg) {
        try {
          final text = msg is String
              ? msg
              : utf8.decode(msg is List<int> ? msg : const []);
          final data = jsonDecode(text) as Map<String, dynamic>;
          if (data['type'] == 'RESULT' && data['action'] != null) {
            final action = data['action'] as String;
            if (action == 'UNKNOWN') {
              // 未匹配命令：回显识别到的文字，让用户看到识别在工作
              if (mounted) {
                setState(() => _voiceFeedback =
                    '🎤 听到：${data['text'] ?? ''}（未识别为命令）');
              }
            } else {
              _executeCommand(data);
              if (mounted) {
                setState(() => _voiceFeedback = '✅ 已执行: $action');
              }
            }
          }
        } catch (_) {}
      }, onError: (e) {
        // 连接异常：用户仍处于语音模式时自动重连（无需重开开关）
        _restartVoiceSession();
      }, onDone: () {
        // 对端/网络关闭连接：自动重连恢复持续监听
        _restartVoiceSession();
      }, cancelOnError: false);

      // 采集 16kHz 单声道 PCM s16le 并转发给后端 FunASR
      // 方案1（系统 AEC）：用 VOICE_COMMUNICATION 通信链路，由系统音频 HAL 内置
      // 回声消除移除扬声器外放的回声；配合 modeInCommunication + echoCancel/降噪/AGC。
      // （探针工程真机验证：本方案可有效抑制视频旁白回声，命令识别正常，外放+录音可共存）
      final stream = await recorder.startStream(const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000,
        numChannels: 1,
        echoCancel: true,
        noiseSuppress: true,
        autoGain: true,
        androidConfig: AndroidRecordConfig(
          audioSource: AndroidAudioSource.voiceCommunication,
          speakerphone: true,
          audioManagerMode: AudioManagerMode.modeInCommunication,
        ),
      ));

      // 语音控制策略：A=保持播放；B=自动闪避+客户端VAD
      _voiceStrategy = await VoiceSettings.getStrategy();
      _vad = _voiceStrategy == VoiceSettings.strategyB
          ? AudioVad(speechThreshold: _vadThreshold)
          : null;
      _ducked = false;
      _prevSpeaking = false;
      _vadBuffer.clear();
      _vadForwarding = false;
      _vadStartedAt = DateTime.now();
      _originalVolume = _controller?.value.volume ?? 1.0;
      _lastUnDuck = DateTime.now();
      // AI 模式讲解开启时静音视频自带音频（避免与讲解混音导致讲解变小）
      _syncVideoVolume();

      _audioSub = stream.listen((data) {
        try {
          if (_voiceStrategy == VoiceSettings.strategyB && _vad != null) {
            // 预热期（~1.2s）：让 VAD 基线先适应视频底噪，同时全量转发，
            // 保证刚开启语音时说的第一条命令不因基线未稳定而丢失。
            final inWarmup = DateTime.now().difference(_vadStartedAt) <
                const Duration(milliseconds: 1200);
            if (inWarmup) {
              _vad!.feed(data);
              ws.sink.add(data);
            } else if (_ducked) {
              // 闪避期间视频被静音，麦克风输入骤降：若继续喂 VAD，自适应基线会
              // 快速塌陷，恢复音量后把视频声误判为"说话"→ 门控一直转发视频声、
              // 后续命令不触发闪避也识别不出（首句正常、后续失效的根因）。
              // 此时用户在说话：保持转发，但不更新 VAD，保护基线。
              _forwardVoiceGated(ws, data, true);
            } else {
              // 方案 B：VAD 检测 → 说话时自动压低视频音量（画面继续），静音后恢复
              final speaking = _vad!.feed(data);
              _applyDucking(speaking);
              // ASR 省资源：仅"说话"时转发音频，静音/仅视频声不转发
              _forwardVoiceGated(ws, data, speaking);
            }
          } else {
            ws.sink.add(data);
          }
        } catch (_) {}
      });
      _recorder = recorder;
      if (mounted) {
        setState(() {
          _listening = true;
          _voiceFeedback = '🎤 语音识别中，说命令即可…（点击开关结束）';
        });
      }
      _micCtrl.repeat(reverse: true);

      // 语音开启时保持视频播放（方案 A/B 均不暂停，仅 B 在说话时闪避音量）
      debugPrint('VOICE: strategy=$_voiceStrategy');
    } catch (e) {
      // 启动失败：清理可能已建立的部分资源后向上抛出
      _audioSub?.cancel();
      _audioSub = null;
      try {
        _recorder?.stop();
      } catch (_) {}
      _recorder = null;
      _voiceWs = null;
      rethrow;
    }
  }

  /// WS 断线后自动重建语音会话（持续监听的关键）。
  /// 仅当用户仍处于语音模式且页面存活时执行；失败则结束语音模式避免死循环。
  Future<void> _restartVoiceSession() async {
    if (!_listening || !mounted || _reconnecting) return;
    _reconnecting = true;
    try {
      // 清理旧资源（保持 _listening=true，不退出语音模式）
      _audioSub?.cancel();
      _audioSub = null;
      try {
        _recorder?.stop();
      } catch (_) {}
      _recorder = null;
      try {
        _voiceWs?.sink.close();
      } catch (_) {}
      _voiceWs = null;

      await Future.delayed(const Duration(milliseconds: 800));
      if (!_listening || !mounted) return;
      await _startVoiceSession();
    } catch (e) {
      _stopListening();
      if (mounted) {
        setState(() => _voiceFeedback = '❌ 语音服务断开，请重试');
      }
    } finally {
      _reconnecting = false;
    }
  }

  /// 启动时读取已保存的语音控制策略与参数
  Future<void> _loadVoiceStrategy() async {
    final s = await VoiceSettings.getStrategy();
    final t = await VoiceSettings.getVadThreshold();
    final d = await VoiceSettings.getDuckHoldMs();
    final m = await VoiceSettings.getMinAudibleMs();
    final v = await VoiceSettings.getDuckVolume();
    if (!mounted) return;
    setState(() {
      _voiceStrategy = s;
      _vadThreshold = t;
      _duckHoldMs = d;
      _minAudibleMs = m;
      _duckVolume = v;
    });
  }

  /// 方案 B：仅在"静音→说话"的瞬间触发一次固定时长闪避，
  /// 说完（VAD 判定静音）后才允许再次闪避，避免视频自身声音反复触发闪避导致卡顿。
  void _applyDucking(bool speaking) {
    final c = _controller;
    if (c == null) return;
    // AI 模式讲解开启时视频已静音（讲解为主音），无需闪避
    if (_isAi && _narrationOn) return;
    if (!speaking) {
      // 静音态：解除武装，为下次说话重新准备
      _prevSpeaking = false;
      return;
    }
    // 说话态：仅处理"刚进入说话"的那一次，持续说话不重复触发
    if (_prevSpeaking) return;
    _prevSpeaking = true;
    if (_ducked) return;
    final now = DateTime.now();
    // 距上次恢复已过最小可听间隔才再次闪避，避免反复抖动
    if (now.difference(_lastUnDuck) <
        Duration(milliseconds: _minAudibleMs)) {
      return;
    }
    _ducked = true;
    try {
      c.setVolume(_duckVolume);
    } catch (_) {}
    // 到点强制恢复（无论 VAD 是否仍判"有语音"，保证说完后视频恢复出声）
    _duckTimer?.cancel();
    _duckTimer =
        Timer(Duration(milliseconds: _duckHoldMs), _forceUnduck);
  }

  /// 方案 B：闪避计时到点，强制恢复原音量（讲解开启时保持静音视频）
  void _forceUnduck() {
    if (!_ducked) return;
    _ducked = false;
    _lastUnDuck = DateTime.now();
    _syncVideoVolume();
  }

  /// 依据当前状态同步视频音量：AI 模式讲解开启 → 静音视频自带音频（讲解更清晰）；否则恢复原音量
  void _syncVideoVolume() {
    final c = _controller;
    if (c == null) return;
    try {
      c.setVolume((_isAi && _narrationOn) ? 0 : _originalVolume);
    } catch (_) {}
  }

  /// ASR 省资源门控：仅在 VAD 判定"说话"时把音频转发给后端 FunASR。
  /// 静音时只缓存最近 ~350ms（预滚），说话瞬间先补发预滚再实时转发，
  /// 保证命令开头不被掐掉；不说时（仅视频声）不发送，ASR 空闲不浪费。
  void _forwardVoiceGated(dynamic ws, Uint8List data, bool speaking) {
    if (speaking) {
      if (!_vadForwarding) {
        // 刚进入说话：先补发预滚缓冲（含本块），再进入实时转发
        _vadForwarding = true;
        _vadBuffer.add(data);
        for (final d in _vadBuffer) {
          try {
            ws.sink.add(d);
          } catch (_) {}
        }
        _vadBuffer.clear();
      } else {
        // 持续说话：实时转发本块（不入缓冲）
        try {
          ws.sink.add(data);
        } catch (_) {}
      }
    } else {
      // 静音：停止转发，只缓存最近 ~350ms 供下次说话预滚
      _vadForwarding = false;
      _vadBuffer.add(data);
      var size = 0;
      for (final d in _vadBuffer) {
        size += d.lengthInBytes;
      }
      while (size > _preRollBytes && _vadBuffer.isNotEmpty) {
        final removed = _vadBuffer.removeAt(0);
        size -= removed.lengthInBytes;
      }
    }
  }

  void _stopListening() {
    _micCtrl.stop();
    _micCtrl.value = 0;
    _audioSub?.cancel();
    _audioSub = null;
    try {
      _recorder?.stop();
    } catch (_) {}
    _recorder = null;
    try {
      _voiceWs?.sink.close();
    } catch (_) {}
    _voiceWs = null;
    _vad?.reset();
    _vad = null;
    _prevSpeaking = false;
    _vadBuffer.clear();
    _vadForwarding = false;
    _duckTimer?.cancel();
    // 方案 B：若仍处于闪避状态则恢复音量
    if (_ducked) {
      _ducked = false;
      try {
        _controller?.setVolume(_originalVolume);
      } catch (_) {}
    }
    // 录音结束：开语音前视频在播放则恢复播放
    final shouldResume = _wasPlayingBeforeVoice;
    _wasPlayingBeforeVoice = false;
    if (_controller != null && shouldResume) {
      try {
        _controller!.play();
      } catch (_) {}
    }
    if (mounted) {
      setState(() {
        _listening = false;
        if (_voiceFeedback?.contains('🎤') == true) {
          _voiceFeedback = '✅ 语音识别已停止';
        }
      });
    }
  }

  void _executeCommand(Map<String, dynamic> cmd) {
    final action = cmd['action'] as String?;
    final params = (cmd['params'] as Map<String, dynamic>?) ?? {};
    final c = _controller;
    if (c == null) return;

    switch (action) {
      case 'NEXT_STEP':
        if (_steps.isEmpty) break;
        final next =
            _activeStepIndex >= 0 ? _activeStepIndex + 1 : 0;
        if (next < _steps.length) _jumpTo(next);
        break;
      case 'PREV_STEP':
        if (_steps.isEmpty) break;
        final prev = _activeStepIndex > 0 ? _activeStepIndex - 1 : 0;
        _jumpTo(prev);
        break;
      case 'LOOP_STEP':
        final idx = ((params['stepIndex'] ?? 1) as num).toInt() - 1;
        if (idx >= 0 && idx < _steps.length) {
          _toggleLoop(_steps[idx]);
          _jumpTo(idx);
        }
        break;
      case 'LOOP_CURRENT':
        if (_activeStepIndex >= 0 && _activeStepIndex < _steps.length) {
          final s = _steps[_activeStepIndex];
          if (!_loopingStepIds.contains(s.id)) _toggleLoop(s);
        }
        break;
      case 'STOP_LOOP':
        for (final s in List.of(_steps)) {
          if (_loopingStepIds.contains(s.id)) _toggleLoop(s);
        }
        break;
      case 'PLAY':
        c.play();
        break;
      case 'PAUSE':
        c.pause();
        break;
      case 'RESTART':
        c.seekTo(Duration.zero);
        c.play();
        break;
      case 'FORWARD':
        final sec = ((params['seconds'] ?? 10) as num).toDouble();
        _seekClamped(c.value.position.inMilliseconds / 1000.0 + sec);
        break;
      case 'BACKWARD':
        final sec = ((params['seconds'] ?? 10) as num).toDouble();
        _seekClamped(c.value.position.inMilliseconds / 1000.0 - sec);
        break;
      case 'SPEED':
        final speed = ((params['speed'] ?? 1.0) as num).toDouble();
        _setSpeed(speed);
        break;
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  String _fmt(double seconds) {
    if (seconds.isNaN || seconds.isInfinite) return '--:--';
    final m = seconds ~/ 60;
    final s = (seconds % 60).toInt();
    return '$m:${s.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final title = _cleanTitle(widget.initialTitle);
    return Scaffold(
      appBar: AppBar(
        title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          if (_isAi && _narrationAvailable)
            IconButton(
              icon: Icon(
                _narrationOn
                    ? Icons.record_voice_over
                    : Icons.record_voice_over_outlined,
                color: _narrationOn ? Colors.orange : null,
              ),
              tooltip: _narrationOn ? '语音讲解：开（点按关闭）' : '语音讲解：关（点按开启）',
              onPressed: () {
                setState(() {
                  _narrationOn = !_narrationOn;
                  if (_narrationOn) {
                    // 强制下一 tick 重播当前步骤讲解
                    _narrationActiveStep = -1;
                  } else {
                    _stopNarration();
                  }
                });
                // 讲解开关同步视频音量（开=静音视频自带音频，关=恢复）
                _syncVideoVolume();
              },
            ),
          if (!_isAi) ...[
            IconButton(
              icon: const Icon(Icons.compare),
              tooltip: '成品纠偏',
              onPressed: _openCorrection,
            ),
            IconButton(
              icon: Icon(
                _isFav ? Icons.star : Icons.star_border,
                color: _isFav ? Colors.amber : null,
              ),
              tooltip: _isFav ? '取消收藏' : '收藏',
              onPressed: _toggleFavorite,
            ),
          ],
        ],
      ),
      body: _buildBody(),
    );
  }

  /// 成品纠偏：做完菜拍一张成品照 → AI 与目标成品（视频封面/手动参照）对比 → 纠偏报告
  Future<void> _openCorrection() async {
    if (_isGuest) {
      _toast('登录后可成品纠偏');
      return;
    }
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => CorrectionReportPage(
        videoId: widget.videoId,
        title: _cleanTitle(widget.initialTitle),
      ),
    ));
  }

  /// 收藏 / 取消收藏（游客提示登录）
  Future<void> _toggleFavorite() async {
    if (_isGuest) {
      _toast('登录后可收藏');
      return;
    }
    try {
      if (_isFav) {
        await _api.removeFavorite(widget.videoId);
        if (mounted) setState(() => _isFav = false);
        _toast('已取消收藏');
      } else {
        await _api.addFavorite(widget.videoId);
        if (mounted) setState(() => _isFav = true);
        _toast('已收藏，可在“我的收藏”查看');
      }
    } catch (e) {
      _toast('操作失败: $e');
    }
  }

  /// 去掉视频文件扩展名，用做显示标题
  String _cleanTitle(String? t) {
    if (t == null || t.isEmpty) return '视频详情';
    return t.replaceAll(
        RegExp(r'\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$',
            caseSensitive: false),
        '');
  }

  Widget _buildBody() {
    if (_error != null && !_videoReady) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.videocam_off_outlined,
                  size: 52, color: Colors.orange),
              const SizedBox(height: 14),
              Text(_error!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 14, color: Colors.black87, height: 1.5)),
              const SizedBox(height: 18),
              FilledButton.tonalIcon(
                onPressed: _retryLoad,
                icon: const Icon(Icons.refresh, size: 18),
                label: const Text('重试'),
              ),
            ],
          ),
        ),
      );
    }
    // 顶部固定：视频 + 控制条 + 语音控制（不随步骤滚动上移，用户始终可见）；
    // 下方 Expanded 为步骤列表独立滚动 → 最后一步完整可达、不被遮挡。
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
          child: Column(
            children: [
              _buildPlayer(),
              const SizedBox(height: 8),
              _buildControls(),
              // 语音/循环需登录：游客模式隐藏语音控制
              if (!_isGuest) ...[const SizedBox(height: 12), _buildVoiceBar()],
            ],
          ),
        ),
        const SizedBox(height: 12),
        Expanded(child: _buildStepsArea()),
      ],
    );
  }

  Widget _buildPlayer() {
    final c = _controller;
    if (c == null || !_videoReady) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: AspectRatio(
          aspectRatio: 16 / 9,
          child: Container(
            color: Colors.black,
            alignment: Alignment.center,
            child: _error != null
                ? const Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.error_outline,
                          color: Colors.white70, size: 32),
                      SizedBox(height: 8),
                      Text('视频加载失败',
                          style: TextStyle(color: Colors.white70)),
                    ],
                  )
                : const CircularProgressIndicator(color: Colors.white),
          ),
        ),
      );
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: AspectRatio(
        aspectRatio: c.value.aspectRatio > 0 ? c.value.aspectRatio : 16 / 9,
        child: GestureDetector(
          onTap: () => c.value.isPlaying ? c.pause() : c.play(),
          child: Stack(
            fit: StackFit.expand,
            children: [
              VideoPlayer(c),
              // 步骤时间线标记
              if (_steps.isNotEmpty && _duration > 0)
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 8,
                  child: LayoutBuilder(
                    builder: (ctx, constraints) {
                      final w = constraints.maxWidth;
                      return SizedBox(
                        height: 6,
                        child: Stack(
                          children: [
                            // 全宽底轨：表示整段视频时间轴，保证暗红进度条铺满视频宽度
                            Positioned(
                              left: 0,
                              right: 0,
                              top: 0,
                              height: 6,
                              child: Container(
                                decoration: BoxDecoration(
                                  color: const Color(0x52E74C3C),
                                  borderRadius: BorderRadius.circular(3),
                                ),
                              ),
                            ),
                            ..._steps.map((s) {
                              final left = (s.startTime / _duration * w)
                                  .clamp(0.0, w)
                                  .toDouble();
                              final avail = (w - left).clamp(0.0, w);
                              final width = ((s.endTime - s.startTime) /
                                      _duration *
                                      w)
                                  .clamp(0.5, avail)
                                  .toDouble();
                              return Positioned(
                                left: left,
                                top: 0,
                                width: width,
                                height: 6,
                                child: GestureDetector(
                                  onTap: () => _jumpTo(_steps.indexOf(s)),
                                  child: Container(
                                    decoration: BoxDecoration(
                                      color:
                                          _steps.indexOf(s) == _activeStepIndex
                                              ? const Color(0xFFE74C3C)
                                              : const Color(0x99E74C3C),
                                      borderRadius: BorderRadius.circular(3),
                                    ),
                                  ),
                                ),
                              );
                            }),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              if (!c.value.isPlaying && c.value.position == Duration.zero)
                const Center(
                  child: Icon(Icons.play_circle_fill,
                      size: 64, color: Colors.white70),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildControls() {
    final c = _controller;
    final playing = c?.value.isPlaying ?? false;
    return Column(
      children: [
        Row(
          children: [
            IconButton(
              icon: Icon(playing ? Icons.pause : Icons.play_arrow),
              onPressed: () {
                if (playing) {
                  c?.pause();
                } else {
                  c?.play();
                }
              },
            ),
            IconButton(
              icon: const Icon(Icons.replay),
              tooltip: '从头播放',
              onPressed: () {
                c?.seekTo(Duration.zero);
                c?.play();
              },
            ),
            Expanded(
              child: Slider(
                value: _position.clamp(0.0, _duration > 0 ? _duration : 1.0),
                max: _duration > 0 ? _duration : 1.0,
                onChanged: (v) {
                  c?.seekTo(Duration(milliseconds: (v * 1000).round()));
                  setState(() => _position = v);
                },
              ),
            ),
            Text('${_fmt(_position)} / ${_fmt(_duration)}',
                style: const TextStyle(fontSize: 12)),
            const SizedBox(width: 4),
            PopupMenuButton<double>(
              initialValue: _speed,
              tooltip: '倍速',
              onSelected: _setSpeed,
              itemBuilder: (_) => [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]
                  .map((s) => PopupMenuItem(
                        value: s,
                        child: Text(s == 1.0 ? '${s}x 正常' : '${s}x'),
                      ))
                  .toList(),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                child: Text('${_speed}x',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
              ),
            ),
          ],
        ),
      ],
    );
  }

  /// 语音控制帮助：显示支持的命令提示
  void _showVoiceHelp() {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('🎤 语音控制命令'),
        content: const Text(
          '支持的命令：\n\n'
          '· 播放 / 暂停\n'
          '· 下一步 / 上一步\n'
          '· 循环第N步 / 循环这段\n'
          '· 停止循环\n'
          '· 从头 / 重新开始\n'
          '· 快进N秒 / 后退N秒\n'
          '· N倍速\n\n'
          '点击红色麦克风按钮开启持续语音控制，直接说出命令即可。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('知道了'),
          ),
        ],
      ),
    );
  }

  /// 参数帮助弹窗：点击参数行的"?"图标弹出说明
  void _showParamHelp(BuildContext ctx, String title, String desc) {
    showDialog<void>(
      context: ctx,
      builder: (c) => AlertDialog(
        title: Text(title, style: const TextStyle(fontSize: 16)),
        content: Text(desc, style: const TextStyle(fontSize: 13, height: 1.6)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(c).pop(),
            child: const Text('知道了'),
          ),
        ],
      ),
    );
  }

  /// A/B 模式卡片（不带"方案"字样）
  Widget _modeCard(
    BuildContext ctx,
    String label,
    String title,
    String subtitle,
    bool selected,
    VoidCallback onTap,
  ) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: selected
              ? const Color(0x144CAF50)
              : Colors.grey.shade100,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected
                ? Colors.green
                : Colors.grey.shade400,
            width: selected ? 2 : 1,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(label,
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.bold)),
                const Spacer(),
                if (selected)
                  const Icon(Icons.check_circle,
                      color: Colors.green, size: 18),
              ],
            ),
            const SizedBox(height: 4),
            Text(title,
                style: const TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(subtitle,
                style: const TextStyle(
                    fontSize: 11, color: Colors.grey)),
          ],
        ),
      ),
    );
  }

  /// 参数滑块行：标签 + 问号帮助 + 滑块
  Widget _paramRow(
    BuildContext ctx, {
    required String label,
    required String valueText,
    required String helpTitle,
    required String helpDesc,
    required double value,
    required double min,
    required double max,
    required int? divisions,
    required ValueChanged<double> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text('$label：$valueText',
                  style: const TextStyle(fontSize: 13)),
            ),
            InkWell(
              onTap: () => _showParamHelp(ctx, helpTitle, helpDesc),
              borderRadius: BorderRadius.circular(12),
              child: const Padding(
                padding: EdgeInsets.all(2),
                child: Icon(Icons.help_outline,
                    size: 17, color: Colors.grey),
              ),
            ),
          ],
        ),
        Slider(
          value: value,
          min: min,
          max: max,
          divisions: divisions,
          label: valueText,
          onChanged: onChanged,
        ),
      ],
    );
  }

  /// 语音控制设置：模式 A/B（无"方案"字样）+ 仅 B 可调参数 + 每参数问号帮助
  Future<void> _showVoiceStrategyDialog() async {
    var strategy = _voiceStrategy;
    var vadThreshold = _vadThreshold;
    var duckHoldMs = _duckHoldMs;
    var minAudibleMs = _minAudibleMs;
    var duckVolume = _duckVolume;

    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) {
          final bEnabled = strategy == VoiceSettings.strategyB;
          return AlertDialog(
            title: const Text('语音控制设置'),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: _modeCard(
                          ctx,
                          'A',
                          '保持播放',
                          '语音时视频继续出声',
                          strategy == VoiceSettings.strategyA,
                          () => setDialogState(() =>
                              strategy = VoiceSettings.strategyA),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: _modeCard(
                          ctx,
                          'B',
                          '自动闪避',
                          '说话时自动压低音量',
                          strategy == VoiceSettings.strategyB,
                          () => setDialogState(() =>
                              strategy = VoiceSettings.strategyB),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Divider(),
                  Opacity(
                    opacity: bEnabled ? 1.0 : 0.4,
                    child: IgnorePointer(
                      ignoring: !bEnabled,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              const Text('自动闪避参数',
                                  style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                      fontSize: 13)),
                              if (!bEnabled)
                                const Padding(
                                  padding: EdgeInsets.only(left: 6),
                                  child: Text('（仅 B 可调）',
                                      style: TextStyle(
                                          fontSize: 11,
                                          color: Colors.grey)),
                                ),
                            ],
                          ),
                          _paramRow(
                            ctx,
                            label: 'VAD 灵敏度阈值',
                            valueText: '${vadThreshold.round()}',
                            helpTitle: 'VAD 灵敏度阈值',
                            helpDesc: '检测"说话"的能量门槛。数值越小越灵敏，'
                                '但视频里厨师说话/音乐声更容易被误判为说话；'
                                '数值越大越迟钝，可能漏掉较小的命令声。\n\n'
                                '范围 300–3000，建议 600–1500。',
                            value: vadThreshold,
                            min: 300,
                            max: 3000,
                            divisions: 27,
                            onChanged: (v) =>
                                setDialogState(() => vadThreshold = v),
                          ),
                          _paramRow(
                            ctx,
                            label: '闪避保持时长',
                            valueText: '$duckHoldMs ms',
                            helpTitle: '闪避保持时长',
                            helpDesc: '检测到说话后，视频音量被压低并保持的时长。'
                                '到点后无论是否仍在说话都会强制恢复，'
                                '避免说完后视频一直无声。\n\n'
                                '范围 500–5000ms，建议 1200–2500。',
                            value: duckHoldMs.toDouble(),
                            min: 500,
                            max: 5000,
                            divisions: 45,
                            onChanged: (v) => setDialogState(
                                () => duckHoldMs = v.round()),
                          ),
                          _paramRow(
                            ctx,
                            label: '最小可听间隔',
                            valueText: '$minAudibleMs ms',
                            helpTitle: '最小可听间隔',
                            helpDesc: '两次闪避之间的最短间隔，'
                                '避免连续说话时视频音量频繁忽大忽小。\n\n'
                                '范围 500–5000ms，建议 1000–2000。',
                            value: minAudibleMs.toDouble(),
                            min: 500,
                            max: 5000,
                            divisions: 45,
                            onChanged: (v) => setDialogState(
                                () => minAudibleMs = v.round()),
                          ),
                          _paramRow(
                            ctx,
                            label: '闪避音量',
                            valueText: '${(duckVolume * 100).round()}%',
                            helpTitle: '闪避音量',
                            helpDesc: '闪避期间视频保持的音量。'
                                '0% 完全静音，50% 半音量。'
                                '若想边听视频边说话，可调高一些。',
                            value: duckVolume,
                            min: 0,
                            max: 1,
                            divisions: 10,
                            onChanged: (v) =>
                                setDialogState(() => duckVolume = v),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('取消'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(ctx).pop(true),
                child: const Text('保存'),
              ),
            ],
          );
        },
      ),
    );

    if (saved != true) return;
    // 保存策略与参数
    await VoiceSettings.setStrategy(strategy);
    await VoiceSettings.setParams(
      threshold: vadThreshold,
      duckHoldMs: duckHoldMs,
      minAudibleMs: minAudibleMs,
      duckVolume: duckVolume,
    );
    if (mounted) {
      setState(() {
        _voiceStrategy = strategy;
        _vadThreshold = vadThreshold;
        _duckHoldMs = duckHoldMs;
        _minAudibleMs = minAudibleMs;
        _duckVolume = duckVolume;
      });
      _toast('语音控制设置已保存');
    }
  }

  Widget _buildVoiceBar() {
    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 2, horizontal: 8),
            child: Row(
              children: [
                // 语音控制开关（紧凑：比问号/设置略大即可）
                GestureDetector(
                  onTap: _toggleVoice,
                  behavior: HitTestBehavior.opaque,
                  child: ScaleTransition(
                    scale: _micScale,
                    child: Container(
                      width: 34,
                      height: 34,
                      margin: const EdgeInsets.symmetric(horizontal: 8),
                      decoration: BoxDecoration(
                        color: _listening ? Colors.red : Colors.red.shade50,
                        shape: BoxShape.circle,
                        border: Border.all(color: Colors.red, width: 1.2),
                      ),
                      child: Icon(
                        _listening ? Icons.mic : Icons.mic_none,
                        color: Colors.red,
                        size: 18,
                      ),
                    ),
                  ),
                ),
                // 帮助：显示语音控制命令提示
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.help_outline, size: 20),
                  color: Colors.grey,
                  tooltip: '语音控制帮助',
                  onPressed: _showVoiceHelp,
                ),
                // 设置：语音控制策略 A/B
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.settings_outlined, size: 20),
                  color: Colors.grey,
                  tooltip: '语音控制策略',
                  onPressed: _showVoiceStrategyDialog,
                ),
                const Spacer(),
                // 语音识别结果展开/收起（靠右）
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: Icon(
                    _voiceResultExpanded
                        ? Icons.expand_less
                        : Icons.expand_more,
                    size: 20,
                  ),
                  color: Colors.grey,
                  tooltip: '语音识别结果',
                  onPressed: () => setState(
                      () => _voiceResultExpanded = !_voiceResultExpanded),
                ),
              ],
            ),
          ),
          // 语音识别及执行结果（默认收起，点右侧箭头展开）
          if (_voiceResultExpanded)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  _voiceFeedback ?? '暂无语音识别结果，点击上方红色麦克风开始说话',
                  style: TextStyle(
                    fontSize: 13,
                    color: _voiceFeedback?.startsWith('✅') == true
                        ? Colors.green
                        : (_voiceFeedback != null
                            ? Colors.red.shade700
                            : Colors.grey),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// 步骤列表：独立滚动区（顶部视频/控制/语音固定不动），
  /// 卡片全部可滚到，最后一步完整可见、不被遮挡。
  Widget _buildStepsArea() {
    if (_steps.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(
            child: Text('暂无步骤（视频可能尚未处理）',
                style: TextStyle(color: Colors.grey))),
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 24),
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                _offlineCache
                    ? '操作步骤（${_steps.length}）· 离线缓存'
                    : '操作步骤（${_steps.length}）',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            TextButton.icon(
              onPressed: () =>
                  setState(() => _stepsExpanded = !_stepsExpanded),
              icon: Icon(_stepsExpanded ? Icons.expand_less : Icons.expand_more),
              label: Text(_stepsExpanded ? '收起' : '展开'),
            ),
          ],
        ),
        if (_stepsExpanded)
          for (var i = 0; i < _steps.length; i++) _stepCard(i, _steps[i]),
      ],
    );
  }

  /// 编辑步骤文字（云端视频；已发布/共享的内容后端会拒绝并提示先取消发布/共享）
  Future<void> _editStep(VideoStep s) async {
    final summaryCtrl = TextEditingController(text: s.summary);
    final origCtrl = TextEditingController(text: s.originalText ?? '');
    final result = await showDialog<(String, String)>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('编辑步骤文字'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: summaryCtrl,
                maxLines: 3,
                decoration: const InputDecoration(labelText: '步骤摘要'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: origCtrl,
                maxLines: 4,
                decoration:
                    const InputDecoration(labelText: '解说原文（可选）'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(context).pop(
                  (summaryCtrl.text.trim(), origCtrl.text.trim())),
              child: const Text('保存')),
        ],
      ),
    );
    if (result == null || !mounted) return;
    final (summary, original) = result;
    if (summary.isEmpty) {
      _toast('步骤摘要不能为空');
      return;
    }
    try {
      // 双写：在线写穿云端+本地；离线本地生效+待同步（联网后自动同步，云端为准）
      final result = await LocalStepCacheStore.instance.editStep(
        _api,
        widget.videoId,
        s.id,
        summary: summary,
        originalText: original,
      );
      if (!mounted) return;
      if (result == 'offlineSaved') {
        _toast('已离线保存，联网后自动同步到云端');
      } else {
        _toast('步骤已保存，需重新审核后方可发布/共享');
      }
      _loadSteps();
    } catch (e) {
      if (!mounted) return;
      _toast(friendlyError(e, fallback: '保存失败'));
    }
  }

  Widget _stepCard(int i, VideoStep s) {
    final active = i == _activeStepIndex;
    final looping = _loopingStepIds.contains(s.id);
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      color: active
          ? Theme.of(context).colorScheme.primaryContainer
          : null,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: active
            ? const BorderSide(color: Color(0xFFE74C3C), width: 1.5)
            : BorderSide.none,
      ),
      child: ListTile(
        onTap: () => _jumpTo(i),
        leading: CircleAvatar(
          radius: 16,
          child: Text('${s.stepIndex}',
              style: const TextStyle(fontSize: 14)),
        ),
        title: Text(
          s.summary.isEmpty ? '（无摘要）' : s.summary,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          '${_fmt(s.startTime)} - ${_fmt(s.endTime)}',
          style: TextStyle(
            color:
                looping ? const Color(0xFFE74C3C) : Colors.grey.shade600,
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: Icon(
                looping ? Icons.repeat_on : Icons.repeat,
                color: looping ? const Color(0xFFE74C3C) : Colors.grey,
              ),
              tooltip: looping ? '停止循环' : '循环播放',
              onPressed: _isGuest
                  ? () => _toast('登录后可使用循环播放')
                  : () => _toggleLoop(s),
            ),
            if (widget.videoId > 0 && !_isGuest)
              IconButton(
                icon: const Icon(Icons.edit_outlined, size: 20),
                tooltip: '编辑步骤文字',
                onPressed: () => _editStep(s),
              ),
          ],
        ),
      ),
    );
  }
}
