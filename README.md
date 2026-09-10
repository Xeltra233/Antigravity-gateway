# Antigravity Gateway for Android (防截断兼容网关 Android 移动端)

<p align="center">
  <img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="App Icon" />
</p>

---

## 📑 目录 (Table of Contents)

- [🇨🇳 中文说明](#-中文说明)
  - [项目简介](#-项目简介)
  - [🌟 核心特性](#-核心特性)
  - [📱 界面与功能使用](#-界面与功能使用)
  - [⚙️ 技术架构与安全性](#️-技术架构与安全性)
  - [🛠️ 本地编译与构建](#️-本地编译与构建)
- [🇬🇧 English Documentation](#-english-documentation)
  - [Overview](#overview)
  - [🌟 Key Features](#-key-features)
  - [📱 UI & Usage Guide](#-ui--usage-guide)
  - [⚙️ Technical Architecture & Security](#️-technical-architecture--security)
  - [🛠️ Build from Source](#️-build-from-source)
- [📄 License](#-license)

---

<a name="中文说明"></a>
## 🇨🇳 中文说明

### 📖 项目简介

**Antigravity Gateway for Android**（包名 `org.antigravity.gateway`）是专为 Android 平台定制的极简本地 OpenAI Chat Completions 防截断代理网关。

移动端通过 JNI 内嵌编译好的 Go 原生核心动态库（`libantigravity.so`），无需依赖任何云端中转或外部服务器，直接在 Android 手机/平板/车机/模拟器本地启动防截断网关，彻底解决 **SillyTavern 酒馆、Cherry Studio、NextChat** 等客户端在移动设备上游玩时的回答截断、Gemini/CPA 轮次 400 报错及未闭合 Markdown/JSON 代码块问题。

---

### 🌟 核心特性

1. **极简单屏交互**：
   - 专为移动端设计的单屏 Material Design 界面，无繁琐层级，所有关键信息与操作一屏触达。
2. **多供应商独立管理**：
   - 支持多套上游供应商配置自由切换与新建；
   - 支持通过卡片右侧 ✏️ 编辑图标或长按供应商名称直接重命名；
   - 具备最后一个供应商删除保护机制，防止误删配置。
3. **全局独立下游 Key**：
   - 首次启动自动生成高熵 `sk-agw-...` 密钥，独立于各个供应商；
   - 切换或修改供应商完全不影响下游客户端配置；
   - 支持重新生成并一键复制到剪贴板，支持明文/密文切换查看。
4. **系统级 Keystore 硬件加密**：
   - 所有上游 API Key 与全局下游 Key 均采用 **Android Keystore AES-GCM** 加密后持久化存储；
   - 绝不向外部存储、明文 SharedPreferences 或系统日志泄露密钥。
5. **固定冷门端口与局域网应用链接**：
   - 进程绑定监听冷门固定端口 `38472`（`HOST=0.0.0.0`）；
   - 界面自动识别并显示本机局域网 IP 与应用链接（`http://<IP>:38472/v1`），支持一键复制到酒馆或客户端中。
6. **前台服务后台保活 & 划走停机**：
   - 启动网关后自动挂载常驻前台服务通知，退至后台、息屏或切换 App 时代理服务不中断；
   - 在系统多任务卡片中划走应用或在界面点击「停止」时，网关优雅退出并释放端口占用。
7. **内置直连验证客户端**：
   - 界面内置「拉取模型」（`GET /v1/models`）与「消息测试」（`POST /v1/chat/completions`）功能；
   - 拉取成功后自动回填首个可用模型 ID，点击即可完成端到端收发连通性测试。
8. **全 CPU 架构 ABI 适配**：
   - 预编译打包全部 4 种 Android ABI：`arm64-v8a`、`x86_64`、`armeabi-v7a`、`x86`。

---

### 📱 界面与功能使用

#### 1. 配置供应商
- 点击「切换/新建」添加新供应商或切换已有供应商；
- 点击供应商名称右侧的 ✏️ 图标或长按名称可重命名当前供应商；
- 填入对应供应商的 **上游 URL** 与 **上游 Key**。

#### 2. 配置下游与启动
- 在「全局下游 Key」区域点击「生成」生成专属 Key，或点击「复制」将其填入客户端；
- 点击蓝色 **「启动」** 按钮，状态变为「停止」且通知栏显示前台保活服务，应用链接高亮显示；
- 复制界面展示的 **应用链接**（例如 `http://192.168.1.100:38472/v1`）填入客户端的 Base URL。

#### 3. 客户端接入示例 (SillyTavern / Cherry Studio)
- **API 类型**: `OpenAI / Chat Completions`
- **Base URL / 自定义端点**: `http://<手机IP>:38472/v1`（同设备运行填 `http://127.0.0.1:38472/v1`）
- **API Key**: 填入应用内复制的全局下游 Key（如 `sk-agw-...`）
- **模型**: 选择或输入上游原生模型名称（如 `gemini-3.5-flash-low`, `claude-sonnet-4-6` 等）

---

### ⚙️ 技术架构与安全性

```
┌────────────────────────────────────────────────────────┐
│                   Android Application                  │
│  ┌───────────────────────┐  ┌───────────────────────┐  │
│  │   UI (Material 3)     │  │  GatewayService       │  │
│  │   MainActivity / VM   │  │  Foreground KeepAlive │  │
│  └───────────┬───────────┘  └───────────┬───────────┘  │
│              │                          │              │
│  ┌───────────▼───────────┐              │              │
│  │   ConfigRepository    │              │              │
│  │   Keystore AES-GCM    │              │              │
│  └───────────────────────┘              │              │
│                                         │              │
│  ┌──────────────────────────────────────▼───────────┐  │
│  │ JNI Bridge (org.antigravity.gateway.bridge)      │  │
│  └──────────────────────┬───────────────────────────┘  │
└─────────────────────────┼──────────────────────────────┘
                          │ CGO / JNI
┌─────────────────────────▼──────────────────────────────┐
│  Go Native Core (libantigravity.so)                    │
│  • Synthetic Transport Tool Engine                     │
│  • Incremental SSE State Machine                       │
│  • Chat Turn Sanitizer & Bounded Repair                │
│  • Embedded SQLite (:memory:)                          │
│  • Listening on 0.0.0.0:38472                          │
└────────────────────────────────────────────────────────┘
```

- **数据安全**：所有配置采用系统级 Hardware-backed Keystore 提供的 AES-GCM-256 算法加密存储于应用内部隔离空间。
- **内存数据库**：Android 运行时将 Key 鉴权 SQLite 数据库配置为 `:memory:` 纯内存模式，杜绝文件系统持久化明文残留与权限异常。

---

### 🛠️ 本地编译与构建

#### 环境要求
- JDK 17+
- Android SDK (API 34, Build-Tools 34.0.0)
- NDK (26.3.11579264 或兼容版本)
- Go 1.24+ (如需重新交叉编译 JNI `.so` 库)

#### 构建步骤
```bash
# 1. 进入 Android 项目目录
cd android

# 2. 运行单元测试
./gradlew :app:testReleaseUnitTest

# 3. 构建 Release APK
./gradlew :app:assembleRelease

# 产物输出路径:
# android/app/build/outputs/apk/release/antigravity-gateway-v1.0.0.apk
```

---

#### 方案 C：从源码自行编译（需安装 Go 1.25+）
如果你希望自己修改代码并编译：

##### 1. 安装 Go 语言环境（如尚未安装）
- **Windows**: 打开 PowerShell 执行 `winget install GoLang.Go`，或从 [Go 官网下载安装包](https://go.dev/dl/)。
- **Ubuntu / Debian**: 
  ```bash
  sudo apt update && sudo apt install -y golang
  ```
- **macOS**: 
  ```bash
  brew install go
  ```

##### 2. 获取源码
```bash
git clone https://github.com/Xeltra233/Antigravity-gateway.git
cd Antigravity-gateway
```

##### 3. 执行编译命令
- **Linux / macOS 本地编译**:
  ```bash
  go build -trimpath -ldflags="-s -w" -o gateway ./cmd/gateway
  chmod +x gateway
  ```

- **Windows 本地编译 (PowerShell / CMD)**:
  ```bash
  go build -trimpath -ldflags="-s -w" -o gateway.exe ./cmd/gateway
  ```

- **查看版本信息**:
  ```bash
  ./gateway -v
  # 输出: Antigravity Gateway version 1.0.10 (commit: ..., built: ...)
  ```

- **跨平台交叉编译 (在一台机器上为其他系统编译)**:
  ```bash
  # 编译 Linux 64位服务器运行的二进制
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o gateway-linux-amd64 ./cmd/gateway

  # 编译 Windows 运行的 EXE 文件
  CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o gateway.exe ./cmd/gateway
  ```
---

### ⚙️ 配置详解与环境变量

网关采用**环境变量与 `.env` 配置文件优先（12-Factor App）**的设计，遵循以下优先级：
1. **系统/终端已存在的环境变量**（最高优先级，适合 CI/CD 与 Docker `-e` 注入）；
2. **`ENV_FILE` 变量指定路径**的配置文件；
3. **当前工作目录下的 `.env`**；
4. **可执行文件同级目录下的 `.env`**（特别保证 Windows 双击运行与 Windows 服务自启）；
5. **父级目录下的 `../.env`**。

> 💡 **自动剥离 UTF-8 BOM**：支持在 Windows 记事本中直接新建并保存 `.env`，网关会自动处理 BOM 头，绝不报错。

#### 环境变量完整参考表

| 环境变量 | 必需 | 默认值 | 详细说明 |
| :--- | :---: | :--- | :--- |
| **`UPSTREAM_BASE_URL`** | **是** | - | 上游 API 根链接（如 `https://api.openai.com` 或代理中转地址，末尾 `/v1` 会自动补齐/规范化） |
| **`UPSTREAM_API_KEY`** | 视模式 | - | 上游 Bearer API 密钥（当 `UPSTREAM_AUTH_MODE=bearer` 时必需） |
| `UPSTREAM_AUTH_MODE` | 否 | `bearer` | 上游鉴权模式：`bearer` 或 `none`（上游无需鉴权或本地代理时） |
| `UPSTREAM_TIMEOUT_MS` | 否 | `120000` | 上游请求单次超时时间（毫秒，默认 2 分钟） |
| `PORT` | 否 | `8080` | 网关本地监听端口 |
| `HOST` | 否 | `0.0.0.0` | 网关监听网络地址 |
| `API_KEY` | 视情况 | - | 下游客户端简易访问 Key（**推荐配置**）。与 `DOWNSTREAM_KEYS_JSON` 二选一，网关 `/v1/*` 接口强制要求有效 Bearer Key。 |
| `ENV_FILE` | 否 | - | 自定义指定加载的 `.env` 配置文件路径 |
| `WRAPPER_MODE` | 否 | `prefer` | 包装模式：`prefer`（推荐，自适应注入合成协议）、`required`（强制要求）、`off`（紧急透明透传回滚） |
| `RECOVERY_POLICY` | 否 | `repair` | 格式恢复策略：`repair`（本地确定性修复）、`repair_then_retry`（单次安全重试）、`fail`（直接报错） |
| `UPSTREAM_EMPTY_RETRIES` | 否 | `3` | 上游空回（完全没有任何输出）时的自动重试次数 |
| `CONTROL_MESSAGE_ROLE` | 否 | `system` | 控制提示词角色：`system` 或 `developer` |
| `CONTROL_MESSAGE_POSITION`| 否 | `tail` | 控制提示词注入位置：`tail`（末尾强生效）、`head`、`system_tail` |
| `SYNTHETIC_TOOL_PREFIX` | 否 | `agw_emit_` | 动态合成工具的前缀（用于生成 96-bit 随机工具名） |
| `SYNTHETIC_TOOL_STRICT` | 否 | `false` | 是否向模型发送严格（Strict）Tool Schema 约束 |
| `TEXT_MODEL_PATTERN` | 否 | - | 可选正则：自定义判定为文本对话模型并启用防截断保护的匹配规则 |
| `NON_TEXT_MODEL_PATTERN` | 否 | - | 可选正则：自定义判定为生图/语音/嵌入模型并跳过合成包装直接透传的匹配规则 |
| `MAX_REQUEST_BYTES` | 否 | `16777216` | 最大允许请求大小（字节，默认 16MB） |
| `MAX_RESPONSE_BYTES` | 否 | `16777216` | 最大允许响应大小（字节，默认 16MB） |
| `MAX_CONCURRENT_REQUESTS` | 否 | `1024` | 全局最大并发请求数 |
| `MAX_CONCURRENT_REQUESTS_PER_KEY` | 否 | `64` | 单个下游 API Key 最大并发请求数 |
| `REQUEST_QUEUE_TIMEOUT_MS`| 否 | `50` | 高并发下请求排队最大等待超时（毫秒） |
| `STREAM_SIDE_BUFFER_BYTES`| 否 | `0` | 流式 Side Buffer 大小（默认 `0` 即极速直发，零额外延迟） |
| `STREAM_REPAIR_BUFFER_BYTES`| 否 | `1048576` | 流式格式修复缓冲区大小（默认 1MB） |
| `STREAM_FLUSH_INTERVAL_MS`| 否 | `0` | 流式输出刷新间隔（毫秒，默认 0 即刻刷新） |
| `ADMIN_API_KEY` | 否 | `admin-secret-key-12345` | 多 Key 动态管理接口鉴权 Key（用于 `/admin/keys`，自定义时至少 8 字符） |
| `KEY_HMAC_SECRET` | 否 | 内置默认密钥 | 下游 Key 的 HMAC-SHA256 安全签名密钥（自定义时至少 16 字符） |
| `KEY_DB_PATH` | 否 | `./data/keys.sqlite` | 动态 Key 持久化存储 SQLite 数据库路径 |
| `DOWNSTREAM_KEYS_JSON` | 否 | `[]` | 静态下游分发 Key 配置（JSON 数组，纯环境变量配置时使用） |
| `SHUTDOWN_TIMEOUT_MS` | 否 | `30000` | 优雅关机超时时长（毫秒，默认 30 秒） |
| `MODELS_CACHE_TTL_MS` | 否 | `30000` | `/v1/models` 上游模型列表缓存有效期（毫秒） |
| `LOG_LEVEL` | 否 | `info` | 日志级别：`debug`、`info`、`warn`、`error` |
| `TRUST_PROXY` | 否 | `false` | 是否信任反向代理传递的 `X-Forwarded-For` 真实客户端 IP |
| `UPSTREAM_MAX_IDLE_CONNS` | 否 | `2048` | 上游 HTTP 连接池最大空闲连接数 |
| `UPSTREAM_MAX_IDLE_CONNS_PER_HOST` | 否 | `512` | 每个上游 Host 最大空闲连接数 |
| `UPSTREAM_MAX_CONNS_PER_HOST` | 否 | `512` | 每个上游 Host 最大并发连接数 |

#### `.env` 配置文件示例 (直接复制项目中的 `.env.example` 修改)
```env
# 必需：上游根地址与密钥
UPSTREAM_BASE_URL=https://api.openai.com
UPSTREAM_API_KEY=sk-your-upstream-key-here

# [推荐] 下游客户端连接网关所使用的简易访问 Key (酒馆/客户端填写此 Key)
API_KEY=sk-antigravity-123456
PORT=8080
HOST=0.0.0.0

# 防截断策略配置
WRAPPER_MODE=prefer
RECOVERY_POLICY=repair
UPSTREAM_EMPTY_RETRIES=3

# 日志级别
LOG_LEVEL=info
```

---

### 🚀 部署与运行方式

#### 方式 1：Windows 一键批处理 (`run.bat`)
在发布包中已内置智能优化的 `run.bat`，直接双击运行即可：
- 自动检测同级目录的 `.env` 文件；
- 若无 `.env` 且无环境变量，会自动从 `.env.example` 复制生成 `.env` 并友好引导配置；
- 若运行异常退出，会保留窗口并提示具体排查步骤（如检查端口占用、Key 是否填写等）。

#### 方式 2：Linux / macOS 命令行直接启动
将 `.env.example` 复制为 `.env` 并填写好配置，直接运行：
```bash
cp .env.example .env
# 编辑配置
nano .env

# 启动运行
./gateway
```
或直接通过命令行环境变量覆盖启动：
```bash
export UPSTREAM_BASE_URL="https://your-upstream-domain.com"
export UPSTREAM_API_KEY="sk-your-upstream-key"
export API_KEY="sk-antigravity-123456"
export PORT="8080"

./gateway
```

#### 方式 3：Linux Systemd 守护进程部署
创建 systemd 服务文件 `/etc/systemd/system/antigravity-gateway.service`：
```ini
[Unit]
Description=Antigravity Gateway Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/antigravity-gateway
ExecStart=/opt/antigravity-gateway/gateway
Restart=always
RestartSec=5
# 既可使用 Environment 指定，也可直接在 WorkingDirectory 下放置 .env 文件
Environment=UPSTREAM_BASE_URL=https://your-upstream-domain.com
Environment=UPSTREAM_API_KEY=sk-your-upstream-key
Environment=API_KEY=sk-antigravity-123456
Environment=PORT=8080
Environment=HOST=0.0.0.0

[Install]
WantedBy=multi-user.target
```
启动并开机自启：
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now antigravity-gateway
sudo systemctl status antigravity-gateway
```

#### 方式 4：Docker / Docker Compose 部署
使用 `docker run` 快速启动：
```bash
docker run -d \
  --name antigravity-gateway \
  -p 8080:8080 \
  -e UPSTREAM_BASE_URL="https://your-upstream-domain.com" \
  -e UPSTREAM_API_KEY="sk-your-upstream-key" \
  -e API_KEY="sk-antigravity-123456" \
  --restart unless-stopped \
  antigravity-gateway:latest
```

或使用 `docker-compose.yml`：
```yaml
version: '3.8'

services:
  gateway:
    build: .
    container_name: antigravity-gateway
    ports:
      - "8080:8080"
    environment:
      - UPSTREAM_BASE_URL=https://your-upstream-domain.com
      - UPSTREAM_API_KEY=sk-your-upstream-key
      - API_KEY=sk-antigravity-123456
      - PORT=8080
    restart: unless-stopped
```

---

### 🔑 多用户动态 Key 管理 API

网关内置了多用户 API Key 管理引擎（支持 SQLite 存储与不可变内存快速鉴权），管理员可以通过 `/admin/keys` 接口进行自动化签发与吊销。

#### 1. 创建新下游 Key
```bash
curl -X POST http://127.0.0.1:8080/admin/keys \
  -H "Authorization: Bearer admin-secret-key-12345" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "User-Alice",
    "allowed_models": ["gemini-3.5-flash-low", "gpt-4o"]
  }'
```
响应示例：
```json
{
  "id": "key_7f8a91b2c3d4",
  "key": "sk-agw-live-a1b2c3d4e5f6...",
  "name": "User-Alice",
  "allowed_models": ["gemini-3.5-flash-low", "gpt-4o"],
  "status": "active",
  "created_at": "2026-09-03T01:00:00Z"
}
```

#### 2. 列出所有 Key
```bash
curl -s http://127.0.0.1:8080/admin/keys \
  -H "Authorization: Bearer admin-secret-key-12345"
```

#### 3. 吊销特定 Key
```bash
curl -X POST http://127.0.0.1:8080/admin/keys/key_7f8a91b2c3d4/revoke \
  -H "Authorization: Bearer admin-secret-key-12345"
```

---

### 📱 客户端接入实战

网关完全兼容标准 OpenAI `/v1` 协议端点。

#### 1. 通用连接参数
- **API 接口地址 (Base URL)**: `http://127.0.0.1:8080/v1`（局域网/公网部署请替换为对应 IP 或域名）
- **API 密钥 (API Key)**: 填入网关中配置的 `API_KEY` 或 `DOWNSTREAM_KEYS_JSON` 中的有效 Key；网关的 `/v1/*` 接口始终要求 Bearer Key，未配置任何下游 Key 时不会变成免认证模式。
- **模型名称**: 填入上游支持的原生模型（如 `gemini-3.5-flash-low`, `gemini-3.7-flash-high`, `claude-sonnet-4-6` 等）

#### 2. SillyTavern (酒馆) 设置
1. 打开酒馆 API 设置面板，选择 **API**: `Chat Completion (OpenAI Compatible)`；
2. **Custom Endpoint (自定义端点)**: `http://127.0.0.1:8080/v1`；
3. **API Key**: 输入你的 `API_KEY`；
4. 点击 **Connect (连接)**，在下拉列表中选择你的目标模型即可畅快游玩，彻底告别回答截断与轮次 400 报错。

#### 3. Cherry Studio / NextChat / Chatbox 设置
- **提供商类型**: OpenAI / OpenAI 兼容
- **API 地址**: `http://127.0.0.1:8080/v1`
- **API Key**: `sk-antigravity-123456`

#### 4. cURL 快速测试验证
- **测试模型列表获取**:
  ```bash
  curl -s -H "Authorization: Bearer sk-antigravity-123456" http://127.0.0.1:8080/v1/models
  ```
- **测试对话流式输出 (SSE)**:
  ```bash
  curl http://127.0.0.1:8080/v1/chat/completions \
    -H "Authorization: Bearer sk-antigravity-123456" \
    -H "Content-Type: application/json" \
    -d '{
      "model": "gemini-3.5-flash-low",
      "messages": [{"role": "user", "content": "你好，请写一段500字的故事。"}],
      "stream": true
    }'
  ```

---

### 🛡️ 运维监控与健康检查

- **存活探针 (Liveness)**: `GET /healthz` → 返回 `{"status":"ok","version":"1.0.10"}` (200 OK)
- **就绪探针 (Readiness)**: `GET /readyz` → 返回 `{"status":"ready","version":"1.0.10"}` (200 OK)
- **Prometheus 监控指标**: `GET /metrics` → 输出请求总量、活跃请求数、过载拒绝、合成包装命中/修复/重试/冲突等指标。
- **紧急回滚**: 若遇到突发未知上游格式异常，修改 `.env` 中的 `WRAPPER_MODE=off` 并重启网关，即可切换为原生纯透传模式。

---

### ❓ 常见问题排查 (FAQ)

#### Q1: 为什么会出现 400 "Requests ending with a model turn are not supported"?
**A**: 这是 Google Gemini / CPA 上游的特定限制，原生接口要求对话内容必须以 `user` 角色结尾。网关已内置智能轮次闭环机制，会自动将尾部悬空的 Assistant 预填或系统提示词自适应修正为 User 轮次，避免触发上游报错。

#### Q2: 遇到模型拒答（如“抱歉，我无法生成该内容”）是网关问题吗？
**A**: 不是。模型拒答分为两种：
1. **外审拦截**（返回 `finish_reason: "SAFETY"` 或 HTTP 400）；
2. **内审拒答**（模型本身回复道歉文本，返回 `role: "assistant"`，`status: 200`）。
如果是内审拒答，说明提示词命中了模型的关键词红线，建议调整人物设定或选用对齐规则更宽松/带有 Thinking 链的模型。

#### Q3: 网关支持非文本模型（如生图/声音）吗？
**A**: 完全支持。网关内置了智能正则过滤器，检测到生图、图像分析、音频等模型时会自动跳过合成包装，执行 100% 原生透传。

#### Q4: 为什么我在 `.env` 文件里修改了配置却不生效？
**A**: 网关自 `v1.0.7` 起已原生支持 `.env` 文件解析。请确认：
1. 操作系统环境变量中是否已设置了同名变量（系统环境变量优先级高于 `.env` 文件）；
2. 启动时注意观察首行日志 `loaded configuration from .env file (path: ...)`，查看网关实际加载的 `.env` 文件绝对路径；
3. 如果使用 Windows，推荐直接使用包内自带的 `run.bat`，它会自动保障读取脚本同级目录下的 `.env`。

---

>>>>>>> main
<a name="english"></a>
## 🇬🇧 English Documentation

### Overview

**Antigravity Gateway for Android** (`org.antigravity.gateway`) is a standalone, lightweight Android application delivering an embedded OpenAI Chat Completions anti-truncation proxy gateway.

By embedding the high-performance Go native core (`libantigravity.so`) via JNI, the app acts as a local proxy on your Android phone, tablet, emulator, or head unit. It completely resolves truncation, missing code block enclosures, and Gemini/CPA trailing turn 400 errors for clients such as **SillyTavern, Cherry Studio, and NextChat**.

---

### 🌟 Key Features

1. **Minimal Single-Screen UI**: Clean Material interface designed specifically for mobile devices.
2. **Multi-Provider Switcher & Rename**: Add, switch, and rename providers (via ✏️ icon or long-press) with deletion safety for the default provider.
3. **Global Downstream Key**: Auto-generated high-entropy `sk-agw-...` key independent of upstream provider selections.
4. **Android Keystore AES-GCM Encryption**: Hardware-backed credential encryption preventing sensitive API keys from leaking into logs or storage.
5. **Fixed Cold Port & LAN Detection**: Fixed cold port `38472` with auto-detected LAN link (`http://<LAN-IP>:38472/v1`).
6. **Foreground Service Keep-Alive**: Persistent notification keeps the proxy running in the background; swiping the app away from recent tasks automatically shuts down the gateway and frees the port.
7. **Built-in Verification Client**: In-app `GET /v1/models` and `POST /v1/chat/completions` testing with auto-fill for the first available model.
8. **Multi-ABI Support**: Packaged with native libraries for `arm64-v8a`, `x86_64`, `armeabi-v7a`, and `x86`.

---

### 📱 UI & Usage Guide

1. **Configure Provider**: Select or add a provider, rename if needed, and enter the upstream URL and Key.
2. **Downstream Key**: Generate or copy the global downstream key.
3. **Start Gateway**: Tap **Start**, then copy the displayed App URL (`http://<IP>:38472/v1`).
4. **Client Setup**: Configure your client (e.g. SillyTavern) with the App URL as Base URL, the Downstream Key as API Key, and your chosen model name.

---

### ⚙️ Technical Architecture & Security

- **Native JNI Engine**: Core routing, synthetic transport protocol, and streaming state machine execute natively via `libantigravity.so`.
- **In-Memory SQLite**: The Android bridge initializes SQLite with `:memory:` to ensure no plaintext key residue exists on disk.
- **Keystore Encryption**: All credentials stored in `ConfigRepository` are encrypted with AES-256-GCM using keys generated inside the Android Keystore.

---

### 🛠️ Build from Source

```bash
cd android
./gradlew :app:testReleaseUnitTest
./gradlew :app:assembleRelease
# Output APK: android/app/build/outputs/apk/release/antigravity-gateway-v1.0.0.apk
```

---

#### Option C: Build from Source (Requires Go 1.25+)

##### 1. Install Go (if not installed)
- **Windows**: In PowerShell run `winget install GoLang.Go` or download from [Go Downloads](https://go.dev/dl/).
- **Ubuntu / Debian**: `sudo apt update && sudo apt install -y golang`
- **macOS**: `brew install go`

##### 2. Clone & Compile
```bash
git clone https://github.com/Xeltra233/Antigravity-gateway.git
cd Antigravity-gateway

# Native compilation
go build -trimpath -ldflags="-s -w" -o gateway ./cmd/gateway

# Print version
./gateway -v
```

##### 3. Cross-Compilation
```bash
# Target Linux AMD64 from Windows/macOS
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o gateway-linux-amd64 ./cmd/gateway

# Target Windows from Linux/macOS
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o gateway.exe ./cmd/gateway
```

---

### ⚙️ Configuration & Environment Variables

The gateway follows 12-Factor App design principles and reads configuration following this precedence hierarchy:
1. **Operating System / Shell Environment Variables** (highest priority);
2. **File specified via `ENV_FILE`** environment variable;
3. **`.env` in current working directory**;
4. **`.env` in executable directory** (essential for Windows double-clicking or service auto-start);
5. **`../.env` in parent directory**.

#### Complete Environment Variables Reference

| Variable | Required | Default | Description |
| :--- | :---: | :--- | :--- |
| **`UPSTREAM_BASE_URL`** | **Yes** | - | Upstream API base URL (e.g., `https://api.openai.com` or custom reverse proxy, `/v1` normalized automatically) |
| **`UPSTREAM_API_KEY`** | Conditional | - | Upstream Bearer API key (required when `UPSTREAM_AUTH_MODE=bearer`) |
| `UPSTREAM_AUTH_MODE` | No | `bearer` | Upstream authentication mode: `bearer` or `none` |
| `UPSTREAM_TIMEOUT_MS` | No | `120000` | Upstream request timeout in milliseconds (default: 2 minutes) |
| `PORT` | No | `8080` | Gateway listening port |
| `HOST` | No | `0.0.0.0` | Gateway listening host interface |
| `API_KEY` | Conditional | - | Downstream client access key (**Recommended**). Provide either this or keys in `DOWNSTREAM_KEYS_JSON`; `/v1/*` strictly requires a valid Bearer key. |
| `ENV_FILE` | No | - | Custom path to load `.env` configuration file from |
| `WRAPPER_MODE` | No | `prefer` | Wrapper mode: `prefer` (recommended, injects synthetic protocol), `required` (strict), `off` (emergency passthrough) |
| `RECOVERY_POLICY` | No | `repair` | Recovery policy: `repair` (local deterministic fix), `repair_then_retry` (retry on failure), `fail` (error out) |
| `UPSTREAM_EMPTY_RETRIES` | No | `3` | Number of automatic retries upon receiving empty responses from upstream |
| `CONTROL_MESSAGE_ROLE` | No | `system` | Injected control message role: `system` or `developer` |
| `CONTROL_MESSAGE_POSITION`| No | `tail` | Injected message position: `tail` (recommended for strong adherence), `head`, `system_tail` |
| `SYNTHETIC_TOOL_PREFIX` | No | `agw_emit_` | Prefix for generated 96-bit synthetic tool names |
| `SYNTHETIC_TOOL_STRICT` | No | `false` | Whether to send strict schema validation constraint in tool definition |
| `TEXT_MODEL_PATTERN` | No | - | Optional regex override to classify models as text/chat models |
| `NON_TEXT_MODEL_PATTERN` | No | - | Optional regex override to classify models as non-text (vision/audio/embedding) to skip wrapper |
| `MAX_REQUEST_BYTES` | No | `16777216` | Maximum allowed request size in bytes (default: 16MB) |
| `MAX_RESPONSE_BYTES` | No | `16777216` | Maximum allowed response size in bytes (default: 16MB) |
| `MAX_CONCURRENT_REQUESTS` | No | `1024` | Global maximum concurrent requests |
| `MAX_CONCURRENT_REQUESTS_PER_KEY` | No | `64` | Maximum concurrent requests per API key |
| `REQUEST_QUEUE_TIMEOUT_MS`| No | `50` | Maximum queue wait time before timeout (milliseconds) |
| `STREAM_SIDE_BUFFER_BYTES`| No | `0` | Streaming side buffer size (default: 0 for instant emission) |
| `STREAM_REPAIR_BUFFER_BYTES`| No | `1048576` | Streaming repair buffer size (default: 1MB) |
| `STREAM_FLUSH_INTERVAL_MS`| No | `0` | Streaming flush interval in milliseconds |
| `ADMIN_API_KEY` | No | `admin-secret-key-12345` | Authentication key for dynamic key management endpoint (`/admin/keys`, min 8 chars) |
| `KEY_HMAC_SECRET` | No | Built-in secret | HMAC-SHA256 signature secret for downstream keys (min 16 chars) |
| `KEY_DB_PATH` | No | `./data/keys.sqlite` | SQLite database path for persistent downstream keys |
| `DOWNSTREAM_KEYS_JSON` | No | `[]` | Static downstream keys array in JSON format |
| `SHUTDOWN_TIMEOUT_MS` | No | `30000` | Graceful shutdown timeout in milliseconds |
| `MODELS_CACHE_TTL_MS` | No | `30000` | Upstream `/v1/models` cache TTL in milliseconds |
| `LOG_LEVEL` | No | `info` | Logging verbosity level: `debug`, `info`, `warn`, `error` |
| `TRUST_PROXY` | No | `false` | Trust `X-Forwarded-For` header from reverse proxies |
| `UPSTREAM_MAX_IDLE_CONNS` | No | `2048` | Maximum idle connections in HTTP transport pool |
| `UPSTREAM_MAX_IDLE_CONNS_PER_HOST` | No | `512` | Maximum idle connections per upstream host |
| `UPSTREAM_MAX_CONNS_PER_HOST` | No | `512` | Maximum total connections per upstream host |

#### `.env` File Example
```env
# Required: Upstream URL and Key
UPSTREAM_BASE_URL=https://api.openai.com
UPSTREAM_API_KEY=sk-your-upstream-key-here

# Recommended: Downstream client access key (Input this into SillyTavern / client)
API_KEY=sk-antigravity-123456
PORT=8080
HOST=0.0.0.0

# Anti-truncation strategy
WRAPPER_MODE=prefer
RECOVERY_POLICY=repair
UPSTREAM_EMPTY_RETRIES=3

# Logging
LOG_LEVEL=info
```

---

### 🚀 Deployment Methods

#### Method 1: Windows Batch Script (`run.bat`)
Double-click `run.bat` included in the release package:
- Automatically loads configuration from `.env` in the same directory;
- If `.env` is absent and no environment variables are detected, automatically copies `.env.example` to `.env` and guides setup;
- Pauses with troubleshooting guidance upon any exit error.

#### Method 2: Linux / macOS CLI
```bash
cp .env.example .env
nano .env

./gateway
```
Or start directly via exported environment variables:
```bash
export UPSTREAM_BASE_URL="https://your-upstream-domain.com"
export UPSTREAM_API_KEY="sk-your-upstream-key"
export API_KEY="sk-antigravity-123456"
export PORT=8080

./gateway
```

#### Method 3: Linux Systemd Service
Create `/etc/systemd/system/antigravity-gateway.service`:
```ini
[Unit]
Description=Antigravity Gateway Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/antigravity-gateway
ExecStart=/opt/antigravity-gateway/gateway
Restart=always
RestartSec=5
Environment=UPSTREAM_BASE_URL=https://your-upstream-domain.com
Environment=UPSTREAM_API_KEY=sk-your-upstream-key
Environment=API_KEY=sk-antigravity-123456
Environment=PORT=8080
Environment=HOST=0.0.0.0

[Install]
WantedBy=multi-user.target
```
Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now antigravity-gateway
sudo systemctl status antigravity-gateway
```

#### Method 4: Docker / Docker Compose
Run via Docker CLI:
```bash
docker run -d \
  --name antigravity-gateway \
  -p 8080:8080 \
  -e UPSTREAM_BASE_URL="https://your-upstream-domain.com" \
  -e UPSTREAM_API_KEY="sk-your-upstream-key" \
  -e API_KEY="sk-antigravity-123456" \
  --restart unless-stopped \
  antigravity-gateway:latest
```

Or via `docker-compose.yml`:
```yaml
version: '3.8'

services:
  gateway:
    build: .
    container_name: antigravity-gateway
    ports:
      - "8080:8080"
    environment:
      - UPSTREAM_BASE_URL=https://your-upstream-domain.com
      - UPSTREAM_API_KEY=sk-your-upstream-key
      - API_KEY=sk-antigravity-123456
      - PORT=8080
    restart: unless-stopped
```

---

### 🔑 Dynamic Multi-Key Management API

The gateway includes an integrated SQLite-backed key management system. Administrators can manage downstream keys dynamically via `/admin/keys`.

#### 1. Create Downstream Key
```bash
curl -X POST http://127.0.0.1:8080/admin/keys \
  -H "Authorization: Bearer admin-secret-key-12345" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "User-Alice",
    "allowed_models": ["gemini-3.5-flash-low", "gpt-4o"]
  }'
```

#### 2. List All Keys
```bash
curl -s http://127.0.0.1:8080/admin/keys \
  -H "Authorization: Bearer admin-secret-key-12345"
```

#### 3. Revoke Key
```bash
curl -X POST http://127.0.0.1:8080/admin/keys/key_7f8a91b2c3d4/revoke \
  -H "Authorization: Bearer admin-secret-key-12345"
```

---

### 📱 Client Integration

The gateway is 100% compliant with standard OpenAI `/v1` endpoints.

#### 1. General Settings
- **Base URL**: `http://127.0.0.1:8080/v1`
- **API Key**: Configured `API_KEY` or a key from `DOWNSTREAM_KEYS_JSON`; `/v1/*` always requires a valid Bearer key
- **Model**: Original upstream model name (e.g. `gemini-3.5-flash-low`, `claude-sonnet-4-6`)

#### 2. SillyTavern Setup
1. Open API settings, set **API**: `Chat Completion (OpenAI Compatible)`.
2. **Custom Endpoint**: `http://127.0.0.1:8080/v1`.
3. **API Key**: Input your `API_KEY`.
4. Click **Connect**, select model, and enjoy truncation-free roleplaying!

#### 3. Cherry Studio / NextChat / Chatbox
- **Provider**: OpenAI / OpenAI Compatible
- **API Host**: `http://127.0.0.1:8080/v1`
- **API Key**: `sk-antigravity-123456`

#### 4. cURL Verification
- **Fetch Models**:
  ```bash
  curl -s -H "Authorization: Bearer sk-antigravity-123456" http://127.0.0.1:8080/v1/models
  ```
- **Test Streaming Chat Completion**:
  ```bash
  curl http://127.0.0.1:8080/v1/chat/completions \
    -H "Authorization: Bearer sk-antigravity-123456" \
    -H "Content-Type: application/json" \
    -d '{
      "model": "gemini-3.5-flash-low",
      "messages": [{"role": "user", "content": "Write a 500-word short story."}],
      "stream": true
    }'
  ```

---

### 🛡️ Health Checks & Observability

- **Liveness Probe**: `GET /healthz` → returns `{"status":"ok","version":"1.0.10"}` (200 OK)
- **Readiness Probe**: `GET /readyz` → returns `{"status":"ready","version":"1.0.10"}` (200 OK)
- **Prometheus Metrics**: `GET /metrics` → exports standard metrics including request totals, latencies, active connections, and synthetic wrapper counts.
- **Emergency Fallback**: Set `WRAPPER_MODE=off` in `.env` and restart the gateway to revert to transparent raw passthrough.

---

### ❓ Frequently Asked Questions (FAQ)

#### Q1: Why did I receive 400 "Requests ending with a model turn are not supported"?
**A**: This is a strict constraint enforced by Google Gemini / CPA upstream requiring conversations to end on a `user` turn. The gateway automatically detects dangling Assistant pre-fills and system messages, sanitizing trailing turns into compliant user turns to eliminate this error.

#### Q2: Is model refusal (e.g., "I cannot fulfill this request") caused by the gateway?
**A**: No. Refusal occurs either as:
1. **External Filter**: Guardrails returning `finish_reason: "SAFETY"` or HTTP 400.
2. **Internal Refusal**: The model itself generating an apology text (`role: "assistant"`, status 200).
Internal refusal indicates sensitive keywords in the prompt card. Adjust your character card or switch to models with reasoning/thinking enabled.

#### Q3: Does the gateway support non-text models (e.g., image generation, audio)?
**A**: Yes. The gateway includes an automatic classifier that skips synthetic wrapping for non-text models (DALL-E, Flux, Whisper, Embeddings) and performs 100% raw passthrough.

#### Q4: Why are my changes in `.env` not taking effect?
**A**: Starting from `v1.0.7`, native `.env` loading is fully supported. Check:
1. Whether an environment variable with the same name exists in your system (system environment variables take precedence over `.env`);
2. Check the first log output on startup `loaded configuration from .env file (path: ...)`;
3. On Windows, use `run.bat` which guarantees loading `.env` from the script directory.

---

>>>>>>> main
<a name="license"></a>
## 📄 License

This project is licensed under the [MIT License](LICENSE).
Copyright (c) 2026 Xeltra233.
