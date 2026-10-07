import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import 'work_publish_page.dart';

/// 我的作品集（Keep 式作品记录）：网格展示 + 详情 + 删除 + 发布入口
class WorksPage extends StatefulWidget {
  const WorksPage({super.key});

  @override
  State<WorksPage> createState() => _WorksPageState();
}

class _WorksPageState extends State<WorksPage> {
  final ApiService _api = ApiService();
  List<dynamic> _works = [];
  final Map<int, String> _coverUrls = {};
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
      final works = await _api.getMyWorks();
      _coverUrls.clear();
      for (final w in works) {
        final id = w['id'] as int;
        if ((w['images'] as List?)?.isNotEmpty == true) {
          _coverUrls[id] = await _api.workImageUrl(id, 0);
        }
      }
      if (!mounted) return;
      setState(() {
        _works = works;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载作品失败，请稍后重试');
        _loading = false;
      });
    }
  }

  Future<void> _openDetail(Map<String, dynamic> w) async {
    final images = (w['images'] as List?) ?? [];
    final urls = <String>[];
    for (var i = 0; i < images.length; i++) {
      urls.add(await _api.workImageUrl(w['id'] as int, i));
    }
    if (!mounted) return;
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      builder: (_) => _WorkDetailSheet(
        work: w,
        imageUrls: urls,
        onDelete: () async {
          await _api.deleteWork(w['id'] as int);
          if (!mounted) return;
          await _load();
        },
      ),
    );
  }

  Future<void> _pushPublish() async {
    final res = await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const WorkPublishPage()),
    );
    if (res == true) _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('我的作品')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _pushPublish,
        icon: const Icon(Icons.add_a_photo_outlined),
        label: const Text('发作品'),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_error!),
            const SizedBox(height: 12),
            FilledButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    if (_works.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.restaurant_menu, size: 64, color: Colors.grey),
            const SizedBox(height: 12),
            const Text('还没有作品，把做好的菜拍下来分享吧'),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _pushPublish,
              icon: const Icon(Icons.add_a_photo_outlined),
              label: const Text('发布第一个作品'),
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: GridView.builder(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 80),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
          childAspectRatio: 0.88,
        ),
        itemCount: _works.length,
        itemBuilder: (_, i) => _workCard(_works[i]),
      ),
    );
  }

  Widget _workCard(Map<String, dynamic> w) {
    final id = w['id'] as int;
    final cover = _coverUrls[id];
    final title = w['title'] ?? w['recipeTitle'] ?? '作品';
    final createdAt = w['createdAt']?.toString() ?? '';
    return InkWell(
      onTap: () => _openDetail(w),
      borderRadius: BorderRadius.circular(12),
      child: Card(
        clipBehavior: Clip.antiAlias,
        margin: EdgeInsets.zero,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: cover != null
                  ? Image.network(
                      cover,
                      fit: BoxFit.cover,
                      width: double.infinity,
                      errorBuilder: (_, __, ___) => const Center(
                          child:
                              Icon(Icons.broken_image, color: Colors.grey)),
                    )
                  : const Center(
                      child: Icon(Icons.restaurant, color: Colors.grey)),
            ),
            Padding(
              padding: const EdgeInsets.all(6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '$title',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontWeight: FontWeight.w600, fontSize: 13),
                  ),
                  Text(
                    createdAt.length >= 10 ? createdAt.substring(0, 10) : '',
                    style: const TextStyle(color: Colors.grey, fontSize: 11),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 作品详情底部弹层：多图翻页 + 信息 + 删除
class _WorkDetailSheet extends StatefulWidget {
  final Map<String, dynamic> work;
  final List<String> imageUrls;
  final Future<void> Function() onDelete;
  const _WorkDetailSheet({
    required this.work,
    required this.imageUrls,
    required this.onDelete,
  });

  @override
  State<_WorkDetailSheet> createState() => _WorkDetailSheetState();
}

class _WorkDetailSheetState extends State<_WorkDetailSheet> {
  int _page = 0;

  @override
  Widget build(BuildContext context) {
    final w = widget.work;
    final title = w['title'] ?? w['recipeTitle'] ?? '作品';
    final recipe = w['recipeTitle'];
    final desc = w['description']?.toString() ?? '';
    final createdAt = w['createdAt']?.toString() ?? '';
    final date = createdAt.length >= 10 ? createdAt.substring(0, 10) : '';
    final height = MediaQuery.of(context).size.height * 0.7;

    return SizedBox(
      height: height,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Expanded(
                  child: Text('$title',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.bold)),
                ),
                IconButton(
                  icon: const Icon(Icons.delete_outline, color: Colors.red),
                  tooltip: '删除作品',
                  onPressed: () async {
                    final ok = await showDialog<bool>(
                      context: context,
                      builder: (ctx) => AlertDialog(
                        title: const Text('删除这个作品？'),
                        actions: [
                          TextButton(
                              onPressed: () => Navigator.of(ctx).pop(false),
                              child: const Text('取消')),
                          FilledButton(
                              onPressed: () => Navigator.of(ctx).pop(true),
                              child: const Text('删除')),
                        ],
                      ),
                    );
                    if (ok == true) {
                      await widget.onDelete();
                      if (context.mounted) Navigator.of(context).pop();
                    }
                  },
                ),
              ],
            ),
          ),
          Expanded(
            child: widget.imageUrls.isEmpty
                ? const Center(child: Icon(Icons.restaurant, size: 64))
                : PageView.builder(
                    onPageChanged: (i) => setState(() => _page = i),
                    itemCount: widget.imageUrls.length,
                    itemBuilder: (_, i) => Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(12),
                        child: Image.network(
                          widget.imageUrls[i],
                          fit: BoxFit.contain,
                          width: double.infinity,
                          errorBuilder: (_, __, ___) => const Center(
                              child: Icon(Icons.broken_image,
                                  size: 48, color: Colors.grey)),
                        ),
                      ),
                    ),
                  ),
          ),
          if (widget.imageUrls.length > 1)
            Padding(
              padding: const EdgeInsets.all(4),
              child: Text('${_page + 1} / ${widget.imageUrls.length}',
                  style: const TextStyle(color: Colors.grey, fontSize: 12)),
            ),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (recipe != null && '$recipe'.isNotEmpty)
                  Text('🍲 $recipe',
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                if (date.isNotEmpty)
                  Text(date, style: const TextStyle(color: Colors.grey)),
                if (desc.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(desc),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}
