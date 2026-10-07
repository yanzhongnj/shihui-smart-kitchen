import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/xunwei_provider.dart';

/// 寻味会话列表：新建 / 切换 / 重命名 / 删除
class XunweiSessionsPage extends StatelessWidget {
  const XunweiSessionsPage({super.key});

  Future<void> _rename(BuildContext context, int id, String current) async {
    final name = await showDialog<String>(
      context: context,
      builder: (_) => _RenameDialog(initial: current),
    );
    if (name != null && name.trim().isNotEmpty && context.mounted) {
      await context.read<XunweiProvider>().renameSession(id, name.trim());
    }
  }

  Future<void> _delete(BuildContext context, int id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除会话'),
        content: const Text('确定删除这个会话及全部消息吗？'),
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
    if (ok == true && context.mounted) {
      await context.read<XunweiProvider>().deleteSession(id);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<XunweiProvider>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('寻味会话'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add_comment_outlined),
            tooltip: '新建对话',
            onPressed: () async {
              final id =
                  await context.read<XunweiProvider>().createSession();
              if (context.mounted) Navigator.of(context).pop(id);
            },
          ),
        ],
      ),
      body: p.sessions.isEmpty
          ? const Center(
              child: Text('暂无会话，点击右上角 + 新建',
                  style: TextStyle(color: Colors.grey)))
          : RefreshIndicator(
              onRefresh: () => context.read<XunweiProvider>().loadSessions(),
              child: ListView.builder(
                itemCount: p.sessions.length,
                itemBuilder: (context, i) {
                  final s = p.sessions[i];
                  final isCurrent = s.id == p.currentSessionId;
                  return ListTile(
                    leading: Icon(Icons.chat_bubble_outline,
                        color: isCurrent ? Colors.orange : Colors.grey),
                    title: Text(s.title,
                        maxLines: 1, overflow: TextOverflow.ellipsis),
                    subtitle: s.lastPreview.isEmpty
                        ? null
                        : Text(s.lastPreview,
                            maxLines: 1, overflow: TextOverflow.ellipsis),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text('${s.messageCount}条',
                            style: const TextStyle(
                                fontSize: 12, color: Colors.grey)),
                        PopupMenuButton<String>(
                          onSelected: (v) {
                            if (v == 'rename') _rename(context, s.id, s.title);
                            if (v == 'delete') _delete(context, s.id);
                          },
                          itemBuilder: (_) => const [
                            PopupMenuItem(value: 'rename', child: Text('重命名')),
                            PopupMenuItem(value: 'delete', child: Text('删除')),
                          ],
                        ),
                      ],
                    ),
                    onTap: () => Navigator.of(context).pop(s.id),
                  );
                },
              ),
            ),
    );
  }
}

/// 重命名对话框（controller 生命周期由自身管理，避免提前 dispose 崩溃）
class _RenameDialog extends StatefulWidget {
  final String initial;
  const _RenameDialog({required this.initial});

  @override
  State<_RenameDialog> createState() => _RenameDialogState();
}

class _RenameDialogState extends State<_RenameDialog> {
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
      title: const Text('重命名会话'),
      content: TextField(
        controller: _controller,
        autofocus: true,
        decoration: const InputDecoration(border: OutlineInputBorder()),
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
