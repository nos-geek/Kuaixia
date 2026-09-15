# -*- coding: utf-8 -*-
"""开发服务器启动入口。

默认绑定 0.0.0.0:8000，方便 Android 手机通过 Wi-Fi 访问。
不要绑定 127.0.0.1，否则手机无法访问开发机的 localhost。

启动方式：
    python run.py
或：
    uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import uvicorn

if __name__ == "__main__":
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=False)
