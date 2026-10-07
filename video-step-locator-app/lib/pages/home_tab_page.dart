import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/auth_provider.dart';
import '../services/api_service.dart';
import 'download_page.dart';
import 'checkin_page.dart';
import 'experience_page.dart';
import 'favorites_page.dart';
import 'login_page.dart';
import 'plans_page.dart';
import 'profile_page.dart';
import 'recipe_list_page.dart';
import 'report_page.dart';
import 'video_list_page.dart';
import 'video_player_page.dart';
import 'work_publish_page.dart';
import 'works_page.dart';
import 'creation_home_page.dart';
import 'health_photo_checkin_page.dart';
import 'xunwei_page.dart';

/// 首页（炫彩）：渐变头图 + 语音亮点 + 快捷入口 + 体验视频
class HomeTabPage extends StatefulWidget {
  final void Function(int index)? onSwitchTab;
  const HomeTabPage({super.key, this.onSwitchTab});

  @override
  State<HomeTabPage> createState() => _HomeTabPageState();
}

class _HomeTabPageState extends State<HomeTabPage> {
  final ApiService _api = ApiService();
  List<dynamic> _experience = [];
  bool _loading = true;
  bool _error = false;
  // 首页闭环数据（登录用户：今日状态/等级/计划预览）
  Map<String, dynamic> _stats = {};
  Map<String, dynamic> _level = {};
  List<dynamic> _plans = [];
  bool _dashLoading = false;

  @override
  void initState() {
    super.initState();
    _loadExperience();
  }

  Future<void> _loadExperience() async {
    setState(() {
      _loading = true;
      _error = false;
    });
    try {
      final data = await _api.experienceVideos();
      if (!mounted) return;
      setState(() {
        _experience = data;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = true;
        _loading = false;
      });
    }
  }

  Future<void> _loadDashboard() async {
    setState(() => _dashLoading = true);
    try {
      final stats = await _api.getCheckinStats();
      final level = await _api.getLevel();
      final plans = await _api.getPlans();
      if (!mounted) return;
      setState(() {
        _stats = stats;
        _level = level;
        _plans = plans;
        _dashLoading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _dashLoading = false);
    }
  }

  void _push(Widget page) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => page));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: CustomScrollView(
        slivers: [
          SliverToBoxAdapter(child: _buildHero()),
          SliverToBoxAdapter(child: _buildDashboard()),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 22, 12, 4),
              child: Row(
                children: [
                  const Text('尝味',
                      style:
                          TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                  const Spacer(),
                  TextButton(
                    onPressed: () => widget.onSwitchTab?.call(1),
                    child: const Text('查看全部'),
                  ),
                ],
              ),
            ),
          ),
          SliverToBoxAdapter(child: _buildExperienceSection()),
          const SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }

  // ---------- 渐变头图 ----------
  Widget _buildHero() {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [Color(0xFFF0A06E), Color(0xFFE8876B), Color(0xFFD96E58)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.vertical(bottom: Radius.circular(28)),
      ),
      padding: const EdgeInsets.fromLTRB(20, 28, 20, 30),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(14),
                child: Image.asset('assets/logo.png',
                    width: 54, height: 54, fit: BoxFit.cover),
              ),
              const SizedBox(width: 14),
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('拾味',
                        style: TextStyle(
                            color: Colors.white,
                            fontSize: 22,
                            fontWeight: FontWeight.bold)),
                    SizedBox(height: 2),
                    Text('会找菜 · 会造菜 · 会做菜 · 会懂你',
                        style: TextStyle(color: Colors.white70, fontSize: 13)),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 22),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.16),
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Row(
              children: [
                Icon(Icons.mic, color: Colors.white, size: 22),
                SizedBox(width: 10),
                Expanded(
                  child: Text('动口就开做，全程不碰手机，语音带你过每一关',
                      style: TextStyle(color: Colors.white, fontSize: 13)),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // ---------- 首页闭环主区（找灵感→定计划→跟着做→记成果→看成长） ----------
  Widget _buildDashboard() {
    final loggedIn = context.watch<AuthProvider>().isLoggedIn;
    if (loggedIn && _stats.isEmpty && !_dashLoading) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadDashboard());
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (loggedIn) _buildTodayStatus() else _buildLoginCard(),
          const SizedBox(height: 14),
          _buildFlowGuide(),
          const SizedBox(height: 16),
          _buildSectionTitle('找灵感', Icons.lightbulb_outline, const Color(0xFFFF8E53)),
          _buildModuleRow([
            _QuickItem(Icons.travel_explore, '寻味 AI', const Color(0xFFE84393),
                () => _push(const XunweiPage()), enabled: loggedIn),
            _QuickItem(Icons.auto_awesome, '酿味 AI', const Color(0xFF8E5BF7),
                () => _push(const CreationHomePage()), enabled: loggedIn),
            _QuickItem(Icons.menu_book, '品味', const Color(0xFFFF8E53),
                () => _push(const RecipeListPage())),
            _QuickItem(Icons.play_circle_fill, '尝味', const Color(0xFFE74C3C),
                () => _push(const ExperiencePage())),
          ]),
          if (loggedIn) ...[
            const SizedBox(height: 14),
            _buildPlanPreview(),
          ],
          const SizedBox(height: 14),
          _buildSectionTitle('记录成果', Icons.camera_alt_outlined, const Color(0xFF2E9E6B)),
          _buildModuleRow([
            _QuickItem(Icons.photo_camera_outlined, '拍照记录', const Color(0xFF2E9E6B),
                () => _push(const HealthPhotoCheckinPage()), enabled: loggedIn),
            _QuickItem(Icons.local_fire_department, '炒菜打卡', const Color(0xFFF2B705),
                () => _push(const CheckinPage()), enabled: loggedIn),
            _QuickItem(Icons.add_a_photo_outlined, '发作品', const Color(0xFF8E5BF7),
                () => _push(const WorkPublishPage()), enabled: loggedIn),
            _QuickItem(Icons.photo_library_outlined, '我的作品', const Color(0xFF4F7CF7),
                () => _push(const WorksPage()), enabled: loggedIn),
          ]),
          const SizedBox(height: 14),
          _buildSectionTitle('成长与复盘', Icons.insights, const Color(0xFF00A6C0)),
          if (loggedIn) ...[
            _buildLevelBar(),
            const SizedBox(height: 10),
          ],
          _buildModuleRow([
            _QuickItem(Icons.emoji_events_outlined, '成就徽章', const Color(0xFFF2B705),
                () => _push(const CheckinPage()), enabled: loggedIn),
            _QuickItem(Icons.insert_chart_outlined, '月度报告', const Color(0xFF3F9B8F),
                () => _push(const ReportPage()), enabled: loggedIn),
            _QuickItem(Icons.person_outline, '我的', const Color(0xFF6C7A89),
                () => _push(const ProfilePage())),
          ]),
          const SizedBox(height: 14),
          _buildSectionTitle('更多工具', Icons.apps, const Color(0xFF6C7A89)),
          _buildMoreTools(loggedIn),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  /// 今日状态卡：连续打卡 + 热量环 + 今日计划待做（闭环「目标+进度」锚点）
  Widget _buildTodayStatus() {
    final streak = _stats['streak'] ?? 0;
    final todayCalories = _stats['todayCalories'] is num
        ? (_stats['todayCalories'] as num).toInt()
        : 0;
    final rawBudget = _stats['caloriesBudget'];
    final budget = rawBudget is num ? rawBudget.toInt() : null;
    final b = budget ?? 2000;
    final ratio = b > 0 ? (todayCalories / b).clamp(0.0, 1.0).toDouble() : 0.0;
    final over = b > 0 && todayCalories > b;
    final todayItem = _todayPlanItem();
    return Card(
      margin: EdgeInsets.zero,
      color: const Color(0xFFFFF4EC),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.local_fire_department,
                          color: Colors.deepOrange, size: 18),
                      const SizedBox(width: 4),
                      Text('连续打卡 $streak 天',
                          style: const TextStyle(
                              fontWeight: FontWeight.bold, fontSize: 14)),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    todayItem != null
                        ? '🍳 今天计划：${todayItem['recipeTitle'] ?? ''}'
                            '（${_mealLabel(todayItem['mealType'])}）'
                        : '今天没有安排，去计划一道菜吧',
                    style: const TextStyle(fontSize: 13),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    children: [
                      FilledButton.tonal(
                        onPressed: () => _push(const CheckinPage()),
                        child: const Text('去打卡'),
                      ),
                      OutlinedButton(
                        onPressed: () => _push(const PlansPage()),
                        child: const Text('看计划'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            SizedBox(
              width: 76,
              height: 76,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  SizedBox(
                    width: 70,
                    height: 70,
                    child: CircularProgressIndicator(
                      value: ratio,
                      strokeWidth: 7,
                      backgroundColor: Colors.grey.shade200,
                      valueColor: AlwaysStoppedAnimation(
                          over ? Colors.red : Colors.orange),
                    ),
                  ),
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        '$todayCalories',
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                          color: over ? Colors.red : Colors.black87,
                        ),
                      ),
                      const Text('千卡',
                          style: TextStyle(fontSize: 10, color: Colors.grey)),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 今日计划待做条目（当前周计划中，今天 weekday 未完成的）
  Map<String, dynamic>? _todayPlanItem() {
    if (_plans.isEmpty) return null;
    final plan = _plans.first as Map<String, dynamic>;
    final items = (plan['items'] as List?) ?? [];
    final todayOffset = DateTime.now().weekday - 1; // 0=周一
    for (final it in items) {
      final m = it as Map<String, dynamic>;
      final offset = m['dayOffset'] is num ? (m['dayOffset'] as num).toInt() : 0;
      if (offset == todayOffset && m['done'] != true) return m;
    }
    return null;
  }

  /// 未登录引导卡
  Widget _buildLoginCard() {
    return Card(
      margin: EdgeInsets.zero,
      color: const Color(0xFFFFF4EC),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            const Icon(Icons.lock_outline, color: Colors.deepOrange),
            const SizedBox(width: 10),
            const Expanded(
              child: Text('登录后开启做菜习惯养成：打卡 / 计划 / 成长 / 月度报告',
                  style: TextStyle(fontSize: 13)),
            ),
            FilledButton(
              onPressed: () => _push(const LoginPage()),
              child: const Text('登录'),
            ),
          ],
        ),
      ),
    );
  }

  /// 闭环流程引导条
  Widget _buildFlowGuide() {
    const steps = [
      (Icons.lightbulb_outline, '找灵感'),
      (Icons.event_note_outlined, '定计划'),
      (Icons.play_circle_outline, '跟着做'),
      (Icons.camera_alt_outlined, '记成果'),
      (Icons.insights, '看成长'),
    ];
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF1E6),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          for (var i = 0; i < steps.length; i++) ...[
            if (i > 0)
              const Expanded(
                child: Divider(color: Color(0xFFF2C4A8), thickness: 2),
              ),
            Column(
              children: [
                Icon(steps[i].$1, color: const Color(0xFFE8590C), size: 20),
                const SizedBox(height: 2),
                Text(steps[i].$2,
                    style: const TextStyle(
                        fontSize: 11, color: Color(0xFF9A5B33))),
              ],
            ),
          ],
        ],
      ),
    );
  }

  /// 分区标题
  Widget _buildSectionTitle(String title, IconData icon, Color color) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8, left: 2),
      child: Row(
        children: [
          Icon(icon, color: color, size: 18),
          const SizedBox(width: 6),
          Text(title,
              style:
                  const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }

  /// 模块入口行（3 等宽）
  Widget _buildModuleRow(List<_QuickItem> items) {
    return Row(
      children: [
        for (final it in items)
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 3),
              child: _moduleCard(it),
            ),
          ),
      ],
    );
  }

  Widget _moduleCard(_QuickItem it) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: it.enabled ? it.onTap : () => _toast('请先登录后使用'),
        child: Opacity(
          opacity: it.enabled ? 1 : 0.35,
          child: Container(
            height: 84,
            decoration: BoxDecoration(
              color: it.color.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: it.color.withValues(alpha: 0.18)),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                      color: it.color.withValues(alpha: 0.15),
                      shape: BoxShape.circle),
                  child: Icon(it.icon, color: it.color, size: 20),
                ),
                const SizedBox(height: 6),
                Text(it.label,
                    style: const TextStyle(
                        fontSize: 12, fontWeight: FontWeight.w500)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// 本周计划预览
  Widget _buildPlanPreview() {
    if (_plans.isEmpty) {
      return Card(
        margin: EdgeInsets.zero,
        child: ListTile(
          leading: const Icon(Icons.event_note_outlined,
              color: Color(0xFF3F9B8F)),
          title: const Text('还没有做饭计划'),
          subtitle: const Text('创建一份本周菜谱计划，跟着做不迷路'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => _push(const PlansPage()),
        ),
      );
    }
    final plan = _plans.first as Map<String, dynamic>;
    final items = (plan['items'] as List?) ?? [];
    final doneItems = plan['doneItems'] is num
        ? (plan['doneItems'] as num).toInt()
        : 0;
    final totalItems = plan['totalItems'] is num
        ? (plan['totalItems'] as num).toInt()
        : items.length;
    final progress = totalItems > 0 ? (doneItems / totalItems).clamp(0.0, 1.0) : 0.0;
    final undone = items
        .where((e) => (e as Map)['done'] != true)
        .take(2)
        .toList();
    return Card(
      margin: EdgeInsets.zero,
      color: const Color(0xFFF0F7F5),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => _push(const PlansPage()),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.event_note_outlined,
                      color: Color(0xFF3F9B8F), size: 18),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text('本周计划 · ${plan['title'] ?? ''}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontWeight: FontWeight.bold, fontSize: 14)),
                  ),
                  Text('$doneItems/$totalItems',
                      style:
                          const TextStyle(color: Colors.grey, fontSize: 12)),
                ],
              ),
              const SizedBox(height: 6),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(
                  value: progress.toDouble(),
                  minHeight: 5,
                  backgroundColor: Colors.grey.shade200,
                  valueColor:
                      const AlwaysStoppedAnimation(Color(0xFF3F9B8F)),
                ),
              ),
              const SizedBox(height: 8),
              if (undone.isEmpty)
                const Text('本周计划已完成 🎉', style: TextStyle(fontSize: 13))
              else
                for (final u in undone)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Text(
                      '• ${u['recipeTitle'] ?? ''}'
                      ' · ${_mealLabel(u['mealType'])}',
                      style: const TextStyle(fontSize: 13),
                    ),
                  ),
            ],
          ),
        ),
      ),
    );
  }

  /// 等级进度条
  Widget _buildLevelBar() {
    final level = _level['level'] ?? 1;
    final levelName = _level['levelName'] ?? '入门学徒';
    final totalExp = _level['totalExp'] ?? 0;
    final next = _level['nextThreshold'] ?? 100;
    final progress = ((_level['progress'] ?? 0) as num).toDouble();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFFE8F3FA),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          const Text('👨‍🍳', style: TextStyle(fontSize: 22)),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('厨艺 Lv.$level · $levelName',
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.bold)),
                const SizedBox(height: 4),
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: (progress / 100).clamp(0.0, 1.0),
                    minHeight: 6,
                    backgroundColor: Colors.grey.shade200,
                    valueColor:
                        const AlwaysStoppedAnimation(Color(0xFF00A6C0)),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Text('$totalExp / $next',
              style: const TextStyle(color: Colors.grey, fontSize: 11)),
        ],
      ),
    );
  }

  /// 更多工具（次级入口）
  Widget _buildMoreTools(bool loggedIn) {
    final tools = <_QuickItem>[
      _QuickItem(Icons.video_library_outlined, '我的视频', const Color(0xFF4F7CF7),
          () => _push(const VideoListPage())),
      _QuickItem(Icons.link, '链接下载', const Color(0xFF3F9B8F),
          () => _push(const DownloadPage())),
      _QuickItem(Icons.star_outline, '我的收藏', const Color(0xFFF2B705),
          () => _push(const FavoritesPage()), enabled: loggedIn),
    ];
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: tools
          .map((t) => ActionChip(
                avatar: Icon(t.icon,
                    color: t.enabled ? t.color : Colors.grey, size: 18),
                label: Text(t.label),
                onPressed: t.enabled ? t.onTap : () => _toast('请先登录后使用'),
              ))
          .toList(),
    );
  }

  static String _mealLabel(Object? mealType) {
    const map = {
      'breakfast': '早餐',
      'lunch': '午餐',
      'dinner': '晚餐',
      'snack': '加餐',
    };
    return map[mealType] ?? '${mealType ?? ''}';
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  // ---------- 体验视频横向卡片 ----------
  Widget _buildExperienceSection() {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 40),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (_error) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 20),
        child: Center(
          child: Column(
            children: [
              const Icon(Icons.wifi_off_outlined, color: Colors.grey, size: 40),
              const SizedBox(height: 8),
              const Text('尝味加载失败', style: TextStyle(color: Colors.grey)),
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: _loadExperience,
                icon: const Icon(Icons.refresh),
                label: const Text('重试'),
              ),
            ],
          ),
        ),
      );
    }
    if (_experience.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(
          child: Text('暂无可尝味的视频', style: TextStyle(color: Colors.grey)),
        ),
      );
    }
    return SizedBox(
      height: 168,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: _experience.length,
        itemBuilder: (context, i) {
          final v = _experience[i] as Map<String, dynamic>;
          final id = (v['id'] as num).toInt();
          final title = ((v['title'] ?? '尝味视频') as String).replaceAll(
              RegExp(r'\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$',
                  caseSensitive: false),
              '');
          final steps = (v['stepCount'] ?? 0) as int;
          return Padding(
            padding: const EdgeInsets.only(right: 12),
            child: _experienceCard(id, title, steps),
          );
        },
      ),
    );
  }

  Widget _experienceCard(int id, String title, int steps) {
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: () => _push(VideoPlayerPage(videoId: id, initialTitle: title)),
      child: Container(
        width: 200,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          gradient: LinearGradient(
            colors: [
              const Color(0xFF4F7CF7).withValues(alpha: 0.85),
              const Color(0xFF8E5BF7).withValues(alpha: 0.85),
            ],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.play_circle_fill, color: Colors.white, size: 34),
            const Spacer(),
            Text(
              title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w600,
                  fontSize: 14),
            ),
            const SizedBox(height: 4),
            Text('$steps 个步骤 · 点击播放',
                style: const TextStyle(color: Colors.white70, fontSize: 11)),
          ],
        ),
      ),
    );
  }
}

/// 首页模块入口项
class _QuickItem {
  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;
  final bool enabled;

  _QuickItem(this.icon, this.label, this.color, this.onTap,
      {this.enabled = true});
}
