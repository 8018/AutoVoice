# 第 4 批：精确地点身份修复（待 dev 全量验证）

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

## 尚未完成的门禁

本实现未提交、未部署。根任务负责审核、离线 judge、dev 部署后完整文本/路线/统计基准及结果归档；上述门禁未运行前本批不算完成。未进行 Android、实车、语音或驾驶验收。精确身份规则是保守解析，不宣称覆盖任意自然语言专名或任意 POI 别名。
