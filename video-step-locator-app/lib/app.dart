import 'package:flutter/material.dart';
import 'pages/home_page.dart';
import 'widgets/splash_screen.dart';

class VideoStepApp extends StatefulWidget {
  const VideoStepApp({super.key});

  @override
  State<VideoStepApp> createState() => _VideoStepAppState();
}

class _VideoStepAppState extends State<VideoStepApp> {
  bool _splashDone = false;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner:false,//去掉debug图标
      title: '拾味',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFE74C3C),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
        appBarTheme: const AppBarTheme(
          centerTitle: true,
          elevation: 0,
        ),
      ),
      // 游客可直接浏览菜谱/链接下载；登录入口在“我的”。
      // 启动 Splash：首页在其下方同步构建并继续加载数据，Splash 到时淡出移除。
      home: Stack(
        children: [
          const HomePage(),
          if (!_splashDone)
            SplashGate(onDone: () => setState(() => _splashDone = true)),
        ],
      ),
    );
  }
}
