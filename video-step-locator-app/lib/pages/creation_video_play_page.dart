import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:video_player/video_player.dart';
import '../providers/creation_provider.dart';
import '../utils/error_message.dart';

/// 酿味 AI 视频教程播放页（复用 video_player；支持步骤时间戳跳转）
class CreationVideoPlayPage extends StatefulWidget {
  final int creationId;
  final String title;
  final List<Map<String, dynamic>> steps; // [{index, start, end, summary}]
  const CreationVideoPlayPage(
      {super.key, required this.creationId, required this.title, this.steps = const []});

  @override
  State<CreationVideoPlayPage> createState() => _CreationVideoPlayPageState();
}

class _CreationVideoPlayPageState extends State<CreationVideoPlayPage> {
  VideoPlayerController? _controller;
  bool _initFailed = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    try {
      final url = await context.read<CreationProvider>().videoUrl(widget.creationId);
      if (!mounted) return;
      final c = VideoPlayerController.networkUrl(Uri.parse(url));
      _controller = c;
      await c.initialize();
      await c.setLooping(false);
      if (mounted) setState(() {});
      c.play();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _initFailed = true;
        _error = friendlyError(e, fallback: '视频加载失败');
      });
    }
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  String _fmt(double sec) {
    final m = sec ~/ 60;
    final s = sec % 60;
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(widget.title),
      ),
      body: _initFailed
          ? Center(
              child: Text(_error ?? '视频加载失败',
                  style: const TextStyle(color: Colors.white)))
          : _controller == null
              ? const Center(child: CircularProgressIndicator())
              : _buildPlayer(),
    );
  }

  Widget _buildPlayer() {
    final c = _controller!;
    return Column(
      children: [
        AspectRatio(
          aspectRatio: c.value.aspectRatio == 0 ? 16 / 9 : c.value.aspectRatio,
          child: VideoPlayer(c),
        ),
        _controls(c),
        if (widget.steps.isNotEmpty)
          Expanded(
            child: Container(
              color: const Color(0xFF1C1C1E),
              child: ListView(
                padding: const EdgeInsets.all(8),
                children: [
                  for (final s in widget.steps)
                    ListTile(
                      dense: true,
                      title: Text('${s['summary'] ?? ''}',
                          style: const TextStyle(color: Colors.white)),
                      subtitle: Text(
                        '${_fmt((s['start'] as num?)?.toDouble() ?? 0)} - '
                        '${_fmt((s['end'] as num?)?.toDouble() ?? 0)}',
                        style: const TextStyle(color: Colors.white54, fontSize: 12),
                      ),
                      onTap: () {
                        final t = (s['start'] as num?)?.toDouble() ?? 0;
                        c.seekTo(Duration(milliseconds: (t * 1000).round()));
                        c.play();
                      },
                    ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Widget _controls(VideoPlayerController c) {
    return ValueListenableBuilder<VideoPlayerValue>(
      valueListenable: c,
      builder: (ctx, v, _) {
        final dur = v.duration.inMilliseconds.toDouble();
        final pos = v.position.inMilliseconds.toDouble();
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Row(
            children: [
              IconButton(
                icon: Icon(v.isPlaying ? Icons.pause : Icons.play_arrow),
                color: Colors.white,
                onPressed: () => v.isPlaying ? c.pause() : c.play(),
              ),
              Expanded(
                child: Slider(
                  min: 0,
                  max: dur <= 0 ? 1 : dur,
                  value: pos.clamp(0, dur <= 0 ? 1 : dur),
                  onChanged: (d) => c.seekTo(Duration(milliseconds: d.round())),
                ),
              ),
              Text('${_fmt(pos / 1000)} / ${_fmt(dur / 1000)}',
                  style: const TextStyle(color: Colors.white54, fontSize: 12)),
            ],
          ),
        );
      },
    );
  }
}
