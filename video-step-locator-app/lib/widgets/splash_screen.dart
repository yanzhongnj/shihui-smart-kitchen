import 'package:flutter/material.dart';

/// 启动 Splash 屏：品牌 Logo + 文案 + 加载指示。
/// 由 [SplashGate] 控制：展示约 1.6s 后淡出并回调 onDone，由父级从树中移除。
class SplashScreen extends StatelessWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [Color(0xFFF0A06E), Color(0xFFE8876B), Color(0xFFD96E58)],
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
        ),
      ),
      child: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(24),
              child: Image.asset('assets/logo.png',
                  width: 96, height: 96, fit: BoxFit.cover),
            ),
            const SizedBox(height: 22),
            const Text(
              '拾味',
              style: TextStyle(
                  color: Colors.white,
                  fontSize: 26,
                  fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            const Text(
              '会聊天的 AI 厨房，每一顿都是你的拿手菜',
              style: TextStyle(color: Colors.white70, fontSize: 14),
            ),
            const SizedBox(height: 44),
            const SizedBox(
              width: 28,
              height: 28,
              child: CircularProgressIndicator(
                  color: Colors.white, strokeWidth: 2.5),
            ),
          ],
        ),
      ),
    );
  }
}

/// Splash 门控：全屏覆盖在首页之上，约 1.6s 后淡出并移除；
/// 首页在 Splash 下方同步构建、继续加载数据，不受影响。
class SplashGate extends StatefulWidget {
  final VoidCallback onDone;
  const SplashGate({super.key, required this.onDone});

  @override
  State<SplashGate> createState() => _SplashGateState();
}

class _SplashGateState extends State<SplashGate> {
  bool _visible = true;

  @override
  void initState() {
    super.initState();
    Future.delayed(const Duration(milliseconds: 1500), () {
      if (!mounted) return;
      setState(() => _visible = false); // 开始淡出
      Future.delayed(const Duration(milliseconds: 500), () {
        if (mounted) widget.onDone(); // 淡出完成后从树中移除
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedOpacity(
      opacity: _visible ? 1 : 0,
      duration: const Duration(milliseconds: 500),
      curve: Curves.easeOut,
      child: const SplashScreen(),
    );
  }
}
