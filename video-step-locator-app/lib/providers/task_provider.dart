import 'package:flutter/foundation.dart';
import '../services/api_service.dart';

class TaskProvider extends ChangeNotifier {
  final ApiService _api = ApiService();

  List<Map<String, dynamic>> _tasks = [];
  bool _loading = false;

  List<Map<String, dynamic>> get tasks => _tasks;
  bool get isLoading => _loading;

  Future<void> fetchTasks() async {
    _loading = true;
    notifyListeners();

    try {
      final data = await _api.getTasks();
      _tasks = data.cast<Map<String, dynamic>>();
    } catch (e) {
      debugPrint('Fetch tasks failed: $e');
    }

    _loading = false;
    notifyListeners();
  }
}
