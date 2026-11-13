# 导航产品分类实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按分类交付导航产品清单的 58 项能力，使用户从找点到行中调整获得可验证的执行结果。

**Architecture:** 沿用 Android 客户端的导航业务会话和 Java 服务端的地点解析、多轮选择。先完善现有高德 URI 交接链路，再通过统一地图能力接口接入真实路线、行程控制和回调；搜索任务与实际行程持续分离。

**Tech Stack:** Kotlin、Android、Java、Gradle、现有高德地点检索及 URI 交接。新增地图 SDK 或车机协议在第二批前完成技术验证，不预先承诺某个供应商提供全部能力。

**Spec:** [逐项产品清单](../../navigation-product-checklist.md)；[调研与产品需求](../../navigation-product-research-prd.md)。

## 全局约束

- 本次仅产出计划，不启动代码实施。默认延续现有 Android 手机应用形态；原生车机移植另列项目。
- 第一批复用 URI，不能用打开成功冒充导航已开始。地图接口缺失不阻塞第一批发布。
- 真实地图回调是“导航中、到达、结束”的唯一依据；预测时间、距离、营业和桩态必须有数据来源。
- 用户明确的途经顺序必须保留；搜索取消不结束实际行程；执行失败不播报成功。
- 地点候选与路线候选上下文隔离；保留已有任务、列表、候选身份及版本校验。
- 原规格拟定指标：单地点成功率 ≥95%，两轮选点 ≥90%，验收集误执行与假成功为 0；首个有效反馈 P95≤2 秒，候选返回 P95≤5 秒。均需真机测量，未达到前不得宣称达标。

## 重点检查

1. 否定、改口和取消后迟到结果：归任务 1、2，禁止误执行。
2. 地址覆盖、切换用户、删除后缓存残留：归任务 3，不能使用旧用户地址。
3. 多地点中间一项歧义或失败：归任务 4，不得静默省略后交接。
4. 地图回调乱序、重连、路线已在外部变更：归任务 5、7，不能恢复旧行程状态。
5. 无实时状态、未知营业/电量/桩态：归任务 6、9，明确未知而非填入默认数值。

## 交付批次与依赖

| 批次 | 交付范围 | 完成后用户能做什么 | 发布门槛 |
|---|---|---|---|
| M1 基础找点闭环 | 任务 1—4，A01—A06、B、C、D01—D03、H01、I | 自然表达目的地、纠错、回家去公司、有序多地点交接 | 现有 URI 链路真机验收通过 |
| M2 路线与状态 | 任务 5—6，D04—D07、F | 预览选路、改偏好、查行程、结束或恢复 | 地图能力验证及真实回调通过 |
| M3 途中调整与展示 | 任务 7—8，E、G | 沿途搜、修改行程、控制播报和地图 | 原路线保留、失败恢复、通道隔离通过 |
| M4 复杂出行 | 任务 9，A07—A08、H02—H04 | 按条件找店、按车辆状态规划补能 | 属性和车辆数据质量满足验收 |

建议顺序：任务 1 → 2 → 3 → 4；任务 5 的地图验证可与 M1 开发同期进行；任务 6 依赖 5，任务 7 依赖 6，任务 8 依赖 5，任务 9 依赖 2、7。并行表示团队可同时排期，不要求在本次聊天启动并行开发。

未提供人员配置、地图授权与设备清单，因此按成果设里程碑，不虚构日历工期。每个分类完成后独立评审和提交，不把 58 项作为一次大版本上线。

## 代码落点

以下为仓库相对路径。现有文件已核对存在；标注“新增”的文件是拟定职责边界，正式实施时先检查同职责实现，避免重复建设。

| 简称 | 文件或目录 | 职责 |
|---|---|---|
| 客户端导航目录 | AutoVoice/voice-business/src/main/kotlin/com/autovoice/voicebusiness/navigation/ | 现有 NavigationSession、NavigationExecutor、NavigationDialoguePolicy |
| 服务端导航目录 | AutoVoiceServer/navigation-domain/src/main/java/com/autovoice/server/navigation/ | 现有 NavigationDialogService、NavigationSelectionPolicy、候选存储 |
| 地点工具 | AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/NavigationToolFacade.java | 地点检索和解析入口 |
| 端侧装配 | AutoVoice/app/src/main/kotlin/com/autovoice/app/MainViewModel.kt | 界面及业务依赖装配 |
| 车辆上下文 | AutoVoice/app/src/main/kotlin/com/autovoice/app/VehicleContextProvider.kt | 真实定位和车辆数据边界 |
| 协议 | shared/contracts/intent.schema.json、shared/contracts/gateway-messages.schema.json、shared/protocol.md | 意图、任务与执行结果契约 |
| 共享样例 | shared/fixtures/ | 端云共同验收数据 |

## 任务 1 I 类通用执行基础

**覆盖：** I01—I06；为所有分类建立共同执行规则。主要协作：客户端、服务端、测试。

**文件：** 修改客户端 NavigationExecutor.kt、NavigationSession.kt；按需要修改协议和 MainViewModel.kt；在客户端导航目录新增 NavigationCapabilities.kt、NavigationActionResult.kt。测试扩展现有 NavigationBusinessBoundaryTest.kt 和 NavigationSessionTest.kt。

**产出接口：** `NavigationCapabilities.supports(operation: String): Boolean`；`NavigationActionResult(operationId: String, status: NavigationActionStatus, message: String)`，状态区分 UNSUPPORTED、PENDING、ACCEPTED、SUCCEEDED、FAILED。ACCEPTED 仅表示交接接受；URI 模式不生成实际导航启动的 SUCCEEDED。operationId 在重试时复用，绑定现有任务身份，不取代候选校验。

- [ ] 先补回归：同一确认重复到达只打开一次；取消后返回旧结果不执行；无能力返回 UNSUPPORTED；打开失败回复不含成功；同时冲突请求进入澄清。
- [ ] 用下方客户端命令执行并确认新增用例在相关行为未实现时失败。
- [ ] 增加能力声明与标准执行结果；以实际结果生成最终话术；保留现有交接状态语义。
- [ ] 为定位、网络、搜索和打开失败建立可区分错误码；采集任务结果和延迟，地址在普通日志中脱敏。
- [ ] 运行客户端和涉及的端云契约回归；真机检查交接成功与失败话术。通过后提交这一任务。

## 任务 2 A B 类搜索与多轮选择

**覆盖：** A01—A06、B01—B09、H01。H01 仅查站点，不承诺空闲桩或能量可达性。

**文件：** 修改地点工具、NavigationDialogService.java、NavigationSelectionPolicy.java、NavigationDialoguePolicy.kt；更新 docs/prompts/qwen-omni-navigation.txt；扩展 NavigationToolFacadeTest.java、NavigationDialogServiceTest.java 和共享样例。

**输入输出：** 输入文本、当前位置及现有任务身份；输出沿用现有候选契约。新增城市、区域、参考地点、入口限定及已排除候选语义时，同步 schema、协议和共享样例，不私自塞入不可校验的自由文本操作。

- [ ] 建立失败用例：指定城市优先于当前位置；指定地点周边不替换成车辆附近；T2 不匹配机场中心；“不要去第二个”不选择；换批不重复原列表。
- [ ] 实现检索条件解析、参考点消歧、最多 3 项展示与换批策略。没有更多结果时明确告知，不重复旧结果。
- [ ] 完善序号、名称、地址选择及补充限定；新列表生成新身份，旧序号失效；无匹配不自动选首项。
- [ ] 无定位只允许依明确城市或参考地点检索；无结果请求一个关键限定；充电站缺桩态显示未知。
- [ ] 运行服务端导航及地点工具测试、客户端选择回归；真机验证两轮音频到地图交接。通过后提交。

## 任务 3 C 类常用地址

**覆盖：** C01—C05、I04。主要协作：客户端、产品、测试。

**文件：** 在客户端导航目录新增 SavedNavigationPlaceStore.kt；在 app 的现有持久化体系中实现存储适配；修改 MainViewModel.kt 增加设置、确认、删除入口。新增 SavedNavigationPlaceStoreTest.kt。

**拟定接口：** `read(userId: String, kind: SavedPlaceKind): NavigationTarget?`、`save(userId: String, kind: SavedPlaceKind, target: NavigationTarget)`、`delete(userId: String, kind: SavedPlaceKind)`；SavedPlaceKind 仅 HOME、WORK。首期保存到当前设备的用户配置，不默认云同步；若项目没有账号，明确使用设备本地配置，界面不得宣称多人账号隔离。

- [ ] 先测地址未设置、覆盖后取消、删除后再次“回家”、用户切换和重启持久化。
- [ ] 实现存储与确认流程；明确地点解析复用任务 2，定位未知不保存“这里”。
- [ ] 在发出地点检索前解析家/公司；仅传递本次需要的地点信息，不上传完整地址簿。
- [ ] 验证读写失败和日志脱敏，运行地址测试与客户端构建。通过后提交。

## 任务 4 D 类出发前行程草稿

**覆盖：** D01—D03。依赖任务 2、3。

**文件：** 客户端导航目录新增 NavigationTripDraft.kt；修改 NavigationSession.kt、NavigationExecutor.kt、MainViewModel.kt；扩展 NavigationSessionTest.kt、NavigationBusinessBoundaryTest.kt 和多地点共享样例。

**拟定接口：** `NavigationTripDraft(orderedStops: List<NavigationTarget>, revision: Long)`；只有所有地点完成解析后才转换为现有 `NavigationTrip(destination, waypoints)`。最后一项为终点，其余保留原序作为途经点。

- [ ] 先测 A→B→C 顺序、B 歧义、B 检索失败、缺失家地址、确认重复和放弃草稿。
- [ ] 实现逐地点消歧与完整草稿展示；任何地点未解决时禁止提交部分路线。
- [ ] 用户确认后复用现有单点/多点 URI；超过执行端途经点上限时要求用户缩减，不截断。
- [ ] 回归失败不交接、成功只报告打开结果；真机检查多点实际进入地图的顺序。完成 M1 验收后提交。

## 任务 5 地图执行能力验证与适配

**覆盖：** M2、M3 的共同前置，不单独算产品项。

**文件：** 客户端导航目录新增 NavigationMapPort.kt、NavigationMapEvent.kt；地图供应方实现放入 app 的 navigation 适配目录；接入 MainViewModel.kt 和 NavigationSession.kt。新增 NavigationMapPortContractTest.kt。

**边界：** 适配层负责规划、选路、查询、修改、结束、播报和视图操作的能力发现及回执。所有请求带 operationId；所有行程事件带 tripId、revision、observedAt。供应方不能可靠提供的字段保持未知，不在业务层合成。

- [ ] 在目标设备验证供应方逐项能力：多路线、偏好、活动行程查询、更新、结束、播报通道、视图、恢复和事件监听；记录版本、权限、调用及真实结果。
- [ ] 输出“可用 / 不可用 / 需授权”矩阵。不可用的产品项保持关闭；不能只通过更改提示词宣布支持。
- [ ] 按确认可用的能力定义强类型请求和结果，先写适配契约用例：回调乱序、重复、丢失、重连、外部地图已改路线。
- [ ] 实现供应方适配与 URI 适配并存；URI 仅声明它实际支持的交接能力。
- [ ] 用真实设备完成一次开始—查询—结束闭环，再开放任务 6；没有该证据时 M2 不发布。

此任务是外部依赖决策点。供应方具体接口与授权尚未确认，因此任务 6—9 是有明确验收范围的分类实施包；正式编码前需依据本任务结果固定方法签名及供应方错误映射，不能将本计划当作已核实的 SDK 接口说明。

## 任务 6 D F 类路线与行程控制

**覆盖：** D04—D07、F01—F05。依赖任务 5。

**文件：** 客户端导航目录新增 NavigationRouteSession.kt；修改 NavigationSession.kt、地图适配、MainViewModel.kt；服务端导航域增加路线选择语义。新增 NavigationRouteSessionTest.kt、NavigationTripControlTest.kt，并补端云路线候选样例。

- [ ] 先测路线序号不误选地点、偏好无解、查询不启动、未知/过期状态不报数字、结束失败不报完成。
- [ ] 增加真实路线预览和选择；路线列表携带独立身份；显示距离、时间、可用收费字段，缺失字段不填零。
- [ ] 实现偏好重规划，无法满足时展示替代并等待用户选择；保存本次偏好，跨行程持久化另行评审。
- [ ] 实现剩余时长、到达时刻、剩余里程与结束操作。读取无时间戳数据不得当作实时结果。
- [ ] 实现恢复：地图明确提供恢复能力时执行恢复；否则先询问是否按旧目的地重新规划，不宣称接续原导航。
- [ ] 运行路线和行程控制测试，并真机核对查询值、地图显示和结束结果；通过后提交 M2。

## 任务 7 E 类途中搜索与修改

**覆盖：** E01—E06。依赖任务 6。

**文件：** 客户端导航目录新增 NavigationTripMutation.kt；修改地图适配、NavigationSession.kt；扩展地点工具及服务端导航域以支持沿途搜索。新增 NavigationTripMutationTest.kt 和沿途查询共享样例。

- [ ] 先测搜索取消不结束导航、添加失败保留原路线、删除错误点被拒绝、旧版本修改被拒绝、修改期间外部路线变化。
- [ ] 沿当前路线搜索并展示额外绕行时间或距离；供应方不支持时明确不可用，不用“附近”结果冒充“沿途”。
- [ ] 把新增、删除、重排和更换终点先生成变更草稿；用户确认时核对当前行程版本。
- [ ] 调用地图后等待有效回执，成功才提交新版本；失败保留已确认的旧业务状态并重新核对地图实际状态。
- [ ] 真机验证四种修改、搜索取消及网络失败。不能保证原行程不受破坏的操作不开放。通过后独立提交。

## 任务 8 G 类播报、地图和记录

**覆盖：** G01—G08。依赖任务 5；G08 可独立先做本应用记录清除。

**文件：** 客户端导航目录新增 NavigationPresentationController.kt；修改地图适配和 MainViewModel.kt；新增 NavigationPresentationControllerTest.kt。记录清除接入项目实际记录存储，不新增重复日志库。

- [ ] 先测静音不影响媒体、音量越界、缩放边界、地图无活动路线、供应方无卫星图、记录清除取消。
- [ ] 接入导航静音、恢复和音量；只操作导航通道，不具备独立通道时不以系统音量替代。
- [ ] 接入缩放、全程、路况图和可选卫星图；依能力表展示入口，失败保持原显示状态。
- [ ] 记录清除明确范围：本应用只能承诺清除本应用记录；清除外部地图历史必须具备对应接口。确认清除不结束当前行程。
- [ ] 真机检查通道隔离与地图结果；运行控制器测试。与任务 7 共同完成 M3，分别提交。

## 任务 9 A H 类复杂搜索和补能

**覆盖：** A07—A08、H02—H04。依赖任务 2、7及真实数据源。

**文件：** 服务端导航目录新增 NavigationPlaceConstraintService.java、ChargingTripPlanner.java；扩展地点工具、协议及车辆上下文；新增对应测试和约束/补能共享样例。

- [ ] 先定义属性来源和有效期、车型兼容信息、SOC 时间戳、能耗模型及缺失处理；未经验证的数据源不开启对应筛选条件。
- [ ] 先测营业未知不等于营业、桩态未知不等于空闲、SOC 过期不可规划、无兼容站点、到达电量目标不可达。
- [ ] 模糊与多条件搜索返回候选及匹配依据；无法验证的条件单独标明，保留用户澄清机会。
- [ ] 在真实路线和车辆数据上筛选沿途兼容站点；计算电量约束与充电停留，并展示估算假设。不得由语言模型直接生成里程或 SOC 数字。
- [ ] 组合充电与休息形成可修改草稿，复用任务 7 提交；不可达时给出失败原因或可选择的替代方案，不降低用户目标后静默执行。
- [ ] 用已记录行程回放验证，再在目标车型实测；数据不足则保留站点查询，关闭自动补能规划。通过后提交 M4。

## 验证命令与发布检查

根据实际修改范围执行，命令在各自项目目录运行，成功标准为退出码 0 且相关测试无失败。本次文档工作未执行这些命令，不代表现有项目已通过。

客户端目录 `AutoVoice`：

```sh
./gradlew :voice-business:test :app:testIflytekDebugUnitTest :app:testOpenDebugUnitTest \
  :app:assembleIflytekDebug :app:assembleOpenDebug :app:lintIflytekDebug :app:lintOpenDebug
```

服务端目录 `AutoVoiceServer`：

```sh
./gradlew :navigation-domain:test :skill-mcp:test :contracts:test :agent-loop:test
```

若模块测试任务名称因插件不同而不匹配，先运行 `./gradlew :voice-business:tasks --all` 核对并使用对应实际测试任务；不把“无此任务”误判为产品失败或测试通过。

- [ ] 不少于 200 条表达覆盖各已开放类别，按安静/噪声环境分组；未开放能力验证明确降级。
- [ ] 每条保存产品 ID、输入、期望、实际执行、设备及地图版本、网络、延迟与通过结果，敏感地点脱敏。
- [ ] M1—M4 每批分别核对清单完成项及不支持项；仅在实现与真机证据齐全后改变状态。
- [ ] 扩大开放范围前满足原规格门槛；取消后误执行、旧选择执行、假成功任何一项出现均阻止该批发布。
- [ ] 回退时关闭新增能力入口并保留已验证的 URI 基础交接；不清除用户地址，不声称能回退外部地图实际行程。

## 完整性核对

任务 1 覆盖 I；任务 2 覆盖 A01—A06、B、H01；任务 3 覆盖 C；任务 4 覆盖 D01—D03；任务 5 提供地图基础；任务 6 覆盖 D04—D07、F；任务 7 覆盖 E；任务 8 覆盖 G；任务 9 覆盖 A07—A08、H02—H04。产品清单 58 项均有归属，供应方依赖和可选项未被当作已支持能力。
