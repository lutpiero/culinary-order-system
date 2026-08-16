from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from loguru import logger
from pydantic import BaseModel, Field

from src.config import AppConfig, get_config
from src.marketplaces.base import BaseMarketplace
from src.marketplaces.shopee import ShopeeAdapter
from src.marketplaces.tokopedia import TokopediaAdapter
from src.odoo.client import OdooClient
from src.printing import detect_marketplace, render_label_file, write_print_request
from src.sync.delivery_sync import confirm_deliveries_after_pickup
from src.sync.engine import SyncEngine

_engine: SyncEngine | None = None
_config: AppConfig | None = None


class WebhookPayload(BaseModel):
    """Body accepted by the webhook endpoints. All fields are optional."""

    event: str = Field(default="", description="Webhook event name (informational).")
    product_id: int | None = Field(default=None, description="Odoo product ID that changed.")
    details: dict = Field(default_factory=dict, description="Optional extra event context.")


class LabelRenderRequest(BaseModel):
    """Request body for ``POST /labels/render``."""

    pdf: str = Field(
        description="Path to the label PDF (absolute, or relative to the project base dir).",
        examples=["labels/2026-04-01_tokopedia_TSPX-00248220303.pdf"],
    )
    marketplace: str | None = Field(
        default=None,
        description="Marketplace the label belongs to (default: auto-detected from the filename).",
        examples=["shopee", "tokopedia"],
    )
    dpi: int | None = Field(default=None, description="Render DPI (default: from config.yaml).")
    width: int | None = Field(default=None, description="Output width in px (default: from config print_width_mm).")
    order_id: str | None = Field(default=None, description="Order ID override (default: parsed from the label).")
    queue: bool = Field(default=True, description="Also drop a print request into the print queue.")


class LabelDownloadRequest(BaseModel):
    """Request body for ``POST /labels/download``."""

    marketplace: str = Field(description="Marketplace to download from.", examples=["shopee", "tokopedia"])
    order_id: str = Field(description="Marketplace order ID.", examples=["220621JTRHCTK8"])
    max_retries: int | None = Field(
        default=None, description="Retries when the download hits the anti-bot puzzle (default 3, Tokopedia only)."
    )
    retry_wait_seconds: float | None = Field(
        default=None, description="Seconds to rest between retries (default 180, Tokopedia only)."
    )


class PickupRequest(BaseModel):
    """Request body for ``POST /pickup``."""

    marketplace: str = Field(default="shopee", description="Marketplace. Only 'shopee' is supported.")
    order_id: str | None = Field(default=None, description="Specific order ID; omit to pickup all eligible orders.")


class WebhookResponse(BaseModel):
    """Response shape for the webhook endpoints."""

    results: dict | None = Field(default=None, description="Sync results keyed by marketplace.")
    error: str | None = Field(default=None, description="Error message, when the sync could not start.")
    status: str | None = Field(default=None, description="e.g. 'busy' when a sync is already running.")


class LabelRenderResponse(BaseModel):
    """Response for ``POST /labels/render``."""

    order_id: str = Field(description="Order ID of the rendered label.")
    marketplace: str = Field(description="Marketplace used for parsing.")
    png: str = Field(description="Path to the regenerated thermal preview PNG.")
    pbm: str = Field(description="Path to the regenerated thermal PBM (sent to the printer).")
    queued: str | None = Field(default=None, description="Print queue request path, or null when not queued.")


class LabelDownloadResponse(BaseModel):
    """Response for ``POST /labels/download``."""

    order_id: str = Field(description="Marketplace order ID.")
    marketplace: str = Field(description="Marketplace the label was downloaded from.")
    filepath: str = Field(description="Path where the label PDF was saved.")


class PickupResponse(BaseModel):
    """Response for ``POST /pickup``."""

    marketplace: str = Field(description="Marketplace the pickup was arranged on.")
    order_id: str | None = Field(default=None, description="Order ID that was picked up, or null for 'ALL'.")
    pickups: list[str] = Field(description="List of order IDs pickup was arranged for.")
    deliveries_validated: list[str] = Field(
        default_factory=list,
        description="Pickup order IDs whose Odoo delivery was validated (stock deducted).",
    )


class ErrorResponse(BaseModel):
    """Standard error body returned on non-2xx responses."""

    detail: str = Field(description="Human-readable error message.")


async def verify_api_key(request: Request) -> None:
    cfg = _config or get_config()
    if not cfg.server.api_key:
        return
    auth_header = request.headers.get("X-API-Key", "")
    if auth_header != cfg.server.api_key:
        raise HTTPException(status_code=401, detail="Invalid or missing X-API-Key header")


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    if _engine:
        await _engine.close()


API_SECURITY = {
    "securitySchemes": {
        "ApiKeyAuth": {
            "type": "apiKey",
            "in": "header",
            "name": "X-API-Key",
            "description": (
                "API key from config.yaml `server.api_key`. "
                "When `api_key` is empty the endpoints are open and no key is required."
            ),
        }
    }
}

API_TAGS = [
    {"name": "Health", "description": "Service liveness checks."},
    {"name": "Webhooks", "description": "Webhook-triggered syncs (stock, price, orders, full)."},
    {"name": "Labels", "description": "Download and render shipping labels."},
    {"name": "Pickup", "description": "Arrange courier pickup."},
]

app = FastAPI(
    title="Marketplace Integrator API",
    version="0.1.0",
    description=(
        "REST API for the Marketplace Integrator (Odoo <-> Shopee / Tokopedia).\n\n"
        "- **Swagger UI**: `/docs`\n"
        "- **ReDoc**: `/redoc`\n"
        "- **OpenAPI JSON**: `/openapi.json`\n\n"
        "Endpoints that mutate marketplace state or access labels require the `X-API-Key` header "
        "when `server.api_key` is set in config.yaml."
    ),
    lifespan=lifespan,
    openapi_tags=API_TAGS,
)


def custom_openapi():
    """Build the OpenAPI schema, injecting the X-API-Key security scheme."""
    if app.openapi_schema:
        return app.openapi_schema
    from fastapi.openapi.utils import get_openapi

    schema = get_openapi(
        title="Marketplace Integrator API",
        version="0.1.0",
        description=app.description,
        routes=app.routes,
        tags=API_TAGS,
    )
    schema.setdefault("components", {})
    schema["components"]["securitySchemes"] = API_SECURITY["securitySchemes"]
    app.openapi_schema = schema
    return schema


app.openapi = custom_openapi


def set_engine(engine: SyncEngine) -> None:
    global _engine
    _engine = engine


def set_config(config: AppConfig) -> None:
    global _config
    _config = config


async def _get_adapter(marketplace: str) -> tuple[BaseMarketplace, bool]:
    """Return a marketplace adapter, preferring the engine's persistent one.

    Returns ``(adapter, owned)`` where ``owned`` means the caller constructed
    it and is responsible for closing it.
    """
    config = _config or get_config()
    if _engine is not None:
        _engine._init_marketplaces()
        adapter = _engine.marketplaces.get(marketplace)
        if adapter is not None:
            return adapter, False
    mp_config = config.marketplaces.get(marketplace)
    if not mp_config:
        raise HTTPException(status_code=404, detail=f"{marketplace} not configured in config.yaml")
    base_dir = config.base_dir
    if marketplace == "shopee":
        return ShopeeAdapter(mp_config, base_dir), True
    if marketplace == "tokopedia":
        return TokopediaAdapter(mp_config, base_dir), True
    raise HTTPException(status_code=400, detail=f"Unknown marketplace: {marketplace}")


@app.get(
    "/",
    tags=["Health"],
    summary="API information",
    description="Root endpoint listing the API and pointing to the interactive docs.",
)
async def root() -> dict:
    return {
        "name": "Marketplace Integrator API",
        "version": app.version,
        "docs": "/docs",
        "redoc": "/redoc",
        "openapi": "/openapi.json",
    }


@app.get(
    "/health",
    tags=["Health"],
    summary="Health check",
    description="Returns ``{'status': 'ok'}`` when the service is up.",
    response_model=dict,
)
async def health() -> dict:
    return {"status": "ok"}


@app.post(
    "/webhook/stock",
    dependencies=[Depends(verify_api_key)],
    tags=["Webhooks"],
    summary="Trigger stock sync",
    description="Run a stock sync for all enabled marketplaces.",
    openapi_extra={"security": [{"ApiKeyAuth": []}]},
    response_model=WebhookResponse,
    responses={401: {"model": ErrorResponse}, 503: {"model": WebhookResponse}},
)
async def webhook_stock(payload: WebhookPayload) -> WebhookResponse:
    logger.info(f"Webhook received: stock change for product {payload.product_id}")
    if _engine is None:
        return WebhookResponse(error="Engine not initialized")
    if not await _engine.acquire_lock():
        return WebhookResponse(error="Sync already in progress", status="busy")
    try:
        results = await _engine.sync_stock()
        return WebhookResponse(results=results)
    finally:
        _engine.release_lock()


@app.post(
    "/webhook/price",
    dependencies=[Depends(verify_api_key)],
    tags=["Webhooks"],
    summary="Trigger price sync",
    description="Run a price sync for all enabled marketplaces.",
    openapi_extra={"security": [{"ApiKeyAuth": []}]},
    response_model=WebhookResponse,
    responses={401: {"model": ErrorResponse}, 503: {"model": WebhookResponse}},
)
async def webhook_price(payload: WebhookPayload) -> WebhookResponse:
    logger.info(f"Webhook received: price change for product {payload.product_id}")
    if _engine is None:
        return WebhookResponse(error="Engine not initialized")
    if not await _engine.acquire_lock():
        return WebhookResponse(error="Sync already in progress", status="busy")
    try:
        results = await _engine.sync_price()
        return WebhookResponse(results=results)
    finally:
        _engine.release_lock()


@app.post(
    "/webhook/orders",
    dependencies=[Depends(verify_api_key)],
    tags=["Webhooks"],
    summary="Trigger orders sync",
    description="Run an orders sync for all enabled marketplaces.",
    openapi_extra={"security": [{"ApiKeyAuth": []}]},
    response_model=WebhookResponse,
    responses={401: {"model": ErrorResponse}, 503: {"model": WebhookResponse}},
)
async def webhook_orders() -> WebhookResponse:
    logger.info("Webhook received: new order notification")
    if _engine is None:
        return WebhookResponse(error="Engine not initialized")
    if not await _engine.acquire_lock():
        return WebhookResponse(error="Sync already in progress", status="busy")
    try:
        results = await _engine.sync_orders()
        return WebhookResponse(results=results)
    finally:
        _engine.release_lock()


@app.post(
    "/webhook/sync",
    dependencies=[Depends(verify_api_key)],
    tags=["Webhooks"],
    summary="Trigger full sync",
    description="Run a full sync (stock, price and orders) for all enabled marketplaces.",
    openapi_extra={"security": [{"ApiKeyAuth": []}]},
    response_model=WebhookResponse,
    responses={401: {"model": ErrorResponse}, 503: {"model": WebhookResponse}},
)
async def webhook_full_sync() -> WebhookResponse:
    logger.info("Webhook received: full sync request")
    if _engine is None:
        return WebhookResponse(error="Engine not initialized")
    if not await _engine.acquire_lock():
        return WebhookResponse(error="Sync already in progress", status="busy")
    try:
        results = await _engine.sync_all()
        return WebhookResponse(results=results)
    finally:
        _engine.release_lock()


@app.post(
    "/labels/render",
    dependencies=[Depends(verify_api_key)],
    tags=["Labels"],
    summary="Render a label PDF to thermal bitmap and queue for print",
    description=(
        "Parse a shipping-label PDF (Shopee / Tokopedia GKS, TSA, TSPX), regenerate the "
        "72mm thermal bitmap (PNG + PBM) into `labels/print_ready`, and by default drop a "
        "print request into `labels/print_queue` for the Windows print service."
    ),
    openapi_extra={"security": [{"ApiKeyAuth": []}]},
    response_model=LabelRenderResponse,
    responses={
        401: {"model": ErrorResponse},
        404: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
    },
)
async def label_render(payload: LabelRenderRequest) -> LabelRenderResponse:
    config = _config or get_config()
    pdf_path = Path(payload.pdf)
    if not pdf_path.is_absolute():
        pdf_path = config.base_dir / pdf_path
    if not pdf_path.exists():
        raise HTTPException(status_code=404, detail=f"PDF not found: {pdf_path}")
    marketplace = payload.marketplace or detect_marketplace(pdf_path.name) or "shopee"
    try:
        rendered = render_label_file(
            pdf_path,
            marketplace=marketplace,
            dpi=payload.dpi,
            width_px=payload.width,
        )
    except Exception as e:
        raise HTTPException(status_code=422, detail=f"Render failed: {e}")
    if rendered is None:
        raise HTTPException(status_code=422, detail=f"Could not render label from {pdf_path}")
    png_path, pbm_path, label = rendered
    order_id = payload.order_id or label.order_id
    queued = None
    if payload.queue and order_id:
        queued = str(write_print_request(pbm_path, marketplace, order_id, png_path=png_path))
    logger.info(f"[api] rendered label {order_id} from {pdf_path}")
    return LabelRenderResponse(
        order_id=order_id,
        marketplace=marketplace,
        png=str(png_path),
        pbm=str(pbm_path),
        queued=queued,
    )


@app.post(
    "/labels/download",
    dependencies=[Depends(verify_api_key)],
    tags=["Labels"],
    summary="Download a shipping label for an order",
    description=(
        "Download the shipping-label PDF for a given marketplace order ID via the "
        "marketplace adapter and save it under `sync.label_output_path`."
    ),
    openapi_extra={"security": [{"ApiKeyAuth": []}]},
    response_model=LabelDownloadResponse,
    responses={
        401: {"model": ErrorResponse},
        404: {"model": ErrorResponse},
        502: {"model": ErrorResponse},
    },
)
async def label_download(payload: LabelDownloadRequest) -> LabelDownloadResponse:
    config = _config or get_config()
    adapter, owned = await _get_adapter(payload.marketplace)
    try:
        label_dir = Path(config.sync.label_output_path)
        if not label_dir.is_absolute():
            label_dir = config.base_dir / label_dir
        label_dir.mkdir(parents=True, exist_ok=True)
        if payload.marketplace == "tokopedia":
            filepath = await adapter.download_shipping_label(
                payload.order_id,
                label_dir,
                max_retries=payload.max_retries or 3,
                retry_wait_seconds=payload.retry_wait_seconds or 180.0,
            )
        else:
            filepath = await adapter.download_shipping_label(payload.order_id, label_dir)
        if filepath is None:
            raise HTTPException(status_code=502, detail=f"Failed to download label for {payload.order_id}")
        logger.info(f"[api] downloaded label {payload.order_id} -> {filepath}")
        return LabelDownloadResponse(
            order_id=payload.order_id,
            marketplace=payload.marketplace,
            filepath=str(filepath),
        )
    finally:
        if owned:
            await adapter.close()


@app.post(
    "/pickup",
    dependencies=[Depends(verify_api_key)],
    tags=["Pickup"],
    summary="Arrange courier pickup",
    description="Arrange a Shopee courier pickup for one order, or all eligible orders when no order_id is given.",
    openapi_extra={"security": [{"ApiKeyAuth": []}]},
    response_model=PickupResponse,
    responses={400: {"model": ErrorResponse}, 401: {"model": ErrorResponse}},
)
async def arrange_pickup(payload: PickupRequest) -> PickupResponse:
    if payload.marketplace != "shopee":
        raise HTTPException(status_code=400, detail=f"Pickup not supported for {payload.marketplace}")
    adapter, owned = await _get_adapter("shopee")
    try:
        results = await adapter.arrange_pickup(payload.order_id)
        logger.info(f"[api] arranged {len(results)} pickup(s) for order_id={payload.order_id or 'ALL'}")

        deliveries_validated: list[str] = []
        if results:
            config = _config or get_config()
            odoo = _engine.odoo if _engine is not None else OdooClient(config.odoo)
            try:
                deliveries_validated = await confirm_deliveries_after_pickup(
                    "shopee", results, odoo
                )
            except Exception as e:
                logger.error(f"[api] Failed to validate Odoo deliveries after pickup: {e}")

        return PickupResponse(
            marketplace="shopee",
            order_id=payload.order_id,
            pickups=results,
            deliveries_validated=deliveries_validated,
        )
    finally:
        if owned:
            await adapter.close()
