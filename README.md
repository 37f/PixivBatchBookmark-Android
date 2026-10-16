# Pixiv 批量收藏工具 Android v1.0 快速队列·双壁纸版

当前源码版本名 `1.0`、`versionCode 11`，由 v0.1.7 仅更新版本信息，功能保持原样。保留独立包名 `com.openai.pixivbatchbookmark.wallpaper`，`minSdk 24`、`targetSdk / compileSdk 35`，两张原壁纸均保持原样。

本次只更新仓库版本信息，未构建或发布 v1.0 APK。下方继续提供原 v0.1.7 APK，原发布页、附件和校验值保持原样。

## 下载现有 APK（v0.1.7）

[下载 v0.1.7 APK](https://github.com/37f/PixivBatchBookmark-Android/releases/download/v0.1.7/PixivBatchBookmark_Android_v0.1.7_fast_queue.apk) · [发布页面](https://github.com/37f/PixivBatchBookmark-Android/releases/tag/v0.1.7)

最低 Android 7.0（API24），版本0.1.7 / versionCode10。此 APK 使用测试签名；旧安装签名不同则不能覆盖安装，卸载前先备份数据。签名私钥不在本仓库或发布附件中。

Android SDK35 的资源/Java编译、DEX生成、APK v2/v3签名、zipalign、包信息和ZIP完整性校验通过。真机安装与 Pixiv 线上接口、实际速度仍待验证。

APK SHA-256：`f9a25f84b95589e90c031df5447d05e6813c94b5adcc84e186a474d6b3aa06de`。

## 快速队列

- 每个批次先核验登录并取得一次 CSRF，之后复用同一已核验的 Pixiv 首页和 WebView 会话，不逐张加载作品 HTML。
- 每次登录/检查的首页导航使用新的 WebView 页面实例并绑定代次，复用全局 Cookie 会话；旧页面完成/主框架错误不能影响替代任务。每个批次内页面仍保持不变。
- 查询最多 2 个并行，所有修改请求严格串行；修改起点至少间隔 350 ms（网络与核验时间也计入间隔），组间短暂间隔 120 ms，替换原固定每张 1.4 秒等待。
- 新收藏使用 `/ajax/illusts/bookmarks/add`；已有收藏模式相同则跳过，模式不同通过 `/ajax/illusts/bookmarks/edit_restrict` 原位修改，保留原标签和备注。
- 取消仅检查输入中的作品，先取得真正的 `bookmarkData.id`，每组最多 5 条收藏记录传入 `/ajax/illusts/bookmarks/remove` 的 `bookmarkIds`，再逐个核验。不把作品 ID 当收藏记录 ID，不扫描整个账号收藏。
- 多条取消接口的线上支持未在本次验证；出现批次错误就停止，不盲目缩小批次或重放修改。
- 作品 404 会探测是否误输画师 ID；这个探测中的 403/429 也会传递并停止队列。不存在的作品逐条报告，其他普通读取错误不会被当作收藏成功。

## 暂停、停止与恢复

- 暂停后已发出的请求会完成，之后不发出新请求；核验也可能等待“继续”。暂停期间停用原生无进展超时，继续保留原队列。
- 停止会使旧回调失效并 abort 待完成 fetch；无法撤回服务器可能已接收的修改。已发出但未核验的修改明确显示“待核验”，不会自动重试。
- 一个新任务不能静默替换正在运行的批次。批量取消仍有应用内确认提示，取消会删除该收藏的标签和备注。
- 旋转及声明的常规配置变化保留当前 Activity/WebView，保留输入、模式、结果、日志、进度与活动任务。
- 实际页面重建或进程退出后从私有文件恢复输入、结果、日志、模式和进度，任务恢复为停止状态。修改提交前先持久记录作品 ID，恢复时明确显示待核验项；可能保守包含实际未发出或已核验但尚未落盘的项，请先到 Pixiv 核对。
- 恢复快照不保存 CSRF、Cookie 或密码，新的批次重新检查会话。快照和导出文件使用带版本、完整长度、SHA-256 摘要及字段校验的封装；损坏文件保留隔离副本。UI 主文件缺失或损坏时，状态可回退到已验证的前次快照，待核验提交记录始终独立读取。单批最多 10000 个作品，单个恢复/导出快照最多 8 MiB；同一规范目录使用进程共享协调器串行处理完整文件事务。页面取得 owner 后再恢复；快照在捕获时分配 revision，退役 owner 或更早 revision 的写入被拒绝（包括 bridge 捕获的旧状态）。状态写入使用独立后台队列，慢文档提供器不会挡住状态保存。重建恢复最后成功提交的快照，未落盘的退役 checkpoint 不重放；无法安全保存提交记录时不发出新的修改。

历史待核验记录按原任务令牌保留。后来新任务确认同一作品，不会自动清除早期任务的未知结果；恢复提示可能再次出现。这是持久的历史警告，当前没有单独的确认/清除入口，请根据 Pixiv 实际状态理解这些记录；应用不会因这些记录自动重试。

## TXT、解析和导出

- 支持 UTF-8 BOM、纯作品 ID、编号列表，以及逗号、中文逗号、分号、中文分号、空白分隔的纯 ID 列表。
- 继续排除画师/作者/用户上下文的含糊 ID，保留明确作品链接和 PID；自动去重。不截断超过 12 位的数字；作品和用户链接都解析完整 URL 并验证真实 host、端口和用户信息，不将 Unicode 或其他非 Pixiv 域名当作品链接。画师语境沿连续数字/编号行保留，直到作品提示、作品链接或新的文本/分隔段落。
- TXT 导入限制为 2 MiB，超过会提示拆分；文件提供器的读取和写入在后台线程执行。
- 导出结果和保存日志在打开系统选择器前保存完整快照。选择器重建或进程退出后仍使用该快照；先验证内容存在且匹配请求，再打开输出文件。没有快照时不写空白文件并宣称成功。
- 不完整、摘要不符或字段缺失的导出会在打开输出文件前拒绝；不会用旧的同类型导出快照替代当前内容。
- 同一时间只处理一个文件操作，运行时 lease 跨页面实例协调；已在进行的提供器 I/O 会持有 lease 到结束，替代页面不能同时开始第二个文件读写。进程退出后运行时 lease 消失，持久导出操作 UUID 保留，恢复/读取/取消/完成必须匹配这个 UUID；旧回调不能清除新的同类型操作；取消清理还必须匹配捕获的 lease，即使替代页面恢复同一 UUID，退役旧取消也不能删除它。对话框排队取消持有 lease 到后台 finally，未选择继续/取消的普通关闭可立即释放。取消选择器会清除对应快照；写入失败时保留快照，同类型导出按钮可继续或取消上次保存。

## 使用

1. 开启系统 VPN/NekoBox VPN/TUN，确保本应用未被分应用规则排除。
2. 点击“登录 Pixiv”，只在 Pixiv 官方网页输入账号密码；返回后可“检查登录”。“清除登录”只清除此应用 WebView 的登录会话。
3. 输入或导入作品 ID/链接，解析后选择公开或私密收藏，再开始任务；需要取消时使用“批量取消收藏”。
4. 可暂停、继续、停止；停止或错误后先核对待核验项，再决定新的操作。任务结果和日志可保存为 TXT。
5. 底部可选择原秋叶壁纸或粉色兔耳壁纸，展示模式按完整比例显示；返回工具保留任务状态，选择会保存。

## 本地测试

无需网络依赖，使用 JDK 17、Node.js、Python 3：

```bash
python tests/run-tests.py
# 或
./tests/run-tests.sh
```

若工具不在 PATH，传 `--java-root /path/to/jdk --node /path/to/node`，或设置 `JAVA_HOME`。没有 `javac` 启动器时，测试脚本使用 `java -m jdk.compiler/com.sun.tools.javac.Main`。

覆盖原有 5 项解析、BatchGate 生命周期组、6 项 JS 回归，加上 10 项解析、队列启动回调代次检查、11 组持久化/导出/TXT 测试、5 组跨实例存储/快照代次/导出 lease/导航代次测试和 12 项真实 JS 队列网络夹具测试。新事务并发测试用 JDK17 SecurityManager 提供测试线程调度屏障，会出现预期弃用警告；生产代码不使用 SecurityManager。具体结果及限制见 VALIDATION.md。

## 构建与设备验收

Android Studio 可打开项目。命令行构建需要 Python 3、JDK 17、官方 Android SDK 35 / Build Tools 和自己的签名密钥。源码不含签名私钥，构建脚本不会自动生成密钥：

```powershell
$env:JAVA_HOME = 'C:\Android\jdk17'
$env:ANDROID_SDK_ROOT = 'C:\Android\sdk'
$env:KEYSTORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Keystore password' -AsSecureString)).Password
python build-apk.py --keystore 'C:\Private\signing.jks' --key-alias 'your-key-alias'
Remove-Item Env:KEYSTORE_PASSWORD
```

默认输出 `dist/PixivBatchBookmark_Android_v1.0_fast_queue.apk`，默认不覆盖已有文件。升级现有安装需要相同签名证书。

2026-10-14 已构建 v0.1.7 APK，并通过 Android Java/资源编译、DEX生成、签名与对齐校验。v1.0 本次仅更新版本信息，未重新构建 APK。未运行 Android 设备或模拟器，未登录真实 Pixiv、未提交线上收藏，也未测量真实网络提速。`docs/` 截图与 `tests/test-wallpaper-ui.py` 属于继承的 v0.1.6 资料，不能当作 v0.1.7 或 v1.0 的运行证据。设备验收应补充活动/暂停期间旋转、进程恢复、选择器重建、超大 TXT、429/403、停止后迟到回调以及真实取消批次接口支持情况。
