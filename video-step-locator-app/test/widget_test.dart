// 冒烟测试：验证应用外壳能正常构建（含所有 Provider）。
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';

import 'package:video_step_locator_app/app.dart';
import 'package:video_step_locator_app/providers/auth_provider.dart';
import 'package:video_step_locator_app/providers/video_provider.dart';
import 'package:video_step_locator_app/providers/task_provider.dart';
import 'package:video_step_locator_app/providers/recipe_provider.dart';
import 'package:video_step_locator_app/providers/download_provider.dart';
import 'package:video_step_locator_app/providers/preference_provider.dart';

void main() {
  testWidgets('App shell builds with providers', (WidgetTester tester) async {
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider(create: (_) => AuthProvider()),
          ChangeNotifierProvider(create: (_) => VideoProvider()),
          ChangeNotifierProvider(create: (_) => TaskProvider()),
          ChangeNotifierProvider(create: (_) => RecipeProvider()),
          ChangeNotifierProvider(create: (_) => DownloadProvider()),
          ChangeNotifierProvider(create: (_) => PreferenceProvider()),
        ],
        child: const VideoStepApp(),
      ),
    );
    await tester.pump();

    // 应用标题应渲染，说明外壳正常构建
    expect(find.text('智能菜谱助手'), findsWidgets);
  });
}
