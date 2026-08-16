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

        if marketplace_name == "tokopedia":
            filepath = await adapter.download_shipping_label(
                order_id,
                label_dir,
                max_retries=args.max_retries,
                retry_wait_seconds=args.retry_wait,
                headless=not args.headed,
            )
        else:
            filepath = await adapter.download_shipping_label(order_id, label_dir)
        if filepath:
            print(f"Label downloaded: {filepath}")
        else:
            print(f"Failed to download label for {order_id}")
    except Exception as e:
        print(f"Error: {e}")
    finally:
        await adapter.close()


async def cmd_pickup(args):
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
    else:
        print(f"Pickup not yet implemented for {marketplace_name}")
        return

    try:
        results = await adapter.arrange_pickup(order_id)
        if results:
            print(f"Pickup arranged for {len(results)} order(s): {results}")
            try:
                from src.odoo.client import OdooClient
                from src.sync.delivery_sync import confirm_deliveries_after_pickup

                odoo = OdooClient(config.odoo)
                confirmed = await confirm_deliveries_after_pickup(
                    marketplace_name, results, odoo
                )
                if confirmed:
                    print(f"Delivery validated for {len(confirmed)} order(s): {confirmed}")
                else:
                    print("No Odoo deliveries were validated after pickup.")
            except Exception as e:
                print(f"Failed to validate Odoo deliveries after pickup: {e}")
        else:
            print("No pickups were arranged.")
    except Exception as e:
        print(f"Error: {e}")
    finally:
        await adapter.close()


async def cmd_backfill_deliveries(args):
    config = load_config()
    base_dir = config.base_dir

    mp_config = config.marketplaces.get(args.marketplace)
    if not mp_config:
        print(f"{args.marketplace} not configured in config.yaml")
        return

    if args.marketplace == "shopee":
        from src.marketplaces.shopee import ShopeeAdapter
        adapter = ShopeeAdapter(mp_config, base_dir)
    elif args.marketplace == "tokopedia":
        from src.marketplaces.tokopedia import TokopediaAdapter
        adapter = TokopediaAdapter(mp_config, base_dir)
    else:
        print(f"Unknown marketplace: {args.marketplace}")
        return

    try:
        from src.odoo.client import OdooClient
        from src.sync.delivery_sync import backfill_pending_deliveries

        odoo = OdooClient(config.odoo)
        summary = await backfill_pending_deliveries(odoo, adapter)
        print("Backfill summary:", summary)
    except Exception as e:
        print(f"Error: {e}")
    finally:
        await adapter.close()


async def cmd_cleanup(args):
    from scripts.cleanup_marketplace_orders import run_cleanup

    run_cleanup(
        confirm=args.confirm,
        skip_stock_restore=args.skip_stock_restore,
        skip_cache_clear=args.skip_cache_clear,
    )


async def cmd_render_label(args):
    config = load_config()
    pdf_path = Path(args.pdf)
    if not pdf_path.is_absolute():
        pdf_path = config.base_dir / pdf_path
    if not pdf_path.exists():
        print(f"PDF not found: {pdf_path}")
        return

    from src.printing import detect_marketplace, render_label_file, write_print_request

    marketplace = args.marketplace
    if marketplace is None:
        marketplace = detect_marketplace(pdf_path.name) or "shopee"
    order_id = args.order_id or ""
    artifact = pdf_path.resolve()
    png_path = None

    try:
        rendered = render_label_file(
            pdf_path,
            marketplace=marketplace,
            dpi=args.dpi,
            width_px=args.width,
        )
        if rendered:
            png_path, pbm_path, label = rendered
            artifact = pbm_path
            order_id = order_id or label.order_id
    except Exception as e:
        print(f"Render failed: {e}")

    if not order_id:
        print("Could not determine order id from the label; no print request queued.")
        return

    req = write_print_request(artifact, marketplace, order_id, png_path=png_path)
    print(f"Label queued for printing: {req}")
    if png_path:
        print(f"Preview: {png_path}")


async def cmd_serve(args):
    config = load_config()
    init_db(config.base_dir / "data" / "marketplace.db")
    await get_db()

    engine = SyncEngine(config)
    set_engine(engine)
    set_server_config(config)

    host = args.host or config.server.host
    port = args.port or config.server.port

    import uvicorn
    logger.info(f"Starting webhook server on {host}:{port}")
    if config.sync.dry_run:
        logger.info("Sync is in DRY RUN mode. Webhook-triggered syncs will not modify marketplaces.")
    if config.server.api_key:
        logger.info("API key authentication is enabled.")
    else:
        logger.warning("No API key configured. Webhook endpoints are open!")

    try:
        config_uvicorn = uvicorn.Config(
            app,
            host=host,
            port=port,
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

    serve_parser = subparsers.add_parser("serve", help="Start webhook server + scheduler")
    serve_parser.add_argument("--host", default=None, help="Bind host (default: from config.yaml)")
    serve_parser.add_argument("--port", type=int, default=None, help="Bind port (default: from config.yaml)")

    label_parser = subparsers.add_parser("download-label", help="Download shipping label for an order")
    label_parser.add_argument("marketplace", choices=["shopee", "tokopedia"], help="Marketplace")
    label_parser.add_argument("order_id", help="Marketplace order ID")
    label_parser.add_argument(
        "--max-retries", type=int, default=3, help="Retries when the download hits the anti-bot puzzle (default 3)"
    )
    label_parser.add_argument(
        "--retry-wait", type=float, default=180.0, help="Seconds to rest between retries (default 180)"
    )
    label_parser.add_argument(
        "--headed",
        action="store_true",
        help="Open a visible browser window (headless=False) so you can watch the flow / solve the anti-bot puzzle",
    )

    pickup_parser = subparsers.add_parser("pickup", help="Arrange pickup for orders (Shopee)")
    pickup_parser.add_argument("marketplace", choices=["shopee"], help="Marketplace (only shopee supported)")
    pickup_parser.add_argument(
        "order_id", nargs="?", default=None, help="Specific order ID (optional: pickup all eligible if omitted)"
    )

    backfill_parser = subparsers.add_parser(
        "backfill-deliveries",
        help="Validate Odoo deliveries for imported marketplace orders that are stuck in 'Ready to Ship'",
    )
    backfill_parser.add_argument(
        "marketplace", choices=["shopee", "tokopedia"], help="Marketplace to backfill"
    )

    render_parser = subparsers.add_parser(
        "render-label", help="Regenerate a thermal label from a PDF and queue it for printing"
    )
    render_parser.add_argument("pdf", help="Path to the label PDF (absolute or relative to project dir)")
    render_parser.add_argument(
        "--marketplace", choices=["shopee", "tokopedia"], default=None,
        help="Marketplace (default: auto-detected from the filename)",
    )
    render_parser.add_argument("--dpi", type=int, default=None, help="Render DPI (default: from config)")
    render_parser.add_argument(
        "--width", type=int, default=None, help="Output width in px (default: from config print_width_mm)"
    )
    render_parser.add_argument("--order-id", default=None, help="Order id override (default: parsed from the label)")

    cleanup_parser = subparsers.add_parser(
        "cleanup", help="Remove all marketplace orders (sales, deliveries, invoices, stock) from Odoo"
    )
    cleanup_parser.add_argument(
        "--confirm", action="store_true", help="Perform destructive operations (default: dry-run)"
    )
    cleanup_parser.add_argument("--skip-stock-restore", action="store_true", help="Do not restore consumed stock")
    cleanup_parser.add_argument("--skip-cache-clear", action="store_true", help="Do not clear local sqlite cache")

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
    elif args.command == "pickup":
        asyncio.run(cmd_pickup(args))
    elif args.command == "backfill-deliveries":
        asyncio.run(cmd_backfill_deliveries(args))
    elif args.command == "render-label":
        asyncio.run(cmd_render_label(args))
    elif args.command == "cleanup":
        asyncio.run(cmd_cleanup(args))


if __name__ == "__main__":
    cli()
