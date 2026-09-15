# Kuaixia Parser API Protocol

快夏（Kuaixia）解析服务器协议。任何实现本协议的服务器，都可以被快夏 Android 客户端直接使用，**无需修改 APK**。

版本：`v1`

---

## 1. 约定

- 基础路径：`/api/v1`
- 请求与响应均为 JSON，`Content-Type: application/json`
- 字段命名采用 **camelCase**
- 编码 UTF-8
- 支持 `http` 与 `https`；Android 端会拒绝 `file://`、`content://` 等本地协议

## 2. 健康检查

```http
GET /api/v1/health
```

响应 `200 OK`：

```json
{
    "status": "ok",
    "name": "Kuaixia Parser Server",
    "version": "1.0",
    "apiVersion": "v1"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| status | string | 固定 `ok` |
| name | string | 服务器名称，Android 设置页展示 |
| version | string | 服务器版本号 |
| apiVersion | string | 协议版本，固定 `v1` |

Android 设置页「测试连接」即调用此接口。成功显示「服务器连接正常」，失败显示「无法连接服务器」。

## 3. 解析

```http
POST /api/v1/parse
```

请求体：

```json
{
    "url": "https://example.com/video"
}
```

### 成功响应

`200 OK`，返回 `MediaParseResult`：

```json
{
    "platform": "bilibili",
    "originalUrl": "https://example.com/video",
    "title": "测试视频",
    "author": "作者",
    "thumbnail": "https://example.com/thumb.jpg",
    "duration": 120,
    "mediaType": "VIDEO",
    "streams": [
        {
            "id": "1080",
            "url": "https://cdn.example.com/video.mp4",
            "quality": "1080P",
            "width": 1920,
            "height": 1080,
            "fps": 30,
            "bitrate": 5000,
            "fileSize": 123456789,
            "mimeType": "video/mp4",
            "codec": "h264",
            "hasAudio": true,
            "downloadMode": "DIRECT",
            "headers": {}
        }
    ]
}
```

### 字段说明

**MediaParseResult**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| platform | string | ✅ | 平台标识，如 bilibili / douyin / youtube |
| originalUrl | string | ✅ | 原始链接 |
| title | string | ❌ | 标题 |
| author | string | ❌ | 作者 |
| thumbnail | string | ❌ | 封面图 URL |
| duration | long | ❌ | 时长（秒） |
| mediaType | string | ✅ | 媒体类型，如 VIDEO / AUDIO |
| streams | MediaStream[] | ✅ | 可下载流列表 |

**MediaStream**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | string | ✅ | 流标识 |
| url | string | ✅ | 媒体直链 |
| quality | string | ❌ | 清晰度标签，如 1080P |
| width / height | int | ❌ | 分辨率 |
| fps | int | ❌ | 帧率 |
| bitrate | long | ❌ | 码率 |
| fileSize | long | ❌ | 文件大小（字节） |
| mimeType | string | ❌ | MIME 类型 |
| codec | string | ❌ | 编码，如 h264 |
| hasAudio | bool | ❌ | 是否含音频 |
| downloadMode | string | ❌ | `DIRECT`（默认）或 `SERVER_PROXY` |
| headers | object | ❌ | 下载时需附加的请求头 |

### 错误响应

错误返回对应 HTTP 状态码 + 统一错误体：

```json
{
    "code": "INVALID_URL",
    "message": "链接无效，仅支持 http/https"
}
```

统一错误码：

| code | HTTP | 说明 |
|------|------|------|
| INVALID_URL | 400 | 链接无效 |
| UNSUPPORTED_PLATFORM | 422 | 暂不支持的平台 |
| NETWORK_ERROR | 502 | 网络错误 |
| TIMEOUT | 504 | 超时 |
| LOGIN_REQUIRED | 401 | 需要登录 |
| ACCESS_DENIED | 403 | 访问被拒绝 |
| MEDIA_NOT_FOUND | 404 | 未找到媒体资源 |
| SERVER_ERROR | 500 | 服务器内部错误 |
| PARSER_ERROR | 422 | 解析失败 |

Android 端根据 `code` 显示中文提示，不显示 StackTrace。

## 4. 下载模式语义

- `DIRECT`：Android 直接从 `url` 下载（默认）。
- `SERVER_PROXY`：仅当 Android 无法直连（403 / Referer 限制 / Header 限制 / 需要服务器合并等）时使用，由服务器代理下载。

**服务器默认只解析，不搬运视频。**

## 5. 扩展接口（预留，后续版本）

```http
GET  /api/v1/scripts/{platform}
GET  /api/v1/tasks/{taskId}
GET  /api/v1/tasks/{taskId}/file
```

---

## 6. 兼容性要求

任何服务器只要实现 `GET /api/v1/health` 和 `POST /api/v1/parse` 并按本协议返回，即可被快夏使用。

Android 客户端**不会**针对不同服务器写 `if serverA / if serverB` 分支逻辑。
