/// 用户口味/忌口画像
class UserPreference {
  int? spiceLevel;          // 0=不吃辣 ~ 5=特辣
  String? tabooAllergens;   // 过敏原，逗号分隔
  String? tabooIngredients; // 忌口食材
  String? preferredTastes;  // 偏好口味
  String? preferredCuisines;// 偏好菜系
  int? maxCalories;
  String? notes;

  UserPreference({
    this.spiceLevel,
    this.tabooAllergens,
    this.tabooIngredients,
    this.preferredTastes,
    this.preferredCuisines,
    this.maxCalories,
    this.notes,
  });

  factory UserPreference.fromJson(Map<String, dynamic> json) {
    return UserPreference(
      spiceLevel: json['spiceLevel'] as int?,
      tabooAllergens: json['tabooAllergens'],
      tabooIngredients: json['tabooIngredients'],
      preferredTastes: json['preferredTastes'],
      preferredCuisines: json['preferredCuisines'],
      maxCalories: json['maxCalories'] as int?,
      notes: json['notes'],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'spiceLevel': spiceLevel ?? 2,
      'tabooAllergens': tabooAllergens ?? '',
      'tabooIngredients': tabooIngredients ?? '',
      'preferredTastes': preferredTastes ?? '',
      'preferredCuisines': preferredCuisines ?? '',
      'maxCalories': maxCalories,
      'notes': notes ?? '',
    };
  }

  String get spiceText {
    const labels = ['不吃辣', '微辣', '中辣', '较辣', '特辣', '极辣'];
    final s = (spiceLevel ?? 2).clamp(0, 5);
    return labels[s];
  }
}
