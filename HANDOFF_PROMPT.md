# RikkaHub 项目提示词（交接必读）

## 一、项目概况
- 项目根：`/storage/emulated/0/MobiCode Studio/Projects/rikkahub-3.1.1`
- 包名：`me.rerere.rikkahub`（RikkaHub 3.1.1，Kotlin + Jetpack Compose）
- 本仓库为增强版：扩展商店、本地工具链、Shizuku UserService 等均为定制新增。

## 二、构建与发布流程（重要）
1. 本地打包源码：
   ```bash
   git ls-files > /tmp/files.list
   echo "app/keystore/release.keystore" >> /tmp/files.list   # keystore 不入 git，必须手动加
   tar -czf /tmp/src.tar.gz -T /tmp/files.list
   ```
2. 更新 `RELEASE_NOTES.txt`（本次修改说明，中文，简短分条）。
3. 执行 `/data/local/tmp/upload_src.js`：把 `src.tar.gz` 与 `RELEASE_NOTES.txt` 上传到 GitHub 仓库 `dormantxxy/rikkahub-local-tools`，更新 main 触发 `build.yml` 构建。
4. **每次构建必须发布新 Release，版本不覆盖**：`build.yml` 使用 `tag_name: v3.1.1-build${run_number}`，每次 run_number 递增，旧版本全部保留。Release 说明读取 `RELEASE_NOTES.txt`。
5. 构建成功后 APK 在 Actions Artifacts 和 Releases 下载（arm64：`app-github-arm64-v8a-release.apk`）。

## 三、关键模块
### 扩展商店（工具链）
- `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/toolchains/` — 商店 UI（搜索/分类/版本选择/更新/依赖展示）
- `data/localtools/MobiCodeCatalog.kt` — 内置 16 个精选扩展（assets/mobicode_extensions.json）+ Termux 在线索引（清华/中科大/北外镜像拉取 `Packages`，24h 磁盘缓存，不打包）
- `data/localtools/ExtensionInstaller.kt` — 安装器：
  - .mcext（ZIP）与 .deb（ar + data.tar.xz）两种格式
  - Termux .deb 路径映射：`data/data/com.termux/files/usr/` → `payload/`
  - 自动依赖安装（BFS 递归、去重、实时进度）
  - 下载镜像：GitHub 用 ghfast/gh-proxy/ghproxy；Termux 用清华/中科大/北外
- `data/localtools/ToolchainEnvironment.kt` — 基础 10 个环境变量（HOME/TMPDIR/GOTMPDIR/GOCACHE/GOMODCACHE/CARGO_HOME/MOBICODE_PROJECT_ROOT/MOBICODE_EXEC_DIR/GIT_CONFIG_NOSYSTEM/GIT_CONFIG_GLOBAL）；每个扩展安装时保存自己的 envVars（PATH/LD_LIBRARY_PATH/LIBRARY_PATH/CPATH/MOBICODE_SYSROOT + OPENSSL_CONF 等）
- `data/localtools/ShellExecutor.kt` — NORMAL/ROOT/SHIZUKU 三种执行模式；SHIZUKU 走 UserService
- `data/localtools/ShizukuHelper.kt` — 全局 binder/权限监听（StateFlow）

### Shizuku
- `app/src/main/java/me/rerere/rikkahub/shizuku/IShizukuShellService.aidl`（AIDL）
- `ShizukuShellService.kt` — 运行在 Shizuku root 进程的 UserService
- `ShizukuShellManager.kt` — bindUserService 绑定/8 秒超时/重连
- `AndroidManifest.xml` 必须注册 `rikka.shizuku.ShizukuProvider`（provider AAR 不自带）
- `app/proguard-rules.pro` 保护 UserService 类不被 R8 移除（`build.gradle.kts` 的 `optimization { keepRules { ... } }` 引用）

### 本地工具（AI 工具）
- `data/ai/tools/local/` — 工具实现
  - 默认：get_time_info / list_file_tree / search_code / web_fetch / manage_files / run_shell / run_cli
  - 增强：env_info / pkg_search / pkg_install（工具链）；tap / swipe / input_text / ui_dump / screenshot / logcat（需 root/Shizuku，无权限不暴露给 AI）
- `AssistantLocalToolPage.kt` — 本地工具开关；列表最下面「设备 Shell 审批」开关（默认关闭）
- `Assistant.deviceShellApproval` — 控制 run_shell 是否需要审批
- search_code / list_file_tree / manage_files search 均为纯 Kotlin 实现（不能用系统 grep/find：Android toybox grep 在部分 ROM 上无输出）

### 聊天界面
- `ui/components/ai/ChatInput.kt` — 输入框：有文字时按钮始终为发送（生成中也可入队）；空输入+生成中才显示停止
- 左侧滑页 `ChatDrawer.kt`：原“图像生成”入口已替换为“扩展商店”（Screen.Toolchains）

## 四、纪律
1. 改前备份（`.mobicode/fix_backup_时间戳/`）。
2. 精确字符串替换，禁止重写大文件。
3. 构建走 GitHub Actions（`dormantxxy/rikkahub-local-tools`，仓库已公开）。
4. **每次构建发布新 Release（tag 唯一，不覆盖），必须写修改说明**。
5. 版本号：`versionCode = 194 + RIKKAHUB_VERSION_BUMP(run_number)`，versionName=3.1.1。

## 五、本地工具修复记录
1. ✅ **run_shell 自动分流**（已实施）：命令首词命中扩展 payload/bin → ToolchainExecutor 聚合环境执行；否则系统环境。
2. ✅ **默认工作目录统一 files/RikkaHub/**（已实施）：runShellTool/manageFilesTool/searchCodeTool/fileTreeTool 全部 rikkahubWorkDir。
3. AI 无确认可改系统设置：用户决定不改（不加危险名单/审批默认值不变）。
4. ✅ **Magisk root 路径 nsenter**（已修复）：ShellExecutor.startRoot 的 su -c 命令加 nsenter -t 1 -m -- sh -c 前缀，实测 /data/data 325 目录可见。
