# -*- coding: utf-8 -*-
"""平台解析器抽象基类。

与 Android 端 PlatformParser 接口对齐。
"""
from __future__ import annotations

from abc import ABC, abstractmethod

from app.models import MediaParseResult


class PlatformParser(ABC):
    """所有平台解析器的基类。"""

    #: 平台标识，如 mock / bilibili / douyin / youtube
    name: str = "base"

    @abstractmethod
    def supports(self, url: str) -> bool:
        """判断该解析器是否支持给定 URL。"""

    @abstractmethod
    async def parse(self, url: str) -> MediaParseResult:
        """解析 URL，返回标准 MediaParseResult。"""
