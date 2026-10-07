import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// 体重追踪页（营养健康模块 P3）
/// 记录体重（同日覆盖）+ 目标进度 + 连续记录 + 最近 30 天趋势折线图（CustomPainter）。
class WeightPage extends StatefulWidget {
  const WeightPage({super.key});

  @override
  State<WeightPage> createState() => _WeightPageState();
}

class _WeightPageState extends State<WeightPage> {
  final ApiService _api = ApiService();
  final _weightC = TextEditingController();

  Map<String, dynamic> _data = {};
  bool _loading = true;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _weightC.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final d = await _api.getWeightTrend(days: 30);
      if (!mounted) return;
      setState(() {
        _data = d;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载体重数据失败');
        _loading = false;
      });
    }
  }

  Future<void> _record() async {
    final w = double.tryParse(_weightC.text.trim());
    if (w == null || w <= 0) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请输入有效的体重（kg）')));
      return;
    }
    setState(() => _saving = true);
    try {
      final res = await _api.addWeightLog(w);
      if (!mounted) return;
      setState(() => _saving = false);
      final badges = (res['newBadges'] as List?) ?? [];
      if (badges.isNotEmpty) {
        final names = badges.map((b) => '${b['icon'] ?? ''} ${b['name'] ?? ''}').join('  ');
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
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('已记录今日体重')));
      }
      _weightC.clear();
      await _load();
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('记录失败：${friendlyError(e)}')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('体重追踪'),
        actions: [
          IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: _loading ? null : _load,
              tooltip: '刷新'),
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
                      _buildRecordCard(),
                      const SizedBox(height: 12),
                      _buildProgressCard(),
                      const SizedBox(height: 12),
                      _buildChartCard(),
                    ],
                  ),
      ),
    );
  }

  Widget _buildRecordCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('⚖️ 今日体重',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            Text('连续记录 ${_data['streak'] ?? 0} 天',
                style: const TextStyle(color: Colors.grey, fontSize: 12)),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _weightC,
                    keyboardType: const TextInputType.numberWithOptions(
                        decimal: true),
                    decoration: const InputDecoration(
                        labelText: '体重（kg）',
                        hintText: '如 64.5',
                        isDense: true),
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton(
                  onPressed: _saving ? null : _record,
                  child: Text(_saving ? '保存中…' : '记录'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildProgressCard() {
    final latest = _data['latestWeight'];
    final target = _data['targetWeight'];
    final remaining = _data['remaining'];
    final progress = _data['progress'];
    if (latest == null || target == null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text(
            '记录几次体重后，将结合健康档案的目标体重（target_weight）展示减重进度。未建档可先到「健康档案」设置目标体重。',
            style: TextStyle(color: Colors.grey.shade600),
          ),
        ),
      );
    }
    final pct = progress is num ? progress.toInt().clamp(0, 100) : 0;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🎯 目标进度',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 10),
            Row(
              children: [
                Text('当前 $latest kg', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                const Spacer(),
                Text('目标 $target kg', style: const TextStyle(color: Colors.grey)),
              ],
            ),
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: pct / 100,
                minHeight: 8,
                backgroundColor: Colors.grey.shade300,
                valueColor:
                    const AlwaysStoppedAnimation(Color(0xFF26A69A)),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              remaining is num
                  ? (remaining > 0
                      ? '还差 $remaining kg 达成目标'
                      : '已达成目标，超了 ${(remaining * -1)} kg')
                  : '已完成',
              style: const TextStyle(color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildChartCard() {
    final items = _data['items'] is List ? (_data['items'] as List) : [];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('📈 近 30 天趋势',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            if (items.isEmpty)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: Text('暂无体重记录')),
              )
            else
              SizedBox(
                height: 180,
                width: double.infinity,
                child: CustomPaint(
                  painter: _WeightChartPainter(items),
                  child: const SizedBox.expand(),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 简单折线图（自绘，无第三方图表依赖）
class _WeightChartPainter extends CustomPainter {
  final List<dynamic> items;
  _WeightChartPainter(this.items);

  @override
  void paint(Canvas canvas, Size size) {
    if (items.isEmpty) return;
    final pts = <Offset>[];
    double minW = double.infinity, maxW = -double.infinity;
    for (final it in items) {
      final w = (it['weight'] is num ? (it['weight'] as num) : 0).toDouble();
      if (w < minW) minW = w;
      if (w > maxW) maxW = w;
    }
    if (maxW == minW) {
      minW -= 1;
      maxW += 1;
    }
    const padL = 36.0, padR = 12.0, padT = 12.0, padB = 24.0;
    final n = items.length;
    final w = size.width;
    final h = size.height;
    for (int i = 0; i < n; i++) {
      final v = (items[i]['weight'] is num ? (items[i]['weight'] as num) : 0).toDouble();
      final x = (n == 1 ? w / 2 : padL + (w - padL - padR) * i / (n - 1));
      final y = padT + (h - padT - padB) * (1 - (v - minW) / (maxW - minW));
      pts.add(Offset(x, y));
    }

    // 网格 + 纵轴刻度
    final gridPaint = Paint()
      ..color = Colors.grey.shade300
      ..strokeWidth = 1;
    for (int g = 0; g <= 4; g++) {
      final gy = padT + (h - padT - padB) * g / 4;
      canvas.drawLine(Offset(padL, gy), Offset(w - padR, gy), gridPaint);
      final label = (maxW - (maxW - minW) * g / 4).toStringAsFixed(1);
      final tp = TextPainter(
        text: TextSpan(text: label, style: const TextStyle(fontSize: 9, color: Colors.grey)),
        textDirection: TextDirection.ltr,
      )..layout();
      tp.paint(canvas, Offset(0, gy - tp.height / 2));
    }
    // 首尾日期
    final first = items.first['date']?.toString() ?? '';
    final last = items.last['date']?.toString() ?? '';
    final fp = TextPainter(
      text: TextSpan(text: first, style: const TextStyle(fontSize: 9, color: Colors.grey)),
      textDirection: TextDirection.ltr,
    )..layout();
    fp.paint(canvas, Offset(padL, h - 14));
    final lp = TextPainter(
      text: TextSpan(text: last, style: const TextStyle(fontSize: 9, color: Colors.grey)),
      textDirection: TextDirection.ltr,
    )..layout();
    lp.paint(canvas, Offset(w - padR - lp.width, h - 14));

    // 折线
    final line = Paint()
      ..color = const Color(0xFF26A69A)
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;
    if (pts.length == 1) {
      canvas.drawCircle(pts.first, 3, line);
    } else {
      final path = Path()..moveTo(pts.first.dx, pts.first.dy);
      for (int i = 1; i < pts.length; i++) {
        path.lineTo(pts[i].dx, pts[i].dy);
      }
      canvas.drawPath(path, line);
    }
    // 数据点
    final dot = Paint()..color = const Color(0xFF26A69A);
    for (final p in pts) {
      canvas.drawCircle(p, 2.5, dot);
    }
  }

  @override
  bool shouldRepaint(covariant _WeightChartPainter old) => old.items != items;
}
