# 导航实施记录

计划：docs/superpowers/plans/2026-11-12-navigation.md

2026-11-13：从 dev 创建 codex/navigation-product。首次误用本地旧 dev，尚未修改业务代码即纠正；HTTPS 同步后基线为 origin/dev 3d5f916a23c0e95e72a8fbb5fa3f9632b6a7d1ed。原工作目录未改动。

Ruling: 按新基线更新 Android 测试与构建为 Iflytek/Open 两种 flavor — 上游 #166 已改变构建结构 — 若遗漏会漏测某一语音通道。

任务 1：准备中，最新基线 :voice-business:test 已通过（--offline --configure-on-demand）。全量 Android 配置缺少 NDK 26.1.10909125，尚未验证打包。
任务 2—9：未开始。
真机和地图供应方验证：尚未执行，不标记完成。

Pre-flight: 任务 1 的能力与结果契约供任务 2—9 使用；保留现有任务身份。任务 3 的地址解析供任务 4 使用；任务 5 的实际地图接口是任务 6—9 的前置，不能以 URI 代替行程控制。

2026-11-13 追加：用户要求 NDK 补齐及子代理开发，root 审核。3 个代理分工执行结果、服务端搜索、常用地址。用户追加 Flash 切换：当前源代码 deepseek-chat，已改 deepseek-flash，并显式非思考模式保持原工具循环兼容；LLM 模块测试已通过，旅行攻略测试依赖下载中，未部署。NDK 官方清单校验值 44df0485bbd565b89585b687bb748521a98f65a8，下载中。

Flash验证：22项LLM测试与3项旅行攻略测试通过。只读检查dev部署app.jar，业务llm.jar与TravelGuideService模型常量均为deepseek-chat。部署环境选择待用户回复，尚未改线上。CMake 3.22.1已校验并安装，cmake --version通过。

NDK已安装：/Users/michaelliu/Library/Android/sdk/ndk/26.1.10909125，985051981字节官方包SHA1核对一致，source.properties和clang17.0.2命令通过。CMake3.22.1与SDK command-line tools同样已安装。Iflytek原生构建验证中。

2026-11-13 审核更新：任务1结果反馈、任务2搜索消歧、任务3常用地址已交付代码；任务4多站草稿确认集成中。任务2服务端191项测试通过，任务3 Open客户端构建与测试通过，详见各任务报告。根审核发现中文城市数字归一化问题（如三亚），已退回修复。任务5真实地图能力审计完成，任务6—9中的运行态控制受SDK授权、真机回执及车辆/充电数据约束，未标记完成。

Iflytek检查：NDK/CMake原生编译通过，运行中的任务4使两个既有多站立即启动测试失败，待确认流程及断言更新后重跑。尚不声明Iflytek整包通过。Flash独立提交0e09865，22项LLM与3项旅行攻略测试通过；dev线上仍为deepseek-chat，待用户选择部署范围。

Flash独立安装包构建完成：从0e09865归档生成，不含未提交导航代码；bootJar通过，检查jar内业务和旅行攻略均为deepseek-flash。SHA256 0710af39e8e3647c62d1c020fbaa96c4cfd8fe02504bfbb6c3ffe7a655dcd66c，尚未部署。

Flash dev部署完成（用户批准后）：独立0e09865网关安装包已于2026-11-13部署至dev，旧app.jar已备份。健康检查UP，config/gateway/tts/skill-registry/offline-engine全部READY。使用dev运行进程现有凭据发出无用户数据的最小上游请求，HTTP200、model=deepseek-flash、有正常回复；生产未改动。

根最终服务端验证：./gradlew test :app:bootJar --offline 通过，621项测试0失败0跳过。shared npm test通过。Android完整CI等价命令仍在运行，最终状态待写。

最终Android验证通过：test、lint、Iflytek/Open Debug测试/lint/assemble及androidUnitTestCoverage全部通过（CI stub配置）。XML合计1317项，0失败0错误0跳过；包含不同构建变体重复执行的用例。原生工具29项测试通过，两种CI APK布局检查通过。覆盖率：四个业务基础模块合计82.63%，voice-business79.98%，voice-engine96.00%，Iflytek app55.95%，Open app56.25%，均满足对应门槛。NDK真实编译此前已通过。CI APK不等于完整离线/开源语音资源可运行，真机地图及语音验收仍未完成。

当前交付：导航任务1—4代码及任务5能力审计；M1未经过真机验收，不标记产品正式完成。任务6—9等待地图授权、实时回执、目标设备及车辆/充电数据。导航变更未部署，dev仅更新Flash。
