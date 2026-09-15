# 快夏 Kuaixia Parser Server

解析服务器，实现 **Kuaixia Parser API Protocol**。任何实现该协议的客户端（包括快夏 Android）都可以直接使用本服务器，无需修改 APK。

## 技术栈

- Python 3.12+
- FastAPI
- Pydantic v2
- httpx

## 当前提供的能力

- `GET /api/v1/health` — 健康检查
- `POST /api/v1/parse` — 解析入口（默认由示例解析器返回示例数据，用于协议联调）

> 服务器默认**只做解析**，不代理媒体流量。接入真实平台解析器时，实现
> `app/parsers/base.py` 的 `PlatformParser` 并在 `registry.py` 注册即可，客户端无需改动。

## 启动

```bash
# 建议使用虚拟环境
python -m venv .venv
# Windows: .venv\Scripts\activate   Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt

# 启动（0.0.0.0 便于手机通过 Wi-Fi 访问）
python run.py
```

默认监听 `0.0.0.0:8000`。手机访问示例：`http://<服务器IP>:8000/api/v1/health`

## 接口

### Health

```bash
curl http://127.0.0.1:8000/api/v1/health
```

返回：

```json
{"status":"ok","name":"Kuaixia Parser Server","version":"1.0","apiVersion":"v1"}
```

### Parse

```bash
curl -X POST http://127.0.0.1:8000/api/v1/parse \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/video"}'
```

## 目录结构

```text
server/
├── run.py                 # 启动入口
├── requirements.txt
└── app/
    ├── main.py            # FastAPI 路由
    ├── config.py          # 服务元信息
    ├── models.py          # Pydantic 模型（与 Android 字段一致）
    ├── errors.py          # 统一错误码
    ├── logging_config.py  # 日志（脱敏）
    └── parsers/
        ├── base.py        # PlatformParser 抽象基类
        ├── mock_parser.py # 示例解析器（返回示例数据，供协议联调）
        └── registry.py    # ParserRegistry 解析顺序
```
