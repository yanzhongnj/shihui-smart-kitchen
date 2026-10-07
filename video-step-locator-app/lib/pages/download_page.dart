import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/download_provider.dart';
import '../widgets/video_preview_dialog.dart';
import 'video_list_page.dart';

/// 设置标题对话框（独立 StatefulWidget，controller 生命周期由自身管理，避免提前 dispose 触发 Element 断言）
class _TitleDialog extends StatefulWidget {
  final String initial;
  const _TitleDialog({required this.initial});

  @override
  State<_TitleDialog> createState() => _TitleDialogState();
}

class _TitleDialogState extends State<_TitleDialog> {
  late final TextEditingController _controller =
      TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('设置视频标题'),
      content: TextField(
        controller: _controller,
        autofocus: true,
        maxLines: 2,
        decoration: const InputDecoration(
          hintText: '输入标题（不影响去重，仅显示用）',
          border: OutlineInputBorder(),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_controller.text.trim()),
          child: const Text('确定'),
        ),
      ],
    );
  }
}

/// 链接下载页（方案乙）：粘贴链接 → 解析 → 勾选 → 本地下载（手机本地下载，进度与后端无关）
/// 可选 [initialUrl]：从「寻味」推荐跳转时预填链接并自动解析
class DownloadPage extends StatefulWidget {
  final String? initialUrl;

  const DownloadPage({super.key, this.initialUrl});

  @override
  State<DownloadPage> createState() => _DownloadPageState();
}

class _DownloadPageState extends State<DownloadPage> {
  final _urlController = TextEditingController();
  final Set<int> _selected = {};
  // 用户自定义标题（按解析条目索引）；仅影响显示，不影响 MD5 去重
  final Map<int, String> _titles = {};

  @override
  void initState() {
    super.initState();
    if (widget.initialUrl != null && widget.initialUrl!.isNotEmpty) {
      _urlController.text = widget.initialUrl!;
      // 预填后自动解析（从「寻味」跳转时直接显示解析结果卡片）
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        final p = context.read<DownloadProvider>();
        if (p.parseResult == null) _parse(p);
      });
    }
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  /// 跳转到「我的视频」（本地视频：游客可查看；注册用户可上传云端、步骤提取）
  void _openMyVideos() {
    if (!mounted) return;
    Navigator.push(context,
        MaterialPageRoute(builder: (_) => const VideoListPage()));
  }

  Future<void> _parse(DownloadProvider p) async {
    final url = _urlController.text.trim();
    if (url.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请先粘贴视频链接')));
      return;
    }
    _selected.clear();
    await p.parse(url);
  }

  /// 方案乙：本地下载（后端解析直连地址 → 手机本地下载，进度纯本地、与后端无关）
  void _startLocalDownload(DownloadProvider p) {
    final result = p.parseResult;
    if (result == null || _selected.isEmpty) return;
    final entries = _selected.map((i) {
      final e = result['entries'][i] as Map<String, dynamic>;
      return {
        'title': _titles[i] ?? e['title'] ?? '视频',
        'url': e['url'],
      };
    }).toList();
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => _LocalDownloadSheet(
          pageUrl: result['pageUrl'] ?? _urlController.text.trim(),
          entries: entries),
    );
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<DownloadProvider>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('链接下载'),
        actions: [
          IconButton(
            icon: const Icon(Icons.video_library_outlined),
            tooltip: '我的视频',
            onPressed: _openMyVideos,
          ),
        ],
      ),
      body: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(12),
        children: [
          _urlCard(p),
          if (p.isParsing) const Padding(
            padding: EdgeInsets.symmetric(vertical: 20),
            child: Center(child: CircularProgressIndicator()),
          ),
          if (p.parseResult != null) _parseResultCard(p),
          if (p.error != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Text(p.error!,
                  style: const TextStyle(color: Colors.red, fontSize: 12)),
            ),
        ],
      ),
    );
  }

  Widget _urlCard(DownloadProvider p) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            TextField(
              controller: _urlController,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: '粘贴视频/合集/播放列表链接',
                hintText: '支持 B站 / 抖音 / YouTube 等',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.icon(
                onPressed: p.isParsing ? null : () => _parse(p),
                icon: const Icon(Icons.search),
                label: const Text('解析'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _parseResultCard(DownloadProvider p) {
    final result = p.parseResult!;
    final entries = result['entries'] as List<dynamic>? ?? [];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '解析结果：${result['pageTitle'] ?? ''}（${entries.length} 个）',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 4),
            ...List.generate(entries.length, (i) {
              final e = entries[i] as Map<String, dynamic>;
              final selected = _selected.contains(i);
              final dur = e['duration'] != null
                  ? _fmtDuration((e['duration'] as num).toDouble())
                  : '';
              return CheckboxListTile(
                value: selected,
                dense: true,
                controlAffinity: ListTileControlAffinity.leading,
                title: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _titles[i] ?? e['title'] ?? '(未命名)',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      tooltip: '设置标题（不影响去重）',
                      onPressed: () =>
                          _editTitle(i, _titles[i] ?? e['title'] ?? ''),
                    ),
                  ],
                ),
                subtitle: Text('${e['uploader'] ?? ''} $dur'.trim()),
                onChanged: (v) => setState(() {
                  if (v == true) {
                    _selected.add(i);
                  } else {
                    _selected.remove(i);
                  }
                }),
              );
            }),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text('已选 ${_selected.length} 个'),
                TextButton(
                  onPressed: _selected.isEmpty
                      ? null
                      : () => _previewAll(p, entries),
                  child: const Text('预览'),
                ),
                FilledButton.icon(
                  onPressed: _selected.isEmpty
                      ? null
                      : () => _startLocalDownload(p),
                  icon: const Icon(Icons.download, size: 18),
                  label: const Text('本地下载'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 编辑视频标题（仅影响显示；MD5 去重仍按文件内容判断，标题可自由修改）
  Future<void> _editTitle(int index, String current) async {
    final value = await showDialog<String>(
      context: context,
      builder: (_) => _TitleDialog(initial: current),
    );
    if (value != null && value.isNotEmpty && mounted) {
      setState(() => _titles[index] = value);
    }
  }

  void _previewAll(DownloadProvider p, List<dynamic> entries) {
    final first = _selected.first;
    final e = entries[first] as Map<String, dynamic>;
    if (e['url'] == null) return;
    VideoPreviewDialog.show(context, p.previewUrl(e['url'] as String));
  }

  String _fmtDuration(double s) {
    final m = (s ~/ 60).toString().padLeft(2, '0');
    final sec = (s % 60).toStringAsFixed(0).padLeft(2, '0');
    return '$m:$sec';
  }
}

/// 方案乙「本地下载」进度底部面板：逐条展示本地下载进度（进度纯本地、与后端无关）
class _LocalDownloadSheet extends StatefulWidget {
  final String pageUrl;
  final List<Map<String, dynamic>> entries;
  const _LocalDownloadSheet({required this.pageUrl, required this.entries});

  @override
  State<_LocalDownloadSheet> createState() => _LocalDownloadSheetState();
}

class _LocalDownloadSheetState extends State<_LocalDownloadSheet> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      unawaited(context
          .read<DownloadProvider>()
          .startLocalDownloads(widget.pageUrl, widget.entries));
    });
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<DownloadProvider>();
    final items = p.localDownloads;
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
            bottom: MediaQuery.of(context).viewInsets.bottom),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('本地下载',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              const Text('下载完成后保存在手机本地，进度与后端无关；可去「我的视频」查看/上传',
                  style: TextStyle(fontSize: 12, color: Colors.grey)),
              const SizedBox(height: 12),
              ConstrainedBox(
                constraints: BoxConstraints(
                    maxHeight: MediaQuery.of(context).size.height * 0.55),
                child: items.isEmpty
                    ? const Padding(
                        padding: EdgeInsets.symmetric(vertical: 24),
                        child: Center(child: CircularProgressIndicator()))
                    : ListView(
                        shrinkWrap: true,
                        children: items.map((it) => _itemTile(p, it)).toList(),
                      ),
              ),
              const SizedBox(height: 8),
              if (p.localDownloadAllDone)
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: () {
                      Navigator.of(context).pop();
                      final okCount = items
                          .where((x) => x.status == 'completed')
                          .length;
                      final badCount = items
                          .where((x) =>
                              x.status == 'failed' || x.status == 'cancelled')
                          .length;
                      final msg = badCount > 0
                          ? '本地下载结束：成功 $okCount 个，失败/取消 $badCount 个，可去「我的视频」查看'
                          : '已保存 $okCount 个到本地，可去「我的视频」查看';
                      ScaffoldMessenger.of(context)
                        ..clearSnackBars()
                        ..showSnackBar(SnackBar(
                          content: Text(msg),
                          duration: const Duration(seconds: 3),
                        ));
                    },
                    child: const Text('完成'),
                  ),
                )
              else
                Row(
                  children: [
                    const Text('HLS 下载请保持 App 在前台',
                        style: TextStyle(color: Colors.orange, fontSize: 12)),
                    const Spacer(),
                    TextButton(
                        onPressed: p.isLocalDownloading
                            ? () => p.cancelAllLocalDownloads()
                            : null,
                        child: const Text('全部取消')),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _itemTile(DownloadProvider p, LocalDownloadItem it) {
    final done = it.status == 'completed';
    final failed = it.status == 'failed' || it.status == 'cancelled';
    return ListTile(
      contentPadding: EdgeInsets.zero,
      dense: true,
      leading: Icon(
        done
            ? Icons.check_circle
            : failed
                ? Icons.error
                : Icons.downloading,
        color: done
            ? Colors.green
            : failed
                ? Colors.red
                : Colors.blue,
      ),
      title: Text(it.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (it.isActive) ...[
            LinearProgressIndicator(
                value: it.progress > 0 ? it.progress : null),
            const SizedBox(height: 2),
            Text(
              it.stageText +
                  (it.progress > 0
                      ? ' ${(it.progress * 100).toStringAsFixed(0)}%'
                      : ''),
              style: const TextStyle(fontSize: 11, color: Colors.grey),
            ),
          ] else if (failed)
            Text(it.error ?? (it.status == 'cancelled' ? '已取消' : '失败'),
                style: const TextStyle(fontSize: 12, color: Colors.red))
          else if (it.note != null && it.note!.isNotEmpty)
            Text(it.note!,
                style: const TextStyle(fontSize: 12, color: Colors.grey)),
        ],
      ),
      trailing: it.isActive
          ? TextButton(
              onPressed: () => p.cancelLocalDownload(it.id),
              child: const Text('取消'),
            )
          : null,
    );
  }
}
