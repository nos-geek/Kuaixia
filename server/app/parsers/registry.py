# -*- coding: utf-8 -*-
"""ParserRegistry：统一解析入口。

解析顺序（后续阶段扩展）：
    URL → YtDlpParser → Native Parser → WebView / Remote JS

Phase 1 只有 MockParser，作为兜底解析器。
"""
from __future__ import annotations

import time
from typing import List, Optional

from app.errors import ErrorCode, ParserException
from app.logging_config import get_logger
from app.models import MediaParseResult
from app.parsers.base import PlatformParser
from app.parsers.mock_parser import MockParser

log = get_logger("registry")


class ParserRegistry:
    """按顺序尝试所有注册的解析器，返回第一个成功的结果。"""

    def __init__(self, parsers: Optional[List[PlatformParser]] = None):
        self._parsers = parsers or [MockParser()]

    def register(self, parser: PlatformParser) -> None:
        self._parsers.append(parser)

    async def parse(self, url: str) -> MediaParseResult:
        started = time.monotonic()
        for parser in self._parsers:
            if not parser.supports(url):
                continue
            log.info("parse start platform=%s parser=%s", parser.name, parser.name)
            try:
                result = await parser.parse(url)
                elapsed_ms = int((time.monotonic() - started) * 1000)
                log.info(
                    "parse ok platform=%s parser=%s elapsed_ms=%d",
                    result.platform,
                    parser.name,
                    elapsed_ms,
                )
                return result
            except ParserException:
                # 该解析器明确失败，交给下一个解析器继续尝试
                log.info("parse fallthrough parser=%s", parser.name)
                continue
            except Exception as exc:  # noqa: BLE001
                # 记录错误类型，不输出堆栈细节与敏感信息
                log.error(
                    "parse error parser=%s error_type=%s",
                    parser.name,
                    type(exc).__name__,
                )
                continue

        raise ParserException(
            ErrorCode.UNSUPPORTED_PLATFORM,
            "无法解析该链接，暂不支持的平台或链接无效",
        )
