import 'dart:async';
import 'dart:io';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:video_player/video_player.dart';
import '../models/creation_models.dart';
import '../providers/creation_provider.dart';
import '../utils/error_message.dart';
import '../widgets/calorie_badge.dart';
import 'creation_chat_page.dart';
import 'creation_cook_page.dart';
import 'video_player_page.dart';
import '../models/video_step.dart';

/// 酿味创作详情页：菜名/理念/标签 + 图文步骤 + 计时器 + 迭代/版本/评价/衍生/删除
class CreationDetailPage extends StatefulWidget {
  final int creationId;
  const CreationDetailPage({super.key, required this.creationId});

  @override
  State<CreationDetailPage> createState() => _CreationDetailPageState();
}

class _CreationDetailPageState extends State<CreationDetailPage> {
  CreationDetail? _detail;
  bool _loading = true;
  String? _error;
  // AI 视频教程状态（P2）
  Map<String, dynamic>? _video;
  bool _videoLoading = false;
  bool _videoError = false;
  // 视频生成实时进度（轮询 /creation/video-gen/tasks）
  Map<String, dynamic>? _videoProgress;
  Timer? _videoPollTimer;
  int? _videoTotalSegments;
  // TTS 语音讲解状态
  Map<String, dynamic>? _tts;
  bool _ttsLoading = false;
  // 语音讲解试听（独立于视频，可单独生成/试听）
  final AudioPlayer _ttsPreviewPlayer = AudioPlayer();
  int? _ttsPreviewingStep;
  // 步骤折叠/展开（按 index，展开时显示完整内容）
  final Set<int> _expandedSteps = <int>{};
  // 酱汁设计（P0）：当前酱汁配方（未确认仅存于内存，确认后落库回显）
  Map<String, dynamic>? _sauce;
  bool _sauceLoading = false;
  // 酱汁效果图（P1）
  String? _effectImageUrl;
  bool _effectLoading = false;
  bool _effectError = false;
  Map<String, dynamic>? _effectProgress;
  Timer? _effectPollTimer;

  @override
  void initState() {
    super.initState();
    // 试听播放结束自动复位选中态
    _ttsPreviewPlayer.onPlayerComplete.listen((_) {
      if (mounted) setState(() => _ttsPreviewingStep = null);
    });
    _load();
  }

  @override
  void dispose() {
    _videoPollTimer?.cancel();
    _effectPollTimer?.cancel();
    _ttsPreviewPlayer.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final d = await context.read<CreationProvider>().getDetail(widget.creationId);
      if (!mounted) return;
      setState(() {
        _detail = CreationDetail.fromJson(d);
        _loading = false;
        _error = null;
      });
      // 尽力拉取视频状态（不阻塞详情）
      try {
        final vt = await context.read<CreationProvider>().videoTasks(widget.creationId);
        if (mounted) setState(() => _video = vt);
        // 若已有生成任务在跑（如从详情页返回），恢复实时进度轮询
        if (vt['videoStatus'] == 'generating') {
          if (mounted) {
            setState(() {
              _videoLoading = true;
              _videoError = false;
              _videoProgress = vt;
              _videoTotalSegments ??= (vt['steps'] as List?)?.length;
            });
          }
          _startVideoPolling();
        }
      } catch (_) {}
      // 尽力拉取语音讲解状态（不阻塞详情）
      try {
        final t = await context.read<CreationProvider>().ttsStatus(widget.creationId);
        if (mounted) setState(() => _tts = t);
      } catch (_) {}
      // 尽力拉取效果图状态（不阻塞详情）：已生成则显示；生成中恢复轮询
      try {
        final ei = await context
            .read<CreationProvider>()
            .effectImageTasks(widget.creationId);
        if (!mounted) return;
        final effUrl = ei['effectImage'] as String?;
        if (effUrl != null && effUrl.isNotEmpty) {
          // version=effectImage 相对路径 → URL 带 v 参数打破缓存
          final url = await context
              .read<CreationProvider>()
              .effectImageUrl(widget.creationId, version: effUrl);
          if (!mounted) return;
          setState(() => _effectImageUrl = url);
        }
        final tasks = (ei['tasks'] as List? ?? [])
            .whereType<Map>()
            .map((e) => Map<String, dynamic>.from(e))
            .toList();
        if (tasks.any((t) => t['status'] == 'running' || t['status'] == 'queued')) {
          setState(() {
            _effectLoading = true;
            _effectError = false;
            _effectProgress = ei;
          });
          _startEffectPolling();
        }
      } catch (_) {}
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = friendlyError(e, fallback: '加载创作失败');
        _loading = false;
      });
    }
  }

  void _showSnack(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  // ---------------- AI 视频教程（P2） ----------------

  Future<void> _genVideo() async {
    setState(() {
      _videoLoading = true;
      _videoError = false;
      _videoProgress = null;
    });
    try {
      final p = context.read<CreationProvider>();
      var r = await p.generateVideo(widget.creationId);
      // 付费引擎：先返回成本预估，需用户确认（Mock 免费直接 started）
      if (r['needConfirm'] == true) {
        if (!mounted) return;
        final ok = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('确认生成视频教程？'),
            content: Text(
                '预计 ${r['segments'] ?? 0} 段共 ${r['seconds'] ?? 0} 秒，约 ${r['estimatedCost'] ?? 0} 元。确认后开始生成，可实时查看逐段进度。'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('取消')),
              FilledButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('确认生成')),
            ],
          ),
        );
        if (ok != true) {
          if (mounted) setState(() => _videoLoading = false);
          return;
        }
        if (!mounted) return;
        r = await p.generateVideo(widget.creationId, confirm: true);
      }
      // 异步长任务已启动（started:true，含 segments 总段数）→ 轮询逐段进度
      if (!mounted) return;
      setState(() {
        _videoProgress = null;
        _videoTotalSegments = r['segments'] as int? ?? _videoTotalSegments;
      });
      _startVideoPolling();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _videoLoading = false;
        _videoError = true;
      });
      _showSnack('生成视频失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  // ---------------- 视频生成实时进度（轮询） ----------------

  void _startVideoPolling() {
    _videoPollTimer?.cancel();
    _videoPollTimer =
        Timer.periodic(const Duration(seconds: 3), (_) => _pollVideoProgress());
    _pollVideoProgress();
  }

  void _stopVideoPolling() {
    _videoPollTimer?.cancel();
    _videoPollTimer = null;
  }

  Future<void> _pollVideoProgress() async {
    if (!mounted) return;
    try {
      final t =
          await context.read<CreationProvider>().videoTasks(widget.creationId);
      if (!mounted) return;
      final status = t['videoStatus'] as String?;
      setState(() => _videoProgress = Map<String, dynamic>.from(t));
      if (status == 'ready') {
        _stopVideoPolling();
        setState(() {
          _video = {
            'videoStatus': 'ready',
            'durationSec': t['durationSec'],
            'steps': t['steps'],
          };
          _videoLoading = false;
          _videoProgress = null;
        });
        _showSnack(
            '视频教程已生成！${t['durationSec'] ?? ''} 秒 · ${(t['steps'] as List?)?.length ?? 0} 段');
        try {
          final tt = await context
              .read<CreationProvider>()
              .ttsStatus(widget.creationId);
          if (mounted) setState(() => _tts = tt);
        } catch (_) {}
      } else if (status == 'failed') {
        _stopVideoPolling();
        setState(() {
          _videoLoading = false;
          _videoError = true;
          _videoProgress = null;
        });
      }
    } catch (_) {
      // 轮询失败忽略，下个周期重试
    }
  }

  /// 生成/刷新语音讲解（阿里云 qwen3-tts-instruct-flash）
  Future<void> _genTts() async {
    setState(() => _ttsLoading = true);
    try {
      final r = await context.read<CreationProvider>().generateTts(widget.creationId);
      if (!mounted) return;
      setState(() {
        _tts = r;
        _ttsLoading = false;
      });
      if (r['needKey'] == true) {
        _showSnack(r['message'] as String? ?? '语音讲解未配置（ALIYUN_ASR_APP_KEY）');
      } else if (r['ready'] == true) {
        _showSnack('语音讲解已就绪（${(r['items'] as List?)?.length ?? 0} 段）');
      } else {
        _showSnack('部分步骤讲解生成失败，可重试');
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _ttsLoading = false);
      _showSnack('语音讲解生成失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  /// 试听某步语音讲解（独立播放器，再次点击停止）
  Future<void> _toggleTtsPreview(int stepIndex) async {
    if (_ttsPreviewingStep == stepIndex) {
      await _ttsPreviewPlayer.stop();
      if (mounted) setState(() => _ttsPreviewingStep = null);
      return;
    }
    try {
      final url = await context
          .read<CreationProvider>()
          .ttsUrl(widget.creationId, stepIndex);
      await _ttsPreviewPlayer.stop();
      await _ttsPreviewPlayer.play(UrlSource(url));
      if (mounted) setState(() => _ttsPreviewingStep = stepIndex);
    } catch (e) {
      if (!mounted) return;
      _showSnack('试听失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  Future<void> _openVideo() async {
    final steps = (_video?['steps'] as List? ?? [])
        .whereType<Map<String, dynamic>>()
        .toList();
    String url;
    try {
      url = await context.read<CreationProvider>().videoUrl(widget.creationId);
    } catch (e) {
      if (!mounted) return;
      _showSnack('视频打开失败：${friendlyError(e, fallback: '请重试')}');
      return;
    }
    // 本地缓存在播放器内完成（本地优先：命中缓存本地播，否则先下载到本地再播）
    if (!mounted) return;
    // 复用体验播放器 VideoPlayerPage（步骤高亮/跳转/循环/倍速/语音控制）
    final aiSteps = steps
        .map((s) => VideoStep(
              id: (s['index'] as num?)?.toInt() ?? 0,
              stepIndex: (s['index'] as num?)?.toInt() ?? 0,
              summary: s['summary'] as String? ?? '',
              startTime: (s['start'] as num?)?.toDouble() ?? 0,
              endTime: (s['end'] as num?)?.toDouble() ?? 0,
              confidence: 'high',
            ))
        .toList();
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => VideoPlayerPage(
          videoId: widget.creationId,
          initialTitle: _detail?.title ?? 'AI 视频教程',
          aiVideoUrl: url,
          aiSteps: aiSteps,
          // 语音讲解：可用的步骤序号集合（播放器内按步骤切换时播放对应讲解）
          aiNarrationSteps: ((_tts?['items'] as List?) ?? [])
              .whereType<Map<String, dynamic>>()
              .where((m) => m['ready'] == true)
              .map((m) => (m['index'] as num?)?.toInt() ?? -1)
              .where((i) => i >= 0)
              .toSet(),
        ),
      ),
    );
  }

  // ---------------- 迭代（菜谱不满意） ----------------

  Future<void> _revise() async {
    final controller = TextEditingController();
    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('不满意？告诉我怎么改'),
        content: TextField(
          controller: controller,
          maxLines: 3,
          decoration: const InputDecoration(
            hintText: '例如：更辣一点 / 主料换成豆腐 / 步骤精简到 5 步',
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, controller.text.trim()),
              child: const Text('提交修改')),
        ],
      ),
    );
    if (action == null || action.isEmpty) return;
    if (!mounted) return;
    final p = context.read<CreationProvider>();
    final r = await p.revise(widget.creationId, action);
    if (!mounted) return;
    if (r['action'] == 'revise') {
      _showSnack('已更新：${r['reply'] ?? ''} ${r['deltaNote'] ?? ''}');
    } else {
      _showSnack(r['reply'] as String? ?? 'AI 需要更多信息');
    }
    await _load();
  }

  // ---------------- 版本回退 ----------------

  Future<void> _showVersions() async {
    final p = context.read<CreationProvider>();
    final items = await p.versions(widget.creationId);
    if (!mounted) return;
    showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text('菜谱版本历史',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            ),
            for (final v in (items as List? ?? []))
              ListTile(
                leading: const Icon(Icons.history),
                title: Text('v${v['version']}'),
                subtitle: Text(v['deltaNote'] as String? ?? ''),
                trailing: IconButton(
                  icon: const Icon(Icons.restore),
                  tooltip: '回退到此版本',
                  onPressed: () async {
                    Navigator.pop(ctx);
                    await p.revert(
                        widget.creationId, (v['version'] as num).toInt());
                    if (!mounted) return;
                    _showSnack('已回退到 v${v['version']}');
                    await _load();
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }

  // ---------------- 口味评价 ----------------

  Future<void> _rate() async {
    String? selected;
    final controller = TextEditingController();
    final r = await showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSt) => AlertDialog(
          title: const Text('这道菜味道如何？'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Wrap(
                spacing: 8,
                children: [
                  ChoiceChip(
                      label: const Text('😋 好吃'),
                      selected: selected == 'delicious',
                      onSelected: (_) => setSt(() => selected = 'delicious')),
                  ChoiceChip(
                      label: const Text('😐 一般'),
                      selected: selected == 'ok',
                      onSelected: (_) => setSt(() => selected = 'ok')),
                  ChoiceChip(
                      label: const Text('💥 翻车'),
                      selected: selected == 'failed',
                      onSelected: (_) => setSt(() => selected = 'failed')),
                ],
              ),
              const SizedBox(height: 10),
              TextField(
                controller: controller,
                decoration: const InputDecoration(
                  hintText: '补充评价（可选）',
                  isDense: true,
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
              onPressed: selected == null
                  ? null
                  : () => Navigator.pop(ctx, selected),
              child: const Text('提交'),
            ),
          ],
        ),
      ),
    );
    if (r == null) return;
    if (!mounted) return;
    await context
        .read<CreationProvider>()
        .feedback(widget.creationId, r, controller.text.trim());
    if (mounted) _showSnack('感谢评价，已记入偏好记忆');
  }

  // ---------------- 衍生创作 ----------------

  Future<void> _derive() async {
    final p = context.read<CreationProvider>();
    final r = await p.derive(widget.creationId);
    if (!mounted) return;
    final sid = (r['sessionId'] as num?)?.toInt();
    if (sid == null) {
      _showSnack('衍生创作失败');
      return;
    }
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => CreationChatPage(sessionId: sid)),
    );
  }

  // ---------------- 站外分享（P4） ----------------

  Future<void> _share() async {
    try {
      final r = await context
          .read<CreationProvider>()
          .shareCreation(widget.creationId);
      if (!mounted) return;
      final code = r['code'] as String? ?? '';
      final url = r['shareUrl'] as String? ?? '';
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('分享这道 AI 新菜'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('分享码：'),
              SelectableText(code,
                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              const SizedBox(height: 8),
              const Text('分享链接：'),
              SelectableText(url),
              const SizedBox(height: 8),
              const Text('只读分享，未登录用户可查看菜谱内容',
                  style: TextStyle(fontSize: 12, color: Colors.black54)),
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
          ],
        ),
      );
    } catch (e) {
      if (!mounted) return;
      _showSnack('分享失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  // ---------------- 升B：导出普通视频 / 发布菜谱库（r82） ----------------

  /// 导出 AI 视频为普通视频（讲解语音合成进音轨，建「我的视频」记录，审核通过后可公开）
  Future<void> _exportVideo() async {
    if (!mounted) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('导出为普通视频？'),
        content: const Text(
            '将 AI 视频与同步语音讲解合成为一条普通视频，保存到「我的视频」'
            '（含讲解音轨、可按步骤跳转），审核通过后可公开。此操作免费、不重新生成。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('导出')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      final r = await context
          .read<CreationProvider>()
          .exportVideoToRegular(widget.creationId);
      if (!mounted) return;
      _showSnack('已导出为普通视频（我的视频 #${r['videoId']}），审核通过后可公开');
    } catch (e) {
      if (!mounted) return;
      _showSnack('导出失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  /// 发布菜谱到常规菜谱库（镜像 recipes，source=AI_CREATE，带 AI 创作角标）
  Future<void> _publishRecipe() async {
    if (!mounted) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('发布到菜谱库？'),
        content: const Text(
            '将本 AI 创作镜像到常规「品味」库（带 AI 创作角标），'
            '可在菜谱列表/搜索中被查看。此操作免费。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('发布')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      final r = await context
          .read<CreationProvider>()
          .publishRecipeToLibrary(widget.creationId);
      if (!mounted) return;
      _showSnack('已发布到菜谱库（#${r['libraryRecipeId']}）');
    } catch (e) {
      if (!mounted) return;
      _showSnack('发布失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  // ---------------- 删除 ----------------

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除这个创作？'),
        content: const Text('将同时删除版本历史，且不可恢复。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              style: FilledButton.styleFrom(
                  backgroundColor: Colors.red.shade400),
              child: const Text('删除')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await context.read<CreationProvider>().deleteCreation(widget.creationId);
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('创作详情')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Text(_error!))
              : _detail == null
                  ? const Center(child: Text('创作不存在'))
                  : _content(_detail!),
    );
  }

  Widget _content(CreationDetail d) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(d.title,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        if (d.summary.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text(d.summary, style: const TextStyle(color: Colors.black54)),
        ],
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final t in d.tasteTags)
              Chip(
                  label: Text(t),
                  visualDensity: VisualDensity.compact,
                  backgroundColor: const Color(0xFFFFF3E0)),
            Chip(
              label: Text('辣度 ${d.spiceLevel ?? 0}/10'),
              visualDensity: VisualDensity.compact,
            ),
            if (d.estimatedTime != null)
              Chip(
                label: Text('约 ${d.estimatedTime} 分钟'),
                visualDensity: VisualDensity.compact,
              ),
            Chip(
              label: Text(_diffText(d.difficulty)),
              visualDensity: VisualDensity.compact,
            ),
            if (d.calories != null)
              Chip(
                label: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('${d.calories} 千卡'),
                    const SizedBox(width: 6),
                    CalorieBadge(d.calories),
                  ],
                ),
                visualDensity: VisualDensity.compact,
              ),
          ],
        ),
        const SizedBox(height: 6),
        Text('v${d.version} · 已迭代 ${d.iterCount} 次',
            style: const TextStyle(fontSize: 12, color: Colors.black38)),
        const SizedBox(height: 12),
        if (d.mainIngredients.isNotEmpty) ...[
          _sectionTitle('主料'),
          _tagWrap(d.mainIngredients),
          const SizedBox(height: 8),
        ],
        if (d.auxiliaryIngredients.isNotEmpty) ...[
          _sectionTitle('辅料 / 调料'),
          _tagWrap(d.auxiliaryIngredients),
          const SizedBox(height: 8),
        ],
        _sectionTitle('图文步骤'),
        const SizedBox(height: 6),
        // 单列垂直布局：每个步骤一行，默认折叠（只显示标题+快捷键），点击展开完整内容
        Column(
          children: [
            for (final s in d.steps) ...[
              _stepCard(s is Map ? Map<String, dynamic>.from(s) : {}),
              const SizedBox(height: 8),
            ],
          ],
        ),
        const SizedBox(height: 8),
        if (d.allergens.isNotEmpty)
          Text('⚠️ 过敏原提示：${d.allergens.join('、')}',
              style: TextStyle(color: Colors.red.shade600)),
        const SizedBox(height: 8),
        if (d.safetyItems.isNotEmpty) ...[_safetySection(d.safetyItems), const SizedBox(height: 8)],
        Center(
          child: Text(d.disclaimer,
              style: const TextStyle(fontSize: 11, color: Colors.black38)),
        ),
        const SizedBox(height: 12),
        _sauceSection(d),
        const SizedBox(height: 8),
        _effectImageSection(d),
        const SizedBox(height: 8),
        _videoSection(d),
        const SizedBox(height: 8),
        _actionsRow(d),
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _safetySection(List<Map<String, dynamic>> items) {
    return Card(
      color: const Color(0xFFFFFBF2),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('🛡️ 食物安全提示',
                style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            for (final it in items) ...[_safetyItem(it), const SizedBox(height: 6)],
          ],
        ),
      ),
    );
  }

  Widget _safetyItem(Map<String, dynamic> it) {
    final level = '${it['level'] ?? ''}';
    final icon = level == 'RED'
        ? '🔴'
        : level == 'YELLOW'
            ? '🟡'
            : '🟢';
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$icon '),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('${it['title'] ?? ''}',
                  style: const TextStyle(fontWeight: FontWeight.w600)),
              if ('${it['advice'] ?? ''}'.isNotEmpty)
                Text('${it['advice']}',
                    style: const TextStyle(fontSize: 12, color: Colors.black54)),
            ],
          ),
        ),
      ],
    );
  }

  // ---------------- 酱汁设计（P0，纯 LLM） ----------------

  Future<void> _designSauce() async {
    final controller = TextEditingController();
    final demand = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('设计酱汁'),
        content: TextField(
          controller: controller,
          maxLines: 2,
          decoration: const InputDecoration(
            hintText: '可选需求：如 偏甜 / 微辣 / 低脂 / 无糖…（留空则由 AI 自定）',
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, controller.text.trim()),
              child: const Text('开始设计')),
        ],
      ),
    );
    if (demand == null) return;
    if (!mounted) return;
    setState(() => _sauceLoading = true);
    try {
      final r = await context
          .read<CreationProvider>()
          .designSauce(widget.creationId, demand: demand.isEmpty ? null : demand);
      if (!mounted) return;
      setState(() {
        _sauce = Map<String, dynamic>.from(r);
        _sauceLoading = false;
      });
      _showSnack('酱汁已设计好，可确认或换一种口味');
    } catch (e) {
      if (!mounted) return;
      setState(() => _sauceLoading = false);
      _showSnack('设计酱汁失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  Future<void> _reviseSauce() async {
    final controller = TextEditingController();
    final demand = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('怎么调整酱汁？'),
        content: TextField(
          controller: controller,
          maxLines: 2,
          decoration: const InputDecoration(
            hintText: '例如：更辣一点 / 换成低脂版 / 不要蒜',
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, controller.text.trim()),
              child: const Text('重新设计')),
        ],
      ),
    );
    if (demand == null || demand.isEmpty) return;
    if (!mounted) return;
    setState(() => _sauceLoading = true);
    try {
      final r = await context
          .read<CreationProvider>()
          .reviseSauce(widget.creationId, demand);
      if (!mounted) return;
      setState(() => _sauceLoading = false);
      if (r['action'] == 'revise' && r['sauce'] is Map) {
        setState(() => _sauce = Map<String, dynamic>.from(r['sauce'] as Map));
        _showSnack('已更新酱汁：${r['deltaNote'] ?? ''}');
      } else {
        _showSnack(r['reply'] as String? ?? 'AI 需要更多信息');
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _sauceLoading = false);
      _showSnack('调整酱汁失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  Future<void> _confirmSauce() async {
    final sauce = _sauce;
    if (sauce == null) return;
    if (!mounted) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认这份酱汁？'),
        content: const Text('确认后将保存到本菜谱，可随时查看。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确认保存')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await context
          .read<CreationProvider>()
          .confirmSauce(widget.creationId, sauce);
      if (!mounted) return;
      _showSnack('酱汁已保存 ✓');
      await _load();
    } catch (e) {
      if (!mounted) return;
      _showSnack('确认失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  Widget _sauceSection(CreationDetail d) {
    final saved = d.sauceJson ?? _sauce;
    return Card(
      color: const Color(0xFFFFF8F0),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(children: [
              Icon(Icons.local_drink_outlined, color: Color(0xFFE8853B), size: 18),
              SizedBox(width: 6),
              Text('酱汁设计',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            ]),
            const SizedBox(height: 8),
            if (_sauceLoading)
              const Row(children: [
                SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2)),
                SizedBox(width: 8),
                Expanded(
                    child: Text('AI 正在设计酱汁…',
                        style: TextStyle(color: Colors.black54, fontSize: 13))),
              ])
            else if (saved != null && saved.isNotEmpty)
              _sauceCard(saved)
            else ...[
              const Text('为这道菜设计一份专属酱汁（口味/比例/替代版，AI 生成）',
                  style: TextStyle(color: Colors.black54, fontSize: 12)),
              const SizedBox(height: 8),
              FilledButton.tonalIcon(
                onPressed: _designSauce,
                icon: const Icon(Icons.auto_awesome),
                label: const Text('设计酱汁'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _sauceCard(Map<String, dynamic> s) {
    final name = s['name'] as String? ?? '';
    final base = s['base'] as String? ?? '';
    final ratio = (s['ratio'] as List? ?? []).whereType<Map>().toList();
    final steps = (s['steps'] as List? ?? []).whereType<String>().toList();
    final alts = (s['alternatives'] as List? ?? []).whereType<Map>().toList();
    final cal = s['calories'];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Expanded(
            child: Text(name.isEmpty ? '酱汁配方' : name,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
          ),
          if (cal != null)
            Text('约 $cal 千卡',
                style: const TextStyle(fontSize: 12, color: Colors.black54)),
        ]),
        if (base.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text(base, style: const TextStyle(color: Colors.black87)),
        ],
        if (ratio.isNotEmpty) ...[
          const SizedBox(height: 6),
          for (final r in ratio)
            Text('• ${r['item'] ?? ''}：${r['amount'] ?? ''}',
                style: const TextStyle(fontSize: 13)),
        ],
        if (steps.isNotEmpty) ...[
          const SizedBox(height: 6),
          for (final st in steps)
            Text('${steps.indexOf(st) + 1}. $st',
                style: const TextStyle(fontSize: 13, color: Colors.black54)),
        ],
        if (alts.isNotEmpty) ...[
          const SizedBox(height: 6),
          const Text('替代版：',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
          for (final a in alts)
            Text('• ${a['name'] ?? ''}：${a['note'] ?? ''}',
                style: const TextStyle(fontSize: 12, color: Colors.black54)),
        ],
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          children: [
            if (_sauce != null)
              FilledButton(
                onPressed: _confirmSauce,
                style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFFE8853B)),
                child: const Text('确认酱汁'),
              ),
            OutlinedButton(
                onPressed: _reviseSauce, child: const Text('换一种口味')),
          ],
        ),
        if (s == _sauce && _sauce != null && _detail?.sauceJson == null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text('未确认，仅展示',
                style: TextStyle(fontSize: 11, color: Colors.orange.shade700)),
          ),
      ],
    );
  }

  // ---------------- 酱汁效果图（P1，文生图 + 成本控制） ----------------

  Future<void> _genEffectImage() async {
    if (!mounted) return;
    // 风格选择
    final style = await showDialog<String>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('选择效果图风格'),
        children: [
          _styleOption(ctx, '写实美食摄影', 'realistic'),
          _styleOption(ctx, '精致摆盘', 'plated'),
          _styleOption(ctx, '美食插画', 'illustration'),
        ],
      ),
    );
    if (style == null || !mounted) return;
    setState(() {
      _effectLoading = true;
      _effectError = false;
      _effectProgress = null;
    });
    try {
      final p = context.read<CreationProvider>();
      var r = await p.generateEffectImage(widget.creationId, style: style);
      // 付费引擎：先返回成本预估，需用户确认（Mock 免费直接 started）
      if (r['needConfirm'] == true) {
        if (!mounted) return;
        final ok = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('确认生成效果图？'),
            content: Text('约 ${r['estimatedCost'] ?? 0} 元/张。确认后开始生成。'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('取消')),
              FilledButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('确认生成')),
            ],
          ),
        );
        if (ok != true) {
          if (mounted) setState(() => _effectLoading = false);
          return;
        }
        if (!mounted) return;
        r = await p.generateEffectImage(widget.creationId, confirm: true, style: style);
      }
      if (!mounted) return;
      setState(() {
        _effectLoading = true;
        _effectProgress = null;
        _effectError = false;
      });
      _startEffectPolling();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _effectLoading = false;
        _effectError = true;
      });
      _showSnack('生成效果图失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  Widget _styleOption(BuildContext ctx, String label, String value) {
    return SimpleDialogOption(
      onPressed: () => Navigator.pop(ctx, value),
      child: Text(label),
    );
  }

  void _startEffectPolling() {
    _effectPollTimer?.cancel();
    _effectPollTimer = Timer.periodic(
        const Duration(seconds: 3), (_) => _pollEffectProgress());
    _pollEffectProgress();
  }

  void _stopEffectPolling() {
    _effectPollTimer?.cancel();
    _effectPollTimer = null;
  }

  Future<void> _pollEffectProgress() async {
    if (!mounted) return;
    try {
      final t = await context
          .read<CreationProvider>()
          .effectImageTasks(widget.creationId);
      if (!mounted) return;
      final tasks = (t['tasks'] as List? ?? [])
          .whereType<Map>()
          .map((e) => Map<String, dynamic>.from(e))
          .toList();
      final running = tasks.any(
          (x) => x['status'] == 'running' || x['status'] == 'queued');
      final failed = tasks.any((x) => x['status'] == 'failed');
      if (!running) {
        _stopEffectPolling();
        final eff = t['effectImage'] as String?;
        if (eff != null && eff.isNotEmpty) {
          // version=新 effectImage 路径 → 重新生成后 URL 变化，强制加载新图
          final url = await context
              .read<CreationProvider>()
              .effectImageUrl(widget.creationId, version: eff);
          if (!mounted) return;
          setState(() {
            _effectImageUrl = url;
            _effectLoading = false;
            _effectError = false;
            _effectProgress = null;
          });
          _showSnack('效果图已生成！');
          await _load();
        } else if (failed) {
          setState(() {
            _effectLoading = false;
            _effectError = true;
            _effectProgress = null;
          });
        } else {
          setState(() => _effectLoading = false);
        }
        return;
      }
      setState(() => _effectProgress = t);
    } catch (_) {
      // 轮询失败忽略，下个周期重试
    }
  }

  Widget _effectImageSection(CreationDetail d) {
    final url = _effectImageUrl;
    return Card(
      color: const Color(0xFFF0F7F0),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(children: [
              Icon(Icons.image_outlined, color: Color(0xFF3E8E5A), size: 18),
              SizedBox(width: 6),
              Text('AI 效果图',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            ]),
            const SizedBox(height: 8),
            if (_effectLoading || _effectProgress != null)
              _effectProgressPanel()
            else if (url != null && url.isNotEmpty)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.network(
                      url,
                      height: 240,
                      width: double.infinity,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => Container(
                        height: 240,
                        color: Colors.black12,
                        child: const Center(child: Text('图片加载失败')),
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    children: [
                      FilledButton.tonalIcon(
                        onPressed: _genEffectImage,
                        icon: const Icon(Icons.refresh),
                        label: const Text('重新生成'),
                      ),
                    ],
                  ),
                ],
              )
            else if (_effectError)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('效果图生成失败，请重试',
                      style: TextStyle(color: Colors.red)),
                  const SizedBox(height: 8),
                  FilledButton.tonalIcon(
                    onPressed: _genEffectImage,
                    icon: const Icon(Icons.auto_awesome),
                    label: const Text('生成效果图'),
                  ),
                ],
              )
            else ...[
              const Text('用 AI 为这道菜生成精美效果图（写实/摆盘/插画）',
                  style: TextStyle(color: Colors.black54, fontSize: 12)),
              const SizedBox(height: 8),
              FilledButton.tonalIcon(
                onPressed: _genEffectImage,
                icon: const Icon(Icons.auto_awesome),
                label: const Text('生成效果图'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// 效果图生成进度面板（轮询 /effect-image/tasks）
  Widget _effectProgressPanel() {
    final t = _effectProgress;
    final tasks = ((t?['tasks'] as List?) ?? [])
        .whereType<Map>()
        .map((e) => Map<String, dynamic>.from(e))
        .toList();
    final done = tasks.where((x) => x['status'] == 'completed').length;
    final failed = tasks.where((x) => x['status'] == 'failed').length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Row(children: [
          SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2)),
          SizedBox(width: 8),
          Expanded(
              child: Text('正在生成效果图…',
                  style: TextStyle(color: Colors.black54, fontSize: 13))),
        ]),
        if (tasks.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text('已完成 $done/${tasks.length} 张${failed > 0 ? ' · 失败 $failed 张' : ''}',
              style: const TextStyle(fontSize: 12, color: Colors.black54)),
        ],
      ],
    );
  }

  Widget _videoSection(CreationDetail d) {
    final status = _video?['videoStatus'] as String?;
    final dur = _video?['durationSec'];
    final steps = _video?['steps'] as List? ?? [];
    return Card(
      color: const Color(0xFFF4F8FF),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(children: [
              Icon(Icons.movie_outlined, color: Color(0xFF4F7CF7), size: 18),
              SizedBox(width: 6),
              Text('AI 视频教程',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            ]),
            const SizedBox(height: 8),
            if (_videoLoading || _videoProgress != null || status == 'generating')
              _videoProgressPanel()
            else if (status == 'ready')
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('已生成 ${dur ?? ''} 秒视频教程 · ${steps.length} 段（可点击步骤跳转）',
                      style: const TextStyle(color: Colors.black87)),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    children: [
                      FilledButton.icon(
                        onPressed: _openVideo,
                        icon: const Icon(Icons.play_arrow),
                        label: const Text('▶ 播放教程'),
                      ),
                      OutlinedButton(
                        onPressed: _genVideo,
                        child: const Text('重新生成'),
                      ),
                      OutlinedButton.icon(
                        onPressed: _exportVideo,
                        icon: const Icon(Icons.ios_share),
                        label: const Text('导出为普通视频'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Divider(height: 16),
                  _ttsSection(videoReady: true),
                ],
              )
            else if (_videoError)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('视频生成失败，请重试',
                      style: TextStyle(color: Colors.red)),
                  const SizedBox(height: 8),
                  const Divider(height: 16),
                  _ttsSection(videoReady: false),
                ],
              )
            else
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('为步骤生成 AI 视频教程（支持逐段进度查看）',
                      style: TextStyle(color: Colors.black54)),
                  const SizedBox(height: 8),
                  FilledButton.icon(
                    onPressed: _genVideo,
                    icon: const Icon(Icons.auto_awesome),
                    label: const Text('生成视频教程'),
                  ),
                  const SizedBox(height: 8),
                  const Divider(height: 16),
                  _ttsSection(videoReady: false),
                ],
              ),
          ],
        ),
      ),
    );
  }

  /// 语音讲解区（独立于视频：可单独生成/试听；生成视频后播放器自动同步朗读）
  Widget _ttsSection({required bool videoReady}) {
    final ttsReady = _tts?['ready'] == true;
    final ttsNeedKey = _tts?['needKey'] == true;
    final ttsItems = ((_tts?['items'] as List?) ?? [])
        .whereType<Map<String, dynamic>>()
        .toList();
    final ttsSteps = ttsItems.where((m) => m['ready'] == true).length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.record_voice_over_outlined,
                color: Color(0xFF4F7CF7), size: 18),
            const SizedBox(width: 6),
            const Text('语音讲解',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
            const Spacer(),
            if (_ttsLoading)
              const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2))
            else if (ttsReady)
              TextButton(onPressed: _genTts, child: const Text('重新生成'))
            else
              FilledButton.tonal(
                  onPressed: _genTts, child: const Text('生成语音讲解')),
          ],
        ),
        if (!_ttsLoading)
          Text(
            ttsReady
                ? (videoReady
                    ? '播放教程时将同步朗读每步操作（$ttsSteps 段）'
                    : '语音讲解已生成（$ttsSteps 段），生成视频教程后将自动同步朗读')
                : (ttsNeedKey
                    ? '需配置 ALIYUN_ASR_APP_KEY 后可用'
                    : '为每步生成语音讲解，可独立试听（生成视频教程后自动同步朗读）'),
            style: const TextStyle(color: Colors.black54, fontSize: 12),
          ),
        if (ttsReady && !_ttsLoading)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Wrap(
              spacing: 6,
              runSpacing: 4,
              children: [
                for (final m in ttsItems)
                  if (m['ready'] == true)
                    ActionChip(
                      avatar: Icon(
                        _ttsPreviewingStep == (m['index'] as num?)?.toInt()
                            ? Icons.stop_circle_outlined
                            : Icons.play_arrow,
                        size: 16,
                      ),
                      label: Text('试听第${m['index']}步'),
                      onPressed: () =>
                          _toggleTtsPreview((m['index'] as num?)?.toInt() ?? 0),
                    ),
              ],
            ),
          ),
      ],
    );
  }

  /// 单段重创：仅重做该步骤视频片段，其余段复用后重拼（复用进度轮询）
  /// 预览单步视频分段（弹框播放）
  Future<void> _previewStep(int stepIndex) async {
    String url;
    try {
      url = await context
          .read<CreationProvider>()
          .segmentUrl(widget.creationId, stepIndex);
    } catch (e) {
      if (!mounted) return;
      _showSnack('预览失败：${friendlyError(e, fallback: '请重试')}');
      return;
    }
    // 分段本地优先：命中本地缓存则本地播放；否则先下载到本地缓存
    if (mounted) _showSnack('正在缓存步骤视频…');
    String? localPath;
    try {
      localPath = await context
          .read<CreationProvider>()
          .cacheAiSegmentLocal(widget.creationId, stepIndex);
    } catch (_) {}
    if (!mounted) return;
    showDialog(
      context: context,
      builder: (_) => _StepPreviewDialog(
        url: localPath ?? url,
        localPath: localPath,
        title: '步骤 $stepIndex 预览',
      ),
    );
  }

  Future<void> _regenerateStep(int stepIndex) async {
    if (!mounted) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('重做此段视频？'),
        content: const Text('仅重新生成该步骤的视频片段，其余步骤保留，完成后自动重新拼接。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('重做此段')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() {
      _videoLoading = true;
      _videoError = false;
      _videoProgress = null;
    });
    try {
      await context
          .read<CreationProvider>()
          .regenerateVideoStep(widget.creationId, stepIndex);
      if (!mounted) return;
      _startVideoPolling();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _videoLoading = false;
        _videoError = true;
      });
      _showSnack('重做此段失败：${friendlyError(e, fallback: '请稍后重试')}');
    }
  }

  /// 视频生成实时进度面板（轮询 /creation/video-gen/tasks：进度条 + 当前段 + 分段状态）
  Widget _videoProgressPanel() {
    final t = _videoProgress;
    final tasks = ((t?['tasks'] as List?) ?? [])
        .whereType<Map>()
        .map((m) => Map<String, dynamic>.from(m))
        .toList();
    final total = _videoTotalSegments ?? tasks.length;
    final done = tasks.where((x) => x['status'] == 'completed').length;
    final failed = tasks.where((x) => x['status'] == 'failed').length;
    final running = tasks.where((x) => x['status'] == 'running').toList();
    final runIdx = running.isEmpty ? null : running.first['stepIndex'];
    final progress = total == 0 ? null : (done + failed) / total;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2)),
          const SizedBox(width: 8),
          const Expanded(
              child: Text('正在生成视频教程（逐段生成+拼接）…',
                  style: TextStyle(color: Colors.black54, fontSize: 13))),
        ]),
        const SizedBox(height: 8),
        if (total > 0) ...[
          LinearProgressIndicator(
            value: progress,
            minHeight: 6,
            backgroundColor: const Color(0xFFD9E2F7),
            color: const Color(0xFF4F7CF7),
          ),
          const SizedBox(height: 6),
          Text(
            '已完成 $done/$total 段'
            '${runIdx != null ? ' · 正在生成第 $runIdx 段' : ''}'
            '${failed > 0 ? ' · 失败 $failed 段' : ''}',
            style: const TextStyle(fontSize: 12, color: Colors.black54),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 4,
            children: [
              for (final x in tasks)
                Chip(
                  label: Text(
                      '${x['stepIndex']} ${_taskStatusText(x['status'])}',
                      style: const TextStyle(fontSize: 11)),
                  visualDensity: VisualDensity.compact,
                  backgroundColor:
                      _taskStatusColor(x['status']).withValues(alpha: 0.12),
                  labelStyle: TextStyle(color: _taskStatusColor(x['status'])),
                ),
            ],
          ),
        ] else
          const Text('正在初始化生成任务…',
              style: TextStyle(fontSize: 12, color: Colors.black54)),
      ],
    );
  }

  String _taskStatusText(Object? s) => switch (s) {
        'running' => '生成中',
        'completed' => '完成',
        'failed' => '失败',
        _ => '排队',
      };

  Color _taskStatusColor(Object? s) => switch (s) {
        'running' => const Color(0xFF4F7CF7),
        'completed' => const Color(0xFF2E9E6B),
        'failed' => const Color(0xFFE74C3C),
        _ => const Color(0xFF8A94A6),
      };

  Widget _actionsRow(CreationDetail d) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        FilledButton.icon(
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute(
                builder: (_) => CreationCookPage(creationId: d.id)),
          ),
          icon: const Icon(Icons.play_arrow),
          label: const Text('跟做'),
        ),
        OutlinedButton.icon(
          onPressed: _revise,
          icon: const Icon(Icons.edit_note),
          label: const Text('不满意·迭代'),
        ),
        OutlinedButton.icon(
          onPressed: _showVersions,
          icon: const Icon(Icons.history),
          label: const Text('版本'),
        ),
        OutlinedButton.icon(
          onPressed: _rate,
          icon: const Icon(Icons.star_outline),
          label: const Text('评价'),
        ),
        OutlinedButton.icon(
          onPressed: _derive,
          icon: const Icon(Icons.auto_awesome),
          label: const Text('衍生'),
        ),
        OutlinedButton.icon(
          onPressed: _share,
          icon: const Icon(Icons.share_outlined),
          label: const Text('分享'),
        ),
        OutlinedButton.icon(
          onPressed: _publishRecipe,
          icon: const Icon(Icons.library_add_outlined),
          label: const Text('发布菜谱'),
        ),
        TextButton.icon(
          onPressed: _delete,
          icon: Icon(Icons.delete_outline, color: Colors.red.shade400),
          label: Text('删除',
              style: TextStyle(color: Colors.red.shade400)),
        ),
      ],
    );
  }

  Widget _stepCard(Map<String, dynamic> s) {
    final timer = (s['timer_min'] as num?)?.toInt() ?? 0;
    final stepIndex = (s['index'] as num?)?.toInt() ?? 0;
    final videoReady = _video?['videoStatus'] == 'ready' && !_videoLoading;
    final expanded = _expandedSteps.contains(stepIndex);
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            // 标题行：点击切换折叠/展开（默认折叠只显示标题，展开后显示完整内容）
            InkWell(
              onTap: () => _toggleStep(stepIndex),
              borderRadius: BorderRadius.circular(6),
              child: Row(
                children: [
                  CircleAvatar(
                    radius: 12,
                    backgroundColor: const Color(0xFF4F7CF7),
                    child: Text('${s['index'] ?? ''}',
                        style:
                            const TextStyle(color: Colors.white, fontSize: 12)),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text('${s['summary'] ?? ''}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontWeight: FontWeight.bold, fontSize: 14)),
                  ),
                  Icon(
                    expanded ? Icons.expand_less : Icons.expand_more,
                    size: 18,
                    color: Colors.black38,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 6),
            // 功能快捷键（折叠/展开均显示）：编辑 / 预览 / 重做此段
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: [
                // 编辑步骤（始终可用，仅改图文文本不触发生成）
                TextButton.icon(
                  onPressed: () => _editStep(stepIndex, s),
                  icon: const Icon(Icons.edit_outlined, size: 14),
                  style: TextButton.styleFrom(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    minimumSize: const Size(0, 26),
                    visualDensity: VisualDensity.compact,
                  ),
                  label: const Text('编辑',
                      style: TextStyle(
                          fontSize: 11.5, color: Color(0xFF4F7CF7))),
                ),
                if (videoReady) ...[
                  TextButton.icon(
                    onPressed: () => _previewStep(stepIndex),
                    icon: const Icon(Icons.play_circle_outline, size: 14),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 6, vertical: 2),
                      minimumSize: const Size(0, 26),
                      visualDensity: VisualDensity.compact,
                    ),
                    label: const Text('预览',
                        style: TextStyle(
                            fontSize: 11.5, color: Color(0xFF4F7CF7))),
                  ),
                  TextButton(
                    onPressed: () => _regenerateStep(stepIndex),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 6, vertical: 2),
                      minimumSize: const Size(0, 26),
                      visualDensity: VisualDensity.compact,
                    ),
                    child: const Text('重做此段',
                        style: TextStyle(
                            fontSize: 11.5, color: Color(0xFF4F7CF7))),
                  ),
                ],
              ],
            ),
            // 展开时才显示完整内容（不截断，保证内容完整）
            if (expanded) ...[
              const Divider(height: 12),
              Text('${s['detail'] ?? ''}',
                  style: const TextStyle(
                      color: Colors.black87, height: 1.5, fontSize: 13.5)),
              if (timer > 0) ...[const SizedBox(height: 6),
                Row(children: [
                  const Icon(Icons.timer_outlined,
                      size: 13, color: Color(0xFFE67E22)),
                  const SizedBox(width: 4),
                  Text('约 $timer 分钟',
                      style: const TextStyle(
                          fontSize: 11.5, color: Color(0xFFE67E22))),
                ]),
              ],
            ],
          ],
        ),
      ),
    );
  }

  void _toggleStep(int stepIndex) {
    setState(() {
      if (!_expandedSteps.remove(stepIndex)) {
        _expandedSteps.add(stepIndex);
      }
    });
  }

  /// 编辑单步内容（弹框修改步骤名/详情/耗时），仅改图文文本，不触发生成
  Future<void> _editStep(int stepIndex, Map<String, dynamic> step) async {
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => _StepEditDialog(
        summary: step['summary'] as String? ?? '',
        detail: step['detail'] as String? ?? '',
        timerMin: (step['timer_min'] as num?)?.toInt() ?? 0,
      ),
    );
    if (result == null || !mounted) return;
    final summary = result['summary'] as String?;
    final detail = result['detail'] as String?;
    final timerMin = result['timerMin'] as int?;
    try {
      await context.read<CreationProvider>().updateStep(
            widget.creationId,
            stepIndex,
            summary: summary,
            detail: detail,
            timerMin: timerMin,
          );
      if (!mounted) return;
      // 本地更新已展示的步骤，避免整页刷新闪烁
      setState(() {
        final list = _detail?.steps;
        if (list != null) {
          for (final s in list) {
            if (s is Map &&
                (s['index'] as num?)?.toInt() == stepIndex) {
              if (summary != null) s['summary'] = summary;
              if (detail != null) s['detail'] = detail;
              if (timerMin != null) s['timer_min'] = timerMin;
            }
          }
        }
        // 保存后默认展开该步骤，便于查看修改效果
        _expandedSteps.add(stepIndex);
      });
      _showSnack('步骤 $stepIndex 已更新');
    } catch (e) {
      if (!mounted) return;
      _showSnack('保存失败：${friendlyError(e, fallback: '请重试')}');
    }
  }

  Widget _sectionTitle(String t) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: Text(t,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
      );

  Widget _tagWrap(List<String> tags) => Wrap(
        spacing: 6,
        runSpacing: 6,
        children: [
          for (final t in tags)
            Chip(
              label: Text(t),
              visualDensity: VisualDensity.compact,
            ),
        ],
      );

  String _diffText(String d) => switch (d) {
        'easy' => '简单',
        'hard' => '较难',
        _ => '中等',
      };
}

/// 步骤分段预览弹框（轻量：点击画面播放/暂停，右上角关闭退出）
class _StepPreviewDialog extends StatefulWidget {
  final String url;
  final String? localPath;
  final String title;
  const _StepPreviewDialog(
      {required this.url, this.localPath, required this.title});

  @override
  State<_StepPreviewDialog> createState() => _StepPreviewDialogState();
}

class _StepPreviewDialogState extends State<_StepPreviewDialog> {
  VideoPlayerController? _controller;
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    _controller = widget.localPath != null
        ? VideoPlayerController.file(File(widget.localPath!))
        : VideoPlayerController.networkUrl(Uri.parse(widget.url));
    _controller!.initialize().then((_) {
      if (!mounted) return;
      setState(() => _ready = true);
      _controller!.play();
    }).catchError((Object _) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = _controller;
    return Dialog(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 8, 0),
            child: Row(
              children: [
                const Icon(Icons.movie_outlined,
                    size: 18, color: Color(0xFF4F7CF7)),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(widget.title,
                      style: const TextStyle(
                          fontWeight: FontWeight.bold, fontSize: 15)),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: !_ready
                ? const SizedBox(
                    height: 120,
                    child: Center(child: CircularProgressIndicator()))
                : c == null
                    ? const SizedBox(
                        height: 120,
                        child: Center(child: Text('预览加载失败')))
                    : AspectRatio(
                        aspectRatio: c.value.aspectRatio,
                        child: GestureDetector(
                          onTap: () =>
                              c.value.isPlaying ? c.pause() : c.play(),
                          child: Stack(
                            alignment: Alignment.center,
                            children: [
                              VideoPlayer(c),
                              if (!c.value.isPlaying)
                                const Icon(Icons.play_circle_fill,
                                    size: 56, color: Colors.white70),
                            ],
                          ),
                        ),
                      ),
          ),
        ],
      ),
    );
  }
}

/// 步骤编辑弹框：修改步骤名/详情/耗时（controller 由弹框自身管理生命周期）
class _StepEditDialog extends StatefulWidget {
  final String summary;
  final String detail;
  final int timerMin;
  const _StepEditDialog({
    required this.summary,
    required this.detail,
    required this.timerMin,
  });

  @override
  State<_StepEditDialog> createState() => _StepEditDialogState();
}

class _StepEditDialogState extends State<_StepEditDialog> {
  late final TextEditingController _summaryCtrl;
  late final TextEditingController _detailCtrl;
  late final TextEditingController _timerCtrl;

  @override
  void initState() {
    super.initState();
    _summaryCtrl = TextEditingController(text: widget.summary);
    _detailCtrl = TextEditingController(text: widget.detail);
    _timerCtrl = TextEditingController(
        text: widget.timerMin > 0 ? '${widget.timerMin}' : '');
  }

  @override
  void dispose() {
    _summaryCtrl.dispose();
    _detailCtrl.dispose();
    _timerCtrl.dispose();
    super.dispose();
  }

  void _save() {
    final summary = _summaryCtrl.text.trim();
    if (summary.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('步骤名不能为空')));
      return;
    }
    final timerMin = int.tryParse(_timerCtrl.text.trim());
    Navigator.pop(context, {
      'summary': summary,
      'detail': _detailCtrl.text.trim(),
      'timerMin': timerMin,
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('编辑步骤'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: _summaryCtrl,
              maxLines: 2,
              decoration: const InputDecoration(
                  labelText: '步骤名', hintText: '例如：热锅下油，爆香葱姜'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _detailCtrl,
              maxLines: 6,
              decoration: const InputDecoration(
                  labelText: '步骤详情', hintText: '描述该步骤的具体操作…'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _timerCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                  labelText: '耗时（分钟，可留空）', hintText: '例如 5'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('取消')),
        FilledButton(onPressed: _save, child: const Text('保存')),
      ],
    );
  }
}
