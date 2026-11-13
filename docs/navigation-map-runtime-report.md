# Android 地图运行适配和验证

## 已实现

| 产品项 | 实现与状态 |
| --- | --- |
| D04、D06、D07 | 在线时可用已授权服务端高德路线预览，显示供应方返回的预计时长、里程、收费和所请求策略。出发前估算和活动导航数据分开。缺少近期精确定位时提示刷新位置，无结果或超时不打开地图。 |
| D05 | 选配内嵌 SDK 时，由 SDK 独立算路并按其路线标识选路。普通导航请求停在路线列表。只读预览需要明确点「按此目的地开始」进入新算路，再选路线。Web 路线方案只作预览。 |
| F01–F03 | 内嵌 SDK 使用实际路径身份匹配的 NaviInfo。30 秒内的同路线观测才能用于剩余时间、预计到达时刻和剩余里程。错序、陈旧及负值字段不作为有效观测。 |
| F04–F05 | 内嵌结束调用高德 stopNavi。等待确认时显示结束状态未知，允许返回地图、退出本地跟踪并重新规划。到达只从 SDK 事件确认。继续原导航缺少实时接续接口，用户可按原目的地重新规划。 |
| E01、E06 | 根据当前路径的段、链路与点索引取得剩余路线，调用高德沿途搜索。支持洗手间、服务区、充电站、加油站和餐饮。放弃搜索后不显示迟到结果，活动路线继续。绕行量保持未知，因为未建立同一策略和同一路段的可比算路。 |
| E02–E05 | 行中修改仍关闭。高德独立算路不会干扰原行程，公开的切换接口尚无证据保证切换失败时保留原活动引导。 |
| G01–G03 | 内嵌导航关闭高德内置语音，使用导航专用 Android TTS 输出。静音只停止该播报实例，音量只设置导航语句增益。设备缺少可用中文 TTS 时控制会反馈暂不可用。 |
| G04–G07 | 内嵌导航地图使用 SDK 控制缩放、全览、路况和卫星底图。显式显示与关闭是幂等操作，切换命令单独处理。 |
| G08 | 现有应用没有持久导航搜索记录。清除口令说明本机范围和高德 App 的处理入口。 |
| H02–H04 | 沿途充电站是位置候选。车辆 SOC、车型兼容、可用枪、目标到达电量均保持未知。 |

没有配置 Android Key 的常规版本可继续按原流程向高德地图交接单目的地、回家和有序多地点。外部地图接管时，AutoVoice 对实时进度、行程修改、播报及地图控制返回不可用提示。地图内语音控制的同步反馈可直接播报，异步请求先播报受理状态，实际结果显示在导航面板。

## 配置和运行边界

- 选配版本固定 Maven Central 合包 `com.amap.api:navi-3dmap-location-search:11.3.100_3dmap11.3.100_loc11.3.101_sea9.8.1`。构建从供应方 JAR 中分离 SDK classes、地图 assets 和 arm64-v8a 本机库。供应方发布物与地图 SDK 不入库。
- 在被授权的 Android 应用包名及签名下设置 `AUTOVOICE_AMAP_ANDROID_KEY`，或在未追踪的 `AutoVoice/local.properties` 写入 `amap.androidKey`。应用内由用户阅读并同意高德隐私说明后才调用内嵌 SDK。服务端 Web 服务 Key 属于独立授权。
- 仅验证编译与打包可传 `-PincludeAmapNavigationSdk=true`，或指定已取得的同版本合包 `-PamapNavigationSdkJar=/absolute/path/sdk.jar`。无 Key 时内嵌入口不开放。
- SDK 的 `isNaviStarted()` 在 `stopNavi()` 中先被清除，内部停止异常由 SDK 捕获，因此不能单独作为停止成功回执。当前产品界面保留结束待确认，退出本地跟踪时释放本应用持有的 SDK 对象。
- Android 包已编译接入实际高德公开 API。当前缺少绑定本应用签名的 Android Key，开始—查询—结束、定位与地图层实效、导航播报隔离、沿途搜索、后台及权限边界均需授权真机实测。M2/M3 发布门槛仍未满足。

## 构建证据

在 `AutoVoice` 运行以下命令，使用 Maven Central 的真实高德合包。

```sh
./gradlew :voice-business:test :app:testOpenDebugUnitTest :app:assembleOpenDebug \
  -PamapNavigationSdkJar=/private/tmp/autovoice-amap-sdk.jar -PuseIflytekStub=true \
  --offline --configure-on-demand --console=plain
```

业务单测 76 项和 Android Open Debug 单测 237 项通过，SDK Open Debug APK 打包成功。解包检查确认 APK 内包含 arm64-v8a 的 `libAMapSDK_NAVI_v11_3_100.so`、`libnui.so` 和实际地图资产。业务测试覆盖只读候选选中、路线列表身份、旧路线/乱序/过期回调、丢失与重复回执、超时后重试、停止待确认退出、搜索取消和图层重复显式切换。该包仅用于 SDK 编译与打包验证，实际语音设备包另行构建。

## 来源

[高德官方 SDK 下载和版本](https://lbs.amap.com/api/android-navi-sdk/download) · [Maven Central 供应方版本清单](https://repo.maven.apache.org/maven2/com/amap/api/navi-3dmap-location-search/maven-metadata.xml) · [官方独立算路](https://lbs.amap.com/api/android-navi-sdk/guide/route-plan/independentcalculateroute) · [AMapNavi](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNavi.html) · [NaviInfo](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/NaviInfo.html) · [RoutePOISearchQuery](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/Search/com/amap/api/services/routepoisearch/RoutePOISearchQuery.html) · [导航视图](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNaviView.html)
