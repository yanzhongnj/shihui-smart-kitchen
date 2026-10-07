import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/auth_provider.dart';
import '../services/api_service.dart';
import 'about_help_page.dart';
import 'checkin_page.dart';
import 'diet_page.dart';
import 'health_profile_page.dart';
import 'plans_page.dart';
import 'report_page.dart';
import 'works_page.dart';
import 'favorites_page.dart';
import 'login_page.dart';
import 'my_recipes_page.dart';
import 'preferences_page.dart';
import 'task_list_page.dart';
import 'video_list_page.dart';
import 'weight_page.dart';
import 'xunwei_memory_page.dart';

/// 我的：游客引导登录；登录后管理视频/收藏/口味/下载/用量
class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  Future<void> _showBilling() async {
    try {
      final s = await ApiService().getBillingSummary();
      if (!mounted) return;
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('用量 / 计费'),
          content: Text(
            '累计流量：${(s['totalBytes'] ?? 0) / 1048576 >= 1024 ? '${((s['totalBytes'] ?? 0) / 1073741824).toStringAsFixed(2)} GB' : '${((s['totalBytes'] ?? 0) / 1048576).toStringAsFixed(1)} MB'}\n'
            '单价：¥${s['ratePerGb'] ?? 0}/GB\n'
            '预计费用：¥${s['estimatedCost'] ?? 0}\n'
            '计费说明：${s['formula'] ?? '按视频流量计费，公式待定'}',
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('好的')),
          ],
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('查询失败: $e')));
    }
  }

  Future<void> _checkVersion() async {
    try {
      final v = await ApiService().checkAppVersion('android', '1.0.0');
      if (!mounted) return;
      final hasUpdate = v['hasUpdate'] == true;
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title: Text(hasUpdate ? '发现新版本' : '已是最新版本'),
          content: Text(
            hasUpdate
                ? '最新版本：${v['latestVersion']}\n${v['updateMessage'] ?? '请到发布渠道下载更新'}'
                : '当前版本 ${v['currentVersion'] ?? '1.0.0'} 已是最新',
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('好的')),
          ],
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('检查更新失败: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthProvider>();
    final loggedIn = auth.isLoggedIn;

    return Scaffold(
      appBar: AppBar(title: const Text('我的')),
      body: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          if (!loggedIn) _guestCard(context, auth) else _userCard(auth),
          const SizedBox(height: 8),
          _sectionTitle('习惯与成长'),
          _tile(Icons.local_fire_department_outlined, '炒菜打卡',
              () => _push(const CheckinPage()),
              enabled: loggedIn),
          _tile(Icons.monitor_heart_outlined, '健康档案',
              () => _push(const HealthProfilePage()),
              enabled: loggedIn),
          _tile(Icons.restaurant_outlined, '饮食记录',
              () => _push(const DietPage()),
              enabled: loggedIn),
          _tile(Icons.monitor_weight_outlined, '体重追踪',
              () => _push(const WeightPage()),
              enabled: loggedIn),
          _tile(Icons.insert_chart_outlined, '月度报告',
              () => _push(const ReportPage()),
              enabled: loggedIn),
          _tile(Icons.event_note_outlined, '做饭计划',
              () => _push(const PlansPage()),
              enabled: loggedIn),
          const SizedBox(height: 4),
          _sectionTitle('我的内容'),
          _tile(Icons.video_library_outlined, '我的视频',
              () => _push(const VideoListPage())),
          _tile(Icons.menu_book_outlined, '我的菜谱',
              () => _push(const MyRecipesPage()), enabled: loggedIn),
          _tile(Icons.star_outline, '我的收藏',
              () => _push(const FavoritesPage())),
          _tile(Icons.photo_library_outlined, '我的作品',
              () => _push(const WorksPage()),
              enabled: loggedIn),
          _tile(Icons.tune, '口味 / 忌口设置',
              () => _push(const PreferencesPage())),
          _tile(Icons.psychology_outlined, '偏好进化记忆',
              () => _push(const XunweiMemoryPage()), enabled: loggedIn),
          _sectionTitle('智能处理'),
          _tile(Icons.autorenew, '处理任务',
              () => _push(const TaskListPage()), enabled: loggedIn),
          _sectionTitle('账户'),
          _tile(Icons.data_usage, '用量 / 计费', _showBilling,
              enabled: loggedIn),
          _tile(Icons.system_update_outlined, '检查更新', _checkVersion,
              enabled: loggedIn),
          _sectionTitle('关于'),
          _tile(Icons.help_outline, '帮助', () => _push(const HelpPage())),
          _tile(Icons.info_outline, '关于', () => _push(const AboutPage())),
          if (loggedIn)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: FilledButton.tonal(
                onPressed: () async {
                  await context.read<AuthProvider>().logout();
                },
                child: const Text('退出登录'),
              ),
            ),
        ],
      ),
    );
  }

  Widget _guestCard(BuildContext context, dynamic auth) {
    return Card(
      color: Theme.of(context).colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            const Icon(Icons.person_outline, size: 48),
            const SizedBox(height: 8),
            const Text('游客模式', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            const Text(
              '可解析视频链接、在线预览、下载，只读查看公开菜谱。\n登录后可上传视频、收藏、设置口味、站内分享。',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 12, color: Colors.black54),
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                FilledButton(
                  onPressed: () => _push(const LoginPage()),
                  child: const Text('登录 / 注册'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _userCard(dynamic auth) {
    final name = auth.username;
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          child: Text(name.isNotEmpty ? name[0] : '?'),
        ),
        title: Text(name, style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(auth.user?.email ?? '已登录'),
      ),
    );
  }

  Widget _sectionTitle(String t) {
    return Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 4, left: 4),
      child: Text(t,
          style: const TextStyle(fontSize: 12, color: Colors.grey)),
    );
  }

  Widget _tile(IconData icon, String label, VoidCallback? onTap,
      {bool enabled = true}) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 3),
      child: ListTile(
        leading: Icon(icon),
        title: Text(label),
        enabled: enabled,
        trailing: const Icon(Icons.chevron_right),
        onTap: enabled ? onTap : null,
      ),
    );
  }

  void _push(Widget page) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => page));
  }
}
