import 'dart:async';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import '../services/api_service.dart';
import '../services/local_video_store.dart';
import '../utils/error_message.dart';

/// 视频分片上传页：选文件 → 初始化 → 分块上传 → 合并入库
class VideoUploadPage extends StatefulWidget {
  final LocalVideo? initialVideo;
  const VideoUploadPage({super.key, this.initialVideo});

  @override
  State<VideoUploadPage> createState() => _VideoUploadPageState();
}

class _VideoUploadPageState extends State<VideoUploadPage> {
  final ApiService _api = ApiService();
  static const int _chunkSize = 5 * 1024 * 1024; // 5MB，与后端一致

  PlatformFile? _picked;
  bool _uploading = false;
  double _progress = 0;
  String? _message;

  @override
  void initState() {
    super.initState();
    // 从「下载记录-上传到云端」进入时，预填本地文件，省去再次选择
    final init = widget.initialVideo;
    if (init != null && init.filePath.isNotEmpty) {
      final path = init.filePath;
      _picked = PlatformFile(
        name: path.split(Platform.pathSeparator).last,
        size: init.sizeBytes,
        path: path,
      );
    }
  }

  Future<void> _pickFile() async {
    if (_uploading) return;
    // 仅允许选择常见视频格式，避免系统选择器弹出非视频文件
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: [
        'mp4',
        'webm',
        'avi',
        'mov',
        'mkv',
        'flv',
        'wmv',
        'm4v',
        'ts',
      ],
      allowMultiple: false,
    );
    if (result != null && result.files.isNotEmpty) {
      setState(() {
        _picked = result.files.single;
        _progress = 0;
        _message = null;
      });
    }
  }

  Future<void> _startUpload() async {
    final picked = _picked;
    if (picked == null) {
      _toast('请先选择视频文件');
      return;
    }
    final path = picked.path;
    if (path == null) {
      _toast('无法读取该文件（无本地路径）');
      return;
    }

    setState(() {
      _uploading = true;
      _progress = 0;
      _message = '正在初始化上传…';
    });

    try {
      final file = File(path);
      final size = await file.length();
      final totalChunks = (size / _chunkSize).ceil();

      // 0. 计算文件 MD5 做秒传检查（同文件不重复上传）
      setState(() => _message = '正在校验文件…');
      final md5 = await _computeFileMd5(file);
      final existing = await _api.checkUploadExists(md5);
      if (existing != null && existing['exists'] == true) {
        if (!mounted) return;
        setState(() {
          _uploading = false;
          _progress = 1;
          _message = '✅ 云端已存在相同视频（秒传）';
        });
        _toast('云端已存在相同视频，已秒传');
        Navigator.of(context).pop();
        return;
      }

      // 1. 初始化上传（带真实 md5，供后端秒传/校验）
      final uploadId =
          await _api.initUpload(picked.name, size, md5, totalChunks);
      if (!mounted) return;
      setState(() => _message = '上传中 0%');

      // 2. 分块上传
      final raf = await file.open();
      try {
        for (int i = 0; i < totalChunks; i++) {
          if (!mounted) return;
          final start = i * _chunkSize;
          final len = (size - start) < _chunkSize ? (size - start) : _chunkSize;
          await raf.setPosition(start);
          final bytes = await raf.read(len);
          await _api.uploadChunk(uploadId, i, bytes);
          if (!mounted) return;
          setState(() {
            _progress = (i + 1) / totalChunks;
            _message =
                '上传中 ${((i + 1) / totalChunks * 100).toStringAsFixed(0)}%';
          });
        }
      } finally {
        await raf.close();
      }

      // 3. 合并入库
      setState(() => _message = '正在合并…');
      final videoId = await _api.mergeChunks(uploadId);
      if (!mounted) return;
      setState(() {
        _uploading = false;
        _progress = 1;
        _message = '✅ 上传成功，已自动开始智能处理（步骤识别）';
      });
      _toast('上传成功，视频 #$videoId 已自动开始处理');
      // 新流程：上传后自动开始步骤识别处理（后端亦自动触发；此处兜底，失败不阻断）
      unawaited(_api.triggerProcess(videoId).catchError((_) {}));
    } catch (e) {
      if (!mounted) return;
      final msg = _friendlyUploadError(e);
      setState(() {
        _uploading = false;
        _message = msg;
      });
      _toast(msg);
    }
  }

  /// 流式计算文件 MD5（秒传用，1MB 分块，避免大文件内存占用）
  Future<String> _computeFileMd5(File file) async {
    final sink = _DigestSink();
    final conv = md5.startChunkedConversion(sink);
    final raf = await file.open();
    try {
      const chunkSize = 1024 * 1024;
      while (true) {
        final bytes = await raf.read(chunkSize);
        if (bytes.isEmpty) break;
        conv.add(bytes);
      }
    } finally {
      await raf.close();
      conv.close();
    }
    return sink.digest?.toString() ?? '';
  }

  /// 上传异常转友好提示（优先提取后端返回的业务信息，如“文件MD5校验失败”等）
  String _friendlyUploadError(Object e) =>
      '上传失败：${friendlyError(e, fallback: '网络或服务器异常，请稍后再试')}';

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final fileName = _picked?.name;
    final fileSize = _picked?.size;
    // 必须用 Scaffold 提供 Material 祖先（InkWell 依赖）与 AppBar 返回按钮，
    // 并用可滚动容器避免内容溢出
    return Scaffold(
      appBar: AppBar(title: const Text('上传视频')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            InkWell(
              onTap: _pickFile,
              child: Container(
                padding: const EdgeInsets.all(48),
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.grey.shade300, width: 2),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  children: [
                    Icon(Icons.cloud_upload,
                        size: 64, color: Colors.grey.shade400),
                    const SizedBox(height: 16),
                    Text(
                      fileName ?? '点击选择视频文件',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 16,
                        color: fileName != null ? Colors.black87 : Colors.grey,
                      ),
                    ),
                    if (fileSize != null)
                      Text(
                        '${(fileSize / 1048576).toStringAsFixed(1)}MB',
                        style:
                            const TextStyle(color: Colors.grey, fontSize: 14),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            if (_uploading) ...[
              LinearProgressIndicator(value: _progress),
              const SizedBox(height: 8),
            ],
            if (_message != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(
                  _message!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13),
                ),
              ),
            ElevatedButton(
              onPressed: _uploading || fileName == null ? null : _startUpload,
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
              child: Text(_uploading ? '上传中…' : '开始上传'),
            ),
          ],
        ),
      ),
    );
  }
}

/// MD5 流式计算输出收集器（crypto 未导出 AccumulatorSink，自实现）
class _DigestSink implements Sink<Digest> {
  Digest? digest;
  @override
  void add(Digest data) => digest = data;
  @override
  void close() {}
}
