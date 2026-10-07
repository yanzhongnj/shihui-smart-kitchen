class Video {
  final int id;
  final int userId;
  final String title;
  final String filename;
  final int? fileSize;
  final double? duration;
  final String status;
  final String? reviewStatus;
  final String? reviewComment;
  final String? visibility;
  final int uploadProgress;
  final String? createdAt;

  Video({
    required this.id,
    required this.userId,
    required this.title,
    required this.filename,
    this.fileSize,
    this.duration,
    this.status = 'pending',
    this.reviewStatus,
    this.reviewComment,
    this.visibility,
    this.uploadProgress = 0,
    this.createdAt,
  });

  factory Video.fromJson(Map<String, dynamic> json) {
    return Video(
      id: json['id'],
      userId: json['userId'],
      title: json['title'],
      filename: json['filename'] ?? '',
      fileSize: json['fileSize'],
      duration: (json['duration'] as num?)?.toDouble(),
      status: json['status'] ?? 'pending',
      reviewStatus: json['reviewStatus'],
      reviewComment: json['reviewComment'],
      visibility: json['visibility'],
      uploadProgress: json['uploadProgress'] ?? 0,
      createdAt: json['createdAt'],
    );
  }

  /// 显示标题：去掉视频文件扩展名
  String get displayTitle {
    return title.replaceAll(
        RegExp(r'\.(mp4|webm|avi|mov|mkv|flv|wmv|m4v|ts)$',
            caseSensitive: false),
        '');
  }

  String get statusText {
    switch (status) {
      case 'pending': return '待处理';
      case 'processing': return '处理中';
      case 'completed': return '已完成';
      case 'failed': return '失败';
      default: return status;
    }
  }

  /// 审核状态文案：待审核 / 已通过 / 不合格
  String get reviewStatusText {
    switch (reviewStatus) {
      case 'pending': return '待审核';
      case 'approved': return '已通过';
      case 'rejected': return '不合格';
      default: return '';
    }
  }

  String get fileSizeText {
    if (fileSize == null) return '';
    return fileSize! > 1048576
        ? '${(fileSize! / 1048576).toStringAsFixed(1)}MB'
        : '${(fileSize! / 1024).toStringAsFixed(1)}KB';
  }
}
