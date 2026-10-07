import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/xunwei_provider.dart';
import '../utils/error_message.dart';

/// 寻味记忆键 → 中文标签
const Map<String, String> _keyLabels = {
  'taste': '口味偏好',
  'taboo': '忌口 / 过敏',
  'cuisine': '偏好菜系',
  'skill': '厨艺水平',
  'scene': '使用场景',
  'notes': '其他信息',
  'liked_dishes': '喜欢的菜',
  'disliked_dishes': '不喜欢的菜',
  'liked_videos': '喜欢的视频',
  'disliked_videos': '不喜欢的视频',
  'preferred_ingredients': '常用食材',
  'avoided_ingredients': '避免的食材',
  'scenes': '场景偏好',
};

/// 记忆来源 → 中文标签
const Map<String, String> _sourceLabels = {
  'explicit': '显式设置',
  'chat': '对话学习',
  'download': '下载',
  'favorite': '收藏',
  'review': '评价',
  'vision': '拍照识菜',
};

String _labelFor(String key) => _keyLabels[key] ?? key;

/// 我的寻味记忆：查看 / 编辑 / 删除 / 清空 / 偏好自进化开关
class XunweiMemoryPage extends StatefulWidget {
  const XunweiMemoryPage({super.key});

  @override
  State<XunweiMemoryPage> createState() => _XunweiMemoryPageState();
}

class _XunweiMemoryPageState extends State<XunweiMemoryPage> {
  List<dynamic> _memories = [];
  bool _loading = true;
  bool _autoEvolve = true;
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
      final p = context.read<XunweiProvider>();
      final mems = await p.getMemory();
      final auto = await p.isAutoEvolveEnabled();
      if (!mounted) return;
      setState(() {
        _memories = mems;
        _autoEvolve = auto;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载记忆失败，请稍后重试');
        _loading = false;
      });
    }
  }

  Future<void> _toggleAutoEvolve(bool value) async {
    try {
      await context.read<XunweiProvider>().setAutoEvolveEnabled(value);
      if (!mounted) return;
      setState(() => _autoEvolve = value);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(value ? '已开启偏好自进化' : '已暂停偏好自进化')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('操作失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  Future<void> _editMemory(Map<String, dynamic> mem) async {
    final messenger = ScaffoldMessenger.of(context);
    final p = context.read<XunweiProvider>();
    final controller =
        TextEditingController(text: mem['memValue'] as String? ?? '');
    final value = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('修改「${_labelFor(mem['memKey'] as String? ?? '')}」'),
        content: TextField(
          controller: controller,
          maxLines: 3,
          decoration: const InputDecoration(hintText: '输入新的记忆内容'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(controller.text.trim()),
              child: const Text('保存')),
        ],
      ),
    );
    if (value == null || value.isEmpty) return;
    try {
      await p.updateMemory(mem['id'] as int, value);
      await _load();
      messenger.showSnackBar(const SnackBar(content: Text('已更新记忆')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(
          content: Text('修改失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  Future<void> _deleteMemory(Map<String, dynamic> mem) async {
    final messenger = ScaffoldMessenger.of(context);
    final p = context.read<XunweiProvider>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除这条记忆？'),
        content: Text(
            '「${_labelFor(mem['memKey'] as String? ?? '')}」：${mem['memValue']}'),
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
    if (ok != true) return;
    try {
      await p.deleteMemory(mem['id'] as int);
      await _load();
      messenger.showSnackBar(const SnackBar(content: Text('已删除记忆')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(
          content: Text('删除失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  Future<void> _clearAll() async {
    final messenger = ScaffoldMessenger.of(context);
    final p = context.read<XunweiProvider>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清空全部记忆？'),
        content: const Text('将删除所有寻味学习到的画像，此操作不可恢复。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('清空')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await p.clearMemory();
      await _load();
      messenger.showSnackBar(const SnackBar(content: Text('已清空记忆')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(
          content: Text('清空失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('偏好进化记忆')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.error_outline,
                            size: 48, color: Colors.grey),
                        const SizedBox(height: 12),
                        Text(_error!, textAlign: TextAlign.center),
                        const SizedBox(height: 12),
                        FilledButton.tonal(
                            onPressed: _load, child: const Text('重试')),
                      ],
                    ),
                  ),
                )
              : ListView(
                  padding: const EdgeInsets.all(12),
                  children: [
                    Card(
                      child: Column(
                        children: [
                          SwitchListTile(
                            title: const Text('偏好自进化'),
                            subtitle: const Text(
                                '开启后，系统会自动从你的寻味对话、口味设置和行为中学习并更新画像，越用越懂你'),
                            value: _autoEvolve,
                            onChanged: _toggleAutoEvolve,
                          ),
                          const Divider(height: 1),
                          const ListTile(
                            leading: Icon(Icons.auto_awesome,
                                color: Colors.orange),
                            title: Text('记忆会如何帮到你？'),
                            subtitle: Text(
                                '寻味推荐会贴合你的口味、忌口与偏好菜系；你也可在这里随时查看、纠正或清除系统学到的记忆'),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('已学到的记忆（${_memories.length}）',
                            style: Theme.of(context).textTheme.titleSmall),
                        if (_memories.isNotEmpty)
                          TextButton(
                            onPressed: _clearAll,
                            child: const Text('清空全部',
                                style: TextStyle(color: Colors.red)),
                          ),
                      ],
                    ),
                    if (_memories.isEmpty)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 48),
                        child: Center(
                          child: Text(
                              '还没有学到记忆。\n多和「寻味」聊聊你想吃什么、有什么口味偏好吧。',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: Colors.grey)),
                        ),
                      )
                    else
                      ..._memories.map(
                          (m) => _memoryTile(m as Map<String, dynamic>)),
                  ],
                ),
    );
  }

  Widget _memoryTile(Map<String, dynamic> mem) {
    final key = mem['memKey'] as String? ?? '';
    final source = mem['source'] as String?;
    final confidence = mem['confidence'] as int?;
    final srcText = _sourceLabels[source] ?? (source ?? '');
    final meta = <String>[
      if (srcText.isNotEmpty) srcText,
      if (confidence != null) '置信度 $confidence%',
    ].join(' · ');
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        leading: const Icon(Icons.psychology_outlined),
        title: Text(_labelFor(key)),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_displayValue(mem),
                maxLines: 4, overflow: TextOverflow.ellipsis),
            if (meta.isNotEmpty)
              Text(meta,
                  style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
          ],
        ),
        isThreeLine: true,
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.edit_outlined, size: 20),
              tooltip: '修改',
              onPressed: () => _editMemory(mem),
            ),
            IconButton(
              icon: const Icon(Icons.delete_outline, size: 20),
              tooltip: '删除',
              onPressed: () => _deleteMemory(mem),
            ),
          ],
        ),
      ),
    );
  }

  /// 行为记忆（liked_videos/disliked_videos）值为 JSON 数组 → 渲染为列表
  String _displayValue(Map<String, dynamic> mem) {
    final key = mem['memKey'] as String? ?? '';
    final value = mem['memValue'] as String? ?? '';
    if ((key == 'liked_videos' || key == 'disliked_videos') &&
        value.trimLeft().startsWith('[')) {
      try {
        final arr = jsonDecode(value) as List;
        if (arr.isEmpty) return '（空）';
        return arr
            .map((e) {
              final m = e as Map<String, dynamic>;
              final c = (m['count'] as num?)?.toInt() ?? 1;
              return '• ${m['title']}${c > 1 ? ' ×$c' : ''}';
            })
            .join('\n');
      } catch (_) {}
    }
    return value;
  }
}
