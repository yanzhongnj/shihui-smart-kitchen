import 'dart:convert';

/// 寻味推荐视频（AI 推荐的做菜视频，含可下载 URL）
class XunweiVideo {
  final String title;
  final String url;
  final String uploader;
  final String thumbnail;
  final String reason;
  final double duration;
  final int viewCount;

  const XunweiVideo({
    required this.title,
    required this.url,
    this.uploader = '',
    this.thumbnail = '',
    this.reason = '',
    this.duration = 0,
    this.viewCount = 0,
  });

  factory XunweiVideo.fromJson(Map<String, dynamic> j) => XunweiVideo(
        title: j['title'] as String? ?? '',
        url: j['url'] as String? ?? '',
        uploader: j['uploader'] as String? ?? '',
        thumbnail: j['thumbnail'] as String? ?? '',
        reason: j['reason'] as String? ?? '',
        duration: (j['duration'] as num?)?.toDouble() ?? 0,
        viewCount: (j['viewCount'] as num?)?.toInt() ?? 0,
      );

  String get durationText {
    final s = duration.toInt();
    if (s <= 0) return '';
    final m = s ~/ 60;
    final sec = s % 60;
    return '$m分${sec.toString().padLeft(2, '0')}秒';
  }
}

/// 寻味消息（user / assistant）
class XunweiMessage {
  final int id;
  final String role;
  final String content;
  final List<XunweiVideo> videos;

  const XunweiMessage({
    required this.id,
    required this.role,
    required this.content,
    this.videos = const [],
  });

  bool get isUser => role == 'user';

  /// videoJson 可能是 JSON 字符串（历史消息）或列表（发送响应）
  static List<XunweiVideo> _parseVideos(dynamic raw) {
    if (raw == null) return const [];
    if (raw is List) {
      return raw
          .whereType<Map<String, dynamic>>()
          .map(XunweiVideo.fromJson)
          .toList();
    }
    if (raw is String && raw.trim().isNotEmpty) {
      try {
        final list = jsonDecode(raw);
        if (list is List) {
          return list
              .whereType<Map<String, dynamic>>()
              .map(XunweiVideo.fromJson)
              .toList();
        }
      } catch (_) {}
    }
    return const [];
  }

  factory XunweiMessage.fromJson(Map<String, dynamic> j) => XunweiMessage(
        id: (j['id'] as num?)?.toInt() ?? 0,
        role: j['role'] as String? ?? 'assistant',
        content: j['content'] as String? ?? '',
        videos: _parseVideos(j['videoJson']),
      );
}
