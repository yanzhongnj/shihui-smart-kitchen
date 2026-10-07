import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/creation_provider.dart';
import '../utils/error_message.dart';

/// 酿味图文跟做页：步骤卡 + 计时器（无视频兜底，TTS 已按 v0.4 不做）
class CreationCookPage extends StatefulWidget {
  final int creationId;
  const CreationCookPage({super.key, required this.creationId});

  @override
  State<CreationCookPage> createState() => _CreationCookPageState();
}

class _CreationCookPageState extends State<CreationCookPage> {
  Map<String, dynamic>? _data;
  List<dynamic> _steps = [];
  int _current = 0;
  bool _loading = true;
  String? _error;
  Timer? _timer;
  int _remainSec = 0;
  bool _timerActive = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final data =
          await context.read<CreationProvider>().cook(widget.creationId);
      if (!mounted) return;
      setState(() {
        _data = data;
        _steps = (data['steps'] as List? ?? []);
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载跟做失败');
        _loading = false;
      });
    }
  }

  Map<String, dynamic> _step(int i) =>
      _steps[i] as Map<String, dynamic>? ?? {};

  /// 当前步有 timer_min 则启动倒计时
  void _startTimer(int minutes) {
    _timer?.cancel();
    setState(() {
      _remainSec = minutes * 60;
      _timerActive = _remainSec > 0;
    });
    if (_remainSec <= 0) return;
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) return;
      setState(() {
        if (_remainSec > 0) {
          _remainSec--;
        } else {
          t.cancel();
          _timerActive = false;
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('⏱ 时间到，进入下一步吧！')));
        }
      });
    });
  }

  void _goTo(int i) {
    if (i < 0 || i >= _steps.length) return;
    setState(() => _current = i);
    _timer?.cancel();
    final t = _step(i)['timer_min'];
    if (t is int && t > 0) _startTimer(t);
  }

  String _fmt(int sec) {
    final m = sec ~/ 60;
    final s = sec % 60;
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('图文跟做')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Text(_error!))
              : _body(),
    );
  }

  Widget _body() {
    final step = _step(_current);
    final title = _data?['title'] as String? ?? '';
    final disclaimer = _data?['disclaimer'] as String? ?? '';
    final total = _steps.length;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Row(
            children: [
              Text(title,
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.bold)),
              const Spacer(),
              Text('第 ${_current + 1}/$total 步',
                  style: const TextStyle(color: Colors.black54)),
            ],
          ),
        ),
        if (_timerActive)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Card(
              color: const Color(0xFFFFF6E5),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    const Icon(Icons.timer_outlined, color: Color(0xFFFF8E53)),
                    const SizedBox(width: 8),
                    Text('步骤计时：${_fmt(_remainSec)}',
                        style: const TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Color(0xFFE67E22))),
                  ],
                ),
              ),
            ),
          ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${step['index'] ?? (_current + 1)}. ${step['summary'] ?? ''}',
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 12),
                    Expanded(
                      child: SingleChildScrollView(
                        child: Text(
                          '${step['detail'] ?? ''}',
                          style: const TextStyle(
                              fontSize: 15, height: 1.6, color: Colors.black87),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _current > 0 ? () => _goTo(_current - 1) : null,
                    child: const Text('上一步'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: _current < total - 1
                        ? () => _goTo(_current + 1)
                        : () {
                            ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('完成！记得打卡记录成果哦')));
                          },
                    child: Text(_current < total - 1 ? '下一步' : '完成'),
                  ),
                ),
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: Text(disclaimer,
              style: const TextStyle(fontSize: 11, color: Colors.black38)),
        ),
      ],
    );
  }
}
