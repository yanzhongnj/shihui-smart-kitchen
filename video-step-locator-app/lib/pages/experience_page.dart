import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import 'video_player_page.dart';

/// 游客体验视频列表（无需登录）
class ExperiencePage extends StatefulWidget {
  const ExperiencePage({super.key});

  @override
  State<ExperiencePage> createState() => _ExperiencePageState();
}

class _ExperiencePageState extends State<ExperiencePage> {
  final ApiService _api = ApiService();
  List<dynamic> _videos = [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final data = await _api.experienceVideos();
      if (!mounted) return;
      setState(() {
        _videos = data;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '尝味视频加载失败，请稍后重试');
        _loading = false;
      });
    }
  }

  String _duration(dynamic d) {
    if (d == null) return '';
    final sec = (d as num).toDouble();
    final m = sec ~/ 60;
    final s = (sec % 60).toInt();
    return '$m:${s.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('尝味'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
        ],
      ),
      body: RefreshIndicator(onRefresh: _load, child: _buildBody()),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return ListView(
        children: [
          const SizedBox(height: 120),
          Center(
            child: Text(
              _error ?? '加载失败',
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.red),
            ),
          ),
        ],
      );
    }
    if (_videos.isEmpty) {
      return ListView(
        children: const [
          SizedBox(height: 120),
          Center(child: Text('暂无可尝味的视频', style: TextStyle(color: Colors.grey))),
          SizedBox(height: 8),
          Center(
            child: Text(
              '管理员上传并处理视频后即可尝味',
              style: TextStyle(color: Colors.grey, fontSize: 12),
            ),
          ),
        ],
      );
    }
    return ListView.builder(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: _videos.length,
      itemBuilder: (context, i) {
        final v = _videos[i] as Map<String, dynamic>;
        final id = (v['id'] as num).toInt();
        final title = ((v['title'] ?? '尝味视频') as String).replaceAll(
            RegExp(r'\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$',
                caseSensitive: false),
            '');
        final dur = _duration(v['duration']);
        final steps = (v['stepCount'] ?? 0) as int;
        return Card(
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: ListTile(
            leading: Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(Icons.play_circle_fill, size: 32),
            ),
            title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text([
              if (dur.isNotEmpty) '时长 $dur',
              '步骤 $steps 个',
            ].join(' · ')),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => VideoPlayerPage(videoId: id, initialTitle: title),
              ),
            ),
          ),
        );
      },
    );
  }
}
