#!/usr/bin/env python3
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from loguru import logger

from src.config import load_config, set_config
from src.models.database import get_db, init_db
from src.server import app, set_engine
from src.server import set_config as set_server_config
from src.sync.engine import SyncEngine

logger.remove()
logger.add(sys.stderr, level="INFO", format="<green>{time:HH:mm:ss}</green> | <level>{level:7}</level> | {message}")
logger.add("data/marketplace.log", rotation="10 MB", retention="7 days", level="DEBUG")


async def cmd_login(args):
    config = load_config()
    marketplace_name = args.marketplace
    base_dir = config.base_dir

    if marketplace_name == "shopee":
        from src.marketplaces.shopee import ShopeeAdapter
        mp_config = config.marketplaces.get("shopee")
        if not mp_config:
            print("Shopee not configured in config.yaml")
            return
        adapter = ShopeeAdapter(mp_config, base_dir)
    elif marketplace_name == "tokopedia":
        from src.marketplaces.tokopedia import TokopediaAdapter
        mp_config = config.marketplaces.get("tokopedia")
        if not mp_config:
            print("Tokopedia not configured in config.yaml")
            return
        adapter = TokopediaAdapter(mp_config, base_dir)
    else:
        print(f"Unknown marketplace: {marketplace_name}. Use 'shopee' or 'tokopedia'.")
        return

    success = await adapter.login_interactive()
    if success:
        print(f"Login successful for {marketplace_name}!")
    else:
        print(f"Login failed for {marketplace_name}.")
    await adapter.close()


async def cmd_sync(args):
    config = load_config()
    init_db(config.base_dir / "data" / "marketplace.db")
    await get_db()

    if args.live:
        config.sync.dry_run = False
        logger.warning("LIVE MODE: Changes will be pushed to real marketplaces!")

    set_config(config)

    if not config.sync.dry_run:
        logger.warning(
            "dry_run is False. This will modify live marketplace data. "
            "Press Ctrl+C within 5 seconds to abort..."
        )
        try:
            await asyncio.sleep(5)
        except KeyboardInterrupt:
            print("\nAborted.")
            return

    engine = SyncEngine(config)
    sync_type = args.type
    marketplace = args.marketplace

    try:
        if sync_type == "all":
            results = await engine.sync_all()
        elif sync_type == "stock":
            results = await engine.sync_stock(marketplace)
        elif sync_type == "price":
            results = await engine.sync_price(marketplace)
        elif sync_type == "orders":
            results = await engine.sync_orders(marketplace)
        elif sync_type == "map":
            results = await cmd_map_products(engine, marketplace)
        else:
            print(f"Unknown sync type: {sync_type}")
            return

        print("\n=== Sync Results ===")
        if config.sync.dry_run:
            print("[DRY RUN] No changes were made to marketplaces.\n")
        for mp_name, data in results.items():
            print(f"\n{mp_name}:")
            for key, value in data.items():
                print(f"  {key}: {value}")
    finally:
        await engine.close()


async def cmd_map_products(engine: SyncEngine, marketplace_name: str | None):
    from src.sync.product_sync import build_product_mapping_interactive

    engine._init_marketplaces()
    results: dict[str, dict] = {}

    targets = engine.marketplaces
    if marketplace_name:
        if marketplace_name not in engine.marketplaces:
            print(f"Marketplace '{marketplace_name}' not found or not enabled.")
            return {}
        targets = {marketplace_name: engine.marketplaces[marketplace_name]}

    for name, mp in targets.items():
        try:
            count = await build_product_mapping_interactive(engine.odoo, mp)
            results[name] = {"mappings_created": count}
        except Exception as e:
            logger.error(f"Product mapping failed for {name}: {e}")
            results[name] = {"error": str(e)}
        finally:
            await mp.close()

    return results


async def cmd_download_label(args):
    config = load_config()
    marketplace_name = args.marketplace
    order_id = args.order_id
    base_dir = config.base_dir

    mp_config = config.marketplaces.get(marketplace_name)
    if not mp_config:
        print(f"{marketplace_name} not configured in config.yaml")
        return

    if marketplace_name == "shopee":
        from src.marketplaces.shopee import ShopeeAdapter
        adapter = ShopeeAdapter(mp_config, base_dir)
    elif marketplace_name == "tokopedia":
        from src.marketplaces.tokopedia import TokopediaAdapter
        adapter = TokopediaAdapter(mp_config, base_dir)
    else:
        print(f"Unknown marketplace: {marketplace_name}")
        return

    try:
        label_dir = Path(config.sync.label_output_path)
        if not label_dir.is_absolute():
            label_dir = base_dir / label_dir
        label_dir.mkdir(parents=True, exist_ok=True)

        filepath = await adapter.download_shipping_label(order_id, label_dir)
        if filepath:
            print(f"Label downloaded: {filepath}")
        else:
            print(f"Failed to download label for {order_id}")
    except Exception as e:
        print(f"Error: {e}")
    finally:
        await adapter.close()


async def cmd_serve(args):
    config = load_config()
    init_db(config.base_dir / "data" / "marketplace.db")
    await get_db()

    engine = SyncEngine(config)
    set_engine(engine)
    set_server_config(config)

    import uvicorn
    logger.info(f"Starting webhook server on {config.server.host}:{config.server.port}")
    if config.sync.dry_run:
        logger.info("Sync is in DRY RUN mode. Webhook-triggered syncs will not modify marketplaces.")
    if config.server.api_key:
        logger.info("API key authentication is enabled.")
    else:
        logger.warning("No API key configured. Webhook endpoints are open!")

    try:
        config_uvicorn = uvicorn.Config(
            app,
            host=config.server.host,
            port=config.server.port,
            log_level="info",
        )
        server = uvicorn.Server(config_uvicorn)
        await server.serve()
    finally:
        await engine.close()


def cli():
    parser = argparse.ArgumentParser(description="Marketplace Integrator for Odoo + Shopee + Tokopedia")
    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    login_parser = subparsers.add_parser("login", help="Login to a marketplace (opens browser)")
    login_parser.add_argument("marketplace", choices=["shopee", "tokopedia"], help="Marketplace to login")

    sync_parser = subparsers.add_parser("sync", help="Run sync operation")
    sync_parser.add_argument(
        "type",
        choices=["all", "stock", "price", "orders", "map"],
        help="Type of sync to perform",
    )
    sync_parser.add_argument(
        "-m", "--marketplace",
        choices=["shopee", "tokopedia"],
        help="Target specific marketplace (default: all enabled)",
    )
    sync_parser.add_argument(
        "--live",
        action="store_true",
        help="Run in LIVE mode (dry_run=false). Without this flag, all syncs are dry-run.",
    )

    subparsers.add_parser("serve", help="Start webhook server + scheduler")

    label_parser = subparsers.add_parser("download-label", help="Download shipping label for an order")
    label_parser.add_argument("marketplace", choices=["shopee", "tokopedia"], help="Marketplace")
    label_parser.add_argument("order_id", help="Marketplace order ID")

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        return

    if args.command == "login":
        asyncio.run(cmd_login(args))
    elif args.command == "sync":
        asyncio.run(cmd_sync(args))
    elif args.command == "serve":
        asyncio.run(cmd_serve(args))
    elif args.command == "download-label":
        asyncio.run(cmd_download_label(args))


if __name__ == "__main__":
    cli()
