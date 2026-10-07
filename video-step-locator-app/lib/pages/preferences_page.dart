import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/user_preference.dart';
import '../providers/preference_provider.dart';

/// 口味/忌口设置（需登录）
class PreferencesPage extends StatefulWidget {
  const PreferencesPage({super.key});

  @override
  State<PreferencesPage> createState() => _PreferencesPageState();
}

class _PreferencesPageState extends State<PreferencesPage> {
  final _allergens = TextEditingController();
  final _taboo = TextEditingController();
  final _tastes = TextEditingController();
  final _cuisines = TextEditingController();
  final _notes = TextEditingController();
  final _maxCalController = TextEditingController();
  int _spice = 2;
  bool _maxCalEnabled = false;
  int _maxCal = 600;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final p = context.read<PreferenceProvider>();
      p.fetch().then((_) {
        if (!mounted) return;
        _apply(p.pref);
      });
    });
  }

  void _apply(UserPreference? pref) {
    if (pref == null) return;
    _spice = pref.spiceLevel ?? 2;
    _allergens.text = pref.tabooAllergens ?? '';
    _taboo.text = pref.tabooIngredients ?? '';
    _tastes.text = pref.preferredTastes ?? '';
    _cuisines.text = pref.preferredCuisines ?? '';
    _maxCalEnabled = pref.maxCalories != null;
    _maxCal = pref.maxCalories ?? 600;
    _maxCalController.text = _maxCalEnabled ? '$_maxCal' : '';
    _notes.text = pref.notes ?? '';
    setState(() {});
  }

  @override
  void dispose() {
    _allergens.dispose();
    _taboo.dispose();
    _tastes.dispose();
    _cuisines.dispose();
    _notes.dispose();
    _maxCalController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    final pref = UserPreference(
      spiceLevel: _spice,
      tabooAllergens: _allergens.text.trim(),
      tabooIngredients: _taboo.text.trim(),
      preferredTastes: _tastes.text.trim(),
      preferredCuisines: _cuisines.text.trim(),
      maxCalories: _maxCalEnabled ? _maxCal : null,
      notes: _notes.text.trim(),
    );
    final ok = await context.read<PreferenceProvider>().save(pref);
    if (!mounted) return;
    setState(() => _saving = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(ok ? '已保存口味设置' : '保存失败，请重新登录')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('口味 / 忌口设置')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('辣度（${const ['不吃辣', '微辣', '中辣', '较辣', '特辣', '极辣'][_spice]}）'),
          Slider(
            value: _spice.toDouble(),
            min: 0,
            max: 5,
            divisions: 5,
            label: '$_spice',
            onChanged: (v) => setState(() => _spice = v.round()),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _allergens,
            decoration: const InputDecoration(
              labelText: '过敏原',
              hintText: '如：花生、虾、牛奶（逗号分隔）',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _taboo,
            decoration: const InputDecoration(
              labelText: '忌口食材',
              hintText: '如：香菜、肥肉、内脏',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _tastes,
            decoration: const InputDecoration(
              labelText: '偏好口味',
              hintText: '如：酸辣、清淡、甜口',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _cuisines,
            decoration: const InputDecoration(
              labelText: '偏好菜系',
              hintText: '如：川菜、粤菜',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('限制每日摄入热量'),
            value: _maxCalEnabled,
            onChanged: (v) => setState(() => _maxCalEnabled = v),
          ),
          if (_maxCalEnabled)
            TextField(
              controller: _maxCalController,
              keyboardType: TextInputType.number,
              onChanged: (v) => _maxCal = int.tryParse(v) ?? _maxCal,
              decoration: const InputDecoration(
                labelText: '目标热量（千卡）',
                border: OutlineInputBorder(),
              ),
            ),
          const SizedBox(height: 12),
          TextField(
            controller: _notes,
            maxLines: 2,
            decoration: const InputDecoration(
              labelText: '备注',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: _saving ? null : _save,
            child: Text(_saving ? '保存中...' : '保存设置'),
          ),
        ],
      ),
    );
  }
}
