import 'dart:math';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 游客设备标识：本地持久化，用于后端游客配额与下载任务归属
class DeviceId {
  static const FlutterSecureStorage _storage = FlutterSecureStorage();
  static const String _key = 'device_id';
  static String? _cached;

  static String _generate() {
    final r = Random.secure();
    final b = List<int>.generate(16, (_) => r.nextInt(256));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    String h(int i) => b[i].toRadixString(16).padLeft(2, '0');
    return '${h(0)}${h(1)}${h(2)}${h(3)}-${h(4)}${h(5)}-${h(6)}${h(7)}'
        '-${h(8)}${h(9)}-${h(10)}${h(11)}${h(12)}${h(13)}${h(14)}${h(15)}';
  }

  static Future<String> get() async {
    if (_cached != null) return _cached!;
    final v = await _storage.read(key: _key);
    if (v != null && v.isNotEmpty) {
      _cached = v;
      return v;
    }
    final id = _generate();
    await _storage.write(key: _key, value: id);
    _cached = id;
    return id;
  }
}
