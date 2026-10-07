/// 下载任务（注册用户与游客通用）
class DownloadTask {
  final int id;
  final String title;
  final String status;
  final int progress;
  final int? fileSize;
  final String? formatRequested;
  final String? format; // 文件扩展名（如 mp4），用于本地保存
  final String? sourceUrl; // 来源链接
  final String? errorMessage;
  final int? videoId;
  final bool? duplicate;
  final String? createdAt;

  DownloadTask({
    required this.id,
    required this.title,
    required this.status,
    this.progress = 0,
    this.fileSize,
    this.formatRequested,
    this.format,
    this.sourceUrl,
    this.errorMessage,
    this.videoId,
    this.duplicate,
    this.createdAt,
  });

  factory DownloadTask.fromJson(Map<String, dynamic> json) {
    return DownloadTask(
      id: json['id'],
      title: json['title'] ?? '(未命名)',
      status: json['status'] ?? 'queued',
      progress: json['progress'] ?? 0,
      fileSize: json['fileSize'] as int?,
      formatRequested: json['formatRequested'],
      format: json['format'],
      sourceUrl: json['sourceUrl'],
      errorMessage: json['errorMessage'],
      videoId: json['videoId'] as int?,
      duplicate: json['duplicate'] == true,
      createdAt: json['createdAt'],
    );
  }

  bool get isActive => status == 'running' || status == 'queued';

  String get statusText {
    switch (status) {
      case 'queued': return '排队中';
      case 'running': return '下载中';
      case 'completed': return '已完成';
      case 'failed': return '失败';
      case 'cancelled': return '已取消';
      default: return status;
    }
  }

  String get fileSizeText {
    if (fileSize == null) return '-';
    return fileSize! > 1048576
        ? '${(fileSize! / 1048576).toStringAsFixed(1)}MB'
        : '${(fileSize! / 1024).toStringAsFixed(1)}KB';
  }

  String get formatText {
    switch (formatRequested) {
      case '1080p': return '1080P';
      case '720p': return '720P';
      case '480p': return '480P';
      default: return '最佳';
    }
  }
}
