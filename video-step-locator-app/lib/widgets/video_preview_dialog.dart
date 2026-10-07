import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

/// 网络视频播放预览（后端代理流，支持 Range）
class VideoPreviewDialog extends StatefulWidget {
  final String url;
  const VideoPreviewDialog({super.key, required this.url});

  static Future<void> show(BuildContext context, String url) {
    return showDialog(
      context: context,
      builder: (_) => VideoPreviewDialog(url: url),
    );
  }

  @override
  State<VideoPreviewDialog> createState() => _VideoPreviewDialogState();
}

class _VideoPreviewDialogState extends State<VideoPreviewDialog> {
  late VideoPlayerController _controller;
  bool _ready = false;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    try {
      _controller = VideoPlayerController.networkUrl(Uri.parse(widget.url));
      _controller.initialize().then((_) {
        if (!mounted) return;
        setState(() => _ready = true);
        _controller.play();
      }).catchError((e) {
        debugPrint('video preview error: $e');
        if (!mounted) return;
        setState(() => _failed = true);
      });
    } catch (e) {
      debugPrint('video preview error: $e');
      _failed = true;
    }
  }

  @override
  void dispose() {
    // 先暂停再释放，避免播放中关闭对话框时音频残留（后台继续出声）
    try {
      _controller.pause();
    } catch (_) {}
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('视频预览'),
      content: SizedBox(
        width: 320,
        height: 220,
        child: _failed
            ? Center(
                child: Text('视频加载失败，请检查网络或稍后再试',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.grey, fontSize: 13)))
            : !_ready
                ? const Center(child: CircularProgressIndicator())
                : Center(
                    child: AspectRatio(
                      aspectRatio: _controller.value.aspectRatio,
                      child: GestureDetector(
                        onTap: () => _controller.value.isPlaying
                            ? _controller.pause()
                            : _controller.play(),
                        child: VideoPlayer(_controller),
                      ),
                    ),
                  ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}
