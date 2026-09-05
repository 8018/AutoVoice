# 导航业务状态与手机车辆上下文

## 范围

模拟车辆实验保存在 `codex/navigation-vehicle-simulator`，提交 `e05ab07`。
本实现从 main 独立开发，使用真实手机定位和高德 URI，不包含模拟驾驶、默认成都位置、
模拟距离、ETA 或能耗。

## 导航状态归属

MainViewModel 持有一个 NavigationSession 和 NavigationExecutor，跨语音轮次和引擎重建复用。
UiState 从 NavigationSnapshot 派生候选列表，不再自行持有和修改另一份候选状态。
Session 不处理 ASR、NLU 或仲裁，不判断当前语音轮；VoiceEngine 原有状态机和仲裁完成准入后调用执行器。
进程被杀后不恢复上次交接，因为 URI 模式无法判断高德是否还在导航。

候选生命周期独立于已交接行程：用户可以在先前行程上再次搜索、取消搜索而保留交接记录。
每次列表更新分配 candidateVersion，超时必须匹配版本，防止旧定时器清除新候选。
过期仅关闭本地候选展示，服务端现有 NavigationDialogState 仍负责自己的候选 TTL。
本版本不建立跨端候选版本协议，不声称已解决断线后两端候选一致性；该项需要协议扩展。

收到已确认的 navigate 后，执行器先校验终点及有序途经点，再清理候选并打开高德。
OPENING / ACCEPTED / FAILED 只表示外部应用交接状态；不使用 NAVIGATING 或 ARRIVED
虚构没有接收到的导航回调。取消只能取消本应用待选候选；无候选时返回未执行。
高德实际行程的取消、静音、ETA 和偏航状态需要后续导航 SDK/车机控制协议，不由 URI 推测。

严格解析整个候选数组：缺字段、非数值、非法经纬度或空列表全部拒绝，保留上一份合法状态。
不通过过滤坏记录来修复数组，避免手机序号与服务端候选序号错位。途经点使用相同校验。

## 车辆上下文

VehicleContextProvider 提供位置和可空 SOC。PhoneVehicleContextProvider 默认读取手机最近已知定位，
保留现有权限与定位缓存行为，位置缺失返回未知；不填写虚构电量，也不启用模拟 GPS。
VoiceEngine 每次音频请求从 Provider 读取最新位置，继续使用现有 audio_start 纬经度字段。
SOC 仅预留于接口，没有上云、没有补能推断。测试直接注入固定 Provider。

## 验证与后续

`cd AutoVoice && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

回归包含：候选不拉起地图、旧超时隔离、确认后清候选、途经点顺序、非法输入保持状态、
打开异常、交接后无法伪取消、跨执行器状态复用、未知车辆信息。

下一步按独立变更接入：跨端候选 ID/version；地图 SDK 能力接口及真实回调；
路线预览与多路线选择；行中修改/ETA；最后才是 SOC 驱动补能业务。
