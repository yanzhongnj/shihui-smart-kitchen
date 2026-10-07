import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/recipe.dart';
import '../providers/auth_provider.dart';
import '../providers/recipe_provider.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import '../widgets/calorie_badge.dart';
import 'checkin_page.dart';
import 'work_publish_page.dart';
import 'login_page.dart';
import 'recipe_create_page.dart';

/// 菜谱详情（游客只读；收藏/分享/评价/口味匹配需登录）
class RecipeDetailPage extends StatefulWidget {
  final Recipe recipe;
  const RecipeDetailPage({super.key, required this.recipe});

  @override
  State<RecipeDetailPage> createState() => _RecipeDetailPageState();
}

class _RecipeDetailPageState extends State<RecipeDetailPage> {
  late final int _id = widget.recipe.id;
  Map<String, dynamic>? _reviews;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<RecipeProvider>().fetchDetail(_id);
    });
    _loadReviews();
  }

  Future<void> _loadReviews() async {
    try {
      final data = await ApiService().getRecipeReviews(_id);
      if (!mounted) return;
      setState(() => _reviews = data);
    } catch (_) {
      // 评价加载失败不阻塞页面
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<RecipeProvider>();
    final auth = context.watch<AuthProvider>();
    final recipe = provider.current ?? widget.recipe;

    return Scaffold(
      appBar: AppBar(
        title: Text(recipe.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          if (auth.isLoggedIn && _canManage(recipe, auth)) ...[
            IconButton(
              tooltip: '编辑菜谱',
              icon: const Icon(Icons.edit_outlined),
              onPressed: () => _edit(recipe),
            ),
            IconButton(
              tooltip: '删除菜谱',
              icon: const Icon(Icons.delete_outline),
              onPressed: () => _confirmDelete(recipe),
            ),
          ],
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _infoCard(recipe),
          if (recipe.compatibility != null || auth.isLoggedIn)
            _compatCard(recipe, auth.isLoggedIn, provider),
          const SizedBox(height: 8),
          _actionsRow(recipe, auth.isLoggedIn),
          const SizedBox(height: 16),
          _stepsSection(recipe),
          const SizedBox(height: 16),
          _industrialCard(recipe, auth.isLoggedIn),
          const SizedBox(height: 16),
          _ProvenanceCard(recipe: recipe),
          const SizedBox(height: 16),
          _DeviceParamsCard(recipe: recipe, loggedIn: auth.isLoggedIn),
          const SizedBox(height: 16),
          _reviewsSection(),
          const SizedBox(height: 16),
          _ingredientsSection('主料', recipe.mainIngredients),
          _ingredientsSection('辅料', recipe.auxiliaryIngredients),
          if (recipe.summary != null && recipe.summary!.isNotEmpty)
            _section('简介', recipe.summary!),
        ],
      ),
    );
  }

  Widget _infoCard(Recipe r) {
    final chips = <Widget>[
      if (r.cuisine != null && r.cuisine!.isNotEmpty) Chip(label: Text(r.cuisine!)),
      if (r.spiceLevel != null) Chip(label: Text('辣度 ${r.spiceLevel}')),
      if (r.estimatedTime != null) Chip(label: Text('${r.estimatedTime}分钟')),
      if (r.calories != null)
        Chip(
          avatar: const SizedBox.shrink(),
          label: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('${r.calories}千卡'),
              const SizedBox(width: 6),
              CalorieBadge(r.calories),
            ],
          ),
        ),
      if (r.tasteTags != null && r.tasteTags!.isNotEmpty)
        Chip(label: Text(r.tasteTags!)),
    ];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (r.allergens != null && r.allergens!.isNotEmpty)
              Text('⚠️ 含过敏原：${r.allergens}', style: const TextStyle(color: Colors.red)),
            const SizedBox(height: 8),
            Wrap(spacing: 8, children: chips),
          ],
        ),
      ),
    );
  }

  Widget _compatCard(Recipe r, bool loggedIn, RecipeProvider provider) {
    final level = r.compatLevel;
    final (color, label) = switch (level) {
      'RED' => (Colors.red, '与您的口味/忌口冲突，请谨慎选择'),
      'YELLOW' => (Colors.orange, '与您的偏好不完全匹配'),
      _ => (Colors.green, '与您的偏好匹配，推荐'),
    };
    return Card(
      color: color.withValues(alpha: 0.08),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.tune, color: color),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    loggedIn ? '口味匹配：$label' : '登录后可查看口味匹配',
                    style: TextStyle(color: color, fontWeight: FontWeight.w600),
                  ),
                ),
              ],
            ),
            if (r.compatReasons.isNotEmpty) ...[
              const SizedBox(height: 8),
              ...r.compatReasons.map((e) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text('• $e', style: const TextStyle(fontSize: 13)),
                  )),
            ],
            if (loggedIn && r.compatibility == null) ...[
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: () => provider.fetchCompatibility(),
                  child: const Text('计算口味匹配'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _actionsRow(Recipe r, bool loggedIn) {
    if (!loggedIn) {
      return FilledButton.icon(
        icon: const Icon(Icons.login, size: 18),
        label: const Text('登录使用更多功能'),
        onPressed: () {
          Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const LoginPage()),
          );
        },
      );
    }
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        _actionBtn(Icons.share, '分享', () => _share(r)),
        _actionBtn(Icons.star_outline, '收藏', () => _favorite(r)),
        _actionBtn(Icons.rate_review_outlined, '评价', () => _review(r)),
        _actionBtn(Icons.local_fire_department, '打卡', () => _checkin(r)),
        _actionBtn(Icons.camera_alt_outlined, '发作品', () => _publishWork(r)),
      ],
    );
  }

  Widget _actionBtn(IconData icon, String label, VoidCallback onTap) {
    return SizedBox(
      width: 96,
      height: 40,
      child: FilledButton.icon(
        icon: Icon(icon, size: 18),
        label: Text(label, style: const TextStyle(fontSize: 13)),
        onPressed: onTap,
      ),
    );
  }

  /// 是否有管理权限（本人创建或管理员）
  bool _canManage(Recipe r, AuthProvider auth) {
    final u = auth.user;
    if (u == null) return false;
    if (u.isAdmin) return true;
    return r.userId != null && r.userId == u.id;
  }

  /// 编辑菜谱（创建者/管理员）；保存后刷新详情
  Future<void> _edit(Recipe r) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => RecipeCreatePage(recipe: r)),
    );
    if (saved == true && mounted) {
      context.read<RecipeProvider>().fetchDetail(r.id);
    }
  }

  Future<void> _confirmDelete(Recipe r) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除菜谱'),
        content: Text('确定删除「${r.title}」吗？删除后不可恢复。'),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await context.read<RecipeProvider>().deleteRecipe(r.id);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('菜谱已删除')));
      Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(friendlyError(e, fallback: '删除失败，请稍后重试'))));
    }
  }

  /// 操作步骤（recipes.steps_json → detail.steps）
  Widget _stepsSection(Recipe r) {
    final steps = r.steps;
    if (steps == null || steps.isEmpty) {
      return _section('操作步骤', '（暂无步骤）');
    }
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('操作步骤',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 8),
            ...steps.asMap().entries
                .map((e) => _stepTile(e.key, e.value as Map<String, dynamic>)),
          ],
        ),
      ),
    );
  }

  Widget _stepTile(int index, Map<String, dynamic> s) {
    final summary = s['summary']?.toString() ?? '';
    final detail = s['detail']?.toString() ?? '';
    final timer = s['timerMin'];
    final temp = s['temperature'];
    final mode = s['mode']?.toString();
    final badges = <Widget>[
      if (timer != null && '$timer'.isNotEmpty && timer.toString() != 'null')
        _badge('⏱ $timer 分钟'),
      if (temp != null && temp.toString() != 'null') _badge('🌡 $temp ℃'),
      if (mode != null && mode.trim().isNotEmpty) _badge('⚙ $mode'),
    ];
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CircleAvatar(
            radius: 13,
            backgroundColor: Colors.deepOrange.shade50,
            child: Text('${index + 1}',
                style: const TextStyle(fontSize: 13, color: Colors.deepOrange)),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (summary.isNotEmpty)
                  Text(summary,
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 15)),
                if (badges.isNotEmpty) ...[const SizedBox(height: 4), Wrap(spacing: 6, children: badges)],
                if (detail.isNotEmpty) ...[const SizedBox(height: 4), Text(detail, style: const TextStyle(height: 1.5, fontSize: 13))],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _badge(String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
      decoration: BoxDecoration(
        color: Colors.orange.shade50,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.orange.shade200),
      ),
      child: Text(text,
          style: const TextStyle(fontSize: 11, color: Colors.deepOrange)),
    );
  }

  Future<void> _share(Recipe r) async {
    try {
      final data = await ApiService().createRecipeShareLink(r.id);
      final code = data['code'];
      if (!mounted) return;
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('分享链接已生成'),
          content: Text('分享码：$code\n有效期：${data['expiresAt'] ?? '30天'}'),
          actions: [
            TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('好的')),
          ],
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('分享失败: $e')));
    }
  }

  Future<void> _favorite(Recipe r) async {
    try {
      await ApiService().addRecipeFavorite(r.id);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已收藏')));
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('收藏失败: $e')));
    }
  }

  /// 打卡：进入打卡页并预填菜名（关联本菜谱）
  void _checkin(Recipe r) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => CheckinPage(videoId: r.videoId, recipeTitle: r.title),
    ));
  }

  /// 发作品：进入发布页并预填菜名（关联本菜谱）
  void _publishWork(Recipe r) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) =>
          WorkPublishPage(videoId: r.videoId, recipeTitle: r.title),
    ));
  }

  /// 评价：星级评分 + 评语 + 口味标签 → 提交后端（好评/差评自动进入寻味偏好记忆）
  Future<void> _review(Recipe r) async {
    var rating = 5;
    final commentCtrl = TextEditingController();
    String? tasteLabel;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('评价这道菜'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(5, (i) {
                  final idx = i + 1;
                  return IconButton(
                    icon: Icon(
                      idx <= rating ? Icons.star : Icons.star_border,
                      color: Colors.amber,
                    ),
                    onPressed: () => setDialogState(() => rating = idx),
                  );
                }),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: commentCtrl,
                maxLines: 2,
                decoration: const InputDecoration(
                  hintText: '写下评价（可选）',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: ['好吃', '太辣', '清淡', '下饭', '适合新手', '一般']
                    .map((t) => ChoiceChip(
                          label: Text(t),
                          selected: tasteLabel == t,
                          onSelected: (sel) =>
                              setDialogState(() => tasteLabel = sel ? t : null),
                        ))
                    .toList(),
              ),
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(ctx).pop(false),
                child: const Text('取消')),
            FilledButton(
                onPressed: () => Navigator.of(ctx).pop(true),
                child: const Text('提交')),
          ],
        ),
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await ApiService()
          .addRecipeReview(r.id, rating, commentCtrl.text.trim(), tasteLabel: tasteLabel);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('评价成功，已用于优化你的偏好推荐')));
      await _loadReviews();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('评价失败：${friendlyError(e, fallback: '请稍后重试')}')));
    }
  }

  Widget _reviewsSection() {
    final rv = _reviews;
    if (rv == null) return const SizedBox.shrink();
    final list = (rv['reviews'] as List? ?? []);
    final avg = (rv['avgRating'] ?? 0);
    final count = (rv['count'] ?? 0);
    if (list.isEmpty) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('用户评价',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              SizedBox(height: 8),
              Text('暂无评价，来做第一个评价吧', style: TextStyle(color: Colors.grey)),
            ],
          ),
        ),
      );
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('用户评价（$count）',
                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 8),
            Text('平均评分：${avg.toStringAsFixed(1)} / 5',
                style: const TextStyle(fontSize: 14)),
            const SizedBox(height: 8),
            ...list.take(5).map((j) {
              final m = j as Map<String, dynamic>;
              return Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(m['username']?.toString() ?? '用户',
                              style: const TextStyle(
                                  fontWeight: FontWeight.w600, fontSize: 13)),
                        ),
                        Text('${m['rating'] ?? ''} 分',
                            style: const TextStyle(
                                fontSize: 12, color: Colors.amber)),
                      ],
                    ),
                    if ((m['tasteLabel'] as String?)?.isNotEmpty ?? false)
                      Text('口味：${m['tasteLabel']}',
                          style: const TextStyle(
                              fontSize: 12, color: Colors.orange)),
                    if ((m['comment'] as String?)?.isNotEmpty ?? false)
                      Text('${m['comment']}',
                          style: const TextStyle(fontSize: 13, height: 1.4)),
                  ],
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  Widget _ingredientsSection(String title, String? value) {
    if (value == null || value.isEmpty) return const SizedBox.shrink();
    return _section(title, value);
  }

  Widget _section(String title, String body) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 8),
            Text(body, style: const TextStyle(height: 1.6)),
          ],
        ),
      ),
    );
  }

  /// 工业级卡片：类型/评分 + 分量换算 + 营养 + 精确化增强（P1 最小价值闭环）
  Widget _industrialCard(Recipe r, bool loggedIn) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.rule, size: 20),
                const SizedBox(width: 8),
                const Text('精确化 · 分量换算',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: r.isIndustrial ? Colors.green.shade50 : Colors.grey.shade100,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    r.isIndustrial ? '工业级精确' : '家常型',
                    style: TextStyle(
                        fontSize: 12,
                        color: r.isIndustrial
                            ? Colors.green.shade700
                            : Colors.grey.shade700),
                  ),
                ),
              ],
            ),
            if (r.precisionScore != null) ...[
              const SizedBox(height: 6),
              Text('精确化评分 ${(r.precisionScore! * 100).round()}/100',
                  style: const TextStyle(fontSize: 13, color: Colors.blueGrey)),
            ],
            const SizedBox(height: 2),
            const Text(
              '按人数：按所需份量换算全部食材用量；按模具：按模具面积等比换算（圆模/方模/长方）',
              style: TextStyle(fontSize: 11, color: Colors.grey),
            ),
            const SizedBox(height: 12),
            _ScaleSection(recipe: r, loggedIn: loggedIn),
          ],
        ),
      ),
    );
  }
}

/// 分量换算 + 营养 + 精确化增强（自管理份数状态）
class _ScaleSection extends StatefulWidget {
  final Recipe recipe;
  final bool loggedIn;
  const _ScaleSection({required this.recipe, required this.loggedIn});

  @override
  State<_ScaleSection> createState() => _ScaleSectionState();
}

class _ScaleSectionState extends State<_ScaleSection> {
  late int _servings;
  Map<String, dynamic>? _scale;
  bool _busy = false;
  String? _err;
  bool _moldMode = false;
  String _shape = 'round';
  final _size1Ctrl = TextEditingController(text: '18');
  final _size2Ctrl = TextEditingController(text: '18');

  @override
  void initState() {
    super.initState();
    _servings = widget.recipe.baseServings ?? widget.recipe.servings ?? 2;
    if (_servings <= 0) _servings = 2;
    _loadScale();
  }

  @override
  void dispose() {
    _size1Ctrl.dispose();
    _size2Ctrl.dispose();
    super.dispose();
  }

  Future<void> _loadScale() async {
    setState(() {
      _busy = true;
      _err = null;
    });
    try {
      final Map<String, dynamic> data;
      if (_moldMode) {
        final s1 = double.tryParse(_size1Ctrl.text.trim());
        if (s1 == null || s1 <= 0) {
          setState(() {
            _busy = false;
            _err = '请输入有效的模具尺寸';
          });
          return;
        }
        double? s2;
        if (_shape == 'rect') {
          s2 = double.tryParse(_size2Ctrl.text.trim());
          if (s2 == null || s2 <= 0) {
            setState(() {
              _busy = false;
              _err = '请输入有效的模具长宽';
            });
            return;
          }
        }
        data = await ApiService().getRecipeScale(widget.recipe.id, _servings,
            mode: 'mold', shape: _shape, size1: s1, size2: s2);
      } else {
        data = await ApiService().getRecipeScale(widget.recipe.id, _servings);
      }
      if (!mounted) return;
      setState(() {
        _scale = data;
        _busy = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _err = friendlyError(e, fallback: '换算失败，请稍后重试');
        _busy = false;
      });
    }
  }

  Future<void> _upgrade() async {
    setState(() => _busy = true);
    try {
      final data = await ApiService().upgradeRecipe(widget.recipe.id);
      if (!mounted) return;
      final score = data['precisionScore'];
      final type = data['recipeType'] == 'INDUSTRIAL' ? '工业型' : '家常型';
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('精确化完成：评分 $score，类型 $type')));
      setState(() => _busy = false);
      context.read<RecipeProvider>().fetchDetail(widget.recipe.id);
      _loadScale();
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(friendlyError(e, fallback: '精确化增强失败，请稍后重试'))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final ingredients = (_scale?['ingredients'] as List<dynamic>?) ?? const [];
    final nutrition = _scale?['nutrition'] as Map<String, dynamic>?;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            ChoiceChip(
              label: const Text('按人数'),
              selected: !_moldMode,
              visualDensity: VisualDensity.compact,
              onSelected: (_) {
                setState(() => _moldMode = false);
                _loadScale();
              },
            ),
            ChoiceChip(
              label: const Text('按模具'),
              selected: _moldMode,
              visualDensity: VisualDensity.compact,
              onSelected: (_) {
                setState(() => _moldMode = true);
                _loadScale();
              },
            ),
            if (widget.loggedIn)
              OutlinedButton.icon(
                onPressed: _busy ? null : _upgrade,
                icon: const Icon(Icons.auto_fix_high, size: 16),
                label: const Text('精确化增强'),
              ),
          ],
        ),
        if (_moldMode) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              DropdownButton<String>(
                value: _shape,
                items: const [
                  DropdownMenuItem(value: 'round', child: Text('圆模')),
                  DropdownMenuItem(value: 'square', child: Text('方模')),
                  DropdownMenuItem(value: 'rect', child: Text('长方')),
                ],
                onChanged: (v) {
                  if (v == null) return;
                  setState(() => _shape = v);
                },
              ),
              SizedBox(
                width: 110,
                child: TextField(
                  controller: _size1Ctrl,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(
                    labelText: _shape == 'round'
                        ? '直径(cm)'
                        : _shape == 'square'
                            ? '边长(cm)'
                            : '长(cm)',
                    isDense: true,
                  ),
                ),
              ),
              if (_shape == 'rect')
                SizedBox(
                  width: 90,
                  child: TextField(
                    controller: _size2Ctrl,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: '宽(cm)', isDense: true),
                  ),
                ),
              FilledButton.tonal(
                onPressed: _busy ? null : _loadScale,
                child: const Text('换算'),
              ),
            ],
          ),
        ] else ...[
          const SizedBox(height: 4),
          Row(
            children: [
              const Text('份量'),
              IconButton(
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.remove_circle_outline),
                onPressed: _servings > 1
                    ? () {
                        setState(() => _servings--);
                        _loadScale();
                      }
                    : null,
              ),
              Text('$_servings 人份'),
              IconButton(
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.add_circle_outline),
                onPressed: () {
                  setState(() => _servings++);
                  _loadScale();
                },
              ),
            ],
          ),
        ],
        if (_busy) const LinearProgressIndicator(),
        if (_err != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(_err!, style: const TextStyle(color: Colors.red, fontSize: 13)),
          ),
        if (ingredients.isNotEmpty) ...[
          const SizedBox(height: 8),
          ...ingredients.map((e) => _ingredientRow(e as Map<String, dynamic>)),
        ],
        if (nutrition != null) ...[
          const SizedBox(height: 12),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _nutriItem('热量', '${nutrition['calories'] ?? 0} 千卡'),
              _nutriItem('蛋白', '${nutrition['protein'] ?? 0} g'),
              _nutriItem('脂肪', '${nutrition['fat'] ?? 0} g'),
              _nutriItem('碳水', '${nutrition['carbs'] ?? 0} g'),
            ],
          ),
          if (nutrition['source'] != null)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                '营养${nutrition['source']}${nutrition['estimated'] == true ? '（部分食材未计入）' : ''}（基于精确用量）',
                style: const TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ),
        ],
      ],
    );
  }

  Widget _ingredientRow(Map<String, dynamic> m) {
    final amount = m['scaledAmount'] ?? m['amount'];
    final unit = m['unit'] ?? '';
    final name = m['name'] ?? '';
    final isMain = m['isMain'] == 1;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Text(isMain ? '● ' : '· ',
              style: TextStyle(
                  color: isMain ? Colors.orange : Colors.grey, fontSize: 12)),
          Expanded(child: Text(name)),
          Text(amount != null ? '$amount$unit' : '适量',
              style: const TextStyle(fontWeight: FontWeight.w500)),
        ],
      ),
    );
  }

  Widget _nutriItem(String label, String value) {
    return Column(
      children: [
        Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        Text(label, style: const TextStyle(fontSize: 12)),
      ],
    );
  }
}

/// 溯源卡片：来源 URL / 提取版本 / 时间 / 历史流水（P3）
class _ProvenanceCard extends StatefulWidget {
  final Recipe recipe;
  const _ProvenanceCard({required this.recipe});

  @override
  State<_ProvenanceCard> createState() => _ProvenanceCardState();
}

class _ProvenanceCardState extends State<_ProvenanceCard> {
  Map<String, dynamic>? _data;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final d = await ApiService().getRecipeProvenance(widget.recipe.id);
      if (mounted) setState(() => _data = d);
    } catch (_) {
      // 溯源加载失败不阻塞页面
    }
  }

  @override
  Widget build(BuildContext context) {
    final d = _data;
    if (d == null) return const SizedBox.shrink();
    final source = (d['source'] as String?) ?? widget.recipe.source ?? '';
    final sourceUrl = d['sourceUrl'] as String?;
    final version = d['extractorVersion'] as String?;
    final extractedAt = d['extractedAt'] as String?;
    final rawDigest = d['rawDigest'] as String?;
    final history = (d['history'] as List?) ?? const [];
    final historyCount = history.length;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.history, size: 20),
                SizedBox(width: 8),
                Text('溯源', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              ],
            ),
            const SizedBox(height: 8),
            _row('来源', source),
            if (sourceUrl != null && sourceUrl.isNotEmpty)
              _row('来源链接', sourceUrl),
            if (version != null) _row('提取版本', version),
            if (extractedAt != null) _row('提取时间', extractedAt),
            if (rawDigest != null && rawDigest.isNotEmpty) _row('内容摘要', rawDigest),
            if (historyCount > 1) ...[
              const SizedBox(height: 6),
              Text('历史流水 $historyCount 条',
                  style: const TextStyle(fontSize: 12, color: Colors.grey)),
              ...history.take(3).map((h) {
                final m = h as Map<String, dynamic>;
                return Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    '· ${m['source'] ?? ''} ${m['extractorVersion'] ?? ''} @ ${m['extractedAt'] ?? ''}',
                    style: const TextStyle(fontSize: 11, color: Colors.grey),
                  ),
                );
              }),
            ],
          ],
        ),
      ),
    );
  }

  Widget _row(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 64,
            child: Text(label, style: const TextStyle(color: Colors.grey, fontSize: 13)),
          ),
          Expanded(child: Text(value, style: const TextStyle(fontSize: 13))),
        ],
      ),
    );
  }
}

/// 厨电参数卡片（P4）：流水线 device_params → 一键下发（需登录）
class _DeviceParamsCard extends StatefulWidget {
  final Recipe recipe;
  final bool loggedIn;
  const _DeviceParamsCard({required this.recipe, required this.loggedIn});

  @override
  State<_DeviceParamsCard> createState() => _DeviceParamsCardState();
}

class _DeviceParamsCardState extends State<_DeviceParamsCard> {
  int? _sendingId;

  Future<void> _send(int paramId) async {
    setState(() => _sendingId = paramId);
    try {
      final r = await ApiService().sendRecipeDeviceParam(widget.recipe.id, paramId);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('已下发：${r['command'] ?? ''}（${r['message'] ?? '成功'}）')));
      setState(() => _sendingId = null);
    } catch (e) {
      if (!mounted) return;
      setState(() => _sendingId = null);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(friendlyError(e, fallback: '下发失败，请稍后重试'))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final list = widget.recipe.deviceParams;
    if (list == null || list.isEmpty) return const SizedBox.shrink();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.kitchen_outlined, size: 20),
                SizedBox(width: 8),
                Text('厨电参数', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              ],
            ),
            const SizedBox(height: 8),
            ...list.map((e) {
              final m = e as Map<String, dynamic>;
              final paramId = m['id'] as int?;
              final temp = m['temperature'];
              final dur = m['durationMin'];
              final note = m['note'];
              final parts = <String>[
                if (temp != null) '$temp℃',
                if (dur != null) '$dur 分钟',
              ];
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        '· ${_deviceName('${m['deviceType'] ?? ''}')} · ${m['mode'] ?? ''}${parts.isNotEmpty ? ' · ${parts.join(' · ')}' : ''}${note != null && note.toString().isNotEmpty ? '（$note）' : ''}',
                        style: const TextStyle(fontSize: 13),
                      ),
                    ),
                    if (widget.loggedIn && paramId != null)
                      TextButton(
                        onPressed: _sendingId == paramId ? null : () => _send(paramId),
                        child: Text(_sendingId == paramId ? '下发中...' : '下发'),
                      ),
                  ],
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  String _deviceName(String t) {
    switch (t) {
      case 'oven':
        return '烤箱';
      case 'steamer':
        return '蒸箱';
      case 'cooktop':
        return '灶具';
      case 'airfryer':
        return '空气炸锅';
      default:
        return t;
    }
  }
}
