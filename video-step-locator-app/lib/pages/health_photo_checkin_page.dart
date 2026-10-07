import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';
import '../providers/photo_checkin_provider.dart';
import '../services/api_service.dart';
import '../services/voice_transcriber.dart';
import '../utils/error_message.dart';
import 'diet_page.dart';

/// 拍照打卡（饮食记录 · 拍照识别）：拍照/相册 → 多菜识别预览 → 份量调整/候选换选 → 一键确认打卡。
/// 设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §9。
class HealthPhotoCheckinPage extends StatefulWidget {
  const HealthPhotoCheckinPage({super.key, this.mealType});

  /// 预选餐次；为空按当前时段猜测
  final String? mealType;

  @override
  State<HealthPhotoCheckinPage> createState() => _HealthPhotoCheckinPageState();
}

class _HealthPhotoCheckinPageState extends State<HealthPhotoCheckinPage> {
  final _picker = ImagePicker();
  final _transcriber = VoiceTranscriber();

  @override
  void dispose() {
    _transcriber.dispose();
    super.dispose();
  }

  String? _imagePath;
  Map<String, dynamic>? _preview;
  List<Map<String, dynamic>> _portion = [];
  List<String> _candidates = [];
  String? _error;

  int? get _photoId => (_preview?['photoId'] as num?)?.toInt();

  static String _guessMealType() {
    final h = DateTime.now().hour;
    if (h < 10) return 'breakfast';
    if (h < 14) return 'lunch';
    if (h < 21) return 'dinner';
    return 'snack';
  }

  Future<void> _pickAndUpload() async {
    final src = await _pickSource();
    if (src == null || !mounted) return;
    final x = await _picker.pickImage(
        source: src, maxWidth: 1600, imageQuality: 85);
    if (x == null || !mounted) return;
    setState(() {
      _imagePath = x.path;
      _preview = null;
      _portion = [];
      _candidates = [];
      _error = null;
    });
    final provider = context.read<PhotoCheckinProvider>();
    final mealType = widget.mealType ?? _guessMealType();
    final res = await provider.checkinByPhoto(
      filePath: x.path,
      filename: 'meal.jpg',
      mealType: mealType,
    );
    if (!mounted) return;
    if (res != null) {
      final portion = (res['portion'] as List? ?? [])
          .whereType<Map>()
          .map((e) => Map<String, dynamic>.from(e))
          .toList();
      setState(() {
        _preview = res;
        _portion = portion;
        _candidates = (res['candidates'] as List? ?? [])
            .whereType<String>()
            .toList();
        _error = provider.error;
      });
    } else {
      setState(() => _error = provider.error ?? '拍照识别失败，请稍后重试');
    }
  }

  Future<ImageSource?> _pickSource() {
    return showModalBottomSheet<ImageSource>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera),
              title: const Text('拍一张饭菜照片'),
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
  }

  double _dishCalories(Map<String, dynamic> p) {
    final base = (p['calories'] as num?)?.toDouble() ?? 0;
    final grams = (p['portionGrams'] as num?)?.toDouble() ?? 300;
    final baseGrams = ((p['baseGrams'] ?? 300) as num).toDouble();
    return baseGrams > 0 ? base * grams / baseGrams : base;
  }

  double _totals(String key) {
    double t = 0;
    for (final p in _portion) {
      if (key == 'calories') {
        t += _dishCalories(p);
      } else {
        final base = (p[key] as num?)?.toDouble() ?? 0;
        final grams = (p['portionGrams'] as num?)?.toDouble() ?? 300;
        final baseGrams = ((p['baseGrams'] ?? 300) as num).toDouble();
        t += baseGrams > 0 ? base * grams / baseGrams : base;
      }
    }
    return t;
  }

  Future<void> _confirm() async {
    if (_photoId == null || _portion.isEmpty) return;
    final items = _portion
        .map((p) => {
              'dishName': p['dishName'],
              'portionGrams': p['portionGrams'],
            })
        .toList();
    final provider = context.read<PhotoCheckinProvider>();
    final res = await provider.confirm(_photoId!, items);
    if (!mounted) return;
    if (res != null) {
      final exp = (res['expGained'] as num?)?.toInt() ?? 0;
      final dietId = res['dietRecordId'];
      final today = res['today'] as Map? ?? {};
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('✅ 打卡成功'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('已记录 ${_portion.length} 道菜，合计约 ${_totals('calories').round()} 千卡'),
              if (exp > 0) Text('经验 +$exp'),
              if (dietId != null) Text('饮食记录 #$dietId'),
              const SizedBox(height: 6),
              Text(
                '今日热量 ${(today['calories'] ?? 0)} / ${(today['targets']?['calories'] ?? 0)} 千卡',
                style: const TextStyle(color: Colors.grey),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('完成'),
            ),
          ],
        ),
      );
      if (mounted) Navigator.of(context).pop(true);
    } else {
      _toast(provider.error ?? '确认打卡失败，请稍后重试');
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<PhotoCheckinProvider>();
    return Scaffold(
      appBar: AppBar(title: const Text('📷 拍照记录')),
      body: _buildBody(provider),
    );
  }

  Widget _buildBody(PhotoCheckinProvider provider) {
    if (_imagePath == null) {
      return _buildStart();
    }
    if (provider.uploading) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 12),
            Text('AI 识别菜品中…'),
          ],
        ),
      );
    }
    if (_preview == null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 40, color: Colors.grey),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Text(_error ?? '识别失败',
                  textAlign: TextAlign.center),
            ),
            const SizedBox(height: 16),
            FilledButton(onPressed: _pickAndUpload, child: const Text('重新拍照')),
          ],
        ),
      );
    }
    return _buildPreview(provider);
  }

  Widget _buildStart() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.camera_alt_outlined,
                size: 72, color: Color(0xFF2E9E6B)),
            const SizedBox(height: 16),
            const Text('拍一张饭菜照片',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            const Text('AI 识别菜品与份量，一键完成饮食记录与打卡',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.grey)),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _pickAndUpload,
              icon: const Icon(Icons.photo_camera),
              label: const Text('拍照记录'),
              style: FilledButton.styleFrom(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 32, vertical: 14)),
            ),
            const SizedBox(height: 12),
            TextButton.icon(
              onPressed: () {
                // 改用手动记餐：进入饮食记录页并自动弹出「手动记一餐」对话框
                // 用 push（非 pushReplacement）保留拍照记录页在栈中，
                // 手动记餐被取消时可由 DietPage 返回到本页。
                Navigator.of(context).push(MaterialPageRoute(
                  builder: (_) => const DietPage(autoOpenManual: true),
                ));
              },
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('改用手动记餐'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPreview(PhotoCheckinProvider provider) {
    final totalCal = _totals('calories').round();
    final level = _preview?['calorieLevel'] as Map? ?? {};
    final levelColor = _calorieColor('${level['color'] ?? ''}');
    final ratio = (_preview?['ratio'] as num?)?.toInt() ?? 0;
    final targets = _preview?['targets'] as Map? ?? {};
    final targetCal = (targets['calories'] as num?)?.toInt() ?? 0;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(12),
            children: [
              if (_imagePath != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: Image.file(File(_imagePath!),
                      height: 170, fit: BoxFit.cover),
                ),
              const SizedBox(height: 12),
              Text('识别到 ${_portion.length} 道菜，可调份量或换选',
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              for (int i = 0; i < _portion.length; i++)
                _buildDishCard(i),
              const SizedBox(height: 12),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Text('合计热量',
                              style: TextStyle(fontWeight: FontWeight.bold)),
                          const Spacer(),
                          Text('$totalCal 千卡',
                              style: TextStyle(
                                  fontSize: 20,
                                  fontWeight: FontWeight.bold,
                                  color: levelColor)),
                          const SizedBox(width: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 8, vertical: 2),
                            decoration: BoxDecoration(
                              color: levelColor.withValues(alpha: 0.15),
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Text('${level['label'] ?? ''}',
                                style: TextStyle(
                                    color: levelColor, fontSize: 12)),
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        targetCal > 0
                            ? '本餐约占今日目标 $ratio%（目标 $targetCal 千卡）'
                            : '本餐约 $totalCal 千卡',
                        style: const TextStyle(color: Colors.grey, fontSize: 13),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 8),
              Text('估算值仅供参考，非医疗建议',
                  style: TextStyle(color: Colors.grey.shade500, fontSize: 12)),
            ],
          ),
        ),
        SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // P1 语音补充：按住说菜名加菜（长按由外层 GestureDetector 处理）
                GestureDetector(
                  onLongPressStart: (_) => _beginVoice(),
                  onLongPressEnd: (_) => _voiceAddDish(),
                  child: OutlinedButton.icon(
                    onPressed: () {},
                    icon: const Icon(Icons.mic, size: 18),
                    label: const Text('🎤 按住说菜名加菜'),
                    style: OutlinedButton.styleFrom(
                        padding:
                            const EdgeInsets.symmetric(vertical: 12)),
                  ),
                ),
                const SizedBox(height: 8),
                FilledButton(
                  onPressed: provider.confirming ? null : _confirm,
                  style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14)),
                  child: provider.confirming
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('✅ 确认打卡'),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildDishCard(int index) {
    final p = _portion[index];
    final dishName = '${p['dishName'] ?? '菜$index'}';
    final grams = (p['portionGrams'] as num?)?.toInt() ?? 300;
    final cal = _dishCalories(p).round();

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: _candidates.isEmpty
                      ? Text(dishName,
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.bold))
                      : DropdownButtonHideUnderline(
                          child: DropdownButton<String>(
                            // 始终以当前菜名为选中值；若当前菜名不在候选列表
                            // （搜索选中/语音改名/语音加菜），也加入可选项保证可显示，
                            // 避免 value=null 导致名字空白（“搜索选中后页面没变化”）。
                            value: dishName.isEmpty ? null : dishName,
                            isExpanded: true,
                            items: <String>{
                              if (dishName.isNotEmpty) dishName,
                              ..._candidates,
                            }
                                .map((c) => DropdownMenuItem(
                                    value: c, child: Text(c)))
                                .toList(),
                            onChanged: (v) {
                              if (v != null) {
                                setState(() => p['dishName'] = v);
                              }
                            },
                          ),
                        ),
                ),
                const SizedBox(width: 8),
                Text('约 $cal 千卡',
                    style: const TextStyle(color: Colors.orange)),
              ],
            ),
            Row(
              children: [
                IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(Icons.search, size: 18),
                    tooltip: '从菜品库选',
                    onPressed: () => _pickFromLibrary(index)),
                IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    tooltip: '修改菜名',
                    onPressed: () => _editDishName(index)),
                GestureDetector(
                  onLongPressStart: (_) => _beginVoice(),
                  onLongPressEnd: (_) => _voiceRename(index),
                  // Tooltip 默认 longPress 触发会与外层长按手势竞争，导致语音不生效；
                  // 改为 tap 触发提示，长按保留给语音。
                  child: const Tooltip(
                      triggerMode: TooltipTriggerMode.tap,
                      message: '按住说菜名',
                      child: Icon(Icons.mic, size: 18)),
                ),
                const Spacer(),
                IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(Icons.close, size: 18),
                    tooltip: '删除这道菜',
                    onPressed: () =>
                        setState(() => _portion.removeAt(index))),
              ],
            ),
            Row(
              children: [
                Text('份量 $grams 克'),
                Expanded(
                  child: Slider(
                    min: 50,
                    max: 1000,
                    divisions: 38,
                    value: grams.clamp(50, 1000).toDouble(),
                    label: '$grams 克',
                    onChanged: (v) =>
                        setState(() => p['portionGrams'] = v.round()),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  // ---------- P1：手动改选强化（菜品库搜索 + 语音说菜名） ----------

  /// 从菜品库搜索选择（替换当前菜名并刷新营养）
  Future<void> _pickFromLibrary(int index) async {
    final api = ApiService();
    final results = <Map<String, dynamic>>[];
    final picked = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('从菜品库选择'),
          content: SizedBox(
            width: 360,
            height: 400,
            child: Column(
              children: [
                TextField(
                  autofocus: true,
                  decoration: const InputDecoration(
                      hintText: '输入菜名搜索（如 鱼香肉丝）', isDense: true),
                  onChanged: (v) async {
                    try {
                      final r = await api.searchDishNutrition(v);
                      if (!ctx.mounted) return;
                      setLocal(() {
                        results
                          ..clear()
                          ..addAll(r.whereType<Map<String, dynamic>>());
                      });
                    } catch (_) {}
                  },
                ),
                const SizedBox(height: 8),
                Expanded(
                  child: results.isEmpty
                      ? const Center(
                          child: Text('输入关键字搜索菜品库',
                              style: TextStyle(color: Colors.grey)))
                      : ListView(
                          children: [
                            for (final d in results)
                              ListTile(
                                dense: true,
                                title: Text('${d['dishName'] ?? ''}'),
                                subtitle: Text(
                                    '${(d['nutrition']?['calories'] ?? '?')}千卡/份 · ${d['servingGrams'] ?? 300}g'),
                                onTap: () =>
                                    Navigator.of(ctx).pop(d),
                              ),
                          ],
                        ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (picked == null || index >= _portion.length) return;
    final name = '${picked['dishName'] ?? ''}'.trim();
    if (name.isEmpty) return;
    _applyLibraryDish(index, picked);
  }

  /// 用菜品库条目替换菜名并刷新营养（搜索选中 / 编辑命中库共用）
  void _applyLibraryDish(int index, Map<String, dynamic> dish) {
    final name = '${dish['dishName'] ?? ''}'.trim();
    if (name.isEmpty || index >= _portion.length) return;
    setState(() {
      final p = _portion[index];
      // 按选中菜品库条目刷新营养（每份 servingGrams 的热量）
      final nut = (dish['nutrition'] is Map)
          ? Map<String, dynamic>.from(dish['nutrition'] as Map)
          : <String, dynamic>{};
      final base = (dish['servingGrams'] as num?)?.toInt() ?? 300;
      p['dishName'] = name;
      p['baseGrams'] = base;
      p['calories'] = (nut['calories'] as num?)?.toDouble() ?? 0;
      p['protein'] = (nut['protein'] as num?)?.toDouble() ?? 0;
      p['fat'] = (nut['fat'] as num?)?.toDouble() ?? 0;
      p['carbs'] = (nut['carbs'] as num?)?.toDouble() ?? 0;
      p['estimated'] = false;
      // 让新菜名进入候选，保证下拉框可显示/可再次换选
      if (!_candidates.contains(name)) _candidates.add(name);
    });
  }

  /// 直接编辑菜名：trim + 限长 + alias 归一 → 命中库用库营养；未命中保留占位（确认时后端 AI 估算并收录候选）
  Future<void> _editDishName(int index) async {
    if (index >= _portion.length) return;
    final current = '${_portion[index]['dishName'] ?? ''}';
    final controller = TextEditingController(text: current);
    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('修改菜名'),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: 30,
          decoration: const InputDecoration(
            hintText: '输入菜名（可自由修改）',
            isDense: true,
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('保存')),
        ],
      ),
    );
    if (saved != true || !mounted) return;
    final name = controller.text.trim();
    if (name.isEmpty) {
      _toast('菜名不能为空');
      return;
    }
    if (name.length > 30) {
      _toast('菜名不能超过 30 字');
      return;
    }
    final api = ApiService();
    Map<String, dynamic> norm;
    try {
      norm = await api.normalizeDishName(name);
    } catch (_) {
      norm = {'name': name, 'matched': null};
    }
    if (!mounted) return;
    final canonical = '${norm['name'] ?? name}'.trim();
    final matched = norm['matched'];
    if (matched is Map) {
      // 命中菜品库 → 用规范名 + 库营养
      _applyLibraryDish(index, Map<String, dynamic>.from(matched));
      _toast('已改为「$canonical」（使用菜品库营养）');
    } else {
      // 未命中库 → 改名（保留当前营养占位，确认时后端 AI 重新估算并收录候选）
      setState(() {
        final p = _portion[index];
        p['dishName'] = canonical;
        p['estimated'] = true;
        if (!_candidates.contains(canonical)) _candidates.add(canonical);
      });
      _toast('已改为「$canonical」（确认打卡时自动估算营养）');
      await _offerSaveCustomDish(index, canonical);
    }
  }

  /// 未命中库时可选「保存到我的常做菜」：AI 估算营养并落库（source=user, is_verified=1）
  Future<void> _offerSaveCustomDish(int index, String name) async {
    if (!mounted || index >= _portion.length) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('保存为我的常做菜？'),
        content: Text(
            '「$name」不在菜品库中。是否用 AI 估算营养并保存？保存后再次选用会自动命中其营养。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('暂不')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('保存')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final api = ApiService();
    try {
      final nut = await api.estimateDishNutrition(name);
      if (!mounted) return;
      const servingGrams = 300;
      await api.createUserDish({
        'dishName': name,
        'servingGrams': servingGrams,
        'nutrition': {
          'calories': nut['calories'] ?? 0,
          'protein': nut['protein'] ?? 0,
          'fat': nut['fat'] ?? 0,
          'carbs': nut['carbs'] ?? 0,
        },
      });
      if (!mounted) return;
      // 用估算营养刷新当前卡片显示
      setState(() {
        final p = _portion[index];
        p['baseGrams'] = servingGrams;
        p['calories'] = (nut['calories'] as num?)?.toDouble() ?? 0;
        p['protein'] = (nut['protein'] as num?)?.toDouble() ?? 0;
        p['fat'] = (nut['fat'] as num?)?.toDouble() ?? 0;
        p['carbs'] = (nut['carbs'] as num?)?.toDouble() ?? 0;
        p['estimated'] = true;
      });
      _toast('已保存「$name」到我的常做菜');
    } catch (e) {
      final msg = friendlyError(e, fallback: '保存失败，请稍后重试');
      _toast(msg.contains('已存在')
          ? '菜品库已有该菜，确认打卡时将自动使用其营养'
          : msg);
    }
  }

  /// 按住说话：开始录音
  Future<void> _beginVoice() async {
    try {
      await _transcriber.begin();
    } catch (e) {
      _toast('语音启动失败：请检查麦克风权限');
    }
  }

  /// 清洗语音识别的菜名：去首尾空白与标点（FunASR 句尾常带「。」）
  static String _cleanVoiceText(String s) {
    var t = s.trim();
    const punct = '。．.！!？?，,、；;：:…～~·•"\'‘’“”()（）<>《》「」『』【】';
    while (t.isNotEmpty && punct.contains(t[t.length - 1])) {
      t = t.substring(0, t.length - 1);
    }
    while (t.isNotEmpty && punct.contains(t[0])) {
      t = t.substring(1);
    }
    return t.trim();
  }

  /// 松开：把识别文本设为该道菜名
  Future<void> _voiceRename(int index) async {
    final text = _cleanVoiceText(await _transcriber.end());
    if (text.isEmpty) {
      _toast('没有识别到语音，请再试一次');
      return;
    }
    setState(() => _portion[index]['dishName'] = text);
    _toast('已改为：$text');
  }

  /// 松开：语音识别文本作为新菜加入
  Future<void> _voiceAddDish() async {
    final text = _cleanVoiceText(await _transcriber.end());
    if (text.isEmpty) {
      _toast('没有识别到语音，请再试一次');
      return;
    }
    setState(() => _portion.add({
          'dishName': text,
          'portionGrams': 300,
          'calories': 0,
          'protein': 0,
          'fat': 0,
          'carbs': 0,
          'baseGrams': 300,
        }));
    _toast('已加入：$text（确认时自动估算营养）');
  }

  Color _calorieColor(String c) {
    final s = c.toLowerCase();
    if (s.contains('red')) return Colors.red;
    if (s.contains('orange')) return Colors.orange;
    return Colors.green;
  }
}
