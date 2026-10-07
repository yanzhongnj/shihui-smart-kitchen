import 'package:flutter/material.dart';

/// 帮助页：功能介绍与使用说明
class HelpPage extends StatelessWidget {
  const HelpPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('帮助')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: const [
          _HelpItem(
            Icons.link,
            '链接下载',
            '在首页点击「链接下载」，粘贴视频链接（抖音 / 快手 / B 站等），'
            '解析后选择清晰度即可下载。下载完成后自动保存到手机本地，'
            '可在「我的视频 - 本地」播放。若无法播放，可对任务点「重新下载（H.264 可播放）」',
          ),
          _HelpItem(
            Icons.restaurant_menu,
            '品味',
            '视频处理完成后，系统自动提取食材、调料与烹饪步骤，'
            '可在底栏「品味」中查看。',
          ),
          _HelpItem(
            Icons.travel_explore,
            '寻味',
            '底栏「寻味」是 AI 做菜视频助手：用文字或语音描述你想做的菜，'
            '即可推荐相关视频（需登录后使用）。',
          ),
          _HelpItem(
            Icons.mic,
            '语音控制',
            '播放视频时用语音说「下一步 / 快进30秒 / 两倍速」等指令，'
            '做菜不再手忙脚乱。',
          ),
          _HelpItem(
            Icons.video_library_outlined,
            '我的视频',
            '上传视频后可在「我的视频」中管理、查看，并进行「步骤提取」处理。',
          ),
          _HelpItem(
            Icons.favorite_outline,
            '收藏',
            '收藏喜欢的视频与菜谱，方便随时查看。',
          ),
          _HelpItem(
            Icons.tune,
            '口味设置',
            '在「我的 - 口味 / 忌口设置」中设置口味偏好，'
            '系统会智能匹配适合你的菜谱。',
          ),
        ],
      ),
    );
  }
}

class _HelpItem extends StatelessWidget {
  final IconData icon;
  final String title;
  final String desc;
  const _HelpItem(this.icon, this.title, this.desc);

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: ListTile(
        leading: Icon(icon, color: Theme.of(context).colorScheme.primary),
        title: Text(title,
            style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(desc, style: const TextStyle(height: 1.5)),
        isThreeLine: true,
      ),
    );
  }
}

/// 关于页：应用信息
class AboutPage extends StatelessWidget {
  const AboutPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('关于')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const SizedBox(height: 16),
          Center(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: Image.asset('assets/logo.png',
                  width: 76, height: 76, fit: BoxFit.cover),
            ),
          ),
          const SizedBox(height: 16),
          const Center(
            child: Text('拾味',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
          ),
          const SizedBox(height: 4),
          const Center(
            child: Text('看视频学做菜 · 语音解放双手',
                style: TextStyle(color: Colors.grey, fontSize: 13)),
          ),
          const SizedBox(height: 28),
          const _AboutRow('版本', '1.0.0'),
          const _AboutRow('平台', 'Android'),
          const _AboutRow('开发者', '拾味项目组'),
          const SizedBox(height: 16),
          const Text(
            '本应用用于智能菜谱视频的查看、链接下载、步骤提取与语音控制，'
            '帮助你在一日三餐中轻松学做菜。',
            style: TextStyle(color: Colors.grey, height: 1.6),
          ),
        ],
      ),
    );
  }
}

class _AboutRow extends StatelessWidget {
  final String label;
  final String value;
  const _AboutRow(this.label, this.value);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          SizedBox(
            width: 90,
            child: Text(label, style: const TextStyle(color: Colors.grey)),
          ),
          Expanded(child: Text(value)),
        ],
      ),
    );
  }
}
