from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime

from loguru import logger

from src.config import get_config
from src.marketplaces.base import BaseMarketplace, MarketOrder
from src.models.database import get_db, get_order_cache, upsert_order_cache
from src.odoo.client import OdooClient


async def sync_orders_from_marketplace(odoo: OdooClient, marketplace: BaseMarketplace) -> int:
    logger.info(f"[{marketplace.name}] Starting order sync: marketplace -> Odoo")

    orders = await marketplace.get_orders(status="new")
    if not orders:
        logger.info(f"[{marketplace.name}] No new orders found.")
        return 0

    synced = 0
    for order in orders:
        existing = await get_order_cache(marketplace.name, order.order_id)
        if existing:
            logger.debug(f"[{marketplace.name}] Order {order.order_id} already imported, skipping.")
            continue

        try:
            if not order.items:
                logger.warning(
                    f"[{marketplace.name}] Order {order.order_id} has no line items. "
                    "Order item scraping may not be implemented yet. Skipping."
                )
                await upsert_order_cache(
                    marketplace=marketplace.name,
                    marketplace_order_id=order.order_id,
                    status="skipped_no_items",
                    buyer_name=order.buyer_name,
                    total_amount=order.total_amount,
                )
                continue

            await upsert_order_cache(
                marketplace=marketplace.name,
                marketplace_order_id=order.order_id,
                status="pending",
                buyer_name=order.buyer_name,
                total_amount=order.total_amount,
            )

            odoo_order_id = await _create_odoo_order(odoo, marketplace.name, order)

            cfg = get_config()
            if cfg.sync.auto_confirm_orders:
                try:
                    odoo.confirm_sale_order(odoo_order_id)
                    logger.info(
                        f"[{marketplace.name}] Order {order.order_id} confirmed — "
                        "stock.picking created, inventory updated"
                    )
                except Exception as e:
                    logger.error(
                        f"[{marketplace.name}] Failed to confirm order {order.order_id}: {e}. "
                        "Order created as draft — stock NOT deducted."
                    )

            await upsert_order_cache(
                marketplace=marketplace.name,
                marketplace_order_id=order.order_id,
                status="imported",
                odoo_sale_order_id=odoo_order_id,
                buyer_name=order.buyer_name,
                total_amount=order.total_amount,
                raw_data=json.dumps(order.raw) if order.raw else None,
            )
            synced += 1
            logger.info(
                f"[{marketplace.name}] Order {order.order_id} -> Odoo sale.order#{odoo_order_id}"
            )
            delay = marketplace.config.request_delay_seconds
            await asyncio.sleep(delay)

        except Exception as e:
            error_str = str(e).lower()
            if "login" in error_str or "passport" in error_str or "sso" in error_str:
                logger.error(
                    f"[{marketplace.name}] Session expired! Aborting order sync. "
                    f"Run 'login {marketplace.name}' to re-authenticate."
                )
                break
            logger.error(f"[{marketplace.name}] Failed to import order {order.order_id}: {e}")
            await upsert_order_cache(
                marketplace=marketplace.name,
                marketplace_order_id=order.order_id,
                status="failed",
                raw_data=json.dumps({"error": str(e)}),
            )

    logger.info(f"[{marketplace.name}] Order sync complete: {synced}/{len(orders)} imported")
    return synced


async def _create_odoo_order(odoo: OdooClient, marketplace_name: str, order: MarketOrder) -> int:
    partner_id = odoo.get_or_create_partner(
        name=order.buyer_name or f"Marketplace Buyer ({order.order_id})",
        phone=order.buyer_phone or None,
    )

    lines: list[dict] = []
    for item in order.items:
        product_id = item.get("product_id")
        if product_id is None:
            db = await get_db()
            cursor = await db.execute(
                "SELECT odoo_product_id FROM product_mapping "
                "WHERE marketplace = ? AND marketplace_product_id = ?",
                (marketplace_name, item.get("marketplace_product_id", "")),
            )
            row = await cursor.fetchone()
            if row:
                product_id = row["odoo_product_id"]
            else:
                logger.warning(
                    f"[{marketplace_name}] No mapping for marketplace product "
                    f"{item.get('marketplace_product_id')}, skipping order line."
                )
                continue

        lines.append({
            "product_id": product_id,
            "qty": item.get("qty", 1),
            "price": item.get("price", 0),
        })

    if not lines:
        raise ValueError("No valid order lines to import")

    total_calculated = sum(l["price"] * l["qty"] for l in lines)
    if order.total_amount > 0 and abs(total_calculated - order.total_amount) > order.total_amount * 0.1:
        logger.warning(
            f"[{marketplace_name}] Order {order.order_id} amount mismatch: "
            f"calculated Rp{total_calculated:,.0f} vs marketplace Rp{order.total_amount:,.0f}. "
            "Proceeding but verify amounts."
        )

    ref = f"[{marketplace_name.upper()}] {order.order_id}"
    date_order = _parse_order_date(marketplace_name, order)
    return odoo.create_sale_order(partner_id, lines, ref=ref, date_order=date_order)


def _parse_order_date(marketplace_name: str, order: MarketOrder) -> str | None:
    try:
        if marketplace_name == "tokopedia":
            ts = order.created_at
            if ts and ts.isdigit():
                return datetime.fromtimestamp(int(ts), tz=UTC).strftime("%Y-%m-%d")
        elif marketplace_name == "shopee":
            sn = ""
            polo = order.raw.get("package_level_order_card")
            oc = order.raw.get("order_card")
            if polo:
                sn = polo.get("card_header", {}).get("order_sn", "")
            elif oc:
                sn = oc.get("card_header", {}).get("order_sn", "")
            if sn and len(sn) >= 6 and sn[:2].isdigit():
                yy = int(sn[:2])
                mm = int(sn[2:4])
                dd = int(sn[4:6])
                year = 2000 + yy
                return f"{year}-{mm:02d}-{dd:02d}"
    except Exception:
        pass
    return None
