# -*- coding: utf-8 -*-
"""统一错误码与业务异常。

错误码与 Android 端保持一致，Android 端据此显示中文提示，
不会把 StackTrace 暴露给用户。
"""
from __future__ import annotations


class ErrorCode:
    INVALID_URL = "INVALID_URL"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    NETWORK_ERROR = "NETWORK_ERROR"
    TIMEOUT = "TIMEOUT"
    LOGIN_REQUIRED = "LOGIN_REQUIRED"
    ACCESS_DENIED = "ACCESS_DENIED"
    MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND"
    SERVER_ERROR = "SERVER_ERROR"
    PARSER_ERROR = "PARSER_ERROR"


class ParserException(Exception):
    """解析业务异常，携带统一错误码。"""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message
