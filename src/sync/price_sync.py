from __future__ import annotations

import asyncio

from loguru import logger

from src.config import get_config
from src.marketplaces.base import BaseMarketplace, SessionExpiredError
from src.models.database import get_db
from src.notify import notify_login_required
from src.odoo.client import OdooClient


async def sync_price_to_marketplace(odoo: OdooClient, marketplace: BaseMarketplace) -> int:
    config = get_config()
    sync_cfg = config.sync
    logger.info(f"[{marketplace.name}] Starting price sync: Odoo -> marketplace"
                + (" [DRY RUN]" if sync_cfg.dry_run else ""))

    db = await get_db()
    cursor = await db.execute(
        "SELECT odoo_product_id, marketplace_product_id, marketplace_sku "
        "FROM product_mapping WHERE marketplace = ?",
        (marketplace.name,),
    )
    mappings = [dict(r) for r in await cursor.fetchall()]

    if not mappings:
        logger.warning(f"[{marketplace.name}] No product mappings found.")
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
            odoo_price = float(odoo_product.get("list_price", 0))

            if odoo_price <= 0:
                logger.error(
                    f"[{marketplace.name}] SKIP: Odoo#{odoo_pid} price is {odoo_price}. "
                    "Refusing to push zero/negative price to marketplace."
                )
                skipped += 1
                continue

            if odoo_price < sync_cfg.min_price:
                logger.error(
                    f"[{marketplace.name}] SKIP: Odoo#{odoo_pid} price Rp{odoo_price:,.0f} "
                    f"is below minimum (Rp{sync_cfg.min_price:,.0f})."
                )
                skipped += 1
                continue

            if sync_cfg.dry_run:
                logger.info(
                    f"[{marketplace.name}] [DRY RUN] WOULD update price: "
                    f"Odoo#{odoo_pid} -> {mp_pid} = Rp{odoo_price:,.0f}"
                )
                synced += 1
                continue

            success = await marketplace.update_price(mp_pid, sku, odoo_price)
            if success:
                synced += 1
                logger.debug(f"[{marketplace.name}] Price synced: Odoo#{odoo_pid} -> {mp_pid} = Rp{odoo_price:,.0f}")
            else:
                failed += 1
                logger.warning(f"[{marketplace.name}] Failed to update price for {mp_pid}")

            delay = marketplace.config.request_delay_seconds
            await asyncio.sleep(delay)

        except Exception as e:
            failed += 1
            error_str = str(e).lower()
            if (
                isinstance(e, SessionExpiredError)
                or "login" in error_str
                or "passport" in error_str
                or "sso" in error_str
            ):
                logger.error(
                    f"[{marketplace.name}] Session expired! Aborting entire price sync batch. "
                    f"Run 'login {marketplace.name}' to re-authenticate."
                )
                notify_login_required(marketplace.name)
                break
            logger.error(f"[{marketplace.name}] Error syncing price for Odoo#{odoo_pid}: {e}")

    logger.info(
        f"[{marketplace.name}] Price sync complete: {synced} synced, {skipped} skipped, {failed} failed "
        f"(out of {len(mappings)} total)"
    )
    return synced
