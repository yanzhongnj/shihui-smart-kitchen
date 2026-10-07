/// 寻味会话模型
class XunweiSession {
  final int id;
  final String title;
  final String lastPreview;
  final int messageCount;
  final DateTime? updatedAt;

  const XunweiSession({
    required this.id,
    required this.title,
    this.lastPreview = '',
    this.messageCount = 0,
    this.updatedAt,
  });

  factory XunweiSession.fromJson(Map<String, dynamic> j) => XunweiSession(
        id: (j['id'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '新对话',
        lastPreview: j['lastPreview'] as String? ?? '',
        messageCount: (j['messageCount'] as num?)?.toInt() ?? 0,
        updatedAt: j['updatedAt'] != null
            ? DateTime.tryParse(j['updatedAt'] as String)
            : null,
      );
}
