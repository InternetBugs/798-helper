# 开发说明

面向想改代码或自己编译的人。普通用户看 [README](../README.md) 就够了。

## 项目结构

```
module/
  AndroidManifest.xml          模块清单（无 activity）
  res/values/strings.xml       模块名与描述
  meta/META-INF/xposed/        libxposed 现代 API 的声明文件
    java_init.list             入口类：io.github.internetbugs.helper798.Hook
    scope.list                 作用域：com.cloudora.android
    module.prop                minApiVersion / targetApiVersion / staticScope
  src/io/github/internetbugs/helper798/
    Hook.java                  XposedModule 入口
    AdBlock.java               广告位拦截
    NavBlock.java              底栏「积分」选项卡
    MeBlock.java               「我的」页积分入口
    AntiTouch.java             用水防误触开关与自动续水守护
build.sh                       构建脚本（本地与 CI 共用）
```

模块包名固定为 `io.github.internetbugs.helper798`。LSPosed 按包名登记模块，改包名等于换了一个模块，需要在管理器里重新启用。

## 框架

LSPosed 2.2.1 / libxposed API 102，用的是**现代 API**（`io.github.libxposed.api`），不是老的 `xposedmodule` meta-data。入口类继承 `XposedModule`，hook 走 `hook(Executable).intercept(Hooker)` 拦截器链。

依赖 jar 是 `io.github.libxposed:api:102.0.0` 的 AAR 里抽出来的 `classes.jar`（Maven 上直接下 `.jar` 是 404）：

```bash
curl -fsSL -o /tmp/api.aar \
  https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
unzip -p /tmp/api.aar classes.jar > tools/api-102.jar
```

## 广告位拦截

宿主所有广告都走 `com.ilife.lib.common.ad.util.*`，每种广告位一个 helper。`AdBlock` hook 这些入口并直接返回，不再发起广告请求：

| 广告位 | 入口 |
| --- | --- |
| 开屏 | `SplashAdHelper.loadAndShow(SplashAdListener)` |
| 横幅 | `BannerAdHelper.loadAndShow()` |
| 信息流 | `FeedExpressAdHelper.loadAndShow()` |
| 商城 | `MallAdHelper.loadAndShow()` |
| 激励视频 / 插屏 | `MediationAdHelper.loadAndShow(String)` |
| 全屏 | `FullAdHelper.load(String,String,String)` / `show(Activity)` / `showAd()` |
| 策略门 | `AdManager.isShowAd(List,int)` → `false`，`AdManager.shouldRecommend()` → `false` |

Kotlin 编译器为带默认参数的方法生成 `xxx$default` 合成方法，调用方可能走合成方法，因此每个入口连同 `$default` 一起 hook。

拦截后主动回调 helper 自己的失败回调（`SplashAdListener.onAdLoadFail()`、`MediationAdListener.onAdError()`），让宿主 UI 继续走"广告加载失败"分支，避免卡在加载弹窗。

## 移除积分选项卡

`com.cloudora.android.MainActivity` 的底栏是一个普通 `LinearLayout`（`mLLBottomNav`），每个选项卡是一个固定 300px 宽的子 `LinearLayout`：`mLlHome` / `mLlScore` / `mLlService` / `mLlMe`。

`NavBlock` 在 `onCreate` 之后删掉 `mLlScore`，再给剩下的三个设 `width=0, weight=1`，底栏变成 首页 / 服务 / 我的 三等分。id 用 `getIdentifier(name, "id", pkg)` 按名字解析，应用更新后资源号变了也不会静默失效。

只删视图、不动 ViewPager 的索引，所以"服务""我的"仍然映射到原来的第 2、3 页。底栏在 `onCreate` 返回后才由 fragment 事务填充，因此用 200ms 间隔重试直到找到为止。

## 移除「我的」页的积分入口

「我的」页顶部那排图标（钱包 / 卡包 / 卡券 / 积分）在不同登录状态下装在不同的容器里：有时是 `RecyclerView`（`mRvCommonFun`），有时是普通 `LinearLayout`，而且积分那一格的标题 `TextView` 连 id 都没有。所以 `MeBlock` 不认容器 id、也不认 adapter 位置，而是遍历视图树按标题文本 `积分` 找格子。

定位格子的规则：从标题向上爬，直到某个祖先的父容器有 3 个以上子视图为止——那个祖先就是格子。这条规则对「我的」图标行（2 子视图的格子装在 4 子视图的行里）和底栏（同形状）都成立。再加两条约束防止误伤：格子最多 4 个子视图，且必须含 `ImageView`，所以别处一个孤零零的「积分」标题不会被当成格子。

触发时机：`MainActivity` 的 `onCreate`/`onResume`，以及 `ViewPager2.setCurrentItem`（切选项卡）。每次触发后以 200ms 间隔扫 15 次，覆盖 fragment 事务晚于回调完成布局的情况。删掉格子后如果父容器是 `LinearLayout`，剩下的格子重新设 `width=0, weight=1` 均分。

`RecyclerView` / `ViewPager2` 不在 `android.jar` 里，模块不引 androidx，全部按类名 `Class.forName` 后走 `View` / `ViewGroup` 接口操作。

## 用水防误触

`AntiTouch` 在水控设备详情页（`DeviceCommonDetailActivity` 的 `mDeviceDetailUse` 顶部）与运行状态页（`StateCommonActivity` 的提示文案下方）各注入一个同步开关，默认关闭，状态持久化在宿主 `SharedPreferences`（`n798_helper_prefs` / `anti_touch_enabled`），仅对水控类 `ntype`（5/6/8/9/95/130）生效，排除洗衣机、吹风机等定次设备。

运行时状态机基于以下 hook 点：

1. **延长轮询与息屏保活**：宿主默认在 `DevStateHelper.B(20, 6000, onEach, null)` 中仅轮询 20 次（约 2 分钟）。开启防误触后，hook `DevStateHelper.B` 与 `DevStateHelper.s`，将其升级为 `7200 × 2000ms` 持续轮询并配看门狗；同时复用宿主主进程已声明的 `dataSync` 服务（`FileDownloadService$SharedMainProcessService` 等）启动前台服务常驻通知，叠加 `PARTIAL_WAKE_LOCK`、`WifiLock` 与不抢焦点的全零静态 `AudioTrack` 静音循环（应对 HyperOS `GreezeManager` 在默认省电策略下对第三方 `dataSync` 前台服务依然息屏冻结的问题，触发 `active audio` 豁免），手动关水或退出页面时全部释放。
2. **拦截机器端停水并续开**：当 `DevStateHelper.l(DeviceDetailData)` 将按钮状态从 `SETTLE_IMMEDIATELY` 变为 `USE_IMMEDIATELY` 且未触发手机端手动关水时，拦截 `DevStateHelper.n`、`DevStateHelper.o` 与 `EventBus.e(CloseAdPageEvent)` 阻止退出页面，并在 `DeviceCommonDetailActivity.D0` 抑制重复拉起 `StateCommonActivity`，同时通过 `DevControlHelper.t(false)` 重新下发开水指令（含冷却、退避重试及余额不足/异常终止保护）。
3. **手机端手动关水**：hook `StateCommonActivity.onClick(mClUser)`、`DevControlHelper.i/w` 与 `DeviceViewModel.deviceEnd`，立即解除本次守护并释放 WakeLock；若刚好与在途续水并发，在 `DevControlHelper.s` 回调中补发一次 `DevControlHelper.i(false)` 确保可靠关水。

## 构建

```bash
bash build.sh
```

产物 `build/798-helper.apk`。

环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ANDROID_HOME` | `tools/android-sdk` | SDK 根目录 |
| `BUILD_TOOLS_VERSION` | `35.0.0` | build-tools 版本 |
| `API_JAR` | `tools/api-102.jar` | libxposed api |
| `VERSION_NAME` / `VERSION_CODE` | 取自清单 | 覆盖版本号 |
| `KEYSTORE` / `KS_PASS` / `KEY_ALIAS` / `KEY_PASS` | 无 | 不设则用临时 debug key 签名 |

本地需要 JDK（`javac` 在 PATH 里）和 Python 3。SDK 的 build-tools / platform 需自备：

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

Windows 上 `bash build.sh` 可能被 WSL 接管而不是 Git Bash，那就改用 Git Bash 执行，或逐条跑脚本里的命令。

## 发版

推一个 `v*` 标签即可，GitHub Actions 会自动编译并附到 Release 上：

```bash
git tag v1.4.0
git push origin v1.4.0
```

版本号规则：`vMAJOR.MINOR.PATCH` → `versionCode = MAJOR*10000 + MINOR*100 + PATCH`。手动触发 workflow 时版本号取 `0.0.<run_number>`，只上传 artifact 不发 Release。

签名密钥存在仓库 secrets 里（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）。**换签名密钥后老版本无法覆盖安装**，必须先卸载。

## 安装到设备

```bash
adb push build/798-helper.apk /data/local/tmp/798-helper.apk
adb shell pm install -t -r -d /data/local/tmp/798-helper.apk
```

HyperOS 上 `adb install` 会报 `INSTALL_FAILED_USER_RESTRICTED`；`pm install` 必须带 `-d`（降级）才能过 MIUI 的校验。

## 逆向记录

目标 APK 是加固的（`com.antsafe.sec.wrapper`，VER 7.6.4_1025）：`classes.dex` 头部只有 30 个类、843 个字符串，`file_size` 却是 69,945,504 字节，真实代码在运行时解密。静态反编译只拿到 31 个壳类。

三个关键结论：

1. **加固壳自己解析类，不走 `ClassLoader.loadClass/findClass`。** hook 这两个方法只能看到普通 dex 里的类（`com.ltmb.ltsdk.*`），看不到 `com.ilife.*`。
2. **枚举运行时类要直接走 `BaseDexClassLoader`**：`pathList` → `dexElements[]` → `Element.dexFile` → `DexFile.entries()`，一次拿到 10 个 dex、146,811 个类名。再对目标包 `Class.forName(name, false, cl)` 反射出方法签名，就有了上表的 hook 点。
3. **加固类在 `onPackageLoaded` 时还不存在**，`Class.forName` 抛 `ClassNotFoundException`。模块用 100ms 轮询等 `AdManager` / `SplashAdHelper` 可解析（实测 300ms 内）再装 hook。

另外：读 `/proc/self/maps` + `/proc/self/mem` 会被壳的完整性校验杀掉（进程 `exited cleanly (19)`，约 8 秒后），所以最终版本完全不碰 `/proc`。

宿主内置的广告插件在 APK 的 `assets/assets/` 下：`ltmbzl.jar`（`com.ltmb.ltsdk.core.zl`）、`wm7.19.27`（`com.wangmai.ad`）、`lywm7.19.28`。
