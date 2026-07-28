# Mavic 3T 浏览器监控与 RC Pro MSDK 控制

本目录包含三端实现：

- `Cloud-API-Demo`：电脑浏览器控制台。
- `DJI-Cloud-API-Demo`：Java API、WebSocket 桥接、控制租约和任务存储。
- `Mobile-SDK-Android-V5`：安装在 DJI RC Pro Enterprise 上的 MSDK V5.18.0 应用。

## 安全模型

浏览器不能直接连接飞机。所有指令依次经过浏览器登录、后端单操作者租约、
WebSocket Bearer Token、RC 端协议校验和 MSDK Virtual Stick。启用控制后，RC 端
给首个摇杆帧 1.5 秒握手窗口；收到首帧后使用 300 ms 看门狗。网络断开、应用
进入后台、飞机断连、低电量或控制权变化都会回中并释放 Virtual Stick。后端
控制租约 10 秒无活动后会主动发送安全释放。

界面中的 `STOP REMOTE CONTROL` 表示摇杆回中并释放 Virtual Stick，不表示空中
关闭电机。

## 配置与密钥

后端和构建 Android APK 时必须使用同一个高强度随机 Token。生产环境不要使用仓库
中的默认值；`JWT_SECRET` 和控制 Token 都必须至少 32 字节：

```powershell
$env:MSDK_CONTROL_AUTH_TOKEN = '<至少 32 字节随机值>'
$env:MSDK_CONTROL_WS_URL = 'ws://<电脑局域网IP>:6789/api/v1/msdk/control'
$env:MSDK_CONTROL_DRY_RUN = 'true'
$env:DJI_AIRCRAFT_API_KEY = '<DJI MSDK App Key>'
$env:MYSQL_PASSWORD = '<MySQL 密码>'
$env:JWT_SECRET = '<至少 32 字节随机值>'
```

Android 本机开发也可把 `DJI_AIRCRAFT_API_KEY` 写入已忽略的
`SampleCode-V5\android-sdk-v5-as\local.properties`；不要再把 App Key 提交到
`gradle.properties`。前端先将 `Cloud-API-Demo\env\.env.example` 复制为同目录
中已忽略的 `.env.local`，再填写 DJI License 与实际 API/WebSocket 地址。

MySQL、Redis 和 MQTT 的地址与凭证分别由 `MYSQL_*`、`REDIS_*` 和 `MQTT_*`
环境变量配置。任务文件目录可通过 `MSDK_MISSION_STORAGE_PATH` 修改。

发布版 APK 的签名凭证也只能放在环境变量或已忽略的 `local.properties` 中：

```powershell
$env:ANDROID_SIGNING_STORE_FILE = 'C:\安全目录\release.jks'
$env:ANDROID_SIGNING_STORE_PASSWORD = '<密钥库密码>'
$env:ANDROID_SIGNING_KEY_ALIAS = '<密钥别名>'
$env:ANDROID_SIGNING_KEY_PASSWORD = '<密钥密码>'
```

未配置这四项时，发布版构建会拒绝执行；调试版使用每台开发机自己的 Android
debug key，不再使用仓库中公开的示例签名。若 RC Pro 上装过旧示例签名的 APK，
第一次切换时需要先卸载旧包，再安装新调试包。

跨不可信网络部署时，必须在反向代理终止 TLS，并将 Android 地址改为 `wss://`。
真实控制模式会拒绝明文 `ws://`；明文连接只允许用于默认的模拟/台架模式。
不要把后端端口、MySQL、Redis 或 MQTT 直接暴露到公网。

## 部署边界

当前实现按“一套受信任工作区、一台 RC Pro、一个当班操作者”设计，不应直接作为
多租户公网服务。控制 Bearer Token 会编入 APK；若要扩展到多设备、多组织或公网，
应先改为按设备签发的短期凭证，并补充持久化的租约/任务尝试关联、角色权限、
审计与任务文件保留配额。

## 构建

后端：

```powershell
cd C:\dev\DJI-Cloud-API-Demo
mvn -pl sample -am test
mvn -pl sample -am package -DskipTests
java -jar .\sample\target\sample-1.10.0.jar
```

前端：

```powershell
cd C:\dev\Cloud-API-Demo
npm run build
```

RC Pro APK（默认 `CONTROL_DRY_RUN=true`）：

```powershell
cd C:\dev\Mobile-SDK-Android-V5\SampleCode-V5\android-sdk-v5-as
.\gradlew.bat :sample:assembleDebug --no-daemon
```

APK 输出位于
`android-sdk-v5-sample\build\outputs\apk\debug\sample-debug.apk`。

## 台架验收顺序

1. 拆除桨叶，保持 `CONTROL_DRY_RUN=true`，启动 MySQL、Redis、MQTT 和后端。
2. 在 RC Pro 安装 APK；按 DJI 要求强制停止 Pilot 2，首次注册时保持联网。
3. 浏览器确认 RC、飞机、遥测和模拟模式状态正确。
4. 验证单操作者锁、命令回执、`SAFETY_RELEASE`、页面切后台、断网和 10 秒租约超时。
5. 上传并验证 KMZ，但不执行真实航线。
6. 仅在以上项目全部通过后，把 `MSDK_CONTROL_DRY_RUN` 改为 `false` 重新构建。
7. 先进行无桨电机禁用测试，再进行空旷场地、低高度、系留实飞；现场必须有
   能立即接管实体遥控器的合格飞手。

不得把浏览器按钮称为“电机急停”，也不得在人员、车辆或建筑物附近首次测试。
