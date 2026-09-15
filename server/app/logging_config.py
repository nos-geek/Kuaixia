# -*- coding: utf-8 -*-
"""Python 日志配置。

日志只记录：解析平台、Parser、耗时、错误类型。
不记录 Cookie / Authorization / Token 等敏感信息。
"""
from __future__ import annotations

import logging
import sys


def setup_logging() -> None:
    root = logging.getLogger("kuaixia")
    root.setLevel(logging.INFO)

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter(
            "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
    )
    root.addHandler(handler)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(f"kuaixia.{name}")
