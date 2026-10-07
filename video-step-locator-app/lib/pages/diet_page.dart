import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import 'health_photo_checkin_page.dart';
import 'health_review_page.dart';

/// 饮食记录页（营养健康模块 P1）
/// 今日营养汇总（热量/蛋白/脂肪/碳水 + 目标占比）+ 手动记一餐 + 历史。
class DietPage extends StatefulWidget {
  const DietPage({super.key, this.autoOpenManual = false});

  /// 进入后自动弹出手动记餐对话框（供「拍照记录 → 改用手动记餐」跳转用）
  final bool autoOpenManual;

  @override
  State<DietPage> createState() => _DietPageState();
}

class _DietPageState extends State<DietPage> {
  final ApiService _api = ApiService();

  Map<String, dynamic> _today = {};
  List<dynamic> _history = [];
  int _total = 0;
  int _page = 1;
  bool _loading = true;
  String? _error;

  static const Map<String, String> _mealLabels = {
    'breakfast': '早餐',
    'lunch': '午餐',
    'dinner': '晚餐',
    'snack': '加餐',
  };

  @override
  void initState() {
    super.initState();
    _load();
    if (widget.autoOpenManual) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _openAddDialog();
      });
    }
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final today = await _api.getDietToday();
      final hist = await _api.getDietHistory(page: _page);
      if (!mounted) return;
      setState(() {
        _today = today;
        _history = hist['items'] ?? [];
        _total = hist['total'] ?? 0;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载饮食记录失败');
        _loading = false;
      });
    }
  }

  Future<void> _openAddDialog() async {
    String mealType = 'dinner';
    final noteC = TextEditingController();
    // 动态食材行：名称 + 克数
    final nameCs = <TextEditingController>[TextEditingController()];
    final amountCs = <TextEditingController>[TextEditingController()];

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('手动记一餐'),
          content: SizedBox(
            width: 420,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('餐次'),
                  const SizedBox(height: 4),
                  Wrap(
                    spacing: 6,
                    children: _mealLabels.entries.map((e) {
                      final sel = e.key == mealType;
                      return ChoiceChip(
                        label: Text(e.value),
                        selected: sel,
                        onSelected: (_) => setLocal(() => mealType = e.key),
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 12),
                  const Text('食材（可点🔍从食物库选）'),
                  const SizedBox(height: 4),
                  for (int i = 0; i < nameCs.length; i++) ...[
                    Row(
                      children: [
                        Expanded(
                          flex: 3,
                          child: TextField(
                            controller: nameCs[i],
                            decoration: InputDecoration(
                              hintText: '食材名（如 鸡胸肉）',
                              isDense: true,
                              suffixIcon: IconButton(
                                icon: const Icon(Icons.search, size: 20),
                                tooltip: '从食物库选择',
                                onPressed: () async {
                                  final picked = await _pickFood(ctx);
                                  if (picked != null) {
                                    setLocal(() => nameCs[i].text = picked);
                                  }
                                },
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          flex: 2,
                          child: TextField(
                            controller: amountCs[i],
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(
                                hintText: '克数', isDense: true),
                          ),
                        ),
                        IconButton(
                          icon: const Icon(Icons.remove_circle_outline,
                              size: 20, color: Colors.redAccent),
                          onPressed: nameCs.length > 1
                              ? () => setLocal(() {
                                    nameCs.removeAt(i);
                                    amountCs.removeAt(i);
                                  })
                              : null,
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                  ],
                  TextButton.icon(
                    icon: const Icon(Icons.add, size: 18),
                    label: const Text('添加食材'),
                    onPressed: () => setLocal(() {
                      nameCs.add(TextEditingController());
                      amountCs.add(TextEditingController());
                    }),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: noteC,
                    maxLines: 2,
                    decoration: const InputDecoration(
                        labelText: '备注（可选）', isDense: true),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(ctx).pop(false),
                child: const Text('取消')),
            FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('保存'),
            ),
          ],
        ),
      ),
    );

    if (!mounted) return;
    // 「拍照记录 → 改用手动记餐」场景：用户在手动记一餐对话框中点了取消，
    // 则返回拍照记录页（不留在饮食记录页）。普通进入饮食记录页不受影响。
    if (ok != true) {
      if (widget.autoOpenManual) Navigator.of(context).pop();
      return;
    }
    final items = <Map<String, dynamic>>[];
    for (int i = 0; i < nameCs.length; i++) {
      final name = nameCs[i].text.trim();
      final amt = double.tryParse(amountCs[i].text.trim());
      if (name.isEmpty || amt == null || amt <= 0) continue;
      items.add({'name': name, 'amount': amt, 'unit': 'g'});
    }
    if (items.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请至少填写一种食材及克数')));
      return;
    }
    try {
      await _api.addDietRecord(
          mealType: mealType, items: items, note: noteC.text.trim());
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('已记录，将计入今日营养')));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('记录失败：${friendlyError(e)}')));
    }
  }

  /// 从食物库搜索选食材，返回食材名
  Future<String?> _pickFood(BuildContext ctx) async {
    final searchC = TextEditingController();
    var results = <dynamic>[];
    return showDialog<String>(
      context: ctx,
      builder: (dctx) => StatefulBuilder(
        builder: (dctx, setLocal) => AlertDialog(
          title: const Text('从食物库选择'),
          content: SizedBox(
            width: 360,
            height: 360,
            child: Column(
              children: [
                TextField(
                  controller: searchC,
                  autofocus: true,
                  decoration: const InputDecoration(
                      hintText: '输入食材名搜索…', isDense: true),
                  onChanged: (_) async {
                    final list =
                        await _api.searchFoods(keyword: searchC.text.trim());
                    if (mounted) setLocal(() => results = list);
                  },
                ),
                const SizedBox(height: 8),
                Expanded(
                  child: results.isEmpty
                      ? const Center(child: Text('搜索食物，如 鸡胸肉 / 西兰花'))
                      : ListView(
                          children: results.map<Widget>((f) {
                            final name = f['name'] ?? '';
                            final cal = f['calories'] ?? 0;
                            return ListTile(
                              dense: true,
                              title: Text('$name'),
                              subtitle: Text(
                                  '${f['category'] ?? ''} · $cal 千卡/100g'),
                              onTap: () => Navigator.of(dctx).pop(name),
                            );
                          }).toList(),
                        ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(dctx).pop(),
                child: const Text('取消')),
          ],
        ),
      ),
    );
  }

  Future<void> _delete(int id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除这条饮食记录？'),
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
      await _api.deleteDietRecord(id);
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('饮食记录'),
        actions: [
          IconButton(
              icon: const Icon(Icons.photo_camera_outlined),
              onPressed: _loading
                  ? null
                  : () async {
                      final ok = await Navigator.of(context).push(
                          MaterialPageRoute(
                              builder: (_) => const HealthPhotoCheckinPage()));
                      if (ok == true) _load();
                    },
              tooltip: '拍照记录'),
          IconButton(
              icon: const Icon(Icons.auto_awesome),
              onPressed: _loading
                  ? null
                  : () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => const HealthReviewPage())),
              tooltip: 'AI 饮食复盘'),
          IconButton(
              icon: const Icon(Icons.add_circle_outline),
              onPressed: _loading ? null : _openAddDialog,
              tooltip: '手动记一餐'),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(_error!),
                        const SizedBox(height: 12),
                        FilledButton(
                            onPressed: _load, child: const Text('重试')),
                      ],
                    ),
                  )
                : ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.all(12),
                    children: [
                      _buildTodayCard(),
                      const SizedBox(height: 12),
                      _buildHistorySection(),
                    ],
                  ),
      ),
    );
  }

  Widget _buildTodayCard() {
    final cal = (_today['calories'] ?? 0);
    final protein = _today['protein'] ?? 0;
    final fat = _today['fat'] ?? 0;
    final carbs = _today['carbs'] ?? 0;
    final targets = _today['targets'] is Map
        ? Map<String, dynamic>.from(_today['targets'] as Map)
        : <String, dynamic>{};
    final ratio = _today['ratio'] is Map
        ? Map<String, dynamic>.from(_today['ratio'] as Map)
        : <String, dynamic>{};
    final tCal = _num(targets['calories'], 2000);
    final tPro = _num(targets['protein'], 60);
    final tFat = _num(targets['fat'], 55);
    final tCarb = _num(targets['carbs'], 300);

    Widget metric(String label, String value, String target, int pct, Color color) {
      return Column(
        children: [
          SizedBox(
            width: 56,
            height: 56,
            child: Stack(
              alignment: Alignment.center,
              children: [
                SizedBox(
                  width: 52,
                  height: 52,
                  child: CircularProgressIndicator(
                    value: (pct / 100).clamp(0.0, 1.0),
                    strokeWidth: 6,
                    backgroundColor: Colors.grey.shade200,
                    valueColor: AlwaysStoppedAnimation(color),
                  ),
                ),
                Text('$pct%',
                    style: const TextStyle(
                        fontSize: 11, fontWeight: FontWeight.bold)),
              ],
            ),
          ),
          const SizedBox(height: 4),
          Text(label, style: const TextStyle(fontSize: 12)),
          Text('$value / $target',
              style: const TextStyle(fontSize: 11, color: Colors.grey)),
        ],
      );
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text('🥗 今日营养',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                const Spacer(),
                Text('${_today['recordCount'] ?? 0} 条记录',
                    style: const TextStyle(color: Colors.grey, fontSize: 12)),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                metric('热量',
                    '${(cal is num ? cal : 0).toInt()}',
                    '$tCal',
                    _pct(ratio['calories']),
                    Colors.orange),
                metric('蛋白', '$protein', '$tPro g',
                    _pct(ratio['protein']), Colors.indigo),
                metric('脂肪', '$fat', '$tFat g', _pct(ratio['fat']),
                    Colors.redAccent),
                metric('碳水', '$carbs', '$tCarb g', _pct(ratio['carbs']),
                    Colors.teal),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              '目标来源：${_targetSource(targets)} · 达标即占比 100%',
              style: const TextStyle(fontSize: 11, color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }

  String _targetSource(Map<String, dynamic> targets) {
    final s = targets['source'];
    if (s == 'profile') return '个性化档案';
    if (s == 'preference') return '热量偏好设置';
    return '默认 2000 千卡';
  }

  Widget _buildHistorySection() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text('📋 最近记录',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                const Spacer(),
                Text('共 $_total 条', style: const TextStyle(color: Colors.grey)),
              ],
            ),
            const SizedBox(height: 8),
            if (_history.isEmpty)
              const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: Text('暂无饮食记录，点右上角＋手动记一餐')),
              )
            else
              ..._history.map<Widget>((r) {
                final id = r['id'];
                final meal = _mealLabels[r['mealType']] ?? '${r['mealType']}';
                final cal = r['calories'] ?? 0;
                final estimated = r['estimated'] == 1;
                final source = r['source'] == 'cook' ? '跟做' : '手动';
                final items = (r['items'] is List)
                    ? (r['items'] as List)
                        .map((i) => i is Map ? (i['name'] ?? '') : '')
                        .where((s) => s.isNotEmpty)
                        .join('、')
                    : '';
                return ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text('$meal · ${r['date']}'
                      '${estimated ? ' · 估算' : ''}'),
                  subtitle: Text(
                      items.isEmpty ? '${r['note'] ?? ''}' : items,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        '$cal 千卡',
                        style: const TextStyle(
                            fontWeight: FontWeight.bold, fontSize: 13),
                      ),
                      const SizedBox(width: 8),
                      Text(source,
                          style: const TextStyle(
                              fontSize: 11, color: Colors.grey)),
                      IconButton(
                        icon: const Icon(Icons.delete_outline,
                            size: 18, color: Colors.redAccent),
                        onPressed: () => _delete(id),
                      ),
                    ],
                  ),
                );
              }),
            if (_total > 10) ...[
              const SizedBox(height: 4),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    icon: const Icon(Icons.chevron_left),
                    onPressed: _page > 1
                        ? () {
                            setState(() => _page--);
                            _load();
                          }
                        : null,
                  ),
                  Text('第 $_page 页'),
                  IconButton(
                    icon: const Icon(Icons.chevron_right),
                    onPressed: _page * 10 < _total
                        ? () {
                            setState(() => _page++);
                            _load();
                          }
                        : null,
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  static int _pct(dynamic v) => v is num ? v.toInt() : 0;

  static int _num(dynamic v, int dft) => v is num ? v.toInt() : dft;
}
