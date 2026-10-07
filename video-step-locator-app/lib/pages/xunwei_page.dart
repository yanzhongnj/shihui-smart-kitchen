import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';
import '../models/xunwei_message.dart';
import '../providers/auth_provider.dart';
import '../providers/download_provider.dart';
import '../providers/xunwei_provider.dart';
import '../services/voice_transcriber.dart';
import '../utils/error_message.dart';
import 'download_page.dart';
import 'login_page.dart';
import 'xunwei_memory_page.dart';
import 'xunwei_sessions_page.dart';

/// 寻味聊天页：对话找做菜视频，AI 推荐视频卡片带「去下载」→ 链接下载闭环
class XunweiPage extends StatefulWidget {
  /// 可选：初始化后自动发送的搜索语（P2「跳寻味」：从反查/详情带语境进入找视频教程）
  final String? initialPrompt;
  const XunweiPage({super.key, this.initialPrompt});

  @override
  State<XunweiPage> createState() => _XunweiPageState();
}

class _XunweiPageState extends State<XunweiPage> {
  final _inputController = TextEditingController();
  final _inputFocusNode = FocusNode();
  final _scrollController = ScrollController();
  final _transcriber = VoiceTranscriber();
  bool _initDone = false;
  bool _initStarted = false;
  bool _pttActive = false;
  bool _initialSent = false;

  @override
  void initState() {
    super.initState();
    // 已登录则直接初始化；未登录由 build 中 watch 登录态后在登录后触发
    if (context.read<AuthProvider>().isLoggedIn) _init();
  }

  Future<void> _init() async {
    if (_initStarted) return;
    _initStarted = true;
    final p = context.read<XunweiProvider>();
    try {
      await p.loadSessions();
      if (p.currentSessionId == null) {
        if (p.sessions.isNotEmpty) {
          await p.openSession(p.sessions.first.id);
        } else {
          await p.createSession();
        }
      } else {
        await p.loadMessages(p.currentSessionId!);
      }
    } finally {
      if (mounted) setState(() => _initDone = true);
      _scrollToBottom();
    }
    _maybeSendInitial();
  }

  /// 跳寻味：初始化完成后自动发送 initialPrompt（仅一次）
  void _maybeSendInitial() {
    final prompt = widget.initialPrompt;
    if (prompt == null || prompt.trim().isEmpty || _initialSent) return;
    _initialSent = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        context.read<XunweiProvider>().sendMessage(prompt.trim());
      }
    });
  }

  @override
  void dispose() {
    _inputController.dispose();
    _inputFocusNode.dispose();
    _scrollController.dispose();
    _transcriber.dispose();
    super.dispose();
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
    if (text.trim().isEmpty) return;
    _inputController.clear();
    final p = context.read<XunweiProvider>();
    await p.sendMessage(text.trim());
    if (mounted) _scrollToBottom();
  }

  /// 「去下载」闭环：预解析 URL → 进入下载页（预填 + 显示解析结果）
  Future<void> _goDownload(String url) async {
    final dp = context.read<DownloadProvider>();
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('正在解析视频链接…')));
    await dp.parse(url);
    if (!context.mounted) return;
    Navigator.push(context,
        MaterialPageRoute(builder: (_) => DownloadPage(initialUrl: url)));
  }

  // ---------------- 按住说话语音输入 ----------------

  /// 按住：开始录音
  Future<void> _onPttStart() async {
    if (_pttActive) return;
    setState(() => _pttActive = true);
    try {
      await _transcriber.begin();
    } catch (e) {
      if (!mounted) return;
      setState(() => _pttActive = false);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('语音启动失败：${friendlyError(e, fallback: '请检查麦克风权限或稍后重试')}')));
    }
  }

  /// 松开：停止录音 → 转文字 → 按系统配置决定「确认后寻味」或「自动寻味」
  Future<void> _onPttEnd() async {
    if (!_pttActive) return;
    setState(() => _pttActive = false);
    String text;
    try {
      text = await _transcriber.end();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('语音识别失败：${friendlyError(e, fallback: '请检查网络或稍后重试')}')));
      return;
    }
    if (text.trim().isEmpty) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('没有识别到语音，请再试一次')));
      return;
    }

    if (!mounted) return;
    final needConfirm =
        await context.read<XunweiProvider>().isVoiceConfirmEnabled();
    if (needConfirm) {
      // 需要确认：识别文字填入输入框，用户按回车/发送后才开始寻味
      _inputController.text = text.trim();
      _inputController.selection =
          TextSelection.collapsed(offset: text.trim().length);
      _inputFocusNode.requestFocus();
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('按回车确认，开始寻味')));
    } else {
      // 不需要确认：自动开始寻味
      await _send(text.trim());
    }
  }

  /// 拍照识菜：拍照/相册选图 → 上传 → 后端识别食材 + 寻味推荐（闭环）
  Future<void> _pickAndRecognize() async {
    if (context.read<XunweiProvider>().isSending) return;
    final source = await showModalBottomSheet<ImageSource>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera),
              title: const Text('拍照识菜'),
              subtitle: const Text('拍下食材，识别后可推荐能做的菜谱视频'),
              onTap: () => Navigator.of(ctx).pop(ImageSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('从相册选择食材照片'),
              onTap: () => Navigator.of(ctx).pop(ImageSource.gallery),
            ),
          ],
        ),
      ),
    );
    if (source == null || !mounted) return;
    try {
      final x = await ImagePicker().pickImage(
          source: source, maxWidth: 1600, imageQuality: 85);
      if (x == null || !mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('正在识别食材并寻找菜谱…')));
      await context
          .read<XunweiProvider>()
          .recommendByImage(x.path, x.name);
      if (mounted) _scrollToBottom();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('拍照识菜失败：${friendlyError(e, fallback: '请检查相机权限或稍后重试')}')));
    }
  }

  /// 寻味设置：语音输入后是否需确认
  Future<void> _showSettings() async {
    final xp = context.read<XunweiProvider>();
    final current = await xp.isVoiceConfirmEnabled();
    if (!mounted) return;
    // 开关状态变量提升到 StatefulBuilder 之外：避免每次重建被重置回初始值，导致开关无法切换
    var enabled = current;
    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('寻味设置'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('语音输入后需确认'),
                subtitle: const Text(
                    '开启：松开语音后按回车确认才开始寻味\n关闭：松开语音后自动开始寻味'),
                value: enabled,
                onChanged: (v) => setDialogState(() => enabled = v),
              ),
              const Divider(),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.psychology_outlined),
                title: const Text('偏好进化记忆'),
                subtitle: const Text('查看 / 纠正 / 清除画像，偏好自进化开关'),
                onTap: () {
                  Navigator.of(ctx).pop();
                  Navigator.push(context,
                      MaterialPageRoute(builder: (_) => const XunweiMemoryPage()));
                },
              ),
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(ctx).pop(null),
                child: const Text('取消')),
            FilledButton(
                onPressed: () => Navigator.of(ctx).pop(enabled),
                child: const Text('保存')),
          ],
        ),
      ),
    );
    if (result != null) {
      await xp.setVoiceConfirmEnabled(result);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<XunweiProvider>();
    final session = p.currentSession;
    // 寻味仅注册用户使用：实时监听登录态，登录后自动进入聊天、退出后回到登录引导
    final loggedIn = context.watch<AuthProvider>().isLoggedIn;
    if (loggedIn && !_initDone && !_initStarted) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && !_initDone && !_initStarted) _init();
      });
    }
    return Scaffold(
      appBar: AppBar(
        title: Text(session?.title ?? '寻味'),
        actions: [
          IconButton(
            icon: const Icon(Icons.chat_bubble_outline),
            tooltip: '会话列表',
            onPressed: () async {
              final selected = await Navigator.push<int>(
                context,
                MaterialPageRoute(builder: (_) => const XunweiSessionsPage()),
              );
              if (selected != null && mounted) {
                await context.read<XunweiProvider>().openSession(selected);
                _scrollToBottom();
              }
            },
          ),
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: '寻味设置',
            onPressed: _showSettings,
          ),
        ],
      ),
      body: !loggedIn
          ? _guestView()
          : Column(
              children: [
                Expanded(
                  child: _initDone
                      ? _messageList(p)
                      : const Center(child: CircularProgressIndicator()),
                ),
                if (p.isSending) const LinearProgressIndicator(minHeight: 2),
                _inputBar(p),
              ],
            ),
    );
  }

  /// 游客访问「寻味」的登录引导页
  Widget _guestView() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.lock_outline, size: 56, color: Colors.grey),
            const SizedBox(height: 14),
            const Text('「寻味」需要登录后使用',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            const Text('登录后可用 AI 对话搜索做菜视频',
                style: TextStyle(fontSize: 13, color: Colors.grey)),
            const SizedBox(height: 18),
            FilledButton.icon(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const LoginPage()),
              ),
              icon: const Icon(Icons.login, size: 18),
              label: const Text('去登录'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _messageList(XunweiProvider p) {
    final msgs = p.messages;
    if (msgs.isEmpty) {
      return ListView(
        controller: _scrollController,
        padding: const EdgeInsets.all(16),
        children: const [
          SizedBox(height: 20),
          Text('👋 我是「寻味」，你的做菜视频搜索助手。',
              style: TextStyle(fontSize: 15, height: 1.6)),
          Text('告诉我你想做什么菜，我会帮你找到合适的做菜视频，'
              '并可直接下载跟着学！',
              style: TextStyle(fontSize: 14, height: 1.6, color: Colors.black54)),
          SizedBox(height: 10),
          Text('例如：', style: TextStyle(fontSize: 13, color: Colors.grey)),
          Text('• 我想做鱼香肉丝，推荐几个视频', style: TextStyle(fontSize: 13, color: Colors.grey)),
          Text('• 想吃清淡一点的川菜，有什么推荐', style: TextStyle(fontSize: 13, color: Colors.grey)),
          Text('• 想学做红烧肉，要新手能学会的', style: TextStyle(fontSize: 13, color: Colors.grey)),
        ],
      );
    }
    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.all(12),
      itemCount: msgs.length,
      itemBuilder: (context, i) => _messageItem(msgs[i]),
    );
  }

  Widget _messageItem(XunweiMessage m) {
    if (m.isUser) {
      return Align(
        alignment: Alignment.centerRight,
        child: Container(
          margin: const EdgeInsets.only(bottom: 10, left: 60),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primary,
            borderRadius: BorderRadius.circular(14),
          ),
          child: Text(m.content,
              style: const TextStyle(color: Colors.white, height: 1.4)),
        ),
      );
    }
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(bottom: 10, right: 40),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: Colors.grey.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(m.content, style: const TextStyle(height: 1.4)),
            if (m.videos.isNotEmpty) ...[
              const SizedBox(height: 8),
              ...m.videos.map(_videoCard),
            ],
          ],
        ),
      ),
    );
  }

  Widget _videoCard(XunweiVideo v) {
    return Card(
      margin: const EdgeInsets.only(top: 6),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: Colors.grey.withValues(alpha: 0.2)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (v.thumbnail.isNotEmpty)
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.network(
                      v.thumbnail,
                      width: 84,
                      height: 50,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => Container(
                          width: 84,
                          height: 50,
                          color: Colors.black12,
                          child: const Icon(Icons.movie, size: 20)),
                    ),
                  )
                else
                  Container(
                      width: 84,
                      height: 50,
                      color: Colors.black12,
                      child: const Icon(Icons.movie, size: 20)),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(v.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontWeight: FontWeight.w600, fontSize: 13)),
                      if (v.uploader.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(v.uploader,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                  fontSize: 12, color: Colors.grey)),
                        ),
                      if (v.durationText.isNotEmpty)
                        Text(v.durationText,
                            style: const TextStyle(
                                fontSize: 11, color: Colors.grey)),
                    ],
                  ),
                ),
              ],
            ),
            if (v.reason.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text('💡 ${v.reason}',
                    style: const TextStyle(
                        fontSize: 12, color: Colors.orange, height: 1.3)),
              ),
            const SizedBox(height: 6),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.tonalIcon(
                onPressed: () => _goDownload(v.url),
                icon: const Icon(Icons.download, size: 18),
                label: const Text('去下载'),
                style: FilledButton.styleFrom(
                    visualDensity: VisualDensity.compact),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 按住说话按钮
  Widget _pttMicButton() {
    return Listener(
      onPointerDown: (_) => _onPttStart(),
      onPointerUp: (_) => _onPttEnd(),
      onPointerCancel: (_) => _onPttEnd(),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        margin: const EdgeInsets.only(right: 6),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: _pttActive ? Colors.red : Colors.blueGrey.withValues(alpha: 0.1),
          boxShadow: _pttActive
              ? [
                  BoxShadow(
                      color: Colors.red.withValues(alpha: 0.4),
                      blurRadius: 12,
                      spreadRadius: 2)
                ]
              : null,
        ),
        child: Icon(
          _pttActive ? Icons.mic : Icons.mic_none,
          color: _pttActive ? Colors.white : Colors.blueGrey,
          size: 22,
        ),
      ),
    );
  }

  Widget _inputBar(XunweiProvider p) {
    final hasText = _inputController.text.trim().isNotEmpty;
    return SafeArea(
      top: false,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 录音指示条
          if (_pttActive)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.symmetric(horizontal: 12),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
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
                  Text('正在录音… 松开结束并寻味',
                      style: TextStyle(color: Colors.red, fontSize: 12)),
                ],
              ),
            ),
          Container(
            padding: const EdgeInsets.fromLTRB(10, 6, 10, 8),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surface,
              boxShadow: [
                BoxShadow(
                    color: Colors.black.withValues(alpha: 0.05),
                    blurRadius: 6)
              ],
            ),
            child: Row(
              children: [
                // 文字输入
                Expanded(
                  child: TextField(
                    controller: _inputController,
                    focusNode: _inputFocusNode,
                    textInputAction: TextInputAction.send,
                    minLines: 1,
                    maxLines: 4,
                    onChanged: (_) => setState(() {}),
                    decoration: InputDecoration(
                      hintText: '发消息或按住说话',
                      isDense: true,
                      filled: true,
                      fillColor: Colors.grey.shade100,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(22),
                        borderSide: BorderSide.none,
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 9),
                    ),
                    onSubmitted: _send,
                  ),
                ),
                const SizedBox(width: 6),
                // 语音：按住说话（圆形）
                _pttMicButton(),
                // 发送 / 相机（圆形）：有文字时显示发送，否则为相机（图像）图标
                GestureDetector(
                  onTap: () {
                    if (hasText) {
                      _send(_inputController.text);
                    } else {
                      _pickAndRecognize();
                    }
                  },
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 150),
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: hasText
                          ? Colors.red
                          : Colors.blueGrey.withValues(alpha: 0.1),
                    ),
                    child: Icon(
                      hasText ? Icons.send : Icons.photo_camera_outlined,
                      color: hasText ? Colors.white : Colors.blueGrey,
                      size: 22,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
