from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime

import httpx
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

            invoice_id = None
            cfg = get_config()
            if cfg.sync.auto_confirm_orders:
                try:
                    odoo.confirm_sale_order(odoo_order_id)
                    logger.info(
                        f"[{marketplace.name}] Order {order.order_id} confirmed — "
                        "stock.picking created"
                    )
                except Exception as e:
                    logger.error(
                        f"[{marketplace.name}] Failed to confirm order {order.order_id}: {e}. "
                        "Order created as draft — stock NOT deducted."
                    )

                try:
                    invoice_id = odoo.create_invoice_from_sale_order(odoo_order_id)
                    if invoice_id:
                        logger.info(
                            f"[{marketplace.name}] Order {order.order_id} invoiced — "
                            f"account.move#{invoice_id}"
                        )
                except Exception as e:
                    logger.error(
                        f"[{marketplace.name}] Failed to invoice order {order.order_id}: {e}. "
                        "Sale order confirmed but invoice NOT created."
                    )

            sale_order_name, invoice_name = _export_order_json(
                marketplace.name, order, odoo_order_id, invoice_id, odoo
            )

            _send_whatsapp_notification(
                marketplace.name, order, sale_order_name, invoice_name
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

    shipping_partner_id = None
    shipping_note = None

    if order.shipping_address:
        try:
            shipping_partner_id = odoo.create_shipping_partner(
                parent_id=partner_id,
                name=order.shipping_address.get("name", order.buyer_name),
                address=order.shipping_address,
            )
            logger.info(f"[{marketplace_name}] Created delivery partner#{shipping_partner_id}")
        except Exception as e:
            logger.warning(f"[{marketplace_name}] Failed to create delivery partner: {e}")

    shipping_parts = []
    if order.courier_name:
        shipping_parts.append(f"Kurir: {order.courier_name}")
    if order.tracking_number:
        shipping_parts.append(f"Tracking: {order.tracking_number}")
    if order.shipping_cost > 0:
        shipping_parts.append(f"Ongkir: Rp{order.shipping_cost:,.0f}")
    if order.shipping_etd:
        shipping_parts.append(f"Estimasi: {order.shipping_etd}")
    if shipping_parts:
        shipping_note = " | ".join(shipping_parts)

    return odoo.create_sale_order(
        partner_id, lines,
        ref=ref,
        date_order=date_order,
        shipping_note=shipping_note,
        shipping_partner_id=shipping_partner_id,
    )


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


def _export_order_json(
    marketplace_name: str,
    order: MarketOrder,
    odoo_order_id: int,
    invoice_id: int | None,
    odoo: OdooClient,
) -> tuple[str, str]:
    sale_order_name = ""
    invoice_name = ""

    try:
        so_data = odoo._call(
            "sale.order", "search_read",
            [[["id", "=", odoo_order_id]]],
            {"fields": ["name"], "limit": 1},
        )
        if so_data:
            sale_order_name = so_data[0].get("name", "")
    except Exception:
        pass

    if invoice_id:
        try:
            inv_data = odoo._call(
                "account.move", "search_read",
                [[["id", "=", invoice_id]]],
                {"fields": ["name"], "limit": 1},
            )
            if inv_data:
                invoice_name = inv_data[0].get("name", "")
        except Exception:
            pass

    cfg = get_config()
    export_path = cfg.sync.order_export_path
    if not export_path:
        return sale_order_name, invoice_name

    try:
        from pathlib import Path

        export_dir = Path(export_path)
        export_dir.mkdir(parents=True, exist_ok=True)

        picking_name = ""
        picking_id = None

        try:
            pick_data = odoo._call(
                "stock.picking", "search_read",
                [[["sale_id", "=", odoo_order_id]]],
                {"fields": ["id", "name"], "limit": 1},
            )
            if pick_data:
                picking_id = pick_data[0].get("id")
                picking_name = pick_data[0].get("name", "")
        except Exception:
            pass

        order_date = _parse_order_date(marketplace_name, order)

        export_data = {
            "marketplace": marketplace_name,
            "marketplace_order_id": order.order_id,
            "buyer_name": order.buyer_name,
            "buyer_phone": order.buyer_phone,
            "items": [
                {
                    "product_name": it.get("product_name", ""),
                    "marketplace_product_id": it.get("marketplace_product_id", ""),
                    "qty": it.get("qty", 0),
                    "price": it.get("price", 0),
                }
                for it in order.items
            ],
            "total_amount": order.total_amount,
            "order_date": order_date,
            "shipping": {
                "courier": order.courier_name,
                "tracking": order.tracking_number,
                "cost": order.shipping_cost,
                "etd": order.shipping_etd,
                "address": order.shipping_address,
            },
            "odoo": {
                "sale_order_id": odoo_order_id,
                "sale_order_name": sale_order_name,
                "invoice_id": invoice_id,
                "invoice_name": invoice_name,
                "picking_id": picking_id,
                "picking_name": picking_name,
            },
            "created_at": datetime.now(UTC).isoformat(),
        }

        filename = f"{marketplace_name}_{order.order_id}.json"
        filepath = export_dir / filename
        filepath.write_text(json.dumps(export_data, indent=2, ensure_ascii=False))
        logger.info(f"[{marketplace_name}] Order exported to {filepath}")

    except Exception as e:
        logger.error(f"[{marketplace_name}] Failed to export order {order.order_id}: {e}")

    return sale_order_name, invoice_name


def _format_whatsapp_message(
    marketplace_name: str,
    order: MarketOrder,
    sale_order_name: str,
    invoice_name: str,
) -> str:
    mp_label = marketplace_name.title()
    lines = [f"🛒 *Order Baru dari {mp_label}*", "", f"Order ID: {order.order_id}"]

    if order.buyer_name:
        lines.append(f"Pembeli: {order.buyer_name}")
    lines.append("")

    for item in order.items:
        name = item.get("product_name", item.get("marketplace_product_id", "?"))
        qty = item.get("qty", 1)
        price = item.get("price", 0)
        if price > 0:
            lines.append(f"• {name} × {qty} @ Rp{price:,.0f}")
        else:
            lines.append(f"• {name} × {qty}")

    if order.total_amount > 0:
        lines.append("")
        lines.append(f"*Total: Rp{order.total_amount:,.0f}*")

    lines.append("")
    if sale_order_name:
        lines.append(f"SO: {sale_order_name}")
    if invoice_name:
        lines.append(f"Invoice: {invoice_name}")

    if order.courier_name or order.tracking_number or order.shipping_cost > 0:
        lines.append("")
        lines.append("*Info Pengiriman:*")
        if order.courier_name:
            lines.append(f"Kurir: {order.courier_name}")
        if order.tracking_number:
            lines.append(f"Tracking: {order.tracking_number}")
        if order.shipping_cost > 0:
            lines.append(f"Ongkir: Rp{order.shipping_cost:,.0f}")
        if order.shipping_etd:
            lines.append(f"Estimasi: {order.shipping_etd}")
        addr = order.shipping_address
        if addr:
            addr_parts = [addr.get("address", ""), addr.get("district", ""), addr.get("city", ""), addr.get("state", "")]
            addr_str = ", ".join(p for p in addr_parts if p)
            if addr_str:
                lines.append(f"Alamat: {addr_str}")

    return "\n".join(lines)


def _send_whatsapp_notification(
    marketplace_name: str,
    order: MarketOrder,
    sale_order_name: str,
    invoice_name: str,
) -> None:
    cfg = get_config()
    if not cfg.sync.whatsapp_enabled:
        return

    try:
        message = _format_whatsapp_message(marketplace_name, order, sale_order_name, invoice_name)

        resp = httpx.post(
            cfg.sync.whatsapp_api_url,
            json={"phone_number": cfg.sync.whatsapp_phone, "message": message},
            timeout=10.0,
        )
        resp.raise_for_status()
        logger.info(f"[{marketplace_name}] WhatsApp notification sent for order {order.order_id}")

    except Exception as e:
        logger.error(f"[{marketplace_name}] Failed to send WhatsApp for order {order.order_id}: {e}")
