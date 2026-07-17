from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from loguru import logger
from pydantic import BaseModel

from src.config import AppConfig, get_config
from src.sync.engine import SyncEngine

_engine: SyncEngine | None = None
_config: AppConfig | None = None


class WebhookPayload(BaseModel):
    event: str = ""
    product_id: int | None = None
    details: dict = {}


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


app = FastAPI(title="Marketplace Integrator Webhook", lifespan=lifespan)


def set_engine(engine: SyncEngine) -> None:
    global _engine
    _engine = engine


def set_config(config: AppConfig) -> None:
    global _config
    _config = config


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/webhook/stock", dependencies=[Depends(verify_api_key)])
async def webhook_stock(payload: WebhookPayload):
    logger.info(f"Webhook received: stock change for product {payload.product_id}")
    if _engine is None:
        return {"error": "Engine not initialized"}
    if not await _engine.acquire_lock():
        return {"error": "Sync already in progress", "status": "busy"}
    try:
        results = await _engine.sync_stock()
        return {"results": results}
    finally:
        _engine.release_lock()


@app.post("/webhook/price", dependencies=[Depends(verify_api_key)])
async def webhook_price(payload: WebhookPayload):
    logger.info(f"Webhook received: price change for product {payload.product_id}")
    if _engine is None:
        return {"error": "Engine not initialized"}
    if not await _engine.acquire_lock():
        return {"error": "Sync already in progress", "status": "busy"}
    try:
        results = await _engine.sync_price()
        return {"results": results}
    finally:
        _engine.release_lock()


@app.post("/webhook/orders", dependencies=[Depends(verify_api_key)])
async def webhook_orders():
    logger.info("Webhook received: new order notification")
    if _engine is None:
        return {"error": "Engine not initialized"}
    if not await _engine.acquire_lock():
        return {"error": "Sync already in progress", "status": "busy"}
    try:
        results = await _engine.sync_orders()
        return {"results": results}
    finally:
        _engine.release_lock()


@app.post("/webhook/sync", dependencies=[Depends(verify_api_key)])
async def webhook_full_sync():
    logger.info("Webhook received: full sync request")
    if _engine is None:
        return {"error": "Engine not initialized"}
    if not await _engine.acquire_lock():
        return {"error": "Sync already in progress", "status": "busy"}
    try:
        results = await _engine.sync_all()
        return {"results": results}
    finally:
        _engine.release_lock()
