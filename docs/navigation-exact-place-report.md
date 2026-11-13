# 第 4 批：精确地点身份修复（dev 全量验证通过）

## 2026-11-13 城市行政后缀补修（待 dev 重新验证）

后续 dev 基准 `2026-11-13T12-37-59.050Z-71fd05a.json` 暴露两项参数表达变化：`三亚市凤凰国际机场` 与地图的 `三亚凤凰国际机场` 不一致；`北京市北京南站` 参考点未匹配 `北京南站`。新增四项离线测试，覆盖机场直接候选、同 ID 详情、内部字符/异城拒绝，以及两种参考点参数表达。原测试全部保留。

`/tmp/airport-city-suffix-red.log` 记录 55 项 facade 测试中机场直接候选和详情两项失败；内部字符/异城拒绝通过。`/tmp/reference-city-suffix-red.log` 记录参考点回归测试失败。随后增加仅作用于已知城市前缀的行政后缀归一：只有紧接该城市名称的“市”可被归一；保留完整搜索状态、专名内部字符、城市范围过滤及同 ID 详情核验。

最终验证：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./AutoVoiceServer/gradlew -p AutoVoiceServer --offline test :app:bootJar`；日志 `/tmp/city-suffix-final-green.log` 为 BUILD SUCCESSFUL。全部 XML 汇总 **653 tests，0 failures，0 errors，0 skipped**；`git diff --check` 通过。

新 `AutoVoiceServer/app/build/libs/app.jar` SHA-256：`a7a8b39b32bb448800ace094fc9bc7c84ba6a890ffa05d1fb8ebaa0c79e5eeda`。本次补修未提交、未部署，完整 dev 文本/路线/统计门禁由根任务继续执行，未运行的门禁不计通过。

## 实现范围

- `NavigationToolFacade` 保留连写城市的完整专名，明确“的”及原有普通类别仍按城市类别检索；博物馆/科技馆保留专名歧义。明确品牌条件与城市连写时仍查品牌门店，继续核验品牌证据。
- 城市连写专名、命名车站和博物馆在候选截断前核验身份。车站不接受地铁、公交、入口等附属点；博物馆允许馆区，但明确馆区只接受该馆区。无符合身份的候选不再创建模糊 geocode 兜底。
- 搜索状态保留完整专名；换批排除已展示候选，库存耗尽不混入其他地点；明确城市纠正优先于名称推断城市。
- 机场简称允许真实城市前缀及登记别名。无坐标的候选按原 POI ID 查详情，详情的城市/别名再核验；异 ID、无证据前缀、航站楼和停车场均不作为机场根地点。
- 工具参数描述明确要求 `destinations` 保留完整专名和分馆，不能因 `city` 已填写而删除名称前缀。

## 离线证据

新增 7 个服务端测试，保留原测试。先红后绿：

1. `/tmp/batch4-red.log`：49 个 facade 测试中 3 个失败，分别复现车站混入、博物馆混入、机场简称详情前被拒绝。
2. `/tmp/batch4-followup-red.log`：51 个中 2 个失败，复现北京咖啡店类别回归和登记机场别名未进入详情核验。
3. `/tmp/batch4-brand-red.log`：城市连写品牌测试失败，复现北京星巴克分店被误拒绝。

红测命令：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./AutoVoiceServer/gradlew -p AutoVoiceServer --offline :skill-mcp:test --tests '*NavigationToolFacadeTest'`；品牌红测使用 `--tests '*NavigationToolFacadeTest.cityQualifiedBrand*'`。

最终命令：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./AutoVoiceServer/gradlew -p AutoVoiceServer --offline test :app:bootJar :tts-server:bootJar :skill-manager:bootJar`。

`/tmp/batch4-final-green.log`：BUILD SUCCESSFUL。全部服务端 XML 汇总 649 tests、0 failures、0 errors、0 skipped（基线 642）。`git diff --check` 通过。

一次使用不带项目名的 `bootJar` 在 telemetry 模块失败，因为该库没有主类；按 dev 工作流改为三个可部署服务的构建任务后成功。Gradle 输出既有 Gradle 9 弃用提示。

构建产物 SHA-256：

- `AutoVoiceServer/app/build/libs/app.jar`：`974c852b1539db2af2b857136d55e10692b4f6fab129fd83a003e2a2d95e9ba0`
- `AutoVoiceServer/tts-server/build/libs/tts-server.jar`：`d2f67ca34bb8193f468dee47a9c6007ab2b4159e57eeb82af68b8972d19e0886`
- `AutoVoiceServer/skill-manager/build/libs/skill-manager.jar`：`ce68e8c77155ee7154d435002883f23120333f63fcb91527ebd70c98e10fd2d9`

## 开发环境完整验证

已审核并提交为 `dbe2fea538d33999ad1067f084e76ce7808f1036`，只部署 dev 网关，保留旧包及就绪失败回滚机制，生产未改。

- 文本 v3：33 次请求，29 项业务目标通过、4 项能力边界通过、0 失败；平均 1,390 ms，P95 2,690 ms，最大 2,768 ms。
- 原 v1 的 23 条、新增 v2 的 4 条、新增 v3 的 6 条均独立通过正确率及耗时门槛。
- 真实路线接口 3 条及统计接口/资源 3 组（7 次 HTTP 请求）全部通过。
- 新增精确用例修复前 6 条中 4 条失败，修复后全部通过。候选耗尽明确回复“没有找到更多候选”，不再返回入口和停车场。
- 基准判定器 11 项契约测试通过；A01/B05 四类业务测试加入清单。

完整结果：[文本](qa/navigation-benchmark-runs/2026-11-13T07-31-14.201Z-dbe2fea.json)、[路线](qa/navigation-route-runs/2026-11-13T07-32-05.552Z-dbe2fea.json)、[统计](qa/navigation-stats-runs/20261113T073206Z-dbe2fea538d33999ad1067f084e76ce7808f1036.json)。

未进行实车、语音或驾驶验收。精确身份规则是保守解析，不宣称覆盖任意自然语言专名或任意 POI 别名。小样本文本耗时不代表完整语音或生产 SLO。
