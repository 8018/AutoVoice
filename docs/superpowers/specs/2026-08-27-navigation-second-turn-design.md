# AutoVoice 导航二轮候选选择设计

## 目标

用户说“导航去万达”后，AutoVoice 在当前应用内展示附近候选。此时不打开高德；只有下一轮用户明确说“第二个”“人民路那个”或完整地点名称，才下发最终 `navigation/navigate` 并拉起导航。

## 主流实现调研

- Android for Cars 将地点列表/路线预览与“开始导航”动作分离，推荐以 `MapWithContentTemplate + ListTemplate` 展示地点，用户选择后才进入导航；这是候选展示与有副作用动作分阶段的典型模式。
- Google Assistant 的 visual selection 使用 scene + slot filling：服务端保存当前 scene 和候选，下一轮选择值回传 webhook；Alexa Dialog Management 同样用 `ElicitSlot/ConfirmSlot` 和 dialog state 管理待补槽位。
- 高德 POI 搜索同时支持关键词、周边搜索和 POI 详情；周边搜索可传车辆坐标、半径并按距离排序，候选可稳定携带名称、地址和坐标。

参考：

- https://developer.android.com/training/cars/apps/navigation
- https://developer.android.com/design/ui/cars/guides/app-types/navigation-apps
- https://developers.google.com/assistant/conversational/prompts-selection
- https://developers.google.com/assistant/conversational/scenes
- https://developer.amazon.com/en-US/blogs/alexa/post/efd1fd50-84fe-4e48-97e2-a61ad0612bc3/building-conversational-alexa-skills-confirming-slots-with-dialog-managemen
- https://lbs.amap.com/api/mcp-server/summary
- https://lbs.amap.com/api/webservice/guide/api-advanced/search

## 状态机

```text
IDLE
  └─ “导航去 X” → resolve_navigation(location, X)
       └─ 单目的地候选非空 → WAITING_SELECTION(sessionId, candidates, TTL=2min)
            ├─ “第 N 个”/唯一名称或地址 → navigation/navigate → 清状态 → 拉起高德
            ├─ 越界序号/多个同名 → 提示重选，保留状态
            ├─ “取消/算了” → 清状态，不导航
            └─ 其他话术 → 正常交给车控/模型，候选保留至过期
```

## 协议

首轮为 action，但不是执行导航：

```json
{
  "domain": "navigation",
  "intent": "choose_destination",
  "slots": {
    "query": {"type": "string", "value": "万达广场"},
    "candidates": {
      "type": "string",
      "value": "[{\"poiname\":\"万达广场东店\",\"lat\":30.1,\"lon\":120.1,\"address\":\"中山路1号\"}]"
    }
  }
}
```

第二轮解析成功后继续使用现有 `navigation/navigate {poiname,lat,lon}`，因此真正的高德 URI 执行器无需新增协议。

## 并发与仲裁

- 首轮 `resolve_navigation` 结果直接终止 Agent Loop，避免模型擅自选择第一项并调用 `navigate`。
- Classic：ASR 完成后先用会话状态机解析明确选择，命中则不再等待一次 LLM 调用。
- Omni：Qwen 与旁路 ASR 仍同时获得音频。处于候选选择状态时暂存 Qwen 输出；ASR 命中后只丢弃其输出，Qwen 自然完成，不取消任何候选。
- 云端空调离线候选与在线候选、端侧车窗候选与云端结果的既有两级仲裁规则不变。

## 当前边界

- 单目的地进入候选二轮；多途经点仍沿用原有一次性解析，避免一次会话同时维护多组候选产生组合歧义。
- 候选状态按网关 sessionId 隔离，2 分钟过期；服务重启后不恢复。
- 弹窗不支持点击启动导航，防止与“必须语音明确选择”的产品约束冲突。
