class VideoStep {
  final int id;
  final int stepIndex;
  final String summary;
  final double startTime;
  final double endTime;
  final String? originalText;
  final String confidence;
  final bool isLooping;

  VideoStep({
    required this.id,
    required this.stepIndex,
    required this.summary,
    required this.startTime,
    required this.endTime,
    this.originalText,
    this.confidence = 'low',
    this.isLooping = false,
  });

  factory VideoStep.fromJson(Map<String, dynamic> json) {
    return VideoStep(
      id: json['id'],
      stepIndex: json['stepIndex'],
      summary: json['summary'] ?? '',
      startTime: (json['startTime'] as num).toDouble(),
      endTime: (json['endTime'] as num).toDouble(),
      originalText: json['originalText'],
      confidence: json['confidence'] ?? 'low',
      isLooping: json['looping'] ?? false,
    );
  }

  String get timeText {
    final start = '${startTime.toInt()}:${(startTime * 60 % 60).toInt().toString().padLeft(2, '0')}';
    final end = '${endTime.toInt()}:${(endTime * 60 % 60).toInt().toString().padLeft(2, '0')}';
    return '$start - $end';
  }
}
