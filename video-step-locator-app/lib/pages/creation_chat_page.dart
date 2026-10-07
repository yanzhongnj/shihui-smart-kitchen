import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';
import '../models/creation_models.dart';
import '../providers/creation_provider.dart';
import '../services/voice_transcriber.dart';
import '../utils/error_message.dart';
import 'creation_detail_page.dart';

/// 酿味创作会话页（聊天式多轮共创）
class CreationChatPage extends StatefulWidget {
  final int sessionId;
  /// 可选：初始化后自动发送的创作意图（P2「空结果引导酿味带食材约束」）
  final String? initialPrompt;
  const CreationChatPage({super.key, required this.sessionId, this.initialPrompt});

  @override
  State<CreationChatPage> createState() => _CreationChatPageState();
}

class _CreationChatPageState extends State<CreationChatPage> {
  final _inputController = TextEditingController();
  final _inputFocusNode = FocusNode();
  final _scrollController = ScrollController();
  bool _initDone = false;
  bool _initStarted = false;
  bool _initialSent = false;
  // 多源附件（P1）：最多同时挂一种
  String? _imagePath;
  int? _videoId;
  String? _url;
  // 语音输入（复用寻味 PTT 转写）：转写文本填入输入框待确认
  final _transcriber = VoiceTranscriber();
  bool _voiceActive = false;
  bool _voiceBusy = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  @override
  void dispose() {
    _inputController.dispose();
    _inputFocusNode.dispose();
    _scrollController.dispose();
    _transcriber.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    if (_initStarted) return;
    _initStarted = true;
    final p = context.read<CreationProvider>();
    try {
      await p.openSession(widget.sessionId);
    } finally {
      if (mounted) setState(() => _initDone = true);
      _scrollToBottom();
    }
    _maybeSendInitial();
  }

  /// 空结果引导酿味：初始化后自动发送食材约束创作意图（仅一次）
  void _maybeSendInitial() {
    final prompt = widget.initialPrompt;
    if (prompt == null || prompt.trim().isEmpty || _initialSent) return;
    _initialSent = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _send(prompt.trim());
    });
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _send(String text) async {
    if (text.trim().isEmpty && _imagePath == null && _videoId == null && _url == null) {
      return;
    }
    final img = _imagePath;
    final vid = _videoId;
    final url = _url;
    _inputController.clear();
    setState(() {
      _imagePath = null;
      _videoId = null;
      _url = null;
    });
    final p = context.read<CreationProvider>();
    await p.sendMessage(text.trim(),
        imagePath: img,
        imageFilename: img?.split('/').last,
        videoId: vid,
        url: url);
    if (mounted) _scrollToBottom();
  }

  // ---------------- 语音输入（复用寻味 PTT 转写） ----------------

  /// 按住：开始录音
  Future<void> _onVoiceStart() async {
    if (_voiceActive || _voiceBusy) return;
    setState(() => _voiceActive = true);
    try {
      await _transcriber.begin();
    } catch (e) {
      if (!mounted) return;
      setState(() => _voiceActive = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('语音启动失败：${friendlyError(e, fallback: '请检查麦克风权限或稍后重试')}')));
    }
  }

  /// 松开：停止录音 → 转文字 → 作为语音附件挂载
  Future<void> _onVoiceEnd() async {
    if (!_voiceActive) return;
    setState(() => _voiceActive = false);
    if (_voiceBusy) return;
    _voiceBusy = true;
    String text;
    try {
      text = await _transcriber.end();
    } catch (e) {
      if (!mounted) return;
      _voiceBusy = false;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('语音识别失败：${friendlyError(e, fallback: '请检查网络或稍后重试')}')));
      return;
    }
    if (!mounted) return;
    _voiceBusy = false;
    if (text.trim().isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('没有识别到语音，请再试一次')));
      return;
    }
    // 转写文本填入输入框并聚焦，供用户编辑/确认后发送
    _inputController.text = text.trim();
    _inputController.selection =
        TextSelection.collapsed(offset: _inputController.text.length);
    _inputFocusNode.requestFocus();
    setState(() {});
  }

  /// 拍照/相册选图
  Future<void> _pickImage() async {
    final source = await showModalBottomSheet<ImageSource>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera),
              title: const Text('拍照（食材/参考菜）'),
              onTap: () => Navigator.of(ctx).pop(ImageSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('从相册选择'),
              onTap: () => Navigator.of(ctx).pop(ImageSource.gallery),
            ),
          ],
        ),
      ),
    );
    if (source == null || !mounted) return;
    try {
      final x = await ImagePicker()
          .pickImage(source: source, maxWidth: 1600, imageQuality: 85);
      if (x != null && mounted) {
        setState(() {
          _imagePath = x.path;
          _videoId = null;
          _url = null;
        });
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('选图失败：${friendlyError(e, fallback: '请重试')}')));
    }
  }

  /// 引用系统视频（输入视频 ID）
  Future<void> _addVideoRef() async {
    final ctrl = TextEditingController();
    final v = await showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('引用系统视频'),
        content: TextField(
          controller: ctrl,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(hintText: '输入视频 ID（如 21）'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, int.tryParse(ctrl.text.trim())),
            child: const Text('引用'),
          ),
        ],
      ),
    );
    if (v != null && mounted) {
      setState(() {
        _videoId = v;
        _imagePath = null;
        _url = null;
      });
    }
  }

  /// 粘贴外部链接
  Future<void> _addUrl() async {
    final ctrl = TextEditingController();
    final u = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('粘贴外部链接'),
        content: TextField(
          controller: ctrl,
          decoration: const InputDecoration(
              hintText: 'B站/抖音等菜谱链接'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
            child: const Text('添加'),
          ),
        ],
      ),
    );
    if (u != null && u.isNotEmpty && mounted) {
      setState(() {
        _url = u;
        _imagePath = null;
        _videoId = null;
      });
    }
  }

  /// 确认创作意图 → 生成菜谱 → 进详情
  Future<void> _confirm() async {
    final p = context.read<CreationProvider>();
    final detail = await p.confirm();
    if (!mounted) return;
    if (detail != null && detail['id'] != null) {
      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (_) =>
              CreationDetailPage(creationId: (detail['id'] as num).toInt()),
        ),
      );
      return;
    }
    // 生成失败：滚动到最新气泡展示原因，并弹提示，避免「点了没反应」的困惑
    _scrollToBottom();
    final err = p.error;
    if (err != null && err.isNotEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(err)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<CreationProvider>();
    return Scaffold(
      appBar: AppBar(
        title: Text(p.currentSession?.title ?? '酿味 AI'),
      ),
      body: Column(
        children: [
          Expanded(
            child: _initDone
                ? ListView.builder(
                    controller: _scrollController,
                    padding: const EdgeInsets.all(12),
                    itemCount: p.messages.length,
                    itemBuilder: (ctx, i) =>
                        _bubble(p.messages[i], i == p.messages.length - 1),
                  )
                : const Center(child: CircularProgressIndicator()),
          ),
          if (p.lastAction == 'confirm') _confirmCard(p),
          _inputBar(p),
        ],
      ),
    );
  }

  Widget _bubble(CreationMessage m, bool isLast) {
    final isUser = m.role == 'user';
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.78),
        decoration: BoxDecoration(
          color: isUser ? const Color(0xFF4F7CF7) : const Color(0xFFF2F3F5),
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(14),
            topRight: const Radius.circular(14),
            bottomLeft: Radius.circular(isUser ? 14 : 2),
            bottomRight: Radius.circular(isUser ? 2 : 14),
          ),
        ),
        child: Text(
          m.content,
          style: TextStyle(
            color: isUser ? Colors.white : Colors.black87,
            fontSize: 15,
            height: 1.4,
          ),
        ),
      ),
    );
  }

  /// 意图确认卡（AI 判定 confirm 后展示）
  Widget _confirmCard(CreationProvider p) {
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 4, 12, 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF6E5),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFFFB74D)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.auto_awesome, color: Color(0xFFFF8E53), size: 18),
              SizedBox(width: 6),
              Text('创作意向已明确',
                  style: TextStyle(fontWeight: FontWeight.bold)),
            ],
          ),
          const SizedBox(height: 8),
          Text(p.messages.isEmpty
              ? 'AI 已理解你的创作想法，点击下方按钮生成菜谱。'
              : 'AI 已理解你的创作想法，点击下方按钮生成菜谱。',
              style: const TextStyle(color: Colors.black87)),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: p.isConfirming ? null : _confirm,
              icon: p.isConfirming
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.restaurant, size: 18),
              label: Text(p.isConfirming ? '正在创作…' : '确认并生成菜谱'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _inputBar(CreationProvider p) {
    final hasAttach = _imagePath != null || _videoId != null || _url != null;
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
        color: Theme.of(context).colorScheme.surface,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (_voiceActive)
              Container(
                width: double.infinity,
                margin: const EdgeInsets.symmetric(vertical: 4),
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: Colors.red.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.fiber_manual_record,
                        color: Colors.red, size: 12),
                    SizedBox(width: 6),
                    Text('正在录音… 松开结束',
                        style: TextStyle(color: Colors.red, fontSize: 12)),
                  ],
                ),
              ),
            Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.photo_camera_outlined, size: 20),
                  tooltip: '拍照/选图（识食材）',
                  onPressed: _pickImage,
                ),
                IconButton(
                  icon: const Icon(Icons.video_library_outlined, size: 20),
                  tooltip: '引用系统视频',
                  onPressed: _addVideoRef,
                ),
                IconButton(
                  icon: const Icon(Icons.link, size: 20),
                  tooltip: '粘贴外部链接',
                  onPressed: _addUrl,
                ),
                if (hasAttach)
                  Expanded(
                    child: _pendingAttachChip(),
                  ),
              ],
            ),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _inputController,
                    focusNode: _inputFocusNode,
                    enabled: !p.isSending,
                    minLines: 1,
                    maxLines: 3,
                    textInputAction: TextInputAction.send,
                    onSubmitted: _send,
                    decoration: InputDecoration(
                      hintText: '描述食材 / 口味 / 场景，AI 帮你发明新菜…',
                      isDense: true,
                      filled: true,
                      fillColor: const Color(0xFFF2F3F5),
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 10),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(22),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                _voiceMicButton(),
                const SizedBox(width: 4),
                IconButton.filled(
                  onPressed:
                      p.isSending ? null : () => _send(_inputController.text),
                  icon: const Icon(Icons.send),
                  color: Colors.white,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 按住说话（语音附件）：复用寻味 PTT 转写
  Widget _voiceMicButton() {
    return Listener(
      onPointerDown: (_) => _onVoiceStart(),
      onPointerUp: (_) => _onVoiceEnd(),
      onPointerCancel: (_) => _onVoiceEnd(),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        margin: const EdgeInsets.only(right: 2),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: _voiceActive
              ? Colors.red
              : const Color(0xFF4F7CF7).withValues(alpha: 0.1),
          boxShadow: _voiceActive
              ? [
                  BoxShadow(
                      color: Colors.red.withValues(alpha: 0.4),
                      blurRadius: 12,
                      spreadRadius: 2)
                ]
              : null,
        ),
        child: Icon(
          _voiceActive ? Icons.mic : Icons.mic_none,
          color: _voiceActive ? Colors.white : const Color(0xFF4F7CF7),
          size: 20,
        ),
      ),
    );
  }

  Widget _pendingAttachChip() {
    String label;
    if (_imagePath != null) {
      label = '📷 图片（识食材）';
    } else if (_videoId != null) {
      label = '📎 视频 #$_videoId';
    } else {
      label = '🔗 ${_url ?? ''}';
    }
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(left: 4),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: const Color(0xFFE8F0FE),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(
              child: Text(label,
                  maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12, color: Color(0xFF4F7CF7))),
            ),
            const SizedBox(width: 4),
            GestureDetector(
              onTap: () => setState(() {
                _imagePath = null;
                _videoId = null;
                _url = null;
              }),
              child: const Icon(Icons.close, size: 14, color: Color(0xFF4F7CF7)),
            ),
          ],
        ),
      ),
    );
  }
}
