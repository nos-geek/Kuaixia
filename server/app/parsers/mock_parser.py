# -*- coding: utf-8 -*-
"""MockParser：Phase 1 占位解析器。

用于验证 Android → Server → JSON → Android 全链路，
返回固定的示例媒体信息（标题 / 作者 / 平台 / 多条清晰度）。
"""
from __future__ import annotations

import asyncio
from urllib.parse import urlparse

from app.models import MediaParseResult, MediaStream
from app.parsers.base import PlatformParser


def _guess_platform(url: str) -> str:
    """根据域名粗略猜测平台，仅用于演示，不参与真实解析。"""
    host = (urlparse(url).hostname or "").lower()
    if "bilibili" in host:
        return "bilibili"
    if "douyin" in host:
        return "douyin"
    if "youtube" in host or "youtu.be" in host:
        return "youtube"
    return "mock"


class MockParser(PlatformParser):
    """返回固定示例数据，打通解析链路。"""

    name = "mock"

    def supports(self, url: str) -> bool:
        # Phase 1 兜底：任何合法 URL 都支持，保证链路可测。
        return bool(urlparse(url).scheme in ("http", "https") and urlparse(url).netloc)

    async def parse(self, url: str) -> MediaParseResult:
        # 模拟少量解析耗时，方便 Android 端看到 loading 状态
        await _noop_delay()
        platform = _guess_platform(url)
        return MediaParseResult(
            platform=platform,
            originalUrl=url,
            title="快夏测试视频（Mock）",
            author="快夏示例作者",
            thumbnail="https://example.com/thumb.jpg",
            duration=120,
            mediaType="VIDEO",
            streams=[
                MediaStream(
                    id="720",
                    url="https://cdn.example.com/video-720.mp4",
                    quality="720P",
                    width=1280,
                    height=720,
                    fps=30,
                    bitrate=2500,
                    fileSize=45_000_000,
                    mimeType="video/mp4",
                    codec="h264",
                    hasAudio=True,
                ),
                MediaStream(
                    id="1080",
                    url="https://cdn.example.com/video-1080.mp4",
                    quality="1080P",
                    width=1920,
                    height=1080,
                    fps=30,
                    bitrate=5000,
                    fileSize=123_456_789,
                    mimeType="video/mp4",
                    codec="h264",
                    hasAudio=True,
                ),
            ],
        )


async def _noop_delay() -> None:
    # 用 asyncio.sleep 而非 time.sleep，避免阻塞事件循环
    await asyncio.sleep(0.1)
