# Helix Gateway

OpenAI Chat Completions 协议的防截断网关。Go 核心，既能作为独立服务端部署，也能通过 JNI 内嵌进 Android 应用，在手机上跑一个本地代理。

[![Release](https://img.shields.io/github/v/release/Xeltra233/Helix-gateway?label=release)](https://github.com/Xeltra233/Helix-gateway/releases/latest)
[![Build & Release](https://github.com/Xeltra233/Helix-gateway/actions/workflows/release.yml/badge.svg)](https://github.com/Xeltra233/Helix-gateway/actions/workflows/release.yml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](#license)

<p align="center">
  <img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="App Icon" />
</p>

---

## 它解决什么问题

客户端直连上游时，有三类问题会反复出现：

- **截断**：上游在给出 `finish_reason` 之前断开连接，客户端把半截内容当成完整回复存下来。
- **轮次 400**：Gemini、部分 CPA 上游要求对话以 `user` 结尾，末尾的 `assistant` 预填会被直接拒绝。
- **未闭合结构**：Markdown 代码块、JSON 在断点处断在半句，后续轮次的上下文被污染。

网关夹在客户端与上游之间，在链路上处理这三类问题。客户端只需要改一个 Base URL。

**边界**：网关负责传输层与协议层的修复，**不代替客户端做续写**。流被上游截断时，它会给出明确的错误信号，是否继续由客户端的抗截断逻辑决定——例如 SillyTavern 配合抗截断脚本可以自动续写，不装脚本则收到提示、内容停在断点。

---

## 快速开始

### Android

1. 从 [Releases](https://github.com/Xeltra233/Helix-gateway/releases/latest) 下载 `helix-gateway-v<版本>.apk`。
2. 安装并打开（首次需允许安装未知来源应用）。
3. 点「切换/新建」填上游：**上游地址** 如 `https://api.openai.com/v1`，**上游 Key** 填你的密钥。
4. 回到主界面点「启动网关」，状态变为「网关运行中」。
5. 复制界面上显示的应用链接（形如 `http://192.168.x.x:38472/v1`）和下游 Key（`sk-agw-...`）。
6. 用界面上的「拉取模型」「消息测试」确认端到端连通，然后到客户端里正常对话。

密钥以 Android Keystore AES-GCM 加密存储在本机，上游请求由手机直接发出，不经过任何中转。

### 服务端

```bash
# 1. 取二进制：Release 页按平台下载，或自行编译
go build -trimpath -ldflags="-s -w" -o helix-gateway ./cmd/gateway

# 2. 最小配置
export UPSTREAM_BASE_URL="https://api.openai.com/v1"
export UPSTREAM_API_KEY="sk-your-upstream-key"
export API_KEY="sk-helix-local"        # 下游客户端填这个
export PORT=8080

# 3. 启动
./helix-gateway

# 4. 验证
curl http://127.0.0.1:8080/healthz
curl -H "Authorization: Bearer sk-helix-local" http://127.0.0.1:8080/v1/models
```

端口只能用 `PORT` 环境变量设置，命令行只接受 `--version`。

---

## 客户端接入

网关对外暴露标准 OpenAI `/v1` 协议。

| 参数 | 值 |
| --- | --- |
| Base URL | `http://127.0.0.1:8080/v1`（局域网或远端换成对应 IP / 域名） |
| API Key | `API_KEY` 或 `DOWNSTREAM_KEYS_JSON` 里的有效 Key |
| 模型 | 上游原生模型名，如 `gemini-3.5-flash-low`、`claude-sonnet-4-6` |

`/v1/*` 始终要求 Bearer Key。没有配置任何下游 Key 时，请求一律 401，不会退化成免认证模式。

**SillyTavern**：API 选 `Chat Completion`，Custom Endpoint 填 `http://127.0.0.1:8080/v1`，API Key 填下游 Key，点 Connect 后选模型。

**Cherry Studio / NextChat / Chatbox**：提供商类型选 OpenAI 兼容，API 地址同上，Key 同上。

**cURL 验证**

```bash
# 模型列表
curl -s -H "Authorization: Bearer sk-helix-local" \
  http://127.0.0.1:8080/v1/models

# 流式对话
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-helix-local" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemini-3.5-flash-low",
    "messages": [{"role": "user", "content": "写一段 500 字的故事。"}],
    "stream": true
  }'
```

---

## 防截断怎么做

### 合成传输工具

`WRAPPER_MODE=prefer` 时，网关向模型声明一个自己生成的工具（名字由词池拼装，或按 `SYNTHETIC_TOOL_PREFIX` 加 96-bit 随机串），让回复以工具调用参数的形式落地。这样即使上游在文本流上截断，结构化的参数壳仍能约束输出完整性，网关再把参数还原成普通的 `delta.content` 发给客户端。

客户端看到的依然是标准流式响应，不需要理解这套机制。非文本模型（生图、嵌入、语音）由 `TEXT_MODEL_PATTERN` / `NON_TEXT_MODEL_PATTERN` 判定后跳过包装，走纯透传。

紧急回滚：`WRAPPER_MODE=off` 即恢复原生透传。

### 流式收尾语义

这是网关判断"这次回复到底完没完"的地方：

- 只有确认上游给出了 `finish_reason`，或上游主动发了 `[DONE]`，网关才向客户端补一次 `data: [DONE]`。
- 上游断流、连接中断、事件不完整时，网关发 `event: error` 帧，**不伪造终止符**。半截内容配上假的 `[DONE]` 会让客户端的完整性检查误判为正常结束，这比明确报错更糟。
- 上游的 `event:` / `id:` / `retry:` 字段行、以及 `: comment` 注释帧一律原样透传；无法解析的载荷不丢弃，原样转发。
- 上游自己发的 error 帧原样透传，其后不再追加终止符。
- 连续 `STREAM_HEARTBEAT_MS`（默认 15 秒）没有数据下行时，网关补一帧 `: keep-alive`。这是给 nginx `proxy_read_timeout` 一类中间层看的，客户端解析时会当作注释丢弃。

### 超时模型

`UPSTREAM_TIMEOUT_MS`（默认 120000）的含义随请求类型变化：

- **非流式**：每次上游尝试的总预算，包含读完 body。
- **流式**：先是首字超时；流一旦开始，转为读空闲超时——连续这么久没有任何字节才切断，活跃的长生成不受总时长限制。

首字超时返回 `504 upstream_timeout`。已经开始的流被空闲看门狗切断时，客户端会收到 `event: error`。

### 恢复策略

| 值 | 行为 |
| --- | --- |
| `repair`（默认） | 本地确定性修复：补齐未闭合的 JSON、修正断裂的工具参数结构 |
| `repair_then_retry` | 修不好时做一次安全重试 |
| `fail` | 直接返回错误 |

上游返回完全空响应时按 `UPSTREAM_EMPTY_RETRIES`（默认 3）重试。

### 出站请求形态

上游若按请求体特征做内容分类，命中词表可能静默降级。网关提供一道可逆的分级防护，只改出站字节形态，不动语义：

| 级别 | 行为 | 还原方式 |
| --- | --- | --- |
| `off` | 不处理 | — |
| `wire`（默认） | 请求体中的非 ASCII 字符写成 `\uXXXX` | 上游解析后语义不变 |
| `standard` | `wire` + 前导指令块中的高风险词替换为等价表述 | 只改网关自己的指令文本 |
| `deep` | `standard` + 词表中性词拆分掩码（`甲-乙`），可选把末尾 `assistant` 预填降级为 `user` | 响应中的掩码在转发前还原成原词 |

流式响应按事件解码后逐段还原，跨事件被切开的掩码词也能拼回来。自定义规则通过 `ANTI_ROUTE_RULES_FILE` 追加，格式 `{"split_terms": [...], "scrub": {"原文": "替换"}, "replace": false}`。

### 轮次闭环

末尾悬空的 `assistant` 预填、或与 Gemini 轮次约束冲突的系统提示，会被自适应修正为 `user` 结尾，避免触发 `Requests ending with a model turn are not supported`。

---

## 配置

配置按以下优先级加载，先命中先用：

1. 系统或终端已有的环境变量（适合 CI/CD 与 `docker -e`）
2. `ENV_FILE` 指定路径的文件
3. 当前工作目录下的 `.env`
4. 可执行文件同级目录的 `.env`（Windows 双击运行、服务自启动依赖这一条）
5. 父级目录的 `../.env`

`.env` 带 UTF-8 BOM 也能读，Windows 记事本直接保存不用额外处理。

### 上游

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `UPSTREAM_BASE_URL` | 必填 | 上游根地址，末尾 `/v1` 会自动补齐或规范化 |
| `UPSTREAM_API_KEY` | 视模式 | 上游 Bearer 密钥，`UPSTREAM_AUTH_MODE=bearer` 时必需 |
| `UPSTREAM_AUTH_MODE` | `bearer` | `bearer` 或 `none` |
| `UPSTREAM_TIMEOUT_MS` | `120000` | 见[超时模型](#超时模型)，范围 1000–600000 |
| `UPSTREAM_EMPTY_RETRIES` | `3` | 上游完全空响应时的重试次数，0–10 |
| `UPSTREAM_MAX_IDLE_CONNS` | `2048` | 连接池最大空闲连接 |
| `UPSTREAM_MAX_IDLE_CONNS_PER_HOST` | `512` | 单 Host 最大空闲连接 |
| `UPSTREAM_MAX_CONNS_PER_HOST` | `512` | 单 Host 最大并发连接 |

### 下游鉴权

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `API_KEY` | — | 单一访问 Key，与 `DOWNSTREAM_KEYS_JSON` 二选一 |
| `DOWNSTREAM_KEYS_JSON` | `[]` | 静态多 Key 配置，JSON 数组 |
| `ADMIN_API_KEY` | `admin-secret-key-12345` | `/admin/keys` 管理接口鉴权，自定义时至少 8 字符 |
| `KEY_HMAC_SECRET` | 内置 | 下游 Key 的 HMAC-SHA256 签名密钥，自定义时至少 16 字符 |
| `KEY_DB_PATH` | `./data/keys.sqlite` | 动态 Key 持久化路径 |

`ADMIN_API_KEY` 的默认值等于公开。部署到可被访问的地址前务必改掉。

### 防截断

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `WRAPPER_MODE` | `prefer` | `prefer` 自适应包装 / `required` 强制 / `off` 纯透传 |
| `RECOVERY_POLICY` | `repair` | `repair` / `repair_then_retry` / `fail` |
| `CONTROL_MESSAGE_ROLE` | `system` | `system` 或 `developer` |
| `CONTROL_MESSAGE_POSITION` | `tail` | `tail`（末尾强约束）/ `head` / `system_tail` |
| `SYNTHETIC_TOOL_PREFIX` | 空 | 固定工具名前缀，留空则随机拼装 |
| `SYNTHETIC_TOOL_STRICT` | `false` | 是否发送严格 Tool Schema |
| `TEXT_MODEL_PATTERN` | — | 正则：哪些模型按文本对话处理并启用防截断 |
| `NON_TEXT_MODEL_PATTERN` | — | 正则：哪些模型跳过包装直接透传 |
| `STREAM_HEARTBEAT_MS` | `15000` | 下行静默保活间隔，0 关闭 |
| `STREAM_SIDE_BUFFER_BYTES` | `0` | 流式旁路缓冲，0 表示零额外延迟直发 |

### 请求形态

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `ENTROPY_LEVEL` | `standard` | `off` / `light` / `standard` / `aggressive` |
| `ENTROPY_LAYOUT` | 随等级 | 覆盖 JSON 键序与空白随机化 |
| `ENTROPY_INVISIBLE` | 随等级 | 覆盖网关注入文本中的零宽分隔符 |
| `ENTROPY_PARAM_JITTER` | 随等级 | 覆盖采样参数 ±0.01 微扰 |
| `ENTROPY_SEED` | 随机 | 固定随机种子，仅用于复现问题 |
| `ANTI_ROUTE_LEVEL` | `wire` | `off` / `wire` / `standard` / `deep` |
| `ANTI_ROUTE_WIRE_ESCAPE` | 随等级 | 覆盖 `\uXXXX` 转义 |
| `ANTI_ROUTE_SCRUB` | 随等级 | 覆盖指令块净化 |
| `ANTI_ROUTE_SPLIT` | 随等级 | 覆盖拆词掩码 |
| `ANTI_ROUTE_DEMOTE_PREFILL` | 随等级 | 覆盖末尾 `assistant` 预填降级 |
| `ANTI_ROUTE_SCRUB_SCOPE` | `leading` | `leading` 仅前导指令块 / `all` 全部 system 文本 |
| `ANTI_ROUTE_SEPARATOR` | `hyphen` | `hyphen`(`-`) 或 `wordjoiner`(U+2060) |
| `ANTI_ROUTE_RULES_FILE` | — | 追加规则文件路径 |
| `MODEL_REPORT` | `headers` | `off` / `headers` / `body` / `both` |
| `MODEL_REPORT_KEY` | `helix_model` | `body` 回传时的字段名 |

`ENTROPY_LEVEL=off` 会让工具名固定为 `agw_emit_` + 96-bit 随机串，控制提示词与 JSON 布局回到单一写法。

### 限流与体积

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MAX_REQUEST_BYTES` | `16777216` | 请求体上限（16MB） |
| `MAX_RESPONSE_BYTES` | `16777216` | 响应体上限（16MB） |
| `MAX_CONCURRENT_REQUESTS` | `1024` | 全局并发上限 |
| `MAX_CONCURRENT_REQUESTS_PER_KEY` | `64` | 单 Key 并发上限 |
| `REQUEST_QUEUE_TIMEOUT_MS` | `50` | 过载排队等待上限 |

### 运维

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `HOST` | 全部网卡 | 监听地址，留空即 `0.0.0.0` |
| `PORT` | `8080` | 监听端口（Android 端固定 38472） |
| `LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `TRUST_PROXY` | `false` | 是否信任 `X-Forwarded-For` |
| `MODELS_CACHE_TTL_MS` | `30000` | `/v1/models` 缓存时长 |
| `SHUTDOWN_TIMEOUT_MS` | `30000` | 优雅关机超时 |

### `.env` 示例

```env
UPSTREAM_BASE_URL=https://api.openai.com/v1
UPSTREAM_API_KEY=sk-your-upstream-key
API_KEY=sk-helix-123456
PORT=8080
HOST=0.0.0.0

WRAPPER_MODE=prefer
RECOVERY_POLICY=repair
STREAM_HEARTBEAT_MS=15000

LOG_LEVEL=info
```

完整模板见 `.env.example`。

---

## 部署

### Windows

发布包内有 `run.bat`，双击即可。它会自动检查同级目录的 `.env`，缺失时从 `.env.example` 复制一份并提示填写；进程异常退出时保留窗口并给出排查方向。

### systemd

`/etc/systemd/system/helix-gateway.service`：

```ini
[Unit]
Description=Helix Gateway
After=network.target

[Service]
Type=simple
User=helix
WorkingDirectory=/opt/helix-gateway
EnvironmentFile=/opt/helix-gateway/.env
ExecStart=/opt/helix-gateway/helix-gateway
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now helix-gateway
journalctl -u helix-gateway -f
```

### Docker

```bash
docker build -t helix-gateway .
docker run -d --name helix-gateway \
  -p 8080:8080 \
  -e UPSTREAM_BASE_URL="https://api.openai.com/v1" \
  -e UPSTREAM_API_KEY="sk-your-upstream-key" \
  -e API_KEY="sk-helix-local" \
  helix-gateway
```

`KEY_DB_PATH` 指向的 SQLite 文件需要挂载卷才能跨重启保留动态 Key。

### 反向代理

nginx 放在网关前面时，流式响应必须关掉缓冲并把读超时拉长：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}
```

`proxy_buffering off` 少了这一行，流式会被 nginx 攒成一次性响应。网关的 `: keep-alive` 心跳只能防止 `proxy_read_timeout` 切断，救不了缓冲。

---

## 多 Key 管理 API

需要请求头 `Authorization: Bearer <ADMIN_API_KEY>`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/admin/keys` | 创建下游 Key |
| `GET` | `/admin/keys` | 列出全部 Key |
| `POST` | `/admin/keys/{id}/revoke` | 吊销指定 Key |

```bash
# 创建
curl -X POST http://127.0.0.1:8080/admin/keys \
  -H "Authorization: Bearer admin-secret-key-12345" \
  -H "Content-Type: application/json" \
  -d '{"name": "alice", "expires_in": 86400}'

# 列出
curl http://127.0.0.1:8080/admin/keys \
  -H "Authorization: Bearer admin-secret-key-12345"

# 吊销
curl -X POST http://127.0.0.1:8080/admin/keys/<id>/revoke \
  -H "Authorization: Bearer admin-secret-key-12345"
```

Key 的签名由 `KEY_HMAC_SECRET` 控制，持久化在 `KEY_DB_PATH`。Android 端把这个库配置成 `:memory:`，重启即清空，不在文件系统留明文。

---

## 监控

| 端点 | 返回 |
| --- | --- |
| `GET /healthz` | `{"status":"ok","version":"2.0.0"}` |
| `GET /readyz` | `{"status":"ready","version":"2.0.0"}` |
| `GET /metrics` | Prometheus 格式 |

`/metrics` 包含请求总量、活跃请求数、过载拒绝、合成包装命中/修复/重试/冲突等指标。

---

## 项目结构

```text
Helix-gateway/
├── cmd/
│   ├── gateway/          # 服务端入口
│   ├── androidbridge/    # JNI 桥接层，编译为 libhelix.so
│   └── live_test/        # 端到端联调客户端
├── internal/
│   ├── synthetic/        # 合成工具协议、流式状态机、参数修复
│   ├── recovery/         # 重试编排、空响应处理
│   ├── antiroute/        # 出站请求形态防护
│   ├── entropy/          # 请求形态随机化
│   ├── proxy/            # 上游客户端与路由
│   ├── keymgmt/          # Key 签发、校验、持久化
│   ├── limiter/ limits/  # 并发控制与体积限制
│   ├── modelid/          # 模型识别回传
│   ├── metrics/          # Prometheus 指标
│   ├── server/           # HTTP 服务与中间件
│   ├── logger/ config/   # 日志与配置
├── pkg/gateway/          # 可嵌入的引擎（Android 走这条路径）
├── android/              # Kotlin + Gradle 工程
│   └── app/src/main/java/org/helix/gateway/
│       ├── GatewayApplication.kt   # 启动流程、崩溃采集、安全模式
│       ├── bridge/                 # JNI 封装
│       ├── data/                   # 配置、Keystore 加密、主题偏好
│       ├── net/                    # 自检客户端
│       ├── service/                # 前台保活服务
│       ├── ui/                     # 单屏界面
│       └── util/                   # 崩溃日志、网络工具
├── docs/                 # 界面预览与配色素材
├── scripts/              # JNI 库构建脚本
└── .github/workflows/    # CI 与发布流水线
```

---

## 构建

### 服务端

```bash
go build -trimpath -ldflags="-s -w" -o helix-gateway ./cmd/gateway
./helix-gateway --version
# Helix Gateway version 2.0.0 (commit: ..., built: ...)
```

交叉编译：

```bash
CGO_ENABLED=0 GOOS=linux   GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o gateway-linux-amd64 ./cmd/gateway
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o gateway.exe ./cmd/gateway
```

### Android

需要 JDK 17+、Android SDK（API 34 / Build-Tools 34.0.0）、NDK 26.3.11579264、Go 1.24+。

```bash
# 1. 重新生成四个 ABI 的 JNI 库
./scripts/build-android-libs.sh

# 2. 单元测试
cd android && ./gradlew :app:testReleaseUnitTest

# 3. 打包
./gradlew :app:assembleRelease
# 产物: app/build/outputs/apk/release/helix-gateway-v2.0.0.apk
```

改过 JNI 桥接签名后必须重新跑第 1 步，否则 `.so` 与 Kotlin 侧对不上。

---

## 发布流程

版本号需要三处同步：`cmd/gateway/main.go`、`pkg/gateway/engine.go`、`android/app/build.gradle.kts`（`versionCode` 与 `versionName`）。

推 `v*` 标签触发 `.github/workflows/release.yml`：

1. 构建 Android APK（lint + 单测 + `assembleRelease`）
2. 交叉编译六个服务端二进制（Linux / Windows / macOS × amd64 / arm64）
3. 用 `ldflags -X` 把版本号与 commit SHA 注入二进制
4. 上传全部产物和 `checksums.txt` 到 GitHub Release

任意分支的 push 会先跑 `build.yml`：Go 单测与 vet、六平台交叉编译、四 ABI JNI 库、Android lint / 单测 / `assembleRelease`。

校验发布产物：

```bash
gh release view v2.0.0
sha256sum -c checksums.txt
helix-gateway --version
aapt2 dump badging helix-gateway-v2.0.0.apk | head -1
```

---

## 排错

**`400 Requests ending with a model turn are not supported`**
Google Gemini 与部分 CPA 上游的约束，要求对话以 `user` 结尾。网关默认会自适应修正；若仍出现，检查 `WRAPPER_MODE` 是否被设成 `off`。

**模型拒答算网关的问题吗**
不算。分两种：外审拦截（`finish_reason: "SAFETY"` 或 HTTP 400）和内审拒答（模型返回道歉文本，`role: "assistant"`，200）。内审拒答是提示词命中了模型的红线，需要调设定或换模型。

**改了 `.env` 不生效**
先看启动日志第一行的 `loaded configuration from .env file`，确认加载的是哪个路径。系统环境变量优先级高于 `.env`，同名变量会盖掉文件里的值。

**流式断在半截**
看网关日志里有没有 `upstream stream ended without a finish_reason or [DONE]`。有的话是上游主动断的，网关已发出 `event: error`。客户端有抗截断脚本会自动续写，没有的话需要手动继续。

**长时间没输出被切断**
是空闲看门狗触发了。上游思考时间长就调大 `UPSTREAM_TIMEOUT_MS`，别让首字超时卡在默认 120 秒。

**生图或嵌入请求报错**
用 `NON_TEXT_MODEL_PATTERN` 把这些模型排除在包装之外，走纯透传。

---

## License

[MIT](LICENSE)

---

## English

Helix Gateway is an anti-truncation gateway for the OpenAI Chat Completions protocol. Written in Go, it runs as a standalone server or ships as an Android app that embeds the same core via JNI.

It targets three failure modes that show up constantly in long-form generation: streams that end before `finish_reason`, `assistant`-prefill rejected by Gemini-style "must end with a user turn" checks, and markdown or JSON left structurally broken at the cut point.

It does **not** resume generation on the client's behalf. When upstream truncates, the gateway emits an explicit `event: error` frame instead of a fake `[DONE]`; client-side logic decides whether to continue.

Quick start:

```bash
export UPSTREAM_BASE_URL="https://api.openai.com/v1"
export UPSTREAM_API_KEY="sk-your-upstream-key"
export API_KEY="sk-helix-local"
export PORT=8080
go build -o helix-gateway ./cmd/gateway && ./helix-gateway
```

Point your client at `http://127.0.0.1:8080/v1` with `Authorization: Bearer sk-helix-local`.

Configuration reference, streaming semantics, deployment options, and troubleshooting are documented in Chinese above — the tables are the authoritative source for every environment variable.
