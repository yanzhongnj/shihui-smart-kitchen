import 'package:flutter/material.dart';
import '../services/local_video_store.dart';
import 'video_player_page.dart';

/// 下载记录：所有下载到手机本地的视频记录。
/// 新流程：链接下载的视频保存到本地（游客与注册用户均可本地播放），
/// 需上云时在「我的视频-本地」中上传。
class DownloadRecordsPage extends StatefulWidget {
  const DownloadRecordsPage({super.key});

  @override
  State<DownloadRecordsPage> createState() => _DownloadRecordsPageState();
}

class _DownloadRecordsPageState extends State<DownloadRecordsPage> {
  List<LocalVideo> _items = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final store = LocalVideoStore.instance;
    await store.init();
    if (!mounted) return;
    setState(() {
      _items = store.videos;
      _loading = false;
    });
  }

  void _play(LocalVideo v) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => VideoPlayerPage(
          videoId: 0,
          initialTitle: v.displayTitle,
          localPath: v.filePath,
        ),
      ),
    );
  }

  Future<void> _delete(LocalVideo v) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除下载记录'),
        content: Text('确定删除「${v.displayTitle}」吗？将同时删除手机上的文件。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('删除', style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await LocalVideoStore.instance.remove(v.id);
    await _load();
  }

  /// 简短时间（yyyy-MM-dd HH:mm），避免长字符串换行
  String _shortTime(String? s) {
    if (s == null || s.isEmpty) return '';
    final v = s.replaceFirst('T', ' ').replaceFirst('Z', '');
    return v.length > 16 ? v.substring(0, 16) : v;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('下载记录')),
      body: RefreshIndicator(
        onRefresh: _load,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _items.isEmpty
                ? ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: const [
                      SizedBox(height: 140),
                      Icon(Icons.download_done_outlined,
                          size: 64, color: Colors.grey),
                      SizedBox(height: 12),
                      Center(
                        child: Text('暂无下载记录',
                            style:
                                TextStyle(color: Colors.grey, fontSize: 15)),
                      ),
                      SizedBox(height: 4),
                      Center(
                        child: Text('在「链接下载」中下载视频后，会自动保存到本地并记录在这里',
                            style:
                                TextStyle(fontSize: 12, color: Colors.grey)),
                      ),
                    ],
                  )
                : ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.all(12),
                    itemCount: _items.length,
                    itemBuilder: (context, i) => _recordCard(_items[i]),
                  ),
      ),
    );
  }

  Widget _recordCard(LocalVideo v) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        leading: Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: Colors.green.withValues(alpha: 0.12),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.download_done, color: Colors.green, size: 26),
        ),
        title: Text(v.displayTitle,
            maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('已下载到本地 · ${v.fileSizeText}'),
            if (_shortTime(v.createdAt).isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(
                  _shortTime(v.createdAt),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
                ),
              ),
          ],
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.play_circle_outline, color: Colors.green),
              tooltip: '播放',
              onPressed: () => _play(v),
            ),
            PopupMenuButton<String>(
              tooltip: '管理',
              onSelected: (a) {
                if (a == 'delete') _delete(v);
              },
              itemBuilder: (_) => const [
                PopupMenuItem(
                    value: 'delete',
                    child: Text('删除', style: TextStyle(color: Colors.red))),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
