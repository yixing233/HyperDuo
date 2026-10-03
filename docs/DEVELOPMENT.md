# HyperDuo 开发文档

面向二次开发与问题定位。用户向的安装、设置说明见 [README](../README.md)。

## 项目构成

| 项 | 值 |
| --- | --- |
| `namespace` / `applicationId` | `io.github.yixing233.hyperduo` |
| `versionCode` / `versionName` | 源码里 2 / 1.1；发布时由 `release.ps1 -Version` 注入（见「发布流水线」） |
| `minSdk` / `targetSdk` / `compileSdk` | 29 / 36 / 37 |
| Java / Kotlin JVM target | 21（不是选择，见「构建」） |
| 框架接口 | libxposed **API 102**（`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`） |
| 作用域 | `com.android.systemui`（`app/src/main/resources/META-INF/xposed/scope.list`） |
| 入口 | `io.github.yixing233.hyperduo.HyperDuoModule`（`java_init.list`） |

应用侧有两个入口：`.ui.MainActivity` 带 `de.robv.android.xposed.category.MODULE_SETTINGS`
分类（LSPosed 管理器里点击模块进入），`.ui.MainActivityAlias` 是桌面 LAUNCHER 图标。

依赖：`compileOnly io.github.libxposed:api:102.0.0`、`implementation io.github.libxposed:service:102.0.0`、
`androidx.core:core-ktx:1.19.0`、`androidx.activity:activity-compose:1.13.0`、
`org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`（更新器要在主线程外做网络、
回到主线程报状态，Compose 本身没有调度原语）、`kotlinx-serialization-json:1.11.0`（miuix-nav 的
路由要 `@Serializable`）、Miuix **0.9.4**（`miuix-ui-android` / `miuix-preference-android` /
`miuix-icons-android` / `miuix-blur-android` / `miuix-shader-android` / `miuix-nav-android`）。
`miuix-blur` 声明 minSdk 33，本模块用 `<uses-sdk tools:overrideLibrary>` 把它压回 29 ——
它内部每个效果都由 `isRuntimeShaderSupported()` 门控，33 以下是退化成普通绘制而不是崩溃。

## 源码结构

```
io.github.yixing233.hyperduo
├─ HyperDuoModule.java   入口（extends XposedModule，按 META-INF/xposed/java_init.list 实例化）
├─ HyperDuoApp.kt        Application：注册 XposedServiceHelper 监听，持有 service
├─ Prefs.java            键名 / 默认值 / 上下界（两端唯一真源）
├─ TrioSettings.java     跨进程配置快照（值对象，刻意不依赖 Xposed API）
├─ TrioConfig.java       hook 侧：绑定 remote preferences 并热更新快照
├─ TrioHooks.java        全部 hook 安装与容器折叠逻辑
├─ TrioOverlay.java      独立窗口绘制：状态栏窗口只有约 43dp 高并裁掉多余部分，
│                      这里另开一个只有图标那么大、不接收触摸的窗口
├─ TrioState.java        电量 / 信号等级 / 前景色等运行期状态
├─ TrioGeometry.java     120×120 设计空间的几何常量
├─ TrioAppearance.java   外观规则：每个元素该不该画、画在哪（渲染器与预览共用）
├─ TrioRenderer.java     纯 Canvas 描边绘制（状态栏与预览共用）
├─ TrioPreviewView.java  设置界面里的预览 View
├─ Refl.java             反射小工具
└─ ui\
   ├─ MainActivity.kt         ComponentActivity + MiuixTheme
   ├─ SettingsScreen.kt       全部设置项 + 预览卡片 + 颜色弹窗 + 更新卡片
   ├─ SettingsRepository.kt   读写本地 prefs 并写穿到 remote preferences
   └─ UpdateController.kt     检查 / 下载 / 调起安装器（GitHub Releases）
                              三个 URL 常量同处一处：API、RELEASES_PAGE、REPO_HOME
```

## 构建

需要 JDK 21、Android SDK（platform 37 + build-tools 37.0.0）与 Gradle 9.8。本仓库把整套工具链
与依赖都放在 `.tools\`：

```powershell
$T='C:\code\HyperDuo\.tools'
$env:JAVA_HOME="$T\jdk\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME="$T\gradle-home"
$env:ANDROID_HOME="$T\sdk"
& "$T\gradle\gradle-9.8.0\bin\gradle.bat" --project-dir C:\code\HyperDuo :app:assembleDebug --no-daemon --console=plain
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。

**JDK 21 是硬性要求，不是偏好。** Miuix 0.9.4 的全部产物都是 Java 21 字节码（class 文件
major 65），而 `miuix-nav` 的入口 `rememberNavController` / `entry` 是 inline composable：
Kotlin 拒绝把 21 的目标 inline 进 17 的输出，报

```
Cannot inline bytecode built with JVM target 21 into bytecode that is being
built with JVM target 17. Specify proper '-jvm-target' option.
```

而且 AGP 会硬性校验两侧一致（`Inconsistent JVM targets between Java and Kotlin
compile tasks: 17 and 21.`），所以 `compileOptions` 与 `kotlin.compilerOptions.jvmTarget`
必须同时是 21，也就必须有一个真能产出 21 class 的 `javac`——只把 Kotlin 单独升到 21
在 JDK 17 上编不过（`无效的源发行版：21`）。`build.ps1` 的离线管线仍然用
`-source 8 -target 8`，JDK 21 接受这两个值（有 deprecation 警告），只是共用同一个 JDK。

**构建需要联网。** `io.github.libxposed` 已 vendored 到 `.tools\maven`，但 miuix 0.9.4
与 miuix-nav 不在本地 Gradle 缓存里，`--offline` 会解析失败。

要点：

- `settings.gradle.kts` 把 `.tools\maven` 作为**第一个**仓库（承载 `io.github.libxposed:api/service`
  的本地副本），排在 `google()` / `mavenCentral()` 之前，所以 libxposed 部分不依赖网络
  （miuix 部分仍然依赖，见上）。
  `dependencyResolutionManagement` 用的是 `FAIL_ON_PROJECT_REPOS`，因此**不要在模块里写
  `repositories {}`**。
- `android.useAndroidX=true`（Compose 必需）。Compose 编译器是
  `org.jetbrains.kotlin.plugin.compose` 2.3.21，与 Kotlin 2.3.21 同版本。
- `gradle.properties` 中 `android.suppressUnsupportedCompileSdk=37`、
  `org.gradle.configuration-cache=false`、`org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8`。
- 手写的 `build.ps1`（javac/d8/aapt2 离线管线）**已弃用**，保留仅供查阅：它无法编译
  Kotlin/Compose 与 `res/`，这正是迁移到 Gradle 的原因。
- 本仓库**没有 Gradle wrapper**（工具链是 vendored 的），本地用 `.tools\gradle\gradle-9.8.0`。

### 发布构建

```powershell
& "$T\gradle\gradle-9.8.0\bin\gradle.bat" --project-dir C:\code\HyperDuo :app:assembleRelease `
  -PhyperduoVersionName=1.2 -PhyperduoVersionCode=10200 --no-daemon --console=plain
```

`app\build.gradle.kts` 顶部读取 `hyperduoVersionName` / `hyperduoVersionCode` 两个 project
property，缺省时回落到 `1.1` / `2`。**发布构建不能加 `--offline`**：R8 需要一个未 vendored 的
`org.jetbrains.kotlin:compose-group-mapping`，离线会以
`Execution failed for task ':app:produceReleaseComposeMapping'` 失败。

release 开启 `isMinifyEnabled` + `isShrinkResources`，但 `proguard-rules.pro` 里
`-keep class io.github.yixing233.hyperduo.** { *; }` 保证了 hook 侧与更新器都不被剥离 —— 这是必须的，
因为 hook 类由框架按 `java_init.list` 反射实例化，R8 看不到这条引用。

### 签名

`signingConfigs.create("hyperduo")` 使用 `rootProject.file(".tools/debug.keystore")`，
`storePassword` / `keyPassword` 均为 `android`，`keyAlias` 为 `hyperduo`。debug 与 release
共用同一个签名，目的是让就地升级覆盖已安装的模块时能继续保持模块授权。

`.tools/debug.keystore` **是入库的**（`.gitignore` 显式放行），因此发布不需要任何 secret 就能
产出与本地 debug 包、与应用内更新互相覆盖的 APK。

### 发布流水线

发布是**一条本地命令**，没有 CI。`release.ps1` 发完主仓后**自动**调用 `release-module.ps1`，
把同一个 APK、同一份说明发到 LSPosed 模块仓（模块页的下载链接走这里），两个仓库永远同版本：

```powershell
.\release.ps1 -Version 1.0 -NotesFile .\release-notes-1.0.md
.\release.ps1 -Version 1.0 -DryRun             # 只构建，不打 tag、不建 Release、不发模块仓
.\release.ps1 -Version 1.0 -SkipModuleRepo     # 只发主仓，跳过模块仓

# 模块仓那步失败（主仓已发出）时单独补齐；或手动重发/覆盖模块仓的某个版本
.\release-module.ps1 -Version 1.0 -VersionCode 10000 `
    -Apk .\dist\HyperDuo-1.0.apk -NotesFile .\release-notes-1.0.md
```

`release.ps1` 按顺序做六件事，任何一步失败都立刻停下：

1. 校验版本号（`major` / `major.minor` / `major.minor.patch`，缺段按 0 计），算出
   `versionCode = major*10000 + minor*100 + patch`。
2. 不带动 `--offline` 地跑 `:app:assembleRelease`，把版本号用 `-PhyperduoVersionName`
   / `-PhyperduoVersionCode` 注入。
3. 复制成 `dist\HyperDuo-<version>.apk`，再用 `aapt2 dump badging` **回读 APK 里的版本**，
   与预期不符就中止 —— 版本注入写错时这一步会立刻暴露，而不是等用户装上才发现。
4. 打并推送 `v<version>` tag，创建 GitHub Release（`git credential fill` 取凭据，不存明文
   token），最后上传 APK asset。
5. **清掉主仓里其它所有 Release**，只留刚发的这一版（见下）。
6. 调 `release-module.ps1` 发模块仓：同 APK、同说明，`-Force` / `-KeepOldReleases`
   原样传递。模块仓自己校验 badging、上传 asset、同步 `module-README.md`、清理旧
   Release。说明为空时模块仓会拒绝发布 —— LSPosed 规范要求 Release 正文就是
   changelog，所以发版必须带 `-Notes` 或 `-NotesFile`。**这一步失败不会撤回主仓的
   Release**，直接重跑 `release-module.ps1` 补齐即可。

#### 只保留最新版 Release

两个仓库都遵循同一条策略：**Releases 页永远只有刚发布的这一版**。这是有意为之，不是清理
遗留 —— 用户要的是「点进 Releases 就是最新版」，旧版本留在列表里只会让人下错。

- **tag 一律保留**。删的只是 GitHub 上的 Release 记录（标题、说明、asset）；`v1.0`…
  `v1.4` 这些 git tag 原样不动，历史源码快照仍然能 `git checkout v1.3` 检出。
- 两个脚本都在**最后一步**做清理（asset 已上传、发布已经成功之后），所以清理失败绝不会
  影响这次发布；失败时脚本会以非零退出告诉你，但版本已经发出去了。
- **draft 不删**：`Remove-StaleReleases` 的过滤条件是
  `$_.id -ne $KeepId -and -not $_.draft`，没发布的草稿留着。
- 需要留旧版时加 `-KeepOldReleases`（两个脚本都有这个开关）。
- **后果：旧版本的 changelog 也随之消失**。Release body 不是版本库里的文件，删了 Release
  就没有第二份副本。所以 `-NotesFile` 指向的笔记**是入库的**：`release-notes-<version>.md`
  放在仓库根，和脚本并排。现存 `release-notes-1.1.md` / `release-notes-1.3.md` /
  `release-notes-1.4.md` 三份（1.1 与 1.3 的 Release 已被清理删除，正文只剩这里这一份；
  它们原先只在被忽略的 `work\notes-*.md` 里）。**发新版时把笔记写成
  `release-notes-<version>.md` 再入库**，否则「只留最新版」等于把历史更新说明一起丢掉 ——
  而 tag 只记录源码，不记录说明。

**容易误判的一点**：删掉 Release 之后，`/releases/tag/v1.3` 这类地址**仍然返回 HTTP 200**
（它退化成纯 tag 页）。判据不是状态码，而是：页面 `<title>` 从 `Release HyperDuo 1.3`
变成 `Release v1.3`，且**页面上不再有 `HyperDuo-*.apk` asset 链接**；`/releases` 列表页只剩
一条 `/releases/tag/` 链接。

`.tools\debug.keystore` **是入库的**（`.gitignore` 显式放行），所以发布不需要任何 secret，
产出的 APK 与本地 debug 包、与应用内更新互相覆盖。

**文件名是契约的一部分**：更新器取 Release 的第一个 `.apk` asset，这个名字就是设备上的下载
文件名。

**为什么一个版本必须带 APK**：应用内更新器读的是 `/releases/latest`。如果一个 Release 存在却
没有 APK asset，用户会看到一个装不上的「新版本」。所以宁可失败也不要发空 Release。

**为什么不用 GitHub Actions**：最初的 `release.yml` 在 `android-actions/setup-android@v3` 这
一步就失败了（`platforms;android-37.0` 是 `compileSdk 37` 这种很新的版本化平台，托管 runner 上
的 setup-android 拿不到），后续步骤全部跳过。修这个要有权限调试别人的 action，而本地产出 APK
只需要几十秒且工具链已经 vendored，所以改成 `release.ps1`。发布脚本与本地构建共用同一套命令，
不会出现「CI 能过、本地过不了」的分叉。

#### 改 `release.ps1` 时的两个坑

**必须保留 UTF-8 BOM**（`release.ps1` 是 UTF-8 **with BOM**）。这个 shell 是 Windows
PowerShell **5.1**，它读无 BOM 的文件时用 ANSI 代码页（本机是 GBK/936）。中文注释被解成乱码后，
某个多字节序列会**吞掉后面的引号**，于是报出一堆语法错误（`Missing expression after ','`、
`The string is missing the terminator`），而那些行本身完全正确。用 `read` 工具或编辑器看到的
内容是对的，所以这类报错极易被误诊。给文件加回 BOM 即可：

```powershell
$p='C:\code\HyperDuo\release.ps1'
$t=[System.IO.File]::ReadAllText($p,[System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($true)))
```

**不要用 `& git … 2>&1`**。`$ErrorActionPreference='Stop'` 会把原生程序写到 stderr 的正常输出
（git 的 `Everything up-to-date`、gradle 的弃用提示）升级成**终止错误**，一个成功的步骤就会中止
整个发布。脚本里的 `Invoke-Native` 临时把 `$ErrorActionPreference` 放宽到 `Continue`，只用 exit
code 判成败 —— 调外部程序一律走它。

### 安装与观测

```powershell
& C:\code\HyperDuo\install.ps1              # 安装 + 打开设置界面 + 提示重启 SystemUI
& C:\code\HyperDuo\install.ps1 -InstallOnly
& C:\code\HyperDuo\install.ps1 -LogOnly     # 只跟日志
```

脚本内硬编码了 `$Root='C:\code\HyperDuo'`、`$Adb='C:\Program Files\UotanToolbox\Bin\platform-tools\adb.exe'`、
`$Package='io.github.yixing233.hyperduo'`；日志用 `adb logcat -v time -s LSPosedFramework:* AndroidRuntime:E *:S`。

手动启动设置界面：

```powershell
adb shell am start -n io.github.yixing233.hyperduo/.ui.MainActivity
```

日志 tag 是 **`LSPosedFramework`**（框架代打的），**不是** `HyperDuo`；行形如
`(com.android.systemui)[io.github.yixing233.hyperduo,HyperDuo,<id>,0,1] <msg>`。启动时会打印一行
`HyperDuo installed, hooks=9`；固件改了方法名时会打 `skip <id>: method not found`。

需要重新定位某个容器时，把 `TrioHooks.DEBUG_DUMP` 改成 `true` 再构建，即可打印容器的屏幕
坐标、尺寸与全部子视图（含 slot / 宽高 / alpha / visibility）。

## 配置通道

设置走 libxposed 的 remote preferences，组名 / 文件名同为 `Prefs.NAME = "hyperduo_settings"`；
键与默认值集中在 `Prefs.java`，上下界也在那里（第十一轮后共 36 个 `KEY_*` 常量，其中
`KEY_SHOW_MOBILE_TYPE` 是只读的迁移遗留键，实际读写的 35 个键见配置项参考）。

- **写侧**（设置 App）：`HyperDuoApp.onServiceBind` 拿到 `XposedService`，之后每次改动都
  `getRemotePreferences(NAME).edit().putX(...).commit()`，并把同一份写进本应用的
  `SharedPreferences`（即 `shared_prefs\hyperduo_settings.xml`）。服务是异步绑定、也可能随时
  死亡，所以 `SettingsRepository` 每次写入都实时取 `HyperDuoApp.xposedService`，**不缓存
  service**；绑定成功时还会 `syncAllToFramework()` 全量重放这 35 个键，补齐框架缺席期间的改动。
  **每新增一个键都必须同时加到 `syncAllToFramework()`**，否则该键在框架重连后不会下发。
- **读侧**（hook 进程）：remote `SharedPreferences` 是**只读**的，但支持
  `registerOnSharedPreferenceChangeListener`，框架会实时投递变更。`TrioConfig` 注册一个监听器，
  每次回调重新读成一份 `TrioSettings` 快照并 `invalidateHosts()` 重绘宿主 ⇒ **改设置不需要
  重启 SystemUI，也不需要热重载**。
- 用 `commit()` 而非 `apply()`：值必须在对下一帧绘制可见。

### Compose 注意事项

任何 `OverlayDialog`（本项目的颜色选择弹窗）都必须写在 `Scaffold` 的 content lambda **内部**。
Miuix 的 `MiuixPopupHost` 从 `LocalRootDialogStates` / `LocalDialogStates` 读取待渲染的弹窗列表，
而这两个 CompositionLocal 只由 `Scaffold` 提供；写在外部会注册到一个没人渲染的孤儿列表，
弹窗**静默不出现且不报错**。

这条约束对重启确认弹窗同样成立：它用的就是 `OverlayDialog`，所以也必须留在 `Scaffold` 的 content
lambda 内。`window.*` 下的组件（`WindowDialog`、`WindowBottomSheet` 等）**不受这条约束**：它们自己开一个
真正的 `androidx.compose.ui.window.Dialog`，与 `Scaffold` 的弹窗宿主无关，因此不依赖任何父级。本项目
没有用它们：多引入一种弹窗形态不如复用颜色选择弹窗已有的那种。

### 重启系统界面

顶栏右侧的「重启系统界面」按钮需要 root。它执行的是 `su -c "killall com.android.systemui"`，与
README 里给的手工命令一致——用 `su -c` 而不是往裸 `su` 里喂命令，是为了让退出码属于真正要执行的那条
命令：授权被拒绝与 `killall` 本身失败都表现为非 0，不必从输出里去猜。stdout 与 stderr 合并后**先读完
再 `waitFor`**，否则 shell 写满无人读的管道会一直阻塞，`waitFor` 永不返回。

状态机在 `ui/RestartController.kt`（`Idle` / `Running` / `Done` / `Failed`）。它和 `UpdateController`
一样被提到 screen 级持有：确认弹窗被关掉后 kill 可能仍在进行，root 授权弹窗也不一定随着弹窗消失。

确认弹窗复用颜色选择弹窗那套 `OverlayDialog`，因此和它一样必须写在 `Scaffold` 的 content lambda 内
（见上一节）。kill 进行中把 `enabled` 置为 `false` 并让 `onDismissRequest` 直接返回，此时取消/确认按钮
都不可点、点击外部与返回键也都不关闭——用户可能正对着 root 授权弹窗，此时关掉确认弹窗会让结果无处可报。
失败文案沿用更新卡片「本地化标题 · 原始诊断文本」的拼接方式，诊断文本不翻译。**这条通路不需要新增
`Prefs` 键**，也不经过配置通道。

### 颜色恢复默认的确认

颜色页的「恢复默认」同样先弹确认。它和重启确认共用同一套 `OverlayDialog` 外壳（`ResetColorsDialog`），
所以同样必须在 `Scaffold` 的 content lambda 内。理由是这条操作会一次性丢掉六种自定义颜色（三种状态 ×
深/浅背景）且没有撤销，误触的代价与重启同一个量级。

弹窗打开期间只翻转一个 `resettingColors` 布尔值，**不在打开时就重置**：确认与取消走的是同一条 `update`
漏斗，写操作只发生在确认回调里。该行此前没有 `enabled` 守卫，顺带补上 `a.glyph && a.roleColors`——
在状态颜色总开关关闭时它重置的是一组不生效的值，和上面几行保持一致的灰显更合理。

### 顶栏模糊

顶栏用 `miuix-blur` 的 `Modifier.textureBlur` 采样页面内容做磨砂玻璃，而不是画一层不透明底色。
两个修饰符缺一不可：页面 `LazyColumn` 挂 `Modifier.layerBackdrop(backdrop)` 把自己录进一个
graphics layer，顶栏再通过同一个 `backdrop` 采样它——捕获源与采样面是 `Scaffold` 里两个不同的兄弟槽位，
库内部靠 `layerCoordinates.localPositionOf()` 对齐全局坐标，不需要嵌套。

- **必须自己门控，不能只依赖库**：库在 `isRuntimeShaderSupported()` 为假时会跳过特效本身
  （`DrawBackdropNode.draw()` 首行 `if (!enabled) { drawContent(); return }`），但这对本项目不够——
  模糊顶替的是顶栏自己的背景，而 `TopAppBar` 把传入的 `modifier` 应用在自身 `background(color)`
  **之前**，所以未门控的老设备上会得到一个完全没有背景的顶栏，列表会直接从标题上滚过去。
  因此调用点用 `if (barBlurSupported)` 同时切换 `modifier` 与 `color`（支持时 `Color.Transparent`，
  否则 `surfaceColor`）。
- **捕获层必须先铺不透明底**：页面留白与卡片间隙是透明的，直接模糊会把邻格颜色横着拖开一道。
  `rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }` 先铺一层 `surface`。
- `rememberLayerBackdrop` 的 `onDraw` 经 `rememberUpdatedState` 读取，所以每帧新建的 lambda 不会重建
  backdrop、不会重置坐标；但 `remember` 本身必须**无条件**调用，条件化会破坏 slot table。
- `BarBlurRadius = 40f`，远高于库默认的 20dp：顶栏有状态栏 + 大标题那么高，半径太小时底下文字仍可辨，
  观感只是半透明蒙层而非磨砂。
- 依赖侧：`miuix-blur-android` 的 aar 自带 `minSdkVersion="33"`，而本项目 `minSdk = 29`；靠
  `app/src/main/AndroidManifest.xml` 里的 `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur" />`
  保住 29（已验证 merge 后仍是 `minSdkVersion="29"`）。`miuix-shader-android` 虽由 blur 传递引入，
  但代码直接 import 了它的 `isRuntimeShaderSupported()`，所以也显式声明。

### 提示文案：Snackbar 与 Tooltip

- 反馈改用 Miuix `SnackbarHost`（`Scaffold(snackbarHost = ...)`）。用它是因为它与页面同一个
  surface、跟随主题、且随页面销毁而消失。时长选 `SnackbarDuration.Short`（4000ms）而不是
  `Long`（10000ms）——两个数字取自反编译的 `SnackbarKt.toMillis`，不是猜的：这几条消息都是一句
  话，十秒太长，而提示条本身还可以划走。所有弹出都收敛到一个 `showMessage: (String) -> Unit`，
  并用 `remember(scope, snackbarHostState)` 包住，避免每个接收它的行都被不稳定 lambda 拖着重绘。
  （1.0 的两条反馈文案是常驻行内文字，不是 Toast；git 历史里从未出现过 `android.widget.Toast`。）
- 被复合门控灰显的行（例如需要先开总开关再开子开关）加 `TooltipBox` 长按提示，文案来自
  单条格式串 `R.string.gate_hint`（`需要先打开「%1$s」` / `Turn on "%1$s" first`），
  由 `gateHint(vararg gates: Pair<Boolean, Int>)` 取第一个未满足的开关名。提示通过
  `enabled = hint != null` 关闭——`Tooltip.kt` 里 `tooltipGestures` 在 `enabled = false` 时退化成
  普通 Modifier，所以灰显行只有在确实存在未打开的前置开关时才响应长按。
- **仅被总开关拦下的行刻意不加提示**：总开关就在同一屏上，提示是噪音。
- **一行可以被别的开关门禁，但永远不能被自己门禁。** 推导 `enabled` 时不要用把本行开关值折进去的
  派生谓词，而要回溯到它所依赖的**上游**开关。1.4.1 落地时「充电时显示闪电」写成了
  `enabled = a.glyph && a.drawsBolt()`，而 `TrioAppearance.drawsBolt()` 是 `boltWanted && value`
  ——**含这一行自己的值**，于是关掉它以后该行永久变灰、再也打不开（用户上机报「无法开启」）。
  正确写法是 `enabled = a.glyph && a.value`（挂「显示电量数字」）。上机核验方法：`uiautomator dump`
  取该开关节点，点掉之后它必须是 `checked="false"` 但仍 `clickable="true" enabled="true"`；旧写法
  会是 `clickable="false" enabled="false"`。**不要**用「预置 `show_bolt=false` 再启动 app」验证——
  `SettingsRepository` 启动时会全量同步并把 pref 回写成 `true`，必须走 UI 点击。

### 导航：miuix-nav

设置页的四个分区由 `miuix-nav` 的 `NavDisplay` 承载，四个标签就是栈上的四个目的地。

- 路由是 `@Serializable sealed interface Route : NavKey` 下的四个 **data object**。必须可序列化
  是因为 nav 把 back stack 放在 `rememberSaveable` 里、用 serializer 重建；必须是 object 是因为
  nav 用路由自己的 `toString()` 给每个条目的 saved state 做命名空间，object 的 `toString()`
  是类名（跨进程稳定），而普通类的默认实现会打印 identity hash（进程重启后复位）。
- `rememberNavController<Route>(Route.General)` 的**超类型必须显式写出来**：reified 参数否则会
  从实参推断成 `Route.General`，之后 push 其它三个子类型时保存/恢复会序列化失败。
- 切分区走 `selectTab`：落在已经在栈上的路由就 `popUntil` 回退过去，不在栈上才 `push`。
  这样栈是一条路径而不是点击流水账，也顺手满足 nav 文档要求的 push 幂等（重复 key 会被拒绝，
  而标签条允许点得比转场更快）。
- **页面级状态必须传对象而不是值。** `NavDisplay` 内部是
  `val provider = remember(content) { entryProvider(content) }`——DSL lambda 被记忆，provider
  只在 lambda 实例变化时重建。所以 entry 里若捕获 `settings` 的**值**，之后设置变化时它会一直
  渲染那个快照。因此 `SettingsScreen` 保留 `settingsState`/`serviceState` 两个 `MutableState`
  对象并把它俩传给 entry，让每次读取都发生在目的地自己的组合里（真实 snapshot read）。
- **`preview` + 标签条留在每个目的地的 `LazyColumn` 里，没有提到 `NavDisplay` 之上。**
  它是页面的一部分，要跟着内容滚走：顶栏的收起依赖这个滚动，预览卡也正因如此才能在不滚回
  顶部的情况下与设置项对照。提到上层会把它钉在屏幕顶部，两件事同时坏掉。
  四个目的地各带一份（`sectionHeader()`，`SettingsScreen.kt:314-330`），所以它随各自的列表
  滚动。看起来是四份重复，实际不会看出差别：两份是同样的像素、同一个位置，
  转场时互相淡入淡出落在完全相同的内容上。
  位置也验过是等价的——把 header 放进列表只改了它的归属，没改它的坐标：改动前后两次
  `uiautomator dump` 里预览卡与四个标签的 `bounds` 逐字相同。
- 起先放在那条唯一 `LazyColumn` 上的 `.layerBackdrop(barBackdrop)` 与
  `.nestedScroll(scrollBehavior.nestedScrollConnection)` 上移到 `NavDisplay`：模糊要采样整页，
  滚动行为要听到现在发生在目的地内部的滚动。
- **切标签一律回到列表顶部，不恢复上次的滚动位置。** 这里换过两次方向，最终以用户的
  「不要记录页面位置」为准。中间那版曾把「每个目的地各留各的偏移」当成返回语义的正确形态：
  四个分区各有一个 `listState`，走回去就回到原处。问题在于目的地被覆盖时**仍然组合着**
  （见下文交叉淡入的可见窗口），所以「回到原处」不是恢复一个冻结的快照，而是读者刚在标签条上
  选了一个分区、却被丢进它的中段。滚动偏移属于这一次访问，不属于这个分区，因此不跨访问携带。
  实现：`SectionList` 多收一个 `isTop`（`SettingsScreen.kt:572-587`），
  `LaunchedEffect(isTop) { if (isTop) listState.scrollToItem(0) }`；四个 entry 各自传
  `isTop = nav.backStack.lastOrNull() == Route.Xxx`（`SettingsScreen.kt:467/475/483/497`）。**键取 `isTop` 而不是
  `LaunchedEffect(Unit)`**：同一个已组合的列表会被反复覆盖又揭开，重置必须发生在每次「成为栈顶」
  时，而不是当初创建它的那次组合里。
- 对话框（取色、重启、重置确认）**刻意不做成路由**：它们是浮在页面上的提示，推成路由会让
  返回手势的含义从「回上一个分区」变成「取消」。
- **转场不能用库的预设，必须换成一个零位移的交叉淡入。** `NavTransitions.MiuixDefault` 的动作是
  「到达一个新页面」：进入的层从右缘整幅推入（`NavTransitions.kt:43-57` 里 `d <= 0f` 分支的
  `translationX` 最大等于页宽），被它覆盖的层视差左移 1/4 页宽并降到 0.9 alpha，外层再由
  `NavDisplayEffects.dimAmount = 0.5f` 压一层灰。四个标签是同一页的四个视图，横推过去看着像
  把页面撕成两半。`SectionTransition`（`SettingsScreen.kt:195-228`）因此
  只动 alpha：`relativeDepth` 为 0 是停在顶层、-1 是完全退出到上层之上、+1 是被上层盖住，所以
  `alpha = if (d <= 0f) (1f + d) else 1f` 一个式子就同时管往前往后两个方向，被盖住的那层保持
  全不透明直到真的被盖住。
  **挑 alpha 而不是位移动画，也是因为 header 就在被动画的层里**：两份 header 是同一批像素，
  淡入淡出看不出来；一旦改成横推或缩放，屏幕上就会出现两套错开的预览卡与标签条。
- **被覆盖分支也必须显式写 `alpha`。** 这个 block 跑在 `Modifier.graphicsLayer { }` 里，而
  graphicsLayer 是跨帧保留的：某个属性这一帧不再被赋值，它就沿用上一帧的值。只在 `d <= 0f`
  时赋值会让层冻结在它上一次进入动画的 alpha 上，而不是正常显示为不透明。
- 时长取 220 ms 而非预设的 500 ms，`NavDriverSpec.PROGRAMMATIC_DURATION_MILLIS` 是为整页推入调
  的曲线。另外只有**静止起步的整步**才会走 `programmatic` 曲线
  （`runtime\NavDriver.kt` 的 `usesProgrammaticCurve = velocity == 0f && abs(distance) >= 0.999f`），
  所以这个 Tween 实际只作用于点标签；返回手势中途松手带着速度，仍会落到 `commit` 的 spring 上。
- `SectionEffects`（`SettingsScreen.kt:235-238`）关掉 `enableCornerClip`、把 `dimAmount` 设为 0：
  这里从没有层叠在另一层之上，裁角和压暗都没有对象。
- **每个目的地的 `LazyColumn` 必须自己铺底色（`Modifier.background(MiuixTheme.colorScheme.surface)`，
  `SettingsScreen.kt:606`）。** 交叉淡入要求被覆盖的那层在转场期间继续组合、继续绘制——
  visibility window 是 `-1 < d <= opaqueDepth`（`runtime\NavPresentation.kt:104`），
  `opaqueDepth = 1f` 正是为了留住这一层。问题是两个列表尺寸位置完全相同，被覆盖层**只能被上层
  实际画出的像素遮住**：卡片之间有 12dp 间隙、较短的分区结束后还有大片空白，透明列表在这些地方
  什么都盖不住，于是上一个分区的行直接透出来，看起来像旧标签页垫在新标签页底下（用户报的原话是
  「不同标签页会覆盖上一个标签页的内容在底层」）。`Scaffold` 只画一层底色（`containerColor =
  MiuixTheme.colorScheme.surface`，`.tools\tmp-src\miuix-sources\…\basic\Scaffold.kt:88`），
  在只有一个列表的年代够用，现在得由列表自己负责。用同一个 surface 色是刻意的：静止态因此
  与改动前逐像素一致。

## 实现要点

### 注入点

`MiuiBatteryMeterIconView.onDraw(Canvas)` 之后。该视图本身就被测量为 **28dp × 20dp**
（`battery_meter_width` × `status_bar_icon_height`），画布够用，无需改动视图树。注意固件的
**旧式分支不清画布**（`onDraw` L536 在 `super.onDraw` 后直接 `return`），所以我们在
`proceed()` 之后无条件 `drawColor(0, CLEAR)` 再绘制字形，避免原生 drawable 透出来。

### 几何

120×120 设计空间，常量集中在 `TrioGeometry.java`。电池环圆心 `(59.5, 61.487)`、半径 51.5、
描边 8、起始角 148.69°、扫过 242.62°（缺口在**底部**）；顶部缺口留给数字（进度 0.325–0.675）
或闪电（0.345–0.655，比数字缺口**更窄**）。Wi-Fi 半径 31 / 18.5，描边 7，等级 0–3；点阵 4 颗
半径 5.5，等级 0–4。环线粗细 / 弧线粗细 / 数字字号 / 轨道透明度由 `TrioSettings` 覆盖，未改动
时可复现上面这些原始值。

`DOTS` 只有一排；双卡读数的第二排（`DOTS_DUAL`）由它关于**环心** `B_CY = 61.48715261785473f`
镜像得到，而不是关于设计盒中心（120 的一半 = 60）——镜像轴取错会让上排整体偏 1.49 设计单位，
在 28dp 的宿主里约合 0.76px，肉眼几乎看不出，但对齐参考图时对不上。双排点半径
`DUAL_DOT_R = 7.0f` 比单排的 `DOT_R = 5.5f` 大：参考图的点明显更大，且 7.0 是最大环线粗细
（16）下仍留在墨迹盒内的上限。

缺口是**有内容才开**的：`gapStart == gapEnd == 0`（`GAP_NONE`）时 `batteryRing` 的两段会合并成
一整圈。判断顶部缺口是否被占用，以及谁占圆心，都在 `TrioRenderer.drawInto` 的决策块里
（`TrioRenderer.java:118` 起）；圆心与缺口的字号、基线、清空宽度分别由
`TrioGeometry.centreSize/centerBaseline/centreClearWidth` 与 `gapBaseline/gapClearWidth` 给出。

`batteryRing(Canvas, from, to, gapStart, gapEnd)` 的 `from` / `to` 是**可见弧**上的进度，不是整条
路径的进度：缺口本身不出墨，也就不该占用电量。设缺口为 `[m0, m1]`，可见弧长
`drawn = m0 + (1 - m1)`，那么电量 `f` 到达的可见位置是 `u = f * drawn`，左段画
`[0, min(u, m0)]`、右段画 `[m1, m1 + max(0, u - m0)]`。于是 `drawn = 0.65` 时 50% 恰好铺满左半环
（`u = 0.325 = m0`）、右半环为空，50%–100% 全部落在右半环上。

早期实现把 `level/100` 当作整条路径的进度直接用（`from`/`to` 不乘 `drawn`），缺口宽度没有补偿，
症状是电量在缺口整段宽度内**原地不动**：45% 就已填满左半环，而右半环要等电量爬过 67% 才起弧。
端帽（`STROKE` 是 `Paint.Cap.ROUND`，半宽 `stroke/2`）会让每段末端向外多渗一点墨：`stroke = 14`
时约 `7 / 218.078 ≈ 0.032` 路径进度（7.79°），**校验像素时必须先扣掉这个渗出量**，否则正确渲染
也会被判成越界。

### 电量数字居中

设置里这一项叫「电量数字居中」，存储键却是 `swap_wifi_value` —— **键名冻结，不要改**。它是个布尔，
已装用户可能已经打开过；换新键名会让那部分人的选择在升级后静默丢失（框架不做类型/名称迁移）。
只有**标签**改过名：旧文案「Wi-Fi 与数字换位」描述的是机制，而用户要的是「数字居中」。

打开后，`drawInto` 不再做「两个槽位互换」，而是一套**优先级**：电量数字永远第一顺位占圆心，
其余符号依次让位。

| | 关（默认，行为未变） | 开 |
| --- | --- | --- |
| 圆心 | Wi-Fi 弧；无 Wi-Fi 且未选环内类型时是电量数字 | 电量数字（放大 `CENTRE_SIZE_RATIO` 倍）——只要 `show_value` 且电量已知就必定是它 |
| 顶部缺口 | 电量数字 / 闪电 | Wi-Fi 弧（缩小到 `GAP_WIFI_SCALE`）/ 充电时的小闪电 / 让位出来的环内网络类型 |

三条让位规则都只作用于 `centred == true`：

1. **闪电恒落缺口**。`boltInCentre` / `boltInGap` 这对变量已删除，`drawBolt(Canvas c, int color)`
   只剩一个落点，固定用 `boltOffsetX/Y` + `BOLT_SCALE`（原居中几何 `BOLT_CENTRE_SCALE`、
   `boltCentreOffsetX/Y` 已从 `TrioGeometry` 移除）。闪电占缺口时**整组 Wi-Fi 弧不画**，
   门是 `cfg.showWifi && (!centred || wifiInGap)` —— 不能只把 `wifiInGap` 置假，否则
   `drawWifi(..., false)` 会把弧画回圆心压在数字上。
2. **缺口归闪电时用窄嘴**。`gapUsed` 的门与 `GAP_START/END_CHARGE` 的选择只看 `bolt`，
   不再看闪电落在哪个槽位。
3. **环内网络类型让位**。`valueInCentre` 生效时数字占圆心，类型改由新增的
   `drawGapType(Canvas c, String type, int fg, TrioSettings cfg)` 画进缺口（`gapClearWidth` +
   `gapBaseline`，用 `cfg.typeSize` / `cfg.typeWeight`）；圆心空出来时才回到 `drawCentreType`。

Wi-Fi 搬进缺口仍是 canvas 变换（`translate` + `scale`）完成的，几何常量仍是原始那套绝对值，
在 `save()`/`restoreToCount()` 里做。

`centred == false` 时这套式子化简回改动前的判定（`wifiInGap` 与 `typeInGap` 恒为假），
`work\slotcheck\verify.ps1` 用 12 个非居中状态在新旧两版之间逐像素对照，专门锁住这一点。

代码侧的符号（`Prefs.KEY_VALUE_CENTRED`、`TrioSettings.valueCentred`、`TrioRenderer` 里的 `centred`）
都跟着标签改成了「居中」语义，只有那个字符串字面量保持 `"swap_wifi_value"`。

### 三合一样式：圆环与矩形

`trio_style` 两档：`0` 圆环（默认）、`1` 矩形。**选项顺序即存储值**，未识别的值在
`TrioSettings` 里被 `Prefs.clamp` 收敛回圆环，所以升级不会画出一个不存在的排布。

切换发生在 `TrioRenderer.drawInto` 的入口：`drawInto` 只负责 `clearWhenDone` 清屏、算一次
`roleColor`，再按样式选**各自的墨迹盒**做一次 `translate` + `scale`，随后的绘制分成两支：

| | 圆环（`STYLE_RING`） | 矩形（`STYLE_RECT`） |
| --- | --- | --- |
| 墨迹盒 | `INK_X/Y/W/H`（`1.0, 0.5, 117.0, 117.5`） | `RECT_INK_X/Y/W/H`（`1.0, 1.0, 118.0, 114.4`） |
| 落点 | `drawRingLayout` | `drawRectLayout` |

两套墨迹盒是必要的：矩形排布的纵向范围比圆环小、横向范围比圆环大，共用一盒会让其中一种在
宿主 28dp×20dp 的画布里被裁掉或缩得偏小。选盒由包级重载
`inkScale(int w, int h, boolean rect)` 决定，它再转调私有的
`inkScale(int w, int h, float inkW, float inkH)`；`drawInto` 与设置页预览都走这个重载，
**预览因此拿到与状态栏逐位相同的缩放**。公开的 `inkScale(int, int)` 语义不变
——`TrioHooks` 用它做「宿主尚未测量」的守卫（`TrioHooks.java:1752`），改它会把守卫一起改坏。
环外标签的字号**已经不再取自 inkScale**，见「网络类型：环内与环外」。

矩形排布（设计空间仍是 120×120，`TrioGeometry` 的 `rectangular arrangement` 一节）：

- **两列信号点**，`RECT_DOT_LX = 9.6` / `RECT_DOT_RX = 110.4`，行心
  `RECT_DOT_TOP + i * RECT_DOT_STEP`（`15.6 + i * 22.5`，4 行），半径 `RECT_DOT_R = 8.6`。
  两列是**同一个电平的镜像**：点亮数 `min(RECT_DOT_ROWS, level)`，自顶向下点亮，未点亮用
  `withAlpha(fg, cfg.trackAlpha)`。随 `show_mobile` 开关。
- **顶部槽位**三选一，优先级 **闪电 > Wi-Fi > 网络类型**，与圆环排布一致：闪电复用
  `drawBolt` 的路径，只换成 `rectBoltOffsetX/Y`（把缩放后的墨迹中心摆到 `x = 60`）；
  Wi-Fi 复用**同一组** `W1/W2/WIFI_DOT`，用 `RECT_WIFI_SCALE = 0.95` 缩放后由
  `rectWifiOffsetX/Y` 锚定到外弧中心线顶点 `RECT_WIFI_TOP = 8.5`；网络类型用
  `RECT_TYPE_BASELINE = 42.3` 与 `typeSize * RECT_TYPE_SCALE(= 1.26)`。
  弧的缩放是量出来的：参考图外弧的墨迹宽 3.05 倍点径，未缩放的组是 3.20 倍，说明这个
  排布下弧几乎就是全尺寸，取 `0.95` 而不是 `1.0` 是因为参考图自己的弧略扁（墨迹更宽、
  描边更细）——**这个差异刻意不复刻，弧必须沿用现有图标，只做重定位**。
- **电量数字**居中于 `RECT_VALUE_X = 60`，基线 `RECT_VALUE_BASELINE = 92.7`，字号
  `valueSize * RECT_VALUE_SCALE(= 1.38)`，颜色是 `fg`（与圆环排布的 `drawValue` 一致，
  `role` 只喂给电量条）。两种文字都先过 `fitSize(text, requested, RECT_TEXT_CLEAR)`
  （`RECT_TEXT_CLEAR = 83.6`，即两列点之间的净宽）。
- **底部电量条**：`RECT_BAR_LEFT = 1.0` / `RECT_BAR_RIGHT = 119.0`（与两列点的外缘齐平）、
  中心线 `RECT_BAR_CY = 107.3`，**粗细取 `cfg.ringStroke`**——矩形没有圆环，让这个滑块继续有
  归宿；设置页在矩形下把这一行改称「电量条粗细」（`stroke_title_rect`），滑块本身不换。
  轨道是整条胶囊（`drawRoundRect`，`withAlpha(fg, cfg.trackAlpha)`），点亮段用
  `Path.addRoundRect` 配 `RECT_RADII = {r,r, 0,0, 0,0, r,r}`（左端圆角、右端垂直切边），
  右端 `cut = left + (right - left) * clamp01(level / 100f)`。
- **`valueCentred` 对矩形完全无效**：`drawRectLayout` 里没有它的分支。规则收在
  `TrioAppearance`（构造时 `centreValue = c.valueCentred && !rect`），设置页据此把这一行置灰
  并提示需要先打开圆环；这是本次门禁改造**唯一有意的行为变化**。

矩形排布把 Wi-Fi 弧的画弧主体从 `drawWifi(c, level, fg, a, gap)` 抽成了
`drawWifiArcs(c, level, fg, a)`（`TrioRenderer.java:479` / `:496`）：两个排布各自只做一次
`translate` + `scale` 再调用它，**弧的形状与描边完全同源**，这也是「Wi-Fi 图标沿用现有实现」
的落地方式。取 `TrioAppearance` 而不是 `TrioSettings` 是同一件事的一部分：弧的粗细现在问
`a.arcStroke`，不再是 `cfg.arcStroke`。

### 网络类型：环内与环外

`mobile_type_mode` 三档：`0` 关闭、`1` 环内、`2` 环外。**选项顺序即存储值**。

- **环内**（`1`）走渲染器：规则在 `TrioAppearance.Ring` 的决策块里
  （`TrioAppearance.java:222-250`）。`type = a.typeInRing && mobileType != null && !mobileType.isEmpty()`，
  再按圆心是否已被电量数字占用分流：`typeInCentre = type && !wifi && !valueInCentre`，
  `typeInGap = type && valueInCentre && !wifi && !bolt && !dual`。即**数字优先**，类型退到顶部缺口；
  没有数字可展示时才回到圆心。它**不查 `show_value`** —— 与闪电（`drawsBolt()` = `showBolt && showValue`）不同。
- **环外**（`2`）不是 canvas 绘制。宿主画布只有 `battery_meter_width = 28dp` ×
  `status_bar_icon_height = 20dp`，装不下环外的字，所以模块**自己往电池容器里新增一个
  `TextView`**：`TrioHooks.OutTypeLabel`（`TrioHooks.java` 的 `out-of-ring type label` 区块）。
  挂点是 `batteryContainerOf(host)` 返回的 `MiuiStatusBatteryContainer` —— 选它是因为
  `MiuiStatusBatteryContainer.onMeasure/onLayout` 只量/摆自己那几个具名字段、
  **从不遍历 `childAt`**，因此第 4 个子视图不会被量也不会被摆，手工 `layout()` 的位置能保住；
  容器还带 `clipChildren="false"`，越界也画得出来。

**绝不能挂到 `MiuiStatusIconContainer`**：它的 `onLayout` 第一遍把所有 child 的 x 归零，第二遍
又把每个 child 强转 `StatusIconDisplayable`（外来视图直接 `ClassCastException`）。同理也不能用
`WeakHashMap<View, TextView>` 缓存 label —— value 里的 `View.getParent()` 强引用回 key，条目永远
回收不掉；这里改用「子视图 `instanceof OutTypeLabel`」当标记。

挂载/摘除只发生在 posted 路径（`applyConfigChange`、`registerHost`、`invalidateHosts`），因为
`settle()` 是从 `onLayout` 里调的，在那儿 `addView` 会触发
`requestLayout() improperly called during layout`。`settle` 里只调 `refreshOutTypeLabel`，它发现
文本/字号需要变时**只排队一次 posted sync**（`setText`/`setTextSize` 会 re-measure → 调度布局，
同样不能在 `onLayout` 内做），否则只更新颜色与位置。

字号用 `setTextSize(TypedValue.COMPLEX_UNIT_PX, a.outTypeSize)`：**必须显式
`COMPLEX_UNIT_PX`**，单参 `setTextSize(float)` 默认按 SP 解释，而这里存的是裸 px。**环外字号
不再乘 `inkScale`**：它是一个独立键 `out_type_size`（默认 32，与 `type_size` 同值，
见 `Prefs.KEY_OUT_TYPE_SIZE`），含义是「宿主 20dp 图标盒子里的像素」，乘上画布缩放反而会
把两种排布的字号绑在一起。字重取 `a.typeWeight`（`TrioRenderer.typefaceFor`），与环内共用。
宿主尚未测量（`inkScale <= 0`）时跳过挂载，下一次 posted sync 自愈。文本为空时置 `GONE`
而非移除 —— 网络类型随 modem 来去，每次布局 add/remove 太吵。

`type_size` 只作用环内，`out_type_size` 只作用环外，二者**不再共用**；`type_weight` 是两者
共用的。设置页的门禁因此是 `a.typeInRing` / `a.typeOutOfRing` / `a.typeAnywhere()`，不再是
裸的 `mobileTypeMode == X` 比较。`show_mobile_type` 是废弃的旧布尔键，仅用于迁移读取
（见配置项参考）。

**结尾 A 的缩小（`type_suffix_scale`）**：参考图 `docs/ref-5ga.png` 量出 A 高 / 主字高 ≈
56/86 ≈ 0.65，底边与主字底边近似齐平（差 2px），所以默认 65。这个比例是**标签本身的属性**，
与它画在环内还是环外无关，因此环内、环外共用一个键，两处各用自己的机制实现：

- **环内（Canvas）**：`Canvas.drawText(CharSequence,...)` **不应用 Span**（只有 `TextPaint` 经
  `StaticLayout` 才会），所以 `TrioRenderer.drawType` 分两段绘制：先量 `"5G"` 与 `"A"` 的宽度，
  按总宽算出左起点（`TEXT` 默认 `Align.CENTER`，居中会让两段叠在同一处，所以绘制期间切成
  `Align.LEFT`、画完还原），两段共用同一条 baseline 以保证底边对齐。`fitSize` 也走同一套
  量测（`measureType`），否则用整串宽度判断「装不下」会把本来放得下的标签缩小。
- **环外（TextView）**：`OutTypeLabel` 是真 `TextView`，直接用
  `SpannableStringBuilder` + `RelativeSizeSpan` 套在末字上，平台自动排版并对齐 baseline。
- **判断后缀**：MIUI 报的是整串（`"5GA"`），所以规则是「末字为 `A` 且长度 > 1 且比例 < 100」
  （`TrioGeometry.hasShrunkSuffix`）。比例 100 即关闭缩小；单独的 `"A"` 不是后缀，缩小它等于整体
  变小，那是字号滑杆的事。

**环外标签的两个边距（`out_type_margin_left_dp` / `_right_dp`）**：标签的两个邻居不同——外侧
对着原生图标行，内侧对着环外读数（没有读数时对着电池），两条缝分别调。语义按**物理左右**定义
（不是阅读顺序的 start/end），RTL 下整行镜像，所以 `placeOutTypeLabel` 里 LTR 用右缝、RTL 用左缝
（`anchor.getLeft() - right - width` 对 `anchor.getRight() + left`）。`reserveOutRingStrip` 是唯一
把两条缝合成为**一个** strip 数字的地方（见其注释），同样按 RTL 取「朝锚点」的那条缝；只有标签
独占时才会把外侧那条缝也算进去。

**环外读数的位置偏移（`out_signal_offset_x_dp` / `_y_dp`）**：走**布局层**（在
`placeOutTypeLabel` 的 clamp 之后把像素偏移加到 `left`/`top`），不走 `drawOutSignal` 改
`x0`/`baseline`。两个理由：视图的测量尺寸/占位不变（偏移后不会被裁、也不会和邻居重叠），且标签
锚在读数已偏移的 `left` 上会**自动跟着走**，两者不会脱节。`placeOutTypeLabel` 里已有的
`setTranslationX(-islandShiftPx)` 是超级岛专用，偏移加在 `layout()` 的坐标上而非 translation，
两者不打架。水平偏移默认 0，所以 `placeOutTypeLabel` 的三参重载保留给标签自己用（标签不带偏移，
由读数带动）。

已知风险：原生 `mobile_type_single` 是 mobile 槽组的子级，而 `foldedSlots()` 不包含
`mobile_type`，所以「关闭显示移动信号点 + 环外」时可能同时看到原生与自建两个标签。环内模式不会
冲突 —— 它一定伴随 mobile 槽折叠。

### 配色

角色优先序在 `TrioRenderer.roleColor(TrioSettings cfg, int level, boolean charging, boolean powerSave, boolean low, int fg)`
（`TrioRenderer.java:169`）：

1. `critical` —— `level >= 0 && level < TrioGeometry.CRITICAL_LEVEL`（`CRITICAL_LEVEL = 20`，
   `TrioGeometry.java:208`），`TrioRenderer.java:175`；
2. `lowPower` —— `powerSave || low || (level >= 0 && level <= cfg.lowThreshold)`，`TrioRenderer.java:178`；
3. `charging` —— `TrioRenderer.java:181-182`；
4. 否则回落到前景 tint。

轨道与未激活点用前景色（默认 22%，可调）透明度；数字与闪电始终用前景 tint。深/浅底由前景色的
sRGB 亮度判断，三组角色色各有深/浅两套。前景 tint 自身仍取自图标视图的
`mUseTint / mTintColor / mLightColor / mDarkColor`。

快充由 `TrioState` 反射读 `mQuickCharging`（与 `mCharging`）得到，**只改闪电颜色**（琥珀色
`TrioGeometry.QUICK_CHARGE`），不改形状或大小 —— 见 `TrioRenderer.drawBolt(Canvas c, int color)`
（`TrioRenderer.java:426`）的注释：*bolt fill; amber while the battery reports quick charge*。

### 预览与状态栏共用几何

`TrioPreviewView`（设置界面顶部一行 39dp 的方块）直接调用
`TrioRenderer.drawInto(..., clearWhenDone=false)`，与状态栏走同一份代码与同一个 `TrioSettings`
快照。`clearWhenDone=false` 是必须的 —— UI 预览若清画布会把 Activity 背景擦成透明黑。

预览卡共 8 格：充电 / 快充 / 正常 / 无 Wi-Fi（带双卡电平与环内类型）/ 省电 / 危险 /
**环外类型** / **环外信号**。后两格是例外：环外标签与环外读数都不由渲染器绘制
（见「网络类型：环内与环外」与「环外信号」），所以走
`TrioPreviewView.setOutTypeOnly(true)` / `setOutSignalOnly(true)` 自己画。标签那格必须与
`TrioHooks.updateOutTypeLabel` 同口径——字号取 `outTypeSize`、字重取 `typeWeight`、按 px 而非 sp，
再把宿主 20dp 图标盒按预览自身的高度等比换算（`HOST_ICON_HEIGHT_DP = 20f`，与 `docs` 里
`status_bar_icon_height` 一致），结尾 A 的缩放取 `type_suffix_scale`。`out_type_size` 在设置页
别处**没有任何可见反馈**，这一格就是它的唯一所见即所得参照；口径一旦和钩子侧不一致，预览就
开始骗人，比没有预览更糟。信号那格同理：按 `out_signal_size_dp × density` 与参照纵横比定出
读数尺寸，并把 `out_signal_offset_*_dp` 换算成格内的位移（格宽按「读数 + 两侧满量程偏移」定，
否则大偏移会把读数推出格子，看起来像没有这一项）。

`PREVIEW_SIZE = 39.dp` 是为了让八格一行放得下（8×39 + 7×4 = 340dp < 344dp）。第 7 格用了
`"5GA"` 作为示例类型——正是为了让 `type_suffix_scale` 有可见反馈。

唯一的额外处理：状态栏里这个标签是 wrap-content、排在图标盒旁边，宽了就往右伸不会被裁；
而预览格是个正方形，`"5GA"` 这种宽字串会顶到边界。所以 `TrioPreviewView.drawOutTypeLabel`
在量得文本宽度超出格宽时，只用 `setTextScaleX` 做**水平压缩**，不动字号 —— `outTypeSize`
真正设定的是**高度**，这一维必须保持精确。

### 原生图标抑制

两条通道叠加，两者都**按子开关**决定折叠哪些槽位（`TrioHooks.foldedSlots()`：`show_wifi` → `wifi`；
`show_mobile` → `mobile` + `stacked_mobile`；总开关关闭时为空集）。

1. `MiuiStatusIconContainer` 的 `ignoredSlots` 列表 —— 让容器在 `onMeasure` 时把它们排除出
   `measureViews`。`syncSlots()` 让该列表与子开关**双向**对齐：补上缺失的，并**移除**不再需要的。
   移除才是「交还」的关键：MIUI 会为所有未被忽略的槽位重新布局并定位。
2. 仅靠 (1) 不够：`MiuiStatusIconContainer.onLayout` 第一趟会把**每个**孩子放在容器局部 `x=0`，
   后续趟只重定位「可见且未 blocked 且不在 `ignoredSlots`」的孩子，被忽略的孩子永远停在容器
   左边缘 —— 而状态栏容器因为 `MiuiNotificationStatusContainer.onMeasure` 的半屏测量，左边缘
   正好在**屏幕中线**，于是留下游离的「5G」。所以在 `onLayout` 之后遍历孩子，凡 `getSlot()`
   命中折叠集合的，就 `layout(0,0,0,0)`（每趟重施，`layout()` 不调度新布局故不成环）并
   `setVisibility(GONE)`（只做一次，`COLLAPSED` 去重）。

`ModernStatusBarView.isIconVisible()` 只由 binding 与动画标志决定，与 `getVisibility()` 无关，
所以 GONE 单独用是无效的 —— 必须配合 `ignoredSlots`，反之亦然。

**交还**（子开关关闭时）必须把上面两步都撤销：(1) 从 `ignoredSlots` 移除该槽，
(2) 把 `COLLAPSED` 里登记过的孩子恢复 `VISIBLE`，然后 `requestLayout()` 让 MIUI 重排。
`settle()` 与 `restoreNative()` 都只对**自己登记过**（`COLLAPSED`）的孩子恢复可见性：MIUI 自己
隐藏的图标（无 SIM、关 Wi-Fi、飞行模式）从不登记，绝不能被复活。至于折叠期留下的 `0×0` 布局，
不必手工纠正 —— 槽位一旦不在 `ignoredSlots` 里，MIUI 就会重新测量并布局它。

`system_icons.xml` 被 7 个布局 include（状态栏 / 锁屏 / 控制中心 / 两个 QS 头部 / CC fake），
每个都膨胀出独立实例，所以这条规则挂在 `MiuiStatusIconContainer` **类**上而不是某个实例上，
一次覆盖全部宿主；`insets` 变化时 `MiuiPhoneStatusBarView.updateCutoutLocation` 会用
`setIgnoredSlots(RIGHT_BLOCK_LIST)` 清空列表，故在 `onLayout` 每趟用 `ensureFolded` 补齐。
`ensureFolded` 同样是双向的，并且**只在确有差异时才调用 `syncSlots`**：`addIgnoredSlots` 结尾
无条件 `requestLayout()`，每趟盲目追加会造成无限布局循环。

### 充电闪电的交还

闪电不是状态栏槽位，而是 `MiuiBatteryMeterView` 的孩子，所以它走另一条路。

模块把电池样式钉成 0（见 `hyperduo-style`），而 MIUI 的原生闪电**只在 style 1/2 下被测量**：
`onMeasure` 里那句 `measureChildWithMargins(mBatteryChargingView, ...)` 带 style 条件，所以
style 0 时该视图宽高恒为 0 —— 单纯把它设成 `VISIBLE` 也不会显示。`updateChargeAndText` 同样
按 style 决定闪电可见性，`onLayout` 也按 style 决定是否把它排在电池视图之后。

因此交还的做法是**用反射直接写 `mBatteryStyle = BOLT_STYLE(1)`**，而不是调用
`onBatteryStyleChanged(1)`。原因是后者 style-1 分支会 `mBatteryIconView.setVisibility(8)` ——
而 `mBatteryIconView` 正是字形绘制的宿主，走那条路等于用「交还闪电」换掉字形。直接写字段则同时
点亮 `onMeasure`（测量闪电）、`updateChargeAndText`（按电量状态显示闪电）与 `onLayout`（排到
电池视图右侧，即系统默认位置）三条路径，而完全不碰宿主可见性。全固件只有构造函数与
`onBatteryStyleChanged` 会给 `mBatteryStyle` 赋值，所以这次覆写不会被别处悄悄改回去。

谓词是 `enabled && showBolt && showValue`（`TrioRenderer` 画闪电的条件），即**当且仅当字形真的
画出闪电时才压制原生闪电**。实现分两处，都必须用同一规则，否则会把刚交还的闪电又藏回去：
`hyperduo-style`（style 变更时）与 `hyperduo-charge-text`（`updateChargeAndText` 每次重跑时）。
设置变更时由 `applyMeterText()` 补齐，并按 `showValue`/总开关分别处理闪电与百分比容器。

**总开关关闭时必须先把 `mBatteryStyle` 清成 `-1`**（构造函数自己的初值），再透传真实样式。
否则会卡住 MIUI 自己的还原：`onBatteryStyleChanged` 的主体在 `if (mBatteryStyle != i3)` 门内，
而 `handBackBolt` 之前已把字段写成 1；若真实样式恰好也是 1，这道判断为假，主体被跳过，
`:641/:642`（普通图标 VISIBLE、hollow GONE）不执行 —— 可 `handBackBolt` 留下的正是这个组合，
本该是 style 1 的「hollow 开、普通图标关」，于是 hollow 电池轮廓永远回不来。清成 `-1` 是唯一能
让那道门通过的状态。

百分比容器（`mBatteryPercentContainer`）只在 style 3 下被测量和显示，而模块从不请求 style 3，
所以模块开着时它只能是隐藏的。

### 只压制「自己的」容器

`isOwned()` 从绘制宿主沿视图树向上找到它所属的 `MiuiStatusBatteryContainer`（按类名匹配，
不走 classloader —— 宿主被 Compose/Factory 包装后 classloader 拿不到 SystemUI 类），只有真正
在画字形的容器才隐藏原生图标；电池视图本身被隐藏的容器（island / 极简模式 / 控制中心折叠态）
保留自己的信号图标。

### 实时刷新

等级来自 `transformResId`，但**指示器消失时那个方法不再被调用** —— MIUI 关掉 Wi-Fi 后不再重绑
该视图，`sWifiLevel` 会永久停在最后一次的值，弧线就一直在屏幕上（这正是「关了 Wi-Fi 弧还在」
的成因）。所以 Wi-Fi 的**有无**改由实时视图树采样：在 `MiuiStatusIconContainer.onLayout` 之后
遍历孩子，对 `getSlot()` 为 `"wifi"` 的读 `ModernStatusBarView.isIconVisible()`（读 binding，
与 `getVisibility()` 无关），结果喂给 `TrioState.setWifiPresent()`，只在**边沿**变化时重绘宿主。

注意不能靠「子视图是否存在」判断：Wi-Fi 关闭时 MIUI **不移除** `slot=wifi` 的孩子，只是停止
绑定它，孩子数量恒为 1。移动信号不做此反馈 —— `isIconVisible()` 在已淡出的移动视图上仍为 true，
无法区分开关，所以点阵仍由最后一次 resId 驱动。

采样只认权威状态栏容器（`isStatusBarContainer`：等于捕获到的 `mStatusBarStatusIcons`，或祖先
类名为 `MiuiPhoneStatusBarView`），否则控制中心/QS 头部的容器会污染全局状态。

#### 环外视图的前景色（深浅色跟随）

环外两个自建视图（`OutTypeLabel`、`OutSignalView`）的前景色都取自 `TrioState.foreground()`，
但**没有任何东西会在深浅色变化时重绘它们** —— 这是「字体颜色跟随状态栏变色的逻辑更新不及时」
的成因。机制（jadx 取证）：MIUI 的深浅色流程是
`MiuiBatteryMeterView.updateLightDarkTint(...)`（`MiuiBatteryMeterView.java:1176-1204`）→
`:1189 miuiBatteryMeterIconView.onDarkChangeInternal()` → 非 legacy 的两条路径都汇到
`MiuiBatteryMeterIconView.java:504 updateProgressBackgroundBitmap(); L505 invalidate();`。
即**深浅色变化必然 invalidate 电池图标视图**（也就必然重跑 `hyperduo-draw`），但它只给自己的
子树重新着色。两个环外视图挂在 `MiuiStatusBatteryContainer` 里、是 glyph host 的**兄弟**，
不在那条链上：`MiuiStatusBatteryContainer` 完全没有 tint 处理，而 `settle()` 里的
`refreshOutTypeLabel` 又被 `onLayout` 门禁卡住（文本/字号/内边距都已相符时不动作）。

修法在 `TrioHooks.recolourOutRing(TrioState state, int ink)`：`hyperduo-draw` 钩子在
`state.refresh()` 之后把本帧的 `state.foreground()` 与 `TrioState.outRingInk` 比较，
**只在不等的那一帧**记账并 post 一次重着色 —— 给标签 `setTextColor(ink)`，给读数
`invalidate()`（它的 `onDraw` 本就每帧现读颜色，缺的只是「被要求画」）。两条细节：

- **账记在 `TrioState` 的实例字段上，不是静态字段**：状态栏与控制中心各有一个电池容器，
  前景色对两者相同，共享一个静态格会让先画的那个把变化吞掉，后画的永远停在旧色。
- **先记账再 post**：颜色抖动时不能每帧排一个 runnable；而且记下的正是本帧画出去的值，
  两边不会对不上。`foreground()` 在 `0` 时替换成默认值，所以初值 `0` 必然触发第一次比较。

`OutSignalView.onDraw` 里原先的注释「the row is invalidated when the tint changes」正是这个
bug 的信念来源，已改正。

`TrioHooks` 需要 Xposed API，桌面上编不了，所以这条规则和该文件里其它几条一样，靠
`work\outringcheck\verify.ps1` 的**源码钉子**守着：只在不等的帧记账、先记账再 post、标签拿到新墨色、
读数被要求重绘，四条成对断言，外加「错误注释不得复活」与「`outRingInk` 必须是实例字段且
`foreground()` 仍把 0 折成 `DEFAULT_FOREGROUND`」两条形状断言。**唯一无法离线替代的是真机那一下**
—— 改深浅色时环外文字是否立刻跟上。

### 双卡信号

「双卡信号」（`dual_sim_signal`）在**无 Wi-Fi 且未充电**时把一排点换成上下两排：**上排 = 卡一
（slot 0）、下排 = 卡二（slot 1）**。判定集中在 `TrioAppearance.dualSimRows(wifiInk, charging,
sims)`，渲染端只问「画几排」。触发条件三条同时成立，任一条不满足就回退成单排（当前上网卡）：

1. 开关打开；
2. `wifiInk` 为假（没有 Wi-Fi 墨迹）；
3. 第 3 个参数（`charging`）为假 —— 注意是**插没插充电器**，不是**这一帧画不画闪电**。
   闪电自己还有 `show_value` 这个前置条件（`drawsBolt() = showBolt && showValue`），两者不是一回事；
   早期实现误把 `bolt` 传进来，于是「充电中 + 关掉电量数字」会被判成未充电而画出两排。现在两处
   调用点（`Ring`/`Rect`）都传 `charging`，`work\dualsimcheck\verify.ps1` 专门盯这条。

`sims < 2` 时也回退 —— 只有一张卡有读数时，画两排会多出一排空格，不如照旧画当前上网卡。

几何在 `TrioGeometry`：`DOTS_DUAL[8][2]` 的 `0..3` 是 SIM 1 上排（`DOTS` 关于**环心** `B_CY`
镜像，不是关于设计盒中心），`4..7` 是 SIM 2 下排（`DOTS` 原值）；下排落在 `B_START`/`B_SWEEP`
没盖住的那段自然开口里，所以双排时 `gapUsed` 强制为真，否则电量弧会横穿上排点。点半径用
`DUAL_DOT_R = 7.0f`（单排仍用 `DOT_R = 5.5f`）—— 参考图的点明显更大，且 `7.0` 是在最大环线
粗细下仍能留在墨迹盒内的上限。

**12 点钟缺口在双排时要张到与底部开口等宽**（`GAP_START_DUAL = 1f - 180f / B_SWEEP ≈ 0.2581`、
`GAP_END_DUAL = 180f / B_SWEEP ≈ 0.7419`，`TrioRenderer.drawRingLayout` 里 `r.dual` 优先取这对值）。
上排是 `DOTS` 绕环心镜像，外点几乎正落在弧中线 `B_R = 51.5` 上：外点圆心到弧端点圆心只有约
10.4 个设计单位，而弧是圆帽收尾（`STROKE.setStrokeCap(ROUND)`）、帽半径 `ringStroke/2 = 7`，再加
点半径 7 共 14 ⇒ **帽与点重叠，上排看着糊成一团、且左右不对称**。原先共用的
`GAP_START_IDLE`/`GAP_END_IDLE` 只张 `0.35 * B_SWEEP = 84.9°`，而弧底部自然开口是
`360 - B_SWEEP = 117.4°`，窄了 32.5°。改成等宽后上下互为镜像，余量回到约 10 单位，与下排一致。
放大缺口不影响缺口里的文字：双排时百分比已改去环心（见下），`gapClearWidth()` 仍按 idle 缺口算。
顺带一处叠加缺陷：`valueInCentre` 原先只看 `centreValue`/`(!wifi && !type)`，双排的上排占了缺口而
`valueInGap` 会把数字送进同一位置，两排点与数字叠画；现在 `valueInCentre = hasValue &&
(a.centreValue || dual || (!wifi && !type))`，数字让位到它本来该去的环心。

**电平来源**：`MiuiStatusBarIconViewHelper.transformResId(int, boolean, boolean)` 是静态方法，
**签名里没有 subId 也没有 slot**，而且实测渲染走的是 Compose 的 `stacked_mobile` 堆叠图标
（经典 `slot=mobile` 的 `ModernStatusBarMobileView` 是 alpha 0 的死实例），所以那一层拿不到分卡
读数。模块直接问 `android.telephony`：`SubscriptionManager.getActiveSubscriptionInfoList()` 拿
每个 `getSubscriptionId()` / `getSimSlotIndex()`（subId↔slot 的对应关系运行时解析，**不硬编码**），
`getDefaultDataSubscriptionId()` 认当前上网卡，`TelephonyManager.createForSubscriptionId(subId)
.getSignalStrength()` 再取电平得到 0..4。

**取哪个电平：MIUI 的，不是 AOSP 的**（`TrioState.miuiLevel(SignalStrength)`）。MIUI 用
`SignalStrength.getMiuiLevel()` 选 `stat_sys_signal_N`，该方法**不在公开 SDK 里**（连 SDK 37 的
`android.jar` 也只有 `getLevel()`），所以用 `Refl.callByName(strength, "getMiuiLevel")` 反射取，
取不到（`NoSuchMethodError`/非 Number）才退回 `strength.getLevel()`。实机证据：Xiaomi 14 在 5G NR 下
`dumpsys telephony.registry` 两张卡都是 **`miuiLevel = 4` 而 `level = 3`**，而系统状态栏画的是
四格满格。`MobileSignalController.updateTelephony()`（jadx
`work\jadx-out\sources\com\android\systemui\statusbar\connectivity\MobileSignalController.java:495`）
走的正是 `miuiLevel = signalStrength2.getMiuiLevel();` ⇒ 如果双排照 `getLevel()` 取，就会**比系统
单排读数矮一格**，这正是用户报的「单卡满格、双卡上下都少一格」。`work\simcheck\verify.ps1` 现在
多建一份「把 `miuiLevel(strength)` 换回 `strength.getLevel()`」的负例，钉住这条。

`Context` 由绘制路径首次经过时经
`TrioState.attachContext(...)` 缓存（hook 安装时可能根本没有 context）。整个采样在
try/catch Throwable 里，失败时**保留上一轮的值**而不是清空。subId→slot 与各卡电平在
`sampleSims` 末尾一次性发布到 `sSlotLevels`/`sDataSlot`，避免跨轮询拼帧。

采样时机有两处：`refresh()` 里按 `SIMS_INTERVAL_MS = 2000L` 的帧率守卫（`sampleSimsIfDue`，仅在
`dualSim` 打开或单排链从没答过时轮询），以及 `hyperduo-signal` 拦截里的 `pollSimsNow()` —— MIUI
只在读数变化时才重绑图标，所以那次调用本身就是「per-SIM 电平已过期」的事件；它绕过帧率守卫，
但只在**确实变化**时返回 true，避免 MIUI 自己的图标抖动变成重绘循环。

**「单排 = 当前上网卡」**：`refresh()` 读电平之后，若 `sMobileLevel < 0`（图标链从没答过）而
`sSlotLevels[sDataSlot]` 有读数，则用后者兜底。这样开关关掉、单卡、或充电中时那一排仍然是
当前上网卡，而不是最后一张碰巧刷新过的卡。subId↔slot 的映射与这条兜底由
`work\simcheck\verify.ps1` 离线守着（见「测试」一节）——参考机上 `subId` 恰好等于槽位，
所以「把订阅号当槽位」这种写法在那里看起来是对的，那条工装用不相等的 id 把它逼出来。

### 信号：环内与环外堆叠

`signal_mode` 两档：`0` 环内（默认）、`1` 环外。**选项顺序即存储值**，未识别的值由
`TrioSettings` 的 `Prefs.clamp` 收敛回环内。环外模式配套两个布尔键 `stacked_signal`（默认关）
与 `data_sim_only`（默认关）。

行为的唯一判据收在 `TrioAppearance` 的两个方法上，别处一律不问裸键：

- `signalDots()` = `mobile && signalInRing` —— **环内是否画那排点**。它同时是 `foldedSlots()`
  折叠 `mobile` 的条件、`drawRingLayout` 画点阵的条件、以及 `TrioAppearance.Rect.dots` 的来源。
  原先这三处各写一次 `a.mobile`，环外一旦出现就会三处不一致（环里不画、槽却折叠了）。
- `stackedOut()` = `glyph && mobile && signalOutOfRing && stackedSignal` —— **模块是否接管环外读数**，
  也是唯一允许折叠 `mobile` 槽的环外条件。带 `mobile` 是因为「显示移动信号点」的语义是
  「哪儿都不画移动读数」，环内它已经通过 `signalDots()` 这样回答；漏掉它就会出现
  「点阵开关关着、环外堆叠却照画」——用户关掉的那一项被另一个开关复活。
- `foldsMobile()` = `signalDots() || stackedOut()` —— **Hook 侧唯一该问的问题**。它写在这里而不是
  `TrioHooks.foldedSlots()` 里，是为了让规则不依赖 Xposed API 就能被工装驱动（见「测试」）。

由此得到四条组合，**只有第一条与改动前逐字节相同**：

| `signal_mode` | `stacked_signal` | `mobile` 槽 | 移动信号由谁画 |
| --- | --- | --- | --- |
| 环内 | 任意（不起作用） | 折叠 | 模块（环内点阵，含双卡两排） |
| 环外 | 关 | **不折叠** | **系统原生信号格** |
| 环外 | 开 | 折叠 | 模块（`OutSignalView`） |
| 环外 | 开 + `data_sim_only` | 折叠 | 模块，且只画上网卡 |

环外 + 堆叠关**必须不折叠**：这条路径下模块什么都不画，折叠了就是屏幕上少一个信号格。
`work\outringcheck\verify.ps1` 用真值表把 `foldedSlots()` 在这四种组合下的输出逐一对照。
另外「显示移动信号点」（`show_mobile`）关掉时，上表落空：`mobile` 为假使 `signalDots()` 与
`stackedOut()` 同时为假，于是两个位置都既不折叠也不自绘，移动读数整项交还系统——这才是那个
开关承诺的「不画移动读数」，而不是只对环内生效。

**绘制**：`TrioRenderer.drawOutSignal(Canvas, int width, int height, int bars, int dots, int fg,
TrioAppearance a)`。它**不使用 120×120 设计空间，也不乘 `inkScale`**：环外读数不在环里，
用一个描述电池的数字去标定它，等于把状态栏高度绑到电池几何上。所以它直接用参考图自己的单位
（`TrioGeometry` 的 `stacked out-of-ring signal` 一节）：四列、列距 67、柱宽 50、柱高
`{75, 100, 125, 150}` 等差递增、柱底共线、圆头半径 = 柱宽/2；点行直径 50，与柱底留 9；
`STACK_INK_W = 251`、`STACK_INK_H = 209`。整套单位两轴同尺度，所以只算一个 `scale` 就能保住
柱阶、列距与圆头的比例关系。未点亮的柱/点用 `withAlpha(fg, a.trackAlpha)` —— 与环内点阵同一个
底纹浓度键。**读数不足 4 时不画残缺的行**：几列亮就是几格，剩下的列留在底纹色里。

**注意那个 9 是怎么来的**：参考图没有抗锯齿，边缘落在整像素上，所以柱底边缘是 238、点行上缘是
247，空带是 `238..246` = 9。若按「两段墨迹首行下标相减」去量同一张图会得到 10，整套读数就高了
一个单位。`work/outringcheck/compare.py` 存在的意义正是这一条差值 —— 它把参考图与渲染结果**都**
量一遍再比，而不是拿源码里的数字去核对源码。

**两行 vs 一行**：`outSignalLevels(boolean dataSimOnly, int mobileLevel, int[] slotLevels,
int dataSlot, int[] out)` 是唯一的读数解析处。两卡都有读数 ⇒ `out = {卡1, 卡2}`（柱 + 点）；
只有一张卡有读数 ⇒ `{那, -1}`（只有柱，无点行）；都没有时退回 `mobileLevel` 兜底。**只画一张卡
时统一画柱、不画点**，哪怕那张卡恰好是卡二 —— 一张卡不该因为它是第二张就用另一种符号。
`data_sim_only` 打开时只取上网卡：`dataSlot` 命中哪张就取哪张，`dataSlot` 未知（`-1`）或那张卡
没有读数时退回第一张有读数的卡，绝不退化成两张。

**高度与尺寸**：视图高度是 `TrioRenderer.outSignalHeight(sizeDp, density)` —— 用户设置的 dp
（`out_signal_size_dp`，默认 15）乘上显示器密度，**与那一行多高没有任何关系**，再把结果交给
`outSignalWidth(height)` 按参考图纵横比求宽。**两个函数的参照框永远是 `STACK_INK_H`（209）**，
与有没有点行无关。

**旧形式为什么被换掉（bug (g)）**：高度原先是 `outSignalHeight(anchor.getHeight(), outSignalSize)`，
即电池图标盒**活高度**的百分比。MIUI 把这一行在收起时排成 88px、拉开控制中心后变成 134px
（就是系统 `statusBars` inset 的高度，`dumpsys window displays` 实测），于是读数跟着放大
`134/88 ≈ 1.52` 倍 —— 用户报的「下拉到控制中心后这个信号还会莫名其妙的放大」正是它。dp 免疫：
密度不变，下拉多少都画一样大。

**这里修掉过一个尺寸异常**：原先 `outSignalWidth(height, dots)` 的分母随 `dots` 在
`STACK_INK_H`(209) 与 `STACK_BAR_H[3]`(150) 之间切换，而 `drawOutSignal` 里的
`scale = Math.min(width/inkW, height/inkH)` 又用同一个较矮的框 —— 两处互相印证，谁都看不出错，
结果**同一宿主高度下「无点行」的柱被放大 `209/150 ≈ 1.39` 倍**：单卡读数比双卡读数高出一大截，
切到「仅显示上网卡」时视图还会跟着跳一下。现在参照框只有 209 一个：点行只是**内容**，不是**画框**，
`outSignalInkH(dots)` 降级为「居中用的墨高」，不再参与比例。`work/outringcheck` 的 `squat` 反例
把这两行同时改回旧写法，探针必须挂掉 —— 单独改一处是**看不见的**，这正是这个 bug 能活下来的原因。

**改尺寸要不要重测**：`TrioHooks.outSignalHeight(View host)` 是唯一的换算入口，
`updateOutSignal` 的 `measure(...)` 与 `refreshOutSignal` 的变更比较都用它，滑杆改值与重新测量
不可能对目标高度各执一词；比较式因此是 `view.getMeasuredHeight() != outSignalHeight(host)`。
它**只从视图取密度**（`host.getResources().getDisplayMetrics().density`），视图的测量高度**故意
不取** —— 取了就等于把 bug (g) 请回来。

宿主尚未测量（`inkScale <= 0`）时跳过挂载，下一次 posted sync 自愈。

**挂载位置与生命周期**完全照抄环外类型标签那套（见「网络类型：环内与环外」）：挂在
`batteryContainerOf(host)` 返回的 `MiuiStatusBatteryContainer` 上，同样的 posted-only
挂载/摘除、同样的 `instanceof OutSignalView` 当标记（不用 `WeakHashMap`）、`onDetachedFromWindow`
时一并摘掉。`requestOutSignalSync` 的第一行是 `if (!stackedOut()) return;` —— 与
`requestOutTypeSync` 反过来写（后者现在写的是 `if (typeOutOfRing) return;`，是个既有 bug）。

**两个视图共用一条 strip**：信号与类型标签都排在电池图标左边，都以
`reserveOutTypeSpace(container, total)` 往容器左侧撑 padding。它们各自更新时如果都按自己的宽度
去撑，后更新的那个就会把先更新的挤掉，所以统一走 `reserveOutRingStrip(container)` —— 它读两个
子视图的 `getMeasuredWidth()` 求和（信号在前、标签在外，间距按 `out_type_margin_left/right_dp`
取「朝锚点」的那条缝），一次撑到位。定位用
`placeOutTypeLabel(container, view, anchor, offsetX, offsetY)`，标签的锚点是
`labelAnchorIn(container, meter)`：有信号就贴在信号外侧，否则直接贴电池盒 —— 阅读顺序是
网络类型 → 信号 → 电池，标签永远在最外。读数的位置偏移（`out_signal_offset_*_dp`）就加在
`placeOutTypeLabel` 的坐标上，标签因此自动跟随；标签自己用三参重载（偏移为 0）。

**位置偏移的验证**：`work/outringcheck` 的 `SuffixShot` 同时是后缀缩放的量测脚本——它把
`"5GA"` 按若干比例渲染进环心，用列游程量出末段与主段的高度比，默认 65 应落在 0.65 附近、
比例 100 应与旧版单字号完全一致。

**采样时机**：环外堆叠也要分卡读数，所以 `TrioState.refresh()` 的轮询门与
`hyperduo-signal` 里的 `pollSimsNow` 条件都从裸的 `dualSim` 放宽成
`dualSim || appearance().stackedOut()`。

## Hook 清单

| id | 目标 | 作用 |
| --- | --- | --- |
| `hyperduo-container` | `MiuiPhoneStatusBarView.onFinishInflate` | 捕获权威的状态栏图标容器 |
| `hyperduo-icon-layout` | `MiuiStatusIconContainer.onLayout` | 按子开关同步 slot + 压制原生信号视图（覆盖全部宿主）+ 采样 Wi-Fi 有无 |
| `hyperduo-draw` | `MiuiBatteryMeterIconView.onDraw` | 清画布 + 绘制字形 |
| `hyperduo-detach` | `MiuiBatteryMeterIconView.onDetachedFromWindow` | 注销宿主 |
| `hyperduo-style` | `MiuiBatteryMeterView.onBatteryStyleChanged` | 强制样式 0，还原 `mStoreRealStyle`；需要时交还原生闪电 |
| `hyperduo-charge-text` | `MiuiBatteryMeterView.updateChargeAndText` | 按 `show_bolt`/`show_value` 隐藏原生充电/百分比视图 |
| `hyperduo-cutout` | `MiuiPhoneStatusBarView.updateCutoutLocation` | 重新追加被 `setIgnoredSlots` 清掉的 slot |
| `hyperduo-signal` | `MiuiStatusBarIconViewHelper.transformResId` | 读取 Wi-Fi / 移动信号等级并触发重绘 |
| `hyperduo-mobile-type` | `MobileTypeDrawable.measure` | 读 `mMobileType`（网络类型 3G/4G/5G…）并触发重绘 |

共 9 个 hook。每个 hook 组独立容错：固件重命名某个方法只会让该组打日志跳过，不影响其余。
设置通道不占 hook —— `TrioConfig` 是注册在 remote `SharedPreferences` 上的变更监听器。

`hyperduo-mobile-type` 必须在 `chain.proceed()` **之后**再读字段：`measure()` 会把 `"5G++"`
就地改写成 `"5G"` 并另置一个 double-plus 标志（`MobileTypeDrawable.java:69`），提前读会拿到
未规范化的原值。网络类型绝不自行推断 —— `5GA` 是 MIUI 按运营商配置
（`OperatorConfig.support5GADisplay`）决定的，模块只如实显示系统给的字符串。

## 配置项参考

键名、默认值与上下界的唯一真源是 `Prefs.java`（`app/src/main/java/io/github/yixing233/hyperduo/Prefs.java`）。

| 键 | 默认 | 范围 |
| --- | --- | --- |
| `enabled` | `true` | — |
| `show_wifi` / `show_mobile` / `show_value` / `show_bolt` | `true` | — |
| `dual_sim_signal` | `false` | —（无 Wi-Fi 且未充电时改画上下两排点，上排卡一、下排卡二；见「双卡信号」） |
| `signal_mode` | `0`（环内） | 0 – 1（环内 / 环外）；**选项顺序即存储值**；见「信号：环内与环外堆叠」 |
| `stacked_signal` | `false` | —（环外时自绘柱+点；关闭则把 mobile 槽交还系统） |
| `data_sim_only` | `false` | —（环外堆叠时只画上网卡；只在 `stacked_signal` 打开时有意义） |
| `mobile_type_mode` | `0`（关闭） | 0 – 2（关闭 / 环内 / 环外） |
| `show_mobile_type` | `false` | 已废弃，只读用于迁移 |
| `swap_wifi_value` | `false` | 「电量数字居中」的存储键；名字是历史遗留，**冻结不改**（只对圆环样式有效） |
| `trio_style` | `0`（圆环） | 0 – 1（圆环 / 矩形）；**选项顺序即存储值** |
| `role_colors` | `true` | — |
| `color_critical_on_dark` / `color_critical_on_light` | `0xFFFF3B30` | — |
| `color_charging_on_dark` / `color_charging_on_light` | `0xFF34C759` / `0xFF1F8F3D` | — |
| `color_low_on_dark` / `color_low_on_light` | `0xFFF2B900` / `0xFFC99700` | — |
| `low_threshold` | `20` | 5 – 50 |
| `ring_stroke` | `14` | 4 – 16 |
| `arc_stroke` | `12` | 3 – 16 |
| `value_size` | `36` | 16 – 44 |
| `value_weight` | `700` | 100 – 900 |
| `type_size` | `32` | 16 – 44（只用于环内） |
| `out_type_size` | `32` | 16 – 64（只用于环外；上界高于 `type_size`，见 `Prefs.java` 的说明） |
| `type_suffix_scale` | `65` | 50 – 100（结尾为 A 的类型如 5GA，末尾 A 相对主字号的百分比；环内分段绘制、环外用 `RelativeSizeSpan`，两处共用同一个键） |
| `out_type_margin_left_dp` | `2` | 0 – 16（环外标签与其**外侧**的空隙，dp；RTL 下随整行镜像） |
| `out_type_margin_right_dp` | `2` | 0 – 16（环外标签与其**内侧**的空隙，dp；RTL 下同样镜像） |
| `out_signal_size_dp` | `15` | 6 – 20（环外信号读数的 dp 高度；乘显示器密度成像素，**不跟随电池容器高度**，只用于环外 + 堆叠信号） |
| `out_signal_offset_x_dp` | `0` | -12 – 12（环外读数左右偏移的 dp；正数向右，标签跟着走） |
| `out_signal_offset_y_dp` | `0` | -12 – 12（环外读数上下偏移的 dp；正数向下，只动读数不改占位） |
| `type_weight` | `700` | 100 – 900（环内/环外共用） |
| `track_alpha` | `56` | 0 – 255 |
| `debug_log` | `false` | — |

## 已知限制

- 仅在 HyperOS 4 / 小米 14（`CP2A.260605.016`，SDK 37，`OS4.0.0.27.XNCCNXM`）上验证过；其他
  固件可能类名或字段名不同，届时会退化为对应 hook 组打日志跳过。
- 折叠集合按类名/slot 字符串硬编码（`wifi` / `mobile` / `stacked_mobile`）。若后续固件引入新的
  移动槽位名，需要在 `TrioHooks.MANAGED_SLOTS` 里补一项，并在 `foldedSlots()` 里归属到对应的
  子开关；`DEBUG_DUMP = true` 可以打印出实际 slot 名。
- 子开关的「交还」依赖 `MiuiStatusIconContainer.ignoredSlots` 可读可写（反射）：读不到该字段时
  `syncSlots` 退化为只追加，于是子开关只能压制、不能交还（等同旧行为）。
- 原生充电闪电的交还依赖 `mBatteryStyle` 被直接改写：这在 MIUI 自身不重新派发 style 变更时成立。
  若用户在系统设置里改了电池样式，`hyperduo-style` 会重跑并重新按子开关决定是否交还。
- Wi-Fi 与移动信号等级来自 `MiuiStatusBarIconViewHelper.transformResId` 的原始 resId
  （`getResourceEntryName` 解析尾位数字）；若固件改走别的绑定路径，环内会退化为不显示对应
  弧/点（不会崩溃）。Wi-Fi 的**有无**另有实时采样兜底，不受此限制。
- 移动信号开关无法实时反映：`isIconVisible()` 在淡出的移动视图上仍为 true，故移动点只在
  resId 变化时更新。
- 「双卡信号」的分卡电平来自 `android.telephony`（`SubscriptionManager` / `TelephonyManager`），
  而**图标那条链给不出 slot**：`transformResId(int, boolean, boolean)` 是静态方法，签名里既没有
  subId 也没有 slot；Compose 的 `stacked_mobile` 堆叠图标与 Dagger 里的
  `MiuiMobileIconInteractorImpl`（它有 `subId` 与 `phoneId`）模块都够不着。因此该开关依赖
  SystemUI 进程本身持有 `READ_PHONE_STATE` / `READ_PRIVILEGED_PHONE_STATE`（实测该进程
  `sharedUserId="android.uid.systemui"`，模块不额外声明权限）。取数失败时只在日志里留痕，
  字形退回单排，不会崩溃。
- 修改环线粗细 / 数字字号等几何参数会立即生效，但**不会重新测量**原生电池视图的
  28dp×20dp 尺寸；极端值下字形可能被裁切。

## 目录

```
app\build.gradle.kts                   Gradle 模块配置（namespace io.github.yixing233.hyperduo）
app\src\main\java\io\github\yixing233\hyperduo\   hook 源码（Java）
app\src\main\java\io\github\yixing233\hyperduo\ui\ 设置界面（Kotlin + Compose + Miuix）
app\src\main\res\                      strings / themes / 图标
app\src\main\res\xml\file_paths.xml    FileProvider 路径（更新器下载目录）
app\src\main\resources\META-INF\xposed\  java_init.list / scope.list / module.prop
release.ps1                            构建 release APK 并上传到 GitHub Release
docs\                                  开发文档与 README 配图
build.gradle.kts / settings.gradle.kts / gradle.properties   构建配置
install.ps1                            安装 + 打开设置 + 日志
build.ps1                              旧离线构建（已弃用，保留查阅）
work\jadx-out\                         MiuiSystemUI 反编译源（分析用，不入库）
work\unpacked\                         MiuiSystemUI 解包资源（分析用，不入库）
work\geocheck\                         离线段渲染与解析校验（不入库）
work\gapcheck\                         电量环缺口像素校验与前后对照图（不入库）
work\outringcheck\                     环内/环外与两个信号开关的真值表校验、环外读数与参考图的数值比对（不入库）
work\preview\                          离线 JVM 预览工装（不入库）
work\overview\                         全部支持样式的状态总览图（不入库）
.tools\                                JDK / Gradle / SDK / 本地 Maven 仓库
.ref\                                  参考模块与 libxposed 源码（分析用，不入库）
```

## 测试

仓库**目前没有任何自动化测试**（`app\src` 下只有 `main`，没有 `test` / `androidTest` source set）。
回归验证靠两条手工通道：

1. **离线 JVM 预览**（`work\preview\`，不入库）：一套只服务工装的 `android.*` 桌面垫片 + 入口
   `PreviewMain`，直接编译 `Prefs.java` / `TrioSettings.java` / `TrioGeometry.java` /
   `TrioAppearance.java` / `TrioRenderer.java` 出图，不需要设备就能看几何。工装内嵌的
   `SharedPreferences` 替身**必须叫 `FakePrefs`** —— 叫 `Prefs` 会遮蔽真正的
   `io.github.yixing233.hyperduo.Prefs`，导致常量解析失败。渲染器依赖的外观规则全部在 `TrioAppearance` 里，
   所以它必须和 `TrioRenderer` 一起编进去，否则 `work\overview\run.ps1` 会以
   `cannot find symbol` 失败。垫片出图与真机不符时，**先怀疑垫片**（历史上
   `Canvas.restoreToCount` 的语义错实现过一次，症状是画布变换泄漏到后续所有绘制；矩形样式的
   `drawRoundRect` / `Path.addRoundRect` 也一度只有真机 API、垫片缺失）。
2. **上机验证**：`install.ps1` 装机后重启 SystemUI，看日志与状态栏实拍。

另有 `work\gapcheck\verify.ps1`（不入库）专门盯**电量环缺口**：它把同一份 `GapProbe` 跑两遍 —— 一遍
编工作树的 `TrioRenderer`，一遍把 `batteryRing` 单独换回旧算法（其余代码不动，免得测到另一个程序）。
`GapProbe` 用反射直接驱动真渲染器并沿环心线回读像素，按「可见弧」区间模型逐档断言 0–100% 的左右末端、
缺口内是否无墨，再走 `drawInto` 的槽位决策做端到端确认。固定版必须 `exit 0`、旧算法必须
`exit 1`（旧算法在 45/55/60/70/75% 上共 42 项不符）——**两边都跑才说明探针真的在测这个 bug**；
只跑通过的那一边等于没有反例。探针同时产出 `work\gapcheck\out\mouth-{old,new}.png` 对照图。

另有 `work\slotcheck\verify.ps1`（不入库）盯**居中模式的槽位优先级**。它把同一份 `SlotProbe` 跑两遍
（工作树一份、`git show 312ae00:` 重建的旧渲染器一份，旧源必须先断言含 `boltCentreOffsetX`、不含
`drawGapType`）。**基线必须是写死的修订号，不能是 `HEAD`**：修复未提交时 `HEAD` 正好是旧版，
提交之后 `HEAD` 就是修复本身，探针会拿自己和自己比、然后「证明」一件它没测过的事——本脚本在
提交后确实这样假绿过一次，是被那句 `boltCentreOffsetX` 断言拦下的，所以那道断言不是多余的。
判定用**无色不变量**：圆心圆盘（`CENTER_CY` 处半径 18 设计单位）内的像素在「有闪电」与「无闪电」
两种渲染下必须逐像素相同——低电量角色色 `0xFFF2B900` 与快充琥珀色 `0xFFFFBA28` 太近，
按颜色计数不可靠，所以只比像素。渲染时置 `cfg.roleColors = false`，否则数字颜色会随充电标志变化而
掩盖结论。另有对照组防空洞：未居中 + Wi-Fi 3 与居中 + Wi-Fi 3 的圆心盘哈希必须不同，否则「有/无闪电
相同」什么都没测。新版必须 `exit 0`、旧版必须 `exit 1`（旧版 6 项不符：闪电落在圆心、缺口无琥珀、
Wi-Fi 0..3 四个哈希各不同、环内类型扰动圆心、缺口以下也被改动），同时要求 12 个非居中状态在新旧
两版之间哈希逐行相等，锁住「本改动只影响居中模式」。探针产出 `work\slotcheck\out\slots-{old,new}.png`
六格对照图，格下说明走 `caption` 逐字换行并对超宽/超高一并 `throw`（同样是「宁可失败也别裁字」）。

另有 `work\dualsimcheck\verify.ps1`（不入库）盯**双卡开关的判定规则本身**。`gapcheck` 与 `slotcheck`
测的是电量弧算术与居中优先级，都不覆盖「什么时候才该画两排」；`overview` 只画出一个双卡格，证明
的是**两排画得出来**，不是**只在该画的时候画**。它把同一份 `DualSimProbe` 跑**三遍**，每次只换一个
文件：`fixed` 编工作树；`bolt` 把两处 `dualSimRows` 调用点从 `charging` 换回 `bolt`；`narrow` 把
`GAP_START_DUAL`/`GAP_END_DUAL` 退回 `GAP_START_IDLE`/`GAP_END_IDLE`（即用户投诉的上排拥挤状态）。
探针分五组断言：`rule()` 逐条查 `dualSimRows` 的真值（无 Wi-Fi 无充电双卡为真；有
Wi-Fi 墨迹 / 充电 / 只读到一张卡 / 一张都没读到 / 开关关 / 移动信号表关，六种情形全假）；`layoutFlag()`
直击那个真缺陷——`showValue=false`（于是 `drawsBolt()` 为假）时插着充电器，`Ring`/`Rect` 的 `dual`
标志必须仍为假；`pixels()` 用 MD5 逐像素哈希要求「被抑制的帧」与「开关关掉」的帧**完全相同**（这才
叫回退成单排），并要求该画时两者必须不同；`rows()` 按 `DOTS_DUAL` 与 `inkScale` 复算 8 个圆心、读
圆心核心区 alpha，锁住**上排 = slot 0**；`separation()` 把上排拥挤变成可断言的量——对每个点从其
**中心像素**做迭代式 4-连通 flood fill，要求连通域面积约等于单个点（`area / (π·DUAL_DOT_R²·scale²)
< 2.5`），因为一旦与弧的圆帽连成一体，面积会涨到数倍（`narrow` 版实测外侧两点为 **8.22 / 5.58** 个
点面积，`fixed` 版 8 个点全是 **0.97..0.99**）。`fixed` 必须 `exit 0`，`bolt` 与 `narrow` 必须
`exit 1`（实测分别 3 条、2 条不符）。

要给用户看这次修复的前后差别，用 `work\dualsimcheck\before-after.ps1`：它编两遍（工作树 vs 把两个
`GAP_*_DUAL` 换回 idle 值），再用 `TopZoom.java`（12 点钟区域放大 3 倍）与 `DualSimShot.java`
（四态总览）各出一张，最后由 `Panels.java` 拼成上下两栏的 `out\top-before-after.png` 与
`out\dualsim-before-after.png`。**一次编译只能表现一个几何**，所以对照必须两次编译 + 外部拼图。

**采样坑（踩过一次）**：`rows()` 最初取 26px 窗口内的**最大** alpha，结果 `{2,4}` 时最外侧的上排点
被判成亮——它在 720px 图上是 `(522,102)`，离圆环弧只有约 25px，窗口把弧吃了进来。正确做法是读圆心
**核心区**取**最小** alpha：亮点为 255、轨道点恰为 `trackAlpha`，两者判然可分。只看窗口内亮点计数或
最大值都会把「紧挨弧的点」读反。

另有 `work\outringcheck\verify.ps1`（不入库）盯**环内/环外与两个新开关的真值表**。它编
`work\outringcheck\src\io\github\yixing233\hyperduo\OutRingProbe.java`，分五组：

- `foldRule()` 逐条查 `TrioAppearance` 的表面：环内 `signalDots()`/`foldsMobile()` 为真而
  `stackedOut()` 为假；环外 + 堆叠关三者**全假**（并显式断言「mobile 槽原样留着，MIUI 才有图标可画」）；
  环外 + 堆叠开为 假/真/真；再叠 `data_sim_only` 仍为 真/真；总开关关、`show_mobile` 关各自
  「不折叠也不自绘」；最后两条查 `Rect.dots` 与 `Ring.dual` 在环外都为假。
- `inRingUntouched()` 是**环内逐字节不变**这条承诺的可执行版本：3 组槽位读数 × 2 种双卡开关 ×
  2 种样式共 12 组，要求 `signal_mode=环内` 时「两个新开关都关」与「都开」的 720×720 渲染哈希**相等**。
- `reading()` 查 `outSignalLevels` 的读数解析（两卡取槽序、单卡只画柱、`dataSimOnly` 命中上网卡、
  上网卡沉默时退回另一张已应答的卡等 11 组）。
- `geometry()` 查参考图比例式（列数、柱高严格递增、`STACK_INK_W/H` 与 `STACK_BAR_H[3]+gap+D` 的关系），
  并查**盒子始终按 `STACK_INK_H` 定比例**：`outSignalWidth(h)` 与 `h*STACK_INK_W/STACK_INK_H` 相等、
  高度为 0 时宽度为 0，以及 `outSignalHeight` 的 dp 语义（`15dp × density` 就是像素高、密度越大
  像素越多、非正 dp 或非正密度得 0、下限仍留 1 px）。
- `pixels()` 在真实画布上验柱底共线、最高柱在最右、未点亮列既不透明也不与点亮列同色、点行在柱底下方，
  以及**同一行上有无点行的最高柱一样高**（尺寸异常的回归断言）。

三条反例构建都由 `[regex]::Replace` 从工作树文本生成：`refold` 把 `foldsMobile()` 尾上 `|| signalOutOfRing`
（环外一律折叠，模拟「交还系统却把系统图标摘了」），`inert` 把 `signalDots()` 尾上 `&& !stackedSignal`
（环内也被堆叠开关压住），`squat` 同时改回 `drawOutSignal` 的 `scale` 与 `outSignalWidth` 两行的旧分母
（只改一处是看不见的 —— 两处互相印证，正是尺寸异常能长期存活的原因）。`fixed` 必须 `exit 0`，
三条反例必须 `exit 1`（实测分别 3 条、14 条、3 条不符；`squat` 实测最高柱 51 vs 72 px，即 1.39 倍本身）。
脚本开头有一道**源码钉**：`TrioHooks.java` 里若已不存在 `if (a.foldsMobile())` 就直接 `throw`——
否则探针会变成在测一条没人调用的规则。同一段还有**环外前景色跟随深浅色**那一批钉子（`TrioHooks`
桌面上编不了，只能钉源码）：只在不等的帧记账、先记账再 post、标签 `setTextColor(ink)`、读数
`signal.invalidate()`，四条成对断言，外加两条形状断言 —— `OutSignalView.onDraw` 上那句
「the row is invalidated when the tint changes」的错误注释不得复活，`TrioState.outRingInk` 必须是
实例字段（`static` 即报错）而 `foreground()` 必须仍把 `0` 折成 `DEFAULT_FOREGROUND`（否则初值
`0` 会与首帧读数相等，第一次着色被当成「没变」而跳过）。七种回归形态都实测能把钉子碰响。

**为什么尺寸断言不能自己算取样列**：`squat` 第一次跑出来是「51 vs 0 px」，因为探针用**正确**的公式
去算最高柱的横坐标，而那个构建的几何恰恰是错的，取样列整个落在柱外，量到的是空画布。断言「两边一样大」
时，取样点必须与几何无关（取全画布最高的亮柱，`tallestRun`），否则一条本意是抓尺寸错的断言会退化成
在抓「那里没东西」。

**为什么断言「未点亮」要挑对列**：`pixels()` 最初在第一列取点验底纹，而三点读数下第一列的点是亮着的，
于是那条断言恒假，看起来像产品缺陷。改成在**第四列**取样（三点的读数下第四列必暗）并补一条
「未点亮的点仍在屏幕上（alpha > 0）」才通过。断言「没有」必须取**确定没有**的位置。

**与参考图的数值化比对**（`work\outringcheck\OutSignalShot` + `compare.py`，不入库、不参与 `verify.ps1`）：
`OutRingProbe` 只能证明源码自洽 —— 它拿 `TrioGeometry` 的常量去核对 `TrioRenderer` 用它们画出来的像素，
两边同时错也照样通过。要抓这类错只能出图：`OutSignalShot` 把三种读数按**参考图 1 单位 = 1 像素**画在
恰好等于各自 ink box 的画布上（`scale` 恰为 1，没有任何取整问题），产出 `work\outringcheck\out\out-signal.png`；
`compare.py` 再用**同一套量法**分别量参考图与渲染结果，比列数、列距、柱宽、四根柱高、点行直径、
柱底到点行的空带。**两边都量**是关键：不拿源码里的数字去核对源码。

这条比对当场抓到一个真错误：空带本是 **9** 而不是 10。参考图无抗锯齿、边缘落在整像素上，柱底最后一行
墨是 237（下边缘 238）、点行第一行墨是 247，空带 `238..246` = 9；按「两段墨迹首行下标相减」量会得到 10。
原先的 `STACK_DOT_GAP = 10f` 因此让整个读数高一个单位（`STACK_INK_H` 210 → 应为 209）。改成 9 后
`compare.py` `exit 0`，三格（两卡 4 柱+4 点 / 单卡 4 柱 / 仅上网卡 2 柱）的量测与参考图逐项相等。

另有 `work\simcheck\verify.ps1`（不入库）盯**订阅号到槽位的映射**与**单排兜底**——这是双卡信号里唯一
无人覆盖的一段。上一条 `dualsimcheck` 验的是「什么时候画两排」，`overview` 验的是「两排画得出来」，
而**哪一排是卡一**、以及**只画一排时画哪张卡**，都发生在 `TrioState.sampleSims` 里，只有真机
SystemUI 进程会跑到它。它把真实的 `app\src\main\java\io\github\yixing233\hyperduo\TrioState.java`（不是
`work\preview` 里那个同名桌面替身）编进一套 `android.telephony.*` 垫片里跑两遍：一遍编工作树，一遍把
`final int slot = info.getSimSlotIndex();` 换成 `info.getSubscriptionId()`（**只动这一行**）。探针分七组：
`plumbing()` 查无 context 时惰性、attach 后才通；`mapping()` 是核心——**故意用与槽位不相等的订阅号**
（`subId 9 → 槽 0`、`subId 5 → 槽 1`）断言电平落在**槽**而非订阅号上，另加反向槽序与一个槽位越界的
订阅（不贡献任何一排、但 `sDataSlot` 仍解析出来）；`fallback()` 断言图标链静默时取**当前上网卡**而非
槽 0，且上网卡自己没读数时**不从槽 0 借**；`gating()` 断言开关关且图标链已答时**根本不去问 telephony**；
`degrade()` 逐项拔掉订阅服务 / telephony 服务 / 订阅列表 / `createForSubscriptionId` / `getSignalStrength`，
要求**要么保留上一轮读数、要么降级为 -1，绝不误报**，并锁住「电平 0 是真实读数（有卡无服务）」与
「0..4 之外一律拒绝、不钳制」；`pollNow()` 要求事件驱动刷新**只在真变化时**报变化；`miuiLevel()`
（第五组加的那批）钉住**读的是 MIUI 的电平而不是 AOSP 的**——见「双卡信号」一节的说明。
现在共 **47 条断言**，且是**三构建**对照：固定版必须 `exit 0`（47 条全 `[ok]`）、
「订阅号当槽位」版必须 `exit 1`（实测 27 条不符）、「把 `miuiLevel(strength)` 换回 `strength.getLevel()`」
版必须 `exit 1`（实测**恰好 4 条**不符，且正是那 4 条 MIUI 断言 ⇒ 分离度精确到条）。

**门控判定的坑（踩过一次）**：`gating()` 一开始对每次 `refresh()` 都新建 host，结果它是被 2 秒限流
而不是被开关拦下的——断言会**因为错误的原因通过**。必须复用同一个 host、并在两次 `refresh()` 之间把
`SystemClock.now` 步进 2 秒以上，让限流不再起作用，测的才是门控本身。

前三条工装都直接驱动渲染器，所以**渲染器的私有签名一改就会连带弄坏它们**，而它们又不在
`:app:compileDebugJavaWithJavac` 里，改完主代码不跑一遍是发现不了的。已知会咬人的三处：`drawBattery`
在 `TrioAppearance` 抽取后从收 `TrioSettings` 改成了收已解析的 `TrioAppearance`；`drawInto` 在双卡信号
里于 `mobileType` 之前插入了 `int[] slotLevels`；三个工装的编译源清单里还必须带上
`TrioAppearance.java`（渲染器把外观规则全问它），但**不能**带 `PreviewMain` / `OverviewMain`
——`slotcheck` 的旧版构建会拿它们去调旧渲染器，编译期就失败。因此 `GapProbe` 按新签名反射，
`SlotProbe` 则在运行期先找带 `int[].class` 的 `drawInto`、找不到再退回旧签名并补 `null` 占位，
这样同一份探针源码能同时链上新旧两版渲染器。`DualSimProbe` 走的是当前签名，因此它只编当前
`TrioRenderer`，靠换 `TrioAppearance` 造反例。

`simcheck` 是这里面唯一**不碰渲染器**的：它编的是 `TrioState.java`，因此受渲染器签名变化影响的是
反过来的方向——它不能带上 `work\preview\src` 里的 `TrioState.java` / `TrioConfig.java`（同名桌面替身，
会撞类），只能借那里的 `SharedPreferences.java` 与 `Bundle.java` 两个纯垫片；`TrioConfig` 则由
`simcheck\src` 自带一份**可设置的**替身（开关要在运行中途翻），`Refl.java` 是纯 JDK 所以直接编真源。
**替身要跟住真 `TrioConfig` 的公开面**：加 `appearance()` 那次（`refresh()` 改读
`appearance().stackedOut()` 来决定要不要为环外读数采分卡电平）替身没跟上，`simcheck` 立刻以
`TrioState.java:199: 错误: 找不到符号` 失败——所以替身补了 `appearance()`（`TrioAppearance.of(snapshot)`，
该类是纯 Java、无 android 端口，能直接编），并把 `TrioAppearance.java` 加进编译源清单。
`TrioConfig` 或 `TrioState.refresh()` 的采样门控一改，就要先跑这条工装再谈别的。
改完渲染器请把五条 `verify.ps1` 都跑一遍；动过 `TrioState` 的采样逻辑则补跑 `work\simcheck\verify.ps1`；
动过 `TrioAppearance` 的规则谓词则补跑 `work\outringcheck\verify.ps1` 与 `work\dualsimcheck\verify.ps1`；
**动过环外读数的几何常量**（`TrioGeometry` 的 `stacked out-of-ring signal` 一节）还要重跑出图工装
`work\outringcheck\OutSignalShot` + `compare.py` —— 那批常量只有它与参考图逐项比对过，五条
`verify.ps1` 里没有一条能发现「常量整体偏一个单位」这类错。

另有 `work\overview\run.ps1`（不入库）：把渲染器**实际支持的每一个样式**画成一张状态总览图
`work\overview\out\overview.png`（1788×2248，3 组共 26 格）。它复用 `work\preview` 的桌面垫片，
每格都以 `TrioSettings.defaults()` 为底再叠加该格的单项 tweak，因此代表的始终是出厂外观。用途有二：
一是改渲染器后一眼看全所有样式的回归（新样式没进这张图就等于没被清点），二是给用户/文档出图。
出图脚本会打印被编译的 `TrioRenderer.java` 的 SHA256，便于确认图对应哪份代码。

**当前支持样式的清单**（即总览图的三组，也是渲染器能力的边界）：

- 电池（顶部）10 格：充电中 / 快速充电 / 用电中（数字）/ 低电量 / 低电量模式 / 危险电量 /
  已充满 / 只显示圆环 / 无 Wi-Fi 时数字居中 / 无 Wi-Fi 时充电且数字居中。
- Wi-Fi（中部）7 格：已连接 3 格 / 2 格 / 1 格 / Wi-Fi 开启未关联 / Wi-Fi 关闭或不可用 /
  电量数字居中 / 居中后充电（闪电在缺口）。

注意两个「居中」不是一回事，别混：电池组里的「**无 Wi-Fi 时**数字居中」是**自动**行为
（`wifiInk` 为假时数字自己掉进圆心，没有开关），Wi-Fi 组里的「电量数字居中」才是那个**设置项**
（`swap_wifi_value`，用户手动打开）。
- 移动信号与网络类型 9 格：信号 4 格 / 2 格 / 无信号 / 双卡信号（4 / 2 格）/ 网络类型环内 4G /
  环内 5G / 环内 5GA / 类型关闭 / 类型环外。**环外类型不由本 Canvas 绘制** —— 它由 `TrioHooks`
  另建的 `OutTypeLabel extends TextView` 画在电池表左侧，所以那一格是手绘标签示意，不是渲染器输出。
  双卡格故意取两卡电平不同（4 / 2），这样它若悄悄退化成单排会「看起来就不对」，而不是看起来像
  一个合理的单卡读数。双卡格在图上仍走圆环样式（矩形样式的两列本来就各认一张卡）。

另有一条只在双卡手机上会露面的形状：「双卡信号」（`dual_sim_signal`，见「双卡信号」一节）。
它在**无 Wi-Fi 且未充电**时把一排点换成上下两排（上排卡一、下排卡二），圆环样式下两排落在
圆环的两个缺口里（上排那个缺口会被张到与底部开口等宽，否则点会与环的圆帽糊在一起），
矩形样式下两列各认一张卡。判定收在 `TrioAppearance.dualSimRows` 一处，渲染端只问「画几排」。

参考的 macOS 版总览图里还有两组本模块**没有实现**，不要误画：**蓝牙音频**（4 格）与**音量**（7 格）
在 `app\src\main\java\io\github\yixing233\hyperduo` 下搜 `volume|bluetooth|audio|headset|earbud` 无任何匹配。
此外参考图的「已连接电源，未充电」也无对应状态 —— 渲染器只有充电 / 未充电两态，不区分插电未充。

## 验证记录

- 几何离线校验（渲染）：`work\geocheck\geocheck.png` 通过。
- 信号名解析校验：112 个资源名逐条判定正确。
- 上机（小米 14 / HyperOS 4）：9 个 hook 全部安装（日志 `HyperDuo installed, hooks=9`），无
  `AndroidRuntime:E`；状态栏 / 锁屏 / 控制中心的原生 Wi-Fi、移动（含 `stacked_mobile`）、电池
  图标均被抑制；屏幕中央不再残留游离的「5G」；Wi-Fi 关闭时不画弧；深色背景下前景色取样正确；
  切换设置不重启 SystemUI 即生效。
- 居中开关（存储键 `swap_wifi_value`）上机双向验证（小米 14 / houji / Android 17 / HyperOS 4 / KernelSU，
  `versionCode=10300` / `versionName=1.3`，`adb install -r` Success、SystemUI 重启后
  `HyperDuo installed, hooks=9 enabled=true`、无 `FATAL EXCEPTION`）——三态各取一帧，8x 放大目视：
  - `work\ondevice\v13-ring-8x.png`（充电 + Wi-Fi 关闭，真实 100%）：缺口里是**小闪电**，
    圆心是 **100**——即「数字优先级最高」，不是旧版的大闪电占圆心。
  - `work\ondevice\v13-charge-wifi-8x.png`（充电 + **Wi-Fi 已关联** `OpenWrt` / RSSI `-38`）：
    缺口只有小闪电，**两条 Wi-Fi 弧整组不画**（验证 `cfg.showWifi && (!centred || wifiInGap)` 这道门）。
  - `work\ondevice\v13-wifi-8x.png`（`dumpsys battery unplug` + Wi-Fi 已关联）：**弧缩进顶部缺口**、
    数字仍在圆心。
  截图 `v13-charging.png` / `v13-charge-wifi.png` / `v13-wifi.png`，裁图用
  `& "$env:JAVA_HOME\bin\java.exe" -cp work\overview\classes Crop <src> <dst> 1055 20 90 85 8`。
  取完把 Wi-Fi 关回 `off`、`dumpsys battery reset` 已确认（`USB powered: true` / `level: 100` 为真实状态）。
  按字节复核「设备上跑的就是这一份」：`adb pull` 设备 `base.apk` 与
  `app\build\outputs\apk\debug\app-debug.apk` SHA256 逐字相同
  （`8F951FD65CC7B3625AA881FE021C96D3E701A7D64162BFB5829BA953FDA177C8`，33019398 B）。
  **修正记录**：早期实现让「居中 + 充电」时大闪电占圆心、数字缩到缺口，与「数字在居中模式下优先级最高」
  的语义相反；1.2 上机截图正是这个错误形态。`work\slotcheck\verify.ps1` 用同一探针在新旧两版之间对照
  （新版 exit 0 / 旧版 exit 1，6 项不符），并把 12 个非居中状态锁成逐像素不变。
  **探针基线必须写死修订号**：它原先按 `HEAD` 重建旧版，修复提交后 `HEAD` 就是修复本身，
  会自己跟自己比却照样打印 `VERIFIED`——现已固定为 `$BaseRev = '312ae00'`。
- 更新器上机验证：无 Release 时点「检查更新」显示「作者尚未发布任何正式版本」（404 视为正常
  空答案而非失败），界面不卡死、不误报。
- 更新器端到端验证（真机）：把 debug 包故意装成 0.9(900)，走完「检查更新 → 下载更新 → 安装」，
  版本变为 1.0(10000)。设备到 github.com 时快时慢（同一 asset 在 1.5s 与 15s 超时之间摇摆），
  故下载先打 `api.github.com/repos/…/releases/assets/<id>`（配 `Accept: application/octet-stream`，
  实测 10/10 成功），失败再退到 `browser_download_url`，每个 URL 重试 3 次。
- 安装被拒的两条路径分开处理：`InstallResult.NeedsPermission`（缺「安装未知应用」授权）弹出提示
  并跳到该设置页；`InstallResult.NoInstaller`（设备上没有能处理安装 intent 的应用）只弹提示，
  不跳设置页 —— 授权已经给出时把用户送去设置页会让他面对一个无处可改的界面。
- 「查看仓库」：更新卡片最后一行，**是卡片里唯一无条件存在的行**；卡片其余各行都遵守「只有动作
  当前可行时才出现」的规则，这一行故意破例——用户最需要仓库的时刻恰恰是卡片答不上来的时候
  （作者尚未发布 Release、检查失败、changelog 说得不够）。放在最后因此不打断
  「检查更新 → 下载更新 → 安装」的主线。地址取自与 `API_LATEST` / `RELEASES_PAGE` 同一个
  `REPO` 常量（`REPO_HOME`），三者不可能漂移；与「打开发布页面」互补，一个看源码一个看 Release。
  `openReleasePage(url)` 因该行为而更名为 `openInBrowser(url)`（它本来就是 URL 泛型的，
  旧名字对第二个 URL 是谎话），没有留下别名。`assembleDebug` 通过，`aapt2 dump resources`
  确认 `update_repo` / `update_repo_summary` 在中英两个 locale 都已打包。**未上机验证**（当时无
  adb 设备）：行的渲染、点击后浏览器打开仓库主页这两条仍需真机确认。
- release 构建注入验证：`-PhyperduoVersionName=1.2 -PhyperduoVersionCode=10200` 产出的 APK
  经 `aapt2 dump badging` 确认 `versionCode='10200' versionName='1.2'`；解包后 `dexdump` 确认
  hook 侧与更新器全部类均未被 R8 剥离。
- 重启按钮：`assembleDebug` 通过，`aapt2 dump resources` 确认 `restart_title` / `restart_summary` /
  `restart_confirm` / `restart_running` / `restart_done` / `restart_failed` / `cancel` 七个串在中英
  两个 locale 都已打包（`values` 与 `values-en` 均命中）。上机（小米 14 / HyperOS 4 / Android 17 /
  KernelSU）确认：顶栏右侧 `Refresh` 图标按钮渲染正常、与状态栏图标不冲突；点开后确认弹窗按
  `OverlayDialog` 形态渲染（标题、正文、灰「取消」+ 蓝「重启」双按钮）；`am force-stop` 后冷启动
  不误触，`restarting` 初值正确。root 通路单独验证通过：`su -c 'killall com.android.systemui'`
  退出码 0，SystemUI 随后重新起来。**弹窗落在屏幕底部而非居中**——这是 Miuix 的限制不是本模块的
  bug：`DialogContentLayout` 只在窗口宽 ≥ 840dp 时才用 `Alignment.Center`，手机上恒为
  `BottomCenter`，且 `OverlayDialog` / `WindowDialog` 都没有暴露 alignment 参数。**仍待补**：
  「拒绝授权 → 弹窗留在原位并显示失败」「kill 进行中取消/确认按钮均不可点，点击外部与返回键都不
  关闭弹窗」两条交互路径。
- 颜色恢复默认确认：`assembleDebug` 通过，`aapt2 dump resources` 确认 `color_reset_dialog_summary` /
  `color_reset_confirm` 两个新串在中英两个 locale 都已打包。上机（小米 14 / Android 17 / KernelSU）
  确认颜色页「恢复默认」行渲染正常；**弹窗本身的上机截图未取到**——验证过程中设备 USB 掉线
  （`adb devices` 变空），点击「恢复默认」后的那一步没能截到图。同样的 `OverlayDialog` + 取消/确认
  双按钮形态已在重启弹窗上截到过（见上一条），但这两条确认通路各自仍需一次真机点击确认。
- 顶栏模糊：`assembleDebug` 通过；merge 后的 manifest（`processDebugMainManifest` 与
  `processDebugManifestForPackage` 两处）确认 `<uses-sdk android:minSdkVersion="29"
  android:targetSdkVersion="36" />`，即 `tools:overrideLibrary` 生效、minSdk 未被 blur 的 33 顶上去。
  上机（小米 14 / Android 17 / API 37）静置与滚动两态截图确认：顶栏后是页面内容被磨砂糊开
  （预览卡片图标透出为色块），不再是纯色底；滚动时卡片内容从标题下穿过并保持模糊。
  **未在 API 29–32 设备上验证**——该分支只能确认编译与 manifest，实际分支是否退化成不透明底依赖
  真机或模拟器。
- 门控提示（Tooltip）：语义层用 `uiautomator dump` 验证——总开关关闭时恰好 3 个
  `long-clickable="true"` 节点（充电时显示闪电 / 显示网络类型 / 电量数字居中），打开后为 0，
  与「只有确实存在未打开前置开关的行才响应长按」的预期一致。**提示气泡本身未截到图**：
  取图前设备掉线，长按路径未走通；`gateHint` 的文案拼接由代码路径保证。
- 应用内提示条：`assembleDebug` / `compileDebugKotlin --rerun-tasks` 均通过且无警告。
  **提示条本身未截到图**——它只在更新安装失败或重启完成时弹出，前者需要远端存在新 Release，
  后者会真的重启 SystemUI，都不适合为取图而触发。时长常量由反编译 Miuix 确认
  （`SnackbarDuration.Short` = 4000ms、`Long` = 10000ms，见 `SnackbarKt.toMillis`）。
- miuix-nav 接入：`assembleDebug` 通过（JDK 21 + JVM target 21）。解包 APK 后按 dex 字符串确认
  三件事都进了包：`miuix/kmp/nav/core/NavDisplayKt` 与 `NavController`、`NavBackStackKt`
  （`classes6.dex`），以及本模块的 `Route$General`、`SettingsPage`（`classes4.dex`）。
  已 `adb install -r` 装机成功（小米 14，设备在线）。
  **导航的运行时行为未做视觉验证**——按用户要求本轮不截图、不重启 SystemUI。因此以下各条
  只有编译期与打包期的证据，实际观感待补：分区切换的转场动画、系统/预测返回手势的分区回退、
  顶栏模糊在 `NavDisplay` 改为内容宿主后是否仍正确采样、
  以及标签条高亮与栈顶路由是否始终一致。（转场后来返工成零位移交叉淡入，见下一条；
  「切标签后落在哪里」后来由用户定成一律回到顶部，见第四次返工。）
- 导航转场（第一次上机后返工）：首版直接用了库预设 `NavTransitions.MiuixDefault`，用户截图
  判定「问题太大了」——整页被横推、被覆盖分区残留在左边缘。改为 `SectionTransition`
  原地交叉淡入 + `SectionEffects`（无裁角、无 scrim），`compileDebugKotlin` / `assembleDebug`
  均通过且零警告，已 `adb install -r` 装机。**这次改动同样只有编译期证据**：`adb shell input
  tap`/`keyevent` 与 `adb shell monkey -f` 在本机都被 MIUI 在 OS 层拦下，逐字报错
  `java.lang.SecurityException: Injecting input events requires the caller (or the source of the
  instrumentation, if any) to have the INJECT_EVENTS permission`（`monkey` 另外还要求 COUNT 位置
  参数，缺了报 `** Error: Count not specified`），所以「点一下标签看转场」这一步无法由我完成，
  最终观感由用户目视确认。
- 导航布局（第二次返工）：用户指出「做简单的导航动画即可,不要影响界面布局了」，即转场不得
  以改变页面结构为代价。于是把上一轮为了「只动标签页以下部分」而钉在 `NavDisplay` 之外的
  `preview` + 标签条收回每个目的地自己的 `LazyColumn`（`sectionHeader()`），页面恢复成
  「一条列表，前两行是预览卡与标签条」，与引入导航之前同形。`NavDisplay` 因此直接
  `fillMaxSize()` 并承接 `.layerBackdrop(barBackdrop)` / `.nestedScroll(...)`——原来挂在
  中间那层 `Column` 上的两个修饰符随该 `Column` 一并删除。`sectionPadding.top` 从 `12.dp`
  改为 `padding.calculateTopPadding() + 12.dp`：状态栏内边距原先由 Scaffold 施加在外层
  `Column` 上，现在必须由列表自己让开。
  验证：`compileDebugKotlin` / `assembleDebug` 通过且零警告；APK SHA256
  `EB5552BF…8CE63B`（上一版 `8A0CE291…E4E00B6`），`adb install -r` Success；冷启动
  `mCurrentFocus` 落在 `MainActivityAlias`、`pidof` 有进程、logcat 无 `FATAL EXCEPTION` /
  `No entry` / `Duplicate contentKey`。**静止态布局与改动前逐像素一致**（见上文两次 dump 的
  `bounds` 对比），这一条是本轮唯一可自证的验收点。转场观感同前，仍待用户目视。
- 导航覆盖穿透（第三次返工）：用户报告「不同标签页会覆盖上一个标签页的内容在底层」。
  根因是**交叉淡入与透明列表不相容**：`opaqueDepth = 1f` 刻意让被覆盖层继续组合并绘制
  （窗口 `-1 < d <= opaqueDepth`），而两个 `LazyColumn` 尺寸位置完全相同，被覆盖层只能被上层
  **真正画出的像素**遮住——卡片间 12dp 间隙与较短分区尾部的大片空白什么都盖不住，
  上一分区于是从那里透出。`Scaffold` 只提供一层底色，在只有一个列表的年代够用。
  修复：给 `SectionList` 的 `LazyColumn` 加 `.background(MiuixTheme.colorScheme.surface)`
  （`SettingsScreen.kt:606`），与 `Scaffold` 同色，静止态逐像素不变。
  验证：`assembleDebug` 通过且零警告；APK SHA256 `3CFDDE1E…0605`（上一版 `EB5552BF…8CE63B`），
  `adb install -r` Success；`javap -c` 确认 `BackgroundKt` / `background` / `getSurface` 已进字节码
  （排除「没重新编译」）；冷启动 `mCurrentFocus` 落在 `MainActivityAlias`、`pidof` 有进程、
  logcat 无崩溃；dump `v1.xml` 31613 B 与修复前 `ui4.xml` 完全同尺寸（General 静止态节点未变）。
  **装机版本已核对**：`adb pull` 出设备上的 `/data/app/…/base.apk`，SHA256 与本地 APK 逐字相同
  （都是 `3CFDDE1E…0605`），即设备上跑的确实是含本次修复的构建。
  注意：事后一次「最终重编」失败，报 `SettingsRepository.kt:91/133 Unresolved reference 'KEY_TYPE_SIZE'`
  与 `SettingsScreen.kt:796 Unresolved reference 'setOutTypeSize'`——那是**并发的另一个 DSH session**
  正在改 `Prefs.java` / `TrioSettings.java` / `SettingsRepository.kt` 中途留下的未完成状态，
  与本轮修复无关（我的 `.background(...)` 在 `SettingsScreen.kt:606` 仍在）。已安装的 APK 是
  那次失败构建**之前**的产物，因此设备上的版本不受影响；在此仓库与他人并发写文件时，
  任何时刻的 `assembleDebug` 结果都要先确认不是别人改到一半的状态。
  **判据是库契约而非截图**：`isVisibleAt(1f, 1f)` 为 true，所以被覆盖层在**静止态也一直组合、
  一直绘制**（不只是转场期间）——这正是「点完另一个标签后旧内容长驻底层」这种报告的原因。
  转场之外我无法自证：切标签需要注入输入，而本机 `input` 只能在当前前台窗口生效（见下一条更正），
  不能在用户可能正用手机时调用。
- **更正上文关于输入注入的结论。** 之前记录「`adb shell input tap` / `keyevent` / `monkey` 在本机
  全被 MIUI 拦下」只对**非 root** 成立：本机 `su` 可用（`/system/bin/su`，`uid=0(root) … context=u:r:ksu:s0`），
  `adb shell "su -c 'input tap X Y'"` 能成功注入。但它**无法指定目标窗口**，只会打到当时的
  前台窗口——我试过一次，当时前台是用户的 QQ（`com.tencent.mobileqq/…SplashActivity`，
  稍后变成 `…av.ui.AVActivity`），那一击落在了聊天界面上。**因此它不能用于本 app 的定向验证，
  除非先确认焦点在自己 app 上；不应在用户可能正在使用手机时调用。** 已验证过、也仍然安全的
  观察手段是：`dumpsys window | Select-String mCurrentFocus`、`pidof`、`logcat -d`、
  `uiautomator dump` + `adb pull`、`screencap -p` 到文件再 `pull`（`exec-out screencap` 会损坏 PNG）。
- 导航滚动位置（第四次返工）：用户一句「不要记录页面位置」。上一轮刚把「每个目的地各留各的偏移」
  写成了正确语义（见上文条目），这一轮按用户口径推翻：切标签一律回到列表顶部。
  改动只在 `SectionList`（`SettingsScreen.kt:572-587`）：多收一个 `isTop: Boolean`，
  加 `LaunchedEffect(isTop) { if (isTop) listState.scrollToItem(0) }`；四个 entry
  （`SettingsScreen.kt:467/475/483/497`）各自传 `isTop = nav.backStack.lastOrNull() == Route.Xxx`。
  **键必须是 `isTop` 而不是 `Unit`**：条目被覆盖时仍在组合，`LaunchedEffect(Unit)` 只在创建它的
  那次组合里跑一次，之后成为栈顶不会再触发。
  验证：`assembleDebug` BUILD SUCCESSFUL 且零警告；`javap -c` 里出现
  `LazyListStateKt.rememberLazyListState` 与 `SettingsScreenKt$SectionList$1$1.<init>:(Z…Continuation;)V`
  （那个 `Z` 就是 `isTop`，证明 lambda 确实捕获了它而不是常量）；`SectionList` 签名变为
  `(PaddingValues, boolean, Function1<LazyListScope, Unit>, Composer, int)`。
  APK 33359674 B，SHA256 `BD2C2E96E7DBB196D6E4F004C9F05E100A0471C8E52445F9DD9FF1A605DD933C`
  （上一版 `3CFDDE1E…0605`；体积变大是因为**并发的另一个 DSH session** 的「外环字号」功能
  同时进了这一版，其 `Prefs.java` / `TrioSettings.java` / `SettingsRepository.kt` 已改完并通过编译）。
  `adb install -r` Success；冷启动 `mCurrentFocus` 落在 `MainActivityAlias`、`pidof` = 2675、
  logcat 干净。**装机版本已核对**：`adb pull` 设备 base.apk 到 `.tmp\installed2.apk`，
  SHA256 与本地同为 `BD2C2E96…933C`。
  「回到顶部」这一步的实际手感仍需用户目视——切换标签要注入输入，无法自证。
- **构建环境变更**：`.tools\jdk\jdk-21.0.12.1+1`（Temurin 21.0.12.1+1，从 Adoptium 下载，195.6 MB）
  是新增的，`env.ps1` / `build.ps1` / `release.ps1` / 本文档的 `JAVA_HOME` 都已从 JDK 17 改为它。
  JDK 17 目录**保留未删**，但已不被任何脚本引用。
- 电量环缺口（本轮修复）：用户报告「电量环在中间断开后，应该是左右两半各 50%，现在的逻辑不对，
  会把中间显示为其他图标的位置也算作电量区域了」。根因是 `TrioRenderer.batteryRing` 里
  `level/100` 被当成**整条路径**的进度直接用，缺口宽度没有从 `to` 里补偿回来。
  改为按可见弧长换算（见「几何」一节）。验证走 `work\gapcheck\verify.ps1`：
  同一份 `GapProbe` 跑两遍，固定版 `exit 0`（63 档扫描 + 12 项端到端全绿），
  只把 `batteryRing` 换回旧算法的对照版 `exit 1`（42 项不符，45% 就已填满左半环、
  55/60/65% 右半环完全不起弧）——**反例成立**才说明探针在测这个 bug 而不是恒真。
  `javac -source 8 -target 8 -bootclasspath android.jar` 编 10 个 hook 源 `exit 0`；
  `gradle --offline assembleDebug` `exit 0`。**探针第一版曾因端帽误判**（见下条教训）。
- 教训（端帽）：探针第一版按手工取点分类左右段，在 45%/50% 上报了大量假失败，原因是
  `STROKE` 是 `Paint.Cap.ROUND`，每段末端向外多渗半个笔画宽（`stroke = 14` 时约 0.032 路径进度、
  7.79°）。凡是「看着渲染器明明正确、断言却失败」的像素校验，**先怀疑端帽**，改成区间模型并
  从点亮末端扣掉 `cap` 再比。这条同样适用于 `centreClearWidth` / `gapClearWidth` 之外的任何
  像素级判定。
- 电量环缺口**上机验证**（小米 14 / houji / Android 17 / HyperOS 4 / KernelSU）：`install.ps1
  -InstallOnly` Success，`adb pull` 设备 `base.apk` 与本地 SHA256 逐字相同
  （`E9FB1EDF5918048C974988DA254A675CDF798FBFDE905D81A23C3EE97AF814B0`，33359674 B），
  且 APK (18:25:42) 晚于 `TrioRenderer.java` (17:19:26)——设备上跑的确含本次修复。
  SystemUI 重启后 `HyperDuo installed, hooks=9 enabled=true`，无 `FATAL EXCEPTION`。
  两帧定量测量（`work\ondevice\measure.ps1` / `m60b.ps1`：按颜色分类像素 → 代数拟合圆心半径 →
  映射到路径进度 `t` → 输出连续区间；`TrioGeometry.B_START=148.69008689281117`、
  `B_SWEEP=242.6198262143777`）：
  - **52%**（真实电量，充电中）：绿 `0.002..0.398` 与 `0.666..0.744`，灰含 `0.445..0.999`。
    `u = 0.52 × 0.65 = 0.338`，左段应到 `min(u, m0=0.325)`、右段应从 `0.675` 起画
    `0.013` 残段——两段都出现，缺口内部无绿。
  - **60%**（`dumpsys battery set level 60` 强制值，判别性帧）：绿 `0.001..0.325`（左，止于
    `m0`）+ **右半环绿 0.675..0.783 共 96 个采样**，灰 `0.384..0.623` 等。固定版预测
    `u = 0.39` → 右段 `0.675..0.740`（含端帽到 0.772），实测吻合。**这一帧是判别性的**：
    旧算法 `to = 0.60 < m1 = 0.675` 时右半环一个像素都不画，与实测的 96 个绿采样互斥。
    目视见 `work\ondevice\lv60-fixed-8x.png`（8x）：左半环绿满、右半环从缺口右缘起一小截绿、
    其余为灰轨道。缺口内部仅 3 个采样落在 `0.357..0.643`（495 个中的抗锯齿边缘像素）。
  **强制电量必须还原**：`set level` 是全局持久状态，要写成
  `dumpsys battery set level 60; sleep 2; screencap -p /sdcard/x.png; dumpsys battery reset`
  **同一条 `adb shell`**，否则中途掉线/中断会把手机卡在假电量上（本轮真发生过一次，
  重插 USB 后 `reset` 才清掉，前后读回都是 `level: 47`）。
  拟合圆心时另有两条坑：只用绿像素拟合会退化（绿只有两段短弧），而把「中性灰」阈值放宽到
  `mx >= 90` 会把背景 `#656563` 一起算成轨道（13144 px，圆心被拖到背景里）。正确做法是先按
  目视取粗略圆心做环形预筛（`18 < d < 34`）再拟合，且背景灰度必须排除。
- **全量样式总览图**（对照 macOS 版 `Status Trio` 总览图出的图）：`work\overview\run.ps1`
  `exit 0`，`work\overview\out\overview.png`，`1788×2248`，3 组共 **25 格**，
  `renderer = 92f6733e0b52a58409b17d26270b6a6fc0fa3152978c2cd62d2c5262bb9bb420`
  （即出图时 `TrioRenderer.java` 的 SHA256，用来确认图对应哪份代码）。放大目视核对
  `crop-battery.png`(2x) / `crop-wifi.png`(2x) / `crop-mobile.png`(2x) / `crop-footer.png`(2x) 逐格正确；
  Wi-Fi 组末格「居中后充电」现在画的是**缺口里的小闪电 + 圆心数字**，与改动前的「闪电居中」相反。
  **页脚长这样是有守卫的**：`footerLine` 用 `FontMetrics.stringWidth` 量宽并 `throw`，宁可出图失败
  也不让文字被裁掉——本页新增的居中说明行第一次就超宽 76px 被它拦下，改为更短的句子才通过。
  **清点结论**：参考图的「蓝牙音频」4 格与「音量」7 格在本模块无任何实现
  （`volume|bluetooth|audio|headset|earbud` 零匹配），「已连接电源，未充电」也没有对应状态
  （渲染器只有充电 / 未充电两态），这三类都不应画进图里 —— 详见「测试」一节的样式清单。
  原图曾另有一组「外观与几何」12 格，后按要求删除：那是外观旋钮而非图标状态。
- 教训（出图也会溢出）：总览图页脚前两版都**超出右边界被静默裁掉**，肉眼在图缩略图上根本看不出。
  改成 `footerLine(...)` 先 `getFontMetrics().stringWidth` 量宽、超宽直接 `throw`，
  立刻报出 `footer line overflows the page by 49px`。凡是定宽排版出图，都要让"画不下"变成
  异常而不是裁剪。
- 教训（PowerShell 5.1 与 BOM）：工作树里的 `release.ps1` 丢过 UTF-8 BOM，PS 5.1 于是按 GBK
  解码中文注释，多字节序列吞掉后面的引号，报出 8 个**指向完全正确行**的 parse error。凡在
  PowerShell 5.1 下跑、且含中文的 `.ps1`，都要确认首字节是 `239,187,191`。已逐个按「解码后
  是否出现乱码」判定：`release.ps1`（517 个汉字，**必须**带 BOM）、`install.ps1`（`install.ps1:66`
  的 `Duo 三合一状态栏` 会被解成 `Duo 涓夊悎涓€鐘舵€佹爮`，已补 BOM，len 3140→3143）；
  `env.ps1` / `build.ps1` 一个汉字都没有，无 BOM 也无所谓，保持原样。
  BOM 有无只看 **parse error 是不够的**——`install.ps1` 在无 BOM 时 parse-errors 仍然是 0，
  错的是运行时打印出来的字。
- **1.1 发布**（`release.ps1 -Version 1.1 -NotesFile work\notes-1.1.md`，`exit 0`）：tag `v1.1`
  → `03b36b5f5a0f95709255dbf85705da724da4cfb3`（与 `main` 同一提交，即构建的确实是已提交的代码）；
  Release `HyperDuo 1.1` 非 draft / 非 prerelease，body 808 字符；asset `HyperDuo-1.1.apk`
  3038076 B。**发布产物按字节复核**：从 `api.github.com/repos/…/releases/assets/605602727`
  下载回来 SHA256 `9E024FA1A8988947DA9D6E694AC83B9A737FE7C2EC85C32CDB406C8414FBA6DC`
  与本地 `dist\HyperDuo-1.1.apk` 逐字相同。签名 `CN=HyperDuo` 与设备上原装 APK 同一证书
  （SHA-256 `b4e3a12d…8c41f`），所以能 `-r` 覆盖安装而不冲突。
- **1.1 release 包上机验证**（这一步不可省：R8 会剥离反射用到的类）。`adb install -r` Success，
  `versionCode=10100` / `versionName=1.1`、`pkgFlags` 里 **没有 `DEBUGGABLE`**（release 构建）。
  类名在 dex 里是**斜杠描述符**（`Lio/github/yixing233/hyperduo/TrioHooks;`），用点号搜会全部假阴性 ——
  `TrioHooks` / `TrioRenderer` / `TrioConfig` / `Prefs` / `TrioSettings` / `HyperDuoModule` /
  `ui/RestartController` 全部在 `classes.dex` 里，未被剥离。SystemUI 重启后
  `HyperDuo installed, hooks=9 enabled=true`、`reload receiver registered`，无 `FATAL EXCEPTION`。
  截图 `work\ondevice\release-1.1-statusbar.png` 目视确认：三合一图标正常（绿色充电弧 + `49` +
  闪电 + 底部信号点），环外网络类型 `5G` 也画出来了。日志中对应
  `out type: "5G" size=45.0 label=60x60 at 341,14 anchor=407..491 container=491x88`。
- **1.3 发布**（`release.ps1 -Version 1.3 -NotesFile work\notes-1.3.md`，`exit 0`，构建耗时
  1m19s）：tag `v1.3` → `e97f73d6347f842a21511db03f37cde299732c48`（与 `HEAD`/`main`
  同一提交——`release.ps1` 构建工作树但给 `HEAD` 打 tag，所以**必须先提交干净再跑**）；
  Release `HyperDuo 1.3` 非 draft / 非 prerelease（`draft=False` / `prerelease=False`），
  body 496 字符，asset `HyperDuo-1.3.apk` 3038276 B。**发布产物按字节复核**：从
  `https://github.com/yixing233/HyperDuo/releases/download/v1.3/HyperDuo-1.3.apk` 下载回来
  SHA256 `2AAD1D6AA5920FBA4EA3F1E44B1349364752C81A9BA63675DA1D365EB2916EA1`
  与本地 `dist\HyperDuo-1.3.apk` 逐字相同（R8 产物体积不固定，每次构建字节都不同，
  所以「下载回来对哈希」这一步不能省）。`git ls-remote --tags` 确认远端有 `refs/tags/v1.3`。
  **`v1.2` 从未打过 tag 也从未发布**——`dist\HyperDuo-1.2.apk` 只是本地产物（它含改名后的串
  「电量数字居中」，但还不含居中的优先级修复）。所以 1.1 → 1.3 的用户一次性拿到改名 + 修复，
  1.3 的 release notes 只写这两件事，不提 1.2。
- **1.3 release 包上机验证**（这一步不可省：R8 会剥离反射用到的类，而 debug 包不会暴露这个
  风险）。三个构建类型共用同一个签名配置（`app\build.gradle.kts:26-49`：`debug` 与 `release`
  都取 `signingConfigs.getByName("hyperduo")`，keystore `.tools/debug.keystore`、alias
  `hyperduo`），所以**正式包能直接 `-r` 覆盖 debug 包**——但两者证书必须一致，实测
  `keytool` 的 `SHA256: B4:E3:A1:2D:…:C4:1F` 与 `apksigner verify --print-certs` 打印的
  `b4e3a12d03957e441c3ccf2e4e2be55de0db35fc5eb9aa8ac49a0a9d7298c41f` 相同（也就是设备上
  原装 APK 的同一证书）。
  `adb install -r dist\HyperDuo-1.3.apk` Success；`versionCode=10300` / `versionName=1.3`。
  **按字节证明装进去的就是发布包**：`pm path` 取 codePath 后 `adb pull` 出 `base.apk`，
  SHA256 `2AAD1D6AA5920FBA4EA3F1E44B1349364752C81A9BA63675DA1D365EB2916EA1`（3038276 B）
  与 `dist\HyperDuo-1.3.apk` 以及从 GitHub 下载回来的那份**三者逐字相同**。SystemUI 重启后
  `HyperDuo installed, hooks=9 enabled=true`、`reload receiver registered`，无 `FATAL EXCEPTION`
  ——反射入口在 R8 下存活。截图 `work\ondevice\release-1.3-statusbar.png`（裁切放大
  `release-1.3-icon-4x.png`）目视确认居中优先级修复在正式包里生效：**缺口里是小闪电、
  圆心是 `100`**（若拿 debug 包验证，就等于没验证 R8 这一层）。
- **双卡信号（`dual_sim_signal`）离线验证**：`work\overview\run.ps1` `exit 0`，
  `work\overview\out\overview.png` `1788×2248`、3 组 **26 格**（移动信号组 8 → 9 格），
  `renderer = 38011f32635075984f70fca4c17ab519a71a70f4329dd12e3e543d6eb8b8892f`；
  新增格「双卡信号（4 / 2 格）」目视确认：两排点分别落在圆环的上下两个缺口里、两排都用
  `DUAL_DOT_R = 7.0f`、数字 `79` 居中，与参考图一致。`work\preview\out\04-dual-sim.png`
  （`slotLevels = {4, 2}`、无 Wi-Fi、电量 79）单张复核同形。双卡格**故意取两卡电平不同**
  （4 / 2）：若哪天它悄悄退化成单排，出图会「看起来就不对」，而不是看起来像个合理的单卡读数。
  上下排与 slot 的对应关系**不靠肉眼**（288×288 缩略图上看不出每排几点，容易把两排读反）：
  `work\dotprobe\DotProbe.java` 反射取 `TrioGeometry.DOTS_DUAL` 与 `TrioRenderer.inkScale`，
  按渲染器同一套平移/缩放算出 8 个圆心，再在每点邻域统计亮点。实测（`inkScale = 2.15319`、
  `tx = ty = 15`）：`DOTS_DUAL[0..3]`（设计 y < `B_CY`，上排 = slot 0 = 卡一）**4 点全亮**；
  `DOTS_DUAL[4..7]`（下排 = slot 1 = 卡二）**前 2 点亮、后 2 点灭**（第 7 点 `maxv = 78`，
  即只有 `trackAlpha` 的灰）。与 `slotLevels = {4, 2}` 逐点吻合，确认上排=卡一、下排=卡二。
  测量时要注意相邻点窗口重叠：灭点若紧挨亮点，窗口里会漏进几个亮像素（第 6 点测得 31 px、
  质心偏 29.5 px 靠左），判断某点是亮是灭要看该点圆心附近的 `maxv`，不能只看窗口内亮点计数。
  `:app:assembleDebug` `BUILD SUCCESSFUL`（`37 actionable tasks: 13 executed`）。
  `work\gapcheck\verify.ps1` 与 `work\slotcheck\verify.ps1` 均 `exit 0`：两次`drawInto` 加参、
  `drawBattery` 改收 `TrioAppearance` 之后，两条工装都已改到能同时链上新旧两版渲染器（见「测试」
  一节的三处坑），固定版/新版必须 `exit 0`、旧算法/旧版必须 `exit 1` 各自成立。
- **双卡开关判定规则离线验证（第二轮补）**：`work\dualsimcheck\verify.ps1` `exit 0` —— 固定版
  `exit 0`（21 条断言全 `[ok]`）、把两处 `dualSimRows` 调用点换回 `bolt` 的反例版 `exit 1`
  （恰好那 3 条「充电中且 `show_value=false`」断言不符）。这条工装是为了补上一个**此前没被测过**
  的洞：`overview` 只证明双卡格画得出来，`gapcheck`/`slotcheck` 测的是电量弧与居中优先级，**没有
  任何一条覆盖「什么时候才该画两排」**。而补测的过程本身查出一个真缺陷：`dualSimRows` 第 2 参在
  javadoc 里是「设备是否插着充电器」，两个调用点（`Ring`/`Rect`）却都传了 `bolt`，而
  `bolt = charging && drawsBolt()`、`drawsBolt() = showBolt && showValue`，于是**关掉「显示电量
  数字」后插着充电器也会画出两排**，既违反 javadoc 也违反用户原话的「没充电」。修复即把两处改传
  `charging`（`TrioAppearance.java`，附注释说明「是插头不是闪电」），`TrioSettings.dualSim` 的
  javadoc 与 `docs` 里「没有闪电（`charging` 为假）」这句同样含混的措辞也一并订正为「没插充电器」。
  顺带记一条采样坑：`rows()` 最初取窗口内**最大** alpha，`{2,4}` 时最外侧上排点被圆环弧（相距
  约 25px）带亮而误判；改读圆心**核心区取最小** alpha 后，亮点 255 与轨道点 `trackAlpha=56`
  判然可分（实测 `core alpha 255 255 255 255 / 255 255 56 56`）。
- **双卡上排拥挤的修复与离线验证（第四轮补）**：症状是用户指出「上排显示不完整、空间太少、应该
  上下对称」——上排外侧两点与圆环 12 点钟缺口的两个**圆帽**糊在一起，参考图里那种点与弧分明的样子
  出不来。根因是量化的：上排是 `DOTS` 绕环心 `B_CY` 的镜像，外侧点几乎正落在弧中线 `B_R = 51.5` 上，
  外点圆心到弧端点圆心只有约 **10.4** 个设计单位，而弧用 `STROKE.setStrokeCap(ROUND)` 收尾、帽半径
  `ringStroke / 2 = 7`，加点半径 `DUAL_DOT_R = 7` 共 **14** ⇒ 必然重叠；下排落在弧**没盖住**的那段
  自然开口里（`360 - B_SWEEP = 117.4°`），所以一直舒展。而两排共用的
  `GAP_START_IDLE`/`GAP_END_IDLE` 只张 `0.35 * B_SWEEP = 84.9°`，比底部开口**窄 32.5°** —— 这正是
  「不对称」的可量化来源。修法：新增 `GAP_START_DUAL = 1f - 180f / B_SWEEP ≈ 0.2581`、
  `GAP_END_DUAL = 180f / B_SWEEP ≈ 0.7419`，在 `TrioRenderer.drawRingLayout` 里让 `r.dual` 优先取
  这对值，使 12 点钟缺口与底部开口**等宽**，余量回到约 10 单位。顺带修掉一处叠加缺陷：`valueInCentre`
  原先只看 `centreValue`/`(!wifi && !type)`，双排的上排占了缺口而 `valueInGap` 会把百分比数字送进
  同一位置造成叠画，现在 `hasValue && (a.centreValue || dual || (!wifi && !type))` 让数字回到环心。
  验证：`work\dualsimcheck\verify.ps1` `exit 0`，且升级为**三构建**对照——`fixed` `exit 0`（25 条断言
  全 `[ok]`）、`bolt` 版 `exit 1`（3 条不符，仍是上一轮那条充电语义）、**新增 `narrow` 版**（把两个
  `GAP_*_DUAL` 退回 idle 值，即用户投诉时的状态）`exit 1`（2 条不符）。新增的 `separation()` 组用
  **连通域面积**把「挤不挤」变成断言：从每个点的中心像素做迭代式 4-连通 flood fill，要求
  `area / (π·DUAL_DOT_R²·scale²) < 2.5`，实测 `fixed` 版 8 个点全是 **0.97..0.99** 个点面积，而
  `narrow` 版上排外侧两点涨到 **8.22 / 5.58**（与弧合并成一体），下排 4 点作为对照始终 0.97..0.99。
  同样一条断言在 `bolt` 版下**不**失败，说明它专盯几何、不与其他断言重复。
  可视化复核：`work\dualsimcheck\out\dualsim.png`（2296×650，sha256
  `824BFD162B5630D0EDCAA26A33585C41CFC3976B5A8CFAC0B9DB5D5006A418A4`）四格中第 ② 格已可见上排
  四点完全脱离环的两个圆帽、与下排对称；关闭时只有下排、充电时只有一排且闪电在缺口、单卡时只有一排。
  **改前/改后对照图**由 `work\dualsimcheck\before-after.ps1` 生成：它把同一份绘图代码分别与工作树的
  `TrioGeometry`（双排缺口 = 底部开口宽度）和「两个 `GAP_*_DUAL` 退回 idle 值」的那份各编一遍，再由
  `Panels.java` 拼成上下两栏，产出 `work\dualsimcheck\out\top-before-after.png`（1310×1592，12 点钟
  区域放大 3 倍，由 `TopZoom.java` 裁切）与 `...\out\dualsim-before-after.png`（2316×1422，四态总览，
  由 `DualSimShot.java` 出图）。**没有任何单次编译能同时画出两个几何**，所以必须两次编译各出一张再合并。
  放大图里 `BEFORE` 栏上排外侧两点与弧的圆帽连成一体（`separation()` 实测 8.22 / 5.58 个点面积），
  `AFTER` 栏四点是四个独立正圆、与弧留出明显空隙（0.97..0.99）。
  同轮其余门禁复跑全绿：`gapcheck` `exit 0`（反例 42 条不符）、`slotcheck` `exit 0`（反例 6 条不符）、
  `simcheck` `exit 0`（反例 23 条不符）、`overview` `exit 0`（1788×2248、三组 10/7/9 格、`total
  cells = 26`、`renderer = eb3d8cbbd28e8a662df2759911e55620df672c47a09944c6a259178d19883817`），
  `:app:assembleDebug` `BUILD SUCCESSFUL`（`app-debug.apk` 33426038 B、sha256
  `C7F58E09590C6A875B8226C0A99D68254AAD25C6F3664EA879A9740D1054085C`）；dex 里查得
  `dual_sim_signal` / `dual_sim_title` / `dual_sim_summary` / `DOTS_DUAL` / `DUAL_DOT_R` /
  `dualSimRows` / `GAP_START_DUAL` / `slotLevels` / `pollSimsNow` / `attachContext` / `getSimSlotIndex` /
  `getDefaultDataSubscriptionId` / `appearance` / `stackedOut` 全部命中（6 个 dex，`com/hyperduo`
  零命中），确认本轮几何改动与新开关都进了交付物。这组哈希是**最后一次全绿时**的值，
  同轮之后并发写者又动过 `TrioGeometry` / `TrioHooks` / `TrioRenderer` / `TrioState`，重跑会变；
  **后被第七轮那次构建修复取代**（见「第七轮补」条：`app-debug.apk` 33434570 B、`AAEA3228…`）。
  另记一条工装迁移
  事实：本轮之前 app 整包改名为 `io.github.yixing233.hyperduo`，`work\` 下 25 个工装文件随之迁移
  （`work\migrate-rename.ps1`），其中 `work\slotcheck\verify.ps1` 需要把「工作树路径」与「`git show`
  用的旧提交路径」拆成两个变量（旧提交树里仍是旧路径），并把取出的旧源**重写包声明**后才编得过。
  上机验证当时仍待补（设备未上线），第六轮补上了双排那条。
- **双卡订阅号↔槽位映射与单排兜底离线验证（第三轮补）**：`work\simcheck\verify.ps1` `exit 0` —— 固定版
  `exit 0`（42 条断言全 `[ok]`）、把 `final int slot = info.getSimSlotIndex();` 换成
  `info.getSubscriptionId()` 的反例版 `exit 1`（实测 23 条不符）。补它的原因：双卡信号里画得对不对
  已有三条工装，而**哪一排属于哪张卡**、**只画一排时画哪张卡**只在 `TrioState.sampleSims` 里决定，
  且只在真机 SystemUI 进程会跑到；参考机上 `subId` 恰好等于槽位（subId 1 在槽 0、subId 2 在槽 1），
  所以「把订阅号当槽位」这种写法在那里**看起来是对的**，本工装因此故意用不相等的 `subId 9 → 槽 0`、
  `subId 5 → 槽 1` 来暴露它。实现手法：真实 `TrioState.java` 只依赖 `Refl`（纯 JDK）与 `TrioConfig`，
  于是自带一套 `android.telephony.*` 桌面垫片就能跑，不需要 Xposed、也不需要设备。另记两条坑：
  一是 `gating()` 对每次 `refresh()` 新建 host 时，拦下采样的其实是 2 秒限流而不是开关，断言会
  「因为错误的原因」通过，必须复用同一 host 并在两次 `refresh()` 之间步进 `SystemClock.now`；
  二是编译源**不能**带 `work\preview\src` 里的 `TrioState.java` / `TrioConfig.java`（同名桌面替身撞类），
  只能借它的 `SharedPreferences.java` 与 `Bundle.java`。
  **上机验证（当时）仍待补**：设备 `192.168.1.148:33945` 已下线（`adb connect` 失败、
  `Test-Connection -Quiet` 为 `False`、`adb devices` 只剩一条 `offline`）；第六轮补到了
  「真双卡手机在无 Wi-Fi 未充电时画出两排点」这条。但**「只插一张卡时退化成单排、
  且那排是当前上网卡」至今仍只有离线证据**（`work\simcheck\verify.ps1` 的 counterexample
  用 subId 9 → 槽 0、subId 5 → 槽 1 的错位映射来暴露「把订阅号当槽位」的写法），
  真机没拔过卡，这条不要当成已验收。
- **改名后的真机部分验收（第五轮补，双排本身未拍到、由第六轮补上）**：设备经无线 adb 短暂上线（`192.168.1.148:38661`，
  houji / model `23127PN0CC` / Android 17 / SDK 37 / `wm size` 1200x2670 / density 480），
  本轮取到三条真机事实：
  1. **改名后的包已自动进入 LSPosed 且已加载**。`modules_config.db` 里新包
     `enabled = 1`、`scope` 含 `com.android.systemui`；旧包 `com.hyperduo.trio` 已从设备卸载
     （`pm list packages -f hyperduo` 只剩新包）。**不需要手工往作用域里拖**：LSPosed 按
     `applicationId` 建行，改名后第一次启动模块就自己登记了。截图
     `work\device\out\bar-wifi-on.png`（1200x2670）右侧可见 `13.4 KB/s` + `5G` + 三合一轮
     （环内 `85`、环下四点）——**证明改名 + `TrioAppearance` 重构后的代码在真机 SystemUI 里
     真的跑了**，这比任何离线渲染都强。
  2. **真机是双卡，且未充电**：`dumpsys isub` 里 `id=1 … simSlotIndex=0 … 中国移动`、
     `id=2 … simSlotIndex=1 … 中国广电`（`getAvailableSubscriptionInfoList: [1, 2]`，
     恰好 `subId = slot + 1`，这是参考机的巧合，不是契约）；`dumpsys battery` 三项
     `powered` 全 `false`、`level: 85`、`status: 3`。⇒ 双排的两个前提（未充电、双卡）已满足。
  3. **卡在「无 Wi-Fi」这一步**：`dumpsys wifi` 为 `Wi-Fi is enabled`，而无线调试本身走
     Wi-Fi，`adb shell "svc wifi disable"` 一执行就 `error: closed` / `device offline`，
     之后十余次 `adb connect` 全部失败。**教训：这台设备上不能用 `svc wifi disable` 制造
     「无 Wi-Fi」状态，那等于自断控制通道。**
  因此 `work\device\acceptance.ps1` 改用**语义等价的替代触发**：发模块自己的 reload 广播把
  `show_wifi` 置假（`am broadcast -a io.github.yixing233.hyperduo.action.RELOAD -p
  com.android.systemui --ez enabled true --ez show_wifi false --ez dual_sim_signal true`）。
  这之所以合法，是因为判定规则读的是 `wifiInk`（屏幕上有没有 Wi-Fi 墨迹），不是物理 Wi-Fi
  开关；而 `TrioConfig.onReloadBroadcast` 对不含 `KEY_ENABLED` 的 extras 直接忽略、对缺失的键
  一律沿用**上一次快照**（`TrioSettings.fromBundle(bundle, base)`），所以只带这三个键就只改这
  三个键，其余设置不会被清掉。脚本另含：设备事实、包与旧包检查、LSPosed 配置库判读
  （Java 序列化 boolean 只看 `data` 列**最后一个字节**）、充电/双卡读数、SystemUI 日志里新包
  是否出现、以及关机屏截图。
  **仍未完成的验收，以及为什么主机侧帮不上忙**：截图里还没看到两排点。后来设备整个从局域网
  消失——`arp -a` 里没有 `.148` 的 MAC，`ping -S 192.168.1.180 192.168.1.148` 得到
  `Destination host unreachable`（同法 ping 网关 `192.168.1.1` 正常，`time<1ms TTL=64`），
  异步扫 `192.168.1.0/24` 只有 `192.168.1.1` 与 `192.168.1.180` 活着。**恢复只能靠用户在手机上
  手动重开 Wi-Fi 与无线调试。** 另记一条主机侧陷阱：本机 FlClash 是 TUN 模式
  （`198.18.0.1/30`，默认路由 `0.0.0.0/0 → 198.18.0.2 metric 0`），
  `Find-NetRoute -RemoteIPAddress 192.168.1.148` 判给 `FlClash` 而不是`以太网`，于是
  **任何 TCP 连接都会「成功」**——早先那次「1024..65535 几乎全开」的端口扫描是假象，
  不能用 `TcpClient.ConnectAsync` 判断端口是否开放，要看 `Find-NetRoute` 或 `adb devices`。
  当前 shell 非管理员，加不了静态路由绕开它。
- **真机双排验收完成（第六轮补）**：设备重开 Wi-Fi 后回到局域网，**端口又变了**
  （用户给的 `192.168.1.148:38661` 变 `10061` 拒绝）。这次先跑 `adb mdns services`，
  直接问出真正在听的端口 `_adb-tls-connect._tcp 192.168.1.148:35237`，连上即 `device`。
  **教训：无线调试端口每次重开都变，别再猜或扫端口，直接 `adb mdns services`。**
  设备事实与上轮一致（houji / `23127PN0CC` / release 17 / SDK 37 / `wm size` 1200x2670 /
  density 480）；模块本机 prefs 里 `dual_sim_signal=true`、`mobile_type_mode=2`、
  `swap_wifi_value=true`；`dumpsys battery` 三项 `powered` 全 `false`、`level: 75`；
  `dumpsys isub` 仍是 slot 0 = 中国移动（subId 1）/ slot 1 = 中国广电（subId 2），
  `defaultDataSubId=2`、`activeDataSubId=2`。
  验收手法与结果：先 `screencap` 拍下 Wi-Fi 在屏的基线（`work\device\out\bar-wifi-on.png`，
  裁剪 `tall-wifi-on.png`：环内 `75`、上排位置是 Wi-Fi 弧、**只有一排点**），再发那条 reload 广播
  把 `show_wifi` 置假，同一位置再拍（`bar-dual.png` / `tall-dual.png`）——**真的出现两排点，
  上下对称**，Wi-Fi 弧消失、百分比仍居中 `74`。随后再发一条 `show_wifi true` 把设置还原
  （`bar-restored.png` / `tall-restored.png`：Wi-Fi 弧回来了、又只剩一排），确认这条触发路径
  可逆、没有留下副作用。三态拼图 `work\device\out\device-dual.png`（1020x2278，
  sha256 `53ABB0A709E4E21BFC7B8E781C9BF82A8CC142ECF15977A3CC0920FD8DD8ABB5`，由
  `Panels` 生成）。
  **对称性是量出来的、不是看出来的**：对 `tall-dual.png`（`Crop` 放大 5 倍，1100/5 比例）跑
  `work\refimg\Analyze.java`，六颗点的质心给出环心 `y = 365.18`，于是
  上排三点在上方 `108.19 / 126.94 / 128.19`，对应的下排三点在下方
  `109.12 / 126.82 / 128.31` ⇒ **三对偏差 `0.93 / -0.12 / 0.12` 裁剪像素，
  即最大 `0.19` 设备像素**（内两对在 `0.03` 设备像素内）。这正是 m02630 要的「上下对称」，
  也是 `GAP_*_DUAL` 那两行几何改动的真机证据。
  同轮 `Analyze` 还确认六颗点的外接框都是 `35x35`（`area=900/925`），即**同样大的正圆**：
  上排没有被环的圆帽吃掉，也没和弧粘成一个 blob（离线侧同一结论的数字是分离度 `0.97..0.99`）。
  真机取证的工装细节：`Crop` 与 `Panels` 都在
  `work\dualsimcheck\after-classes`（`io.github.yixing233.hyperduo.Crop/Panels`），
  `java -cp <那个目录> io.github.yixing233.hyperduo.Panels …`；`Panels` 不传全限定名会
  `ClassNotFoundException: Panels`。`Analyze` 在 `work\refimg\classes`（**默认包**，
  `java -cp … Analyze`）。`screencap` 写进 `/sdcard` 再 `adb pull` 仍比 `exec-out` 稳。
- **未转义撇号把构建弄坏、aapt2 才是可信报错源（第七轮补）**：这一轮 `:app:assembleDebug`
  突然失败在**并发写者新加的英文串**上（不是双卡那部分改动）：
  `app\src\main\res\values-en\strings.xml:50:4: Failed to flatten XML for resource
  'stacked_signal_summary' with error: Invalid unicode escape sequence in string` +
  `…:50:4: string/stacked_signal_summary does not contain a valid string resource.`
  + `> Task :app:mergeDebugResources FAILED`。**这条信息严重误导**：按字节扫两个
  `strings.xml`，`0x5C`（反斜杠）计数为 **0**，严格 UTF-8 校验通过、无 BOM；合并产物
  `app\build\intermediates\incremental\debug\mergeDebugResources\merged.dir\values-en\values-en.xml`
  也干净；**清掉 `…\incremental\debug\mergeDebugResources` 再建，报错一字不变**（所以不是增量缓存陈旧）。
  **定位手法（值得复用）**：绕过 Gradle，用 aapt2 直接编一份资源副本 ——
  `.tools\sdk\build-tools\37.0.0\aapt2.exe compile --dir C:\code\HyperDuo\work\resprobe -o out.zip`
  （目录里放 `values\strings.xml` 与 `values-en\strings.xml` 两份），aapt2 才说出真话：
  `strings.xml:52: error: unescaped apostrophe in string`，直指 `… the system's own mobile signal
  icon is kept.`。⇒ **aapt2 报「Invalid unicode escape sequence in string」时，多半其实是
  「字符串里有未转义的撇号」（`'` 必须写成 `\'`，或把整串用 `"…"` 包起来）；Gradle 那条信息
  不可信，遇到就先 aapt2 直编定位，别去清缓存。** 中文版 `values\strings.xml:52` 无此问题
  （撇号本来就不出现在中文串里），所以只改了英文那份。修复后 aapt2 `exit 0`，
  `:app:assembleDebug` `BUILD SUCCESSFUL in 17s`，交付物刷新为
  `app-debug.apk` **33434570 B**、sha256
  **`AAEA3228AE3A9CAD848771ECFE5D0C580D28257F3817D41368DF31A75ED23736`**；
  解包仍是 **6 个 dex**，`dual_sim_signal` / `dual_sim_title` / `dual_sim_summary` / `DOTS_DUAL` /
  `DUAL_DOT_R` / `dualSimRows` / `GAP_START_DUAL` / `slotLevels` / `pollSimsNow` / `attachContext` /
  `getSimSlotIndex` / `getDefaultDataSubscriptionId` **12 个串全命中**、`com/hyperduo` 零命中。


- **包名更换为 `io.github.yixing233.hyperduo`（为进 LSPosed 仓库）**：起因是 LSPosed 的
  反域名归属校验——`com.hyperduo.*` 要求根域 `hyperduo.com` 配一条
  `lsposed-modules-repo-verification=<github 用户名>` 的 TXT 记录，而该域名虽在 Cloudflare
  之下（RDAP：2004-02-04 注册、2027-02-04 到期、registrar Cloudflare），**TXT 记录为空**，
  所以那条路走不通；提交 README 另有免域名通道——用 `io.github.{username}` 前缀即可，
  不需要域名也不需要 TXT。于是 `com.hyperduo.trio` → `io.github.yixing233.hyperduo`。
  改动面：17 个源文件从 `app\src\main\java\com\hyperduo\trio\` 迁到
  `app\src\main\java\io\github\yixing233\hyperduo\`（16 个走 `git mv` 保留历史，1 个未跟踪文件直接移动），
  包声明与 `import` 同步改写；`app\build.gradle.kts` 的 `namespace` / `applicationId`、
  `app\proguard-rules.pro` 的两条 `-keep`、`META-INF\xposed\java_init.list` 的入口类、
  `install.ps1` 与 `release.ps1` 的 `$Package` 一并更新。改写用 `UTF8Encoding($false)` +
  `ReadAllText/WriteAllText`，**原有 LF/CRLF 与无 BOM 全部保持**（仅 `TrioConfig.java` 原本是 CRLF）。
  有意**不动**的东西：`Prefs.NAME`（远程 preferences 组名，也是设置界面文件名，
  改它等于丢用户设置）、签名配置名 / `keyAlias` / 以 `hyperduo` 开头的 Gradle property 名 /
  keystore 与证书。`Prefs.ACTION_RELOAD` **跟着包名一起改了**（`…action.RELOAD`）——
  它不是对外契约，收发的两端（`SettingsRepository.kt:177` 发送、`TrioConfig.java:157` 注册）
  都用同一个常量，只要同一版本内自洽即可。`AndroidManifest.xml` 一个字节都没改——
  组件名全是相对名（`.HyperDuoApp`、`.ui.MainActivity`、`.ui.MainActivityAlias`）自动跟随 `namespace`，
  FileProvider authority 是 `${applicationId}.fileprovider` 也自动跟随。
  离线验证：`:app:assembleDebug` `BUILD SUCCESSFUL in 37s`（`37 actionable tasks: 19 executed`，
  日志里出现 `compileDebugKotlin` / `compileDebugJavaWithJavac` / `dexBuilderDebug`，
  不是「全部 up-to-date」的假绿）；`aapt2 dump badging` → `package: name='io.github.yixing233.hyperduo'`；
  `aapt2 dump xmltree` → `application` / `activity` / `activity-alias` / FileProvider authority
  全部落到新包名，`de.robv.android.xposed.category.MODULE_SETTINGS` 仍在；APK 内
  `META-INF/xposed/java_init.list` 的内容是 `io.github.yixing233.hyperduo.HyperDuoModule`；
  按**斜杠描述符**扫 6 个 dex，`io/github/yixing233/hyperduo/…` 六个关键类全部命中，
  `com/hyperduo/trio` 与 `com/hyperduo/yixing` 零命中。
  **用户影响（必须写进 release notes）**：`applicationId` 变了就是另一个 app，
  旧的 `com.hyperduo.trio` **无法原地覆盖升级**，设置与 LSPosed 作用域都不迁移，需要先卸载再装。
  本文件上方的 v1.1 / v1.3 发布记录（字节核对、证书指纹、旧包名）是**历史事实，按当时原样保留**，
  未随本次改名回填。
- **卡信号环外样式与两个开关（第八轮补）**：新增 `signal_mode`（环内/环外）、`stacked_signal`、
  `data_sim_only` 三个键，环外读数按 350×350 参考图自绘（四条递增圆头柱 + 下方一排圆点，见
  「信号：环内与环外堆叠」一节）。本轮的判据收在 `TrioAppearance` 的 `signalDots()` /
  `stackedOut()` / `foldsMobile()` 三个谓词上，`TrioRenderer.drawOutSignal` 与
  `TrioHooks.OutSignalView` 各只有一处调用它们。
  离线验证：新建的 `work\outringcheck\verify.ps1` `exit 0`（`fixed` 通过；`refold` 与 `inert`
  两个反例构建分别 **3 条 / 14 条**不符而 `exit 1`）；既有四条 `gapcheck` / `slotcheck` /
  `dualsimcheck` / `simcheck` 复跑全部 `exit 0`；`:app:assembleDebug`（`--offline`）
  `BUILD SUCCESSFUL`，`compileDebugKotlin` 与 `compileDebugJavaWithJavac` 都真跑过，
  不是「全部 up-to-date」的假绿。
  **本轮由探针与出图抓出的三个缺陷**：
  1. `stackedOut()` 漏了 `mobile`（「显示移动信号点」关掉后环外堆叠仍会自绘，把用户关掉的
     那一项复活）。探针的断言原文是 `mobile meter off, out of ring stacked: no folding and no
     reading`。补上 `mobile` 后 `show_mobile` 关即「两个位置都交还系统」。
  2. 探针自身在**第一列**取样验底纹，而三点读数下第一列是亮的，断言恒假，看着像产品缺陷；
     改到第四列并补一条「未点亮的点仍在屏幕上」才通过（见「测试」一节的同名段落）。
  3. `STACK_DOT_GAP` 原是 10，实为 **9**（参考图无抗锯齿：柱底最后一行墨 237 即下边缘 238，
     点行首行墨 247，空带 `238..246` = 9）。这个错 `OutRingProbe` 抓不到 —— 它两边都引用同一批
     常量，自洽即通过；是 `OutSignalShot` 出图 + `compare.py` 拿**同一套量法分别量参考图与渲染
     结果**才暴露的（`STACK_INK_H` 210 → 209）。教训：证明「源码自洽」的工装不能证明「符合外部
     基准」，凡有外部参考目标的几何，都要有一件把目标本身也量一遍的工装。
- **双卡两排「各少一格」：读 MIUI 电平而不是 AOSP 电平（第九轮补）**：用户报「单卡时底部满格，
  双卡时上下两排都少一格」。根因在 `TrioState.levelOf` 原来取 `SignalStrength.getLevel()`
  （AOSP 口径），而 MIUI 状态栏用的是 `getMiuiLevel()`。实机 `dumpsys telephony.registry` 直接给出
  反证：Xiaomi 14 / 5G NR 下两张卡都是 **`miuiLevel = 4`、`level = 3`**（同一行里两个字段并存），
  系统画四格满格，双排照 AOSP 口径取就矮一格。jadx 侧佐证 MIUI 自己的取法：
  `work\jadx-out\sources\com\android\systemui\statusbar\connectivity\MobileSignalController.java:495`
  = `miuiLevel = signalStrength2.getMiuiLevel();`。`getMiuiLevel()` 不在公开 SDK 里
  （`javap` 查 SDK 37 的 `android.jar`，`android.telephony.SignalStrength` 只有 `public int getLevel();`），
  所以新增 `TrioState.miuiLevel(SignalStrength)`：`Refl.callByName(strength, "getMiuiLevel")`，
  非 Number 才退回 `strength.getLevel()`；0..4 之外的拒绝规则不变（MIUI 报 9 仍判 -1）。
  离线验证：`work\simcheck\verify.ps1` 从两构建升为**三构建**，第三个反例把
  `final int level = miuiLevel(strength);` 换回 `final int level = strength.getLevel();`（该正则必须
  恰好命中 1 处，否则脚本自己 `throw`）—— 固定版 `exit 0`（**47 条**断言全 `[ok]`）、
  「订阅号当槽位」版 `exit 1`（27 条不符）、「用 AOSP 电平」版 `exit 1`（**恰好 4 条**不符，且正是
  那 4 条 MIUI 断言，分离度精确到条）。`gapcheck` / `slotcheck` / `dualsimcheck` / `overview` 复跑
  全部 `exit 0`（`renderer = e96fd812ba2a…`）；`:app:assembleDebug` `BUILD SUCCESSFUL in 19s`，
  `app-debug.apk` **33434594 B**、sha256 **`4A0738A03E92D0828B8257C6FC76652D1FA86D22589D6ED3BD405F58EBA4032C`**，
  6 个 dex 里 `getMiuiLevel` / `miuiLevel` / `getLevel` 全部命中、`com/hyperduo` 零命中。
  **尚未在真机上复验「双排两排都满格」**（当时设备不在线；`adb mdns services` 返回空）——
  实机证据目前只有 `dumpsys` 那两个字段，没有装新 APK 后的截图。

- **环外信号尺寸异常与「环外信号大小」（第十轮补）**：用户报「环外信号尺寸异常」并要求「增加环外
  信号尺寸的调节功能」，并指出参考 `https://github.com/ColdP/HyperChanger`。新增 `out_signal_size`
  （int 百分比，默认 100 = 与状态栏图标同高，范围 50 – 200），UI 是尺寸卡里的 `IntSlider`，
  门禁 `a.stackedOut()`、提示指向 `master` / `show_mobile` / `signal_mode` / `stacked_signal`
  四条上游开关（**不是**本行自己的结果，否则关掉就再也推不回来）。
  **尺寸异常的真身**：`outSignalWidth(height, dots)` 的分母随点行在 209 / 150 之间切换，
  `drawOutSignal` 的 `scale = min(width/inkW, height/inkH)` 又用同一个较矮的框 —— **两处互相印证**，
  于是同一宿主高度下「无点行」（单卡、或开了「仅显示上网卡」）的柱被放大 `209/150 ≈ 1.39` 倍，
  单卡读数比双卡高出一大截。修法是**参照框只留 209**：新增
  `outSignalHeight(int anchorHeight, int percent)`（纯算术，`Math.max(1, round(h * percent/100f))`）、
  `outSignalWidth` 收成单参、`drawOutSignal` 的 scale 固定除以 `STACK_INK_H`，`outSignalInkH(dots)`
  降级为**居中用的墨高**。`TrioHooks.outSignalHeight(View anchor)` 是唯一的换算入口，
  `measure` 与变更比较都用它。
  借鉴来源：HyperChanger 的 `stacked_mobile_signal_scale` 是 **float** 且夹取 `0.1..3`，
  应用方式是给容器打 `scaleX/scaleY`；本项目取 **int 百分比**以贴合「所有既有设置项都是 int +
  `IntSlider`」的现状，且不缩放视图而是重算几何。
  离线验证：`work\outringcheck\verify.ps1` 从三构建升为**四构建**，第三个反例 `squat` 把
  `scale` 与 `outSignalWidth` **两行同时**改回旧分母（**只改一处看不见** —— 这正是该 bug 能长期
  存活的原因），固定版 `exit 0`、三条反例分别 3 / 14 / 3 条不符而 `exit 1`；`squat` 实测最高柱
  `51 vs 72 px`（约 1.39 倍本身）。既有四条工装复跑全部 `exit 0`。`:app:assembleDebug`
  （`--offline`）`BUILD SUCCESSFUL in 28s`。
  **探针自身的一条教训**：尺寸断言最初用**正确公式**去算取样列，而 `squat` 构建的几何恰恰是错的，
  取样点整个落在柱外，量到空画布（`51 vs 0 px`）—— 断言「两边一样大」时取样点必须与几何无关
  （改取全画布最长的亮柱 `tallestRun`），否则一条本意抓尺寸错的断言退化成在抓「那里没东西」。
  **其中「int 百分比」这一段已被后一轮取代**（见下条「第十一轮补」：单位改为 dp，键值也随
  字符串字面量改为 `out_signal_size_dp`）；本条其余事实（尺寸异常的真身、参照框只留 209、
  行内四构建与探针教训）仍然成立，未受那一次改动影响。

- **「环外信号大小」由百分比改为 dp（第十一轮补）**：用户报「而且下拉到控制中心后这个信号还会
  莫名其妙的放大,需要修复」。**机制**：旧值 `out_signal_size` 是电池容器**活高度**的百分比，
  而 MIUI 把那行在收起时常驻 88px、拉开控制中心后变成 134px（就是系统 `statusBars` inset 的
  高度，`dumpsys window displays` 实测），读数于是按 `134/88 ≈ 1.52` 倍跟着长 —— 这不是绘制
  错，是参照系本身会变。**修法**：单位换成 dp，`TrioRenderer.outSignalHeight` 的签名由
  `(int anchorHeight, int percent)` 改为 `(int sizeDp, float density)`，体是
  `if (sizeDp <= 0 || density <= 0f) return 0; return Math.max(1, Math.round(sizeDp * density));`
  —— 与那一行多高彻底无关。`TrioHooks.outSignalHeight(View host)` 只从视图取
  `getResources().getDisplayMetrics().density`，视图的测量高度**故意不取**（取了就等于把这个
  bug 请回来），两个调用点（变更检查 `view.getMeasuredHeight() != outSignalHeight(host)` 与
  `updateOutSignal` 里的 `final int height = outSignalHeight(host);`）都传 `host`；
  `TrioPreviewView` 自己从 display 取密度再调 `outSignalHeight(a.outSignalSize, density)`。
  **键与取值**：`Prefs.KEY_OUT_SIGNAL_SIZE` 这个常量名不变，只有它的字符串字面量从
  `out_signal_size` 改成 `out_signal_size_dp`；`DEF_OUT_SIGNAL_SIZE` `100 → 15`、
  `MIN_OUT_SIGNAL_SIZE` `50 → 6`、`MAX_OUT_SIGNAL_SIZE` `200 → 20`（`IntSlider` 读同一对常量，
  所以 `TrioSettings` / `TrioAppearance` / `SettingsRepository.kt` / `SettingsScreen.kt` 一行没动）。
  **不欠迁移**：那个旧键值从未随任何一次发布出货，框架也不做类型转换。`outSignalWidth(int height)`
  与 `outSignalInkH(boolean dots)`、`drawOutSignal` 里 `scale = Math.min(width / inkW, height /
  TrioGeometry.STACK_INK_H)` 都**未改**，`TrioPreviewView.HOST_ICON_HEIGHT_DP = 20f` 仍由
  `drawOutTypeLabel` 用着、没有删。**15dp 是实测来的**：测试机（小米 houji / Redmi K70，
  1200×2670，`Physical density: 480` 即 density 3）上 MIUI 自己那四条信号柱墨高 44px，
  `15 × 3 = 45` 正好同高；`6..20dp` 在同密度下是 18..60px，下界是四根柱仍分得清的最小值、
  上界是状态栏那一行在开始挤动邻居图标之前能容下的最大值。文案 `out_signal_size_summary`
  （`values` / `values-en` 两份）同步改写为 dp 说明。

- **1.4 发布（含包名更换 + 双卡/环外/按 MIUI 电平取格）**：第一次用「先提交干净、再跑 release.ps1」的流程走通（`release.ps1` 构建工作树但给 `HEAD` 打 tag，所以必须工作树干净）。提交 `a98053f` `release: 1.4, rename the package to io.github.yixing233.hyperduo`（30 files、4464 insertions / 1103 deletions）先 `git push origin main`（`release.ps1` 只推 tag、**不推分支**，必须单独推一次），再 `release.ps1 -Version 1.4 -NotesFile .tmp\modrepo\notes-1.4.md` → `exit 0`、`BUILD SUCCESSFUL in 14s`。tag `v1.4` 是指向提交 `a98053f68ffe076574053ae17ffdfda0fbe30cf9` 的 **annotated tag**（tag 对象 `058f6f54229c9807004cd7fecf9555dcdccee61a`，`git rev-parse "v1.4^{commit}"` 与 `HEAD` 一致；注意在 PowerShell 里 `^{commit}` 必须加引号，否则 `^{…}` 被当转义吃掉）。Release `HyperDuo 1.4` 非 draft / 非 prerelease（`published=2026-10-03T05:40:25Z`），asset `HyperDuo-1.4.apk` **3067544 B**。**发布产物按字节复核**：从 `https://github.com/yixing233/HyperDuo/releases/download/v1.4/HyperDuo-1.4.apk` 下载回来 SHA256 **`0B95AF03D704C3F52C7B015023F67EA3217C22299CD1AECDBB6ABC07D65BE527`** 与本地 `dist\HyperDuo-1.4.apk` 逐字相同。
- **1.4 发布产物的离线核对**（无设备，上机验证仍缺）：`aapt2 dump badging` → `package: name='io.github.yixing233.hyperduo' versionCode='10400' versionName='1.4'`、targetSdk 36、ABI 四套；`apksigner verify --print-certs` → v2 方案 `true`、证书 `CN=HyperDuo, O=HyperDuo, C=CN`、SHA-256 `b4e3a12d…8c41f`（与设备上原装 APK 同一证书，所以除改名那一次外，同包名的后续版本仍可 `-r` 覆盖）。
- **R8 每次构建字节都不同，但 1.4 这次只差一处**：用 `release.ps1 -DryRun` 重建一份后与已上传的那份对比，**85 个 zip 条目、CRC、时间戳全部相同、体积相同**，却有 339 字节不同、哈希不同。逐字节定位（`.tmp\apkcmp.py`）后锁定唯一差异在 `META-INF/version-control-info.textproto` —— AGP 把构建时的 git 版本写进了 APK：旧的写 `revision: "3743845…"`（改名前的提交），重建的写 `revision: "a98053f…"`（本次发布提交）。另外 4 段差异全在签名块与中央目录（签名覆盖了那个文件，所以连签名一起变）。**结论：重建的那份才是与 tag 一致的正确产物**，已用它覆盖模块仓 asset（`release-module.ps1 -Force` 重传；主仓是首发，无需 `-Force`）。两处 asset 与本地 `dist\HyperDuo-1.4.apk` 现在**三者同一哈希** `0B95AF03…BE527`。
- **PS 5.1 读 BOM-less UTF-8 脚本会按 ANSI（本机 `gb2312`）解码，直接 `& .\release.ps1` 必然解析失败**（报 `Missing expression after ','`、`The string is missing the terminator` 等一串错，且错误里中文全是乱码 —— 这是判据）。`release.ps1` / `install.ps1` / `.tmp\modrepo\release-module.ps1` 都是 BOM-less UTF-8 且含中文，所以在这台机器的 Windows PowerShell 5.1 下只有两条路：装 pwsh 7，或 `[scriptblock]::Create([System.IO.File]::ReadAllText($p,[Text.Encoding]::UTF8))` 后调用（脚本内硬编码 `$Root`、不用 `$PSScriptRoot`/`$script:`，所以这样调用安全）。本次用后者，两个脚本都以 `exit 0` 跑完。**要长期可用，应给这三个脚本加 UTF-8 BOM**（未做，属本次范围外）。
- **LSPosed 模块仓首发 release**：模块仓 `Xposed-Modules-Repo/io.github.yixing233.hyperduo` 按仓库规格发 tag `10400-1.4`、title `1.4`、body 取 `.tmp\modrepo\notes-1.4.md`（首行是「安装前必读」的包名更换说明），asset `HyperDuo-1.4.apk` 3067544 B；`README.md` 同步更新到 1.4（`PUT contents` 带旧 sha → 新 sha `fab6b22eb153025b808a3ead27db6a4a5abae4fc`、commit `cb10135`），`SUMMARY` 未动。驱动脚本 `.tmp\modrepo\release-module.ps1`（只走 Releases API，模块仓不克隆到本地；`maintain` 角色不能改仓库设置但**能发 release**，已实测 `POST /releases` → 201）。
- **模块仓「已上线但未收录」的唯一门禁 = 仓库 description 为空**：抽样 `Xposed-Modules-Repo` 24 个仓库逐个探 `https://modules.lsposed.org/module/<pkg>/`，结果是「描述空 + 404」4 个（含本项目）、「描述空 + 已收录」**0** 个、「描述非空 + 已收录」20 个。所以 release 发了、README 有了也仍 404（`io.github.yixing233.hyperduo` → 404，对照 `io.github.kvmy666.duostatusbar` → 200）。而 **description 目前无法由 API 设置**：`PATCH /repos/Xposed-Modules-Repo/…` → 404 Not Found；GraphQL `updateRepository` → `FORBIDDEN: yixing233 does not have the correct permissions to execute 'UpdateRepository'`（尽管 GitHub 角色表上 maintain 本应有 `Edit a repository's description`，且同 token 的 `PUT …/topics` → 200，证明 token 与路由都正常）。⇒ **只能在 GitHub 网页的 About 面板手填**，文案已备：description `HyperDuo · 仿 iPhone Duo 三合一状态栏图标`（`.tmp\modrepo\desc.txt`）、homepage `https://github.com/yixing233/HyperDuo`（`.tmp\modrepo\homepage.txt`）。**（这一项后来已由用户手填完成并成功收录，见本节末尾。）**

- **模块仓 README 补预览图（1.4 之后）**：模块仓 `Xposed-Modules-Repo/io.github.yixing233.hyperduo` 的 `README.md` 原先只有纯文字，已在三处插入 `<img>`（用 HTML 标签而非 Markdown 语法，就是为了显式控制 `width`）：第 5 行标题下 `statusbar.png`（`width="900"`，真实状态栏字形）、第 48 行新增 `## 效果预览` 一节插 `states.png`（`width="820"`，六种状态）、第 70 行 `## 设置` 一节插 `settings.png`（`width="420"`，设置界面整屏）。图片**不落在模块仓里**，而是引用主仓的图：`https://cdn.jsdelivr.net/gh/yixing233/HyperDuo@main/docs/images/<name>.png` —— 模块仓按规格只放 `README.md` + `SUMMARY` 两个文件（`git clone --depth 1` 复核确认仍只有这两个），所以图片只能外链；选 jsDelivr 而非 `raw.githubusercontent.com` 是因为对照仓 `io.github.kvmy666.duostatusbar` 的已上线 README 用的就是 jsDelivr，且 jsDelivr 自带 CDN 与 `@main` 缓存语义。
- **外链可达性与字节一致性已实测**：`raw.githubusercontent.com` 与 `cdn.jsdelivr.net` 两条路对 `docs/images/` 下三张图都是 HTTP 200 且 **SHA256 与工作树逐字相同**（`statusbar.png` 155272 B / 4800×384、`states.png` 37408 B / 1124×405、`settings.png` 131178 B / 1200×1780；三张图 git 内已跟踪且工作树无改动）。GitHub 侧的渲染也用 `Accept: application/vnd.github.html+json` 拉 `readme` 端点验证过：三个 `<img>` 都正常生成（GitHub 把它们经 camo 代理，`data-canonical-src` 仍是我们的 jsDelivr URL），说明语法与地址都没问题。写入用 `PUT /repos/Xposed-Modules-Repo/io.github.yixing233.hyperduo/contents/README.md`（带旧 sha `fab6b22eb153025b808a3ead27db6a4a5abae4fc`）→ 新 sha `00ccd134494a60e6ba89a1aa4f022e98cb92fbc9`、commit `1a43a3f`；拉回来与本地暂存件逐字比对为 `True`（11004 B）。`SUMMARY` 未动（模块仓首页摘要仍是不含图片的一小段）。
- **description / homepage 已由用户手填完成**（这是上一节留下的唯一阻塞项）：`GET /repos/Xposed-Modules-Repo/io.github.yixing233.hyperduo` 现在返回 `description = 'HyperDuo · 仿 iPhone Duo 三合一状态栏图标'`、`homepage = 'https://github.com/yixing233/HyperDuo'`、`topics = lsposed, statusbar, xposed`。**但模块页此时仍是 404**（`https://modules.lsposed.org/module/io.github.yixing233.hyperduo/` → 404，对照 `io.github.kvmy666.duostatusbar` → 200）——按仓库说明，收录要等上游 Cloudflare Pages 重新构建（说明写的是 5–15 分钟），**所以「填了 description 就立刻 200」并不成立，需要按时间复验**。
- **模块页已收录（description 手填后约 23 分钟）**：`https://modules.lsposed.org/module/io.github.yixing233.hyperduo/` 在 **14:14:43** 由 404 转为 **200**（后台轮询 60 s 一次，`?cb=`/`Cache-Control: no-cache` 都不能提前穿透缓存，只有上游重新构建才生效；description 手填时刻约 05:51:27Z，即上游站点约 23 分钟后才重建）。**判据不能只看详情页 404**：同一时刻站点首页 `https://modules.lsposed.org/` 的 `astro-island` props 里已经出现本模块（`"name":"io.github.yixing233.hyperduo"`、`description` 为手填的那串、`url` 指模块仓，列表行 `<h2>` 显示手填 description、`Website` 指主仓、`Source` 指模块仓、`<time dateTime="2026-10-03T05:58:49Z">`），所以**「首页已列出、详情页还 404」是重建过程中的中间态**，等待期间不要据此判定失败。
- 收录后的详情页（36628 B）核验：`<title>` = `HyperDuo · 仿 iPhone Duo 三合一状态栏图标 · Xposed Module Repository`；README 的三个 `<img>` 全部渲染出来，`data-canonical-src` 仍是我们的 jsDelivr 地址（`statusbar.png` width 900 / `states.png` width 820 / `settings.png` width 420）；下载链接是 `download/10400-1.4/HyperDuo-1.4.apk?sign=…`，即模块仓那个 release。
- 工具坑：详情页直连偶发 `000`（curl 超时/连接失败），**必须 `curl.exe -sL` 跟随重定向并加重试**，否则会把网络抖动误判成 404（无斜杠的 `.../hyperduo` 返回 `308`，`-L` 才能跟到 200）。
- **「只保留最新版 Release」落地（1.4 之后）**：两个脚本各加一个 `Remove-StaleReleases`
  函数（`GET releases?per_page=100` → 过滤 `$_.id -ne $KeepId -and -not $_.draft` → 逐个
  `DELETE /releases/$id`）和 `-KeepOldReleases` 开关，都在**上传 asset 之后**才调用。
  主仓提交 `f90046e`（`release.ps1`，`1 file changed, 39 insertions(+), 1 deletion(-)`）；
  模块仓脚本见下条。**执行前先验证可恢复性**：把 4 个远端 asset 全部下载回来与 `dist\` 同名
  文件逐个比 SHA256，得到 `v1.4 3067544 B` / `v1.3 3038276 B` / `v1.1 3038076 B` /
  `v1.0 2560743 B` **四个 `identical=True`**，确认字节完全一致后才 `DELETE` 掉
  `v1.3`/`v1.1`/`v1.0`（保留 `v1.4`）。清理后 `git ls-remote --tags origin` 仍是八个 ref
  （四个 tag 各有 annotated + peeled 两条），`GET /repos/yixing233/HyperDuo/releases/latest`
  → `tag=v1.4`、asset `HyperDuo-1.4.apk` 3067544 B，**应用内更新器不受影响**。
- **模块仓发布脚本已从 `.tmp\modrepo\release-module.ps1` 移到仓库根 `release-module.ps1`**：
  它原先落在 `.gitignore` 的 `/.tmp/` 里，而 `docs/DEVELOPMENT.md` 却在引用它 ——「每次发布
  都这样」的策略放在被忽略的目录里等于不可复现。现在它与 `release.ps1` 并排入库。**该文件
  刻意保持 ASCII-only 且无 BOM**（`nonASCII=0`、7702 B），所以 PS 5.1 按 ANSI 读也无害；
  文件头注释已写明这条约束，**不要往里面加中文**。验证：`& .\release-module.ps1 -Version 1.4
  -VersionCode 10400 -Apk .\dist\HyperDuo-1.4.apk -DryRun` → 版本三者全对、`exit 0`。
- **包名更换（`a98053f`）曾把两个脚本的 UTF-8 BOM 弄丢，造成真实回归**：`release.ps1` 在
  `bcb7f9b`/`0cc4d5b`/`03b36b5` 三个历史版本里都是 `BOM=True (239,187,191)`，到 `a98053f`
  变成 `BOM=False (35,32,230)`；`install.ps1` 在 `41ef355` 是 `BOM=True`、`a98053f` 也丢了。
  后果：本机 `[Parser]::ParseFile` 读 `release.ps1` 报 **9 个 parse error**（`line 11: Missing
  expression after ','`、`line 44/55: Unexpected token …`），即**文档里的调用方式
  `.\release.ps1` 当时根本跑不起来**；`install.ps1` 则 parse errors 为 0 但运行时打印乱码
  （正是 §「教训（PowerShell 5.1 与 BOM）」记过的那个陷阱）。修复见提交 `39c4b45`
  （`2 files changed, 2 insertions(+), 2 deletions(-)`，每文件仅第 1 行 1 增 1 删）。
  **回归测法**：`ParseFile` 数 errors + 用 `[System.IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)`
  核对中文是否正常，两条都要做。**教训：凡是"改包名/批量替换"式的全局改写，收尾必须复验
  BOM 与行尾**，因为这类改写常顺手重写整个文件。
- **`.gitattributes` 已经规定 `*.ps1 text eol=crlf`，且仓库 `core.autocrlf=false`**，所以
  `git add` 时出现的 `warning: in the working copy of '...', LF will be replaced by CRLF`
  是**无害的**（索引里存的仍是 LF，工作树保持 LF）。不要为了消掉这个警告去改脚本行尾。
- **环外视图前景色跟随深浅色（第十二轮补）**：用户报「自行绘制的环外信号类型，像 5G 的字体颜色，
  似乎有跟随状态栏文本变色的逻辑……但是它的更新不是很及时」。根因与修法见「实时刷新」下的
  「环外视图的前景色（深浅色跟随）」一节：`hyperduo-draw` 钩子比较
  `state.foreground()` 与 `TrioState.outRingInk`，只在不等的帧记账并 post 一次
  `recolourOutRing`。**这一条只有真机能验**（改深浅色的那一下是否立刻跟上），当前设备离线，
  上机验证仍缺；离线侧只保证编译不回归、五条工装（`gapcheck` / `slotcheck` / `dualsimcheck` /
  `simcheck` / `outringcheck`）全部维持原判，外加出图工装 26 格照常渲染。
  `TrioHooks` 需要 Xposed API、桌面上编不了，所以这条规则像该文件里其它几条一样，改成在
  `work\outringcheck\verify.ps1` 里加**源码钉子**：四条成对断言（只在不等的帧记账 / 先记账再 post /
  标签拿到新墨色 / 读数被要求重绘），外加两条形状断言（`OutSignalView.onDraw` 上那句「tint 变化会
  让它失效」的错误注释不得复活；`outRingInk` 必须是实例字段，而 `foreground()` 必须仍把 0 折成
  `DEFAULT_FOREGROUND`，否则首帧会被当成「没变」而跳过）。七种回归形态各自实测都能把钉子碰响。
  另一条能离线做的判据是**产物核对**：`app\build\outputs\apk\release\app-release.apk` 的
  `classes.dex` 同时含 `recolourOutRing` 与 `outRingInk`（debug 包在 `classes3.dex`），
  证明改动确实进了产物而不是被增量构建漏掉。
