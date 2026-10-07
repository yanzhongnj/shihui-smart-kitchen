import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// 健康档案页（营养健康模块 P1）
/// 身高/体重/目标体重/性别/年龄/运动量/目标 → 后端 Mifflin-St Jeor 自动算 BMR/TDEE/营养目标。
class HealthProfilePage extends StatefulWidget {
  const HealthProfilePage({super.key});

  @override
  State<HealthProfilePage> createState() => _HealthProfilePageState();
}

class _HealthProfilePageState extends State<HealthProfilePage> {
  final ApiService _api = ApiService();

  final _heightC = TextEditingController();
  final _weightC = TextEditingController();
  final _targetWeightC = TextEditingController();
  final _ageC = TextEditingController();
  final _medC = TextEditingController();
  final _condC = TextEditingController();

  String _gender = 'male';
  String _activity = 'moderate';
  String _goal = 'maintain';

  Map<String, dynamic> _profile = {};
  bool _loading = true;
  bool _saving = false;
  String? _error;

  static const Map<String, String> _genderLabels = {
    'male': '男',
    'female': '女',
  };
  static const Map<String, String> _activityLabels = {
    'sedentary': '久坐',
    'light': '轻度活动',
    'moderate': '中等活动',
    'active': '活跃',
    'very_active': '高强度',
  };
  static const Map<String, String> _goalLabels = {
    'lose_weight': '减脂',
    'maintain': '维持',
    'gain_muscle': '增肌',
  };

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _heightC.dispose();
    _weightC.dispose();
    _targetWeightC.dispose();
    _ageC.dispose();
    _medC.dispose();
    _condC.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final p = await _api.getHealthProfile();
      if (!mounted) return;
      setState(() {
        _profile = p;
        if (p['exists'] == true) {
          _heightC.text = _num(p['height']);
          _weightC.text = _num(p['weight']);
          _targetWeightC.text = _num(p['targetWeight']);
          _ageC.text = '${p['age'] ?? ''}';
          _gender = '${p['gender'] ?? 'male'}';
          _activity = '${p['activityLevel'] ?? 'moderate'}';
          _goal = '${p['goal'] ?? 'maintain'}';          _medC.text = '${p['medications'] ?? ''}';
          _condC.text = '${p['conditions'] ?? ''}';        }
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载健康档案失败');
        _loading = false;
      });
    }
  }

  Future<void> _save() async {
    final h = double.tryParse(_heightC.text.trim());
    final w = double.tryParse(_weightC.text.trim());
    final tw = double.tryParse(_targetWeightC.text.trim());
    final age = int.tryParse(_ageC.text.trim());
    if (h == null || w == null || age == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请填写完整的身高、体重、年龄')));
      return;
    }
    setState(() => _saving = true);
    try {
      final saved = await _api.updateHealthProfile({
        'height': h,
        'weight': w,
        if (tw != null) 'targetWeight': tw,
        'gender': _gender,
        'age': age,
        'activityLevel': _activity,
        'goal': _goal,
        'medications': _medC.text.trim(),
        'conditions': _condC.text.trim(),
      });
      if (!mounted) return;
      setState(() {
        _profile = saved;
        _saving = false;
      });
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('健康档案已保存')));
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败：${friendlyError(e)}')));
    }
  }

  static String _num(dynamic v) {
    if (v == null) return '';
    return '${v is num ? (v == v.roundToDouble() ? v.toInt() : v) : v}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('健康档案')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(_error!),
                      const SizedBox(height: 12),
                      FilledButton(onPressed: _load, child: const Text('重试')),
                    ],
                  ),
                )
              : ListView(
                  padding: const EdgeInsets.all(12),
                  children: [
                    _buildFormCard(),
                    const SizedBox(height: 12),
                    _buildTargetCard(),
                  ],
                ),
    );
  }

  Widget _buildFormCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('身体数据',
                style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.bold,
                    color: Theme.of(context).colorScheme.primary)),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _heightC,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                        labelText: '身高（cm）', hintText: '如 170'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: TextField(
                    controller: _weightC,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                        labelText: '体重（kg）', hintText: '如 65'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _targetWeightC,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                  labelText: '目标体重（kg，可选）', hintText: '如 62'),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _ageC,
                    keyboardType: TextInputType.number,
                    decoration:
                        const InputDecoration(labelText: '年龄', hintText: '如 30'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('性别'),
                      const SizedBox(height: 4),
                      SegmentedButton<String>(
                        segments: _genderLabels.entries
                            .map((e) => ButtonSegment(
                                value: e.key, label: Text(e.value)))
                            .toList(),
                        selected: {_gender},
                        onSelectionChanged: (s) =>
                            setState(() => _gender = s.first),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            const Text('日常活动量'),
            const SizedBox(height: 4),
            Wrap(
              spacing: 6,
              children: _activityLabels.entries.map((e) {
                final sel = e.key == _activity;
                return ChoiceChip(
                  label: Text(e.value),
                  selected: sel,
                  onSelected: (_) => setState(() => _activity = e.key),
                );
              }).toList(),
            ),
            const SizedBox(height: 16),
            const Text('健康目标'),
            const SizedBox(height: 4),
            Wrap(
              spacing: 6,
              children: _goalLabels.entries.map((e) {
                final sel = e.key == _goal;
                return ChoiceChip(
                  label: Text(e.value),
                  selected: sel,
                  onSelected: (_) => setState(() => _goal = e.key),
                );
              }).toList(),
            ),
            const SizedBox(height: 16),
            const Text('健康信息（可选，仅本人可见可清除）',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            TextField(
              controller: _medC,
              decoration: const InputDecoration(
                labelText: '用药情况',
                hintText: '如：华法林、头孢、布洛芬（逗号分隔）',
                helperText: '用于食物-药物安全提醒，仅本人可见',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _condC,
              decoration: const InputDecoration(
                labelText: '疾病情况',
                hintText: '如：痛风、糖尿病、高血压、孕产（逗号分隔）',
              ),
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: _saving ? null : _save,
                child: Text(_saving ? '保存中…' : '保存并计算目标'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTargetCard() {
    final has = _profile['targetCalories'] != null;
    if (!has) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text(
            '完善身体数据后，将自动计算你的基础代谢（BMR）、每日消耗（TDEE）与个性化营养目标（热量/蛋白/脂肪/碳水）。',
            style: TextStyle(color: Colors.grey.shade600),
          ),
        ),
      );
    }
    final bmr = _profile['bmr'];
    final tdee = _profile['tdee'];
    final tc = _profile['targetCalories'];
    final tp = _profile['targetProtein'];
    final tf = _profile['targetFat'];
    final tcarbs = _profile['targetCarbs'];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('⚡ 我的营养目标',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            _targetRow('基础代谢 BMR', '$bmr 千卡', Icons.bolt),
            _targetRow('每日消耗 TDEE', '$tdee 千卡', Icons.local_fire_department),
            _targetRow('目标热量', '$tc 千卡', Icons.restaurant),
            const SizedBox(height: 8),
            const Divider(),
            _macroRow('蛋白质', '$tp g', 0.15, Colors.indigo),
            _macroRow('脂肪', '$tf g', 0.25, Colors.orange),
            _macroRow('碳水', '$tcarbs g', 0.60, Colors.teal),
          ],
        ),
      ),
    );
  }

  Widget _targetRow(String label, String value, IconData icon) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 18, color: Colors.grey.shade600),
          const SizedBox(width: 8),
          Text(label),
          const Spacer(),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }

  Widget _macroRow(String label, String value, double ratio, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 10,
            height: 10,
            child: DecoratedBox(decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
          ),
          const SizedBox(width: 8),
          Text(label),
          const Spacer(),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}
