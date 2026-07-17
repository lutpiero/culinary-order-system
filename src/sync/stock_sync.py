from __future__ import annotations

import asyncio

from loguru import logger

from src.config import get_config
from src.marketplaces.base import BaseMarketplace
from src.models.database import get_db
from src.odoo.client import OdooClient


async def sync_stock_to_marketplace(odoo: OdooClient, marketplace: BaseMarketplace) -> int:
    config = get_config()
    sync_cfg = config.sync
    logger.info(f"[{marketplace.name}] Starting stock sync: Odoo -> marketplace"
                + (" [DRY RUN]" if sync_cfg.dry_run else ""))

    db = await get_db()
    cursor = await db.execute(
        "SELECT odoo_product_id, marketplace_product_id, marketplace_sku "
        "FROM product_mapping WHERE marketplace = ?",
        (marketplace.name,),
    )
    mappings = [dict(r) for r in await cursor.fetchall()]

    if not mappings:
        logger.warning(f"[{marketplace.name}] No product mappings found. Run product mapping first.")
        return 0

    synced = 0
    skipped = 0
    failed = 0

    for mapping in mappings:
        odoo_pid = mapping["odoo_product_id"]
        mp_pid = mapping["marketplace_product_id"]
        sku = mapping.get("marketplace_sku", "")

        try:
            odoo_product = odoo.get_product(odoo_pid)
            odoo_stock = int(odoo_product.get("qty_available", 0))

            if odoo_stock < 0:
                logger.warning(
                    f"[{marketplace.name}] Negative stock for Odoo#{odoo_pid}: {odoo_stock}. "
                    "Clamping to 0. Check Odoo inventory."
                )
                odoo_stock = 0

            if sync_cfg.skip_stock_zero and odoo_stock == 0:
                logger.warning(
                    f"[{marketplace.name}] SKIP: Odoo#{odoo_pid} stock is 0. "
                    "Not pushing zero to marketplace to avoid making product unsellable."
                )
                skipped += 1
                continue

            if odoo_stock > sync_cfg.max_stock:
                logger.warning(
                    f"[{marketplace.name}] SKIP: Odoo#{odoo_pid} stock {odoo_stock} exceeds "
                    f"max_stock ({sync_cfg.max_stock}). Not pushing."
                )
                skipped += 1
                continue

            if sync_cfg.dry_run:
                logger.info(
                    f"[{marketplace.name}] [DRY RUN] WOULD update stock: "
                    f"Odoo#{odoo_pid} -> {mp_pid} = {odoo_stock}"
                )
                synced += 1
                continue

            success = await marketplace.update_stock(mp_pid, sku, odoo_stock)
            if success:
                synced += 1
                logger.debug(f"[{marketplace.name}] Stock synced: Odoo#{odoo_pid} -> {mp_pid} = {odoo_stock}")
            else:
                failed += 1
                logger.warning(f"[{marketplace.name}] Failed to update stock for {mp_pid}")

            delay = marketplace.config.request_delay_seconds
            await asyncio.sleep(delay)

        except Exception as e:
            failed += 1
            error_str = str(e).lower()
            if "login" in error_str or "passport" in error_str or "sso" in error_str:
                logger.error(
                    f"[{marketplace.name}] Session expired! Aborting entire stock sync batch. "
                    "Run 'login {marketplace.name}' to re-authenticate."
                )
                break
            logger.error(f"[{marketplace.name}] Error syncing stock for Odoo#{odoo_pid}: {e}")

    logger.info(
        f"[{marketplace.name}] Stock sync complete: {synced} synced, {skipped} skipped, {failed} failed "
        f"(out of {len(mappings)} total)"
    )
    return synced
