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

2026-11-13 第二批交付：用户要求继续自主完成，M1 checkpoint d09969e；路线服务及条件搜索提交5097c65已发布dev，生产未动。dev健康检查UP，旧网关备份在对应gateway release目录。新包保留Flash和管理页。
最终端云验证：协议1.2+路线能力协商、真实3路线、偏好请求、WGS→GCJ官方转换、缺起点拒绝、只读目的地查询及选择、按序多站草稿、未知营业、缺补能数据均通过，详见docs/qa/navigation-dev-smoke-results.json。
Android：真实高德SDK可选集成、运行状态身份/新鲜度、沿途搜索、专用播报及视图代码完成并以供应方artifact编译/打包。缺Android key，默认保留外部高德；行中提交/可靠停止确认/绕行/实车补能与说话人冲突仍有未满足条件，状态逐项见navigation-delivery-status.md，不宣称58项全部完成。
最终全server636项通过；Android全CI后对最后语音口令和并发身份保护再跑两Debug及lint，当前合计1341项0失败。最后修复Android12粗略/精确定位同时申请，重新lint/真实Iflytek打包通过。真实APK约49.25MB，含既有私有运行库（资源与凭据不入库）；校验和9b9a74743b5b3d192853acf9d06bae243f8de9e13b8c77f97590daeeef2246f0。当前无连接设备，本机也没有模拟器可执行程序，尚未实际安装/驾驶验收。
交付：docs/navigation-evening-validation.md、208条标记未执行的真机语料、明确设备序列号的安装脚本。等待Android授权及真实车辆数据；不新增收费服务或伪造供应方回执。

## 最终安装包收尾：导航操作统计

已补入规划、启动、停止受理、沿途搜索与取消、地图和独立导航声音控制的终态及单调耗时记录。固定枚举事件不携带地点、地址、坐标或语音文本。客户端去重，晚到结果不能覆盖已经结算的终态。现有遥测仅支持明细，独立操作记录尚无设备/会话归属及导航聚合面板，I06 仍保留部分支持。

根任务完成接线审核；voice-business、gateway-client、两种 Debug 单元测试及 lint 通过，当前安卓测试结果合计 1,347 项、零失败和跳过。服务端没有因此修改，dev 仍运行已联测的 5097c65。真机和高德 Android 授权仍待提供，逐项状态以 navigation-delivery-status.md 为准。

## 量化循环第 1 批：四项真实搜索故障

已固定 23 条文本基准，保存旧失败结果且新增自动语义及耗时门槛。服务端 33357a0 经 640 项全量测试通过后部署 dev；地图只读详情工具已启用。完整实测 19 项目标通过、4 项能力边界、0 失败，平均 1477ms、P95 3155ms。虹桥 T2、三亚凤凰机场、星巴克及南站周边充电站四项均已返回真实候选与坐标。

用户追加要求基准随业务扩充：v2 保留原23条、追加4种表达，分别检查原集合和新增集合。产品ID覆盖及每次版本、完整结果按时间存档；CI跑判定器，dev发布后跑真实文本门槛，GitHub配置待本分支合入生效。后续每批先修复回归再推进，不把拒绝反馈计为功能实现。
