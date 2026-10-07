import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import 'correction_report_page.dart';
import 'download_page.dart';
import 'video_player_page.dart';
import 'xunwei_page.dart';

const List<String> _kDayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const Map<String, String> _kMealLabels = {
  'breakfast': '早餐',
  'lunch': '午餐',
  'dinner': '晚餐',
  'snack': '加餐',
};

/// 做饭计划（Keep 式训练计划）：每周菜谱计划列表 + 创建 + 完成标记 + 跟做跳转
class PlansPage extends StatefulWidget {
  const PlansPage({super.key});

  @override
  State<PlansPage> createState() => _PlansPageState();
}

class _PlansPageState extends State<PlansPage> {
  final ApiService _api = ApiService();
  List<dynamic> _plans = [];
  bool _loading = true;
  String? _error;
  /// 折叠的计划（按菜单名/计划 id），默认全部展开
  final Set<int> _collapsedPlans = {};
  /// 计划内按星期折叠的分组（key: "${planId}:${day}"），默认全部展开
  final Set<String> _collapsedPlanDays = {};

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
      final plans = await _api.getPlans();
      if (!mounted) return;
      setState(() {
        _plans = plans;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载计划失败，请稍后重试');
        _loading = false;
      });
    }
  }

  Future<void> _openCreate() async {
    final created = await showDialog<bool>(
      context: context,
      builder: (_) => const _CreatePlanDialog(),
    );
    if (created == true) _load();
  }

  Future<void> _toggleDone(Map<String, dynamic> item, bool done) async {
    // 完成前：份量确认（仅有关联菜谱且未锁定）
    if (done &&
        item['recipeId'] is int &&
        item['portionLocked'] != true) {
      final scale = await _choosePortion(item);
      if (scale == null) return;
      try {
        await _api.setPlanItemPortion(item['id'] as int, scale);
      } catch (_) {
        // 份量调整失败不阻塞完成
      }
    }
    try {
      final res = await _api.markPlanItemDone(item['id'] as int, done);
      if (!mounted) return;
      final newBadges = (res['newBadges'] as List?) ?? [];
      final exec = res['exec'] as Map?;
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
      } else if (done) {
        final msg = exec?['autoCheckin'] == true
            ? (exec?['deduped'] == true
                ? '✅ 已跟做，+15 经验（当日该菜已有记录，未重复计热量）'
                : '✅ 已跟做，+15 经验，已自动打卡+记录营养')
            : '✅ 已跟做，+15 经验';
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(msg)));
      } else {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('已取消完成')));
      }
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('操作失败：${friendlyError(e)}')));
    }
  }

  /// 修改条目份量（v0.9：条目点击 / 份量图标共用）；无菜谱/已完成/已锁定则提示
  Future<void> _changePortion(Map<String, dynamic> item) async {
    if (item['recipeId'] is! int) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('该条目无关联菜谱，无法调整份量')));
      return;
    }
    if (item['done'] == true || item['portionLocked'] == true) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('已完成/已锁定的条目不可调整份量')));
      return;
    }
    final s = await _choosePortion(item);
    if (s == null || !mounted) return;
    try {
      await _api.setPlanItemPortion(item['id'] as int, s);
      if (mounted) _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('份量设置失败：${friendlyError(e)}')));
    }
  }

  /// 份量选择（0.5x/1x/1.5x）
  Future<double?> _choosePortion(Map<String, dynamic> item) {
    return showDialog<double>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: Text('份量确认 · ${item['recipeTitle'] ?? ''}'),
        children: [0.5, 1.0, 1.5]
            .map((s) => SimpleDialogOption(
                  onPressed: () => Navigator.of(ctx).pop(s),
                  child: Text(
                    s == 1.0 ? '正常份量（1.0x）' : '${s}x 份量',
                    style: const TextStyle(fontSize: 16),
                  ),
                ))
            .toList(),
      ),
    );
  }

  /// 操作说明弹窗（v0.9：勾选框/点击条目/各按钮含义）
  void _showHelpDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('操作说明'),
        content: const SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _HelpRow(icon: Icons.check_box, text: '勾选框：标记该菜当天是否已完成。勾选后自动打卡、记录营养（按份量折算）、+15 经验，并写入口味偏好。'),
              SizedBox(height: 10),
              _HelpRow(icon: Icons.touch_app, text: '点击条目（菜名/星期·餐次·份量区域）：修改份量（0.5x / 1.0x / 1.5x）。无关联菜谱或已完成/已锁定的条目不可调整。'),
              SizedBox(height: 10),
              _HelpRow(icon: Icons.scale, text: '秤图标：调整份量（同点击条目）。橙色表示已设非默认份量。'),
              SizedBox(height: 10),
              _HelpRow(icon: Icons.play_circle_outline, text: '跟做：获取并打开该菜的做法视频（本地/下载/寻味搜索）。'),
              SizedBox(height: 10),
              _HelpRow(icon: Icons.compare, text: '成品纠偏：跟做完成后拍一张成品照与目标对比。'),
              SizedBox(height: 10),
              _HelpRow(icon: Icons.more_vert, text: '更多：调整餐次/星期、删除条目。删除最后一条会连带删除整个计划。'),
              SizedBox(height: 10),
              _HelpRow(icon: Icons.expand_more, text: '星期标题：点击折叠/展开该天的条目；顶部计划名可折叠整个计划。'),
              SizedBox(height: 10),
              _HelpRow(icon: Icons.auto_awesome, text: 'AI 生成一周菜单：自动生成多天荤素搭配的菜单预览，勾选后写入计划。'),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('知道了')),
        ],
      ),
    );
  }

  /// AI 生成一周菜单
  Future<void> _openAiMenu() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => const _AiMenuDialog(),
    );
    if (ok == true && mounted) _load();
  }

  /// 生成购物清单
  Future<void> _generateShoppingList(Map<String, dynamic> plan) async {
    await showDialog(
      context: context,
      builder: (_) => _ShoppingListDialog(plan: plan),
    );
  }

  /// 线路 B 跟做：优先本地 videoId → 外部候选去下载 → 失败跳寻味
  Future<void> _follow(Map<String, dynamic> item) async {
    final int itemId = item['id'] as int;
    try {
      final res = await _api.attachPlanItemVideo(itemId);
      if (!mounted) return;
      final mode = res['mode'];
      if (mode == 'local' && res['videoId'] is int) {
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => VideoPlayerPage(
            videoId: res['videoId'] as int,
            initialTitle: item['recipeTitle']?.toString(),
          ),
        ));
        return;
      }
      if (res['found'] == true && mode == 'external') {
        final v = (res['video'] as Map?) ?? {};
        final url = v['url']?.toString();
        if (url != null && url.isNotEmpty) {
          Navigator.of(context).push(MaterialPageRoute(
            builder: (_) => DownloadPage(initialUrl: url),
          ));
          return;
        }
      }
      // 未找到 → 跳寻味带 initialPrompt
      Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => XunweiPage(
            initialPrompt: '${item['recipeTitle'] ?? ''} 的做法视频教程'),
      ));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('获取视频失败：${friendlyError(e)}')));
    }
  }

  /// P1 厨电调度（线路 A）：打开调度对话框，编排时间轴到点自动下发
  Future<void> _kitchenSchedule(Map<String, dynamic> item) async {
    await showDialog(
      context: context,
      builder: (_) => _KitchenScheduleDialog(item: item),
    );
  }

  Future<void> _deletePlan(Map<String, dynamic> plan) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('删除「${plan['title'] ?? ''}」？'),
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
    if (ok != true || !mounted) return;
    try {
      await _api.deletePlan(plan['id'] as int);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('已删除')));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('删除失败：${friendlyError(e)}')));
    }
  }

  /// 调整条目餐次/星期（v0.5 反馈增强）；v0.10 按计划起始日显示真实日期
  Future<void> _editItem(Map<String, dynamic> item, DateTime startDate) async {
    final edited = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => _EditItemDialog(item: item, startDate: startDate),
    );
    if (edited == null || !mounted) return;
    try {
      await _api.updatePlanItem(
        item['id'] as int,
        dayOffset: edited['dayOffset'] as int?,
        mealType: edited['mealType'] as String?,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('已调整')));
      _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('调整失败：${friendlyError(e)}')));
    }
  }

  /// 删除单条计划条目（v0.5 反馈增强）；最后一条需确认「计划将一并删除」（v0.8）
  Future<void> _deleteItem(Map<String, dynamic> item) async {
    // 找条目所属计划，判断是否最后一条
    Map<String, dynamic>? owner;
    for (final p in _plans) {
      final pMap = (p as Map).cast<String, dynamic>();
      final its = (pMap['items'] as List?) ?? [];
      if (its.any((it) => (it as Map)['id'] == item['id'])) {
        owner = pMap;
        break;
      }
    }
    final isLast =
        owner != null && ((owner['items'] as List?)?.length ?? 0) <= 1;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(isLast
            ? '删除最后一条「${item['recipeTitle'] ?? ''}」？'
            : '删除「${item['recipeTitle'] ?? ''}」？'),
        content: isLast
            ? const Text('这是该计划的最后一条，删除后计划将一并删除。')
            : null,
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: Text(isLast ? '删除并移除计划' : '删除')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      final res = await _api.deletePlanItem(item['id'] as int);
      if (!mounted) return;
      final planDeleted = res['planDeleted'] == true;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(planDeleted ? '已删除最后一条，计划已一并移除' : '已删除')));
      _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('删除失败：${friendlyError(e)}')));
    }
  }

  /// 折叠/展开单个计划（按菜单名）
  void _togglePlanCollapse(Map<String, dynamic> plan) {
    final id = plan['id'];
    if (id is! int) return;
    setState(() {
      if (!_collapsedPlans.remove(id)) _collapsedPlans.add(id);
    });
  }

  /// 折叠/展开计划内的星期分组
  void _togglePlanDay(int planId, int day) {
    final key = '$planId:$day';
    setState(() {
      if (!_collapsedPlanDays.remove(key)) _collapsedPlanDays.add(key);
    });
  }

  /// 计划起始日解析（后端 startDate 形如 2026-08-28；失败回退今天）
  DateTime _planStartDate(Map<String, dynamic> plan) {
    final s = plan['startDate'];
    if (s is String && s.isNotEmpty) {
      final p = s.split('-');
      if (p.length == 3) {
        final y = int.tryParse(p[0]);
        final m = int.tryParse(p[1]);
        final d = int.tryParse(p[2]);
        if (y != null && m != null && d != null) return DateTime(y, m, d);
      }
    }
    return DateTime.now();
  }

  /// 计划内条目按星期分组（仿 AI 菜单预览），返回各天折叠区块
  List<Widget> _buildPlanDaySections(Map<String, dynamic> plan, List items) {
    final byDay = <int, List<dynamic>>{};
    for (final it in items) {
      final day = it['dayOffset'] is num ? (it['dayOffset'] as num).toInt() : 0;
      byDay.putIfAbsent(day, () => []).add(it);
    }
    final dayKeys = byDay.keys.toList()..sort();
    return [
      for (final day in dayKeys) _buildPlanDaySection(plan, day, byDay[day]!),
    ];
  }

  /// 计划内单天分组（可折叠/展开），点击星期标题切换，仿 AI 菜单预览 _buildDaySection
  Widget _buildPlanDaySection(
      Map<String, dynamic> plan, int day, List<dynamic> dayItems) {
    final rawId = plan['id'];
    final planId = rawId is int ? rawId : -1;
    final date = _planStartDate(plan).add(Duration(days: day));
    final dayLabel =
        '${date.month}月${date.day}日 ${_kDayLabels[date.weekday - 1]}';
    final doneInDay = dayItems.where((it) => it['done'] == true).length;
    final expanded = !_collapsedPlanDays.contains('$planId:$day');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          onTap: () => _togglePlanDay(planId, day),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(
              children: [
                Icon(
                  expanded ? Icons.expand_more : Icons.chevron_right,
                  size: 20,
                  color: Colors.deepOrange,
                ),
                const SizedBox(width: 4),
                Text(dayLabel,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w600)),
                const Spacer(),
                Text('$doneInDay/${dayItems.length} 完成',
                    style: const TextStyle(fontSize: 12, color: Colors.grey)),
              ],
            ),
          ),
        ),
        if (expanded)
          Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: Colors.grey.shade50,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(
              children: dayItems
                  .map((it) => _itemTile(it, _planStartDate(plan)))
                  .toList(),
            ),
          ),
      ],
    );
  }

  /// M2：跟做完成后可「成品纠偏」——拍一张成品照与目标对比
  void _goCorrection(Map<String, dynamic> item) {
    final videoId = item['videoId'];
    if (videoId is! int) return;
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => CorrectionReportPage(
        videoId: videoId,
        title: item['recipeTitle']?.toString(),
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('做饭计划'),
        actions: [
          IconButton(
            icon: const Icon(Icons.help_outline),
            tooltip: '操作说明',
            onPressed: _showHelpDialog,
          ),
          IconButton(
            icon: const Icon(Icons.auto_awesome),
            tooltip: 'AI 生成一周菜单',
            onPressed: _openAiMenu,
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _openCreate,
        icon: const Icon(Icons.playlist_add),
        label: const Text('创建计划'),
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
    if (_plans.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.event_note, size: 64, color: Colors.grey),
            const SizedBox(height: 12),
            const Text('还没有做饭计划，创建一份本周菜谱计划吧'),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _openCreate,
              icon: const Icon(Icons.playlist_add),
              label: const Text('创建计划'),
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 80),
        children: _plans.map((p) => _planCard(p)).toList(),
      ),
    );
  }

  Widget _planCard(Map<String, dynamic> plan) {
    final items = (plan['items'] as List?) ?? [];
    final doneItems = plan['doneItems'] is num
        ? (plan['doneItems'] as num).toInt()
        : 0;
    final totalItems = plan['totalItems'] is num
        ? (plan['totalItems'] as num).toInt()
        : items.length;
    final progress =
        totalItems > 0 ? (doneItems / totalItems).clamp(0.0, 1.0) : 0.0;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            InkWell(
              onTap: () => _togglePlanCollapse(plan),
              borderRadius: BorderRadius.circular(8),
              child: Row(
                children: [
                  Icon(
                    _collapsedPlans.contains(plan['id'])
                        ? Icons.chevron_right
                        : Icons.expand_more,
                    size: 22,
                    color: Colors.deepOrange,
                  ),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text('${plan['title'] ?? '本周计划'}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.bold)),
                  ),
                  Text('$doneItems/$totalItems 完成',
                      style:
                          const TextStyle(color: Colors.grey, fontSize: 12)),
                  IconButton(
                    icon: const Icon(Icons.delete_outline,
                        size: 20, color: Colors.grey),
                    onPressed: () => _deletePlan(plan),
                  ),
                ],
              ),
            ),
            if (!_collapsedPlans.contains(plan['id'])) ...[
              const SizedBox(height: 4),
              Text('起始 ${plan['startDate'] ?? ''}'
                  '${plan['generatedBy'] == 'ai' ? ' · AI 生成' : ''}',
                  style: const TextStyle(color: Colors.grey, fontSize: 12)),
              const SizedBox(height: 8),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(
                  value: progress.toDouble(),
                  minHeight: 6,
                  backgroundColor: Colors.grey.shade200,
                  valueColor: const AlwaysStoppedAnimation(Colors.deepOrange),
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  const Spacer(),
                  OutlinedButton.icon(
                    onPressed: () => _generateShoppingList(plan),
                    icon: const Icon(Icons.shopping_cart_outlined, size: 16),
                    label: const Text('生成购物清单'),
                    style: OutlinedButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      padding: const EdgeInsets.symmetric(horizontal: 10),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              ..._buildPlanDaySections(plan, items),
            ],
          ],
        ),
      ),
    );
  }

  /// 计划条目（v0.6 仿 AI 菜单预览排版：加宽行/菜名2行/荤素徽标/副信息行 + 底部操作行）
  /// v0.10：星期标签按「计划起始日 + dayOffset」真实日期计算，与分组标题一致（AI 菜单起始日非周一）
  Widget _itemTile(Map<String, dynamic> item, DateTime startDate) {
    final done = item['done'] == true;
    final day = item['dayOffset'] is num
        ? (item['dayOffset'] as num).toInt()
        : 0;
    final itemDate = startDate.add(Duration(days: day));
    final dayLabel =
        '${itemDate.month}月${itemDate.day}日 ${_kDayLabels[itemDate.weekday - 1]}';
    final mealLabel = _kMealLabels[item['mealType']] ?? item['mealType'];
    final dt = item['dishType'] == 'meat'
        ? '荤'
        : (item['dishType'] == 'veg' ? '素' : null);
    final scale = item['portionScale'] is num
        ? (item['portionScale'] as num).toDouble()
        : 1.0;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // v1.4：勾选按钮缩小触控区域（默认 48px 过大）
          Checkbox(
            value: done,
            onChanged: (v) => _toggleDone(item, v ?? false),
            visualDensity: VisualDensity.compact,
            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
          const SizedBox(width: 2),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 点击条目修改份量（v0.9）：标题 + 副信息区可点
                InkWell(
                  onTap: () => _changePortion(item),
                  borderRadius: BorderRadius.circular(6),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (dt != null)
                              Container(
                                margin: const EdgeInsets.only(right: 6, top: 1),
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 6, vertical: 1),
                                decoration: BoxDecoration(
                                  color: dt == '荤'
                                      ? const Color(0xFFFFE0B2)
                                      : const Color(0xFFC8E6C9),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Text(dt,
                                    style: TextStyle(
                                        fontSize: 11,
                                        color: dt == '荤'
                                            ? const Color(0xFFE65100)
                                            : const Color(0xFF2E7D32))),
                              ),
                            Flexible(
                              child: Text(
                                '${item['recipeTitle'] ?? '家常菜'}',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                    decoration: done
                                        ? TextDecoration.lineThrough
                                        : null,
                                    color:
                                        done ? Colors.grey : Colors.black87),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '$dayLabel · $mealLabel'
                          ' · ${scale.toStringAsFixed(1)}份',
                          style: const TextStyle(
                              fontSize: 12, color: Colors.black54),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 4),
                // v1.4：操作行改 Wrap 自动换行，按钮多时「⋯」不再被挤出屏幕
                Wrap(
                  alignment: WrapAlignment.end,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: 2,
                  runSpacing: 0,
                  children: [
                    if (item['videoId'] is int)
                      IconButton(
                        icon: const Icon(Icons.compare, size: 18),
                        tooltip: '成品纠偏',
                        visualDensity: VisualDensity.compact,
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(),
                        color: const Color(0xFF2E9E6B),
                        onPressed: () => _goCorrection(item),
                      ),
                    if (item['recipeId'] is int && !done)
                      IconButton(
                        icon: Icon(
                          Icons.scale,
                          size: 18,
                          color: scale != 1.0
                              ? const Color(0xFFE65100)
                              : Colors.grey,
                        ),
                        tooltip: '调整份量',
                        visualDensity: VisualDensity.compact,
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(),
                        onPressed: () => _changePortion(item),
                      ),
                    if (item['recipeId'] is int && !done)
                      TextButton.icon(
                        onPressed: () => _kitchenSchedule(item),
                        icon: const Icon(Icons.kitchen_outlined, size: 16),
                        label: const Text('厨电'),
                        style: TextButton.styleFrom(
                            visualDensity: VisualDensity.compact,
                            minimumSize: const Size(0, 32),
                            padding:
                                const EdgeInsets.symmetric(horizontal: 6)),
                      ),
                    TextButton.icon(
                      onPressed: () => _follow(item),
                      icon: const Icon(Icons.play_circle_outline, size: 16),
                      label: const Text('跟做'),
                      style: TextButton.styleFrom(
                          visualDensity: VisualDensity.compact,
                          minimumSize: const Size(0, 32),
                          padding:
                              const EdgeInsets.symmetric(horizontal: 6)),
                    ),
                    PopupMenuButton<String>(
                      icon: const Icon(Icons.more_vert, size: 18),
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints(),
                      tooltip: '更多',
                      onSelected: (v) {
                        if (v == 'edit') _editItem(item, startDate);
                        if (v == 'delete') _deleteItem(item);
                      },
                      itemBuilder: (_) => const [
                        PopupMenuItem(
                            value: 'edit',
                            child: ListTile(
                                contentPadding: EdgeInsets.zero,
                                leading: Icon(Icons.edit_calendar, size: 18),
                                title: Text('调整餐次/星期'))),
                        PopupMenuItem(
                            value: 'delete',
                            child: ListTile(
                                contentPadding: EdgeInsets.zero,
                                leading: Icon(Icons.delete_outline, size: 18),
                                title: Text('删除条目'))),
                      ],
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 操作说明行（图标 + 文本）
class _HelpRow extends StatelessWidget {
  const _HelpRow({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 18, color: Colors.deepOrange),
        const SizedBox(width: 8),
        Expanded(
          child: Text(text, style: const TextStyle(fontSize: 13, height: 1.4)),
        ),
      ],
    );
  }
}

/// 从菜谱挑选对话框（购物车式多选，v1.2）：加载菜谱列表 → 勾选 → 确定返回选中菜谱
class _RecipePickerDialog extends StatefulWidget {
  const _RecipePickerDialog();

  @override
  State<_RecipePickerDialog> createState() => _RecipePickerDialogState();
}

class _RecipePickerDialogState extends State<_RecipePickerDialog> {
  final ApiService _api = ApiService();
  bool _loading = true;
  String? _error;
  List<dynamic> _recipes = [];
  final Set<int> _selected = {};
  String _query = '';
  /// 下拉筛选（v1.3）：null=全部，否则匹配对应字段
  /// v1.5：菜系→类型（recipe_type：工业菜谱/家常菜），因 cuisine 数据 99% 为空无区分度
  String? _type;
  String? _taste;
  String? _source;

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
      final recipes = await _api.getRecipes();
      if (!mounted) return;
      setState(() {
        _recipes = recipes;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '菜谱加载失败');
        _loading = false;
      });
    }
  }

  /// 从已加载数据提取某字段的唯一非空值（供下拉筛选）
  List<String> _options(String key) {
    final s = <String>{};
    for (final r in _recipes) {
      final v = r[key]?.toString().trim();
      if (v != null && v.isNotEmpty && v != 'null') s.add(v);
    }
    final list = s.toList()..sort();
    return list;
  }

  String _displayLabel(String? v) {
    if (v == null || v.isEmpty) return '全部';
    return switch (v) {
      'breakfast' => '早餐',
      'lunch' => '午餐',
      'dinner' => '晚餐',
      // v1.5：来源中文化 + 类型中文
      'ARTICLE_LINK' => '图文菜谱',
      'VIDEO_LINK' => '视频菜谱',
      'AI_CREATE' => 'AI 创作',
      'USER' => '用户自制',
      'INDUSTRIAL' => '工业菜谱',
      'HOME' => '家常菜谱',
      _ => v,
    };
  }

  List<dynamic> get _filtered {
    final q = _query.trim().toLowerCase();
    return _recipes.where((r) {
      if (_type != null &&
          (r['recipeType']?.toString() ?? '') != _type) {
        return false;
      }
      if (_taste != null &&
          !(r['tasteTags']?.toString() ?? '').contains(_taste!)) {
        return false;
      }
      if (_source != null && (r['source']?.toString() ?? '') != _source) {
        return false;
      }
      if (q.isEmpty) return true;
      final title = (r['title']?.toString() ?? '').toLowerCase();
      final main = (r['mainIngredients']?.toString() ?? '').toLowerCase();
      return title.contains(q) || main.contains(q);
    }).toList();
  }

  /// 主料摘要（mainIngredients 可能是逗号分隔字符串或 List）
  String _mainSummary(dynamic v) {
    if (v == null) return '';
    if (v is List) {
      return v.take(3).join('、');
    }
    final s = v.toString();
    final parts = s.split(',').where((x) => x.trim().isNotEmpty).toList();
    return parts.take(3).join('、');
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      // v1.6：显式白底，避免真机深色模式下背景被反色/覆盖导致文字看不见
      backgroundColor: Colors.white,
      title: Row(
        children: [
          const Expanded(
              child: Text('从菜谱挑选',
                  style: TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.w600,
                      color: Color(0xFF212121)))),
          TextButton(
            onPressed: _recipes.isEmpty
                ? null
                : () => setState(() {
                      final all = _filtered
                          .where((r) => r['id'] is num)
                          .map((r) => (r['id'] as num).toInt())
                          .toSet();
                      // 全选/反选
                      final allSelected = all.isNotEmpty && all.every(_selected.contains);
                      if (allSelected) {
                        _selected.removeAll(all);
                      } else {
                        _selected.addAll(all);
                      }
                    }),
            child: Text(_selected.isEmpty ? '全选' : '清除',
                style: const TextStyle(
                    color: Color(0xFFE74C3C), fontSize: 14)),
          ),
        ],
      ),
      content: SizedBox(
        width: 420,
        height: 520,
        child: _buildContent(),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消',
                style: TextStyle(color: Color(0xFF616161), fontSize: 14))),
        FilledButton(
          style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFFE74C3C)),
          onPressed: _selected.isEmpty
              ? null
              : () {
                  final picked = _recipes
                      .where((r) =>
                          r['id'] is num && _selected.contains((r['id'] as num).toInt()))
                      .map((r) => Map<String, dynamic>.from(r as Map))
                      .toList();
                  Navigator.of(context).pop(picked);
                },
          child: Text('加入计划（${_selected.length}）'),
        ),
      ],
    );
  }

  Widget _buildContent() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(_error!, textAlign: TextAlign.center,
              style: const TextStyle(color: Color(0xFF212121))),
          const SizedBox(height: 8),
          FilledButton(onPressed: _load, child: const Text('重试')),
        ],
      );
    }
    final list = _filtered;
    return Column(
      children: [
        TextField(
          onChanged: (v) => setState(() => _query = v),
          // v1.5：缩小搜索框字体；v1.6：强制深色文字（真机深色模式白字看不见）
          style: const TextStyle(fontSize: 13, color: Color(0xFF212121)),
          decoration: const InputDecoration(
            isDense: true,
            prefixIcon: Icon(Icons.search, size: 18, color: Color(0xFF212121)),
            hintText: '搜索菜名 / 主料',
            hintStyle: TextStyle(fontSize: 12, color: Color(0xFF9E9E9E)),
            border: OutlineInputBorder(),
            contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 6),
          ),
        ),
        const SizedBox(height: 8),
        // v1.4：下拉筛选改两行布局（菜系+口味 / 来源），避免挤在一行溢出
        // v1.5：菜系改为「类型」（recipe_type），因 cuisine 数据几乎全为空无区分度；来源中文化
        Row(
          children: [
            Expanded(
              child: _filterDropdown(
                label: '类型',
                value: _type,
                options: _options('recipeType'),
                onChanged: (v) => setState(() => _type = v),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: _filterDropdown(
                label: '口味',
                value: _taste,
                options: _options('tasteTags'),
                onChanged: (v) => setState(() => _taste = v),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: _filterDropdown(
                label: '来源',
                value: _source,
                options: _options('source'),
                onChanged: (v) => setState(() => _source = v),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Expanded(
          child: list.isEmpty
              ? const Center(
                  child: Text('没有可挑选的菜谱',
                      style: TextStyle(color: Color(0xFF757575))))
              : ListView.builder(
                  itemCount: list.length,
                  itemBuilder: (ctx, i) {
                    final r = list[i];
                    final id = r['id'] is num ? (r['id'] as num).toInt() : -1;
                    final checked = _selected.contains(id);
                    return CheckboxListTile(
                      dense: true,
                      value: checked,
                      contentPadding: EdgeInsets.zero,
                      // v1.4：缩小触控区域，减少行高
                      visualDensity: VisualDensity.compact,
                      materialTapTargetSize:
                          MaterialTapTargetSize.shrinkWrap,
                      controlAffinity: ListTileControlAffinity.leading,
                      // v1.6：标题强制深色（真机深色模式白字看不见）
                      title: Text('${r['title'] ?? '家常菜'}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 14, color: Color(0xFF212121))),
                      subtitle: Text(
                        [
                          _mainSummary(r['mainIngredients']),
                          if (r['calories'] is num) '${r['calories']}千卡',
                          // v1.5：副信息菜系改为类型中文化（cuisine 数据几乎全空）
                          if (r['recipeType']?.toString() != null &&
                              r['recipeType'].toString().isNotEmpty)
                            _displayLabel(r['recipeType'].toString()),
                        ].where((s) => s.isNotEmpty).join(' · '),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        // v1.6：副标题强制深灰（真机深色模式白字看不见）
                        style: const TextStyle(
                            fontSize: 11, color: Color(0xFF616161)),
                      ),
                      onChanged: id < 0
                          ? null
                          : (v) => setState(() {
                                if (v == true) {
                                  _selected.add(id);
                                } else {
                                  _selected.remove(id);
                                }
                              }),
                    );
                  },
                ),
        ),
      ],
    );
  }

  Widget _filterDropdown({
    required String label,
    required String? value,
    required List<String> options,
    required ValueChanged<String?> onChanged,
  }) {
    return InputDecorator(
      decoration: InputDecoration(
        isDense: true,
        labelText: label,
        // v1.5：缩小标签字体；v1.6：强制深色标签（真机深色模式白字看不见）
        labelStyle: const TextStyle(fontSize: 12, color: Color(0xFF212121)),
        border: const OutlineInputBorder(),
        contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      ),
      child: DropdownButtonHideUnderline(
        child: Theme(
          // v1.6：下拉弹出菜单强制白底（系统深色模式下菜单文字/背景不再被覆盖）
          data: Theme.of(context).copyWith(canvasColor: Colors.white),
          child: DropdownButton<String>(
            value: value ?? '',
            isExpanded: true,
            isDense: true,
            // v1.5：缩小已选项字体；v1.6：强制深色文字
            style: const TextStyle(fontSize: 13, color: Color(0xFF212121)),
            items: [
              const DropdownMenuItem(
                  value: '',
                  child: Text('全部',
                      style:
                          TextStyle(fontSize: 13, color: Color(0xFF212121)))),
              ...options.take(20).map((o) => DropdownMenuItem(
                  value: o,
                  child: Text(_displayLabel(o),
                      style: const TextStyle(
                          fontSize: 13, color: Color(0xFF212121))))),
            ],
            onChanged: (v) => onChanged(v == '' ? null : v),
          ),
        ),
      ),
    );
  }
}

/// 创建计划对话框：标题 + 可增删的条目行（星期/菜名/餐次）
class _CreatePlanDialog extends StatefulWidget {
  const _CreatePlanDialog();

  @override
  State<_CreatePlanDialog> createState() => _CreatePlanDialogState();
}

class _CreatePlanDialogState extends State<_CreatePlanDialog> {
  final TextEditingController _titleCtrl =
      TextEditingController(text: '本周计划');
  final List<_PlanRow> _rows = [_PlanRow()];
  bool _submitting = false;

  @override
  void dispose() {
    _titleCtrl.dispose();
    for (final r in _rows) {
      r.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    final items = <Map<String, dynamic>>[];
    for (final r in _rows) {
      final name = r.titleCtrl.text.trim();
      if (name.isEmpty) continue;
      items.add({
        'dayOffset': r.dayOffset,
        'recipeTitle': name,
        'mealType': r.mealType,
        if (r.recipeId != null) 'recipeId': r.recipeId,
      });
    }
    if (items.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请至少填写一道菜')));
      return;
    }
    setState(() => _submitting = true);
    try {
      final title =
          _titleCtrl.text.trim().isEmpty ? '本周计划' : _titleCtrl.text.trim();
      await ApiService().createPlan(title, items);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('创建失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  /// 从菜谱挑选（购物车式多选）：选中菜谱 → 加入计划行（带 recipeId，默认今天晚餐）
  Future<void> _pickRecipes() async {
    final picked = await showDialog<List<Map<String, dynamic>>>(
      context: context,
      builder: (_) => const _RecipePickerDialog(),
    );
    if (picked == null || picked.isEmpty || !mounted) return;
    setState(() {
      for (final r in picked) {
        final row = _PlanRow()
          ..dayOffset = 0
          ..mealType = 'dinner'
          ..recipeId = r['id'] is num ? (r['id'] as num).toInt() : null;
        row.titleCtrl.text = r['title']?.toString() ?? '家常菜';
        _rows.add(row);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('创建做饭计划'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _titleCtrl,
              decoration: const InputDecoration(
                  labelText: '计划标题', hintText: '如：本周计划'),
            ),
            const SizedBox(height: 8),
            ..._rows.asMap().entries.map((e) => _rowWidget(e.key, e.value)),
            const SizedBox(height: 4),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () => setState(() => _rows.add(_PlanRow())),
                icon: const Icon(Icons.add),
                label: const Text('手动添加一道菜'),
              ),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: _pickRecipes,
                icon: const Icon(Icons.playlist_add_check),
                label: const Text('从菜谱挑选（购物车式多选）'),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消')),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          child: Text(_submitting ? '创建中…' : '创建'),
        ),
      ],
    );
  }

  Widget _rowWidget(int index, _PlanRow r) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: DropdownButton<int>(
                  value: r.dayOffset,
                  isExpanded: true,
                  items: [
                    for (var d = 0; d < 7; d++)
                      DropdownMenuItem(value: d, child: Text(_kDayLabels[d])),
                  ],
                  onChanged: (v) => setState(() => r.dayOffset = v ?? 0),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: DropdownButton<String>(
                  value: r.mealType,
                  isExpanded: true,
                  items: _kMealLabels.entries
                      .map((e) =>
                          DropdownMenuItem(value: e.key, child: Text(e.value)))
                      .toList(),
                  onChanged: (v) => setState(() => r.mealType = v ?? 'dinner'),
                ),
              ),
              IconButton(
                icon: const Icon(Icons.close, size: 18),
                onPressed: _rows.length > 1
                    ? () => setState(() => _rows.removeAt(index))
                    : null,
              ),
            ],
          ),
          const SizedBox(height: 4),
          TextField(
            controller: r.titleCtrl,
            decoration: const InputDecoration(
              hintText: '菜名（必填）',
              isDense: true,
              border: OutlineInputBorder(),
            ),
          ),
        ],
      ),
    );
  }
}

class _PlanRow {
  int dayOffset = 0;
  String mealType = 'dinner';
  int? recipeId; // 从菜谱挑选带入（可为空 = 手动输入菜名）
  final TextEditingController titleCtrl = TextEditingController();

  void dispose() => titleCtrl.dispose();
}

/// AI 生成一周菜单对话框：选项 → 生成预览 → 确认写入计划 / 放弃
class _AiMenuDialog extends StatefulWidget {
  const _AiMenuDialog();

  @override
  State<_AiMenuDialog> createState() => _AiMenuDialogState();
}

class _AiMenuDialogState extends State<_AiMenuDialog> {
  final ApiService _api = ApiService();
  final TextEditingController _titleCtrl =
      TextEditingController(text: 'AI 周菜单');
  int _startOffset = 0; // 0=今天 1=明天 2=后天
  int _days = 7;
  bool _favorites = true;
  bool _respectTaboos = true;
  bool _respectMeds = true;
  String _pattern = '1meat1veg';
  final List<String> _meals = ['dinner'];
  bool _generating = false;
  Map<String, dynamic>? _menu;
  String? _error;
  final Set<Object> _selected = {};
  /// 展开的星期（day_offset）集合，默认全部展开
  final Set<int> _expandedDays = {};

  static const _dayOptions = [1, 2, 3, 5, 7];

  @override
  void dispose() {
    _titleCtrl.dispose();
    super.dispose();
  }

  static const _patterns = [
    ('1meat1veg', '一荤一素'),
    ('1meat2veg', '一荤两素'),
    ('2meat2veg', '两荤两素'),
  ];
  static const _mealOptions = [
    ('breakfast', '早餐'),
    ('lunch', '午餐'),
    ('dinner', '晚餐'),
  ];

  Future<void> _generate() async {
    setState(() {
      _generating = true;
      _error = null;
      _selected.clear();
    });
    try {
      final data = await _api.generateMenu(
        generateMode: _favorites ? 'favorites' : 'random',
        respectTaboos: _respectTaboos,
        respectMedications: _respectMeds,
        pattern: _pattern,
        meals: _meals,
        title: _titleCtrl.text.trim().isEmpty
            ? 'AI 周菜单'
            : _titleCtrl.text.trim(),
        startDate: _startDateStr(),
        days: _days,
      );
      if (!mounted) return;
      setState(() {
        _menu = data;
        _generating = false;
        // 默认全选 + 默认展开所有星期
        _expandedDays.clear();
        for (final it in ((data['items'] as List?) ?? [])) {
          if (it['recipe_id'] is num) _selected.add(it['recipe_id']);
          if (it['day_offset'] is num) {
            _expandedDays.add((it['day_offset'] as num).toInt());
          }
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '菜单生成失败，请稍后重试');
        _generating = false;
      });
    }
  }

  Future<void> _confirm() async {
    final menuId = (_menu?['menuId'] as num?)?.toInt();
    if (menuId == null) return;
    if (_selected.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('请至少勾选一道菜')));
      return;
    }
    try {
      final ids = _selected.whereType<num>().map((e) => e.toInt()).toList();
      await _api.confirmMenu(menuId, selectedRecipeIds: ids);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('确认失败：${friendlyError(e)}')));
    }
  }

  Future<void> _discard() async {
    final menuId = (_menu?['menuId'] as num?)?.toInt();
    if (menuId != null) {
      try {
        await _api.discardMenu(menuId);
      } catch (_) {}
    }
    if (!mounted) return;
    Navigator.of(context).pop(false);
  }

  DateTime _startDate() => DateTime.now().add(Duration(days: _startOffset));

  String _startDateStr() {
    final d = _startDate();
    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }

  void _selectAll() {
    setState(() {
      _selected.clear();
      for (final it in ((_menu?['items'] as List?) ?? [])) {
        if (it['recipe_id'] is num) _selected.add(it['recipe_id']);
      }
    });
  }

  void _invert() {
    setState(() {
      for (final it in ((_menu?['items'] as List?) ?? [])) {
        final id = it['recipe_id'];
        if (id is num) {
          if (!_selected.remove(id)) _selected.add(id);
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final menu = _menu;
    return AlertDialog(
      title: Text(menu == null ? 'AI 生成菜单' : '菜单预览'),
      content: SizedBox(
        width: 400,
        child: menu == null ? _buildOptions() : _buildPreview(menu),
      ),
      actions: [
        TextButton(onPressed: _discard, child: const Text('取消')),
        if (menu != null)
          TextButton(
              onPressed: _generating ? null : _discard,
              child: const Text('放弃')),
        if (menu != null)
          FilledButton(
              onPressed: _generating ? null : _confirm,
              child: Text('确认写入（${_selected.length} 道）')),
      ],
    );
  }

  Widget _buildOptions() {
    return SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('根据健康档案与口味/忌口画像，生成未来几天的做菜计划。',
              style: TextStyle(fontSize: 13, color: Colors.black54)),
          const SizedBox(height: 12),
          TextField(
            controller: _titleCtrl,
            decoration: const InputDecoration(
                labelText: '计划标题', hintText: '如：AI 周菜单'),
          ),
          const SizedBox(height: 12),
          const Text('起始日',
              style:
                  TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Wrap(
            spacing: 8,
            children: [0, 1, 2].map((o) {
              final label = switch (o) {
                0 => '今天',
                1 => '明天',
                2 => '后天',
                _ => '',
              };
              return ChoiceChip(
                label: Text(label),
                selected: _startOffset == o,
                onSelected: (_) => setState(() => _startOffset = o),
              );
            }).toList(),
          ),
          const SizedBox(height: 12),
          const Text('菜单天数',
              style:
                  TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Wrap(
            spacing: 8,
            children: _dayOptions.map((d) {
              return ChoiceChip(
                label: Text('$d 天'),
                selected: _days == d,
                onSelected: (_) => setState(() => _days = d),
              );
            }).toList(),
          ),
          const SizedBox(height: 12),
          const Text('每餐荤素搭配',
              style:
                  TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Wrap(
            spacing: 8,
            children: _patterns.map((p) {
              return ChoiceChip(
                label: Text(p.$2),
                selected: _pattern == p.$1,
                onSelected: (_) => setState(() => _pattern = p.$1),
              );
            }).toList(),
          ),
          const SizedBox(height: 12),
          const Text('覆盖餐次（可多选）',
              style:
                  TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Wrap(
            spacing: 8,
            children: _mealOptions.map((m) {
              return FilterChip(
                label: Text(m.$2),
                selected: _meals.contains(m.$1),
                onSelected: (v) => setState(() {
                  if (v) {
                    if (!_meals.contains(m.$1)) _meals.add(m.$1);
                  } else {
                    _meals.remove(m.$1);
                  }
                  if (_meals.isEmpty) _meals.add('dinner');
                }),
              );
            }).toList(),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('收藏/做过优先'),
            subtitle:
                const Text('从已收藏、常做的菜优先挑选', style: TextStyle(fontSize: 12)),
            value: _favorites,
            onChanged: (v) => setState(() => _favorites = v),
          ),
          SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('遵循口忌'),
            subtitle: const Text('拦截忌口/过敏原菜品', style: TextStyle(fontSize: 12)),
            value: _respectTaboos,
            onChanged: (v) => setState(() => _respectTaboos = v),
          ),
          SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('遵循用药'),
            subtitle: const Text('拦截与用药冲突的菜品', style: TextStyle(fontSize: 12)),
            value: _respectMeds,
            onChanged: (v) => setState(() => _respectMeds = v),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(_error!,
                  style: const TextStyle(color: Colors.red, fontSize: 13)),
            ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _generating ? null : _generate,
              icon: _generating
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.auto_awesome),
              label: Text(_generating ? '生成中（约 30 秒）…' : '开始生成'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPreview(Map<String, dynamic> menu) {
    final items = (menu['items'] as List?) ?? [];
    final excluded = (menu['excluded'] as List?) ?? [];
    final missing = (menu['missingDishes'] as List?) ?? [];
    final remaining = menu['remainingToday'];

    // 按星期分组
    final Map<int, List<dynamic>> byDay = {};
    for (final it in items) {
      final day =
          it['day_offset'] is num ? (it['day_offset'] as num).toInt() : 0;
      byDay.putIfAbsent(day, () => []).add(it);
    }
    final dayKeys = byDay.keys.toList()..sort();

    return SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${menu['title'] ?? 'AI 周菜单'} · 从 ${menu['startDate'] ?? _startDateStr()} 起 ${menu['days'] ?? _days} 天',
            style: const TextStyle(fontSize: 12, color: Colors.black54),
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              if (remaining is num)
                Text('今日还可生成 $remaining 次',
                    style: const TextStyle(fontSize: 12, color: Colors.grey))
              else
                const Text('勾选要写入计划的菜品',
                    style: TextStyle(fontSize: 12, color: Colors.grey)),
              const Spacer(),
              Text('已选 ${_selected.length}/${items.length}',
                  style:
                      const TextStyle(fontSize: 12, color: Colors.deepOrange)),
            ],
          ),
          Row(
            children: [
              TextButton(onPressed: _selectAll, child: const Text('全选')),
              TextButton(onPressed: _invert, child: const Text('反选')),
              const Spacer(),
            ],
          ),
          const Divider(height: 1),
          ...dayKeys.map((day) => _buildDaySection(day, byDay[day]!)),
          const Divider(height: 1),
          if (excluded.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text('已排除 ${excluded.length} 道（口忌/用药/幻觉菜名）',
                  style: TextStyle(
                      fontSize: 12, color: Colors.orange.shade800)),
            ),
          if (missing.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text('候选库未收录：${missing.join('、')}',
                  style: const TextStyle(fontSize: 12, color: Colors.grey)),
            ),
        ],
      ),
    );
  }

  /// 单天分组（可折叠/展开），点击星期标题切换
  Widget _buildDaySection(int day, List<dynamic> dayItems) {
    final date = _startDate().add(Duration(days: day));
    final dayLabel =
        '${date.month}月${date.day}日 ${_kDayLabels[date.weekday - 1]}';
    final selectedInDay = dayItems
        .where((it) => it['recipe_id'] is num && _selected.contains(it['recipe_id']))
        .length;
    final expanded = _expandedDays.contains(day);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          onTap: () => setState(() {
            if (!_expandedDays.remove(day)) _expandedDays.add(day);
          }),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Row(
              children: [
                Icon(
                  expanded ? Icons.expand_more : Icons.chevron_right,
                  size: 20,
                  color: Colors.deepOrange,
                ),
                const SizedBox(width: 4),
                Text(dayLabel,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w600)),
                const Spacer(),
                Text('已选 $selectedInDay/${dayItems.length}',
                    style: const TextStyle(fontSize: 12, color: Colors.grey)),
              ],
            ),
          ),
        ),
        if (expanded)
          Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: Colors.grey.shade50,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(
              children:
                  dayItems.map((it) => _buildPreviewItem(it)).toList(),
            ),
          ),
      ],
    );
  }

  /// 预览单道菜（加宽排版：菜名/荤素徽标 + 餐次/热量/备注）
  Widget _buildPreviewItem(Map<dynamic, dynamic> it) {
    final id = it['recipe_id'];
    final nut = (it['nutrition'] as Map?) ?? {};
    final dt = it['dish_type'] == 'meat'
        ? '荤'
        : (it['dish_type'] == 'veg' ? '素' : null);
    final note = it['note']?.toString();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Checkbox(
            value: id is num && _selected.contains(id),
            onChanged: (v) => setState(() {
              if (id is num) {
                if (v == true) {
                  _selected.add(id);
                } else {
                  _selected.remove(id);
                }
              }
            }),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (dt != null)
                      Container(
                        margin: const EdgeInsets.only(right: 6, top: 1),
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 1),
                        decoration: BoxDecoration(
                          color: dt == '荤'
                              ? const Color(0xFFFFE0B2)
                              : const Color(0xFFC8E6C9),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text(dt,
                            style: TextStyle(
                                fontSize: 11,
                                color: dt == '荤'
                                    ? const Color(0xFFE65100)
                                    : const Color(0xFF2E7D32))),
                      ),
                    Flexible(
                      child: Text('${it['dish_name'] ?? ''}',
                          maxLines: 2, overflow: TextOverflow.ellipsis),
                    ),
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  '${_kMealLabels[it['meal_type']] ?? it['meal_type']}'
                  '${nut['calories'] is num ? ' · ${nut['calories']}千卡' : ''}'
                  '${(note != null && note.isNotEmpty) ? ' · $note' : ''}',
                  style: const TextStyle(fontSize: 12, color: Colors.black54),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 购物清单对话框：生成 → 勾选 → 导出/复制
class _ShoppingListDialog extends StatefulWidget {
  const _ShoppingListDialog({required this.plan});

  final Map<String, dynamic> plan;

  @override
  State<_ShoppingListDialog> createState() => _ShoppingListDialogState();
}

class _ShoppingListDialogState extends State<_ShoppingListDialog> {
  final ApiService _api = ApiService();
  /// v0.11：人数选择（1~6 人），默认 2；v0.12 支持自定义人数
  int _persons = 2;
  static const _personOptions = [1, 2, 3, 4, 5, 6];
  bool _customPersons = false;
  final TextEditingController _customCtrl = TextEditingController();
  bool _generated = false;
  bool _loading = false;
  Map<String, dynamic>? _data;
  List<dynamic> _items = [];
  List<dynamic> _skipped = [];
  String? _error;
  String _exportText = '';

  @override
  void dispose() {
    _customCtrl.dispose();
    super.dispose();
  }

  /// 进入自定义人数：清空输入并切换模式
  void _enableCustomPersons() {
    setState(() {
      _customPersons = true;
      _customCtrl.clear();
    });
  }

  /// 解析当前人数（自定义模式取输入框，非法回退 2）
  int _resolvedPersons() {
    if (!_customPersons) return _persons;
    final v = int.tryParse(_customCtrl.text.trim());
    return (v != null && v > 0) ? v : 2;
  }

  /// 自定义人数步进：按当前值 ±1（v0.12），下限 1
  void _customStep(int delta) {
    if (_loading) return;
    final cur = _resolvedPersons();
    final next = cur + delta;
    if (next < 1) return;
    setState(() {
      _customCtrl.text = '$next';
      _customCtrl.selection =
          TextSelection.collapsed(offset: _customCtrl.text.length);
    });
  }

  Future<void> _generate() async {
    setState(() {
      _generated = true;
      _loading = true;
      _error = null;
    });
    try {
      final planId = (widget.plan['id'] as num).toInt();
      final persons = _resolvedPersons();
      // 自定义输入解析结果回写，供结果区展示实际生成人数
      _persons = persons;
      final data = await _api.generateShoppingList(planId, persons: persons);
      if (!mounted) return;
      setState(() {
        _data = data;
        _items = data['items'] ?? [];
        _skipped = data['skippedItems'] ?? [];
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '清单生成失败');
        _loading = false;
      });
    }
  }

  bool _isChecked(Map<dynamic, dynamic> it) =>
      it['checked'] == 1 || it['checked'] == true;

  Future<void> _toggleCheck(int index) async {
    final listId = (_data?['listId'] as num?)?.toInt();
    if (listId == null) return;
    final newItems = <Map<String, dynamic>>[];
    for (var i = 0; i < _items.length; i++) {
      final m = Map<String, dynamic>.from(_items[i] as Map);
      if (i == index) {
        m['checked'] = _isChecked(m) ? 0 : 1;
      }
      newItems.add(m);
    }
    setState(() => _items = newItems);
    try {
      final data = await _api.updateShoppingListItems(listId, newItems);
      if (mounted && data['items'] != null) {
        setState(() => _items = data['items'] as List);
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败：${friendlyError(e)}')));
    }
  }

  /// 批量设置：target=1 全标已有 / 0 全部取消 / null 反选
  Future<void> _applyToAll(int? target) async {
    final listId = (_data?['listId'] as num?)?.toInt();
    if (listId == null) return;
    final newItems = <Map<String, dynamic>>[];
    for (var i = 0; i < _items.length; i++) {
      final m = Map<String, dynamic>.from(_items[i] as Map);
      final cur = _isChecked(m);
      m['checked'] = target != null ? (target == 1 ? 1 : 0) : (cur ? 0 : 1);
      newItems.add(m);
    }
    setState(() => _items = newItems);
    try {
      final data = await _api.updateShoppingListItems(listId, newItems);
      if (mounted && data['items'] != null) {
        setState(() => _items = data['items'] as List);
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败：${friendlyError(e)}')));
    }
  }

  Future<void> _export() async {
    final listId = (_data?['listId'] as num?)?.toInt();
    if (listId == null) return;
    try {
      final data = await _api.exportShoppingList(listId);
      if (!mounted) return;
      setState(() => _exportText = (data['exportText'] as String?) ?? '');
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('导出失败：${friendlyError(e)}')));
    }
  }

  void _copy() {
    if (_exportText.isEmpty) return;
    Clipboard.setData(ClipboardData(text: _exportText));
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('已复制到剪贴板')));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_generated ? '购物清单' : '生成购物清单'),
      content: SizedBox(width: 380, child: _buildContent()),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('关闭')),
      ],
    );
  }

  Widget _buildContent() {
    if (!_generated) {
      return Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('人数（决定食材用量）',
              style: TextStyle(fontSize: 13, color: Colors.black54)),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              ..._personOptions.map((n) => ChoiceChip(
                    label: Text('$n 人'),
                    selected: !_customPersons && _persons == n,
                    visualDensity: VisualDensity.compact,
                    onSelected: _loading
                        ? null
                        : (_) => setState(() {
                              _customPersons = false;
                              _persons = n;
                            }),
                  )),
              ChoiceChip(
                label: const Text('自定义'),
                selected: _customPersons,
                visualDensity: VisualDensity.compact,
                onSelected: _loading ? null : (_) => _enableCustomPersons(),
              ),
            ],
          ),
          if (_customPersons) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                IconButton.filledTonal(
                  onPressed: () => _customStep(-1),
                  icon: const Icon(Icons.remove, size: 20),
                  tooltip: '减少人数',
                  visualDensity: VisualDensity.compact,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _customCtrl,
                    enabled: !_loading,
                    keyboardType: TextInputType.number,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 18),
                    // 只允许数字：过滤所有非数字字符，且最多 2 位（防止超大数）
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                      LengthLimitingTextInputFormatter(2),
                    ],
                    decoration: const InputDecoration(
                      isDense: true,
                      hintText: '人数',
                      border: OutlineInputBorder(),
                      contentPadding: EdgeInsets.symmetric(
                          horizontal: 10, vertical: 8),
                    ),
                    onChanged: (_) => setState(() {}),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filledTonal(
                  onPressed: () => _customStep(1),
                  icon: const Icon(Icons.add, size: 20),
                  tooltip: '增加人数',
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
          ],
          const SizedBox(height: 12),
          Text('从计划菜谱聚合食材，按 ${_resolvedPersons()} 人份换算用量，自动去重/分类。',
              style: const TextStyle(fontSize: 13, color: Colors.black54)),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.center,
            child: FilledButton.icon(
              onPressed: _loading ? null : _generate,
              icon: const Icon(Icons.shopping_cart_outlined),
              label: const Text('生成购物清单'),
            ),
          ),
        ],
      );
    }
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(_error!, textAlign: TextAlign.center),
          const SizedBox(height: 8),
          FilledButton(onPressed: _generate, child: const Text('重试')),
        ],
      );
    }
    return SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '按 $_persons 人份生成 · ${_items.length} 项食材',
            style: const TextStyle(fontSize: 12, color: Colors.black54),
          ),
          const SizedBox(height: 4),
          const Text('勾选 = 家中已有（导出时自动剔除）',
              style: TextStyle(fontSize: 12, color: Colors.grey)),
          const SizedBox(height: 2),
          Row(
            children: [
              TextButton(
                onPressed: () => _applyToAll(1),
                child: const Text('全选（全标已有）'),
              ),
              TextButton(
                onPressed: () => _applyToAll(0),
                child: const Text('全部取消'),
              ),
              TextButton(
                onPressed: () => _applyToAll(null),
                child: const Text('反选'),
              ),
              const Spacer(),
              Text(
                '已有 ${_items.where((e) => _isChecked(e as Map)).length}/${_items.length}',
                style: const TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ],
          ),
          if (_skipped.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Text('有 ${_skipped.length} 条手动条目未纳入清单（无关联菜谱）',
                  style:
                      TextStyle(fontSize: 12, color: Colors.orange.shade800)),
            ),
          ..._items.asMap().entries.map((e) => ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                leading: Checkbox(
                  value: _isChecked(e.value as Map),
                  onChanged: (_) => _toggleCheck(e.key),
                ),
                title: Text('${(e.value as Map)['ingredient'] ?? ''}'),
                subtitle: Text(
                  _fmtAmount(e.value as Map),
                  style: const TextStyle(fontSize: 12),
                ),
                trailing: Text(
                  '${(e.value as Map)['category'] ?? ''}',
                  style: const TextStyle(fontSize: 11, color: Colors.grey),
                ),
              )),
          const SizedBox(height: 8),
          if (_exportText.isEmpty)
            Align(
              alignment: Alignment.centerRight,
              child: OutlinedButton.icon(
                onPressed: _export,
                icon: const Icon(Icons.ios_share, size: 16),
                label: const Text('导出清单'),
              ),
            )
          else
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF5F5F5),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(_exportText,
                      style: const TextStyle(fontSize: 12, height: 1.5)),
                ),
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerRight,
                  child: FilledButton.icon(
                    onPressed: _copy,
                    icon: const Icon(Icons.copy, size: 16),
                    label: const Text('复制清单'),
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }

  String _fmtAmount(Map<dynamic, dynamic> it) {
    final amt = it['amount_g'];
    final unit = it['unit']?.toString() ?? 'g';
    if (amt is num && amt > 0) {
      final s = amt % 1 == 0 ? amt.toInt().toString() : amt.toString();
      return '$s$unit';
    }
    if (it['qualitative'] == true) return '适量';
    return '';
  }
}

/// P1 厨电调度对话框（线路 A）：可选起始时间 → 编排时间轴 → 展示各步状态/到点自动下发
class _KitchenScheduleDialog extends StatefulWidget {
  const _KitchenScheduleDialog({required this.item});
  final Map<String, dynamic> item;

  @override
  State<_KitchenScheduleDialog> createState() => _KitchenScheduleDialogState();
}

class _KitchenScheduleDialogState extends State<_KitchenScheduleDialog> {
  final ApiService _api = ApiService();
  /// v1.1：起始时间用时间控件选择（TimeOfDay），默认空 = 按餐次推断
  TimeOfDay? _selectedTime;
  bool _loading = false;
  bool _scheduled = false;
  Map<String, dynamic>? _data;
  String? _error;

  /// 弹出时间选择控件
  Future<void> _pickTime() async {
    final now = TimeOfDay.now();
    final picked = await showTimePicker(
      context: context,
      initialTime: _selectedTime ?? now,
      helpText: '选择起始时间',
      cancelText: '取消',
      confirmText: '确定',
    );
    if (picked != null && mounted) {
      setState(() => _selectedTime = picked);
    }
  }

  String? get _timeText {
    final t = _selectedTime;
    if (t == null) return null;
    return '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
  }

  Future<void> _schedule() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final itemId = (widget.item['id'] as num).toInt();
      final data = await _api.schedulePlanItem(itemId, startAt: _timeText);
      if (!mounted) return;
      setState(() {
        _data = data;
        _scheduled = true;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '厨电调度失败');
        _loading = false;
      });
    }
  }

  Future<void> _cancel() async {
    final itemId = (widget.item['id'] as num).toInt();
    try {
      await _api.cancelPlanItemSchedule(itemId);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('厨电调度已取消')));
      Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('取消失败：${friendlyError(e)}')));
    }
  }

  String _actionLabel(String? action, String? mode) {
    return switch (action) {
      'preheat' => '预热',
      'steam' => '蒸制',
      'air_fry' => '空气炸',
      'bake' => '烤制',
      'set_timer' => '定时',
      'stop' => '停止',
      _ => (mode == null || mode.isEmpty) ? '启动' : mode,
    };
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_scheduled ? '厨电调度' : '厨电调度 · ${widget.item['recipeTitle'] ?? ''}'),
      // v1.1：固定高度 + 内容滚动，保证表单→loading→结果切换布局稳定、结果可见
      content: SizedBox(
        width: 380,
        height: 340,
        child: SingleChildScrollView(child: _buildContent()),
      ),
      actions: _scheduled
          ? [
              TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('关闭')),
              TextButton(onPressed: _cancel, child: const Text('取消调度')),
            ]
          : [
              TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('关闭')),
              FilledButton.icon(
                onPressed: _loading ? null : _schedule,
                icon: const Icon(Icons.kitchen_outlined, size: 18),
                label: const Text('开始编排'),
              ),
            ],
    );
  }

  Widget _buildContent() {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.only(top: 120),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (_error != null) {
      return Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(height: 40),
          Text(_error!, textAlign: TextAlign.center),
          const SizedBox(height: 8),
          FilledButton(onPressed: _schedule, child: const Text('重试')),
        ],
      );
    }
    if (!_scheduled) {
      return Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('厨电调度：按菜谱设备参数生成时间轴，到点自动下发到已绑定设备。',
              style: TextStyle(fontSize: 13, color: Colors.black54)),
          const SizedBox(height: 4),
          const Text('无绑定设备时自动降级为线路 B（视频参考）。',
              style: TextStyle(fontSize: 12, color: Colors.grey)),
          const SizedBox(height: 16),
          const Text('起始时间',
              style: TextStyle(fontSize: 13, color: Colors.black87)),
          const SizedBox(height: 6),
          // v1.1：时间控件选择（点击弹出 TimePicker）
          InkWell(
            onTap: _loading ? null : _pickTime,
            borderRadius: BorderRadius.circular(8),
            child: InputDecorator(
              decoration: const InputDecoration(
                isDense: true,
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                suffixIcon: Icon(Icons.access_time, size: 20),
              ),
              child: Text(
                _timeText != null ? '$_timeText 开始' : '默认按餐次推断',
                style: const TextStyle(fontSize: 14),
              ),
            ),
          ),
          const SizedBox(height: 8),
          const Text('点击上方时间可选择具体开始时刻；不选则按餐次推断。',
              style: TextStyle(fontSize: 11, color: Colors.grey)),
        ],
      );
    }
    // 已编排：展示设备 + 时间轴
    final execMode = _data?['execMode'];
    final schedule = _data?['schedule'] as Map? ?? {};
    final steps = (schedule['steps'] as List?) ?? [];
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (execMode == 'self')
          Text(_data?['message']?.toString() ?? '已降级为线路 B（视频参考）',
              style: TextStyle(fontSize: 12, color: Colors.orange.shade800)),
        if (execMode == 'appliance')
          Text(
            '${schedule['deviceNickname'] ?? '设备'} · 起始 ${schedule['startAt']} · '
            '${steps.length} 步',
            style: const TextStyle(fontSize: 12, color: Colors.black54),
          ),
        const SizedBox(height: 8),
        ...steps.map((st) {
          final stMap = (st as Map).cast<String, dynamic>();
          final status = stMap['status'];
          final icon = switch (status) {
            'sent' || 'done' => Icons.check_circle,
            'failed' => Icons.error,
            'expired' => Icons.schedule,
            _ => Icons.schedule,
          };
          final color = switch (status) {
            'sent' || 'done' => Colors.green,
            'failed' => Colors.red,
            'expired' => Colors.orange,
            _ => Colors.grey,
          };
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(icon, size: 16, color: color),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '步骤 ${(stMap['stepIndex'] ?? 0) + 1} · '
                        '${_actionLabel(stMap['action']?.toString(), stMap['mode']?.toString())}'
                        '${stMap['temperature'] != null ? ' ${stMap['temperature']}℃' : ''}'
                        '${stMap['durationMin'] != null ? ' ${stMap['durationMin']}min' : ''}',
                        style: const TextStyle(fontSize: 13),
                      ),
                      Text(
                        '${stMap['note'] ?? ''}${stMap['note'] != null ? ' · ' : ''}'
                        '${stMap['startAt']?.toString().substring(11, 16)} '
                        '${_statusText(status)}',
                        style: TextStyle(
                            fontSize: 11, color: Colors.grey.shade600),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          );
        }),
      ],
    );
  }

  String _statusText(String? status) {
    return switch (status) {
      'sent' => '已下发',
      'done' => '已完成',
      'failed' => '下发失败',
      'expired' => '已过期',
      _ => '待下发',
    };
  }
}

/// 调整计划条目餐次/星期对话框（v0.5 反馈增强）
/// v0.10：星期下拉按「计划起始日 + dayOffset」显示真实日期（M月D日 周X），
/// 与分组标题一致——AI 菜单计划起始日非周一，原固定周一~周日标签会造成语义错乱
class _EditItemDialog extends StatefulWidget {
  const _EditItemDialog({required this.item, required this.startDate});

  final Map<String, dynamic> item;
  final DateTime startDate;

  @override
  State<_EditItemDialog> createState() => _EditItemDialogState();
}

class _EditItemDialogState extends State<_EditItemDialog> {
  late int _dayOffset;
  late String _mealType;
  static const _dayOptions = [0, 1, 2, 3, 4, 5, 6];
  static const _mealOptions = ['breakfast', 'lunch', 'dinner', 'snack'];

  String _dayLabel(int offset) {
    final d = widget.startDate.add(Duration(days: offset));
    return '${d.month}月${d.day}日 ${_kDayLabels[d.weekday - 1]}';
  }

  @override
  void initState() {
    super.initState();
    _dayOffset = widget.item['dayOffset'] is num
        ? (widget.item['dayOffset'] as num).toInt()
        : 0;
    _mealType = (widget.item['mealType'] as String?) ?? 'dinner';
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('调整餐次/星期'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Align(
              alignment: Alignment.centerLeft, child: Text('星期')),
          DropdownButton<int>(
            value: _dayOffset,
            isExpanded: true,
            items: _dayOptions
                .map((d) =>
                    DropdownMenuItem(value: d, child: Text(_dayLabel(d))))
                .toList(),
            onChanged: (v) => setState(() => _dayOffset = v ?? 0),
          ),
          const SizedBox(height: 12),
          const Align(
              alignment: Alignment.centerLeft, child: Text('餐次')),
          DropdownButton<String>(
            value: _mealType,
            isExpanded: true,
            items: _mealOptions
                .map((m) => DropdownMenuItem(
                    value: m, child: Text(_kMealLabels[m] ?? m)))
                .toList(),
            onChanged: (v) => setState(() => _mealType = v ?? 'dinner'),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消')),
        FilledButton(
            onPressed: () => Navigator.of(context)
                .pop({'dayOffset': _dayOffset, 'mealType': _mealType}),
            child: const Text('保存')),
      ],
    );
  }
}
