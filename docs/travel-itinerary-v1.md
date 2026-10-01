# 行程攻略第一版

语音或文字请求（如“北京有什么好玩的”“北京一日游攻略”“北京两日游攻略”）由现有 DeepSeek 业务模型调用 `plan_travel` 语义工具，只返回 `travel/plan_guide`、城市和天数。该业务标志仍走云端与端侧现有语义仲裁，并由客户端当前轮对话管理和业务边界采用。客户端收到后立即显示“生成中”弹窗，再发 `travel_start`；服务端在收到合法启动确认前不会检索或生成正文。

服务端使用腾讯 SearchPro 取得来源摘要，再用 DeepSeek 的 `stream:true` 响应生成 Markdown。WebSocket 的 `document_stream` 按 `start`、`delta`、`complete`／`error` 下发；Android 弹窗逐片追加并用 Markwon 重渲染。正文不会作为第二个普通语义回复再次仲裁。服务端 `TravelDocumentInterceptor` 检查业务准入、旧轮输出许可、帧顺序和正文长度。新一轮有效识别文本或显式文字输入获准后，旧轮许可撤销并尽力取消上游 HTTP 请求。仅 VAD 不取消。第一版不支持“导航到第一站”或攻略转导航。

## 服务端配置

腾讯云标准方式（当前 `sdk/tencentsearchpro/SecretKey.csv` 的 SecretId/SecretKey 类型）：将 CSV 的两项分别设置为服务端环境变量 `TENCENTCLOUD_SECRET_ID`、`TENCENTCLOUD_SECRET_KEY`。也支持服务 API KEY 方式，设置 `TENCENT_SEARCHPRO_API_KEY`；若两种同时配置，优先使用 SecretId/SecretKey。生成模型仍需 `DEEPSEEK_API_KEY`。密钥只在服务端使用，不能加入 Android 配置或提交到 Git。上线前需在腾讯云开通 WSA 服务并确认账号有 SearchPro 调用权限。

当前检索只调用一次 SearchPro，最多取 8 条带 HTTP(S) 来源链接的结果，摘要输入有长度上限。模型被要求只根据检索来源编排行程，并对未经检索证实的票价、开放时间和预约条件提示出发前核实。已用当前账号完成一次 SearchPro 真实请求（HTTP 200，返回 10 条结果）；完整语音到弹窗链路仍需部署后联调。自动化测试使用本地模拟 HTTP 服务，不消耗线上额度。

开发和生产网关分别从 `/etc/autovoice-dev/.env`、`/etc/autovoice/.env` 读取这两项变量。现有发布流程不需更改：功能分支 PR 合入 `dev` 且 CI 成功后自动部署开发网关；之后 `dev` 合入 `main` 且 CI 成功后自动部署生产网关。Android debug APK 由 CI 构建，设备安装仍按现有流程进行。

## 验证

服务端测试覆盖 LLM 输出 `plan_travel`、标准方式签名请求、SearchPro 来源进入模型上下文、逐段 Markdown、取消及服务端流拦截器。客户端测试与编译验证协议接线和 UI 依赖。手工联调时依次验证：业务标志先到且立即显示弹窗；未收到 `travel_start` 不调用搜索；Markdown 在生成中持续增长；新有效输入后旧文档不再增长。
