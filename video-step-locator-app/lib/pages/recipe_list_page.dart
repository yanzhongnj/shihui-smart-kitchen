import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/recipe.dart';
import '../providers/auth_provider.dart';
import '../providers/recipe_provider.dart';
import '../widgets/calorie_badge.dart';
import 'recipe_create_page.dart';
import 'recipe_detail_page.dart';
import 'login_page.dart';
import 'ingredient_reverse_page.dart';

/// 菜谱列表（游客只读可见）
class RecipeListPage extends StatefulWidget {
  const RecipeListPage({super.key});

  @override
  State<RecipeListPage> createState() => _RecipeListPageState();
}

class _RecipeListPageState extends State<RecipeListPage> {
  String? _cuisine;
  String? _taste;
  String? _type;
  String? _source;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<RecipeProvider>().fetchRecipes();
    });
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<RecipeProvider>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('品味'),
        actions: [
          IconButton(
            icon: const Icon(Icons.kitchen_outlined),
            onPressed: _openReverse,
            tooltip: '按食材找菜',
          ),
          IconButton(
            icon: const Icon(Icons.filter_list),
            onPressed: _showFilter,
            tooltip: '筛选',
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _openCreate,
        icon: const Icon(Icons.add),
        label: const Text('录入菜谱'),
      ),
      body: RefreshIndicator(
        onRefresh: () => provider.fetchRecipes(cuisine: _cuisine, taste: _taste, source: _source, type: _type),
        child: _buildBody(provider),
      ),
    );
  }

  /// 录入菜谱（需登录；成功后刷新列表）
  Future<void> _openCreate() async {
    if (!context.read<AuthProvider>().isLoggedIn) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('请先登录后录入菜谱')));
      return;
    }
    final created = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => const RecipeCreatePage()),
    );
    if (created == true && mounted) {
      context.read<RecipeProvider>().fetchRecipes(cuisine: _cuisine, taste: _taste, source: _source, type: _type);
    }
  }

  /// 食材反查（P0）：需登录；游客弹登录引导
  Future<void> _openReverse() async {
    if (!context.read<AuthProvider>().isLoggedIn) {
      final go = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('「按食材找菜」需要登录后使用'),
          content: const Text('登录后可按手头食材反查能做的菜谱，并查看热量分级。'),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(ctx).pop(false),
                child: const Text('取消')),
            FilledButton(
                onPressed: () => Navigator.of(ctx).pop(true),
                child: const Text('去登录')),
          ],
        ),
      );
      if (go == true && mounted) {
        Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const LoginPage()));
      }
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const IngredientReversePage()),
    );
  }

  Widget _buildBody(RecipeProvider provider) {
    if (provider.isLoading && provider.recipes.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (provider.recipes.isEmpty) {
      return ListView(
        children: const [
          SizedBox(height: 120),
          Center(child: Text('暂无公开菜谱', style: TextStyle(color: Colors.grey))),
          SizedBox(height: 8),
          Center(
            child: Text(
              '注册用户处理视频后会自动生成菜谱',
              style: TextStyle(color: Colors.grey, fontSize: 12),
            ),
          ),
        ],
      );
    }
    return ListView.builder(
      physics: const AlwaysScrollableScrollPhysics(),
      itemCount: provider.recipes.length,
      itemBuilder: (context, i) {
        final r = provider.recipes[i];
        return Card(
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: ListTile(
            leading: _CompatBadge(level: r.compatLevel, hasCompat: r.hasCompatibility),
            title: Row(
              children: [
                Flexible(
                  child: Text(r.title,
                      maxLines: 1, overflow: TextOverflow.ellipsis),
                ),
                if (r.calories != null) ...[
                  const SizedBox(width: 6),
                  CalorieBadge(r.calories),
                ],
                if (r.sourceType == 'ai_creation') ...[
                  const SizedBox(width: 6),
                  Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 5, vertical: 1),
                    decoration: BoxDecoration(
                      color: Colors.deepPurple.shade50,
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(color: Colors.deepPurple.shade200),
                    ),
                    child: const Text('AI 创作',
                        style: TextStyle(
                            fontSize: 10,
                            color: Colors.deepPurple,
                            fontWeight: FontWeight.w500)),
                  ),
                ],
              ],
            ),
            subtitle: Text(
              _subtitle(r),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            isThreeLine: false,
            trailing: const Icon(Icons.chevron_right),
            onTap: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => RecipeDetailPage(recipe: r)),
              );
            },
          ),
        );
      },
    );
  }

  String _subtitle(Recipe r) {
    final parts = <String>[
      if (r.cuisine != null && r.cuisine!.isNotEmpty) r.cuisine!,
      if (r.tasteTags != null && r.tasteTags!.isNotEmpty) r.tasteTags!,
      if (r.estimatedTime != null) '${r.estimatedTime}分钟',
      if (r.spiceLevel != null) '辣度${r.spiceLevel}',
    ];
    return parts.isEmpty ? (r.summary ?? '') : parts.join(' · ');
  }

  void _showFilter() {
    const cuisines = ['', '川菜', '粤菜', '鲁菜', '苏菜', '湘菜', '闽菜', '浙菜', '徽菜', '家常', '西餐'];
    const tastes = ['', '酸甜', '咸鲜', '麻辣', '鲜香', '清淡', '微辣', '香辣', '甜', '酸'];
    const types = [
      ('', '全部类型'),
      ('INDUSTRIAL', '工业型'),
      ('HOME', '家常型'),
    ];
    const sources = [
      ('', '全部来源'),
      ('VIDEO_LINK', '视频菜谱'),
      ('ARTICLE_LINK', '图文录入'),
      ('USER', '手动录入'),
      ('AI_CREATE', 'AI创作'),
    ];
    // 默认展开第一个已有筛选条件的分区，否则展开「按菜系」
    var open = _cuisine != null
        ? 0
        : _taste != null
            ? 1
            : _type != null
                ? 2
                : _source != null
                    ? 3
                    : 0;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setSheetState) {
            // 生成某个分区内的筛选选项：ChoiceChip 横排，点选即应用并关闭
            Widget chips<T>(
              List<T> items,
              String? current,
              String Function(T) label,
              String Function(T) value,
              void Function(String?) apply,
            ) {
              final sel = current ?? '';
              return Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final it in items)
                    ChoiceChip(
                      label: Text(label(it), style: const TextStyle(fontSize: 13)),
                      selected: sel == value(it),
                      onSelected: (_) {
                        final v = value(it);
                        Navigator.of(ctx).pop();
                        setState(() => apply(v.isEmpty ? null : v));
                        context.read<RecipeProvider>().fetchRecipes(
                            cuisine: _cuisine,
                            taste: _taste,
                            source: _source,
                            type: _type);
                      },
                    ),
                ],
              );
            }

            return SafeArea(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 14, 8, 4),
                    child: Row(
                      children: [
                        const Expanded(
                          child: Text('筛选菜谱',
                              style: TextStyle(
                                  fontSize: 17, fontWeight: FontWeight.bold)),
                        ),
                        TextButton(
                          onPressed: () {
                            Navigator.of(ctx).pop();
                            setState(() {
                              _cuisine = null;
                              _taste = null;
                              _type = null;
                              _source = null;
                            });
                            context.read<RecipeProvider>().fetchRecipes();
                          },
                          child: const Text('重置'),
                        ),
                      ],
                    ),
                  ),
                  Flexible(
                    child: SingleChildScrollView(
                      child: Column(
                        children: [
                          _FilterSection(
                            title: '按菜系筛选',
                            expanded: open == 0,
                            active: _cuisine != null,
                            onToggle: () => setSheetState(
                                () => open = open == 0 ? -1 : 0),
                            child: chips(
                              cuisines, _cuisine, (c) => c.isEmpty ? '全部' : c,
                              (c) => c, (v) => _cuisine = v),
                          ),
                          _FilterSection(
                            title: '按口味筛选',
                            expanded: open == 1,
                            active: _taste != null,
                            onToggle: () => setSheetState(
                                () => open = open == 1 ? -1 : 1),
                            child: chips(
                              tastes, _taste, (ta) => ta.isEmpty ? '全部' : ta,
                              (ta) => ta, (v) => _taste = v),
                          ),
                          _FilterSection(
                            title: '按类型筛选',
                            expanded: open == 2,
                            active: _type != null,
                            onToggle: () => setSheetState(
                                () => open = open == 2 ? -1 : 2),
                            child: chips(
                              types, _type, (t) => t.$2, (t) => t.$1,
                              (v) => _type = v),
                          ),
                          _FilterSection(
                            title: '按来源筛选',
                            expanded: open == 3,
                            active: _source != null,
                            onToggle: () => setSheetState(
                                () => open = open == 3 ? -1 : 3),
                            child: chips(
                              sources, _source, (s) => s.$2, (s) => s.$1,
                              (v) => _source = v),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }
}

/// 可折叠筛选分区：标题 + 展开/收起箭头；active 时标题前显示绿色圆点标记
class _FilterSection extends StatelessWidget {
  final String title;
  final bool expanded;
  final bool active;
  final VoidCallback onToggle;
  final Widget child;
  const _FilterSection({
    required this.title,
    required this.expanded,
    required this.active,
    required this.onToggle,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        InkWell(
          onTap: onToggle,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Row(
              children: [
                if (active) ...[
                  Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(
                        color: Colors.green, shape: BoxShape.circle),
                  ),
                  const SizedBox(width: 8),
                ],
                Expanded(
                  child: Text(title,
                      style: const TextStyle(
                          fontSize: 14, fontWeight: FontWeight.w600)),
                ),
                Icon(
                  expanded ? Icons.expand_less : Icons.expand_more,
                  color: Colors.grey,
                ),
              ],
            ),
          ),
        ),
        if (expanded)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
            child: child,
          ),
        const Divider(height: 1),
      ],
    );
  }
}

class _CompatBadge extends StatelessWidget {
  final String level;
  final bool hasCompat;
  const _CompatBadge({required this.level, required this.hasCompat});

  @override
  Widget build(BuildContext context) {
    final (color, text) = !hasCompat
        ? (Colors.grey, '通用')
        : switch (level) {
            'RED' => (Colors.red, '忌口'),
            'YELLOW' => (Colors.orange, '慎选'),
            _ => (Colors.green, '推荐'),
          };
    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(color: color.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(8)),
      alignment: Alignment.center,
      child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.bold, fontSize: 13)),
    );
  }
}
