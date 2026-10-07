import 'dart:io';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:provider/provider.dart';
import '../models/video.dart';
import '../providers/auth_provider.dart';
import '../providers/download_provider.dart';
import '../providers/video_provider.dart';
import '../services/api_service.dart';
import '../services/local_step_cache.dart';
import '../services/local_video_store.dart';
import '../utils/error_message.dart';
import 'video_player_page.dart';
import 'video_upload_page.dart';

class VideoListPage extends StatefulWidget {
  const VideoListPage({super.key});

  @override
  State<VideoListPage> createState() => _VideoListPageState();
}

class _VideoListPageState extends State<VideoListPage> {
  int _tab = 0; // 0 本地, 1 云端
  List<LocalVideo> _local = [];
  bool _localLoading = true;

  @override
  void initState() {
    super.initState();
    _loadLocal();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (context.read<AuthProvider>().isLoggedIn) {
        context.read<VideoProvider>().fetchVideos();
      }
    });
  }

  Future<void> _loadLocal() async {
    final store = LocalVideoStore.instance;
    await store.init();
    if (!mounted) return;
    setState(() {
      _local = store.videos;
      _localLoading = false;
    });
  }

  Future<void> _refresh() async {
    await _loadLocal();
    if (context.read<AuthProvider>().isLoggedIn) {
      await context.read<VideoProvider>().fetchVideos();
    }
  }

  /// 从手机存储导入/移入视频到「我的视频」本地视频：
  /// 导入=复制到 local_videos/（保留原文件）；移动=移入并删除手机原文件。
  Future<void> _importLocalVideo() async {
    final picked = await FilePicker.platform.pickFiles(type: FileType.video);
    if (picked == null || picked.files.isEmpty) return;
    final file = picked.files.single;
    final path = file.path;
    if (path == null) {
      _toast('无法读取所选文件');
      return;
    }
    final name = file.name.isEmpty ? '视频.mp4' : file.name;
    final title = name.replaceAll(
        RegExp(r'\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$',
            caseSensitive: false),
        '');
    if (!mounted) return;
    // 选择处理方式
    final move = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('选择处理方式'),
        content: Text('「$title」\n\n导入：复制到「我的视频」，保留手机原文件\n移动：移入「我的视频」，删除手机原文件'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('取消')),
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('导入（保留原文件）')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('移动（删除原文件）')),
        ],
      ),
    );
    if (move == null || !mounted) return;
    try {
      _toast(move ? '正在移动…' : '正在导入…');
      final store = LocalVideoStore.instance;
      final dir = await store.ensureVideosDir();
      final safeName = name.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
      final targetPath =
          '${dir.path}${Platform.pathSeparator}${DateTime.now().millisecondsSinceEpoch}_$safeName';
      if (move) {
        // 移动：优先 rename（同盘快），失败回退 复制+删除
        try {
          await File(path).rename(targetPath);
        } catch (_) {
          await File(path).copy(targetPath);
          try {
            await File(path).delete();
          } catch (_) {}
        }
      } else {
        await File(path).copy(targetPath);
      }
      await store.add(title: title, filePath: targetPath);
      await _loadLocal();
      if (!mounted) return;
      _toast(move ? '已移入本地视频' : '已导入本地视频，可本地播放或上传到云端');
    } catch (e) {
      if (!mounted) return;
      _toast('${move ? '移动' : '导入'}失败：${friendlyError(e, fallback: '无法处理该文件')}');
    }
  }

  @override
  Widget build(BuildContext context) {
    final loggedIn = context.watch<AuthProvider>().isLoggedIn;
    return Scaffold(
      appBar: AppBar(
        title: const Text('我的视频'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add_to_photos_outlined),
            tooltip: '导入本地视频',
            onPressed: _importLocalVideo,
          ),
        ],
        bottom: loggedIn
            ? PreferredSize(
                preferredSize: const Size.fromHeight(48),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                  child: SegmentedButton<int>(
                    segments: const [
                      ButtonSegment(value: 0, label: Text('本地视频')),
                      ButtonSegment(value: 1, label: Text('云端视频')),
                    ],
                    selected: {_tab},
                    onSelectionChanged: (s) => setState(() => _tab = s.first),
                    style: const ButtonStyle(
                      visualDensity: VisualDensity.compact,
                    ),
                  ),
                ),
              )
            : null,
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: loggedIn && _tab == 1 ? _buildCloud() : _buildLocal(),
      ),
    );
  }

  // ---------- 本地视频（下载到手机，可本地播放；上传到云端） ----------
  Widget _buildLocal() {
    if (_localLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_local.isEmpty) {
      return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        children: [
          const SizedBox(height: 100),
          const Icon(Icons.offline_pin_outlined, size: 64, color: Colors.grey),
          const SizedBox(height: 12),
          const Center(
            child: Text('暂无本地视频',
                style: TextStyle(color: Colors.grey, fontSize: 15)),
          ),
          const SizedBox(height: 4),
          const Center(
            child: Text('可从手机存储导入，或在「链接下载」下载后自动保存到本地',
                style: TextStyle(fontSize: 12, color: Colors.grey)),
          ),
          const SizedBox(height: 20),
          Center(
            child: FilledButton.icon(
              onPressed: _importLocalVideo,
              icon: const Icon(Icons.add_to_photos_outlined),
              label: const Text('导入本地视频'),
            ),
          ),
        ],
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: _local.length,
      itemBuilder: (context, i) => _localCard(_local[i]),
    );
  }

  Widget _localCard(LocalVideo v) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        leading: Container(
          width: 46,
          height: 46,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(10),
          ),
          child: const Icon(Icons.offline_pin, size: 26),
        ),
        title: Text(v.displayTitle,
            maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(
          [
            if (v.fileSizeText.isNotEmpty) v.fileSizeText,
            if (v.sourceUrl != null) '链接下载',
          ].join(' · '),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        trailing: PopupMenuButton<String>(
          tooltip: '管理',
          onSelected: (a) => _handleLocal(v, a),
          itemBuilder: (_) => [
            const PopupMenuItem(value: 'play', child: Text('播放')),
            const PopupMenuItem(value: 'upload', child: Text('上传到云端')),
            const PopupMenuItem(value: 'rename', child: Text('重命名')),
            const PopupMenuItem(
                value: 'delete',
                child: Text('删除', style: TextStyle(color: Colors.red))),
          ],
        ),
        onTap: () => _playLocal(v),
      ),
    );
  }

  void _playLocal(LocalVideo v) {
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

  Future<void> _handleLocal(LocalVideo v, String action) async {
    switch (action) {
      case 'play':
        _playLocal(v);
        break;
      case 'upload':
        await _uploadLocal(v);
        break;
      case 'rename':
        await _renameLocal(v);
        break;
      case 'delete':
        await _deleteLocal(v);
        break;
    }
  }

  /// 修改本地视频标题（仅显示用，不影响文件与去重）
  Future<void> _renameLocal(LocalVideo v) async {
    final value = await showDialog<String>(
      context: context,
      builder: (ctx) => _RenameDialog(initial: v.displayTitle),
    );
    if (value == null || value.trim().isEmpty || !mounted) return;
    await LocalVideoStore.instance.rename(v.id, value.trim());
    await _loadLocal();
  }

  Future<void> _uploadLocal(LocalVideo v) async {
    if (!context.read<AuthProvider>().isLoggedIn) {
      _toast('请先登录后上传');
      return;
    }
    final file = File(v.filePath);
    if (!await file.exists()) {
      _toast('本地文件不存在');
      return;
    }
    // 该本地视频来自的下载任务若已建过云端视频（仅 Web 端下载例外）→ 不重复上传
    if (v.downloadTaskId != null) {
      final matches = context
          .read<DownloadProvider>()
          .tasks
          .where((t) => t.id == v.downloadTaskId)
          .toList();
      if (matches.isNotEmpty && matches.first.videoId != null) {
        _toast('该视频已上传到云端（来自 Web 端下载），请前往「云端视频」查看');
        await context.read<VideoProvider>().fetchVideos();
        return;
      }
    }
    if (!mounted) return;
    // 其余本地文件走分片上传（VideoUploadPage 支持预填本地文件）；返回后刷新云端列表（含秒传命中）
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => VideoUploadPage(initialVideo: v)),
    );
    if (!mounted) return;
    await context.read<VideoProvider>().fetchVideos();
  }

  Future<void> _deleteLocal(LocalVideo v) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除本地视频'),
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
    await _loadLocal();
    _toast('已删除');
  }

  // ---------- 云端视频（上传到服务器，可发起智能处理） ----------
  Widget _buildCloud() {
    final provider = context.watch<VideoProvider>();
    if (provider.isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (provider.videos.isEmpty) {
      return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        children: const [
          SizedBox(height: 120),
          Icon(Icons.cloud_outlined, size: 64, color: Colors.grey),
          SizedBox(height: 12),
          Center(
            child: Text('暂无云端视频',
                style: TextStyle(color: Colors.grey, fontSize: 15)),
          ),
          SizedBox(height: 4),
          Center(
            child: Text('把「本地视频」上传到云端后，会显示在这里并可发起智能处理',
                style: TextStyle(fontSize: 12, color: Colors.grey)),
          ),
        ],
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: provider.videos.length,
      itemBuilder: (context, index) {
        final video = provider.videos[index];
        return Card(
          margin: const EdgeInsets.only(bottom: 10),
          child: ListTile(
            leading: Container(
              width: 46,
              height: 46,
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Icon(Icons.cloud_done, size: 26),
            ),
            title: Text(video.displayTitle,
                maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${video.filename} · ${video.fileSizeText}',
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 4),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                        color:
                            _statusColor(video.status).withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        video.statusText,
                        style: TextStyle(
                          fontSize: 12,
                          color: _statusColor(video.status),
                        ),
                      ),
                    ),
                    // 审核状态（处理完成后显示）：待审核/已通过/不合格
                    if (video.status == 'completed' &&
                        video.reviewStatusText.isNotEmpty)
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 8, vertical: 2),
                        decoration: BoxDecoration(
                          color: _reviewColor(video.reviewStatus)
                              .withValues(alpha: 0.1),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Text(
                          video.reviewStatusText,
                          style: TextStyle(
                            fontSize: 12,
                            color: _reviewColor(video.reviewStatus),
                          ),
                        ),
                      ),
                    // 已发布到体验
                    if (video.status == 'completed' &&
                        video.reviewStatus == 'approved' &&
                        video.visibility == 'public')
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 8, vertical: 2),
                        decoration: BoxDecoration(
                          color: Colors.blue.shade50,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: const Text(
                          '已发布到尝味',
                          style: TextStyle(
                              fontSize: 12, color: Color(0xFF0C5460)),
                        ),
                      ),
                  ],
                ),
                // 不合格时显示管理员审核意见
                if (video.status == 'completed' &&
                    video.reviewStatus == 'rejected' &&
                    (video.reviewComment?.isNotEmpty ?? false))
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      '审核意见：${video.reviewComment}',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          fontSize: 11, color: Colors.grey.shade600),
                    ),
                  ),
              ],
            ),
            trailing: PopupMenuButton<String>(
              tooltip: '管理',
              onSelected: (v) => _handleMenu(video, v, provider),
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'play', child: Text('播放')),
                const PopupMenuItem(value: 'fav', child: Text('收藏')),
                const PopupMenuItem(value: 'rename', child: Text('重命名')),
                if (video.status != 'completed')
                  const PopupMenuItem(value: 'process', child: Text('开始处理')),
                // 审核通过的视频：属主可发布到体验 / 从体验下架
                if (video.status == 'completed' &&
                    video.reviewStatus == 'approved' &&
                    video.visibility != 'public')
                  const PopupMenuItem(
                      value: 'publish', child: Text('发布到尝味')),
                if (video.status == 'completed' &&
                    video.reviewStatus == 'approved' &&
                    video.visibility == 'public')
                  const PopupMenuItem(
                      value: 'unpublish', child: Text('从尝味下架')),
                if (video.status == 'completed' &&
                    video.reviewStatus == 'approved')
                  const PopupMenuItem(
                      value: 'share_gen', child: Text('生成分享链接')),
                const PopupMenuItem(
                    value: 'share_mgr', child: Text('分享链接管理')),
                const PopupMenuItem(
                    value: 'delete',
                    child: Text('删除', style: TextStyle(color: Colors.red))),
              ],
            ),
            onTap: () => _play(video),
          ),
        );
      },
    );
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  void _play(Video video) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => VideoPlayerPage(
          videoId: video.id,
          initialTitle: video.displayTitle,
        ),
      ),
    );
  }

  Future<void> _handleMenu(Video video, String action, VideoProvider provider) async {
    final api = ApiService();
    switch (action) {
      case 'play':
        _play(video);
        break;
      case 'fav':
        try {
          await api.addFavorite(video.id);
          if (!mounted) return;
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text('已收藏，可在“我的收藏”查看')));
        } catch (e) {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('收藏失败：${friendlyError(e)}')));
        }
        break;
      case 'rename':
        await _rename(video, provider);
        break;
      case 'process':
        try {
          await api.triggerProcess(video.id);
          if (!mounted) return;
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text('已提交步骤提取')));
          provider.fetchVideos();
        } catch (e) {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('处理失败：${friendlyError(e)}')));
        }
        break;
      case 'delete':
        await _delete(video, provider);
        break;
      case 'publish':
        await _publishToExperience(video, provider);
        break;
      case 'unpublish':
        await _unpublishFromExperience(video, provider);
        break;
      case 'share_gen':
        await _generateShare(video, provider);
        break;
      case 'share_mgr':
        await _manageShares(video, provider);
        break;
    }
  }

  /// 生成分享链接（需审核通过）；分享后内容锁定
  Future<void> _generateShare(Video video, VideoProvider provider) async {
    try {
      final data = await ApiService().createShareLink(video.id);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('分享链接已生成'),
          content: SelectableText(
              '${data['shareUrl']}\n\n（分享期间视频内容锁定；撤销/过期后解锁可再编辑）'),
          actions: [
            FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('知道了')),
          ],
        ),
      );
      provider.fetchVideos();
    } catch (e) {
      _toast('生成分享失败：${friendlyError(e)}');
    }
  }

  /// 分享链接管理（列表 + 撤销；撤销全部有效分享后视频解锁）
  Future<void> _manageShares(Video video, VideoProvider provider) async {
    try {
      final links = await ApiService().listShareLinks();
      final mine = links
          .where((l) => (l['videoId'] as num?)?.toInt() == video.id)
          .toList();
      if (!mounted) return;
      if (mine.isEmpty) {
        _toast('该视频暂无分享链接');
        return;
      }
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('分享链接管理'),
          content: SizedBox(
            width: double.maxFinite,
            child: ListView(
              shrinkWrap: true,
              children: mine.map((l) {
                final code = l['code'] as String;
                return ListTile(
                  dense: true,
                  title: SelectableText('/s/$code'),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete_outline),
                    tooltip: '撤销',
                    onPressed: () async {
                      Navigator.of(ctx).pop();
                      try {
                        await ApiService().revokeShareLink(code);
                        _toast('已撤销分享，视频已解锁');
                        provider.fetchVideos();
                      } catch (e) {
                        _toast('撤销失败：${friendlyError(e)}');
                      }
                    },
                  ),
                );
              }).toList(),
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('关闭')),
          ],
        ),
      );
    } catch (e) {
      _toast('获取分享失败：${friendlyError(e)}');
    }
  }

  Future<void> _rename(Video video, VideoProvider provider) async {
    // 对话框内 TextEditingController 由 _RenameDialog 自身管理生命周期，
    // 不能在 showDialog 返回后 dispose（否则弹窗关闭动画仍引用已释放的 controller 会崩溃）。
    final value = await showDialog<String>(
      context: context,
      builder: (ctx) => _RenameDialog(initial: video.displayTitle),
    );
    if (value == null || value.isEmpty || !mounted) return;
    try {
      await ApiService().updateVideo(video.id, value);
      provider.fetchVideos();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('重命名失败：${friendlyError(e)}')));
    }
  }

  Future<void> _publishToExperience(Video video, VideoProvider provider) async {
    try {
      await ApiService().publishVideo(video.id);
      _toast('已发布到体验');
      provider.fetchVideos();
    } catch (e) {
      _toast('发布失败：${friendlyError(e)}');
    }
  }

  Future<void> _unpublishFromExperience(
      Video video, VideoProvider provider) async {
    try {
      await ApiService().unpublishVideo(video.id);
      _toast('已从体验下架');
      provider.fetchVideos();
    } catch (e) {
      _toast('下架失败：${friendlyError(e)}');
    }
  }

  Future<void> _delete(Video video, VideoProvider provider) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除视频'),
        content: Text('确定删除「${video.displayTitle}」吗？此操作不可恢复。'),
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
    try {
      await provider.deleteVideo(video.id);
      // 删除联动清理：移除该视频的本地步骤缓存（孤儿文件）
      await LocalStepCacheStore.instance.removeFor(video.id);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('已删除')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('删除失败：${friendlyError(e)}')));
    }
  }

  Color _statusColor(String status) {
    switch (status) {
      case 'completed': return Colors.green;
      case 'processing': return Colors.orange;
      case 'failed': return Colors.red;
      default: return Colors.grey;
    }
  }

  Color _reviewColor(String? reviewStatus) {
    switch (reviewStatus) {
      case 'pending': return Colors.orange;
      case 'approved': return Colors.green;
      case 'rejected': return Colors.red;
      default: return Colors.grey;
    }
  }
}

/// 重命名对话框：controller 由本组件自身创建/释放，避免在宿主 showDialog 返回后
/// dispose 导致关闭动画仍引用已释放 controller 而崩溃。
class _RenameDialog extends StatefulWidget {
  final String initial;
  const _RenameDialog({required this.initial});

  @override
  State<_RenameDialog> createState() => _RenameDialogState();
}

class _RenameDialogState extends State<_RenameDialog> {
  late final TextEditingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initial);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('重命名视频'),
      content: TextField(
        controller: _controller,
        autofocus: true,
        decoration: const InputDecoration(
          hintText: '输入新的标题',
          border: OutlineInputBorder(),
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消')),
        FilledButton(
            onPressed: () => Navigator.of(context).pop(_controller.text.trim()),
            child: const Text('确定')),
      ],
    );
  }
}
