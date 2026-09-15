# -*- coding: utf-8 -*-
"""核心数据模型。

字段名与 Android 端 Kotlin @Serializable 模型保持一致，
所有兼容 Kuaixia Parser API 的服务器都必须返回同样的 JSON 结构。
"""
from __future__ import annotations

from enum import Enum
from typing import Dict, List, Optional

from pydantic import BaseModel, Field


class DownloadMode(str, Enum):
    """下载模式。默认 DIRECT，只有 Android 无法直连时才用 SERVER_PROXY。"""

    DIRECT = "DIRECT"
    SERVER_PROXY = "SERVER_PROXY"


class MediaStream(BaseModel):
    """单条可下载的媒体流（清晰度）。"""

    id: str
    url: str
    quality: Optional[str] = None
    width: Optional[int] = None
    height: Optional[int] = None
    fps: Optional[int] = None
    bitrate: Optional[int] = None
    fileSize: Optional[int] = None
    mimeType: Optional[str] = None
    codec: Optional[str] = None
    hasAudio: bool = False
    downloadMode: DownloadMode = DownloadMode.DIRECT
    headers: Dict[str, str] = Field(default_factory=dict)


class MediaParseResult(BaseModel):
    """解析结果。"""

    platform: str
    originalUrl: str
    title: Optional[str] = None
    author: Optional[str] = None
    thumbnail: Optional[str] = None
    duration: Optional[int] = None
    mediaType: str
    streams: List[MediaStream] = Field(default_factory=list)


class HealthResponse(BaseModel):
    """健康检查响应。"""

    status: str = "ok"
    name: str = "Kuaixia Parser Server"
    version: str = "1.0"
    apiVersion: str = "v1"


class ParseRequest(BaseModel):
    """解析请求体。"""

    url: str


class ErrorResponse(BaseModel):
    """统一错误响应。

    code 取值（与 Android 端一致）：
    INVALID_URL / UNSUPPORTED_PLATFORM / NETWORK_ERROR / TIMEOUT /
    LOGIN_REQUIRED / ACCESS_DENIED / MEDIA_NOT_FOUND /
    SERVER_ERROR / PARSER_ERROR
    """

    code: str
    message: str
