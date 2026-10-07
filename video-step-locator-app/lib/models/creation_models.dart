/// 酿味（AI 菜品创作）数据模型（P0）
library;

/// 创作会话
class CreationSession {
  final int id;
  final String title;
  final String status;
  final String lastPreview;
  final int messageCount;
  final int? creationId;
  final DateTime? updatedAt;

  const CreationSession({
    required this.id,
    required this.title,
    this.status = 'CREATED',
    this.lastPreview = '',
    this.messageCount = 0,
    this.creationId,
    this.updatedAt,
  });

  factory CreationSession.fromJson(Map<String, dynamic> j) => CreationSession(
        id: (j['id'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '新创作',
        status: j['status'] as String? ?? 'CREATED',
        lastPreview: j['lastPreview'] as String? ?? '',
        messageCount: (j['messageCount'] as num?)?.toInt() ?? 0,
        creationId: (j['creationId'] as num?)?.toInt(),
        updatedAt: j['updatedAt'] != null
            ? DateTime.tryParse(j['updatedAt'] as String)
            : null,
      );
}

/// 会话消息
class CreationMessage {
  final int id;
  final String role; // user | assistant
  final String content;
  final String? intentJson;

  const CreationMessage({
    required this.id,
    required this.role,
    required this.content,
    this.intentJson,
  });

  factory CreationMessage.fromJson(Map<String, dynamic> j) =>
      CreationMessage(
        id: (j['id'] as num?)?.toInt() ?? 0,
        role: j['role'] as String? ?? 'assistant',
        content: j['content'] as String? ?? '',
        intentJson: j['intentJson'] as String?,
      );
}

/// 创作列表项
class CreationItem {
  final int id;
  final String title;
  final String summary;
  final List<String> tasteTags;
  final int? spiceLevel;
  final int? estimatedTime;
  final String difficulty;
  final int version;
  final String status;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  const CreationItem({
    required this.id,
    required this.title,
    this.summary = '',
    this.tasteTags = const [],
    this.spiceLevel,
    this.estimatedTime,
    this.difficulty = 'medium',
    this.version = 1,
    this.status = 'published',
    this.createdAt,
    this.updatedAt,
  });

  factory CreationItem.fromJson(Map<String, dynamic> j) => CreationItem(
        id: (j['id'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        tasteTags: (j['tasteTags'] as List? ?? [])
            .whereType<String>()
            .toList(),
        spiceLevel: (j['spiceLevel'] as num?)?.toInt(),
        estimatedTime: (j['estimatedTime'] as num?)?.toInt(),
        difficulty: j['difficulty'] as String? ?? 'medium',
        version: (j['version'] as num?)?.toInt() ?? 1,
        status: j['status'] as String? ?? 'published',
        createdAt: j['createdAt'] != null
            ? DateTime.tryParse(j['createdAt'] as String)
            : null,
        updatedAt: j['updatedAt'] != null
            ? DateTime.tryParse(j['updatedAt'] as String)
            : null,
      );
}

/// 创作详情（含步骤）
class CreationDetail {
  final int id;
  final String title;
  final String summary;
  final List<String> mainIngredients;
  final List<String> auxiliaryIngredients;
  final List<String> tasteTags;
  final List<String> allergens;
  final int? spiceLevel;
  final int? calories;
  final int? estimatedTime;
  final String difficulty;
  final List<dynamic> steps;
  final int version;
  final int iterCount;
  final String sourceType;
  final String disclaimer;
  final String status;
  /// P1 食物安全规则命中项（后端 detail.safetyJson）
  final List<Map<String, dynamic>> safetyItems;
  /// P0 酱汁配方（后端 detail.sauceJson，未确认时为 null）
  final Map<String, dynamic>? sauceJson;
  /// P1 AI 效果图（后端 detail.effectImage，effect/ 相对路径）
  final String? effectImage;
  /// P1 效果图风格（realistic|plated|illustration）
  final String? effectImageStyle;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  const CreationDetail({
    required this.id,
    required this.title,
    this.summary = '',
    this.mainIngredients = const [],
    this.auxiliaryIngredients = const [],
    this.tasteTags = const [],
    this.allergens = const [],
    this.spiceLevel,
    this.calories,
    this.estimatedTime,
    this.difficulty = 'medium',
    this.steps = const [],
    this.version = 1,
    this.iterCount = 0,
    this.sourceType = 'ai_creation',
    this.disclaimer = '',
    this.status = 'published',
    this.safetyItems = const [],
    this.sauceJson,
    this.effectImage,
    this.effectImageStyle,
    this.createdAt,
    this.updatedAt,
  });

  factory CreationDetail.fromJson(Map<String, dynamic> j) => CreationDetail(
        id: (j['id'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        mainIngredients: (j['mainIngredients'] as List? ?? [])
            .whereType<String>()
            .toList(),
        auxiliaryIngredients: (j['auxiliaryIngredients'] as List? ?? [])
            .whereType<String>()
            .toList(),
        tasteTags: (j['tasteTags'] as List? ?? []).whereType<String>().toList(),
        allergens:
            (j['allergens'] as List? ?? []).whereType<String>().toList(),
        spiceLevel: (j['spiceLevel'] as num?)?.toInt(),
        calories: (j['calories'] as num?)?.toInt(),
        estimatedTime: (j['estimatedTime'] as num?)?.toInt(),
        difficulty: j['difficulty'] as String? ?? 'medium',
        steps: j['steps'] as List? ?? [],
        version: (j['version'] as num?)?.toInt() ?? 1,
        iterCount: (j['iterCount'] as num?)?.toInt() ?? 0,
        sourceType: j['sourceType'] as String? ?? 'ai_creation',
        disclaimer: j['disclaimer'] as String? ?? '',
        status: j['status'] as String? ?? 'published',
        safetyItems: (j['safetyJson'] as List? ?? [])
            .whereType<Map>()
            .map((e) => Map<String, dynamic>.from(e))
            .toList(),
        sauceJson: j['sauceJson'] is Map
            ? Map<String, dynamic>.from(j['sauceJson'] as Map)
            : null,
        effectImage: j['effectImage'] as String?,
        effectImageStyle: j['effectImageStyle'] as String?,
        createdAt: j['createdAt'] != null
            ? DateTime.tryParse(j['createdAt'] as String)
            : null,
        updatedAt: j['updatedAt'] != null
            ? DateTime.tryParse(j['updatedAt'] as String)
            : null,
      );
}
