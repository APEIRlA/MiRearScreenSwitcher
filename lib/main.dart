/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'dart:ui';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'l10n/app_localizations.dart';

void main() {
  // 设置沉浸式状态栏（透明状态栏）
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  runApp(const DisplaySwitcherApp());
}

class DisplaySwitcherApp extends StatelessWidget {
  const DisplaySwitcherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MRSS',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      supportedLocales: const [
        Locale('en', ''),
        Locale('zh', 'CN'),
        Locale('zh', 'TW'),
      ],
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      localeResolutionCallback: (locale, supportedLocales) {
        // Only support en, zh_CN, zh_TW. All others fallback to English.
        if (locale != null) {
          // Check for exact match
          for (var supportedLocale in supportedLocales) {
            if (supportedLocale.languageCode == locale.languageCode &&
                supportedLocale.countryCode == locale.countryCode) {
              return supportedLocale;
            }
          }

          // Check for language-only match
          if (locale.languageCode == 'zh') {
            // For Chinese, check country code
            if (locale.countryCode == 'TW' || locale.countryCode == 'HK') {
              return const Locale('zh', 'TW'); // Traditional Chinese
            } else {
              return const Locale('zh', 'CN'); // Simplified Chinese (default)
            }
          } else if (locale.languageCode == 'en') {
            return const Locale('en', '');
          }
        }

        // Fallback to English for all unsupported languages
        return const Locale('en', '');
      },
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

enum RootStatus { checking, granted, error }

class _HomePageState extends State<HomePage> {
  static const platform = MethodChannel('com.display.switcher/task');

  // Status Enum
  RootStatus _rootStatus = RootStatus.checking;
  bool _rootGranted = false;
  String _customErrorTitle = ''; // For specific error types
  bool _isLoading = false;
  bool _hasError = false; // 是否有错误
  String _errorDetail = ''; // 错误详情

  // V15: 背屏DPI相关
  int _currentRearDpi = 0;
  bool _dpiLoading = true; // DPI加载状态
  final TextEditingController _dpiController = TextEditingController();
  final FocusNode _dpiFocusNode = FocusNode();

  // V2.1: 显示控制相关
  int _currentRotation = 0; // 当前旋转方向 (0=0°, 1=90°, 2=180°, 3=270°)

  // V2.2: 接近传感器开关
  bool _proximitySensorEnabled = true; // 默认打开

  // V2.5: 背屏常亮开关
  bool _keepScreenOnEnabled = true; // 默认打开

  // V3.5: 未投放应用时常亮开关（与背屏常亮互斥）
  bool _alwaysWakeUpEnabled = false; // 默认关闭

  // V3.6: 被投应用保活开关（应用被弹回主屏/被杀时自动恢复）
  bool _keepAliveEnabled = true; // 默认打开

  @override
  void initState() {
    super.initState();
    _checkRoot();
    _loadSettings(); // 加载所有设置
    _loadProximitySensorSetting(); // 加载接近传感器设置

    // 通知权限会在root授权完成后自动请求（见_checkRoot）

    // 延迟获取DPI和旋转
    Future.delayed(const Duration(seconds: 2), () {
      _getCurrentRearDpi();
      _getCurrentRotation();
    });
  }

  @override
  void dispose() {
    _dpiController.dispose();
    _dpiFocusNode.dispose();
    super.dispose();
  }


  // V15: 获取当前背屏DPI
  Future<void> _getCurrentRearDpi() async {
    setState(() {
      _dpiLoading = true;
    });

    // 最多重试5次，每次间隔1秒
    for (int i = 0; i < 5; i++) {
      try {
        final int dpi = await platform.invokeMethod('getCurrentRearDpi');
        setState(() {
          _currentRearDpi = dpi;
          _dpiController.text = dpi.toString();
          _dpiLoading = false;
        });
        print('当前背屏DPI: $dpi');
        return; // 成功就退出
      } catch (e) {
        print('获取背屏DPI失败 (尝试 ${i + 1}/5): $e');
        if (i < 4) {
          await Future.delayed(const Duration(seconds: 1));
        }
      }
    }

    // 所有重试都失败
    setState(() {
      _dpiLoading = false;
      _currentRearDpi = 0;
    });
    print('获取背屏DPI最终失败');
  }

  // V15: 设置背屏DPI
  Future<void> _setRearDpi(int dpi) async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      // 先尝试重新连接TaskService，确保连接正常
      await platform.invokeMethod('ensureRootAccess');

      // 等待连接建立
      await Future.delayed(const Duration(milliseconds: 500));

      await platform.invokeMethod('setRearDpi', {'dpi': dpi});

      // 刷新当前DPI
      await _getCurrentRearDpi();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_dpi_set')} $dpi',
            ),
          ),
        );
      }
    } catch (e) {
      print('设置背屏DPI失败: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_set_failed')} $e. ${AppLocalizations.of(context).translate('toast_ensure_root')}',
            ),
          ),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  // V15: 还原背屏DPI
  Future<void> _resetRearDpi() async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      // 先尝试重新连接TaskService，确保连接正常
      await platform.invokeMethod('ensureRootAccess');

      // 等待连接建立
      await Future.delayed(const Duration(milliseconds: 500));

      await platform.invokeMethod('resetRearDpi');

      // 刷新当前DPI
      await _getCurrentRearDpi();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppLocalizations.of(context).translate('toast_dpi_reset'),
            ),
          ),
        );
      }
    } catch (e) {
      print('还原背屏DPI失败: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_reset_failed')} $e. ${AppLocalizations.of(context).translate('toast_ensure_root')}',
            ),
          ),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _checkRoot() async {
    setState(() {
      _rootStatus = RootStatus.checking;
      _hasError = false;
      _errorDetail = '';
    });

    try {
      // 直接调用Java层检测root授权，增加超时
      final result = await platform
          .invokeMethod('checkRoot')
          .timeout(const Duration(seconds: 3));

      if (!mounted) return;

      setState(() {
        _rootGranted = result == true;
        _hasError = false;
        _errorDetail = '';

        if (_rootGranted) {
          _rootStatus = RootStatus.granted;
        } else {
          _hasError = true;
          _rootStatus = RootStatus.error;
          _customErrorTitle = ''; // Use default "Permission Required"
          _errorDetail = AppLocalizations.of(
            context,
          ).translate('root_permission_denied');
          // 获取详细信息帮助诊断
          _getDetailedStatus();
        }
      });
    } catch (e) {
      if (!mounted) return;

      // 解析异常类型
      String errorType = '';
      String errorMsg = e.toString();

      if (errorMsg.contains('permission') ||
          errorMsg.contains('Permission')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_permission_denied');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_grant_in_root');
      } else if (errorMsg.contains('TimeoutException')) {
        errorType = AppLocalizations.of(
          context,
        ).translate('error_check_timeout');
        _errorDetail = AppLocalizations.of(
          context,
        ).translate('error_root_timeout');
      } else {
        errorType = AppLocalizations.of(context).translate('error_unknown');
        _errorDetail = errorMsg.length > 50
            ? '${errorMsg.substring(0, 50)}...'
            : errorMsg;
      }
      setState(() {
        _rootGranted = false;
        _hasError = true;
        _rootStatus = RootStatus.error;
        _customErrorTitle = errorType;
      });
    }
  }

  Future<void> _getDetailedStatus() async {
    try {
      final info = await platform.invokeMethod('getRootInfo');
      setState(() {
        _errorDetail = info.toString();
      });
    } catch (e) {
      // 获取详细信息失败，保持当前错误信息
    }
  }

  // V2.1: 重启应用
  Future<void> _restartApp() async {
    if (_isLoading) return;

    setState(() => _isLoading = true);

    try {
      // 确保TaskService连接
      await platform.invokeMethod('ensureRootAccess');
      await Future.delayed(const Duration(milliseconds: 500));

      // 检查是否有应用在背屏
      final result = await platform.invokeMethod('returnRearAppAndRestart');

      if (result == true) {
        // 成功返回主屏，退出应用
        SystemNavigator.pop();
      } else {
        // 没有应用在背屏，直接退出
        SystemNavigator.pop();
      }
    } catch (e) {
      // 出错也退出
      SystemNavigator.pop();
    }
  }

  // V2.2: 加载所有设置
  Future<void> _loadSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled =
            prefs.getBool('proximity_sensor_enabled') ?? true;
        _keepScreenOnEnabled = prefs.getBool('keep_screen_on_enabled') ?? true;
        _alwaysWakeUpEnabled =
            prefs.getBool('always_wakeup_enabled') ??
            false; // V3.5: 加载未投放应用时常亮开关状态
        _keepAliveEnabled = prefs.getBool('keep_alive_enabled') ??
            true; // V3.6: 加载被投应用保活开关状态
      });

    } catch (e) {
      print('加载设置失败: $e');
    }
  }
  // V2.2: 加载接近传感器设置
  Future<void> _loadProximitySensorSetting() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled =
            prefs.getBool('proximity_sensor_enabled') ?? true;
      });
    } catch (e) {
      print('加载接近传感器设置失败: $e');
    }
  }


  // V2.2: 切换接近传感器开关
  Future<void> _toggleProximitySensor(bool enabled) async {
    try {
      // 先保存到SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('proximity_sensor_enabled', enabled);

      // 通知Service更新状态
      await platform.invokeMethod('setProximitySensorEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _proximitySensorEnabled = enabled;
      });
      print('接近传感器已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换接近传感器失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _proximitySensorEnabled = !enabled;
      });
    }
  }


  // V2.5: 切换背屏常亮开关
  Future<void> _toggleKeepScreenOn(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('keep_screen_on_enabled', enabled);

      // V3.5: 如果开启，则关闭未投放应用时常亮
      if (enabled && _alwaysWakeUpEnabled) {
        await prefs.setBool('always_wakeup_enabled', false);
        await platform.invokeMethod('setAlwaysWakeUpEnabled', {
          'enabled': false,
        });
      }

      // 通过Intent通知RearScreenKeeperService
      await platform.invokeMethod('setKeepScreenOnEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _keepScreenOnEnabled = enabled;
        if (enabled) _alwaysWakeUpEnabled = false; // V3.5: 互斥关闭
      });
      print('背屏常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换背屏常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _keepScreenOnEnabled = !enabled;
      });
    }
  }

  // V3.5: 切换未投放应用时常亮开关
  Future<void> _toggleAlwaysWakeUp(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('always_wakeup_enabled', enabled);

      // V3.5: 如果开启，则关闭背屏常亮
      if (enabled && _keepScreenOnEnabled) {
        await prefs.setBool('keep_screen_on_enabled', false);
        await platform.invokeMethod('setKeepScreenOnEnabled', {
          'enabled': false,
        });
      }

      // 通过Intent通知AlwaysWakeUpService
      await platform.invokeMethod('setAlwaysWakeUpEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _alwaysWakeUpEnabled = enabled;
        if (enabled) _keepScreenOnEnabled = false; // V3.5: 互斥关闭
      });
      print('未投放应用时常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换未投放应用时常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _alwaysWakeUpEnabled = !enabled;
      });
    }
  }


  // V3.6: 切换被投应用保活开关
  Future<void> _toggleKeepAlive(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('keep_alive_enabled', enabled);

      // 通知RearScreenKeeperService更新保活开关
      await platform.invokeMethod('setKeepAliveEnabled', {
        'enabled': enabled,
      });

      setState(() {
        _keepAliveEnabled = enabled;
      });
      print('被投应用保活已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换被投应用保活失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _keepAliveEnabled = !enabled;
      });
    }
  }

  String _getDisplayStatus(BuildContext context) {
    switch (_rootStatus) {
      case RootStatus.checking:
        return AppLocalizations.of(context).translate('check_root');
      case RootStatus.granted:
        return AppLocalizations.of(context).translate('status_ready');
      case RootStatus.error:
        return _customErrorTitle.isNotEmpty
            ? _customErrorTitle
            : AppLocalizations.of(context).translate('permission_required');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        title: const Text(
          'MRSS',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.restart_alt),
            onPressed: _restartApp,
            tooltip: '重启软件',
          ),
        ],
      ),
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88), // 珊瑚橙
              Color(0xFFFFB5C5), // 粉红
              Color(0xFFE0B5DC), // 紫色
              Color(0xFFA8C5E5), // 蓝色
            ],
          ),
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            physics: const BouncingScrollPhysics(), // 始终允许滑动
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 整合后的状态和权限卡片（毛玻璃效果）
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          children: [
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  _rootGranted
                                      ? Icons.check_circle
                                      : (_hasError
                                            ? Icons.error_outline
                                            : Icons.warning_rounded),
                                  size: 28,
                                  color: _rootGranted
                                      ? Colors.green
                                      : (_hasError
                                            ? Colors.red
                                            : Colors.orange),
                                ),
                                const SizedBox(width: 10),
                                Text(
                                  _getDisplayStatus(context),
                                  style: const TextStyle(
                                    fontSize: 16,
                                    color: Colors.black87,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ],
                            ),
                            if (_hasError && _errorDetail.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              Text(
                                _errorDetail,
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: Colors.black54,
                                  height: 1.3,
                                ),
                                textAlign: TextAlign.center,
                              ),
                            ],
                            if (_hasError) ...[
                              const SizedBox(height: 10),
                              TextButton(
                                onPressed: () async {
                                  // 触发su授权弹窗，然后重新检测
                                  await platform.invokeMethod(
                                      'requestRootPermission');
                                  await Future.delayed(
                                      const Duration(seconds: 2));
                                  _checkRoot();
                                },
                                child: Text(AppLocalizations.of(context)
                                    .translate('grant_permission')),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // V15: 背屏DPI调整卡片
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(
                          cornerRadius: _SquircleRadii.large,
                        ),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.all(20),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('dpi_settings'),
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleMedium
                                          ?.copyWith(
                                            color: Colors.black87,
                                            fontWeight: FontWeight.bold,
                                          ),
                                    ),
                                    if (_dpiLoading) ...[
                                      const SizedBox(width: 12),
                                      const SizedBox(
                                        width: 16,
                                        height: 16,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          valueColor:
                                              AlwaysStoppedAnimation<Color>(
                                                Colors.black54,
                                              ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  _dpiLoading
                                      ? AppLocalizations.of(
                                          context,
                                        ).translate('checking_dpi')
                                      : '${AppLocalizations.of(context).translate('current_dpi').replaceAll('%d', _currentRearDpi.toString())}  ${AppLocalizations.of(context).translate('recommended_range')}',
                                  style: const TextStyle(
                                    color: Colors.black54,
                                    fontSize: 14,
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    Expanded(
                                      child: TextField(
                                        controller: _dpiController,
                                        focusNode: _dpiFocusNode,
                                        enabled: !_dpiLoading && !_isLoading,
                                        keyboardType: TextInputType.number,
                                        style: const TextStyle(
                                          color: Colors.black87,
                                        ),
                                        decoration: InputDecoration(
                                          labelText: AppLocalizations.of(
                                            context,
                                          ).translate('new_dpi'),
                                          labelStyle: const TextStyle(
                                            color: Colors.black54,
                                          ),
                                          hintText: AppLocalizations.of(
                                            context,
                                          ).translate('input_number'),
                                          hintStyle: const TextStyle(
                                            color: Colors.black38,
                                          ),
                                          border: const OutlineInputBorder(
                                            borderRadius: BorderRadius.all(
                                              Radius.circular(
                                                _SquircleRadii.small,
                                              ),
                                            ),
                                            borderSide: BorderSide(
                                              color: Colors.black26,
                                            ),
                                          ),
                                          enabledBorder:
                                              const OutlineInputBorder(
                                                borderRadius: BorderRadius.all(
                                                  Radius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                                ),
                                                borderSide: BorderSide(
                                                  color: Colors.black26,
                                                ),
                                              ),
                                          focusedBorder:
                                              const OutlineInputBorder(
                                                borderRadius: BorderRadius.all(
                                                  Radius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                                ),
                                                borderSide: BorderSide(
                                                  color: Colors.black54,
                                                  width: 2,
                                                ),
                                              ),
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    ClipPath(
                                      clipper: _SquircleClipper(
                                        cornerRadius: _SquircleRadii.small,
                                      ),
                                      child: Container(
                                        decoration: const BoxDecoration(
                                          gradient: LinearGradient(
                                            begin: Alignment.topLeft,
                                            end: Alignment.bottomRight,
                                            colors: [
                                              Color(0xFFFF9D88), // 珊瑚橙
                                              Color(0xFFFFB5C5), // 粉红
                                              Color(0xFFE0B5DC), // 紫色
                                              Color(0xFFA8C5E5), // 蓝色
                                            ],
                                          ),
                                        ),
                                        child: ElevatedButton(
                                          onPressed: (_isLoading || _dpiLoading)
                                              ? null
                                              : () {
                                                  final dpi = int.tryParse(
                                                    _dpiController.text,
                                                  );
                                                  if (dpi != null && dpi > 0) {
                                                    _setRearDpi(dpi);
                                                  } else {
                                                    ScaffoldMessenger.of(
                                                      context,
                                                    ).showSnackBar(
                                                      SnackBar(
                                                        content: Text(
                                                          AppLocalizations.of(
                                                            context,
                                                          ).translate(
                                                            'input_number',
                                                          ), // Reusing input_number or need invalid_input
                                                        ),
                                                      ),
                                                    );
                                                  }
                                                },
                                          style: ElevatedButton.styleFrom(
                                            backgroundColor: Colors.transparent,
                                            foregroundColor: Colors.white,
                                            shadowColor: Colors.transparent,
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 20,
                                              vertical: 16,
                                            ),
                                            shape: RoundedRectangleBorder(
                                              borderRadius:
                                                  BorderRadius.circular(
                                                    _SquircleRadii.small,
                                                  ),
                                            ),
                                          ),
                                          child: Text(
                                            AppLocalizations.of(
                                              context,
                                            ).translate('set_dpi'),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 12),
                                SizedBox(
                                  width: double.infinity,
                                  child: CustomPaint(
                                    painter: _SquircleBorderPainter(
                                      radius: _SquircleRadii.small,
                                      color: Colors.black26,
                                      strokeWidth: 1,
                                    ),
                                    child: ClipPath(
                                      clipper: _SquircleClipper(
                                        cornerRadius: _SquircleRadii.small,
                                      ),
                                      child: Material(
                                        color: Colors.transparent,
                                        child: InkWell(
                                          onTap: (_isLoading || _dpiLoading)
                                              ? null
                                              : _resetRearDpi,
                                          child: Padding(
                                            padding: EdgeInsets.symmetric(
                                              vertical: 12,
                                            ),
                                            child: Row(
                                              mainAxisAlignment:
                                                  MainAxisAlignment.center,
                                              children: [
                                                Icon(
                                                  Icons.restore,
                                                  color: Colors.black87,
                                                  size: 20,
                                                ),
                                                SizedBox(width: 8),
                                                Text(
                                                  AppLocalizations.of(
                                                    context,
                                                  ).translate(
                                                    'restore_default_dpi',
                                                  ),
                                                  style: TextStyle(
                                                    color: Colors.black87,
                                                    fontSize: 14,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),

                                const SizedBox(height: 16),
                                const Divider(color: Colors.black26, height: 1),
                                const SizedBox(height: 16),

                                // V2.1: 旋转控制
                                Row(
                                  children: [
                                    Text(
                                      AppLocalizations.of(
                                        context,
                                      ).translate('rotation_title'),
                                      style: TextStyle(
                                        fontSize: 13,
                                        color: Colors.black87,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                    const Spacer(),
                                    _buildRotationButton('0°', 0),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('90°', 1),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('180°', 2),
                                    const SizedBox(width: 6),
                                    _buildRotationButton('270°', 3),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // V2.2: 背屏遮盖检测卡片（独立）
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(
                          cornerRadius: _SquircleRadii.large,
                        ),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 20,
                              vertical: 16,
                            ),
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            child: Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('rear_cover_detection_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _proximitySensorEnabled,
                                  onChanged: _toggleProximitySensor,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 20),

                // V2.5: 背屏常亮卡片
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 16,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 背屏常亮开关
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('rear_screen_always_on_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _keepScreenOnEnabled,
                                  onChanged: _toggleKeepScreenOn,
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // 未投放应用时常亮开关
                            Row(
                              children: [
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('always_wake_up_title'),
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.black87,
                                  ),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _alwaysWakeUpEnabled,
                                  onChanged: _toggleAlwaysWakeUp,
                                ),
                              ],
                            ),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // V3.6: 被投应用保活开关
                            Row(
                              children: [
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('keep_alive_title'),
                                        style: TextStyle(
                                          fontSize: 16,
                                          fontWeight: FontWeight.bold,
                                          color: Colors.black87,
                                        ),
                                      ),
                                      const SizedBox(height: 4),
                                      Text(
                                        AppLocalizations.of(
                                          context,
                                        ).translate('keep_alive_desc'),
                                        style: TextStyle(
                                          fontSize: 12,
                                          color: Colors.black54,
                                          height: 1.3,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                const SizedBox(width: 12),
                                _GradientToggle(
                                  value: _keepAliveEnabled,
                                  onChanged: _toggleKeepAlive,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),

                // 项目主页 - 跳转 GitHub
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(
                      cornerRadius: _SquircleRadii.large,
                    ),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: () async {
                            // 跳转 GitHub 项目主页
                            try {
                              await platform.invokeMethod('openGithub');
                            } catch (e) {
                              print('打开 GitHub 失败: $e');
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(content: Text('打开失败')),
                                );
                              }
                            }
                          },
                          splashColor: Colors.white.withOpacity(0.3),
                          highlightColor: Colors.white.withOpacity(0.2),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.symmetric(
                              vertical: 12,
                              horizontal: 16,
                            ),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                const Text(
                                  '⭐',
                                  style: TextStyle(fontSize: 20),
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  AppLocalizations.of(
                                    context,
                                  ).translate('github_page'),
                                  style: TextStyle(
                                    color: Colors.black87,
                                    fontSize: 14,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                                const SizedBox(width: 4),
                                Icon(
                                  Icons.open_in_new,
                                  size: 16,
                                  color: Colors.black54,
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),

                const SizedBox(height: 20),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // V2.1: 构建旋转按钮（精确超椭圆，统一12px圆角）
  Widget _buildRotationButton(String label, int rotation) {
    bool isSelected = _currentRotation == rotation;

    return SizedBox(
      width: 50,
      height: 32,
      child: ClipPath(
        clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
        child: Container(
          decoration: BoxDecoration(
            gradient: isSelected
                ? const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      Color(0xFFFF9D88), // 珊瑚橙
                      Color(0xFFFFB5C5), // 粉红
                      Color(0xFFE0B5DC), // 紫色
                      Color(0xFFA8C5E5), // 蓝色
                    ],
                  )
                : null,
            color: isSelected ? null : Colors.white70,
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: (_isLoading || _dpiLoading)
                  ? null
                  : () => _setRotation(rotation),
              child: Center(
                child: Text(
                  label,
                  style: TextStyle(
                    fontSize: 12,
                    color: isSelected ? Colors.white : Colors.black54,
                    fontWeight: isSelected
                        ? FontWeight.w500
                        : FontWeight.normal,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  // V2.1: 获取当前旋转方向
  Future<void> _getCurrentRotation() async {
    try {
      final rotation = await platform.invokeMethod('getDisplayRotation', {
        'displayId': 1,
      });
      if (rotation != null && rotation >= 0) {
        setState(() {
          _currentRotation = rotation;
        });
      }
    } catch (e) {
      print('获取旋转方向失败: $e');
    }
  }

  // V2.1: 设置旋转方向
  Future<void> _setRotation(int rotation) async {
    print('[Flutter] 🔄 开始设置旋转: $rotation (${rotation * 90}°)');

    if (!_rootGranted) {
      print('[Flutter] ❌ Root未授权');
      return;
    }
    if (_isLoading) {
      print('[Flutter] ⚠️ 正在加载中，跳过');
      return;
    }

    setState(() => _isLoading = true);

    try {
      // 确保root可用
      print('[Flutter] 🔗 确保root可用...');
      final connected = await platform.invokeMethod(
        'ensureRootAccess',
      );
      print('[Flutter] 🔗 root状态: $connected');
      await Future.delayed(const Duration(milliseconds: 500));

      print(
        '[Flutter] 📡 调用setDisplayRotation: displayId=1, rotation=$rotation',
      );
      final result = await platform.invokeMethod('setDisplayRotation', {
        'displayId': 1,
        'rotation': rotation,
      });
      print('[Flutter] 📡 setDisplayRotation返回: $result');

      if (result == true) {
        setState(() => _currentRotation = rotation);
        print('[Flutter] ✅ 旋转成功: ${rotation * 90}°');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                '${AppLocalizations.of(context).translate('toast_rotation_set')} ${rotation * 90}°',
              ),
              duration: const Duration(seconds: 1),
            ),
          );
        }
      } else {
        print('[Flutter] ❌ 旋转失败: result=$result');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                AppLocalizations.of(context).translate('toast_rotation_failed'),
              ),
            ),
          );
        }
      }
    } catch (e) {
      print('[Flutter] ❌ 旋转异常: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '${AppLocalizations.of(context).translate('toast_error')} $e',
            ),
          ),
        );
      }
    } finally {
      setState(() => _isLoading = false);
      print('[Flutter] 🏁 旋转操作结束');
    }
  }
}

// 渐变开关，统一四段渐变样式，替代系统绿色Switch
class _GradientToggle extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;
  const _GradientToggle({required this.value, required this.onChanged});

  @override
  State<_GradientToggle> createState() => _GradientToggleState();
}

class _GradientToggleState extends State<_GradientToggle> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => widget.onChanged(!widget.value),
        onHighlightChanged: (h) => setState(() => _pressed = h),
        customBorder: _SquircleShapeBorder(cornerRadius: _SquircleRadii.tiny),
        splashColor: Colors.white.withOpacity(0.2),
        highlightColor: Colors.white.withOpacity(0.1),
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.tiny),
          child: SizedBox(
            width: 52,
            height: 30,
            child: Stack(
              children: [
                // Base background
                Container(color: Colors.white.withOpacity(0.25)),
                // Gradient overlay with fade
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOut,
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // Knob
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 4,
                    vertical: 4,
                  ),
                  child: AnimatedAlign(
                    duration: const Duration(milliseconds: 220),
                    curve: Curves.easeOut,
                    alignment: widget.value
                        ? Alignment.centerRight
                        : Alignment.centerLeft,
                    child: AnimatedScale(
                      duration: const Duration(milliseconds: 120),
                      scale: _pressed ? 0.95 : 1.0,
                      child: Container(
                        width: 22,
                        height: 22,
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(11),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withOpacity(0.15),
                              blurRadius: 3,
                              offset: const Offset(0, 1),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// 应用列表项优化组件（减少重建）
class _AppListItem extends StatelessWidget {
  final String appName;
  final String packageName;
  final Uint8List? iconBytes;
  final bool isSelected;
  final VoidCallback onToggle;

  const _AppListItem({
    required this.appName,
    required this.packageName,
    required this.iconBytes,
    required this.isSelected,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onToggle,
        splashColor: const Color(0x20FFB5C5), // 浅浅的粉红色（四色渐变中间色）
        highlightColor: const Color(0x10E0B5DC), // 浅浅的紫色高光
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          child: Row(
            children: [
              // 图标（全分辨率，不压缩不受损）
              if (iconBytes != null)
                Image.memory(
                  iconBytes!,
                  width: 48,
                  height: 48,
                  fit: BoxFit.contain,
                  gaplessPlayback: true,
                  filterQuality: FilterQuality.high,
                  isAntiAlias: true,
                )
              else
                const Icon(Icons.android, size: 48, color: Colors.white),
              const SizedBox(width: 12),
              // 文本
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      appName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 15),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      packageName,
                      style: const TextStyle(
                        fontSize: 11,
                        color: Colors.white70,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              // 渐变复选框
              _GradientCheckbox(
                value: isSelected,
                onChanged: (_) => onToggle(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// 渐变复选框（替代绿色Checkbox）- 带过渡动画
class _GradientCheckbox extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;

  const _GradientCheckbox({required this.value, required this.onChanged});

  @override
  State<_GradientCheckbox> createState() => _GradientCheckboxState();
}

class _GradientCheckboxState extends State<_GradientCheckbox> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: (_) => setState(() => _pressed = true),
      onTapUp: (_) => setState(() => _pressed = false),
      onTapCancel: () => setState(() => _pressed = false),
      onTap: () => widget.onChanged(!widget.value),
      child: AnimatedScale(
        duration: const Duration(milliseconds: 120),
        scale: _pressed ? 0.9 : 1.0,
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.checkbox),
          child: SizedBox(
            width: 24,
            height: 24,
            child: Stack(
              children: [
                // 底层半透明背景
                Container(color: Colors.white.withOpacity(0.25)),
                // 渐变层（淡入淡出）
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // 边框（渐隐）- 使用CustomPaint绘制超椭圆边框
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 0.0 : 1.0,
                  child: CustomPaint(
                    painter: _SquircleBorderPainter(
                      radius: _SquircleRadii.checkbox,
                      color: Colors.white.withOpacity(0.4),
                      strokeWidth: 2,
                    ),
                  ),
                ),
                // 对勾（缩放弹出）
                Center(
                  child: AnimatedScale(
                    duration: const Duration(milliseconds: 200),
                    curve: Curves.easeOutBack,
                    scale: widget.value ? 1.0 : 0.0,
                    child: const Icon(
                      Icons.check,
                      size: 18,
                      color: Colors.white,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 超椭圆圆角半径
/// 基于屏幕物理圆角半径16.4mm，超椭圆指数n=2.84
/// 使用固定值确保视觉一致性（基于标准DPI 420计算）
class _SquircleRadii {
  // 16.4mm @ 420dpi ≈ 27dp，实际屏幕略大，取32dp
  static const double large = 32.0; // 大卡片圆角
  static const double small = 12.0; // 小组件圆角 (large * 0.375)
  static const double tiny = 16.0; // 开关圆角
  static const double checkbox = 6.0; // 复选框圆角
}

/// 精确的超椭圆（Squircle）形状边框 - 用于InkWell水波纹
/// 使用2.84指数实现与屏幕圆角一致的平滑曲线
class _SquircleShapeBorder extends ShapeBorder {
  final double cornerRadius;
  static const double n = 2.84; // 超椭圆指数

  const _SquircleShapeBorder({required this.cornerRadius});

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.zero;

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {}

  @override
  ShapeBorder scale(double t) =>
      _SquircleShapeBorder(cornerRadius: cornerRadius * t);

  static Path _createSquirclePath(Size size, double radius) {
    final double width = size.width;
    final double height = size.height;
    final double effectiveRadius = radius.clamp(
      0.0,
      math.min(width, height) / 2,
    );

    final path = Path();

    // 顶部左侧圆角
    path.moveTo(0, effectiveRadius);
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2;
      final x =
          effectiveRadius *
          (1 -
              math.pow(math.cos(angle).abs(), 2 / n) *
                  (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          effectiveRadius *
          (1 -
              math.pow(math.sin(angle).abs(), 2 / n) *
                  (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // 顶边
    path.lineTo(width - effectiveRadius, 0);

    // 顶部右侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2;
      final x =
          width -
          effectiveRadius *
              (1 -
                  math.pow(math.cos(angle).abs(), 2 / n) *
                      (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          effectiveRadius *
          (1 -
              math.pow(math.sin(angle).abs(), 2 / n) *
                  (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // 右边
    path.lineTo(width, height - effectiveRadius);

    // 底部右侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2 + math.pi / 2;
      final x =
          width -
          effectiveRadius *
              (1 -
                  math.pow(math.cos(angle).abs(), 2 / n) *
                      (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          height -
          effectiveRadius *
              (1 -
                  math.pow(math.sin(angle).abs(), 2 / n) *
                      (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    // 底边
    path.lineTo(effectiveRadius, height);

    // 底部左侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2 + math.pi;
      final x =
          effectiveRadius *
          (1 -
              math.pow(math.cos(angle).abs(), 2 / n) *
                  (math.cos(angle) >= 0 ? 1 : -1));
      final y =
          height -
          effectiveRadius *
              (1 -
                  math.pow(math.sin(angle).abs(), 2 / n) *
                      (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }

    path.close();
    return path;
  }
}

/// 精确的超椭圆（Squircle）裁剪器
/// 使用2.84指数实现与屏幕圆角一致的平滑曲线
class _SquircleClipper extends CustomClipper<Path> {
  final double cornerRadius;
  static const double n = 2.84; // 超椭圆指数

  _SquircleClipper({required this.cornerRadius});

  @override
  Path getClip(Size size) {
    return _createSquirclePath(size, cornerRadius);
  }

  Path _createSquirclePath(Size size, double radius) {
    final w = size.width;
    final h = size.height;
    final r = radius;

    final path = Path();

    // 从左上角开始，顺时针绘制
    path.moveTo(0, r);

    // 左上角超椭圆
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);

    // 上边
    path.lineTo(w - r, 0);

    // 右上角超椭圆
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);

    // 右边
    path.lineTo(w, h - r);

    // 右下角超椭圆
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);

    // 下边
    path.lineTo(r, h);

    // 左下角超椭圆
    _drawSquircleArc(path, r, h - r, r, math.pi * 0.5, math.pi);

    path.close();
    return path;
  }

  void _drawSquircleArc(
    Path path,
    double cx,
    double cy,
    double radius,
    double startAngle,
    double endAngle,
  ) {
    const int segments = 30;

    for (int i = 0; i <= segments; i++) {
      final t = i / segments;
      final angle = startAngle + (endAngle - startAngle) * t;

      final cosA = math.cos(angle);
      final sinA = math.sin(angle);

      // 超椭圆公式: r * sgn(t) * |t|^(2/n)
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);

      path.lineTo(x, y);
    }
  }

  double _sgn(double x) => x < 0 ? -1.0 : 1.0;

  @override
  bool shouldReclip(_SquircleClipper oldClipper) =>
      oldClipper.cornerRadius != cornerRadius;
}

/// 精确的超椭圆边框绘制器
/// 用于绘制带边框的超椭圆
class _SquircleBorderPainter extends CustomPainter {
  final double radius;
  final Color color;
  final double strokeWidth;
  static const double n = 2.84; // 超椭圆指数

  _SquircleBorderPainter({
    required this.radius,
    required this.color,
    required this.strokeWidth,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;

    final path = _createSquirclePath(size, radius);
    canvas.drawPath(path, paint);
  }

  Path _createSquirclePath(Size size, double r) {
    final w = size.width;
    final h = size.height;

    final path = Path();
    path.moveTo(0, r);

    // 左上角
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);
    path.lineTo(w - r, 0);

    // 右上角
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);
    path.lineTo(w, h - r);

    // 右下角
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);
    path.lineTo(r, h);

    // 左下角
    _drawSquircleArc(path, r, h - r, r, math.pi * 0.5, math.pi);

    path.close();
    return path;
  }

  void _drawSquircleArc(
    Path path,
    double cx,
    double cy,
    double radius,
    double startAngle,
    double endAngle,
  ) {
    const int segments = 30;
    for (int i = 0; i <= segments; i++) {
      final t = i / segments;
      final angle = startAngle + (endAngle - startAngle) * t;
      final cosA = math.cos(angle);
      final sinA = math.sin(angle);
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);
      path.lineTo(x, y);
    }
  }

  double _sgn(double x) => x < 0 ? -1.0 : 1.0;

  @override
  bool shouldRepaint(_SquircleBorderPainter oldDelegate) {
    return oldDelegate.radius != radius ||
        oldDelegate.color != color ||
        oldDelegate.strokeWidth != strokeWidth;
  }
}

