import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// AI 饮食复盘页（营养健康模块 P2）
/// 画像 + 近 N 日饮食 + 目标 → LLM 营养平衡分析与个性化建议（含去酿味/去寻味指引）。
class HealthReviewPage extends StatefulWidget {
  const HealthReviewPage({super.key});

  @override
  State<HealthReviewPage> createState() => _HealthReviewPageState();
}

class _HealthReviewPageState extends State<HealthReviewPage> {
  final ApiService _api = ApiService();
  Map<String, dynamic> _data = {};
  bool _loading = true;
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
      final d = await _api.getHealthReview();
      if (!mounted) return;
      setState(() {
        _data = d;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: 'AI 复盘失败，请稍后重试');
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('AI 饮食复盘'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loading ? null : _load,
            tooltip: '重新复盘',
          ),
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
                    children: _buildSections(),
                  ),
      ),
    );
  }

  List<Widget> _buildSections() {
    final hasData = _data['hasData'] == true;
    final sections = <Widget>[];
    if (!hasData) {
      sections.add(Card(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              const Text('🤖', style: TextStyle(fontSize: 48)),
              const SizedBox(height: 12),
              Text(_data['summary'] ?? '暂无饮食数据'),
              const SizedBox(height: 8),
              Text('去打卡或手动记一餐后，我就能帮你复盘啦',
                  style: TextStyle(color: Colors.grey.shade600)),
            ],
          ),
        ),
      ));
      sections.add(_disclaimer());
      return sections;
    }

    // 总评
    sections.add(Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('📝 总体评价',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text(_data['summary'] ?? ''),
            if (_data['days'] != null) ...[
              const SizedBox(height: 8),
              Text('近 ${_data['days']} 天 · 基于你的饮食记录与健康目标',
                  style: const TextStyle(fontSize: 11, color: Colors.grey)),
            ],
          ],
        ),
      ),
    ));

    // 三大营养素平衡
    final balance = _data['balance'] is Map
        ? Map<String, dynamic>.from(_data['balance'] as Map)
        : <String, dynamic>{};
    if (balance.isNotEmpty) {
      sections.add(Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('⚖️ 营养素平衡',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              _balanceRow('蛋白质', balance['protein'], Colors.indigo),
              _balanceRow('脂肪', balance['fat'], Colors.orange),
              _balanceRow('碳水', balance['carbs'], Colors.teal),
            ],
          ),
        ),
      ));
    }

    // 优点
    final strengths = _data['strengths'] is List
        ? (_data['strengths'] as List).cast<String>()
        : <String>[];
    if (strengths.isNotEmpty) {
      sections.add(Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('✅ 值得肯定',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              ...strengths.map((s) => _bullet(s, Colors.green)),
            ],
          ),
        ),
      ));
    }

    // 改进
    final improvements = _data['improvements'] is List
        ? (_data['improvements'] as List).cast<String>()
        : <String>[];
    if (improvements.isNotEmpty) {
      sections.add(Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('💡 可以更好',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              ...improvements.map((s) => _bullet(s, Colors.orange)),
            ],
          ),
        ),
      ));
    }

    // 个性化建议
    final suggestion = _data['suggestion'] ?? '';
    if (suggestion.toString().isNotEmpty) {
      sections.add(Card(
        color: Theme.of(context).colorScheme.primaryContainer.withValues(alpha: .35),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('🍽️ 给你的建议',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              Text(suggestion.toString()),
            ],
          ),
        ),
      ));
    }

    // P2 饭后安全提醒（与 AI 复盘合并输出）
    final safetyTips = _data['safetyTips'] is List
        ? (_data['safetyTips'] as List).whereType<Map>().toList()
        : <Map>[];
    if (safetyTips.isNotEmpty) {
      sections.add(Card(
        color: const Color(0xFFFFFBF2),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('🛡️ 食物安全提醒',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              for (final it in safetyTips) ...[_reviewSafetyLine(Map<String, dynamic>.from(it)), Row(children: [TextButton(onPressed: () => _sendFeedback(it, 1), child: const Text('👍 有用', style: TextStyle(fontSize: 11))), TextButton(onPressed: () => _sendFeedback(it, 0), child: const Text('👎 无用', style: TextStyle(fontSize: 11)))]), const SizedBox(height: 6)],
            ],
          ),
        ),
      ));
    }

    sections.add(_disclaimer());
    return sections;
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

  Widget _reviewSafetyLine(Map<String, dynamic> it) {
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

  Widget _balanceRow(String label, dynamic text, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 10,
            height: 10,
            child: DecoratedBox(
                decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text.rich(TextSpan(children: [
              TextSpan(
                  text: '$label：',
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              TextSpan(text: '${text ?? ''}'),
            ])),
          ),
        ],
      ),
    );
  }

  Widget _bullet(String text, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.circle, size: 8, color: color),
          const SizedBox(width: 8),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }

  Widget _disclaimer() {
    final d = _data['disclaimer'] ??
        '本建议仅供参考，不构成医疗或营养专业建议。';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 8),
      child: Text('$d',
          style: TextStyle(fontSize: 11, color: Colors.grey.shade500)),
    );
  }
}
