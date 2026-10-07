import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import '../providers/xunwei_provider.dart';

/// 成品拍照纠偏（M1）：实拍成品照 + 目标成品参照图（关联视频封面 / 手动上传）→ 双图对比 → 结构化纠偏报告。
/// 入口：视频播放页（videoId+title）、发作品页（initialImagePath+videoId+title）。
class CorrectionReportPage extends StatefulWidget {
  final int? videoId;
  final String? title; // 菜名/菜谱标题
  final String? initialImagePath; // 预置实拍图（发作品页带入），为空则进入时选图
  const CorrectionReportPage(
      {super.key, this.videoId, this.title, this.initialImagePath});

  @override
  State<CorrectionReportPage> createState() => _CorrectionReportPageState();
}

class _CorrectionReportPageState extends State<CorrectionReportPage> {
  final _picker = ImagePicker();
  String? _userImagePath; // 实拍成品照
  String? _refImagePath; // 手动参照图（可选）
  bool _useVideoCover = true; // 用视频封面作参照（需 videoId）
  bool _saveToSession = false; // 纠偏结果是否存入寻味会话
  bool _savedToSession = false; // 本次纠偏是否已写入会话
  bool _loading = false;
  Map<String, dynamic>? _report;

  @override
  void initState() {
    super.initState();
    _userImagePath = widget.initialImagePath;
    _useVideoCover = widget.videoId != null;
    if (_userImagePath == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _pickUserImage());
    }
  }

  bool get _hasReference =>
      (_useVideoCover && widget.videoId != null) || _refImagePath != null;

  Future<void> _pickUserImage() async {
    final src = await _pickSource('成品照');
    if (src == null || !mounted) return;
    final x = await _picker.pickImage(
        source: src, maxWidth: 1600, imageQuality: 85);
    if (x == null || !mounted) return;
    setState(() => _userImagePath = x.path);
  }

  Future<void> _pickRefImage() async {
    final src = await _pickSource('参照图');
    if (src == null || !mounted) return;
    final x = await _picker.pickImage(
        source: src, maxWidth: 1600, imageQuality: 85);
    if (x == null || !mounted) return;
    setState(() => _refImagePath = x.path);
  }

  Future<ImageSource?> _pickSource(String what) {
    return showModalBottomSheet<ImageSource>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera),
              title: Text('拍照$what'),
              onTap: () => Navigator.of(ctx).pop(ImageSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: Text('从相册选择$what'),
              onTap: () => Navigator.of(ctx).pop(ImageSource.gallery),
            ),
          ],
        ),
      ),
    );
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _correct() async {
    if (_userImagePath == null) {
      _toast('请先拍一张成品照');
      return;
    }
    if (!_hasReference) {
      _toast('请上传一张参照图（或使用视频封面）');
      return;
    }
    setState(() {
      _loading = true;
      _report = null;
      _savedToSession = false;
    });
    final provider = context.read<XunweiProvider>();
    // 「存入寻味会话」开启 → 先确保一个寻味会话，纠偏报告将写入该会话
    int? sid;
    if (_saveToSession) {
      sid = await provider.ensureSession();
    }
    final res = await provider.correctByImage(
      filePath: _userImagePath!,
      filename: 'dish.jpg',
      videoId: _useVideoCover ? widget.videoId : null,
      referencePath: _refImagePath,
      referenceFilename: _refImagePath == null ? null : 'ref.jpg',
      title: widget.title,
      sessionId: sid,
    );
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (res != null) _savedToSession = _saveToSession && sid != null;
    });
    if (res == null) {
      final msg = provider.error ?? '成品纠偏失败，请稍后重试';
      // 关联视频无封面/无参照图 → 自动切到「手动上传参照图」模式，便于用户补参照图后重试
      if (msg.contains('参照图')) {
        setState(() => _useVideoCover = false);
      }
      _toast(msg);
      return;
    }
    setState(() => _report = res);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('成品纠偏')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _sectionTitle('1. 你的成品实拍照（待评价）'),
          const SizedBox(height: 8),
          _imageTile(_userImagePath, '拍/选你做的成品照', _pickUserImage),
          const SizedBox(height: 16),
          _sectionTitle('2. 目标成品参照图（标准样）'),
          const SizedBox(height: 8),
          if (widget.videoId != null)
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('以视频封面为目标参照'),
              subtitle: const Text('关闭后手动上传标准成品图作参照'),
              value: _useVideoCover,
              onChanged: (v) => setState(() => _useVideoCover = v),
            ),
          if (!_useVideoCover || widget.videoId == null) ...[
            _imageTile(_refImagePath, '上传目标成品参照图（标准样）', _pickRefImage),
            const SizedBox(height: 8),
          ],
          const SizedBox(height: 12),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('存入寻味会话'),
            subtitle: const Text('纠偏报告将写入寻味会话，可回溯并继续追问'),
            value: _saveToSession,
            onChanged: (v) => setState(() => _saveToSession = v),
          ),
          FilledButton.icon(
            onPressed: _loading ? null : _correct,
            icon: _loading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.compare),
            label: Text(_loading ? 'AI 对比中…' : '开始纠偏'),
          ),
          const SizedBox(height: 20),
          if (_report != null) _buildReport(_report!),
        ],
      ),
    );
  }

  Widget _sectionTitle(String t) => Text(t,
      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15));

  Widget _imageTile(String? path, String hint, VoidCallback onTap) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        height: 180,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.grey.shade400),
        ),
        child: path == null
            ? Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.add_a_photo_outlined,
                      color: Colors.grey, size: 36),
                  const SizedBox(height: 8),
                  Text(hint, style: const TextStyle(color: Colors.grey)),
                ],
              )
            : ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.file(File(path), fit: BoxFit.cover),
              ),
      ),
    );
  }

  Widget _buildReport(Map<String, dynamic> report) {
    final summary = (report['summary'] as String?) ?? '';
    final dims = (report['dimensions'] as List? ?? [])
        .whereType<Map<String, dynamic>>()
        .toList();
    final expGained = (report['expGained'] as num?)?.toInt() ?? 0;
    final ref = (report['reference'] as Map?) ?? {};
    final source = (ref['source'] as String?) ?? '';
    final sourceLabel = switch (source) {
      'thumbnail' => '参照：视频封面',
      'manual' => '参照：手动上传',
      _ => '',
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.insights, color: Color(0xFF2E9E6B)),
            const SizedBox(width: 6),
            const Text('纠偏报告',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
            const Spacer(),
            if (sourceLabel.isNotEmpty)
              Text(sourceLabel,
                  style: const TextStyle(fontSize: 12, color: Colors.grey)),
          ],
        ),
        if (expGained > 0) ...[
          const SizedBox(height: 6),
          Chip(
            avatar: const Icon(Icons.stars, size: 16, color: Colors.amber),
            label: Text('经验 +$expGained'),
            visualDensity: VisualDensity.compact,
          ),
        ],
        if (_savedToSession) ...[
          const SizedBox(height: 6),
          Chip(
            avatar: const Icon(Icons.chat_bubble_outline,
                size: 16, color: Color(0xFF2E9E6B)),
            label: const Text('✅ 已存入寻味会话'),
            visualDensity: VisualDensity.compact,
          ),
        ],
        const SizedBox(height: 10),
        Card(
          color: const Color(0xFFF0F8F4),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Text(summary,
                style: const TextStyle(fontSize: 14, height: 1.5)),
          ),
        ),
        const SizedBox(height: 12),
        ...dims.map((d) => _dimensionTile(d)),
        if (widget.initialImagePath != null) ...[
          const SizedBox(height: 8),
          FilledButton.tonalIcon(
            onPressed: () => Navigator.of(context).pop(summary),
            icon: const Icon(Icons.library_add_outlined, size: 18),
            label: const Text('存入作品描述'),
          ),
        ],
        const SizedBox(height: 8),
        Text('提示：色泽/火候/收汁等判断基于照片可见线索，仅供改进参考。',
            style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
      ],
    );
  }

  Widget _dimensionTile(Map<String, dynamic> d) {
    final name = (d['name'] as String?) ?? '';
    final conclusion = (d['conclusion'] as String?) ?? '';
    final severity = (d['severity'] as String?) ?? 'none';
    final advice = (d['advice'] as String?) ?? '';
    final (Color color, String label) = switch (severity) {
      'heavy' => (const Color(0xFFE74C3C), '重'),
      'medium' => (const Color(0xFFE67E22), '中'),
      'light' => (const Color(0xFFF1C40F), '轻'),
      _ => (const Color(0xFF27AE60), '无'),
    };
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 86,
              child: Text(name,
                  style: const TextStyle(fontWeight: FontWeight.bold)),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Flexible(child: Text(conclusion)),
                      const SizedBox(width: 6),
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 1),
                        decoration: BoxDecoration(
                          color: color.withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text(label,
                            style: TextStyle(
                                fontSize: 11,
                                color: color,
                                fontWeight: FontWeight.bold)),
                      ),
                    ],
                  ),
                  if (advice.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(advice,
                        style: const TextStyle(
                            fontSize: 13, height: 1.4, color: Colors.black87)),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
