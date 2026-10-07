import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/recipe.dart';
import '../providers/auth_provider.dart';
import '../providers/recipe_provider.dart';
import '../utils/error_message.dart';
import 'recipe_create_page.dart';
import 'recipe_detail_page.dart';

/// 我的菜谱管理：列出本人创建的菜谱，支持查看/删除
class MyRecipesPage extends StatefulWidget {
  const MyRecipesPage({super.key});

  @override
  State<MyRecipesPage> createState() => _MyRecipesPageState();
}

class _MyRecipesPageState extends State<MyRecipesPage> {
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
    final user = context.watch<AuthProvider>().user;
    final mine = user == null
        ? <Recipe>[]
        : provider.recipes
            .where((r) => r.userId != null && r.userId == user.id)
            .toList();

    return Scaffold(
      appBar: AppBar(title: const Text('我的菜谱')),
      body: provider.isLoading && provider.recipes.isEmpty
          ? const Center(child: CircularProgressIndicator())
          : mine.isEmpty
              ? const Center(
                  child: Text('暂无自己录入的菜谱\n去「品味 → 录入菜谱」创建吧',
                      textAlign: TextAlign.center),
                )
              : RefreshIndicator(
                  onRefresh: () => context.read<RecipeProvider>().fetchRecipes(),
                  child: ListView.separated(
                    padding: const EdgeInsets.all(12),
                    itemCount: mine.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, i) => _recipeTile(context, mine[i]),
                  ),
                ),
    );
  }

  Widget _recipeTile(BuildContext context, Recipe r) {
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: Colors.deepOrange.shade50,
          child: const Icon(Icons.restaurant, color: Colors.deepOrange),
        ),
        title: Text(r.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(
          '${_sourceLabel(r.source)} · ${r.isIndustrial ? '工业型' : '家常型'}'
          '${r.precisionScore != null ? ' · 评分 ${(r.precisionScore! * 100).round()}/100' : ''}',
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.edit_outlined),
              tooltip: '编辑',
              onPressed: () => _openEdit(context, r),
            ),
            IconButton(
              icon: const Icon(Icons.delete_outline, color: Colors.redAccent),
              tooltip: '删除',
              onPressed: () => _confirmDelete(context, r),
            ),
          ],
        ),
        onTap: () {
          Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => RecipeDetailPage(recipe: r)),
          );
        },
      ),
    );
  }

  String _sourceLabel(String? s) {
    switch (s) {
      case 'ARTICLE_LINK':
        return '图文录入';
      case 'USER':
        return '手动录入';
      case 'AI_CREATE':
        return 'AI创作';
      case 'VIDEO_LINK':
        return '视频菜谱';
      default:
        return '菜谱';
    }
  }

  Future<void> _openEdit(BuildContext context, Recipe r) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => RecipeCreatePage(recipe: r)),
    );
    if (saved == true && context.mounted) {
      context.read<RecipeProvider>().fetchRecipes();
    }
  }

  Future<void> _confirmDelete(BuildContext context, Recipe r) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除菜谱'),
        content: Text('确定删除「${r.title}」吗？删除后不可恢复。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (ok != true || !context.mounted) return;
    try {
      await context.read<RecipeProvider>().deleteRecipe(r.id);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('菜谱已删除')));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(friendlyError(e, fallback: '删除失败，请稍后重试'))));
    }
  }
}
