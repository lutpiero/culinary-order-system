from __future__ import annotations

import asyncio
import time

from loguru import logger

from src.config import AppConfig
from src.marketplaces.base import BaseMarketplace
from src.marketplaces.shopee import ShopeeAdapter
from src.marketplaces.tokopedia import TokopediaAdapter
from src.models.database import close_db, log_sync
from src.odoo.client import OdooClient
from src.sync.order_sync import sync_orders_from_marketplace
from src.sync.price_sync import sync_price_to_marketplace
from src.sync.stock_sync import sync_stock_to_marketplace


class SyncEngine:
    def __init__(self, config: AppConfig) -> None:
        self.config = config
        self.odoo = OdooClient(config.odoo)
        self.marketplaces: dict[str, BaseMarketplace] = {}
        self._lock = asyncio.Lock()
        self._syncing = False

    async def acquire_lock(self) -> bool:
        if self._syncing:
            return False
        self._syncing = True
        return True

    def release_lock(self) -> None:
        self._syncing = False

    def _init_marketplaces(self) -> None:
        if self.marketplaces:
            return
        base_dir = self.config.base_dir
        shopee_config = self.config.marketplaces.get("shopee")
        if shopee_config and shopee_config.enabled:
            self.marketplaces["shopee"] = ShopeeAdapter(shopee_config, base_dir)
        tokopedia_config = self.config.marketplaces.get("tokopedia")
        if tokopedia_config and tokopedia_config.enabled:
            self.marketplaces["tokopedia"] = TokopediaAdapter(tokopedia_config, base_dir)

    async def sync_all(self) -> dict[str, dict]:
        self._init_marketplaces()
        results: dict[str, dict] = {}
        for name, mp in self.marketplaces.items():
            logger.info(f"=== Syncing {name} ===")
            results[name] = {}
            try:
                logger.info(f"[{name}] Loading marketplace products to populate cache...")
                await mp.get_products()

                if self.config.sync.stock:
                    t0 = time.time()
                    count = await sync_stock_to_marketplace(self.odoo, mp)
                    elapsed = time.time() - t0
                    results[name]["stock"] = {"synced": count, "elapsed": round(elapsed, 1)}
                    await log_sync(name, "stock", "success", count)

                if self.config.sync.price:
                    t0 = time.time()
                    count = await sync_price_to_marketplace(self.odoo, mp)
                    elapsed = time.time() - t0
                    results[name]["price"] = {"synced": count, "elapsed": round(elapsed, 1)}
                    await log_sync(name, "price", "success", count)

                if self.config.sync.orders:
                    t0 = time.time()
                    count = await sync_orders_from_marketplace(self.odoo, mp)
                    elapsed = time.time() - t0
                    results[name]["orders"] = {"synced": count, "elapsed": round(elapsed, 1)}
                    await log_sync(name, "orders", "success", count)

            except Exception as e:
                logger.error(f"Failed to sync {name}: {e}")
                results[name]["error"] = str(e)
                await log_sync(name, "full", "failed", details=str(e))
            finally:
                await mp.close()

        return results

    async def sync_stock(self, marketplace_name: str | None = None) -> dict:
        self._init_marketplaces()
        results: dict[str, dict] = {}
        targets = {marketplace_name: self.marketplaces[marketplace_name]} if marketplace_name else self.marketplaces

        for name, mp in targets.items():
            try:
                logger.info(f"[{name}] Loading marketplace products to populate cache...")
                await mp.get_products()
                count = await sync_stock_to_marketplace(self.odoo, mp)
                results[name] = {"synced": count, "status": "success"}
                await log_sync(name, "stock", "success", count)
            except Exception as e:
                logger.error(f"Stock sync failed for {name}: {e}")
                results[name] = {"error": str(e), "status": "failed"}
                await log_sync(name, "stock", "failed", details=str(e))
            finally:
                await mp.close()
        return results

    async def sync_price(self, marketplace_name: str | None = None) -> dict:
        self._init_marketplaces()
        results: dict[str, dict] = {}
        targets = {marketplace_name: self.marketplaces[marketplace_name]} if marketplace_name else self.marketplaces

        for name, mp in targets.items():
            try:
                logger.info(f"[{name}] Loading marketplace products to populate cache...")
                await mp.get_products()
                count = await sync_price_to_marketplace(self.odoo, mp)
                results[name] = {"synced": count, "status": "success"}
                await log_sync(name, "price", "success", count)
            except Exception as e:
                logger.error(f"Price sync failed for {name}: {e}")
                results[name] = {"error": str(e), "status": "failed"}
                await log_sync(name, "price", "failed", details=str(e))
            finally:
                await mp.close()
        return results

    async def sync_orders(self, marketplace_name: str | None = None) -> dict:
        self._init_marketplaces()
        results: dict[str, dict] = {}
        targets = {marketplace_name: self.marketplaces[marketplace_name]} if marketplace_name else self.marketplaces

        for name, mp in targets.items():
            try:
                count = await sync_orders_from_marketplace(self.odoo, mp)
                results[name] = {"synced": count, "status": "success"}
                await log_sync(name, "orders", "success", count)
            except Exception as e:
                logger.error(f"Order sync failed for {name}: {e}")
                results[name] = {"error": str(e), "status": "failed"}
                await log_sync(name, "orders", "failed", details=str(e))
            finally:
                await mp.close()
        return results

    async def close(self) -> None:
        for mp in self.marketplaces.values():
            await mp.close()
        await close_db()
