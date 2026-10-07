import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../providers/auth_provider.dart';
import '../providers/download_provider.dart';
import '../services/device_id.dart';
import 'home_tab_page.dart';
import 'experience_page.dart';
import 'login_page.dart';
import 'xunwei_page.dart';
import 'recipe_list_page.dart';
import 'profile_page.dart';

/// 主壳：首页 / 尝味 / 寻味 / 品味 / 我的（寻味仅注册用户，游客点选弹登录）
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _index = 0;

  late final List<Widget> _pages = [
    HomeTabPage(onSwitchTab: (i) => setState(() => _index = i)),
    const ExperiencePage(),
    const XunweiPage(),
    const RecipeListPage(),
    const ProfilePage(),
  ];

  @override
  void initState() {
    super.initState();
    // 游客设备标识：尽早注入下载提供器（配额/任务归属）
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final id = await DeviceId.get();
      if (mounted) {
        context.read<DownloadProvider>().setDeviceId(id);
      }
    });
  }

  /// 底部导航切换：寻味(index 2)仅注册用户可用，游客弹登录提示不切换
  void _onSelect(int i) {
    if (i == 2 && !context.read<AuthProvider>().isLoggedIn) {
      _promptLogin();
      return;
    }
    setState(() => _index = i);
  }

  Future<void> _promptLogin() async {
    final go = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('「寻味」需要登录后使用'),
        content: const Text('登录后可体验 AI 对话式做菜视频搜索。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('去登录')),
        ],
      ),
    );
    if (go == true && mounted) {
      Navigator.of(context)
          .push(MaterialPageRoute(builder: (_) => const LoginPage()));
    }
  }

  /// 返回键：非首页先切回首页；首页弹「确定退出」确认后才真正退出
  Future<void> _onPop() async {
    if (_index != 0) {
      setState(() => _index = 0);
      return;
    }
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('退出拾味'),
        content: const Text('确定退出拾味吗？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('退出')),
        ],
      ),
    );
    if (ok == true && mounted) {
      SystemNavigator.pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _onPop();
      },
      child: Scaffold(
        body: IndexedStack(index: _index, children: _pages),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: _onSelect,
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.home_outlined),
              selectedIcon: Icon(Icons.home),
              label: '首页',
            ),
            NavigationDestination(
              icon: Icon(Icons.play_circle_outline),
              selectedIcon: Icon(Icons.play_circle),
              label: '尝味',
            ),
            NavigationDestination(
              icon: Icon(Icons.travel_explore_outlined),
              selectedIcon: Icon(Icons.travel_explore),
              label: '寻味',
            ),
            NavigationDestination(
              icon: Icon(Icons.menu_book_outlined),
              selectedIcon: Icon(Icons.menu_book),
              label: '品味',
            ),
            NavigationDestination(
              icon: Icon(Icons.person_outline),
              selectedIcon: Icon(Icons.person),
              label: '我的',
            ),
          ],
        ),
      ),
    );
  }
}
