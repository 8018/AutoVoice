# 任务 5 地图能力审计

2026-11-13，适用范围为当前 AutoVoice Android 应用及服务端。仓库结果基于代码走查和已有测试证据。供应方能力基于高德官方接口文档。本次未连接开发服务、读取凭据、安装 SDK 或执行真机导航。

本页保留首次审计时的能力边界。后续已接入 Web 路线查询及选配 Android SDK，最新证据见[路线服务报告](navigation-route-service-report.md)、[地图运行报告](navigation-map-runtime-report.md)和[逐项交付状态](navigation-delivery-status.md)。

## 首次审计时的仓库

| 边界 | 当前代码路径 | 证据和范围 |
| --- | --- | --- |
| 单点交接 | `NavigationExecutor` 生成 `androidamap://navi`，`MainViewModel` 发出 Android `ACTION_VIEW` | 打开被系统接受时记 `ACCEPTED`，打开失败记 `FAILED`。业务测试验证 URI 和反馈。实际开始、结束和到达仍待地图侧证据。 |
| 有序多点交接 | `NavigationExecutor` 生成 `amapuri://route/plan`，将最后一点作为终点，其余按确认顺序传入 | Android URI 文档支持途经点和省略起点。执行方会自行算路。客户端没有路线 ID 或路况、状态回流。 |
| 地点查询 | `NavigationToolFacade` 的 `resolve_navigation` 使用 `maps_text_search`、`maps_around_search`、`maps_geo`，存在时使用 `maps_regeocode` | 调用有赖于 `McpSkillRegistry` 实际连接成功，动态发现所需工具。通用注册器可列出供应方其他工具，但代码中没有用于导航产品的强类型算路、沿途或行程控制适配。 |
| 状态与控制 | `NavigationCapabilities.URI_HANDOFF`、`NavigationSession` | 支持地点任务、草稿和交接。候选取消结束本应用的地点任务。交接快照只保存打开状态及交给地图的地点。没有高德 SDK 依赖、活动行程监听器或外部地图双向连接。 |
| 车辆与记录 | `PhoneVehicleContextProvider`、`VehicleContext` | 手机提供符合质量条件的位置。SOC 是可空接口，手机实现没有真实 SOC 数据。未发现本应用可供 G08 清除的持久导航搜索历史。常用地址属于设置数据。 |

M1 真机复核时，检查 `androidamap://navi` 的坐标标记。官方将 `dev` 标为必填。本次审核已要求并补齐 GPS 分支 `dev=1` 与常规高德坐标分支 `dev=0`；实际落点仍需真机验证。多点规划 URI 的 `dev` 作用于整次请求，混合 WGS84 和 GCJ-02 前需统一坐标或拒绝该组合。 [Android 导航 URI](https://lbs.amap.com/api/amap-mobile/guide/android/navigation/) · [Android 路径规划 URI](https://lbs.amap.com/api/amap-mobile/guide/android/route) · [现有交接验证](navigation-task1-report.md)

## 供应方能力和产品门槛

| 产品范围 | URI / 当前 MCP 路径 | 高德公开接口及实施条件 |
| --- | --- | --- |
| D04 多路线预览、D07 出发前预计路程 | 路线 URI 可以打开高德规划页。应用内路线列表和数值当前不可用。 | **需确认路线查询授权。** 高德官方 MCP 驾车规划提供起终点输入和方案集合，公开概述未承诺策略、途经点或收费字段。可先确认实际工具 schema。Web 路径规划 2.0 支持多方案、途经点、策略及可选时间、费用、折线。用带起点、规划时间和来源的查询结果显示估算。 [MCP 能力](https://lbs.amap.com/api/mcp-server/) · [Web 路径规划](https://lbs.amap.com/api/webservice/guide/api/newroute) |
| D05 路线选择、D06 路线偏好 | URI 没有可回传并用于应用内锁定的路线身份。高德 Android 自 8.2.6 起以地图 App 本身的偏好为准。 | **需授权 SDK，或具备确认路线的地图接口。** 内嵌 SDK 支持算路策略、路线集合及导航前 `selectRouteId`。Web 规划可以独立展示偏好预览；外部地图交接仍会重新规划。用户指定严格偏好时要核实结果，证据不足则提示。 [Android 导航 URI](https://lbs.amap.com/api/amap-mobile/guide/android/navigation/) · [SDK 驾车算路](https://lbs.amap.com/api/navigation-sdk-for-android/guide/route-plan/drive-route-plan) · [SDK AMapNavi](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNavi.html) |
| F01–F03 剩余时长、到达时刻、剩余里程 | 当前不可用。Web/普通 MCP 的算路耗时是针对一次规划的估算。 | **需授权 SDK，真机验证。** `onStartNavi` 确认进入导航后，从对应活动路线的 `onNaviInfoUpdate` 读取 path ID、剩余秒数和米数。只用仍有效的回调计算预计到达时刻。无数据或过期时显示未知。 [SDK 事件](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNaviListener.html) · [NaviInfo 字段](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/NaviInfo.html) |
| F04 结束、F05 恢复 | 当前外部地图 URI 不提供行程控制或结果。 | **结束需授权 SDK 和可验证回执。** `stopNavi()` 可停止实时导航，返回类型是 `void`。Android 核心公开监听器有到达和模拟结束事件，本次未查到通用实时停止事件。导航组件的 `onExitPage(pageType)` 是退出事件候选，须在目标版本证明它对应结束引导而非只关闭界面。若无法证明，F04 保持关闭。公开 `pauseNavi`/`resumeNavi` 仅适用于模拟导航。F05 可询问是否按原目的地重新规划，实际接续保持关闭。 [SDK AMapNavi](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNavi.html) · [Android 核心回调](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNaviListener.html) · [组件回调](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/INaviInfoCallback.html) |
| E01 沿途搜索、E06 放弃搜索 | 现有周边查询以单个中心点及半径为输入。没有活动路线折线可用。 | **需可读活动路线及搜索 SDK 授权。** `RoutePOISearchQuery(polyline, type, range)` 可以沿指定折线检索，覆盖厕所、服务区、充电站等指定类别。额外绕行须使用同一剩余路线版本、相同端点和策略，与经 POI 方案作差，并标为估算。供应方缺字段时保留未知。E06 可本地撤销搜索，但只在沿途搜索真实接通后验收该项。 [沿途搜索构造器](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/Search/com/amap/api/services/routepoisearch/RoutePOISearchQuery.html) · [支持类别](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/Search/com/amap/api/services/routepoisearch/RoutePOISearch.RoutePOISearchType.html) · [返回量语义](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/Search/com/amap/api/services/routepoisearch/RoutePOIItem.html) |
| E02–E05 新增、删除、重排途经点及更换终点 | URI 只能重新交接。无法保证正在执行的原路线保持。 | **需验证行中提交原子性。** SDK 的 `reCalculateRoute(strategy)` 保持途经点和终点。独立算路可在原行程仍运行时计算新方案，`startNaviWithPath` 能使用指定方案开始导航，公开文档未承诺失败时原引导原样保留。另有出行解决方案的途经点接口，其授权和适用场景独立。四项逐个验证提交、失败保留和过期版本拒绝，通过后分别开放。 [SDK AMapNavi](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNavi.html) · [司乘同显司机端](https://lbs.amap.com/api/mobility-standard/inservice/sctx/driverandroid) |
| G01/G02 静音与恢复、G03 调节导航音量 | 当前应用不能操作外部高德播报通道。 | **需授权 SDK 和实际音频隔离测试。** SDK 内置播报提供 `stopSpeak` 和 `startSpeak`，也可通过文本回调交给应用自己的导航播报播放器。没有确认独立的内置音量接口。G03 用专用播放器的增益实现，或保持关闭。 [高德导航播报](https://lbs.amap.com/api/android-navi-sdk/guide/voice/speech-synthesizer) |
| G04–G07 缩放、全览、路况图和卫星图 | 当前 URI 不提供在已打开高德实例上的这些控制。 | **需内嵌地图授权及显示验证。** 导航视图支持 `zoomIn`、`zoomOut` 与全览模式；地图对象支持路况图层和卫星底图。逐项验证锁车视角下是否生效，以及导航视图是否允许稳定切换卫星底图。G07 为可选项。 [导航视图](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNaviView.html) · [全览模式](https://lbs.amap.com/api/android-navi-sdk/guide/custom-ui/showmode-trackmode) · [地图图层](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/3D/com/amap/api/maps/AMap.html) |
| G08 清除搜索记录 | 当前未发现可清除的本机历史记录存储。 | **本机能力可独立做，先确认产品确实保存该历史。** 如有已存在的应用历史，在确认后删除该范围并验证删除后重开。当前候选、家和公司、外部高德历史及行程分别处理。 |
| A07/A08 别名和多条件查找 | 现有地点查询可以返回经验证的名称、地址和坐标。尚无结构化经营属性筛选。 | **可先做有证据的候选与澄清。** Web POI 2.0 提供类型、营业时间等可选字段。严格筛选需要实际接口授权、来源与时间可用性。无可信属性时说明未知。仅有地理位置、类别和匹配名称时，仍可返回说明依据的候选。 [Web POI 2.0](https://lbs.amap.com/api/webservice/guide/api-advanced/newpoisearch) |
| H02–H04 沿途兼容充电、SOC 约束和充电休息组合 | 现有地点查询可找站点。手机端无真实车辆 SOC。 | **需活动或已确认路线、车型与充电数据。** 搜索 SDK 9.3.1 起提供充电站类别。车型接口、充电能力、空闲状态、SOC 采样时间、可解释能耗和目标车型实测均有各自前置。公开 Web 文档中的电动车路线指骑行。只有站点坐标时可以展示站点，无法验收车型兼容或保底到达电量。 [沿途充电站类别](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/Search/com/amap/api/services/routepoisearch/RoutePOISearch.RoutePOISearchType.html) · [Web 路径规划 2.0](https://lbs.amap.com/api/webservice/guide/api/newroute) |

## 最小可交付路径

1. **先保留 M1 交接。** 真机核对地图安装与缺失、GCJ/WGS 落点、多点顺序和打开反馈。将能力入口绑定已有真实执行通道，保持 `ACCEPTED` 只表示系统接受交接。
2. **先做查询型交付。** 对开发环境已有的授权，只读取高德 MCP 的已发布工具名称和输入 schema，确认驾车算路真实可用，再用公开地点样本验证返回。用同一供应方结果交付 D07 与具备字段时的 D04。D06 的只读预览需要实际可传策略并核实结果。应用内可选预览用独立列表身份，只有执行端确认所选路线才交付 D05。M2 整批发布仍需真实行程闭环。
3. **对内嵌 SDK 做一次目标设备验证。** 在已授权 Android 应用和签名上锁定版本，记录包版本、位置授权、网络和结果，执行一次真实驾车的「独立算路 → 选路 → 启动 → 收到同路线剩余值 → 停止并确认」。分别验证手动退出导航、只关闭页面、到达、后台返回及权限撤销。若只能确认启动和状态，保留 F04 关闭。目标授权需覆盖导航、地图、搜索各自的实际调用与部署场景。 [Android SDK 接入指南](https://lbs.amap.com/api/navigation-sdk-for-android/gettingstarted)
4. **通过行程闭环后再开启 M3 行中能力。** 先完成一类真实沿途检索和取消，以及专用播报、视图控制。每种行程修改独立验证原路线保留和成功切换，具备供应方回执后上线。
5. **M4 按数据源逐项交付。** 先实现可核验的 POI 候选属性及未知反馈。沿途站点发现与兼容筛选分别验收。车辆遥测、兼容性和能耗验证齐全后再开放 H03/H04。用目标车型回放和实测验收电量约束。

## 适配契约的验收样例

- 规划结果、地点候选和活动行程分别持有身份。请求携带 `operationId`。行程事件携带 `tripId`、路线版本、观察时刻和供应方路线标识。适配层记录单调时钟的事件接收时间做新鲜度判断。只有同一活动路线的有效导航更新才提供剩余时长、到达时刻和里程。
- SDK 算路提交返回成功后，等待同次回调。结束命令以提供方确认或可验证的活动状态终止为完成条件。仅收到页面退出、数据暂时静默或应用退到后台时保持状态未知。重复确认复用操作身份，迟到回调和旧订阅事件不得写入新行程。
- 断线或回前台时核对供应方实际行程身份。外部路线已变时刷新版本并使待确认的修改草稿失效。若接口无法恢复可靠身份，显示未知，并允许用户明确选择重新规划。

本次任务 5 已完成能力审计。目标设备和供应方授权还未验证，任务 5 的适配及真机闭环尚未完成。
