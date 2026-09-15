# -*- coding: utf-8 -*-
"""快夏 Kuaixia Parser Server 主入口（FastAPI）。

API 协议（Kuaixia Parser API Protocol）：
    GET  /api/v1/health
    POST /api/v1/parse

解析逻辑不在 Route 中，全部委托给 ParserRegistry。
"""
from __future__ import annotations

from urllib.parse import urlparse

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import API_VERSION, SERVER_NAME, SERVER_VERSION
from app.errors import ErrorCode, ParserException
from app.logging_config import get_logger, setup_logging
from app.models import ErrorResponse, HealthResponse, MediaParseResult, ParseRequest
from app.parsers.registry import ParserRegistry

setup_logging()
log = get_logger("api")

app = FastAPI(title=SERVER_NAME, version=SERVER_VERSION)

# 统一错误码 → HTTP 状态码映射
_ERROR_STATUS = {
    ErrorCode.INVALID_URL: 400,
    ErrorCode.UNSUPPORTED_PLATFORM: 422,
    ErrorCode.NETWORK_ERROR: 502,
    ErrorCode.TIMEOUT: 504,
    ErrorCode.LOGIN_REQUIRED: 401,
    ErrorCode.ACCESS_DENIED: 403,
    ErrorCode.MEDIA_NOT_FOUND: 404,
    ErrorCode.SERVER_ERROR: 500,
    ErrorCode.PARSER_ERROR: 422,
}

# 允许任意来源，方便 Android / 浏览器 / 第三方客户端调试。
# 本服务为解析服务，无敏感写操作。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

registry = ParserRegistry()


@app.exception_handler(ParserException)
async def parser_exception_handler(request: Request, exc: ParserException) -> JSONResponse:
    """统一把业务异常转为干净的 {code, message} 错误体。"""
    status = _ERROR_STATUS.get(exc.code, 422)
    return JSONResponse(
        status_code=status,
        content=ErrorResponse(code=exc.code, message=exc.message).model_dump(),
    )


def _validate_url(url: str) -> None:
    """校验 URL：仅允许 http/https，拒绝 file:// content:// 等本地协议。"""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ParserException(ErrorCode.INVALID_URL, "链接无效，仅支持 http/https")


@app.get("/api/v1/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        name=SERVER_NAME,
        version=SERVER_VERSION,
        apiVersion=API_VERSION,
    )


@app.post("/api/v1/parse", response_model=MediaParseResult)
async def parse(req: ParseRequest) -> MediaParseResult:
    try:
        _validate_url(req.url)
        return await registry.parse(req.url)
    except ParserException:
        raise
    except Exception as exc:  # noqa: BLE001
        log.error("unexpected error error_type=%s", type(exc).__name__)
        raise ParserException(ErrorCode.SERVER_ERROR, "服务器内部错误") from exc
