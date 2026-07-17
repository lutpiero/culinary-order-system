from __future__ import annotations

from loguru import logger

from src.marketplaces.base import BaseMarketplace
from src.models.database import get_product_mapping, upsert_product_mapping
from src.odoo.client import OdooClient


async def sync_products_to_marketplace(odoo: OdooClient, marketplace: BaseMarketplace) -> int:
    logger.info(f"[{marketplace.name}] Starting product sync: Odoo -> marketplace")
    return 0


async def build_product_mapping_interactive(odoo: OdooClient, marketplace: BaseMarketplace) -> int:
    logger.info(f"[{marketplace.name}] Building product mapping...")
    logger.info("This will compare Odoo products with marketplace products and create mappings.")

    odoo_products = odoo.get_products(fields=["id", "name", "default_code", "list_price", "qty_available"])
    mp_products = await marketplace.get_products()

    if not mp_products:
        logger.error(f"[{marketplace.name}] No marketplace products found.")
        return 0

    odoo_by_sku: dict[str, dict] = {}
    odoo_by_name: dict[str, dict] = {}
    for p in odoo_products:
        sku = (p.get("default_code") or "").strip()
        if sku:
            odoo_by_sku[sku.lower()] = p
        odoo_by_name[p["name"].strip().lower()] = p

    mapped = 0

    for mp in mp_products:
        existing = await get_product_mapping(marketplace.name, marketplace_product_id=mp.product_id)
        if existing:
            continue

        match_odoo = None
        if mp.sku:
            match_odoo = odoo_by_sku.get(mp.sku.lower())
        if not match_odoo:
            match_odoo = odoo_by_name.get(mp.name.strip().lower())

        if match_odoo:
            await upsert_product_mapping(
                marketplace=marketplace.name,
                odoo_product_id=match_odoo["id"],
                marketplace_product_id=mp.product_id,
                marketplace_sku=mp.sku or None,
            )
            mapped += 1
            logger.info(
                f"[{marketplace.name}] Mapped: Odoo#{match_odoo['id']} ({match_odoo['name']}) <-> {mp.product_id} ({mp.name})"
            )
        else:
            logger.warning(
                f"[{marketplace.name}] No Odoo match for marketplace product: {mp.product_id} - {mp.name} (SKU: {mp.sku})"
            )

    logger.info(f"[{marketplace.name}] Product mapping complete: {mapped} new mappings created")
    return mapped
