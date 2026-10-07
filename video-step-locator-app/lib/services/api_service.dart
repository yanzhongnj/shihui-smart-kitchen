import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class ApiService {
  /// 后端 API 地址（模拟器默认 10.0.2.2 指向宿主机）。
  /// 真机联调时用 `--dart-define=API_BASE=http://<电脑局域网IP>:8080/api` 覆盖。
  static const String baseUrl = String.fromEnvironment(
    'API_BASE',
    defaultValue: 'http://10.0.2.2:8080/api',
  );
  static const FlutterSecureStorage _storage = FlutterSecureStorage();

  /// 真机语音 WebSocket 地址（由 baseUrl 派生：http→ws，/api→/ws/voice）
  static String voiceWsUrl(String sessionId, String token) {
    final wsBase = baseUrl
        .replaceFirst('http://', 'ws://')
        .replaceFirst('https://', 'wss://')
        .replaceFirst('/api', '');
    return '$wsBase/ws/voice/$sessionId?token=$token';
  }

  /// 纯转写 WebSocket 地址（语音输入文字内容：创作/寻味按住说话；与视频语音控制 /ws/voice 隔离）
  static String transcribeWsUrl(String sessionId, String token) {
    final wsBase = baseUrl
        .replaceFirst('http://', 'ws://')
        .replaceFirst('https://', 'wss://')
        .replaceFirst('/api', '');
    return '$wsBase/ws/transcribe/$sessionId?token=$token';
  }

  late final Dio _dio;

  ApiService() {
    _dio = Dio(BaseOptions(
      baseUrl: baseUrl,
      connectTimeout: const Duration(seconds: 30),
      receiveTimeout: const Duration(seconds: 30),
      headers: {'Content-Type': 'application/json'},
    ));

    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) async {
        final token = await _storage.read(key: 'jwt_token');
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
      onError: (error, handler) {
        if (error.response?.statusCode == 401) {
          _storage.delete(key: 'jwt_token');
        }
        handler.next(error);
      },
    ));
  }

  // Auth
  Future<Map<String, dynamic>> login(String username, String password) async {
    final res = await _dio.post('/auth/login', data: {
      'username': username,
      'password': password,
    });
    final token = res.data['data']['token'] as String;
    await _storage.write(key: 'jwt_token', value: token);
    return res.data['data'];
  }

  Future<Map<String, dynamic>> register(
      String username, String email, String password) async {
    final res = await _dio.post('/auth/register', data: {
      'username': username,
      'email': email,
      'password': password,
    });
    final data = res.data['data'] as Map<String, dynamic>;
    await _storage.write(key: 'jwt_token', value: data['token'] as String);
    return data;
  }

  // Videos
  Future<List<dynamic>> getVideos({int page = 0, int size = 20, String? deviceId}) async {
    final res = await _dio.get('/videos', queryParameters: {
      'page': page,
      'size': size,
    }, options: Options(headers: {'X-Device-Id': deviceId ?? ''}));
    final data = res.data['data'];
    // 兼容分页返回 {items,total}（§8.18 起）与旧纯 List 返回
    if (data is Map && data['items'] is List) {
      return data['items'] as List;
    }
    return data is List ? data : [];
  }

  Future<Map<String, dynamic>> getVideoDetail(int id) async {
    final res = await _dio.get('/videos/$id');
    return res.data['data'];
  }

  Future<void> deleteVideo(int id) async {
    await _dio.delete('/videos/$id');
  }

  Future<Map<String, dynamic>> updateVideo(int id, String title) async {
    final res = await _dio.put('/videos/$id', data: {'title': title});
    return res.data['data'];
  }

  // Steps
  Future<List<dynamic>> getSteps(int videoId) async {
    final res = await _dio.get('/videos/$videoId/steps');
    return res.data['data'];
  }

  Future<Map<String, dynamic>> toggleLoop(int stepId) async {
    final res = await _dio.post('/steps/$stepId/loop');
    return res.data['data'];
  }

  // Tasks
  Future<List<dynamic>> getTasks() async {
    final res = await _dio.get('/tasks');
    final data = res.data['data'];
    // 兼容分页返回 {items,total}（§8.18 起）与旧纯 List 返回
    if (data is Map && data['items'] is List) {
      return data['items'] as List;
    }
    return data is List ? data : [];
  }

  // 任务管理（管理员或视频属主）
  Future<void> startTask(int id) async => _dio.post('/tasks/$id/start');
  Future<void> pauseTask(int id) async => _dio.post('/tasks/$id/pause');
  Future<void> retryTask(int id) async => _dio.post('/tasks/$id/retry');
  Future<void> cancelTask(int id) async => _dio.post('/tasks/$id/cancel');
  Future<void> deleteTask(int id) async => _dio.delete('/tasks/$id');

  // Upload
  Future<String> initUpload(String filename, int fileSize, String md5, int totalChunks) async {
    final res = await _dio.post('/videos/upload/init', data: {
      'filename': filename,
      'fileSize': fileSize,
      'md5': md5,
      'totalChunks': totalChunks,
    });
    return res.data['data']['uploadId'];
  }

  Future<void> uploadChunk(String uploadId, int chunkIndex, List<int> bytes) async {
    final formData = FormData.fromMap({
      'uploadId': uploadId,
      'chunkIndex': chunkIndex,
      'file': MultipartFile.fromBytes(bytes, filename: 'chunk_$chunkIndex'),
    });
    await _dio.post('/videos/upload/chunk', data: formData);
  }

  Future<int> mergeChunks(String uploadId) async {
    final res = await _dio.post('/videos/upload/merge', data: {
      'uploadId': uploadId,
    });
    return res.data['data']['videoId'];
  }

  // Voice
  Future<Map<String, dynamic>> sendVoiceCommand(String text) async {
    final res = await _dio.post('/voice/command', data: {'text': text});
    return res.data['data'];
  }

  // ============ 菜谱助手 ============

  // 菜谱（游客可只读；支持来源/派生类型/最低评分筛选）
  Future<List<dynamic>> getRecipes(
      {String? cuisine, String? taste, String? source, String? type, double? minScore}) async {
    final res = await _dio.get('/recipes', queryParameters: {
      if (cuisine != null && cuisine.isNotEmpty) 'cuisine': cuisine,
      if (taste != null && taste.isNotEmpty) 'taste': taste,
      if (source != null && source.isNotEmpty) 'source': source,
      if (type != null && type.isNotEmpty) 'type': type,
      if (minScore != null) 'minScore': minScore,
    });
    return res.data['data'] ?? [];
  }

  Future<Map<String, dynamic>> getRecipe(int id) async {
    final res = await _dio.get('/recipes/$id');
    return res.data['data'];
  }

  Future<Map<String, dynamic>> getRecipeCompatibility(int id) async {
    final res = await _dio.get('/recipes/$id/compatibility');
    return res.data['data'];
  }

  /// 分量换算 + 营养；mode=people(按人数) | mold(按模具面积，shape=round/square/rect，size1/size2 尺寸)
  Future<Map<String, dynamic>> getRecipeScale(
    int id,
    int servings, {
    String? mode,
    String? shape,
    double? size1,
    double? size2,
  }) async {
    final res = await _dio.get('/recipes/$id/scale', queryParameters: {
      'servings': servings,
      if (mode != null && mode.isNotEmpty) 'mode': mode,
      if (shape != null && shape.isNotEmpty) 'shape': shape,
      if (size1 != null) 'size1': size1,
      if (size2 != null) 'size2': size2,
    });
    return res.data['data'];
  }

  /// 溯源详情：当前来源 + 历史流水（游客可读可见菜谱）
  Future<Map<String, dynamic>> getRecipeProvenance(int id) async {
    final res = await _dio.get('/recipes/$id/provenance');
    return res.data['data'];
  }

  /// 菜谱设备参数一键下发（需登录；自动找已绑定同类设备）
  Future<Map<String, dynamic>> sendRecipeDeviceParam(int id, int paramId) async {
    final res = await _dio.post('/recipes/$id/device-params/$paramId/send');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// USER 录入菜谱（需登录；industrial=true 走工业型强校验；source=ARTICLE_LINK 可保存图文草稿）
  Future<Map<String, dynamic>> createRecipe(Map<String, dynamic> body) async {
    final res = await _dio.post('/recipes', data: body);
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 修改菜谱（创建者或管理员）
  Future<Map<String, dynamic>> updateRecipe(int id, Map<String, dynamic> body) async {
    final res = await _dio.put('/recipes/$id', data: body);
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 图文解析（ARTICLE_LINK，需登录）：URL → 菜谱草稿（draft 可回填录入表单）
  Future<Map<String, dynamic>> parseArticle(String url) async {
    final res = await _dio.post('/recipes/parse-article', data: {'url': url});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 精确化增强（需登录 + 计费；LLM 精化后重算评分）
  Future<Map<String, dynamic>> upgradeRecipe(int id) async {
    final res = await _dio.post('/recipes/$id/upgrade');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 删除菜谱（创建者或管理员）
  Future<Map<String, dynamic>> deleteRecipe(int id) async {
    final res = await _dio.delete('/recipes/$id');
    return _unwrap<Map<String, dynamic>>(res);
  }

  // ============ 食材反查（P0，需登录） ============

  /// 食材标签云（按频次倒序；keyword 过滤）。需登录（/api/ingredients/** JWT）。
  Future<List<dynamic>> getIngredientTags({int limit = 50, String? keyword}) async {
    final res = await _dio.get('/ingredients/tags', queryParameters: {
      'limit': limit,
      if (keyword != null && keyword.isNotEmpty) 'keyword': keyword,
    });
    final data = res.data['data'];
    return (data is Map && data['items'] is List) ? data['items'] : <dynamic>[];
  }

  /// 食材反查：items=食材名列表；mode=all|missing-one（缺省自动降级）；sort=like|calorie|difficulty；
  /// maxTime=时长上限(分钟)；difficulty=simple|medium|hard（按 estimatedTime 分桶 ≤15/≤45/>45）。
  /// 返回 {items, mode, autoDowngraded, total, results:[{..., matchedIngredients, calorieLevel}], emptyGuide}
  Future<Map<String, dynamic>> reverseIngredients(
      List<String> items,
      {String? mode,
      String? sort,
      int? maxTime,
      String? difficulty}) async {
    final res = await _dio.get('/ingredients/reverse', queryParameters: {
      'items': items.join(','),
      if (mode != null && mode.isNotEmpty) 'mode': mode,
      if (sort != null && sort.isNotEmpty) 'sort': sort,
      if (maxTime != null) 'maxTime': maxTime,
      if (difficulty != null && difficulty.isNotEmpty) 'difficulty': difficulty,
    });
    return _unwrap<Map<String, dynamic>>(res);
  }

  // ============ 游客体验视频（公开，无需登录） ============

  // 体验视频列表
  Future<List<dynamic>> experienceVideos() async {
    final res = await _dio.get('/experience/videos');
    return res.data['data'] ?? [];
  }

  // 体验视频详情（含步骤）
  Future<Map<String, dynamic>> experienceVideoDetail(int id) async {
    final res = await _dio.get('/experience/videos/$id');
    return res.data['data'];
  }

  // 视频流播放地址（支持 query token 供 video_player 使用；游客可播公开视频）
  String videoStreamUrl(int videoId, {String? token}) {
    final q = Uri(queryParameters: {
      if (token != null && token.isNotEmpty) 'token': token,
    });
    return '$baseUrl/videos/$videoId/stream$q';
  }

  // 读取本地 JWT（供 video_player 等非 dio 场景附加到 URL）
  Future<String?> getToken() => _storage.read(key: 'jwt_token');

  // 视频链接解析（游客带 deviceId）
  // 注意：后端对无法解析的链接返回 HTTP 200 + code!=200 + data=null，
  // 必须用 _unwrap 校验业务码并抛出带后端 message 的异常，否则返回 null 会触发
  // 「type 'Null' is not a subtype of type 'FutureOr<Map<String, dynamic>>'」类型错误。
  Future<Map<String, dynamic>> parseUrl(String url, {String? deviceId}) async {
    final res = await _dio.post('/download/parse',
        data: {'url': url},
        options: Options(headers: {'X-Device-Id': deviceId ?? ''}));
    final data = _unwrap<Map<String, dynamic>?>(res);
    if (data == null) {
      throw DioException(
        requestOptions: res.requestOptions,
        response: res,
        type: DioExceptionType.badResponse,
        message: '链接解析失败，未返回有效结果',
      );
    }
    return data;
  }

  // 预览播放地址（后端代理流；注册用户带 token，游客带 deviceId）
  String previewUrl(String mediaUrl, {String? deviceId}) {
    final q = Uri(queryParameters: {
      'url': mediaUrl,
      if (deviceId != null && deviceId.isNotEmpty) 'deviceId': deviceId,
    });
    return '$baseUrl/download/preview$q';
  }

  // 批量创建下载任务（游客带 deviceId）
  // X-Client: app → 后端严格「下载不建云端、显式上传才建」（Web 端仍自动建云端）
  Future<List<dynamic>> startDownload(
    String pageUrl,
    List<Map<String, dynamic>> entries,
    String format, {
    String? deviceId,
  }) async {
    final res = await _dio.post('/download/start',
        data: {'pageUrl': pageUrl, 'entries': entries, 'format': format},
        options: Options(
            headers: {'X-Device-Id': deviceId ?? '', 'X-Client': 'app'}));
    return res.data['data'] ?? [];
  }

  // 下载任务列表（游客带 deviceId）
  Future<List<dynamic>> getDownloadTasks({String? deviceId}) async {
    final res = await _dio.get('/download/tasks',
        options: Options(headers: {'X-Device-Id': deviceId ?? ''}));
    final data = res.data['data'];
    // 后端返回分页 {items,total,page,size}；兼容旧纯 List 返回
    if (data is Map && data['items'] is List) {
      return data['items'] as List;
    }
    return data is List ? data : [];
  }

  // 下载任务取回文件地址（游客带 deviceId）
  String downloadTaskFileUrl(int taskId, {String? deviceId}) {
    final q = Uri(queryParameters: {
      if (deviceId != null && deviceId.isNotEmpty) 'deviceId': deviceId,
    });
    return '$baseUrl/download/tasks/$taskId/file$q';
  }

  // 收藏
  Future<void> addFavorite(int videoId) async => _dio.post('/favorites/$videoId');
  Future<void> removeFavorite(int videoId) async => _dio.delete('/favorites/$videoId');
  Future<List<dynamic>> getFavorites() async {
    final res = await _dio.get('/favorites');
    final data = res.data['data'];
    if (data is Map && data['items'] is List) {
      return data['items'] as List;
    }
    return data is List ? data : [];
  }

  // 是否已收藏当前视频（游客返回 false）
  Future<bool> isFavorite(int videoId) async {
    try {
      final data = await getFavorites();
      return data.any((j) {
        final m = j as Map<String, dynamic>;
        return (m['videoId'] ?? m['id']) == videoId;
      });
    } catch (_) {
      return false;
    }
  }

  // 触发视频处理流水线（生成步骤）
  Future<void> triggerProcess(int videoId) async {
    await _dio.post('/videos/$videoId/process');
  }

  /// 上传秒传检查：该用户是否已有相同 MD5 的视频（有则跳过上传直接复用）
  Future<Map<String, dynamic>?> checkUploadExists(String fileMd5) async {
    final res = await _dio
        .get('/upload/status', queryParameters: {'fileMd5': fileMd5});
    final data = res.data['data'];
    return data is Map<String, dynamic> ? data : null;
  }

  /// 修改视频步骤文字（视频管线步骤；已发布/已共享的内容后端会拒绝并提示先取消发布/共享）
  Future<void> updateVideoStep(int stepId,
      {String? summary, String? originalText}) async {
    await _dio.put('/steps/$stepId',
        data: {
          if (summary != null) 'summary': summary,
          if (originalText != null) 'originalText': originalText,
        });
  }

  // 评价
  Future<void> addReview(int videoId, int rating, String comment, {String? tasteLabel}) async {
    await _dio.post('/videos/$videoId/review',
        data: {'rating': rating, 'comment': comment, 'tasteLabel': tasteLabel});
  }

  Future<Map<String, dynamic>> getReviews(int videoId) async {
    final res = await _dio.get('/videos/$videoId/reviews');
    return res.data['data'];
  }

  // 口味/忌口
  Future<Map<String, dynamic>> getPreferences() async {
    final res = await _dio.get('/preferences');
    return res.data['data'];
  }

  Future<Map<String, dynamic>> updatePreferences(Map<String, dynamic> prefs) async {
    final res = await _dio.put('/preferences', data: prefs);
    return res.data['data'];
  }

  // 计费摘要
  Future<Map<String, dynamic>> getBillingSummary() async {
    final res = await _dio.get('/billing/summary');
    return res.data['data'];
  }

  // 站外分享
  Future<Map<String, dynamic>> createShareLink(int videoId, {int? expireDays}) async {
    final res = await _dio.post('/share/link/$videoId',
        data: {if (expireDays != null) 'expireDays': expireDays});
    return res.data['data'];
  }

  /// 分享链接列表（按创建用户）
  Future<List<dynamic>> listShareLinks() async {
    final res = await _dio.get('/share/links');
    final data = res.data['data'];
    return data is List ? data : [];
  }

  /// 撤销分享链接（撤销后若不再有任何有效分享，视频解锁可再编辑）
  Future<void> revokeShareLink(String code) async {
    await _dio.delete('/share/link/$code');
  }

  // ============ 菜谱级 收藏/评价/分享（支持无视频菜谱：图文/手动/AI 创作） ============
  // 后端自动处理：有视频菜谱回落到视频级数据（与既有接口行为一致）。

  Future<void> addRecipeFavorite(int recipeId) async =>
      _dio.post('/recipes/$recipeId/favorite', data: {});

  Future<void> removeRecipeFavorite(int recipeId) async =>
      _dio.delete('/recipes/$recipeId/favorite');

  /// 菜谱是否已收藏（游客返回 false）
  Future<bool> isRecipeFavorite(int recipeId) async {
    try {
      final res = await _dio.get('/recipes/$recipeId/favorite');
      final data = res.data['data'];
      return data is Map && data['favorite'] == true;
    } catch (_) {
      return false;
    }
  }

  Future<void> addRecipeReview(int recipeId, int rating, String comment,
      {String? tasteLabel}) async {
    await _dio.post('/recipes/$recipeId/review',
        data: {'rating': rating, 'comment': comment, 'tasteLabel': tasteLabel});
  }

  Future<Map<String, dynamic>> getRecipeReviews(int recipeId) async {
    final res = await _dio.get('/recipes/$recipeId/reviews');
    return res.data['data'];
  }

  Future<Map<String, dynamic>> createRecipeShareLink(int recipeId,
      {int? expireDays}) async {
    final res = await _dio.post('/recipes/$recipeId/share',
        data: {if (expireDays != null) 'expireDays': expireDays});
    return res.data['data'];
  }

  // 自动升级检查
  Future<Map<String, dynamic>> checkAppVersion(String platform, String current) async {
    final res = await _dio.get('/app/version',
        queryParameters: {'platform': platform, 'current': current});
    return res.data['data'];
  }

  // 视频文件播放地址（注册用户带 token，公开视频游客可访问）
  Future<String> videoFileUrl(int videoId) async {
    final token = await _storage.read(key: 'jwt_token');
    final q = Uri(queryParameters: {
      if (token != null && token.isNotEmpty) 'token': token,
    });
    return '$baseUrl/videos/$videoId/file$q';
  }

  /// 获取当前登录用户（启动恢复会话用）；未登录/无效 token 返回 null
  Future<Map<String, dynamic>?> getProfile() async {
    final res = await _dio.get('/auth/profile');
    final data = res.data['data'];
    return data is Map<String, dynamic> ? data : null;
  }

  /// 上传本地视频文件到云端（我的视频-本地 → 上传到云端）；返回 videoId
  Future<int> uploadLocalFile(String filePath, String filename) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath, filename: filename),
    });
    final res = await _dio.post('/videos/upload-local', data: form);
    return (res.data['data'] as num).toInt();
  }

  /// 发布到体验（visibility=public）
  Future<void> publishVideo(int videoId) async =>
      _dio.post('/videos/$videoId/publish');

  /// 从体验下架（visibility=private）
  Future<void> unpublishVideo(int videoId) async =>
      _dio.post('/videos/$videoId/unpublish');

  /// 下载任务文件到本地指定路径（取回 H.264 文件）
  Future<void> downloadToLocal(int taskId, String savePath,
      {String? deviceId}) async {
    final token = await _storage.read(key: 'jwt_token');
    final q = Uri(queryParameters: {
      if (token != null && token.isNotEmpty) 'token': token,
      if (deviceId != null && deviceId.isNotEmpty) 'deviceId': deviceId,
    });
    final url = '$baseUrl/download/tasks/$taskId/file$q';
    await _dio.download(url, savePath,
        options: Options(receiveTimeout: const Duration(minutes: 15)));
  }

  /// 强制重新下载已完成任务（重下为 H.264 可播放版本）
  Future<void> redownloadTask(int taskId, {String? deviceId}) async {
    await _dio.post('/download/tasks/$taskId/redownload',
        options: Options(headers: {'X-Device-Id': deviceId ?? ''}));
  }

  /// 下载任意带 token 的媒体 URL 到本地文件（AI 创作视频/讲解/分段本地缓存用；
  /// 方案乙本地下载可附加防盗链头与字节进度）
  Future<void> downloadUrlToFile(
    String url,
    String savePath, {
    Map<String, String>? headers,
    void Function(int received, int total)? onReceiveProgress,
  }) async {
    await _dio.download(url, savePath,
        options: Options(
            headers: headers,
            receiveTimeout: const Duration(minutes: 15)),
        onReceiveProgress: onReceiveProgress);
  }

  /// 方案乙：将链接解析为直连媒体地址(mp4/m3u8/audio) + 防盗链头，供手机端本地下载
  /// 返回 {url, mediaType: hls|mp4|audio, headers: {Referer,...}}
  Future<Map<String, dynamic>> resolveDirect(String url,
      {String? deviceId}) async {
    final res = await _dio.post('/download/resolve',
        data: {'url': url},
        options: Options(headers: {'X-Device-Id': deviceId ?? ''}));
    final data = _unwrap<Map<String, dynamic>?>(res);
    if (data == null) {
      throw DioException(
        requestOptions: res.requestOptions,
        response: res,
        type: DioExceptionType.badResponse,
        message: '链接解析失败，未返回有效直连地址',
      );
    }
    return data;
  }

  // ============ 寻味（AI 对话式做菜视频搜索） ============

  Future<int> createXunweiSession() async {
    final res = await _dio.post('/xunwei/sessions');
    return res.data['data']['sessionId'] as int;
  }

  Future<List<dynamic>> listXunweiSessions() async {
    final res = await _dio.get('/xunwei/sessions');
    return res.data['data'] ?? [];
  }

  Future<List<dynamic>> getXunweiMessages(int sessionId) async {
    final res = await _dio.get('/xunwei/sessions/$sessionId/messages');
    return res.data['data'] ?? [];
  }

  Future<Map<String, dynamic>> sendXunweiMessage(
      int sessionId, String message) async {
    final res = await _dio.post('/xunwei/sessions/$sessionId/messages',
        data: {'message': message});
    return res.data['data'];
  }

  /// 拍照识菜闭环：上传图片 → 后端识别食材 + 寻味推荐（可选走会话）
  Future<Map<String, dynamic>> xunweiImageRecommend(
      String filePath, String filename,
      {int? sessionId}) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath, filename: filename),
      if (sessionId != null) 'sessionId': sessionId,
    });
    final res = await _dio.post('/xunwei/image/recommend', data: form);
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 成品拍照纠偏：上传实拍成品照 + 参照图（关联视频封面 / 手动上传 referenceFile）→ 双图对比 → 纠偏报告
  /// [sessionId] 传寻味会话 ID 时，纠偏报告会以纯文本写入该会话（A 方案）
  Future<Map<String, dynamic>> xunweiImageCorrect(
      String filePath, String filename,
      {String? referencePath,
      String? referenceFilename,
      int? videoId,
      String? title,
      int? sessionId}) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath, filename: filename),
      if (referencePath != null)
        'referenceFile': await MultipartFile.fromFile(referencePath,
            filename: referenceFilename ?? 'ref.jpg'),
      if (videoId != null) 'videoId': videoId,
      if (title != null) 'title': title,
      if (sessionId != null) 'sessionId': sessionId,
    });
    final res = await _dio.post('/xunwei/image/correct', data: form);
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 业务码校验：后端 HTTP 200 但 body.code != 200（业务错误，如「视觉识别未配置」「暂无目标成品参照图」）
  /// → 抛 DioException 让 friendlyError 透出后端 message，避免被吞成通用兜底文案。
  T _unwrap<T>(Response<dynamic> res) {
    final body = res.data;
    if (body is Map && body['code'] != 200) {
      throw DioException(
        requestOptions: res.requestOptions,
        response: res,
        type: DioExceptionType.badResponse,
        message: (body['message'] as String?) ?? '请求失败，请稍后重试',
      );
    }
    return (body is Map ? body['data'] : null) as T;
  }

  Future<void> renameXunweiSession(int sessionId, String title) async =>
      _dio.put('/xunwei/sessions/$sessionId', data: {'title': title});

  Future<void> deleteXunweiSession(int sessionId) async =>
      _dio.delete('/xunwei/sessions/$sessionId');

  Future<List<dynamic>> getXunweiMemory() async {
    final res = await _dio.get('/xunwei/memory');
    return res.data['data'] ?? [];
  }

  Future<void> clearXunweiMemory() async => _dio.delete('/xunwei/memory');

  Future<void> deleteXunweiMemory(int id) async =>
      _dio.delete('/xunwei/memory/$id');

  Future<void> updateXunweiMemory(int id, String value) async =>
      _dio.put('/xunwei/memory/$id', data: {'memValue': value});

  /// 偏好自进化开关
  Future<Map<String, dynamic>> getXunweiMemorySettings() async {
    final res = await _dio.get('/xunwei/memory/settings');
    return res.data['data'] ?? {};
  }

  Future<void> updateXunweiMemorySettings(bool autoEvolve) async =>
      _dio.put('/xunwei/memory/settings', data: {'autoEvolve': autoEvolve});

  // ============ 酿味（AI 菜品创作，P0） ============

  Future<int> createCreationSession() async {
    final res = await _dio.post('/creation/sessions');
    return (res.data['data']['sessionId'] as num).toInt();
  }

  Future<List<dynamic>> listCreationSessions() async {
    final res = await _dio.get('/creation/sessions');
    return res.data['data'] ?? [];
  }

  Future<List<dynamic>> getCreationMessages(int sessionId) async {
    final res = await _dio.get('/creation/sessions/$sessionId/messages');
    return res.data['data'] ?? [];
  }

  /// 会话内发消息 → {action: ask|confirm|revise_recipe|error, reply, intentJson, creationId?, version?}
  Future<Map<String, dynamic>> sendCreationMessage(
      int sessionId, String message) async {
    final res = await _dio.post('/creation/sessions/$sessionId/messages',
        data: {'message': message});
    return res.data['data'];
  }

  /// 带多源附件发消息（图片 / 系统视频引用 / 外部 URL，P1）
  Future<Map<String, dynamic>> sendCreationMessageWithAttachments(
      int sessionId, String message,
      {String? imagePath,
      String? imageFilename,
      int? videoId,
      String? url,
      String? voiceText}) async {
    final att = <Map<String, dynamic>>[];
    if (imagePath != null) att.add({'type': 'image', 'fileIndex': 0});
    if (videoId != null) att.add({'type': 'video_ref', 'videoId': videoId});
    if (url != null && url.trim().isNotEmpty) {
      att.add({'type': 'url', 'url': url.trim()});
    }
    if (voiceText != null && voiceText.trim().isNotEmpty) {
      att.add({'type': 'audio', 'text': voiceText.trim()});
    }
    final fields = <String, dynamic>{'text': message};
    if (att.isNotEmpty) fields['attachments'] = jsonEncode(att);
    if (imagePath != null) {
      fields['files'] = await MultipartFile.fromFile(imagePath,
          filename: imageFilename ?? 'attach.jpg');
    }
    final form = FormData.fromMap(fields);
    final res = await _dio.post('/creation/sessions/$sessionId/messages',
        data: form);
    return res.data['data'];
  }

  /// 确认创作意图 → 菜谱生成，返回创作详情
  Future<Map<String, dynamic>> confirmCreation(int sessionId) async {
    final res = await _dio.post('/creation/sessions/$sessionId/confirm');
    return _unwrap<Map<String, dynamic>>(res);
  }

  Future<Map<String, dynamic>> getCreationIntent(int sessionId) async {
    final res = await _dio.get('/creation/sessions/$sessionId/intent');
    return res.data['data'] ?? {};
  }

  /// 我的创作列表（分页）
  Future<Map<String, dynamic>> getCreations(
      {int page = 1, int size = 20}) async {
    final res = await _dio.get('/creation/creations',
        queryParameters: {'page': page, 'size': size});
    return _unwrap<Map<String, dynamic>>(res);
  }

  Future<Map<String, dynamic>> getCreationDetail(int id) async {
    final res = await _dio.get('/creation/creations/$id');
    return _unwrap<Map<String, dynamic>>(res);
  }

  Future<void> renameCreation(int id, String title) async =>
      _dio.put('/creation/creations/$id', data: {'title': title});

  /// 编辑单步内容（摘要/详情/计时），仅改图文文本，返回更新后的创作详情
  Future<Map<String, dynamic>> updateCreationStep(int id, int stepIndex,
      {String? summary, String? detail, int? timerMin}) async {
    final res = await _dio.put('/creation/creations/$id/steps/$stepIndex',
        data: {
          if (summary != null) 'summary': summary,
          if (detail != null) 'detail': detail,
          if (timerMin != null) 'timerMin': timerMin,
        });
    return _unwrap<Map<String, dynamic>>(res);
  }

  Future<void> deleteCreation(int id) async =>
      _dio.delete('/creation/creations/$id');

  /// 删除创作会话（后端级联删除关联创作及视频/TTS 文件与消息）
  Future<void> deleteCreationSession(int sessionId) async =>
      _dio.delete('/creation/sessions/$sessionId');

  /// 衍生创作 → {sessionId, status, sourceCreationId}
  Future<Map<String, dynamic>> deriveCreation(int id) async {
    final res = await _dio.post('/creation/creations/$id/derive');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 菜谱不满意迭代 → {action, reply, creationId?, version?, deltaNote?}
  Future<Map<String, dynamic>> reviseCreation(int id, String feedback) async {
    final res = await _dio.post('/creation/creations/$id/revise',
        data: {'feedback': feedback});
    return _unwrap<Map<String, dynamic>>(res);
  }

  Future<List<dynamic>> getCreationVersions(int id) async {
    final res = await _dio.get('/creation/creations/$id/versions');
    return res.data['data'] ?? [];
  }

  Future<Map<String, dynamic>> revertCreation(int id, int version) async {
    final res = await _dio.post('/creation/creations/$id/revert/$version');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 图文跟做（步骤卡 + 计时器数据）
  Future<Map<String, dynamic>> cookCreation(int id) async {
    final res = await _dio.post('/creation/creations/$id/cook');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 触发 AI 视频教程生成（P3：付费引擎需 confirm=true；Mock 直接生成）
  Future<Map<String, dynamic>> generateCreationVideo(int id,
      {bool confirm = false}) async {
    final res = await _dio.post('/creation/creations/$id/video-gen',
        queryParameters: {'confirm': confirm});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 视频单段重创：只重做指定步骤片段，其余已完成段复用后重拼（v0.3）
  Future<Map<String, dynamic>> regenerateCreationVideo(int id, int stepIndex) async {
    final res = await _dio.post('/creation/creations/$id/video-regenerate',
        data: {'scope': 'step', 'stepIndex': stepIndex});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 今日视频生成配额用量
  Future<Map<String, dynamic>> getCreationVideoUsage() async {
    final res = await _dio.get('/creation/video-gen/usage');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 视频生成任务进度
  Future<Map<String, dynamic>> getCreationVideoTasks(int creationId) async {
    final res = await _dio.get('/creation/video-gen/tasks',
        queryParameters: {'creationId': creationId});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// AI 视频播放 URL（带 token，供 VideoPlayerController.networkUrl）
  Future<String> creationVideoUrl(int id) async {
    final token = await _storage.read(key: 'jwt_token');
    return '$baseUrl/creation/creations/$id/video?token=${token ?? ''}';
  }

  /// 某步骤视频分段预览 URL（带 token，供 VideoPlayerController.networkUrl）
  Future<String> creationSegmentUrl(int id, int stepIndex) async {
    final token = await _storage.read(key: 'jwt_token');
    return '$baseUrl/creation/creations/$id/segment/$stepIndex?token=${token ?? ''}';
  }

  // ---- TTS 语音讲解（阿里云 qwen3-tts-instruct-flash）----

  /// 生成/刷新语音讲解 → {creationId, ready, items:[{index,text,ready,durationSec,error?}]}
  Future<Map<String, dynamic>> generateCreationTts(int id) async {
    final res = await _dio.post('/creation/creations/$id/tts/generate');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 语音讲解状态（哪些步骤已就绪）
  Future<Map<String, dynamic>> getCreationTts(int id) async {
    final res = await _dio.get('/creation/creations/$id/tts');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 某步骤讲解音频 URL（带 token，供 audioplayers 播放）
  Future<String> creationTtsUrl(int id, int stepIndex) async {
    final token = await _storage.read(key: 'jwt_token');
    return '$baseUrl/creation/creations/$id/tts/$stepIndex?token=${token ?? ''}';
  }

  // ---- 酱汁设计（P0，纯 LLM） ----

  /// 设计酱汁 → 结构化配方 {name, base, ratio[], steps[], alternatives[], calories}（未落库）
  Future<Map<String, dynamic>> designSauce(int id, {String? demand}) async {
    final res = await _dio.post('/creation/creations/$id/sauce',
        data: {if (demand != null && demand.isNotEmpty) 'demand': demand});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 酱汁迭代（更辣/低脂/无糖等）→ {action, reply, sauce?, deltaNote?}
  Future<Map<String, dynamic>> reviseSauce(int id, String demand) async {
    final res = await _dio.post('/creation/creations/$id/sauce/revise',
        data: {'demand': demand});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 确认酱汁（body.sauce 为当前配方）→ 安全校验通过后落库 {saved, sauce, safety}
  Future<Map<String, dynamic>> confirmSauce(int id, Map<String, dynamic> sauce) async {
    final res = await _dio.post('/creation/creations/$id/sauce/confirm',
        data: {'sauce': sauce});
    return _unwrap<Map<String, dynamic>>(res);
  }

  // ---- 酱汁效果图（P1，文生图 + 成本控制） ----

  /// 触发效果图生成：付费引擎需 confirm=true；返回 started 或 estimate{needConfirm,estimatedCost}
  Future<Map<String, dynamic>> generateEffectImage(int id,
      {bool confirm = false, String? style}) async {
    final res = await _dio.post('/creation/creations/$id/effect-image',
        queryParameters: {
          'confirm': confirm,
          if (style != null && style.isNotEmpty) 'style': style,
        });
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 效果图生成任务进度（任务列表 + effectImage/effectImageStyle）
  Future<Map<String, dynamic>> getEffectImageTasks(int creationId) async {
    final res = await _dio.get('/creation/effect-image/tasks',
        queryParameters: {'creationId': creationId});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 今日效果图配额用量
  Future<Map<String, dynamic>> getEffectImageUsage() async {
    final res = await _dio.get('/creation/effect-image/usage');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 效果图 URL（带 token，供 Image.network 展示）
  /// [version] 为 effectImage 相对路径（如 effect/2026/08/23/7_xxx.png），
  /// 用于改变 URL 打破 3600s 缓存，保证「重新生成」后能加载新图。
  Future<String> creationEffectImageUrl(int id, {String? version}) async {
    final token = await _storage.read(key: 'jwt_token');
    final base = '$baseUrl/creation/creations/$id/effect-image?token=${token ?? ''}';
    if (version == null || version.isEmpty) return base;
    return '$base&v=${Uri.encodeQueryComponent(version)}';
  }

  /// 生成创作分享链接 → {code, shareUrl}
  Future<Map<String, dynamic>> shareCreation(int id, {int? expireDays}) async {
    final res = await _dio.post('/creation/creations/$id/share',
        data: {if (expireDays != null) 'expireDays': expireDays});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 解析分享（匿名只读）
  Future<Map<String, dynamic>> resolveCreationShare(String code) async {
    final res = await _dio.get('/creation/share/$code');
    return _unwrap<Map<String, dynamic>>(res);
  }

  // ---- 升B：视频/菜谱常规化（r82） ----

  /// 导出 AI 视频为普通视频（方案B：讲解语音按步骤时间戳合成进音轨），返回 {videoId, filePath, durationSec}
  Future<Map<String, dynamic>> exportCreationVideo(int id) async {
    final res = await _dio.post('/creation/creations/$id/export-video');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 发布菜谱到常规菜谱库（镜像 recipes，source=AI_CREATE），返回 {libraryRecipeId, sourceType, creationId}
  Future<Map<String, dynamic>> publishCreationToLibrary(int id) async {
    final res = await _dio.post('/creation/creations/$id/publish');
    return _unwrap<Map<String, dynamic>>(res);
  }

  Future<void> submitCreationFeedback(
      int id, String rating, String comment) async {
    await _dio.post('/creation/creations/$id/feedback',
        data: {'rating': rating, 'comment': comment});
  }

  // ============ 炒菜打卡 / 成长（Keep 式习惯记录） ============

  /// 打卡：可关联菜谱(videoId)或手填菜名；成功返回 {checkinId, newBadges, stats, dietRecordId, dietEstimated}
  /// portionFactor：份量系数（0.5/1/1.5），影响跟做自动生成的饮食记录营养
  Future<Map<String, dynamic>> addCheckin({
    int? videoId,
    String? recipeTitle,
    String? mealType,
    String? mood,
    String? note,
    int? calories,
    double? portionFactor,
  }) async {
    final res = await _dio.post('/checkins', data: {
      if (videoId != null) 'videoId': videoId,
      if (recipeTitle != null && recipeTitle.isNotEmpty)
        'recipeTitle': recipeTitle,
      if (mealType != null && mealType.isNotEmpty) 'mealType': mealType,
      if (mood != null && mood.isNotEmpty) 'mood': mood,
      if (note != null && note.isNotEmpty) 'note': note,
      if (calories != null) 'calories': calories,
      if (portionFactor != null) 'portionFactor': portionFactor,
    });
    return res.data['data'];
  }

  Future<void> deleteCheckin(int id) async => _dio.delete('/checkins/$id');

  /// 食物安全规则反馈（D5 反馈闭环：有用/无用/纠错）
  Future<void> safetyFeedback(int ruleId, int useful, {String? comment}) async {
    await _dio.post('/safety/feedback', data: {
      'ruleId': ruleId,
      'useful': useful,
      if (comment != null && comment.isNotEmpty) 'comment': comment,
    });
  }

  /// 按月查询打卡记录；month 形如 2026-08
  Future<List<dynamic>> getCheckins(String month) async {
    final res = await _dio.get('/checkins', queryParameters: {'month': month});
    return res.data['data'] ?? [];
  }

  /// 打卡统计：连续天数/累计/今日热量/热量预算/是否已打卡
  Future<Map<String, dynamic>> getCheckinStats() async {
    final res = await _dio.get('/checkins/stats');
    return res.data['data'] ?? {};
  }

  /// 厨艺等级：level/levelName/totalExp/progress/recentLogs
  Future<Map<String, dynamic>> getLevel() async {
    final res = await _dio.get('/level');
    return res.data['data'] ?? {};
  }

  /// 成就徽章：全部定义 + obtained
  Future<List<dynamic>> getBadges() async {
    final res = await _dio.get('/badges');
    return res.data['data'] ?? [];
  }

  // ============ 营养健康模块（健康档案 / 饮食记录 / 食物库） ============

  /// 健康档案读取 {exists, height, weight, gender, age, ..., targetCalories, ...}
  Future<Map<String, dynamic>> getHealthProfile() async {
    final res = await _dio.get('/health/profile');
    return res.data['data'] ?? {};
  }

  /// 保存健康档案（后端自动重算 BMR/TDEE/营养目标）
  Future<Map<String, dynamic>> updateHealthProfile(
      Map<String, dynamic> body) async {
    final res = await _dio.put('/health/profile', data: body);
    return res.data['data'];
  }

  /// 营养目标卡 {calories, protein, fat, carbs, source}
  Future<Map<String, dynamic>> getHealthTargets() async {
    final res = await _dio.get('/health/profile/targets');
    return res.data['data'] ?? {};
  }

  /// 今日饮食汇总 {date, calories, protein, fat, carbs, targets, ratio, records}
  Future<Map<String, dynamic>> getDietToday() async {
    final res = await _dio.get('/health/diet/today');
    return res.data['data'] ?? {};
  }

  /// 历史饮食 {items, total, page, size}
  Future<Map<String, dynamic>> getDietHistory(
      {String? date, int page = 1, int size = 10}) async {
    final res = await _dio.get('/health/diet', queryParameters: {
      if (date != null && date.isNotEmpty) 'date': date,
      'page': page,
      'size': size,
    });
    return res.data['data'] ?? {'items': [], 'total': 0};
  }

  /// 手动记录一餐；items: [{name, amount, unit}]
  Future<Map<String, dynamic>> addDietRecord({
    String? date,
    required String mealType,
    required List<Map<String, dynamic>> items,
    String? note,
  }) async {
    final res = await _dio.post('/health/diet', data: {
      if (date != null && date.isNotEmpty) 'date': date,
      'mealType': mealType,
      'items': items,
      if (note != null && note.isNotEmpty) 'note': note,
    });
    return res.data['data'];
  }

  Future<void> deleteDietRecord(int id) async =>
      _dio.delete('/health/diet/$id');

  // ============ 拍照打卡（饮食记录 · 拍照识别） ============

  /// 拍照打卡：上传照片 → 多菜识别+份量+营养 → 返回预览
  /// 返回 {photoId, photoPath, mealType, recordDate, status, dishes[], portion[],
  ///        candidates[], calories, protein, fat, carbs, estimated, calorieLevel, targets, ratio}
  Future<Map<String, dynamic>> photoCheckin({
    required String filePath,
    required String filename,
    String? mealType,
    String? recordDate,
  }) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath, filename: filename),
      if (mealType != null && mealType.isNotEmpty) 'mealType': mealType,
      if (recordDate != null && recordDate.isNotEmpty)
        'recordDate': recordDate,
    });
    final res = await _dio.post('/health/checkin/photo', data: form);
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 重取识别预览（断线重试/刷新）
  Future<Map<String, dynamic>> getPhotoPreview(int photoId) async {
    final res = await _dio.get('/health/checkin/photo/$photoId/preview');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 确认打卡（items: [{dishName, portionGrams}]）
  Future<Map<String, dynamic>> confirmPhotoCheckin(
      int photoId, List<Map<String, dynamic>> items) async {
    final res = await _dio.post('/health/checkin/photo/$photoId/confirm',
        data: {'items': items});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 识别不准确 → 手动改选（换候选/食物库）
  Future<Map<String, dynamic>> revisePhotoCheckin(
      int photoId, List<Map<String, dynamic>> items) async {
    final res = await _dio.post('/health/checkin/photo/$photoId/revise',
        data: {'items': items});
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// 照片地址（带 token，供 Image.network 加载）
  Future<String> photoCheckinImageUrl(int photoId) async {
    final token = await _storage.read(key: 'jwt_token');
    final q = Uri(queryParameters: {
      if (token != null && token.isNotEmpty) 'token': token,
    });
    return '$baseUrl/health/checkin/photo/$photoId/image$q';
  }

  /// 菜品级营养库搜索（拍照确认页手动改选/语音补充辅助，P1）
  Future<List<dynamic>> searchDishNutrition(String keyword,
      {int limit = 10}) async {
    final res = await _dio.get('/health/dish-nutrition/search',
        queryParameters: {'keyword': keyword, 'limit': limit});
    return res.data['data'] ?? [];
  }

  /// 菜名归一 + 库匹配（APP 直接编辑菜名辅助）：返回 {input, name, matched}
  Future<Map<String, dynamic>> normalizeDishName(String name) async {
    final res = await _dio.get('/health/dish-nutrition/normalize',
        queryParameters: {'name': name});
    return (res.data['data'] as Map?)?.cast<String, dynamic>() ?? {};
  }

  /// 自定义菜名 AI 营养估算（保存「我的常做菜」前调用）
  Future<Map<String, dynamic>> estimateDishNutrition(String name) async {
    final res = await _dio.post('/health/dish-nutrition/estimate',
        data: {'name': name});
    return (res.data['data'] as Map?)?.cast<String, dynamic>() ?? {};
  }

  /// 保存自定义菜品到「我的常做菜」（source=user，is_verified=1）
  Future<Map<String, dynamic>> createUserDish(Map<String, dynamic> body) async {
    final res = await _dio.post('/health/dish-nutrition', data: body);
    return (res.data['data'] as Map?)?.cast<String, dynamic>() ?? {};
  }

  /// 食物库搜索（keyword/category 可选）
  Future<List<dynamic>> searchFoods({String? keyword, String? category}) async {
    final res = await _dio.get('/health/foods/search', queryParameters: {
      if (keyword != null && keyword.isNotEmpty) 'keyword': keyword,
      if (category != null && category.isNotEmpty) 'category': category,
    });
    return res.data['data'] ?? [];
  }

  /// 食物分类
  Future<List<dynamic>> getFoodCategories() async {
    final res = await _dio.get('/health/foods/categories');
    return res.data['data'] ?? [];
  }

  /// AI 饮食复盘（近 N 日）：{code, hasData, summary, balance, strengths, improvements, suggestion, disclaimer}
  /// 用 _unwrap 透出业务错误（如限流 429「今日 AI 复盘次数已达上限」），避免被吞成空 map 显示「暂无饮食数据」。
  Future<Map<String, dynamic>> getHealthReview() async {
    final res = await _dio.get('/health/review');
    return _unwrap<Map<String, dynamic>>(res);
  }

  /// AI 回填食材入库（幂等），返回 {inserted}
  Future<Map<String, dynamic>> confirmAiFoods(
      List<Map<String, dynamic>> foods) async {
    final res =
        await _dio.post('/health/foods/confirm-ai', data: {'foods': foods});
    return res.data['data'] ?? {};
  }

  /// 记录体重（同日 upsert），返回 {id, date, weight, newBadges}
  Future<Map<String, dynamic>> addWeightLog(double weight, {String? date}) async {
    final res = await _dio.post('/health/weight-logs', data: {
      'weight': weight,
      if (date != null && date.isNotEmpty) 'date': date,
    });
    return res.data['data'] ?? {};
  }

  /// 体重趋势：{days, items:[{date,weight}], count, streak, latestWeight, targetWeight, startWeight, delta, remaining, progress}
  Future<Map<String, dynamic>> getWeightTrend({int days = 30}) async {
    final res = await _dio
        .get('/health/weight-trend', queryParameters: {'days': days});
    return res.data['data'] ?? {};
  }

  // ============ 做菜作品（Keep 式作品记录） ============

  /// 发布作品：多图 + 关联菜谱(videoId) + 菜名/标题/描述/可见性；返回 {workId, newBadges}
  Future<Map<String, dynamic>> createWork({
    int? videoId,
    String? recipeTitle,
    String? title,
    String? description,
    String? status,
    required List<String> imagePaths,
  }) async {
    final form = FormData.fromMap({
      if (videoId != null) 'videoId': videoId,
      if (recipeTitle != null && recipeTitle.isNotEmpty)
        'recipeTitle': recipeTitle,
      if (title != null && title.isNotEmpty) 'title': title,
      if (description != null && description.isNotEmpty)
        'description': description,
      if (status != null && status.isNotEmpty) 'status': status,
    });
    for (final path in imagePaths) {
      form.files.add(MapEntry(
          'files', await MultipartFile.fromFile(path, filename: path.split('/').last)));
    }
    final res = await _dio.post('/works', data: form);
    return res.data['data'];
  }

  Future<List<dynamic>> getMyWorks() async {
    final res = await _dio.get('/works/mine');
    return res.data['data'] ?? [];
  }

  Future<void> deleteWork(int id) async => _dio.delete('/works/$id');

  /// 作品图片地址（带 token 查询参数，供 Image.network 加载）
  Future<String> workImageUrl(int workId, int index) async {
    final token = await _storage.read(key: 'jwt_token');
    final q = Uri(queryParameters: {
      if (token != null && token.isNotEmpty) 'token': token,
    });
    return '$baseUrl/works/$workId/image/$index$q';
  }

  // ============ 月度烹饪报告（Keep 式月度总结） ============

  /// 月度报告：summary/topRecipes/cuisines/moods；month 形如 2026-08
  Future<Map<String, dynamic>> getMonthlyReport(String month) async {
    final res = await _dio.get('/reports/monthly',
        queryParameters: {'month': month});
    return res.data['data'] ?? {};
  }

  // ============ 做饭计划（Keep 式训练计划） ============

  /// 创建每周计划：{title, items:[{dayOffset, videoId?, recipeTitle, mealType}]}
  Future<Map<String, dynamic>> createPlan(
      String title, List<Map<String, dynamic>> items) async {
    final res = await _dio.post('/plans', data: {'title': title, 'items': items});
    return res.data['data'];
  }

  Future<List<dynamic>> getPlans() async {
    final res = await _dio.get('/plans');
    return res.data['data'] ?? [];
  }

  Future<void> deletePlan(int planId) async => _dio.delete('/plans/$planId');

  /// 标记计划条目完成/取消；返回 {itemId, done, newBadges, exec}
  Future<Map<String, dynamic>> markPlanItemDone(int itemId, bool done) async {
    final res = await _dio.post('/plans/items/$itemId/done', data: {'done': done});
    return res.data['data'];
  }

  /// 删除单条计划条目；删空则连带删除计划，返回 {deleted, planDeleted}
  Future<Map<String, dynamic>> deletePlanItem(int itemId) async {
    final res = await _dio.delete('/plans/items/$itemId');
    return res.data['data'] as Map<String, dynamic>;
  }

  /// 调整条目餐次/星期：{dayOffset?, mealType?}
  Future<Map<String, dynamic>> updatePlanItem(int itemId,
      {int? dayOffset, String? mealType}) async {
    final res = await _dio.put('/plans/items/$itemId', data: {
      if (dayOffset != null) 'dayOffset': dayOffset,
      if (mealType != null && mealType.isNotEmpty) 'mealType': mealType,
    });
    return res.data['data'];
  }

  // ============ 做饭计划 P1 厨电调度（线路 A，2026-08-29） ============

  /// 编排并启动厨电时间轴：body {startAt?: "HH:mm"}（可选，默认按餐次推断）
  Future<Map<String, dynamic>> schedulePlanItem(int itemId,
      {String? startAt}) async {
    final res = await _dio.post('/plan/items/$itemId/schedule', data: {
      if (startAt != null && startAt.isNotEmpty) 'startAt': startAt,
    });
    return res.data['data'];
  }

  /// 取消厨电调度
  Future<Map<String, dynamic>> cancelPlanItemSchedule(int itemId) async {
    final res = await _dio.post('/plan/items/$itemId/schedule/cancel');
    return res.data['data'];
  }

  /// 查看厨电调度时间轴与状态
  Future<Map<String, dynamic>> getPlanItemSchedule(int itemId) async {
    final res = await _dio.get('/plan/items/$itemId/schedule');
    return res.data['data'];
  }

  // ============ 做饭计划执行闭环（P0，v0.3） ============

  /// AI 生成菜单（预览）：{generateMode, respectTaboos, respectMedications, pattern, meals, title, week(起始日), days}
  Future<Map<String, dynamic>> generateMenu({
    String generateMode = 'favorites',
    bool respectTaboos = true,
    bool respectMedications = true,
    String pattern = '1meat1veg',
    List<String> meals = const ['dinner'],
    String title = 'AI 周菜单',
    String? startDate,
    int days = 7,
  }) async {
    final res = await _dio.post('/plan/menu/generate', data: {
      'generateMode': generateMode,
      'respectTaboos': respectTaboos,
      'respectMedications': respectMedications,
      'pattern': pattern,
      'meals': meals,
      'title': title,
      if (startDate != null && startDate.isNotEmpty) 'week': startDate,
      'days': days,
    });
    return res.data['data'];
  }

  /// 确认菜单 → 写入做饭计划（selectedRecipeIds 非空时仅写勾选条目）
  Future<Map<String, dynamic>> confirmMenu(int menuId,
      {List<int>? selectedRecipeIds}) async {
    final res = await _dio.post('/plan/menu/$menuId/confirm', data: {
      if (selectedRecipeIds != null && selectedRecipeIds.isNotEmpty)
        'selectedRecipeIds': selectedRecipeIds,
    });
    return res.data['data'];
  }

  /// 放弃菜单预览
  Future<void> discardMenu(int menuId) async {
    await _dio.post('/plan/menu/$menuId/discard');
  }

  /// 查询某周菜单列表
  Future<List<dynamic>> getMenus({String? week}) async {
    final res = await _dio.get('/plan/menu', queryParameters: {
      if (week != null && week.isNotEmpty) 'week': week,
    });
    return res.data['data'] ?? [];
  }

  /// 从计划生成购物清单：{planId, persons, stockIngredients[]}；返回 {listId, items, skippedItems, skippedCount}
  Future<Map<String, dynamic>> generateShoppingList(
    int planId, {
    int persons = 2,
    List<String> stockIngredients = const [],
  }) async {
    final res = await _dio.post('/plan/shopping-list/generate', data: {
      'planId': planId,
      'persons': persons,
      if (stockIngredients.isNotEmpty) 'stockIngredients': stockIngredients,
    });
    return res.data['data'];
  }

  /// 购物清单详情
  Future<Map<String, dynamic>> getShoppingList(int id) async {
    final res = await _dio.get('/plan/shopping-list/$id');
    return res.data['data'];
  }

  /// 手动勾选/改量/补录：整体替换 items
  Future<Map<String, dynamic>> updateShoppingListItems(
      int id, List<Map<String, dynamic>> items) async {
    final res = await _dio.put('/plan/shopping-list/$id/items', data: items);
    return res.data['data'];
  }

  /// 生成导出文本（复制/分享）
  Future<Map<String, dynamic>> exportShoppingList(int id) async {
    final res = await _dio.post('/plan/shopping-list/$id/export');
    return res.data['data'];
  }

  /// 完成前调整份量系数（0.5/1.0/1.5）
  Future<Map<String, dynamic>> setPlanItemPortion(int itemId, double scale) async {
    final res = await _dio.post('/plan/items/$itemId/portion', data: {'scale': scale});
    return res.data['data'];
  }

  /// 线路 B：条目「跟做」视频带出（local=直接播放；external=外部链接去下载；false=跳寻味）
  Future<Map<String, dynamic>> attachPlanItemVideo(int itemId) async {
    final res = await _dio.post('/plan/items/$itemId/attach-video');
    return res.data['data'];
  }
}
