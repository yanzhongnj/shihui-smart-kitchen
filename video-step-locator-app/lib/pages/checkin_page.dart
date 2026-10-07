import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// 炒菜打卡页（Keep 式习惯记录）
/// 展示：今日打卡 + 连续天数 + 厨艺等级 + 成就徽章 + 月度日历热力图 + 本月打卡记录。
/// 可从菜谱详情页带入 videoId/recipeTitle 预填。
class CheckinPage extends StatefulWidget {
  final int? videoId;
  final String? recipeTitle;
  const CheckinPage({super.key, this.videoId, this.recipeTitle});

  @override
  State<CheckinPage> createState() => _CheckinPageState();
}

class _CheckinPageState extends State<CheckinPage> {
  final ApiService _api = ApiService();

  Map<String, dynamic> _stats = {};
  Map<String, dynamic> _level = {};
  List<dynamic> _badges = [];
  List<dynamic> _checkins = [];
  DateTime _month = DateTime(DateTime.now().year, DateTime.now().month, 1);
  bool _loading = true;
  String? _error;

  static const Map<String, String> _mealLabels = {
    'breakfast': '早餐',
    'lunch': '午餐',
    'dinner': '晚餐',
    'snack': '加餐',
  };
  static const Map<String, String> _moodLabels = {
    'success': '成功',
    'normal': '一般',
    'fail': '翻车',
  };

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final stats = await _api.getCheckinStats();
      final level = await _api.getLevel();
      final badges = await _api.getBadges();
      final checkins = await _api.getCheckins(_monthKey(_month));
      if (!mounted) return;
      setState(() {
        _stats = stats;
        _level = level;
        _badges = badges;
        _checkins = checkins;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载打卡数据失败，请稍后重试');
        _loading = false;
      });
    }
  }

  Future<void> _loadMonth() async {
    try {
      final checkins = await _api.getCheckins(_monthKey(_month));
      if (!mounted) return;
      setState(() => _checkins = checkins);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('加载记录失败：${friendlyError(e)}')));
    }
  }

  static String _monthKey(DateTime m) =>
      '${m.year}-${m.month.toString().padLeft(2, '0')}';

  void _prevMonth() {
    setState(() {
      _month = DateTime(_month.year, _month.month - 1, 1);
      _checkins = [];
    });
    _loadMonth();
  }

  void _nextMonth() {
    setState(() {
      _month = DateTime(_month.year, _month.month + 1, 1);
      _checkins = [];
    });
    _loadMonth();
  }

  Future<void> _openCheckinForm() async {
    final recipeTitle = widget.recipeTitle;
    final messenger = ScaffoldMessenger.of(context);

    final mealController = ValueNotifier<String>('dinner');
    final moodController = ValueNotifier<String>('success');
    final titleController = TextEditingController(text: recipeTitle ?? '');
    final caloriesController = TextEditingController();
    final noteController = TextEditingController();
    final portion = ValueNotifier<double>(1.0);

    final submitted = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(recipeTitle == null ? '记录今天做了什么菜' : '打卡 · $recipeTitle'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: titleController,
                decoration: const InputDecoration(
                  labelText: '菜名',
                  hintText: '如：鱼香肉丝',
                ),
              ),
              const SizedBox(height: 12),
              const Text('餐次'),
              ValueListenableBuilder<String>(
                valueListenable: mealController,
                builder: (_, v, __) => Wrap(
                  spacing: 6,
                  children: _mealLabels.entries.map((e) {
                    final selected = e.key == v;
                    return ChoiceChip(
                      label: Text(e.value),
                      selected: selected,
                      onSelected: (_) => mealController.value = e.key,
                    );
                  }).toList(),
                ),
              ),
              const SizedBox(height: 12),
              const Text('心情'),
              ValueListenableBuilder<String>(
                valueListenable: moodController,
                builder: (_, v, __) => Wrap(
                  spacing: 6,
                  children: _moodLabels.entries.map((e) {
                    final selected = e.key == v;
                    return ChoiceChip(
                      label: Text(e.value),
                      selected: selected,
                      onSelected: (_) => moodController.value = e.key,
                    );
                  }).toList(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: caloriesController,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: '热量（千卡，可选）',
                  hintText: '如：450',
                ),
              ),
              const SizedBox(height: 12),
              const Text('份量（影响营养记录）'),
              ValueListenableBuilder<double>(
                valueListenable: portion,
                builder: (_, v, __) => Wrap(
                  spacing: 6,
                  children: [0.5, 1.0, 1.5].map((p) {
                    final sel = v == p;
                    return ChoiceChip(
                      label: Text(p == 1.0 ? '标准 1x' : '${p}x'),
                      selected: sel,
                      onSelected: (_) => portion.value = p,
                    );
                  }).toList(),
                ),
              ),
              const SizedBox(height: 4),
              Text(
                '跟做时会按菜谱精确食材自动记录营养；份量越大营养越高。',
                style: TextStyle(fontSize: 11, color: Colors.grey.shade600),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: noteController,
                maxLines: 2,
                decoration: const InputDecoration(
                  labelText: '备注（可选）',
                  hintText: '做得怎么样？有没有翻车？',
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('打卡'),
          ),
        ],
      ),
    );

    if (submitted != true || !mounted) return;
    try {
      final res = await _api.addCheckin(
        videoId: widget.videoId,
        recipeTitle: titleController.text.trim(),
        mealType: mealController.value,
        mood: moodController.value,
        note: noteController.text.trim(),
        calories: int.tryParse(caloriesController.text.trim()),
        portionFactor: portion.value,
      );
      if (!mounted) return;
      final newBadges = (res['newBadges'] as List?) ?? [];
      if (newBadges.isNotEmpty) {
        final names = newBadges
            .map((b) => '${b['icon'] ?? '🏅'} ${b['name'] ?? ''}')
            .join('  ');
        showDialog(
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
        messenger.showSnackBar(const SnackBar(content: Text('打卡成功！')));
      }
      // P2 饭后安全提醒：打卡成功弹窗展示 safetyTips
      final safetyTips = (res['safetyTips'] as List?) ?? [];
      if (safetyTips.isNotEmpty) {
        showDialog(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('🛡️ 食物安全提醒'),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final it in safetyTips)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _safetyLine(
                              it is Map ? Map<String, dynamic>.from(it) : {}),
                          Row(
                            children: [
                              TextButton(
                                onPressed: () => _sendFeedback(it, 1),
                                child: const Text('👍 有用',
                                    style: TextStyle(fontSize: 11)),
                              ),
                              TextButton(
                                onPressed: () => _sendFeedback(it, 0),
                                child: const Text('👎 无用',
                                    style: TextStyle(fontSize: 11)),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
            actions: [
              TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('知道了')),
            ],
          ),
        );
      }
      await _loadAll();
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(
          content: Text('打卡失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  Future<void> _sendFeedback(dynamic it, int useful) async {
    final raw = it is Map ? it['ruleId'] : null;
    final ruleId = raw is num ? raw.toInt() : null;
    if (ruleId == null) return;
    try {
      await _api.safetyFeedback(ruleId, useful);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(useful == 1 ? '感谢反馈 👍' : '已记录，我们会优化这条提醒')));
    } catch (_) {}
  }

  Widget _safetyLine(Map<String, dynamic> it) {
    final level = '${it['level'] ?? ''}';
    final icon = level == 'RED'
        ? '🔴'
        : level == 'YELLOW'
            ? '🟡'
            : '🟢';
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$icon '),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('${it['title'] ?? ''}',
                  style: const TextStyle(fontWeight: FontWeight.w600)),
              if ('${it['advice'] ?? ''}'.isNotEmpty)
                Text('${it['advice']}',
                    style: const TextStyle(fontSize: 12, color: Colors.black54)),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _deleteCheckin(int id) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除这条打卡？'),
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
    if (confirmed != true || !mounted) return;
    try {
      await _api.deleteCheckin(id);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('已删除')));
      await _loadAll();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('删除失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('我的打卡'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loading ? null : _loadAll,
            tooltip: '刷新',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _loadAll,
        child: _buildBody(),
      ),
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
            FilledButton(onPressed: _loadAll, child: const Text('重试')),
          ],
        ),
      );
    }
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(12),
      children: [
        _buildTodayCard(),
        const SizedBox(height: 12),
        _buildNutritionRings(),
        const SizedBox(height: 12),
        _buildLevelCard(),
        const SizedBox(height: 12),
        _buildBadgeSection(),
        const SizedBox(height: 12),
        _buildCalendarCard(),
        const SizedBox(height: 12),
        _buildMonthList(),
      ],
    );
  }

  Widget _buildTodayCard() {
    final streak = _stats['streak'] ?? 0;
    final todayChecked = _stats['todayChecked'] == true;
    final todayCalories = _stats['todayCalories'] is num
        ? (_stats['todayCalories'] as num).toInt()
        : 0;
    final rawBudget = _stats['caloriesBudget'];
    final budget = rawBudget is num ? rawBudget.toInt() : null;
    final b = budget ?? 2000;
    final ratio = b > 0 ? (todayCalories / b).clamp(0.0, 1.0).toDouble() : 0.0;
    final over = b > 0 && todayCalories > b;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '$streak',
                    style: const TextStyle(
                        fontSize: 40, fontWeight: FontWeight.bold),
                  ),
                  const Text('天连续打卡'),
                  const SizedBox(height: 4),
                  Text(
                    todayChecked ? '✅ 今天已打卡' : '今天还没打卡，快记录一道菜吧',
                    style: TextStyle(
                        color: todayChecked ? Colors.green : Colors.orange),
                  ),
                ],
              ),
            ),
            Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 84,
                  height: 84,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      SizedBox(
                        width: 78,
                        height: 78,
                        child: CircularProgressIndicator(
                          value: ratio,
                          strokeWidth: 8,
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
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                              color: over ? Colors.red : Colors.black87,
                            ),
                          ),
                          const Text('千卡',
                              style: TextStyle(
                                  fontSize: 11, color: Colors.grey)),
                        ],
                      ),
                    ],
                  ),
                ),
                Text(
                  budget == null ? '未设预算·按2000' : '目标 $budget 千卡',
                  style: const TextStyle(fontSize: 11, color: Colors.grey),
                ),
                const SizedBox(height: 8),
                FilledButton.icon(
                  icon: const Icon(Icons.restaurant, size: 18),
                  label: Text(todayChecked ? '再记一道' : '打卡'),
                  onPressed: _openCheckinForm,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 多指标营养进度环（热量/蛋白/脂肪/碳水），数据源 stats.todayNutrition + nutritionTargets
  Widget _buildNutritionRings() {
    final tn = _stats['todayNutrition'] is Map
        ? Map<String, dynamic>.from(_stats['todayNutrition'] as Map)
        : <String, dynamic>{};
    final targets = _stats['nutritionTargets'] is Map
        ? Map<String, dynamic>.from(_stats['nutritionTargets'] as Map)
        : <String, dynamic>{};
    final ratios = tn['ratio'] is Map
        ? Map<String, dynamic>.from(tn['ratio'] as Map)
        : <String, dynamic>{};

    Widget ring(String label, dynamic value, dynamic target, dynamic pct, Color color) {
      final v = value is num ? value : 0;
      final t = target is num ? target : 0;
      final p = pct is num ? pct.toInt() : 0;
      return Column(
        children: [
          SizedBox(
            width: 52,
            height: 52,
            child: Stack(
              alignment: Alignment.center,
              children: [
                SizedBox(
                  width: 48,
                  height: 48,
                  child: CircularProgressIndicator(
                    value: (p / 100).clamp(0.0, 1.0),
                    strokeWidth: 5,
                    backgroundColor: Colors.grey.shade200,
                    valueColor: AlwaysStoppedAnimation(color),
                  ),
                ),
                Text('$p%',
                    style: const TextStyle(
                        fontSize: 10, fontWeight: FontWeight.bold)),
              ],
            ),
          ),
          const SizedBox(height: 3),
          Text(label, style: const TextStyle(fontSize: 11)),
          Text('$v/$t',
              style: const TextStyle(fontSize: 10, color: Colors.grey)),
        ],
      );
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🥗 今日营养',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                ring('热量', tn['calories'], targets['calories'],
                    ratios['calories'], Colors.orange),
                ring('蛋白', tn['protein'], targets['protein'],
                    ratios['protein'], Colors.indigo),
                ring('脂肪', tn['fat'], targets['fat'], ratios['fat'],
                    Colors.redAccent),
                ring('碳水', tn['carbs'], targets['carbs'], ratios['carbs'],
                    Colors.teal),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildLevelCard() {
    final level = _level['level'] ?? 1;
    final levelName = _level['levelName'] ?? '入门学徒';
    final totalExp = _level['totalExp'] ?? 0;
    final next = _level['nextThreshold'] ?? 100;
    final progress = ((_level['progress'] ?? 0) as num).toDouble();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text('👨‍🍳', style: TextStyle(fontSize: 28)),
                const SizedBox(width: 8),
                Text('厨艺 Lv.$level · $levelName',
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.bold)),
                const Spacer(),
                Text('$totalExp / $next EXP',
                    style: const TextStyle(color: Colors.grey)),
              ],
            ),
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: (progress / 100).clamp(0.0, 1.0),
                minHeight: 8,
                backgroundColor: Colors.grey.shade300,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBadgeSection() {
    final obtained = _badges.where((b) => b['obtained'] == true).toList();
    final locked = _badges.where((b) => b['obtained'] != true).toList();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text('🏅 成就徽章',
                    style:
                        TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                const Spacer(),
                Text('${obtained.length} / ${_badges.length}',
                    style: const TextStyle(color: Colors.grey)),
              ],
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 96,
              child: ListView(
                scrollDirection: Axis.horizontal,
                children: [
                  ...obtained.map((b) => _badgeItem(b, obtained: true)),
                  ...locked.map((b) => _badgeItem(b, obtained: false)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _badgeItem(dynamic b, {required bool obtained}) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 6),
      child: Tooltip(
        message: '${b['name']}：${b['desc']}',
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: obtained ? Colors.orange.shade50 : Colors.grey.shade200,
                border: Border.all(
                    color:
                        obtained ? Colors.orange.shade300 : Colors.grey.shade300),
              ),
              alignment: Alignment.center,
              child: Text(
                obtained ? '${b['icon'] ?? '🏅'}' : '🔒',
                style: const TextStyle(fontSize: 24),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '${b['name'] ?? ''}',
              style: TextStyle(
                  fontSize: 11,
                  color: obtained ? Colors.black87 : Colors.grey),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCalendarCard() {
    final first = DateTime(_month.year, _month.month, 1);
    final daysInMonth = DateTime(_month.year, _month.month + 1, 0).day;
    final leading = first.weekday - 1; // 周一=0
    // date(day) -> mood（一天多条取第一条）
    final Map<int, String> dateMood = {};
    for (final c in _checkins) {
      final d = DateTime.tryParse(c['date'] as String? ?? '');
      if (d == null || d.month != _month.month || d.year != _month.year) {
        continue;
      }
      dateMood.putIfAbsent(d.day, () => c['mood'] as String? ?? 'normal');
    }
    final today = DateTime.now();
    final isCurrentMonth = today.month == _month.month && today.year == _month.year;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            Row(
              children: [
                IconButton(
                    icon: const Icon(Icons.chevron_left),
                    onPressed: _prevMonth),
                Expanded(
                  child: Text(
                    '${_month.year}年${_month.month}月',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.bold),
                  ),
                ),
                IconButton(
                    icon: const Icon(Icons.chevron_right),
                    onPressed: _nextMonth),
              ],
            ),
            const SizedBox(height: 4),
            Row(
              children: const ['一', '二', '三', '四', '五', '六', '日']
                          .map((w) => Expanded(
                                child: Center(
                                    child: Text(w,
                                        style: const TextStyle(
                                            color: Colors.grey,
                                            fontSize: 12))),
                              ))
                  .toList(),
            ),
            const SizedBox(height: 4),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 7, childAspectRatio: 1.1),
              itemCount: leading + daysInMonth,
              itemBuilder: (_, i) {
                final day = i - leading + 1;
                if (day < 1) return const SizedBox.shrink();
                final mood = dateMood[day];
                final isToday =
                    isCurrentMonth && today.day == day;
                return Center(
                  child: Container(
                    width: 34,
                    height: 34,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: mood == null
                          ? Colors.transparent
                          : _moodColor(mood).withValues(alpha: 0.25),
                      border: isToday
                          ? Border.all(color: Colors.blue, width: 2)
                          : null,
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      '$day',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: isToday ? FontWeight.bold : null,
                        color: mood == null ? Colors.black87 : Colors.black,
                      ),
                    ),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  static Color _moodColor(String mood) {
    switch (mood) {
      case 'success':
        return Colors.green;
      case 'fail':
        return Colors.red;
      default:
        return Colors.orange;
    }
  }

  Widget _buildMonthList() {
    if (_checkins.isEmpty) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Center(child: Text('本月还没有打卡记录，点击上方「打卡」开始吧')),
        ),
      );
    }
    // 按日期倒序展示
    final sorted = [..._checkins]..sort((a, b) =>
        (b['date'] as String).compareTo(a['date'] as String));
    return Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
            child: Text('本月记录（${sorted.length}）',
                style:
                    const TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
          ),
          ...sorted.map((c) => ListTile(
                dense: true,
                leading: const Icon(Icons.restaurant, color: Colors.orange),
                title: Text(c['recipeTitle'] as String? ?? '家常菜'),
                subtitle: Text(
                  '${c['date']} · ${_mealLabels[c['mealType']] ?? c['mealType']}'
                  ' · ${_moodLabels[c['mood']] ?? c['mood']}'
                  '${c['calories'] != null ? ' · ${c['calories']}千卡' : ''}'
                  '${(c['note'] as String? ?? '').isNotEmpty ? '\n${c['note']}' : ''}',
                ),
                isThreeLine: (c['note'] as String? ?? '').isNotEmpty,
                trailing: IconButton(
                  icon: const Icon(Icons.delete_outline,
                      size: 20, color: Colors.grey),
                  onPressed: () => _deleteCheckin(c['id'] as int),
                ),
              )),
        ],
      ),
    );
  }
}
