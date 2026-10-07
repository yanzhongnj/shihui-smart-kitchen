import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'app.dart';
import 'providers/auth_provider.dart';
import 'providers/video_provider.dart';
import 'providers/task_provider.dart';
import 'providers/recipe_provider.dart';
import 'providers/download_provider.dart';
import 'providers/preference_provider.dart';
import 'providers/xunwei_provider.dart';
import 'providers/creation_provider.dart';
import 'providers/photo_checkin_provider.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // 不阻塞首帧：登录态在 runApp 之后异步恢复（Splash 覆盖等待期）。
  // 此前这里 await restoreSession()：若后端网络慢/不可达（Dio 超时 30s），
  // 整个 APP 会一直停留在原生白屏，造成"打开很久才显示主界面"。
  final authProvider = AuthProvider();
  unawaited(authProvider.restoreSession());
  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => authProvider),
        ChangeNotifierProvider(create: (_) => VideoProvider()),
        ChangeNotifierProvider(create: (_) => TaskProvider()),
        ChangeNotifierProvider(create: (_) => RecipeProvider()),
        ChangeNotifierProvider(create: (_) => DownloadProvider()),
        ChangeNotifierProvider(create: (_) => PreferenceProvider()),
        ChangeNotifierProvider(create: (_) => XunweiProvider()),
        ChangeNotifierProvider(create: (_) => CreationProvider()),
        ChangeNotifierProvider(create: (_) => PhotoCheckinProvider()),
      ],
      child: const VideoStepApp(),
    ),
  );
}
