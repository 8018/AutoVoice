# Windows 接续：SenseVoice 应用集成（2026-11-14）

## 当前状态

工作分支 `codex/navigation-product`。这是待真机验收的工作快照，不是已完成发布。

- Open 渠道已从 FireRed 改为 SenseVoice int8 + Silero；保留讯飞唤醒和云端识别。讯飞离线命令词已返回授权过期 18707，不再以续费为前提。
- 模型对比：固定 29 条公开合成音频，SenseVoice + Silero 三轮 29/29；不是麦克风、AEC 或双讲验收。Zipformer 有失败，全模型对比总分仍为失败，不能改写成通过。
- 应用/适配器单测、Open lint 与真实 SDK Open APK 构建此前通过。独立代码审核发现的回调撤销测试假通过已修复。证据在迁移包 evidence/。
- dev 云端文本 33/33、路线 3/3、语音 3/3、统计 3/3；离线判分测试 38/38。文本均值 1315ms / p95 2584ms，充电站和泛博物馆单场景超过 2500ms。云端仍为 b8a9f275b7792a4e0ff68b20741ea313654e7c47，未部署。
- **新 SenseVoice 主包未成功安装；应用入口的 29 条真机集成测试未运行；用户播报中插话仍未验收。** 不得声称回声问题已修复。
- 同一手机已具备 asr-comparison 模型和 speech-models 模型；不要卸载清数据。无线连接多次因系统日志 `Detected wifi network change. Disabling adbwifi.` 关闭。改用 Windows 当前稳定连接，不依赖旧端口。
- 工作快照同时保留此前未提交的音频前端、录音协调、播放引用和回声探针改动；这些不属于本轮 SenseVoice 静态审核范围，需继续真机验证。

## Windows 获取

先运行 `git status`，保留 Windows 本地改动，不做 reset/clean。执行 `git fetch origin`，已有同名分支且无分叉时 `git switch codex/navigation-product`、`git pull --ff-only origin codex/navigation-product`；否则新建跟踪分支或由 Codex 合并，不能覆盖本地修改。

把另行复制的 `AutoVoice-Windows-handoff.zip` 解压到仓库外，例如 `C:\AutoVoice-handoff`。该包含 APK、固定模型/音频、本地 native 依赖、真实 AIKit.aar 和证据。不会通过 Git 分发 SDK/大模型，不包含 SSH 密钥或 Android 签名私钥。保留私人传输，不上传公开附件。

优先直接安装包中已构建 APK（保留原调试签名，避免 Windows 新签名导致覆盖安装失败）。PowerShell，仓库根目录运行，将变量改为真实路径和 `adb devices -l` 中的设备序列号：

```powershell
$handoff = 'C:\AutoVoice-handoff'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$serial = '实际设备序列号'
& $adb -s $serial install -r "$handoff\apk\app-open-debug.apk"
# 上一步成功后再安装测试包；手机若要求安装确认请完成。
& $adb -s $serial install -r "$handoff\apk\app-open-debug-androidTest.apk"
```

每步检查退出码；安装签名不符时停止调查，不卸载，不清数据。Windows 重编译会使用自身调试签名，需协调签名后才可覆盖现有包。

## 先跑真实应用入口测试

现有手机文件已放好；缺失时从迁移包 device/ 恢复到 `/sdcard/Android/data/com.autovoice.app.open/files/asr-comparison/`。生产识别模型位置为 `files/speech-models/models/sensevoice/` 与 `files/speech-models/models/vad/`，分别复制相应目录（含许可证），不要另加一层 models。固定哈希由加载器验证。

```powershell
& $adb -s $serial shell am force-stop com.autovoice.app.open
& $adb -s $serial shell rm -f /sdcard/Android/data/com.autovoice.app.open/files/asr-comparison/sensevoice-app-results.json
& $adb -s $serial shell am instrument -w -e class 'com.autovoice.app.asr.SenseVoiceAppIntegrationTest#appFactoryRecognizesPublicSuiteAndRejectsExpiredOwners' com.autovoice.app.open.test/androidx.test.runner.AndroidJUnitRunner
# 保存完整测试输出，检查测试断言结果，不只看 adb 退出码。
& $adb -s $serial pull /sdcard/Android/data/com.autovoice.app.open/files/asr-comparison/sensevoice-app-results.json "$handoff\sensevoice-app-results.json"
```

检查 status 完成、passed、29 条 exact/callbacksValid、过期/撤销请求断言与实际 APK 哈希；失败/断线保留原始证据，不标通过。随后运行既有完整对比（Python 3.11+、Node）：

```powershell
python tools/qa/navigation-asr-device.py --adb $adb --serial $serial --assets "$handoff\device" --test-apk "$handoff\apk\app-open-debug-androidTest.apk" --pipeline silero
```

对比包含 Zipformer，现有失败会使脚本非零退出，应分别核对模型得分；不要放宽基准。音频是公开合成固定素材，禁止替换后沿用旧基准名。

最后打开 AutoVoice Open，核查 local.asr 显示 SenseVoice READY、唤醒和云端组件状态；如旧 AutoVoice 占麦，可先 force-stop com.autovoice.app（不删数据）。请用户实测多候选播报中说“第二个”，确认能随时打断、识别不混播报、不会提前超时。模型切换本身不证明 AEC 已解决，不能提前将 openMicDeviceValidated 设为 true。

## 后续构建与回归

仓库 Android 根在 AutoVoice/。使用 JDK 21、Android SDK 34，local.properties 填 Windows SDK 路径，不复制 Mac 路径。把迁移包 vendor/AIKit.aar 放到 AutoVoice/adapter-iflytek/libs/AIKit.aar；native/ 保持仓库外。

```powershell
cd AutoVoice
.\gradlew.bat :app:testOpenDebugUnitTest :app:testIflytekDebugUnitTest :app:lintOpenDebug -PuseIflytekStub=true --no-watch-fs
.\gradlew.bat :app:assembleOpenDebug :app:assembleOpenDebugAndroidTest "-PnativeDevRoot=C:/AutoVoice-handoff/native" --no-watch-fs
```

真实设备构建不传 useIflytekStub。离线环境缓存不足可联网获取依赖。不得把编译替身包当真机可用包。每批功能后继续扩充并运行版本化基准，保留失败证据。云端只读检查授权不代表允许改动或部署云端。

接续参考：docs/navigation-open-asr-comparison.md、docs/navigation-echo-interruption-report.md、docs/navigation-local-asr-device-report.md、docs/superpowers/plans/2026-11-14-sensevoice-app-integration.md。
