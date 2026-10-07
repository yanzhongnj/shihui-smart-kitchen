import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/task_provider.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// 智能处理任务列表：展示进度 + 按状态/角色提供管理操作（重试/启动/暂停/取消/删除）
class TaskListPage extends StatefulWidget {
  const TaskListPage({super.key});

  @override
  State<TaskListPage> createState() => _TaskListPageState();
}

class _TaskListPageState extends State<TaskListPage> {
  final ApiService _api = ApiService();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<TaskProvider>().fetchTasks();
    });
  }

  Future<void> _manage(Map<String, dynamic> task, String action) async {
    final id = (task['id'] as num).toInt();
    String msg;
    try {
      switch (action) {
        case 'start':
          await _api.startTask(id);
          msg = '任务已启动';
        case 'pause':
          await _api.pauseTask(id);
          msg = '任务已暂停';
        case 'retry':
          await _api.retryTask(id);
          msg = '任务已重新入队';
        case 'cancel':
          await _api.cancelTask(id);
          msg = '任务已取消';
        case 'delete':
          final ok = await showDialog<bool>(
            context: context,
            builder: (_) => AlertDialog(
              title: const Text('删除任务'),
              content: const Text('确定删除该智能处理任务吗？'),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('取消')),
                FilledButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('删除')),
              ],
            ),
          );
          if (ok != true) return;
          await _api.deleteTask(id);
          msg = '任务已删除';
        default:
          return;
      }
    } catch (e) {
      _toast('操作失败：${friendlyError(e)}');
      return;
    }
    _toast(msg);
    context.read<TaskProvider>().fetchTasks();
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  /// 按任务状态返回可执行的管理操作
  List<Map<String, String>> _actionsFor(String status) {
    switch (status) {
      case 'queued':
        return [
          const {'v': 'cancel', 't': '取消任务'}
        ];
      case 'running':
        return [
          const {'v': 'pause', 't': '暂停任务'},
          const {'v': 'cancel', 't': '取消任务'},
        ];
      case 'paused':
        return [
          const {'v': 'start', 't': '继续处理'},
          const {'v': 'delete', 't': '删除任务'},
        ];
      case 'failed':
        return [
          const {'v': 'retry', 't': '重试'},
          const {'v': 'delete', 't': '删除任务'},
        ];
      default: // completed / 其它
        return [
          const {'v': 'delete', 't': '删除任务'}
        ];
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<TaskProvider>();
    return Scaffold(
      appBar: AppBar(title: const Text('智能处理')),
      body: _buildBody(provider),
    );
  }

  Widget _buildBody(TaskProvider provider) {
    if (provider.isLoading && provider.tasks.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }

    return RefreshIndicator(
      onRefresh: () => context.read<TaskProvider>().fetchTasks(),
      child: provider.tasks.isEmpty
          ? ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              children: const [
                SizedBox(height: 140),
                Icon(Icons.inbox_outlined, size: 64, color: Colors.grey),
                SizedBox(height: 12),
                Center(
                  child: Text('暂无智能处理任务',
                      style: TextStyle(color: Colors.grey, fontSize: 15)),
                ),
                SizedBox(height: 4),
                Center(
                  child: Text('在「我的视频」中点击“开始处理”即可创建任务',
                      style: TextStyle(fontSize: 12, color: Colors.grey)),
                ),
              ],
            )
          : ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(12),
              itemCount: provider.tasks.length,
              itemBuilder: (context, index) =>
                  _buildTaskCard(provider.tasks[index]),
            ),
    );
  }

  /// 将后端原始异常信息转为友好中文（去掉 Java 类名/包名/堆栈痕迹）
  String _friendlyErrorText(String? raw) {
    if (raw == null || raw.trim().isEmpty) return '处理失败，请重试';
    var s = raw.trim();
    // 去掉形如 com.xxx.xxx.PipelineException 的完整类名
    s = s.replaceAll(RegExp(r'(?:[A-Za-z_][\w]*\.)+[A-Za-z_][\w]*Exception'), '');
    // 去掉行内残留的 XXXException: 前缀
    s = s.replaceAll(RegExp(r'[A-Za-z_][\w]*Exception\s*[:：]?\s*'), '');
    s = s.replaceAll(RegExp(r'^[\s:：\-—>]+'), '').trim();
    // 只取第一行，避免堆栈
    s = s.split('\n').first.trim();
    if (s.isEmpty) return '处理失败，请重试';
    if (s.length > 80) s = s.substring(0, 80) + '…';
    // 若不含中文（纯技术英文），给通用提示
    final hasCn = RegExp(r'[\u4e00-\u9fa5]').hasMatch(s);
    return hasCn ? s : '处理失败，请重试';
  }

  /// 任务卡片：状态图标 + 中文状态 + 彩色进度 + 阶段/错误/时间
  Widget _buildTaskCard(Map<String, dynamic> task) {
    final status = task['status'] as String? ?? '';
    final progress = (task['progress'] as num?)?.toInt() ?? 0;
    final videoId = (task['videoId'] as num?)?.toInt();
    final videoTitle = task['videoTitle'] as String?;
    final phase = task['currentPhase'] as String?;
    final error = task['errorMessage'] as String?;
    final createdAt = task['createdAt'] as String?;
    final info = _statusInfo(status);

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 4, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            // 状态图标
            Container(
              width: 46,
              height: 46,
              decoration: BoxDecoration(
                color: info.color.withValues(alpha: 0.12),
                shape: BoxShape.circle,
              ),
              child: Icon(info.icon, color: info.color, size: 26),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    videoTitle ?? '视频 #$videoId',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontWeight: FontWeight.w600, fontSize: 15),
                  ),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      _statusChip(status),
                      const SizedBox(width: 8),
                      Text('$progress%',
                          style: TextStyle(
                              fontSize: 12, color: Colors.grey.shade700)),
                    ],
                  ),
                  if (progress > 0) ...[
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: (progress / 100).clamp(0.0, 1.0),
                        minHeight: 6,
                        color: info.color,
                        backgroundColor: info.color.withValues(alpha: 0.12),
                      ),
                    ),
                  ],
                  if (phase != null && phase.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text('当前：$phase',
                        style: TextStyle(
                            fontSize: 12, color: Colors.grey.shade600)),
                  ],
                  if (error != null && error.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(_friendlyErrorText(error),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style:
                            const TextStyle(fontSize: 12, color: Colors.red)),
                  ],
                  if (createdAt != null && createdAt.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text('创建：$createdAt',
                        style: TextStyle(
                            fontSize: 11, color: Colors.grey.shade500)),
                  ],
                ],
              ),
            ),
            PopupMenuButton<String>(
              tooltip: '管理',
              onSelected: (v) => _manage(task, v),
              itemBuilder: (_) => _actionsFor(status)
                  .map(
                      (a) => PopupMenuItem(value: a['v'], child: Text(a['t']!)))
                  .toList(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusChip(String status) {
    final info = _statusInfo(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: info.color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        info.label,
        style: TextStyle(fontSize: 12, color: info.color),
      ),
    );
  }

  /// 状态 → (图标, 颜色, 中文标签)
  _TaskStatusInfo _statusInfo(String status) {
    switch (status) {
      case 'queued':
        return const _TaskStatusInfo(
            Icons.hourglass_empty, Colors.blueGrey, '排队中');
      case 'running':
        return const _TaskStatusInfo(Icons.sync, Colors.blue, '处理中');
      case 'paused':
        return _TaskStatusInfo(
            Icons.pause_circle_outline, Colors.amber.shade800, '已暂停');
      case 'completed':
        return const _TaskStatusInfo(
            Icons.check_circle_outline, Colors.green, '已完成');
      case 'failed':
        return const _TaskStatusInfo(Icons.error_outline, Colors.red, '失败');
      case 'canceled':
        return const _TaskStatusInfo(Icons.cancel_outlined, Colors.grey, '已取消');
      default:
        return _TaskStatusInfo(Icons.help_outline, Colors.grey, status);
    }
  }
}

/// 任务状态展示信息
class _TaskStatusInfo {
  final IconData icon;
  final Color color;
  final String label;
  const _TaskStatusInfo(this.icon, this.color, this.label);
}
