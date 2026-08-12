from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.v1.router import api_v1_router
from app.config import get_settings
from app.core.database import engine
from app.core.exceptions import AppException, build_error_envelope, get_correlation_id
from app.core.logging import configure_logging, get_logger
from app.core.middleware import CorrelationIdMiddleware
from app.core.redis import close_redis_client

logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    configure_logging()
    logger.info("Starting Fitness Tracker API")
    yield
    await close_redis_client()
    await engine.dispose()
    logger.info("Shutting down Fitness Tracker API")


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(
        title="Fitness Tracker API",
        description="AI-Powered Fitness Platform",
        version="0.1.0",
        lifespan=lifespan,
        docs_url="/api/docs" if not settings.is_production else None,
        redoc_url="/api/redoc" if not settings.is_production else None,
        openapi_url="/api/openapi.json" if not settings.is_production else None,
    )

    app.add_middleware(CorrelationIdMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["X-Correlation-ID"],
    )

    register_exception_handlers(app)
    app.include_router(api_v1_router, prefix="/api/v1")

    return app


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppException)
    async def app_exception_handler(request: Request, exc: AppException) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=build_error_envelope(
                code=exc.code,
                message=exc.message,
                correlation_id=get_correlation_id(request),
                details=exc.details,
            ),
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(
        request: Request,
        exc: StarletteHTTPException,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content=build_error_envelope(
                code=_http_status_to_code(exc.status_code),
                message=str(exc.detail),
                correlation_id=get_correlation_id(request),
            ),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(
        request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        details = [
            {
                "field": ".".join(str(loc) for loc in err.get("loc", ()) if loc != "body"),
                "message": err.get("msg", "Invalid value"),
                "code": "INVALID_FORMAT",
            }
            for err in exc.errors()
        ]
        return JSONResponse(
            status_code=400,
            content=build_error_envelope(
                code="VALIDATION_ERROR",
                message="Request validation failed",
                correlation_id=get_correlation_id(request),
                details=details,
            ),
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("Unhandled exception", exc_info=exc)
        return JSONResponse(
            status_code=500,
            content=build_error_envelope(
                code="INTERNAL_ERROR",
                message="An unexpected error occurred",
                correlation_id=get_correlation_id(request),
            ),
        )


def _http_status_to_code(status_code: int) -> str:
    mapping = {
        401: "UNAUTHORIZED",
        403: "FORBIDDEN",
        404: "NOT_FOUND",
        409: "CONFLICT",
        429: "RATE_LIMIT_EXCEEDED",
        503: "SERVICE_UNAVAILABLE",
    }
    return mapping.get(status_code, "BAD_REQUEST")


app = create_app()
