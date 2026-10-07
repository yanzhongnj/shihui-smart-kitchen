import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/creation_models.dart';
import '../providers/auth_provider.dart';
import '../providers/creation_provider.dart';
import 'creation_chat_page.dart';
import 'creation_detail_page.dart';
import 'login_page.dart';
import '../utils/error_message.dart';

/// 酿味主页：创作会话列表 + 我的创作列表 + 新建创作
class CreationHomePage extends StatefulWidget {
  const CreationHomePage({super.key});

  @override
  State<CreationHomePage> createState() => _CreationHomePageState();
}

class _CreationHomePageState extends State<CreationHomePage> {
  bool _initDone = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final p = context.read<CreationProvider>();
    try {
      await p.loadSessions();
    } finally {
      if (mounted) setState(() => _initDone = true);
    }
  }

  Future<void> _refresh() async {
    final p = context.read<CreationProvider>();
    await p.loadSessions();
  }

  Future<void> _newCreation() async {
    final p = context.read<CreationProvider>();
    int sid;
    try {
      sid = await p.createSession();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('抱歉，${friendlyError(e, fallback: '新建创作失败，请稍后重试')}'),
      ));
      return;
    }
    if (!mounted) return;
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => CreationChatPage(sessionId: sid)),
    );
  }

  Future<void> _openSession(CreationSession s) async {
    final p = context.read<CreationProvider>();
    await p.openSession(s.id);
    if (!mounted) return;
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => CreationChatPage(sessionId: s.id)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<CreationProvider>();
    final loggedIn = context.watch<AuthProvider>().isLoggedIn;
    if (!loggedIn) {
      return Scaffold(
        appBar: AppBar(title: const Text('酿味 AI')),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.auto_awesome, size: 64, color: Color(0xFFFF8E53)),
              const SizedBox(height: 12),
              const Text('登录后即可让 AI 发明新菜'),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () => Navigator.push(context,
                    MaterialPageRoute(builder: (_) => const LoginPage())),
                child: const Text('去登录'),
              ),
            ],
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('酿味 AI')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _newCreation,
        icon: const Icon(Icons.add),
        label: const Text('新创作'),
      ),
      body: !_initDone
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                children: [
                  _sectionTitle('我的创作会话（${p.sessions.length}）'),
                  if (p.sessions.isEmpty)
                    const Padding(
                      padding: EdgeInsets.all(16),
                      child: Text('还没有创作，点右下角「新创作」开始',
                          style: TextStyle(color: Colors.black38)),
                    )
                  else
                    for (final s in p.sessions) _sessionCard(s),
                ],
              ),
            ),
    );
  }

  Widget _sectionTitle(String t) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Text(t,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
      );

  Widget _sessionCard(CreationSession s) {
    final (label, color) = _statusBadge(s.status);
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => _openSession(s),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 8, 10),
          child: Row(
            children: [
              const CircleAvatar(
                backgroundColor: Color(0xFFFFF3E0),
                child: Icon(Icons.lightbulb_outline, color: Color(0xFFFF8E53)),
              ),
              const SizedBox(width: 12),
              // 标题区：标题独占整行，徽标移到副标题行，永不挤压标题
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      s.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 15, fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 3),
                          decoration: BoxDecoration(
                            color: color.withValues(alpha: 0.12),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Text(label,
                              style: TextStyle(fontSize: 11, color: color)),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            s.lastPreview.isEmpty ? '点击继续创作' : s.lastPreview,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                                fontSize: 13, color: Colors.black54),
                          ),
                        ),
                        if (s.creationId != null)
                          TextButton(
                            style: TextButton.styleFrom(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 10, vertical: 2),
                              minimumSize: const Size(0, 30),
                              visualDensity: VisualDensity.compact,
                            ),
                            onPressed: () => Navigator.push(
                              context,
                              MaterialPageRoute(
                                  builder: (_) => CreationDetailPage(
                                      creationId: s.creationId!)),
                            ),
                            child: const Text('查看菜谱'),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                tooltip: '删除会话',
                icon: Icon(Icons.delete_outline, color: Colors.red.shade400),
                onPressed: () => _confirmDeleteSession(s),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 删除会话（有视频/菜谱时强提醒，避免误删已生成内容）
  Future<void> _confirmDeleteSession(CreationSession s) async {
    final hasVideo = s.status == 'VIDEO_READY' || s.status == 'VIDEO_GEN';
    final hasCreation = hasVideo ||
        s.status == 'RECIPE_READY' ||
        s.status == 'DONE' ||
        s.creationId != null;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除会话？'),
        content: Text(hasVideo
            ? '该会话已生成视频教程（视频与语音讲解文件将一并删除），删除后不可恢复。确定删除？'
            : hasCreation
                ? '该会话已生成菜谱，删除后不可恢复。确定删除？'
                : '删除该创作会话后不可恢复。确定删除？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.red.shade400),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final p = context.read<CreationProvider>();
    try {
      await p.deleteSession(s.id);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已删除会话「${s.title}」')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text('删除失败：${friendlyError(e, fallback: '请稍后重试')}')),
      );
    }
  }

  (String, Color) _statusBadge(String status) => switch (status) {
        'GATHERING' => ('收集中', const Color(0xFFE67E22)),
        'CONFIRMED' => ('待确认', const Color(0xFF4F7CF7)),
        'GENERATING' => ('生成中', const Color(0xFF8E5BF7)),
        'RECIPE_READY' => ('菜谱就绪', const Color(0xFF2E9E6B)),
        'VIDEO_GEN' => ('视频生成中', const Color(0xFFE74C3C)),
        'VIDEO_READY' => ('视频就绪', const Color(0xFF2E9E6B)),
        'DONE' => ('已完成', const Color(0xFF6C7A89)),
        _ => ('新会话', const Color(0xFF6C7A89)),
      };
}
