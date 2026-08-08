# AutoVoice 云端服务部署（已部署: 47.94.4.204）

## 服务器侧布局

- `/opt/autovoice/app.jar` — 可执行 jar（`./gradlew :app:bootJar` 产出）
- `/etc/autovoice/.env` — 8 个密钥环境变量（**不入库**；按 `env.example` 建模板，
  真实值由服务器管理员填写；改后 `systemctl restart autovoice-gateway`）
- `/etc/systemd/system/autovoice-gateway.service` — systemd 服务（本目录同名文件），
  开机自启 + 崩溃自动重启（Restart=on-failure）

## 更新流程

```bash
# 本机
cd AutoVoiceServer && ./gradlew :app:bootJar
scp app/build/libs/app.jar root@47.94.4.204:/opt/autovoice/app.jar
# 服务器
systemctl restart autovoice-gateway && journalctl -u autovoice-gateway -f
```

## 运维命令

```bash
systemctl status autovoice-gateway   # 状态
journalctl -u autovoice-gateway -f   # 实时日志
```

## 前置要求

- 服务器: JDK 21（`dnf install java-21-openjdk-headless`），`demo-full` profile 下
  启动（unit 已带 `--spring.profiles.active=demo-full`）
- 阿里云安全组放行入方向 TCP 8080（手机 `ws://<公网IP>:8080/ws`）
