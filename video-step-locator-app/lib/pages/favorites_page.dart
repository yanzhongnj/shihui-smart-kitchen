import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/recipe.dart';
import '../providers/auth_provider.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';
import 'recipe_detail_page.dart';
import 'video_player_page.dart';

/// 我的收藏（需登录）
class FavoritesPage extends StatefulWidget {
  const FavoritesPage({super.key});

  @override
  State<FavoritesPage> createState() => _FavoritesPageState();
}

class _FavoritesPageState extends State<FavoritesPage> {
  final ApiService _api = ApiService();
  List<Map<String, dynamic>> _items = [];
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
      final data = await _api.getFavorites();
      if (!mounted) return;
      setState(() {
        _items = data
            .map((j) => Map<String, dynamic>.from(j as Map))
            .toList();
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载失败，请稍后重试');
        _loading = false;
      });
    }
  }

  Future<void> _unfavorite(int videoId) async {
    await _api.removeFavorite(videoId);
    _load();
  }

  /// 取消菜谱收藏（无视频菜谱走 recipe_id）
  Future<void> _unfavoriteRecipe(int recipeId) async {
    await _api.removeRecipeFavorite(recipeId);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthProvider>();
    if (!auth.isLoggedIn) {
      return Scaffold(
        appBar: AppBar(title: const Text('我的收藏')),
        body: const Center(child: Text('登录后查看收藏', style: TextStyle(color: Colors.grey))),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('我的收藏')),
      body: RefreshIndicator(
        onRefresh: _load,
        child: _buildBody(),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return ListView(children: [
        const SizedBox(height: 100),
        Center(child: Text('加载失败：$_error', style: const TextStyle(color: Colors.red))),
      ]);
    }
    if (_items.isEmpty) {
      return ListView(children: const [
        SizedBox(height: 120),
        Center(child: Text('暂无收藏', style: TextStyle(color: Colors.grey))),
      ]);
    }
    return ListView.builder(
      physics: const AlwaysScrollableScrollPhysics(),
      itemCount: _items.length,
      itemBuilder: (context, i) {
        final v = _items[i];
        final isRecipe = v['type'] == 'recipe' || v['recipeId'] != null;
        if (isRecipe) {
          final recipeId = (v['recipeId'] as num).toInt();
          final title = (v['title'] ?? '(未命名菜谱)') as String;
          final status = (v['status'] ?? '菜谱') as String;
          final videoId =
              v['videoId'] == null ? null : (v['videoId'] as num).toInt();
          return Card(
            margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
            child: ListTile(
              leading: const Icon(Icons.menu_book, size: 32, color: Colors.deepOrange),
              title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(status),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    icon: const Icon(Icons.chevron_right),
                    tooltip: '查看菜谱',
                    onPressed: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => RecipeDetailPage(
                            recipe: Recipe(
                                id: recipeId,
                                videoId: videoId,
                                title: title),
                          ),
                        ),
                      );
                    },
                  ),
                  IconButton(
                    icon: const Icon(Icons.delete_outline),
                    tooltip: '取消收藏',
                    onPressed: () => _unfavoriteRecipe(recipeId),
                  ),
                ],
              ),
            ),
          );
        }
        final rawVideoId = v['videoId'] ?? v['id'];
        final videoId = rawVideoId is num ? rawVideoId.toInt() : null;
        final title = (v['title'] ?? '(未命名)') as String;
        final status = (v['status'] ?? '') as String;
        return Card(
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
          child: ListTile(
            leading: const Icon(Icons.star, size: 32, color: Colors.amber),
            title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(status),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (videoId != null)
                  IconButton(
                    icon: const Icon(Icons.play_circle_outline),
                    tooltip: '播放',
                    onPressed: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) =>
                              VideoPlayerPage(videoId: videoId, initialTitle: title),
                        ),
                      );
                    },
                  ),
                IconButton(
                  icon: const Icon(Icons.delete_outline),
                  tooltip: '取消收藏',
                  onPressed: videoId == null ? null : () => _unfavorite(videoId),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
