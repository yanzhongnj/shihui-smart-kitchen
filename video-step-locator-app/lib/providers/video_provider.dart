import 'package:flutter/foundation.dart';
import '../models/video.dart';
import '../models/video_step.dart';
import '../services/api_service.dart';
import '../services/device_id.dart';

class VideoProvider extends ChangeNotifier {
  final ApiService _api = ApiService();

  List<Video> _videos = [];
  Video? _currentVideo;
  List<VideoStep> _steps = [];
  bool _loading = false;

  List<Video> get videos => _videos;
  Video? get currentVideo => _currentVideo;
  List<VideoStep> get steps => _steps;
  bool get isLoading => _loading;

  Future<void> fetchVideos({int page = 0, int size = 20}) async {
    _loading = true;
    notifyListeners();

    try {
      // 携带设备标识：游客可查看本设备下载的视频；登录用户同时看到自己的视频
      final deviceId = await DeviceId.get();
      final data =
          await _api.getVideos(page: page, size: size, deviceId: deviceId);
      _videos = data.map((j) => Video.fromJson(j)).toList();
    } catch (e) {
      debugPrint('Fetch videos failed: $e');
    }

    _loading = false;
    notifyListeners();
  }

  Future<void> fetchDetail(int id) async {
    _loading = true;
    notifyListeners();

    try {
      final data = await _api.getVideoDetail(id);
      _currentVideo = Video.fromJson(data);

      final stepsData = await _api.getSteps(id);
      _steps = stepsData.map((j) => VideoStep.fromJson(j)).toList();
    } catch (e) {
      debugPrint('Fetch detail failed: $e');
    }

    _loading = false;
    notifyListeners();
  }

  Future<bool> toggleLoop(int stepId) async {
    try {
      final data = await _api.toggleLoop(stepId);
      final looping = data['isLooping'] as bool;
      final idx = _steps.indexWhere((s) => s.id == stepId);
      if (idx >= 0) {
        _steps[idx] = VideoStep(
          id: _steps[idx].id,
          stepIndex: _steps[idx].stepIndex,
          summary: _steps[idx].summary,
          startTime: _steps[idx].startTime,
          endTime: _steps[idx].endTime,
          originalText: _steps[idx].originalText,
          confidence: _steps[idx].confidence,
          isLooping: looping,
        );
        notifyListeners();
      }
      return looping;
    } catch (e) {
      debugPrint('Toggle loop failed: $e');
      return false;
    }
  }

  Future<void> deleteVideo(int id) async {
    try {
      await _api.deleteVideo(id);
      _videos.removeWhere((v) => v.id == id);
      notifyListeners();
    } catch (e) {
      debugPrint('Delete video failed: $e');
    }
  }
}
