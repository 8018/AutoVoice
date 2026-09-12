# D09 厂商约束核查:讯飞离线命令词 SDK(2026-09-12)

依据:计划文档 D09「核查 SDK 初始化、许可证、并发和进程重启约束后,增加独立 worker
进程、IPC、watchdog、重启退避和熔断恢复。**没有完成核查前不指定具体进程复制数量**」。

核查材料:
- `SDK/讯飞离线命令-Linux/Linux_cnenesr_.../README` 与 `samples/*/readme.txt`
- 头文件 `aikit_biz_api.h`、`aikit_biz_config.h`、`aikit_err.h`
- 本项目 JNI 桥 `offline-command/native/autovoice_offline_esr.cpp` 与
  `offline-command/native/README.md`
- 现有 Java 侧 `NativeOfflineCommandProvider` / `OfflineEnginePool` 行为

## 核查结论

| 约束项 | 结论 | 依据 |
| --- | --- | --- |
| 初始化 | `AIKIT_Init` 在进程内初始化 SDK;本项目按"进程全局引擎"处理(`NativeOfflineCommandProvider.close()` 注释明确:引擎加载后保留到进程退出,单个池化适配器不得 uninitialize 其他 worker) | JNI 桥 + provider 注释 |
| 线程安全 | **引擎非线程安全**:Java 侧经单线程串行调用;native 侧仅对 `lastError` 加互斥 | `native/README.md`「引擎非线程安全」 |
| 单次调用自包含 | 每次 recognize 完整生命周期(重载 FSA → SpecifyDataSet → Start → 分帧 Write/Read → End → 取 status==2)。**调用之间无必要残留状态**——这对进程隔离有利:崩溃后无需恢复引擎内部状态 | `native/README.md`「关键流程」 |
| 许可证 | 两种模式:license 文件(authType=1,不出网)/ 联网激活(authType=0,需 443 出站)。授权**绑定 appId 与设备**,未声明单机可运行实例数 | `aikit_biz_config.h` + 部署文档 |
| 多进程 | **厂商文档未声明多进程约束**(sample readme 仅描述单进程用法) | 见下方"未获证实" |
| 崩溃恢复 | SDK 为 C++ 动态库,`AIKIT_Init` 失败或调用中崩溃可致 JVM 崩溃(现有 Java 侧无法拦截)——这正是需要进程隔离的理由 | 评审 R5 |
| 配置数据 | resource 目录 + `cn_fsa.txt` 只读;workDir 可写(SDK 日志 `aikit/aeeLog.txt`)。**多进程必须各自独立 workDir**,否则日志文件竞争 | 部署文档 |

## 未获证实(不得据此下结论)

1. **同一台机器能否并发运行多个 SDK 实例**:文档未声明。理论上各进程独立 `AIKIT_Init`
   应可行(许可证绑定设备而非进程),但**必须实测**;实测前不指定进程复制数量(遵守
   计划文档要求)。
2. 多次 `AIKIT_Init`/`AIKIT_UnInit` 在同一进程内的行为(现有实现选择不 uninitialize)。
3. 联网激活模式下多进程同时出网激活是否触发服务端限流。
4. `vadEnergyThreshold` 等设备端参数在多进程下是否有共享状态(预期无,待验证)。

## 设计约束(据此确定 D09 形状)

1. **进程隔离是唯一能兜住 native 崩溃的手段**:JVM 内无法捕获 `SIGSEGV`/native abort。
2. **单次调用自包含** → worker 崩溃后,重启进程即可恢复,不需要状态迁移。
3. **每 worker 独立 workDir**:避免 SDK 日志互踩。
4. **不必预先指定进程数**:supervisor 支持配置 N,首期以 N=1 验证,容量按实测调整。
5. **旧结果不污染**:进程重启后必须丢弃旧世代的在途请求(用 generation 标记)。
6. **重启退避 + 熔断**:崩溃循环不能变成重启风暴。

## 本地可交付范围 vs 待环境

- **D09a(本地可验证)**:`OfflineEngineSupervisor` —— 子进程生命周期、健康探测、
  请求超时判定、重启指数退避、熔断(避免重启风暴)、世代隔离(旧结果丢弃)。
  用**假 worker 进程**(行为可编排:正常/崩溃/卡死)在 JVM 测试里验证全部行为。
- **D09b(待真实环境)**:真实 SDK worker 主类 + 服务器演练(杀 worker、卡死 worker、
  恢复验证)、多进程实测(上面"未获证实"第 1 条)、进程复制数量定标。
  在服务器实测完成前,**不宣称 native 故障隔离已完成**。
