import 'package:flutter/material.dart';
import '../models/recipe.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

/// USER 菜谱录入/编辑页：菜名/摘要/份量/食材/步骤 + 工业型开关（强校验由后端执行）
/// [recipe] 非空 = 编辑模式（创建者/管理员），预填表单并调 updateRecipe
class RecipeCreatePage extends StatefulWidget {
  final Recipe? recipe;
  const RecipeCreatePage({super.key, this.recipe});

  @override
  State<RecipeCreatePage> createState() => _RecipeCreatePageState();
}

class _RecipeCreatePageState extends State<RecipeCreatePage> {
  final _titleCtrl = TextEditingController();
  final _summaryCtrl = TextEditingController();
  final _cuisineCtrl = TextEditingController();
  final _servingsCtrl = TextEditingController(text: '2');
  final _caloriesCtrl = TextEditingController();
  final _tasteCtrl = TextEditingController();
  final _urlCtrl = TextEditingController();
  bool _industrial = false;
  bool _submitting = false;
  bool _parsing = false;
  String? _articleSourceUrl;

  final List<_IngredientRow> _ingredients = [
    _IngredientRow(),
  ];
  final List<_StepRow> _steps = [
    _StepRow(),
  ];

  @override
  void initState() {
    super.initState();
    final r = widget.recipe;
    if (r == null) return;
    // 编辑模式：从菜谱预填表单（保持来源/溯源，只改内容）
    _titleCtrl.text = r.title;
    _summaryCtrl.text = r.summary ?? '';
    _cuisineCtrl.text = r.cuisine ?? '';
    _servingsCtrl.text = '${r.servings ?? 2}';
    _caloriesCtrl.text = r.calories?.toString() ?? '';
    _tasteCtrl.text = r.tasteTags ?? '';
    _industrial = r.recipeType == 'INDUSTRIAL';
    _articleSourceUrl = r.source == 'ARTICLE_LINK' ? (r.sourceUrl ?? '') : null;
    if ((r.ingredients ?? []).isNotEmpty) {
      _ingredients
        ..clear()
        ..addAll(r.ingredients!
            .map((e) => _IngredientRow.fromDraft(e as Map<String, dynamic>)));
    }
    if ((r.steps ?? []).isNotEmpty) {
      _steps
        ..clear()
        ..addAll(
            r.steps!.map((e) => _StepRow.fromDraft(e as Map<String, dynamic>)));
    }
  }

  @override
  void dispose() {
    _urlCtrl.dispose();
    for (final r in _ingredients) {
      r.dispose();
    }
    for (final r in _steps) {
      r.dispose();
    }
    super.dispose();
  }

  /// 图文解析（ARTICLE_LINK）：抓取 → LLM 结构化草稿 → 回填表单
  Future<void> _parseArticle() async {
    final url = _urlCtrl.text.trim();
    if (url.isEmpty) {
      _toast('请粘贴文章链接');
      return;
    }
    setState(() => _parsing = true);
    try {
      final data = await ApiService().parseArticle(url);
      final draft = (data['draft'] as Map<String, dynamic>?) ?? {};
      _titleCtrl.text = (draft['title'] as String?) ?? '';
      _summaryCtrl.text = (draft['summary'] as String?) ?? '';
      _cuisineCtrl.text = (draft['cuisine'] as String?) ?? '';
      _servingsCtrl.text = '${draft['servings'] ?? 2}';
      _caloriesCtrl.text = draft['calories'] != null ? '${draft['calories']}' : '';
      final tags = (draft['tasteTags'] as List?) ?? const [];
      _tasteCtrl.text = tags.join(',');
      final ingList = (draft['ingredients'] as List?) ?? const [];
      _ingredients
        ..clear()
        ..addAll(ingList.map((e) => _IngredientRow.fromDraft(e as Map<String, dynamic>)));
      if (_ingredients.isEmpty) _ingredients.add(_IngredientRow());
      final stepList = (draft['steps'] as List?) ?? const [];
      _steps
        ..clear()
        ..addAll(stepList.map((e) => _StepRow.fromDraft(e as Map<String, dynamic>)));
      if (_steps.isEmpty) _steps.add(_StepRow());
      _industrial = draft['industrial'] == true;
      _articleSourceUrl = data['sourceUrl'] as String?;
      final titleTxt = draft['title'] as String? ?? '';
      final ingCount = (draft['ingredients'] as List?)?.length ?? 0;
      final stepCount = (draft['steps'] as List?)?.length ?? 0;
      final emptyDraft = titleTxt.trim().isEmpty && ingCount == 0 && stepCount == 0;
      if (!mounted) return;
      setState(() => _parsing = false);
      _toast(emptyDraft
          ? '未能从该链接解析出菜谱内容，请手动填写或更换链接'
          : '解析完成，请核对后保存');
    } catch (e) {
      if (!mounted) return;
      setState(() => _parsing = false);
      _toast(friendlyError(e, fallback: '解析失败，请稍后重试'));
    }
  }

  Future<void> _submit() async {
    final title = _titleCtrl.text.trim();
    if (title.isEmpty) {
      _toast('请填写菜名');
      return;
    }
    final ing = <Map<String, dynamic>>[];
    for (final r in _ingredients) {
      final name = r.nameCtrl.text.trim();
      if (name.isEmpty) continue;
      ing.add({
        'name': name,
        'amount': r.amountCtrl.text.trim().isEmpty ? null : double.tryParse(r.amountCtrl.text.trim()),
        'unit': r.unitCtrl.text.trim(),
        'tolerance': null,
        'isMain': r.isMain,
        'note': r.noteCtrl.text.trim().isEmpty ? null : r.noteCtrl.text.trim(),
      });
    }
    final steps = <Map<String, dynamic>>[];
    for (final r in _steps) {
      final summary = r.summaryCtrl.text.trim();
      if (summary.isEmpty) continue;
      steps.add({
        'summary': summary,
        'detail': r.detailCtrl.text.trim(),
        'timerMin': r.timerCtrl.text.trim().isEmpty ? null : int.tryParse(r.timerCtrl.text.trim()),
        'temperature': r.tempCtrl.text.trim().isEmpty ? null : int.tryParse(r.tempCtrl.text.trim()),
        'mode': r.modeCtrl.text.trim(),
      });
    }
    if (ing.isEmpty) {
      _toast('请至少填写一种食材');
      return;
    }
    if (steps.isEmpty) {
      _toast('请至少填写一个步骤');
      return;
    }
    if (_industrial) {
      // 工业型强校验前置提示（与后端 RecipeValidationService 一致），避免提交后 400
      const units = {'g', 'ml', '个', '勺', '片', '瓣', '人份', '克', '毫升'};
      const qual = {'适量', '少许', '一把', '若干', '少量', '一点', '一点点', '略'};
      for (var i = 0; i < ing.length; i++) {
        final name = ing[i]['name'] as String;
        final amt = ing[i]['amount'];
        final unit = ((ing[i]['unit'] as String?) ?? '').trim().toLowerCase();
        if (amt == null) {
          _toast('工业型：食材「$name」需给出精确用量（克/毫升）');
          return;
        }
        if (!units.contains(unit)) {
          _toast('工业型：食材「$name」用量单位须为 ${units.join('/')}');
          return;
        }
        for (final q in qual) {
          if (('$amt').contains(q)) {
            _toast('工业型：食材「$name」不能使用「$q」等定性描述');
            return;
          }
        }
      }
      for (var i = 0; i < steps.length; i++) {
        final s = steps[i];
        final has = s['timerMin'] != null ||
            s['temperature'] != null ||
            ((s['mode'] as String?) ?? '').trim().isNotEmpty;
        if (!has) {
          _toast('工业型：第 ${i + 1} 步需填写 计时(min) 或 温度℃/模式');
          return;
        }
      }
    }
    final body = <String, dynamic>{
      'title': title,
      'summary': _summaryCtrl.text.trim().isEmpty ? null : _summaryCtrl.text.trim(),
      'cuisine': _cuisineCtrl.text.trim().isEmpty ? null : _cuisineCtrl.text.trim(),
      'servings': int.tryParse(_servingsCtrl.text.trim()),
      'calories': _caloriesCtrl.text.trim().isEmpty ? null : int.tryParse(_caloriesCtrl.text.trim()),
      'tasteTags': _tasteCtrl.text.trim().isEmpty
          ? null
          : _tasteCtrl.text.split(RegExp(r'[,，]')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList(),
      'industrial': _industrial,
      'source': _articleSourceUrl != null ? 'ARTICLE_LINK' : 'USER',
      if (_articleSourceUrl != null) 'sourceUrl': _articleSourceUrl,
      'ingredients': ing,
      'steps': steps,
    };
    setState(() => _submitting = true);
    try {
      if (widget.recipe != null) {
        await ApiService().updateRecipe(widget.recipe!.id, body);
      } else {
        await ApiService().createRecipe(body);
      }
      if (!mounted) return;
      Navigator.of(context).pop(true);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_industrial ? '保存成功（工业型已强校验）' : '保存成功')),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _toast(friendlyError(e, fallback: '录入失败，请稍后重试'));
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.recipe != null ? '编辑菜谱' : '录入菜谱')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (widget.recipe == null)
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _urlCtrl,
                    decoration: const InputDecoration(
                      labelText: '粘贴文章链接（可选，解析图文菜谱）',
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.tonal(
                  onPressed: _parsing ? null : _parseArticle,
                  child: Text(_parsing ? '解析中...' : '解析'),
                ),
              ],
            ),
          const SizedBox(height: 12),
          TextField(
            controller: _titleCtrl,
            decoration: const InputDecoration(labelText: '菜名 *', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _summaryCtrl,
            maxLines: 2,
            decoration: const InputDecoration(labelText: '摘要', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _cuisineCtrl,
                  decoration: const InputDecoration(labelText: '菜系', border: OutlineInputBorder()),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: TextField(
                  controller: _servingsCtrl,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: '份量(人份)', border: OutlineInputBorder()),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _caloriesCtrl,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: '热量(千卡)', border: OutlineInputBorder()),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: TextField(
                  controller: _tasteCtrl,
                  decoration: const InputDecoration(labelText: '口味(逗号分隔)', border: OutlineInputBorder()),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('工业型（精确可度量）'),
            subtitle: const Text('主料须给克/毫升精确用量；每步须有计时或温度/模式'),
            value: _industrial,
            onChanged: (v) => setState(() => _industrial = v),
          ),
          if (_industrial)
            Container(
              margin: const EdgeInsets.only(top: 4),
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.orange.shade50,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.orange.shade200),
              ),
              child: const Text(
                '工业型保存前请确保：①每样食材都有精确用量，单位在 g/ml/个/勺/片/瓣/人份/克/毫升 内；②每一步都填了 计时 或 温度/模式。不满足时提交会被拦截并提示具体项。',
                style: TextStyle(fontSize: 12, color: Colors.deepOrange),
              ),
            ),
          const SizedBox(height: 16),
          Row(
            children: [
              const Text('食材', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.add_circle_outline),
                onPressed: () => setState(() => _ingredients.add(_IngredientRow())),
              ),
            ],
          ),
          ..._ingredients.asMap().entries.map((e) => _ingredientCard(e.key, e.value)),
          const SizedBox(height: 16),
          Row(
            children: [
              const Text('步骤', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.add_circle_outline),
                onPressed: () => setState(() => _steps.add(_StepRow())),
              ),
            ],
          ),
          ..._steps.asMap().entries.map((e) => _stepCard(e.key, e.value)),
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: _submitting ? null : _submit,
            icon: const Icon(Icons.check),
            label: Text(_submitting ? '提交中...' : '提交菜谱'),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Widget _ingredientCard(int index, _IngredientRow r) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  flex: 3,
                  child: TextField(
                    controller: r.nameCtrl,
                    decoration: const InputDecoration(
                      labelText: '食材名称 *',
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.delete_outline),
                  tooltip: '删除该食材',
                  onPressed: _ingredients.length > 1
                      ? () => setState(() => _ingredients.removeAt(index))
                      : null,
                ),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                SizedBox(
                  width: 100,
                  child: TextField(
                    controller: r.amountCtrl,
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(
                        labelText: '用量', isDense: true, border: OutlineInputBorder()),
                  ),
                ),
                SizedBox(
                  width: 90,
                  child: TextField(
                    controller: r.unitCtrl,
                    decoration: const InputDecoration(
                        labelText: '单位', isDense: true, border: OutlineInputBorder()),
                  ),
                ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Checkbox(
                      value: r.isMain,
                      visualDensity: VisualDensity.compact,
                      onChanged: (v) => setState(() => r.isMain = v ?? false),
                    ),
                    const Text('主料'),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 8),
            TextField(
              controller: r.noteCtrl,
              decoration: const InputDecoration(
                labelText: '备注（可选）',
                isDense: true,
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _stepCard(int index, _StepRow r) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 12,
                  backgroundColor: Colors.deepOrange.shade50,
                  child: Text('${index + 1}',
                      style: const TextStyle(fontSize: 12, color: Colors.deepOrange)),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: r.summaryCtrl,
                    decoration: const InputDecoration(
                      labelText: '步骤概括 *',
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.delete_outline),
                  tooltip: '删除该步骤',
                  onPressed: _steps.length > 1
                      ? () => setState(() => _steps.removeAt(index))
                      : null,
                ),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                SizedBox(
                  width: 105,
                  child: TextField(
                    controller: r.timerCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                        labelText: '计时(min)', isDense: true, border: OutlineInputBorder()),
                  ),
                ),
                SizedBox(
                  width: 95,
                  child: TextField(
                    controller: r.tempCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                        labelText: '温度℃', isDense: true, border: OutlineInputBorder()),
                  ),
                ),
                SizedBox(
                  width: 120,
                  child: TextField(
                    controller: r.modeCtrl,
                    decoration: const InputDecoration(
                        labelText: '模式(上下火等)', isDense: true, border: OutlineInputBorder()),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            TextField(
              controller: r.detailCtrl,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: '操作要点（可选）',
                isDense: true,
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _IngredientRow {
  _IngredientRow();
  final nameCtrl = TextEditingController();
  final amountCtrl = TextEditingController();
  final unitCtrl = TextEditingController();
  final noteCtrl = TextEditingController();
  bool isMain = false;
  void dispose() {
    nameCtrl.dispose();
    amountCtrl.dispose();
    unitCtrl.dispose();
    noteCtrl.dispose();
  }

  factory _IngredientRow.fromDraft(Map<String, dynamic> m) {
    final r = _IngredientRow();
    r.nameCtrl.text = m['name']?.toString() ?? '';
    r.amountCtrl.text = m['amount']?.toString() ?? '';
    r.unitCtrl.text = m['unit']?.toString() ?? '';
    r.noteCtrl.text = m['note']?.toString() ?? '';
    r.isMain = m['isMain'] == 1 || m['isMain'] == true;
    return r;
  }
}

class _StepRow {
  _StepRow();
  final summaryCtrl = TextEditingController();
  final detailCtrl = TextEditingController();
  final timerCtrl = TextEditingController();
  final tempCtrl = TextEditingController();
  final modeCtrl = TextEditingController();
  void dispose() {
    summaryCtrl.dispose();
    detailCtrl.dispose();
    timerCtrl.dispose();
    tempCtrl.dispose();
    modeCtrl.dispose();
  }

  factory _StepRow.fromDraft(Map<String, dynamic> m) {
    final r = _StepRow();
    r.summaryCtrl.text = m['summary']?.toString() ?? '';
    r.detailCtrl.text = m['detail']?.toString() ?? '';
    r.timerCtrl.text = m['timerMin']?.toString() ?? '';
    r.tempCtrl.text = m['temperature']?.toString() ?? '';
    r.modeCtrl.text = m['mode']?.toString() ?? '';
    return r;
  }
}
