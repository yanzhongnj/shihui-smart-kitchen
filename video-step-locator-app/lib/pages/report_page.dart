import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:path_provider/path_provider.dart';

import '../services/api_service.dart';
import '../utils/error_message.dart';

/// 月度烹饪报告（Keep 式月度总结）：统计卡片 + 常做菜 + 菜系分布 + 心情分布 + 分享卡片
class ReportPage extends StatefulWidget {
  const ReportPage({super.key});

  @override
  State<ReportPage> createState() => _ReportPageState();
}

class _ReportPageState extends State<ReportPage> {
  final ApiService _api = ApiService();
  final GlobalKey _cardKey = GlobalKey();
  DateTime _month = DateTime(DateTime.now().year, DateTime.now().month, 1);
  Map<String, dynamic> _report = {};
  bool _loading = true;
  bool _saving = false;
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
      final r = await _api.getMonthlyReport(_monthKey(_month));
      if (!mounted) return;
      setState(() {
        _report = r;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载报告失败，请稍后重试');
        _loading = false;
      });
    }
  }

  static String _monthKey(DateTime m) =>
      '${m.year}-${m.month.toString().padLeft(2, '0')}';

  void _prevMonth() {
    setState(() {
      _month = DateTime(_month.year, _month.month - 1, 1);
      _report = {};
    });
    _load();
  }

  void _nextMonth() {
    setState(() {
      _month = DateTime(_month.year, _month.month + 1, 1);
      _report = {};
    });
    _load();
  }

  Future<void> _saveCard() async {
    setState(() => _saving = true);
    try {
      final boundary = _cardKey.currentContext!.findRenderObject()
          as RenderRepaintBoundary;
      final image = await boundary.toImage(pixelRatio: 3);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      final bytes = byteData!.buffer.asUint8List();
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/cooking_report_${_monthKey(_month)}.png');
      await file.writeAsBytes(bytes);
      if (!mounted) return;
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('分享卡片已保存'),
          content: Text('已保存到：\n${file.path}\n\n可从文件管理器找到并分享给朋友。'),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('好的')),
          ],
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败：${friendlyError(e, fallback: '请稍后重试')}')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('月度烹饪报告'),
        actions: [
          IconButton(
            icon: const Icon(Icons.image_outlined),
            tooltip: '生成分享卡片',
            onPressed: _saving ? null : _saveCard,
          ),
        ],
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
    final s = Map<String, dynamic>.from(
        (_report['summary'] as Map?) ?? const {});
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Row(
          children: [
            IconButton(
                icon: const Icon(Icons.chevron_left), onPressed: _prevMonth),
            Expanded(
              child: Text(
                '${_month.year}年${_month.month}月',
                textAlign: TextAlign.center,
                style: const TextStyle(
                    fontSize: 16, fontWeight: FontWeight.bold),
              ),
            ),
            IconButton(
                icon: const Icon(Icons.chevron_right), onPressed: _nextMonth),
          ],
        ),
        RepaintBoundary(key: _cardKey, child: _reportCard(s)),
        const SizedBox(height: 12),
        _statsGrid(s),
        const SizedBox(height: 12),
        _topRecipes(_report['topRecipes']),
        const SizedBox(height: 12),
        _cuisines(_report['cuisines']),
        const SizedBox(height: 12),
        _moods(_report['moods']),
        const SizedBox(height: 12),
        _nutritionSection(),
        const SizedBox(height: 12),
        _correctionAnalysis(_report['correctionAnalysis']),
        const SizedBox(height: 24),
      ],
    );
  }

  /// P3：营养健康 —— 本月营养分布/达标率 + 体重趋势
  Widget _nutritionSection() {
    final n = _report['nutrition'] is Map
        ? Map<String, dynamic>.from(_report['nutrition'] as Map)
        : null;
    final wt = _report['weightTrend'] is Map
        ? Map<String, dynamic>.from(_report['weightTrend'] as Map)
        : null;
    if ((n == null || (n['recordCount'] ?? 0) == 0) &&
        (wt == null || (wt['count'] ?? 0) == 0)) {
      return const SizedBox.shrink();
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🥗 营养健康',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            if (n != null && (n['recordCount'] ?? 0) > 0) ...[
              Text(
                  '饮食记录 ${n['recordCount']} 条 / ${n['recordDays']} 天，总热量 ${n['totalCalories']} 千卡'),
              const SizedBox(height: 4),
              Text(
                  '日均：热量 ${n['avgCaloriesPerDay']} · 蛋白 ${n['avgProtein']}g · 脂肪 ${n['avgFat']}g · 碳水 ${n['avgCarbs']}g'),
              const SizedBox(height: 4),
              Text(
                  '热量达标率 ${n['attainmentRate']}%（${n['achieveDays']}/${n['recordDays']} 天 ≥ 目标 ${n['targetCalories']} 的 90%）'),
            ] else
              const Text('本月暂无饮食记录'),
            if (wt != null && (wt['count'] ?? 0) > 0) ...[
              const SizedBox(height: 6),
              Text(
                  '体重记录 ${wt['count']} 次：${wt['firstWeight']} → ${wt['lastWeight']} kg'),
            ],
          ],
        ),
      ),
    );
  }

  /// M3：成品纠偏分析 —— 本月纠偏次数 + 个人易犯偏差（成长轨迹）
  Widget _correctionAnalysis(dynamic data) {
    final ca = (data is Map) ? data : null;
    if (ca == null) return const SizedBox.shrink();
    final count = (ca['count'] is num) ? (ca['count'] as num).toInt() : 0;
    final mistakes = (ca['topMistakes'] as List?) ?? [];
    if (count <= 0 && mistakes.isEmpty) return const SizedBox.shrink();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🔍 成品纠偏分析',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            Text('本月纠偏 $count 次，AI 帮你对照目标找差距'),
            if (mistakes.isNotEmpty) ...[
              const SizedBox(height: 6),
              const Text('易犯偏差（需留意）',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
              ...mistakes.map((m) => Padding(
                    padding: const EdgeInsets.only(top: 3),
                    child: Text(
                        '· ${m['dimension']} ${m['conclusion']}（${m['count']}次）',
                        style: const TextStyle(fontSize: 13)),
                  )),
            ],
          ],
        ),
      ),
    );
  }

  // ---- 分享卡片（RepaintBoundary 内，独立可截图） ----
  Widget _reportCard(Map<String, dynamic> s) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFFFFA726), Color(0xFFE64A19)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('🍳 拾味 · 月度烹饪报告',
              style: TextStyle(
                  color: Colors.white,
                  fontSize: 18,
                  fontWeight: FontWeight.bold)),
          Text('${s['month'] ?? ''}',
              style: const TextStyle(color: Colors.white70, fontSize: 13)),
          const SizedBox(height: 16),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _cardStat('${s['totalCount'] ?? 0}', '下厨次数'),
              _cardStat('${s['totalDays'] ?? 0}', '打卡天数'),
              _cardStat('${s['distinctRecipes'] ?? 0}', '尝新菜数'),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _cardStat('${s['maxStreakInMonth'] ?? 0}', '最长连续'),
              _cardStat('${s['totalCalories'] ?? 0}', '累计热量'),
              _cardStat('${s['worksCount'] ?? 0}', '作品数'),
            ],
          ),
        ],
      ),
    );
  }

  Widget _cardStat(String value, String label) {
    return Column(
      children: [
        Text(value,
            style: const TextStyle(
                color: Colors.white,
                fontSize: 20,
                fontWeight: FontWeight.bold)),
        Text(label,
            style: const TextStyle(color: Colors.white70, fontSize: 11)),
      ],
    );
  }

  // ---- 统计网格 ----
  Widget _statsGrid(Map<String, dynamic> s) {
    final items = [
      ('下厨次数', '${s['totalCount'] ?? 0}'),
      ('打卡天数', '${s['totalDays'] ?? 0}'),
      ('尝新菜数', '${s['distinctRecipes'] ?? 0}'),
      ('最长连续', '${s['maxStreakInMonth'] ?? 0} 天'),
      ('累计热量', '${s['totalCalories'] ?? 0} 千卡'),
      ('日均热量', '${s['avgCaloriesPerDay'] ?? 0} 千卡'),
      ('作品数', '${s['worksCount'] ?? 0}'),
      ('下载数', '${s['downloadsCount'] ?? 0}'),
    ];
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 4,
        childAspectRatio: 1.1,
        crossAxisSpacing: 8,
        mainAxisSpacing: 8,
      ),
      itemCount: items.length,
      itemBuilder: (_, i) => Container(
        decoration: BoxDecoration(
          color: Colors.orange.shade50,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(items[i].$2,
                style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                    color: Colors.deepOrange)),
            const SizedBox(height: 2),
            Text(items[i].$1,
                style: const TextStyle(fontSize: 11, color: Colors.grey)),
          ],
        ),
      ),
    );
  }

  // ---- 常做菜 TOP ----
  Widget _topRecipes(dynamic data) {
    final list = (data as List?) ?? [];
    if (list.isEmpty) return const SizedBox.shrink();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🍲 常做菜 TOP',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            ...List.generate(list.length, (i) {
              final e = list[i];
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(
                  children: [
                    SizedBox(
                        width: 22,
                        child: Text('${i + 1}',
                            style: const TextStyle(
                                fontWeight: FontWeight.bold,
                                color: Colors.deepOrange))),
                    Expanded(
                        child: Text('${e['name'] ?? ''}',
                            maxLines: 1, overflow: TextOverflow.ellipsis)),
                    Text('${e['count'] ?? 0} 次',
                        style: const TextStyle(color: Colors.grey)),
                  ],
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  // ---- 菜系分布 ----
  Widget _cuisines(dynamic data) {
    final list = (data as List?) ?? [];
    if (list.isEmpty) return const SizedBox.shrink();
    final maxCnt = list
        .map((e) => (e['cnt'] is num ? (e['cnt'] as num).toInt() : 0))
        .fold(0, (a, b) => a > b ? a : b);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🏮 菜系分布',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            ...list.map((e) {
              final name = '${e['name'] ?? ''}';
              final cnt = e['cnt'] is num ? (e['cnt'] as num).toInt() : 0;
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(
                  children: [
                    SizedBox(
                        width: 56,
                        child: Text(name,
                            style: const TextStyle(fontSize: 13))),
                    Expanded(
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(4),
                        child: LinearProgressIndicator(
                          value: maxCnt > 0 ? cnt / maxCnt : 0,
                          minHeight: 8,
                          backgroundColor: Colors.grey.shade200,
                          valueColor: const AlwaysStoppedAnimation(
                              Colors.deepOrange),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                        width: 28,
                        child: Text('$cnt',
                            textAlign: TextAlign.right,
                            style: const TextStyle(
                                color: Colors.grey, fontSize: 12))),
                  ],
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  // ---- 心情分布 ----
  Widget _moods(dynamic data) {
    final m = (data as Map?) ?? {};
    if (m.isEmpty) return const SizedBox.shrink();
    const labels = {'success': '成功', 'normal': '一般', 'fail': '翻车'};
    const colors = {
      'success': Colors.green,
      'normal': Colors.orange,
      'fail': Colors.red,
    };
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('😋 心情分布',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: m.entries.map((e) {
                final key = e.key;
                final count = e.value is num ? (e.value as num).toInt() : 0;
                return Chip(
                  label: Text('${labels[key] ?? key} $count'),
                  avatar: Icon(Icons.circle,
                      size: 12, color: colors[key] ?? Colors.grey),
                );
              }).toList(),
            ),
          ],
        ),
      ),
    );
  }
}
