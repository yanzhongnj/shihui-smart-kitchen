/// 菜谱（含口味/忌口兼容性 + 统一模型：来源/类型/评分/食材/营养）
class Recipe {
  final int id;
  // 来源为 ARTICLE_LINK / AI_CREATE 的菜谱没有关联视频，videoId 可为空
  final int? videoId;
  final String title;
  final String? summary;
  final String? mainIngredients;
  final String? auxiliaryIngredients;
  final String? tasteTags;
  final String? allergens;
  final int? spiceLevel;
  final String? cuisine;
  final int? estimatedTime;
  final int? calories;
  final String? videoTitle;
  final String? sourceType;
  final Map<String, dynamic>? compatibility;
  // 统一模型 v0.2
  final String? source;
  final String? sourceUrl;
  final String? recipeType;
  final double? precisionScore;
  final int? servings;
  final int? baseServings;
  final List<dynamic>? ingredients;
  final Map<String, dynamic>? nutrition;
  final List<dynamic>? deviceParams;
  // 操作步骤 [{index,summary,detail,timerMin,temperature,mode}]（recipes.steps_json）
  final List<dynamic>? steps;
  // 创建者 userId（我的菜谱管理用）
  final int? userId;

  Recipe({
    required this.id,
    required this.videoId,
    required this.title,
    this.summary,
    this.mainIngredients,
    this.auxiliaryIngredients,
    this.tasteTags,
    this.allergens,
    this.spiceLevel,
    this.cuisine,
    this.estimatedTime,
    this.calories,
    this.videoTitle,
    this.sourceType,
    this.compatibility,
    this.source,
    this.sourceUrl,
    this.recipeType,
    this.precisionScore,
    this.servings,
    this.baseServings,
    this.ingredients,
    this.nutrition,
    this.deviceParams,
    this.steps,
    this.userId,
  });

  factory Recipe.fromJson(Map<String, dynamic> json) {
    return Recipe(
      id: json['id'],
      videoId: json['videoId'] as int?,
      title: json['title'] ?? '',
      summary: json['summary'],
      mainIngredients: json['mainIngredients'],
      auxiliaryIngredients: json['auxiliaryIngredients'],
      tasteTags: json['tasteTags'],
      allergens: json['allergens'],
      spiceLevel: json['spiceLevel'] as int?,
      cuisine: json['cuisine'],
      estimatedTime: json['estimatedTime'] as int?,
      calories: json['calories'] as int?,
      videoTitle: json['videoTitle'],
      sourceType: json['sourceType'],
      compatibility: json['compatibility'] as Map<String, dynamic>?,
      source: json['source'] as String?,
      sourceUrl: json['sourceUrl'] as String?,
      recipeType: json['recipeType'] as String?,
      precisionScore: (json['precisionScore'] as num?)?.toDouble(),
      servings: json['servings'] as int?,
      baseServings: json['baseServings'] as int?,
      ingredients: json['ingredients'] as List<dynamic>?,
      nutrition: json['nutrition'] as Map<String, dynamic>?,
      deviceParams: json['deviceParams'] as List<dynamic>?,
      steps: json['steps'] as List<dynamic>?,
      userId: json['userId'] as int?,
    );
  }

  /// 派生类型标签是否工业型
  bool get isIndustrial => recipeType == 'INDUSTRIAL';

  /// 是否有兼容性结果（登录并已计算）
  bool get hasCompatibility => compatibility != null;

  /// 兼容性等级：RED(过敏/忌口) / YELLOW(口味不符) / GREEN(推荐)
  String get compatLevel {
    final c = compatibility;
    if (c == null || c['level'] == null) return 'GREEN';
    return c['level'] as String;
  }

  List<String> get compatReasons {
    final c = compatibility;
    if (c == null || c['reasons'] == null) return const [];
    return (c['reasons'] as List).cast<String>();
  }

  /// 兼容性颜色
  ColorHint get colorHint {
    switch (compatLevel) {
      case 'RED': return ColorHint.red;
      case 'YELLOW': return ColorHint.yellow;
      default: return ColorHint.green;
    }
  }
}

enum ColorHint { red, yellow, green }
