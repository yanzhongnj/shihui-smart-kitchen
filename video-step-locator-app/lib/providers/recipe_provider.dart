import 'package:flutter/foundation.dart';
import '../models/recipe.dart';
import '../services/api_service.dart';
import '../utils/error_message.dart';

class RecipeProvider extends ChangeNotifier {
  final ApiService _api = ApiService();

  List<Recipe> _recipes = [];
  Recipe? _current;
  bool _loading = false;
  String? _error;

  List<Recipe> get recipes => _recipes;
  Recipe? get current => _current;
  bool get isLoading => _loading;
  String? get error => _error;

  Future<void> fetchRecipes(
      {String? cuisine, String? taste, String? source, String? type}) async {
    _loading = true;
    _error = null;
    notifyListeners();
    try {
      final data = await _api.getRecipes(
          cuisine: cuisine, taste: taste, source: source, type: type);
      _recipes = data.map((j) => Recipe.fromJson(j)).toList();
    } catch (e) {
      _error = friendlyError(e, fallback: '加载菜谱失败，请稍后重试');
      debugPrint('fetch recipes failed: $e');
    }
    _loading = false;
    notifyListeners();
  }

  Future<void> fetchDetail(int id) async {
    _loading = true;
    _error = null;
    notifyListeners();
    try {
      final data = await _api.getRecipe(id);
      _current = Recipe.fromJson(data);
    } catch (e) {
      _error = friendlyError(e, fallback: '加载菜谱详情失败，请稍后重试');
      debugPrint('fetch recipe detail failed: $e');
    }
    _loading = false;
    notifyListeners();
  }

  /// 刷新当前菜谱的口味/忌口兼容性（需登录）
  Future<void> fetchCompatibility() async {
    final r = _current;
    if (r == null) return;
    try {
      final c = await _api.getRecipeCompatibility(r.id);
      final compat = c['compatibility'] as Map<String, dynamic>?;
      if (compat != null) {
        _current = Recipe(
          id: r.id,
          videoId: r.videoId,
          title: r.title,
          summary: r.summary,
          mainIngredients: r.mainIngredients,
          auxiliaryIngredients: r.auxiliaryIngredients,
          tasteTags: r.tasteTags,
          allergens: r.allergens,
          spiceLevel: r.spiceLevel,
          cuisine: r.cuisine,
          estimatedTime: r.estimatedTime,
          calories: r.calories,
          videoTitle: r.videoTitle,
          compatibility: compat,
          source: r.source,
          recipeType: r.recipeType,
          precisionScore: r.precisionScore,
          servings: r.servings,
          baseServings: r.baseServings,
          ingredients: r.ingredients,
          nutrition: r.nutrition,
          deviceParams: r.deviceParams,
          steps: r.steps,
          userId: r.userId,
        );
        notifyListeners();
      }
    } catch (e) {
      debugPrint('fetch compatibility failed: $e');
    }
  }

  /// 删除菜谱（创建者或管理员）；成功后从本地列表移除并刷新详情
  Future<void> deleteRecipe(int id) async {
    await _api.deleteRecipe(id);
    _recipes.removeWhere((e) => e.id == id);
    if (_current?.id == id) _current = null;
    notifyListeners();
  }

  /// 修改菜谱（创建者或管理员）；成功后刷新当前详情与列表条目
  Future<void> updateRecipe(int id, Map<String, dynamic> body) async {
    final data = await _api.updateRecipe(id, body);
    final updated = Recipe.fromJson(data);
    _current = updated;
    final idx = _recipes.indexWhere((e) => e.id == id);
    if (idx >= 0) _recipes[idx] = updated;
    notifyListeners();
  }
}
