import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/recipe.dart';
import '../providers/auth_provider.dart';
import '../providers/creation_provider.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import '../widgets/calorie_badge.dart';
import 'creation_chat_page.dart';
import 'recipe_detail_page.dart';
import 'xunwei_page.dart';

/// 食材反查页（P0）：「手头有什么食材 → 能做什么菜」。
///
/// 借鉴 HowToCookPHP 交互（只借鉴交互不借鉴代码）：食材标签云按频次、
/// 点击组合即时反查、缺一容错提示、结果附匹配食材高亮 + 热量分级色。
/// 设计文档《拾味_食材反查与内容源_技术设计文档.md》§1.2/§4。
/// 需登录（后端 /api/ingredients/** 走 JWT）。
class IngredientReversePage extends StatefulWidget {
  const IngredientReversePage({super.key});

  @override
  State<IngredientReversePage> createState() => _IngredientReversePageState();
}

class _IngredientReversePageState extends State<IngredientReversePage> {
  final ApiService _api = ApiService();
  List<Map<String, dynamic>> _tags = [];
  final Set<String> _selected = <String>{};
  bool _loadingTags = true;
  bool _searching = false;
  String? _tagError;
  Map<String, dynamic>? _result;
  String _sort = 'like';
  // P2 难度/时长筛选（null=不限）
  int? _maxTime;
  String? _difficulty;

  @override
  void initState() {
    super.initState();
    _loadTags();
  }

  Future<void> _loadTags() async {
    setState(() {
      _loadingTags = true;
      _tagError = null;
    });
    try {
      final items = await _api.getIngredientTags(limit: 80);
      if (!mounted) return;
      setState(() {
        _tags = items
            .map((e) => Map<String, dynamic>.from(e as Map))
            .toList();
        _loadingTags = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingTags = false;
        _tagError = friendlyError(e, fallback: '加载食材标签失败');
      });
    }
  }

  /// 点选/取消食材 → 即时反查（借鉴 HowToCookPHP 点击组合即时渲染）
  Future<void> _toggle(String name) async {
    setState(() {
      if (!_selected.add(name)) _selected.remove(name);
      _result = null;
    });
    if (_selected.isNotEmpty) await _search();
  }

  Future<void> _search() async {
    if (_selected.isEmpty) return;
    setState(() {
      _searching = true;
      _result = null;
    });
    try {
      final r = await _api.reverseIngredients(_selected.toList(),
          sort: _sort, maxTime: _maxTime, difficulty: _difficulty);
      if (!mounted) return;
      setState(() {
        _result = r;
        _searching = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _searching = false);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(friendlyError(e, fallback: '反查失败，请稍后重试'))));
    }
  }

  void _changeSort(String s) {
    if (_sort == s) return;
    setState(() => _sort = s);
    if (_selected.isNotEmpty) _search();
  }

  void _clearAll() {
    setState(() {
      _selected.clear();
      _result = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('按食材找菜'),
        actions: [
          if (_selected.isNotEmpty)
            TextButton(onPressed: _clearAll, child: const Text('清空')),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _loadTags,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(12),
          children: [
            const Text(
              '家里有什么就做什么：点选食材，看看能做出哪些菜（按匹配度 + 热量分级）。',
              style: TextStyle(color: Colors.grey, fontSize: 12),
            ),
            const SizedBox(height: 12),
            _tagSection(),
            const SizedBox(height: 12),
            _sortBar(),
            const SizedBox(height: 8),
            _filterBar(),
            const SizedBox(height: 12),
            if (_searching)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 40),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_result != null)
              _resultSection()
            else
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 40),
                child: Center(
                  child: Text('选择食材后自动查找能做的菜谱',
                      style: TextStyle(color: Colors.grey)),
                ),
              ),
          ],
        ),
      ),
    );
  }

  // ---------------- 标签云 ----------------

  Widget _tagSection() {
    if (_loadingTags) {
      return const Center(
          child: Padding(
              padding: EdgeInsets.all(24), child: CircularProgressIndicator()));
    }
    if (_tagError != null) {
      return Column(
        children: [
          Text(_tagError!, style: const TextStyle(color: Colors.red)),
          const SizedBox(height: 8),
          OutlinedButton(onPressed: _loadTags, child: const Text('重试')),
        ],
      );
    }
    if (_tags.isEmpty) {
      return const Text('暂无食材标签（菜谱需含精确食材才会生成标签）',
          style: TextStyle(color: Colors.grey));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('食材标签云（${_tags.length}）',
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: _tags.map((t) {
            final name = (t['name'] as String?) ?? '';
            final freq = (t['frequency'] as num?)?.toInt() ?? 0;
            final selected = _selected.contains(name);
            return FilterChip(
              label: Text('$name ×$freq'),
              selected: selected,
              onSelected: (_) => _toggle(name),
              visualDensity: VisualDensity.compact,
              selectedColor: Colors.orange.shade100,
              checkmarkColor: Colors.orange.shade800,
            );
          }).toList(),
        ),
      ],
    );
  }

  // ---------------- 排序 ----------------

  Widget _sortBar() {
    const options = [
      ('like', '相关'),
      ('calorie', '低热量'),
      ('difficulty', '难度(时长)'),
    ];
    return Row(
      children: [
        const Text('排序：', style: TextStyle(fontSize: 13)),
        ...options.map((o) => Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(o.$2),
                selected: _sort == o.$1,
                onSelected: (_) => _changeSort(o.$1),
                visualDensity: VisualDensity.compact,
              ),
            )),
      ],
    );
  }

  // ---------------- 难度/时长筛选（P2） ----------------

  Widget _filterBar() {
    const List<(int?, String)> timeOpts = [
      (null, '时长不限'),
      (15, '≤15分'),
      (30, '≤30分'),
      (45, '≤45分'),
      (60, '≤60分'),
    ];
    const List<(String?, String)> diffOpts = [
      (null, '难度不限'),
      ('simple', '简单'),
      ('medium', '中等'),
      ('hard', '困难'),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: timeOpts.map((o) => ChoiceChip(
                label: Text(o.$2),
                selected: _maxTime == o.$1,
                onSelected: (_) => _changeMaxTime(o.$1),
                visualDensity: VisualDensity.compact,
              )).toList(),
        ),
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: diffOpts.map((o) => ChoiceChip(
                label: Text(o.$2),
                selected: _difficulty == o.$1,
                onSelected: (_) => _changeDifficulty(o.$1),
                visualDensity: VisualDensity.compact,
              )).toList(),
        ),
      ],
    );
  }

  void _changeMaxTime(int? t) {
    if (_maxTime == t) return;
    setState(() {
      _maxTime = t;
      _result = null;
    });
    if (_selected.isNotEmpty) _search();
  }

  void _changeDifficulty(String? d) {
    if (_difficulty == d) return;
    setState(() {
      _difficulty = d;
      _result = null;
    });
    if (_selected.isNotEmpty) _search();
  }

  // ---------------- 结果 ----------------

  Widget _resultSection() {
    final r = _result!;
    final results = (r['results'] as List?) ?? [];
    final autoDg = r['autoDowngraded'] == true;
    final total = (r['total'] as num?)?.toInt() ?? results.length;

    if (results.isEmpty) return _emptyGuide();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (autoDg)
          Container(
            width: double.infinity,
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            decoration: BoxDecoration(
              color: Colors.orange.shade50,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Colors.orange.shade200),
            ),
            child: Text('未完全匹配：已自动放宽为「缺一匹配」，以下菜谱仅命中部分食材',
                style: TextStyle(fontSize: 12, color: Colors.orange.shade900)),
          ),
        Text('找到 $total 道菜',
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        ...results.map((e) => _resultCard(Map<String, dynamic>.from(e as Map))),
      ],
    );
  }

  Widget _resultCard(Map<String, dynamic> r) {
    final matched = (r['matchedIngredients'] as List?)?.cast<String>() ?? [];
    final calories = r['calories'] as int?;
    final recipeType = r['recipeType'] as String?;
    final recipe = Recipe.fromJson(r);
    final meta = <String>[
      if (r['cuisine'] is String && (r['cuisine'] as String).isNotEmpty)
        r['cuisine'] as String,
      if (r['estimatedTime'] is int) '${r['estimatedTime']}分钟',
      if (r['tasteTags'] is String && (r['tasteTags'] as String).isNotEmpty)
        r['tasteTags'] as String,
    ];
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: ListTile(
        title: Row(
          children: [
            Flexible(
              child: Text(r['title'] ?? '',
                  maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
            if (calories != null) ...[
              const SizedBox(width: 6),
              CalorieBadge(calories),
            ],
            if (recipeType == 'INDUSTRIAL') ...[
              const SizedBox(width: 6),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                decoration: BoxDecoration(
                  color: Colors.blue.shade50,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(color: Colors.blue.shade200),
                ),
                child: const Text('工业型',
                    style:
                        TextStyle(fontSize: 10, color: Colors.blue, fontWeight: FontWeight.w500)),
              ),
            ],
          ],
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (matched.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Wrap(
                  spacing: 4,
                  runSpacing: 4,
                  children:
                      matched.map((m) => _matchChip(m)).toList(),
                ),
              ),
            if (meta.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(meta.join(' · '),
                    style: const TextStyle(fontSize: 12, color: Colors.grey)),
              ),
          ],
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.play_circle_outline),
              color: Colors.teal,
              tooltip: '找视频教程',
              onPressed: () => _jumpToXunwei(recipe.title),
            ),
            const Icon(Icons.chevron_right),
          ],
        ),
        onTap: () {
          Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => RecipeDetailPage(recipe: recipe)),
          );
        },
      ),
    );
  }

  /// 跳寻味：找该菜的视频教程（P2，带语境自动搜索）
  void _jumpToXunwei(String title) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => XunweiPage(
          initialPrompt: '「$title」的做法视频教程，请帮我找到并推荐。',
        ),
      ),
    );
  }

  /// 匹配食材高亮 chip（绿色对勾）
  Widget _matchChip(String name) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.green.shade50,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: Colors.green.shade300),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.check, size: 12, color: Colors.green.shade700),
          const SizedBox(width: 2),
          Text(name,
              style: TextStyle(fontSize: 11, color: Colors.green.shade800)),
        ],
      ),
    );
  }

  /// 空结果引导：去酿味创作 / 调整食材
  Widget _emptyGuide() {
    final loggedIn = context.read<AuthProvider>().isLoggedIn;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 40),
      child: Column(
        children: [
          const Icon(Icons.ramen_dining, size: 48, color: Colors.grey),
          const SizedBox(height: 12),
          const Text('这些食材还没有现成的菜谱', style: TextStyle(fontSize: 15)),
          const SizedBox(height: 6),
          const Text('要不要去「酿味」用这些食材创作一道新菜？',
              style: TextStyle(color: Colors.grey, fontSize: 13)),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: loggedIn ? _createWithIngredients : null,
            icon: const Icon(Icons.auto_awesome),
            label: const Text('去酿味创作'),
          ),
          const SizedBox(height: 8),
          TextButton(onPressed: _clearAll, child: const Text('调整食材选择')),
        ],
      ),
    );
  }

  /// 空结果 → 酿味创作（P2「带食材约束」）：新建会话并自动发送食材创作意图
  Future<void> _createWithIngredients() async {
    final p = context.read<CreationProvider>();
    int sid;
    try {
      sid = await p.createSession();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(friendlyError(e, fallback: '创建创作会话失败，请稍后重试'))));
      return;
    }
    final prompt = _selected.isEmpty
        ? '我想创作一道新菜'
        : '我手头有这些食材：${_selected.join('、')}。请帮我想一道用这些食材做的新菜。';
    if (!mounted) return;
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) =>
            CreationChatPage(sessionId: sid, initialPrompt: prompt),
      ),
    );
  }
}
