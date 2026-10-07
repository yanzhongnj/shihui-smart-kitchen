import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import 'correction_report_page.dart';

/// 发布作品（Keep 式作品记录）：多图 + 关联菜谱 + 标题/描述/可见性。
/// 可从菜谱详情页带入 videoId/recipeTitle 预填。
class WorkPublishPage extends StatefulWidget {
  final int? videoId;
  final String? recipeTitle;
  const WorkPublishPage({super.key, this.videoId, this.recipeTitle});

  @override
  State<WorkPublishPage> createState() => _WorkPublishPageState();
}

class _WorkPublishPageState extends State<WorkPublishPage> {
  final ApiService _api = ApiService();
  final List<String> _images = [];
  final TextEditingController _titleCtrl = TextEditingController();
  final TextEditingController _descCtrl = TextEditingController();
  bool _public = true;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    if (widget.recipeTitle != null && widget.recipeTitle!.isNotEmpty) {
      _titleCtrl.text = widget.recipeTitle!;
    }
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickImages() async {
    final remaining = 9 - _images.length;
    if (remaining <= 0) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('最多 9 张图片')));
      return;
    }
    final picked = await ImagePicker().pickMultiImage(limit: remaining);
    if (picked.isEmpty) return;
    if (!mounted) return;
    setState(() {
      for (final x in picked) {
        _images.add(x.path);
      }
    });
  }

  Future<void> _submit() async {
    if (_images.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请先添加成品照片')));
      return;
    }
    setState(() => _submitting = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final res = await _api.createWork(
        videoId: widget.videoId,
        recipeTitle: widget.recipeTitle,
        title: _titleCtrl.text.trim().isEmpty ? null : _titleCtrl.text.trim(),
        description:
            _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
        status: _public ? 'public' : 'private',
        imagePaths: _images,
      );
      if (!mounted) return;
      final newBadges = (res['newBadges'] as List?) ?? [];
      if (newBadges.isNotEmpty) {
        final names = newBadges
            .map((b) => '${b['icon'] ?? '🏅'} ${b['name'] ?? ''}')
            .join('  ');
        await showDialog(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('🎉 获得新徽章！'),
            content: Text(names),
            actions: [
              TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('太棒了')),
            ],
          ),
        );
      } else {
        messenger.showSnackBar(
            const SnackBar(content: Text('作品发布成功！')));
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      messenger.showSnackBar(SnackBar(
          content: Text('发布失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('发布作品')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(widget.recipeTitle == null ? '成品照片' : '成品照片 · ${widget.recipeTitle}',
              style: const TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          _imageGrid(),
          const SizedBox(height: 12),
          TextField(
            controller: _titleCtrl,
            decoration: const InputDecoration(
              labelText: '标题',
              hintText: '如：鱼香肉丝',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _descCtrl,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: '描述（可选）',
              hintText: '记录这道菜的成果、心得…',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('公开作品'),
            subtitle: const Text('关闭后仅自己可见'),
            value: _public,
            onChanged: (v) => setState(() => _public = v),
          ),
          const SizedBox(height: 16),
          if (_images.isNotEmpty)
            OutlinedButton.icon(
              onPressed: _openCorrection,
              icon: const Icon(Icons.compare),
              label: const Text('AI 成品纠偏'),
              style: OutlinedButton.styleFrom(
                foregroundColor: const Color(0xFF2E9E6B),
              ),
            ),
          if (_images.isNotEmpty) const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _submitting ? null : _submit,
            icon: _submitting
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.publish),
            label: Text(_submitting ? '发布中…' : '发布作品'),
          ),
        ],
      ),
    );
  }

  /// AI 成品纠偏：用已选的第一张成品照 + 关联菜谱信息进入纠偏页（AI 对比目标成品）
  /// M2：纠偏后可把总评「存入作品描述」
  Future<void> _openCorrection() async {
    final title = _titleCtrl.text.trim().isEmpty
        ? widget.recipeTitle
        : _titleCtrl.text.trim();
    final summary = await Navigator.of(context).push<String>(MaterialPageRoute(
      builder: (_) => CorrectionReportPage(
        videoId: widget.videoId,
        title: title,
        initialImagePath: _images.first,
      ),
    ));
    if (!mounted || summary == null || summary.trim().isEmpty) return;
    final existing = _descCtrl.text.trim();
    _descCtrl.text = existing.isEmpty
        ? '📷 AI 成品纠偏：$summary'
        : '$existing\n📷 AI 成品纠偏：$summary';
  }

  Widget _imageGrid() {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        crossAxisSpacing: 8,
        mainAxisSpacing: 8,
      ),
      itemCount: _images.length + 1,
      itemBuilder: (_, i) {
        if (i == _images.length) {
          return InkWell(
            onTap: _pickImages,
            borderRadius: BorderRadius.circular(12),
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.grey.shade400),
              ),
              child: const Icon(Icons.add_a_photo_outlined,
                  color: Colors.grey, size: 32),
            ),
          );
        }
        final path = _images[i];
        return Stack(
          fit: StackFit.expand,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Image.file(
                File(path),
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) =>
                    Container(color: Colors.grey.shade200),
              ),
            ),
            Positioned(
              top: 4,
              right: 4,
              child: GestureDetector(
                onTap: () => setState(() => _images.removeAt(i)),
                child: Container(
                  padding: const EdgeInsets.all(2),
                  decoration: const BoxDecoration(
                    color: Colors.black54,
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(Icons.close, size: 16, color: Colors.white),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}
