# Android 本地验证记录

## v1.0 版本信息更新（2026-10-16）

- 按用户指定范围，仅将当前源码改为 `versionName 1.0` / `versionCode 11`，同步应用内两处可见版本、构建脚本默认输出文件名、README 和仓库版本说明。功能、包名、壁纸及 SDK 配置保持原样。
- 未构建或发布 v1.0 APK。现有 v0.1.7 发布页、标签、APK、源码附件及 SHA-256 校验文件保持原样；README 继续明确链接该旧版 APK。
- 版本一致性检查及改动范围检查通过：MainActivity 的全部差异仅为两处 `v0.1.7` → `v1.0`；Android 目录仅改版本配置及这两处文字，未改业务逻辑。
- 使用 Windows、Corretto JDK 17.0.10、Node.js 24.19.0 对修改前提交 `c7aee0944d93a9cbcdecba20db8bbc1fd736c6ff` 和本次源码分别执行同一组回归。Java 测试源码编译通过；解析 15 项、BatchGate 2 项、JavaScript 6 项及队列 12 项均通过。
- TestRecovery 和 TestOwnership 两个存储测试组在 Windows 上因 `RecoveryStore.writeAtomic` 中 `File.renameTo` 未成功而失败；修改前与修改后的退出码、PASS 数一致。两个组在失败前另输出 5 条 PASS，全部已执行测试共输出 40 条 PASS；不能据此宣称完整回归通过。本次未修改这些存储代码。
- v1.0 未执行 Android SDK 构建、设备安装或 Pixiv 线上操作；下方 v0.1.7 的历史构建证据仅属于旧版。

## v0.1.7 历史验证记录

日期：2026-10-13。基线：上传的 v0.1.6 源码（导入提交 `d9d1cb8`）。本记录替换旧版验收记录；旧截图仅作参考。

## 环境与范围

- OpenJDK 17.0.20、Node.js、Python 3；使用内置 `node:vm` 网络夹具和真实 Java 核心类。
- 源码测试阶段没有 Android SDK；随后使用已缓存的 Android SDK35/Build Tools35与可用JDK17，完成 MainActivity/资源编译、DEX生成、APK测试签名和对齐校验。没有模拟器或手机，未验证实际安装运行。
- 未登录真实 Pixiv，未提交任何线上收藏操作。HTTP 429/403、网络故障、并发和取消通过本地夹具模拟。
- 未测量真实网络速度，取消接口一次传多条的线上兼容性尚待验证。代码在批次错误时停止，不自动重试。

## TDD 证据

生产修改前增加测试并实际运行：

- 解析：BOM/分隔 ID 列表预期 4 条，原解析器返回 `[]`，失败。
- 队列：10 项行为夹具初次失败，原版本无 batch queue API。随后在真实 JS 中运行并验证通过；保留原 6 项回归。
- 恢复/导出：初次失败 `durable recovery/export core required`；实现核心后 5 组行为全部通过。
- 额外缺陷：五条取消预读遇到 429 后仍发起未准入的后续查询，新增用例 RED 后修复；暂停时另一工作线程等待继续使 429 无法终止队列，新增用例 RED 后修复。
- 原生 gate 增加任务令牌种子：RED 为缺少构造接口，GREEN 验证种子递增和全部既有 lifecycle 行为。Activity 使用随机正数种子以避免进程重建复用旧任务令牌。

## 最终回归

```bash
python tests/run-tests.py
```

退出码 0，51 行 PASS：

| 组 | 结果 | 覆盖 |
| --- | --- | --- |
| ID 解析 | 15/15 | 原 5 项；BOM、分隔符、编号、超长数字、ASCII/Unicode authority、连续作者列表与段落边界 |
| BatchGate | 2 行 PASS | 原生命周期、重复/迟到回调、取消、来源验证；令牌种子与延迟启动回调代次保护 |
| RecoveryStore | 11 组通过 | 原恢复/导出/TXT；独立 journal 恢复、损坏隔离、封装长度/摘要/完整 schema、UI 已验证备份、旧导出不替代新操作 |
| 跨实例/所有权 | 5 组通过 | 同目录 journal 并发事务、退役/旧 revision 拒绝、export UUID+runtime lease、同首页导航代次 |
| 原 JS 回归 | 6/6 | 画师 ID、公开/私密转换、同模式跳过、新增、取消 |
| JS 队列 | 12/12 | 查询 <=2、修改 <=1、报告顺序、仅 AJAX、5/5/2 取消组、暂停/继续、停止/abort/迟到回调、新任务拒绝、待核验、限流、逐 ID 错误 |

测试入口自动发现 Java/Node，缺少 javac 启动器时使用 `java -m jdk.compiler/com.sun.tools.javac.Main`。测试没有下载 npm 或其他网络依赖。

## 代码层面的检查

- 保持 minSdk 24、target/compileSdk 35、原 applicationId；更新 versionName 0.1.7 / versionCode 10 和可见版本。
- 保留两张壁纸资源和原有登录、TXT、公开/私密选择、结果/日志及展示模式入口。
- 修改提交前在 WebView bridge 线程写入私有文件提交记录；文档提供器 I/O 在单线程 executor 上。
- 写出文件前读取并验证持久导出快照，缺失/不匹配/空白时未打开输出流。
- 暂停时移除 native 60 秒 watchdog；活动队列结果/事件按任务 token 检查，停止使 gate 失效并取消 JS fetch。
- 旋转采用 manifest configChanges 保留当前 Activity/WebView；实际重建从文件恢复为停止。大文本不放进 Bundle。

上述 Activity 集成属于源码检查，不能替代 Android 编译或设备测试。

## 尚需验证

1. 使用 Android SDK 35 编译 Java/资源并构建 APK；在 minSdk 24 和现代版本安装运行。
2. 活动队列、暂停队列及文件选择器期间旋转；确认壁纸、输入、模式、日志和进度保留。
3. 杀进程后恢复停止状态与待核验项；选择器重建后正确写入原快照。
4. 真正的 Pixiv 会话/CSRF 页面变化、权限/验证页、429/403、可见性修改及 5 条取消接口支持。
5. 真实网络下的速度与限流，按线上结果调整保守节奏；任何未确认修改不自动重放。

私有文件系统故障、进程在落盘边界退出可能保守显示已核验或实际未发出的项为待核验；这样避免遗漏不确定的修改。后台进程可能被 Android 回收；本版没有新增持续后台服务。

## 独立任务审查修复（第一轮）

针对 a751b98 的五项反馈，新增失败回归并修复：损坏 UI 快照不再阻断有效提交记录读取；状态/导出使用版本1、完整长度和 SHA-256 封装及完整字段验证；只允许 UI 状态回退到已验证备份，损坏导出直接隔离/拒绝，不使用旧同类型内容；完整 URL 的 authority 校验涵盖 Unicode 前缀与 userinfo；作者语境连续跨数字/编号行；evaluateJavascript 启动结果使用捕获 token 和与生产共用的 BatchGate 决策。

额外验证了：自洽封装内字段缺失仍不能当作完整状态；等长度内容修改也被摘要拒绝；磁盘存在旧同类型有效导出时，损坏的新快照仍拒绝输出。第一轮最终完整回归退出0，44行 PASS，原 JS6/队列12 全部保留。

历史待核验记录仍按原任务 token 保留。后续任务对同作品的成功核验不会自动清除早期未知结果；README 明确说明这些可能重复出现的持久历史警告以及当前没有单独确认/清除入口。保持原 token 隔离，避免异步的新任务核验误清除其他提交记录；本轮未增加新的确认工作流。

原生延迟 callback 的纯 Java 决策已覆盖并用于 MainActivity，但真实 WebView 回调安排、配置重建/选择器、跨实例 private-file 写入调度和设备文件系统仍未执行验证。SDK、APK、设备、线上 Pixiv 与提速测量的限制不变。

## 第二轮针对备份缺失主文件的修复

第一轮新增备份路径在 task-state 主文件缺失时曾直接返回空状态。新增回归实际 RED 为预期保存输入、得到 null；修复后只对 UI 状态读取/验证备份并重建主文件。继续保持停止语义，随后普通 checkpoint 不会用空默认值覆盖已验证内容。缺失主文件而备份无效时拒绝并隔离保存无效字节；导出/提交记录不增加任何备份回退。

第二轮最终 `./tests/run-tests.sh` 退出0，46行 PASS（解析15、BatchGate2、RecoveryStore11、原JS6、队列12）。完整本地日志记录为 fix2-green-regression.log。实际 Android 进程死亡时序仍未在设备执行；SDK/设备/线上接口限制不变。

## 最终综合审查修复波次

最终审查的 F1–F4 已集中修复：canonical directory 共用进程协调器，完整 journal 读改写/atomic 事务串行且临时文件唯一；Activity owner 与捕获时 snapshot revision 在提交点校验，UI/bridge旧写入不能盖掉新状态，状态IO独立于提供器；export 使用持久 UUID 与跨Activity runtime lease，旧取消/完成只 compare-and-clear 原操作，运行中的 provider 不被新页面重复接管；每次owned首页导航使用新 WebView 和捕获的导航代次，旧实例完成及主框架错误均拒绝，批次内仍复用resident页面和全局Cookie会话。

新增 TestOwnership 的4组回归先 RED：旧清理实际丢失新 journal、其余三个生产决策不存在；修复后全套 `./tests/run-tests.sh` 退出0，50行 PASS。原 parser/Gate/Recovery 与 JS6/queue12 保持通过。跨实例事务测试仅在JDK17测试进程使用 deprecated SecurityManager 写入屏障，有4行预期弃用警告；生产无此API。Java8源码语法解析只是 syntax check，不是 Android 类型/API编译。

onDestroy 退役后尚未成功提交的 checkpoint 被丢弃，恢复最后成功落盘状态及独立的提交journal；不在UI等待云端/provider结束。重建的文件选择回调等待UI恢复完成后再处理。真实Android生命周期、WebView callback/共享Cookie、picker与provider调度、文件系统/SDK编译仍未执行；线上Pixiv与速度/五条取消支持限制，以及保守历史待核验警告政策都不变。

## F3 同 UUID 取消交错的最后定点修正

综合复核确认F1/F2/F4通过，发现恢复对话框旧取消可能在替代页面恢复同一UUID后清理其快照。本次仅修该路径：queued negative action保留captured lease，不由onDismiss先释放；后台准入验证当前owner与exact lease，owned compare-and-clear再次验证lease。退役前尚未准入的旧取消拒绝，已准入的清理/provider完成可在原lease仍保留时结束，成功/异常/无法排队均finally式释放原token。普通无动作dismiss仍释放等待lease。

新same-UUID回归先RED缺少授权路径，再GREEN验证替代恢复内容不受旧lease删除、已准入操作跨retire保留、失败finally释放。完整runner退出0，51行PASS，Java8源码仅语法解析4个production文件通过；实际Android编译/设备时序与其他已列限制仍未验证，其他三项源码未扩改。

## GitHub 发布前 APK 构建校验（2026-10-14）

源代码提交386e2236ed2c8d31f0fa154dc5b5c3bd7ebb4691，APK构建期间源码未改变。完整51项本地回归证据仍对应同一份生产代码。Android SDK35资源链接、Java8目标字节码编译和D8通过；APK v2/v3签名及zipalign通过；包名com.openai.pixivbatchbookmark.wallpaper、版本0.1.7/code10、minSdk24、targetSdk35、启动Activity均核对。APK ZIP CRC、DEX结构/校验和、JS资产内容及两张壁纸资源检查通过，APK不包含签名私钥。

APK SHA-256：f9a25f84b95589e90c031df5447d05e6813c94b5adcc84e186a474d6b3aa06de。
证书SHA-256：34ee0bf40b07747de790b80d91faa9e1d32866025eb8e917f9a32e7927712f2d。

本文早期轮次描述没有SDK/没有APK是当时的验证范围；本节补充后续实际构建证据。实际Android安装、生命周期和WebView/picker/provider时序、Pixiv线上接口与速度依然未验证。
