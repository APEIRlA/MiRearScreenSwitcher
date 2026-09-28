# Android 15+ dex 最小补丁工具

当手边只有手机、没有 Flutter 工具链时，用这套脚本对**已构建好的** MRSS APK
做 dex 级最小补丁：只把 `service call activity_task 50` 改成 `51`
（R8 已把重复字符串合并，全项目只有 5 个字符串常量），其余内容一律不动。

## 用法

```bash
# 1) 取 apktool（脚本借用它内置的 smali / baksmali）
curl -sSLo apktool.jar \
  https://repo1.maven.org/maven2/org/apktool/apktool-cli/3.0.3/apktool-cli-3.0.3.jar

# 2) 打补丁 + 签名
./patch_apk.sh 原版.apk 修补版.apk my.keystore 库口令 密钥口令
```

## 流程

1. `baksmali` 反编译 `classes.dex`
2. 把 `"service call activity_task 50 i32 "` → `"service call activity_task 51 i32 "`
3. `smali` 汇编回 dex
4. `build_android15_patch.py` 重建 APK：
   - 其余条目**逐字节复用原始压缩数据**（未压缩的 `.so` / `resources.arsc` 保持未压缩）
   - 仅替换 `classes.dex`
   - 重新做 zip 对齐：`.so` 做 4096 页对齐，其余未压缩条目 4 字节对齐
     （等价 `zipalign -p 4`；不对齐会导致安装被拒）
5. `apksigner` 用 v2 / v3 方案签名

## 注意

- 补丁版签名与原版不同，**安装前必须卸载原版**（应用内设置与 Shizuku 授权会重置）。
- 本脚本只覆盖「transaction code 50 → 51」这一处失效。若上游还有其他改动，
  请以仓库源码为准，不要在补丁版上继续叠加。
- `build_android15_patch.py` 依赖 `extractNativeLibs=false` 的 APK 布局；
  对其它 APK 使用前请先确认 `lib/**/*.so` 是 STORED（未压缩）。
