# WVP-PRO + ZLMediaKit Docker 部署文档

> 机器 IP：192.168.111.62 ｜ 部署目录：`/opt/wvp-stack/` ｜ 部署日期：2026-08-24
> 版本：WVP-PRO v2.7.4（20260107）+ ZLMediaKit master + MySQL 8 + Redis 7
> 用途：GB/T 28181 国标摄像头接入（注册、点播、录像），附带 JT1078 部标车载接口

## 一、架构总览

```
摄像头 (GB/T 28181, SIP UDP 8116, RTP UDP 30000-30500)
   │ 注册 / 点播信令
   ▼
wvp-server (WVP-PRO, Web 18978)  ── 管理/信令 ──▶ wvp-mysql (3306)  wvp-redis (6379)
   │ 拉流控制 (HTTP API 8082)
   ▼
wvp-zlm (ZLMediaKit)  ── 媒体转发 ──▶ 播放端 (Web / RTSP / HTTP-FLV / WebRTC 8001)
```

四个容器全部使用 `network_mode: host`，直接占用宿主机端口，避免 RTP 多端口映射问题。

## 二、端口清单（当前实际占用）

| 端口 | 协议 | 服务 | 说明 |
|------|------|------|------|
| 18978 | TCP | WVP Web / API | 管理页面、REST API |
| 8116 | UDP+TCP | WVP SIP | 国标信令，绑定 192.168.111.62 |
| 30000-30500 | UDP | ZLM RTP | 多端口 RTP，来流时动态占用 |
| 8082 | TCP | ZLM HTTP API | WVP 调 ZLM 用 |
| 8001 | UDP+TCP | ZLM WebRTC | ★ 原 8000 被本机 vllm 占用，已改，勿改回 |
| 15540 | TCP | ZLM RTSP | 拉流用 |
| 1935 | TCP | ZLM RTMP | 拉流用 |
| 21078 | TCP | WVP JT1078 | 部标车载设备接入（2026-08-26 启用） |
| 3306 | TCP | MySQL | 监听 0.0.0.0（纯内网） |
| 6379 | TCP | Redis | 监听 127.0.0.1，db6 |

## 三、目录结构

```
/opt/wvp-stack/
├── docker-compose.yml          # 4 个服务编排
├── DEPLOYMENT.md               # 部署目录内的简版文档（与本文档同源）
├── zlm/
│   ├── config.ini              # ZLM 配置（rtc 端口已改 8001）
│   └── record/                 # 录像输出
├── mysql/
│   ├── data/                   # 数据卷
│   └── init.sql                # 官方 初始化-mysql-2.7.4.sql（22 张表 + admin 账号）
├── redis/
│   └── data/
├── wvp/
│   ├── Dockerfile              # 自建镜像 wvp-pro:2.7.4
│   ├── build/wvp-pro-2.7.4-01070856.jar   # 官方 release 里的 jar
│   ├── static/                 # ★ 前端（index.html + static/js|css|...），必须挂载
│   ├── config/application.yml  # WVP 主配置
│   └── record/                 # 录像下载目录
└── logs/                       # 各容器日志
```

## 四、从零部署步骤（可复现）

### 1. 获取官方 release

```bash
# 国内访问 raw.githubusercontent.com 会超时，用 GitHub API 的 zipball 下载：
curl -L -o wvp-release.zip \
  "https://api.github.com/repos/648540858/wvp-GB28181-pro/zipball/v2.7.4-20260107"
# 解压后：wvp-release/ 下是 wvp-pro-*.jar，wvp-release/static/ 是前端，
# 数据库/ 下是 初始化-mysql-2.7.4.sql
```

### 2. 构建 WVP 镜像（官方没有提供 wvp 镜像，需自建）

`wvp/Dockerfile`：

```dockerfile
FROM docker.1ms.run/library/eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /opt/wvp
COPY build/wvp-pro-2.7.4-01070856.jar /opt/wvp/wvp.jar
EXPOSE 18978 8116
ENTRYPOINT ["java", "-Xms256m", "-Xmx1024m", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/opt/wvp/wvp.jar"]
```

```bash
cd /opt/wvp-stack/wvp
docker build -t wvp-pro:2.7.4 .
```

> 注意：jar 放进镜像即可，**前端 static/ 不要放进镜像**，通过 volume 挂载（见步骤 5）。

### 3. MySQL

- 用官方 `初始化-mysql-2.7.4.sql` 作为 `init.sql` 挂载到 `/docker-entrypoint-initdb.d/`
- 启动参数必须包含 `--lower-case-table-names=1`（**只能首次初始化时设置**，之后改不了）
- 账号：`wvp_user / wvp_password`，库名 `wvp`，root 密码 `root123`
- 若库已存在但缺表/账号不对：清空 `mysql/data/` 后重启容器重建（docker-entrypoint 只在空数据目录时执行 init.sql）

### 4. ZLM 配置（zlm/config.ini）

关键项（当前实际值）：

```ini
[general]
secret = wvp28181secret   # 必须与 application.yml 中 zlm.secret 一致

[http]
port = 8082               # WVP 通过它调用 ZLM API

[rtc]
port = 8001               # ★ 8000 被本机 vllm 占用，已改 8001，勿改回
tcpPort = 8001
```

### 5. WVP 配置（wvp/config/application.yml）

当前实际值：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/wvp?...&allowPublicKeyRetrieval=true  # MySQL8 必须
    username: wvp_user
    password: wvp_password
  redis:
    host: 127.0.0.1
    port: 6379
    database: 6

server:
  port: 18978

sip:
  ip: 192.168.111.62   # 必须填本机实际 IP，设备管理页会原样显示此 IP 给摄像头填写；0.0.0.0 会导致无法接入
  port: 8116
  domain: 3402000000
  id: 34020000002000000001
  password: wvp_sip_password

media:
  zlm:
    - id: zlmediakit01
      ip: 192.168.111.62
      http-port: 8082
      secret: wvp28181secret
      rtp-port-range: 30000,30500
      sdp-ip: 192.168.111.62
      stream-ip: 192.168.111.62

# 部标 JT/T 1078（车载）设备接入；不配置 enable=true 时 /api/jt1078/* 接口不注册，
# 前端 JT1078 菜单轮询会持续报 404/500（issue #1977）
jt1078:
  enable: true
  port: 21078        # 1078 设备接入 TCP 端口
  password: admin123 # 设备鉴权密码
```

### 6. 启动与验证

```bash
cd /opt/wvp-stack
docker compose up -d
# 等待约 30 秒（WVP 启动较慢，别急着下结论）

# 验证：
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18978/     # 期望 200
docker logs wvp-server 2>&1 | grep 'Started VManageBootstrap'        # 启动成功标志
docker logs wvp-server 2>&1 | grep 'SIP SERVER'                      # SIP 绑定成功
ss -uln | grep 8116                                                   # SIP UDP 监听
```

## 五、访问与使用

### 1. 管理页面

- 地址：`http://192.168.111.62:18978/index.html`
- 账号：`admin` / `admin`（不是 123456）
- 主要页面：
  - 设备管理（`#/device`）：国标接入服务参数、设备/通道列表
  - 直播 / 录像 / 平台级联 / 系统设置

### 2. 摄像头接入参数（设备管理页"国标接入服务"处可见）

| 参数 | 值 |
|------|-----|
| 平台 IP | `192.168.111.62` |
| SIP 端口 | `8116`（UDP） |
| SIP 域 | `3402000000` |
| 设备编码 | 20 位，以 `3402000000` 开头（如 `34020000001320000001`） |
| 认证密码 | `wvp_sip_password` |

摄像头侧配好后 1~2 分钟内设备列表应出现该设备；之后在"直播"里直接点播。

### 3. API（手动调用示例）

```bash
# 登录（GET，密码要 32 位 MD5）
M2=$(echo -n admin | md5sum | cut -d' ' -f1)
TOKEN=$(curl -s "http://127.0.0.1:18978/api/user/login?username=admin&password=$M2" \
  | grep -oE 'accessToken":"[^"]+' | cut -d'"' -f3)

# 系统/国标接入信息
curl -s -H "access-token: $TOKEN" "http://127.0.0.1:18978/api/server/system/configInfo"
# ZLM 节点状态（看 status 字段，true=在线）
curl -s -H "access-token: $TOKEN" "http://127.0.0.1:18978/api/server/media_server/list"
```

注意：前端真实 API 路径以 `/opt/wvp-stack/wvp/static/static/js/` 下的 bundle 为准，
源码里的路径（如 `/api/server/configInfo`）在 jar 中可能不存在（500），真实路径是 `/api/server/system/configInfo`。

## 六、日常运维速查

```bash
cd /opt/wvp-stack
docker compose ps                          # 容器状态
docker logs -f wvp-server                  # WVP 日志（注册/点播问题先看这里）
docker logs -f wvp-zlm                     # ZLM 日志（收流/推流问题）
docker compose restart wvp                 # 重启 WVP（改 application.yml 后用这个）
docker compose restart zlm                 # 重启 ZLM（改 config.ini 后用这个）
docker compose down && docker compose up -d  # 全量重启
# 数据库（容器内）：
docker exec -it wvp-mysql mysql -uwvp_user -pwvp_password wvp
```

## 七、坑点记录（本次部署实际踩到的）

### 1. 官方 release 的 jar 不含前端 ★最大坑
- 现象：WVP 启动正常，`GET /` 返回 500 `NoResourceFoundException: No static resource .`
- 根因：官方 CI（build.yml）顺序是 `mvn package` 之后再 `npm run build:prod`，**官方 release 的 jar 里根本没有前端**，前端与 jar 并列放在 `static/` 目录。jar 里只有个 126 字节的占位 `BOOT-INF/classes/index.html`（内容就一个 "111"）。
- WVP 源码里没有任何 `addResourceHandlers` / `static-locations` 自定义，纯靠 Spring Boot 默认静态资源机制：`static/` 放在 **jar 的工作目录旁边**即被识别（日志出现 `Adding welcome page: ServletContext resource [/index.html]`）。
- 解决：`static/` 目录放宿主机 `./wvp/static/`，compose 加挂载 `- ./wvp/static:/opt/wvp/static`。
- 备选方案（不依赖工作目录约定时）：application.yml 加 `spring.web.resources.static-locations: classpath:/static/,file:/opt/wvp/static/`（本次未采用）。

### 2. ZLM WebRTC 端口 8000 与本机 vllm 冲突
- 现象：ZLM 起来后 8000 端口被 vllm 占着，rtc 无法绑定。
- 解决：`zlm/config.ini` 的 `[rtc] port` 和 `tcpPort` 8000 → **8001**。
- ⚠️ 8000 是 vllm 的服务端口，**永远不要改回 8000**。

### 3. 国内网络下载
- `raw.githubusercontent.com` 直接超时，GitHub 官方 releases 也不稳；`github.com/user-attachments`（issue 图片）也不通。
- 可用：`api.github.com`（含 zipball 接口）、Docker 镜像走 `docker.1ms.run` 镜像源（compose 里所有 image 都加了该前缀）。
- 容器内无 `unzip`/`jar`/`python3`（ZLM 官方镜像），宿主机有；解包/解 jar 在宿主机做。

### 4. WVP 端口冲突时直接自杀，日志不友好
- WVP 的 SIP 端口（8116）被占用时进程直接 `System.exit`，日志里看不到明显报错（曾出现日志为空的测试实例）。
- 调试多实例时每个实例必须换 `--server.port` 和 `--sip.port`，并预留 ~30 秒启动时间。

### 5. 登录接口容易搞错
- 接口是 **`GET /api/user/login?username=xx&password=xx`**（不是 POST /login）。
- **密码必须先用 32 位 MD5 加密再传**（前端做的，手动 curl 时容易漏）。
- 默认账号密码是 **admin / admin**（不是网上常见的 123456；123456 会返回 `Bad credentials`）。
- 登录成功后 `accessToken` 在响应体和 `access-token` 响应头里，后续 API 要带 `access-token` 头。

### 6. MySQL 8 相关
- JDBC URL 必须带 `allowPublicKeyRetrieval=true`（8.0 的 caching_sha2_password 默认加密，否则连接报 Public Key Retrieval is not allowed）。
- `--lower-case-table-names=1` 只能首次初始化 data 目录时生效，改不了；WVP 表名大小写敏感，漏了只能重建库。
- 重建库 = 清空 `mysql/data/` + 重启（init.sql 只在空数据目录时执行）。
- 安全提醒：目前 3306 监听 0.0.0.0，纯内网用可以接受；若要收紧，compose 里 mysql 去掉 `--bind-address=0.0.0.0` 改回 127.0.0.1（WVP 通过 127.0.0.1 连，不受影响）。

### 7. sip.ip=0.0.0.0 导致设备管理页"国标接入服务"显示 0.0.0.0（2026-08-26 修复）
- 现象：设备页（`/index.html#/device`）国标接入服务 IP 显示 `0.0.0.0`，摄像头端没法填这个 IP，无法接入。
- 根因：`application.yml` 里 `sip.ip: 0.0.0.0`。WVP 源码中 `SipConfig`（`@ConfigurationProperties(prefix="sip")`）→ `SipLayer.run()` 直接用该值作为 SIP 监听绑定 IP，且 `showIp` 默认等于它，页面显示的就是它。
- 解决：`sip.ip` 改为本机实际 IP（`192.168.111.62`）后 `docker compose restart wvp`。host 网络模式下绑具体 IP 无副作用；SIP UDP/TCP 会绑到该 IP:8116（`ss -uln`/`ss -tln` 可确认）。
- 备选：只想改页面显示而不改绑定，可单独设 `sip.show-ip`。
- 验证：`GET /api/server/system/configInfo`（带 access-token）返回的 `sip.ip`/`sip.showIp` 应为实际 IP。
- ⚠️ 注意：compose 文件未变化时 `docker compose up -d wvp` **不会**重启容器，要 `docker compose restart wvp` 才生效。

### 8. 未启用 jt1078 时前端轮询 /api/jt1078/terminal/list 报 404（issue #1977，2026-08-26 修复）
- 现象：打开网页后定时出现 `GET /api/jt1078/terminal/list?page=1&count=15&query=&online=` 404（部分版本表现为 500 `No static resource api/jt1078/terminal/list`）。
- 根因：`JT1078TerminalController` 等带 `@ConditionalOnProperty(value = "jt1078.enable", havingValue = "true")`，未配置 `jt1078.enable: true` 时控制器不注册。
- 解决：`application.yml` 加
  ```yaml
  jt1078:
    enable: true
    port: 21078
    password: admin123
  ```
  后 `docker compose restart wvp`。启动日志出现 `服务:JT808 Server 启动成功, port:21078` 即生效；此时会额外占用 21078 TCP 端口。
- 说明：JT1078 是部标车载设备协议，普通 GB28181 网络摄像头用不到该模块，但前端菜单会轮询其接口，不启用就会一直报错。

### 9. 其他
- WVP 启动要 ~30 秒（`Started VManageBootstrap` 出现才算成功），验证别太急。
- ZLM 多端口 RTP 模式下 30000-30500 是**来流时动态占用**，启动后 `ss -uln` 看不到这段端口属正常现象。
- 改 `application.yml` / `config.ini` 后都要 `docker compose restart <服务>` 重启对应容器才生效。
- WVP 的 ZLM 节点状态看 `/api/server/media_server/list` 的 `status` 字段（true=在线），不是 `online` 字段。
- 源码参考位置（本机缓存）：`/tmp/wvp-src/648540858-wvp-GB28181-pro-298165b`（tag v2.7.4-20260107，部分 API 路径与 jar 不完全一致，前端 JS bundle 是最可靠的路径来源）。

## 八、本次部署变更记录

| 日期 | 变更 | 原因 |
|------|------|------|
| 2026-08-24 | 初始部署：4 容器 + 前端挂载 + ZLM rtc 改 8001 | 搭建环境 |
| 2026-08-26 | `application.yml`：`sip.ip: 0.0.0.0` → `192.168.111.62`，重启 wvp | 设备管理页国标接入服务显示 0.0.0.0，摄像头无法接入 |
| 2026-08-26 | `application.yml`：新增 `jt1078` 段（enable/21078/admin123），重启 wvp | 前端定时 404 `/api/jt1078/terminal/list`（issue #1977 官方方案） |
