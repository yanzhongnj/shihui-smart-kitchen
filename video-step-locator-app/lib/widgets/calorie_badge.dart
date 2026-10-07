import 'package:flutter/material.dart';

/// 热量等级徽标（P0 收尾：菜谱卡片热量徽标）。
///
/// 阈值与后端 `CalorieLevelService` 默认一致：
///   - LOW   ≤ 300  低热量（绿）
///   - MEDIUM≤ 600  中热量（橙）
///   - HIGH  > 600  高热量（红）
/// 展示在菜谱列表卡片与详情页信息卡上。
class CalorieBadge extends StatelessWidget {
  final int? calories;
  final int lowThreshold;
  final int mediumThreshold;

  const CalorieBadge(this.calories,
      {super.key, this.lowThreshold = 300, this.mediumThreshold = 600});

  String get _level {
    final c = calories;
    if (c == null) return '未知';
    if (c <= lowThreshold) return '低热量';
    if (c <= mediumThreshold) return '中热量';
    return '高热量';
  }

  Color get _color {
    final c = calories;
    if (c == null) return Colors.grey;
    if (c <= lowThreshold) return Colors.green;
    if (c <= mediumThreshold) return Colors.orange;
    return Colors.red;
  }

  @override
  Widget build(BuildContext context) {
    final color = _color;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withValues(alpha: 0.6)),
      ),
      child: Text(_level,
          style: TextStyle(
              fontSize: 10, color: color, fontWeight: FontWeight.w600)),
    );
  }
}
