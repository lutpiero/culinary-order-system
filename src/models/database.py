from __future__ import annotations

from datetime import datetime
from pathlib import Path

import aiosqlite

_DB_PATH: Path | None = None
_db: aiosqlite.Connection | None = None

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS product_mapping (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    odoo_product_id INTEGER NOT NULL,
    marketplace TEXT NOT NULL,
    marketplace_product_id TEXT NOT NULL,
    marketplace_sku TEXT,
    marketplace_variant_id TEXT,
    last_synced_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(marketplace, marketplace_product_id)
);

CREATE TABLE IF NOT EXISTS sync_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    marketplace TEXT NOT NULL,
    sync_type TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'success',
    items_synced INTEGER DEFAULT 0,
    items_failed INTEGER DEFAULT 0,
    details TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS order_cache (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    marketplace TEXT NOT NULL,
    marketplace_order_id TEXT NOT NULL,
    odoo_sale_order_id INTEGER,
    buyer_name TEXT,
    total_amount REAL,
    status TEXT NOT NULL,
    raw_data TEXT,
    imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(marketplace, marketplace_order_id)
);

CREATE INDEX IF NOT EXISTS idx_product_mapping_odoo ON product_mapping(odoo_product_id);
CREATE INDEX IF NOT EXISTS idx_product_mapping_marketplace ON product_mapping(marketplace, marketplace_product_id);
CREATE INDEX IF NOT EXISTS idx_sync_log_type ON sync_log(marketplace, sync_type);
CREATE INDEX IF NOT EXISTS idx_order_cache_status ON order_cache(marketplace, status);
"""


def init_db(db_path: Path | str) -> None:
    global _DB_PATH
    _DB_PATH = Path(db_path)


async def get_db() -> aiosqlite.Connection:
    global _db
    if _db is None:
        if _DB_PATH is None:
            raise RuntimeError("Database not initialized. Call init_db() first.")
        _db = await aiosqlite.connect(str(_DB_PATH))
        _db.row_factory = aiosqlite.Row
        await _db.execute("PRAGMA journal_mode=WAL")
        await _db.execute("PRAGMA foreign_keys=ON")
        await _db.executescript(SCHEMA_SQL)
        await _db.commit()
    return _db


async def close_db() -> None:
    global _db
    if _db is not None:
        await _db.close()
        _db = None


async def log_sync(
    marketplace: str,
    sync_type: str,
    status: str = "success",
    items_synced: int = 0,
    items_failed: int = 0,
    details: str | None = None,
) -> None:
    db = await get_db()
    await db.execute(
        "INSERT INTO sync_log (marketplace, sync_type, status, items_synced, items_failed, details) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (marketplace, sync_type, status, items_synced, items_failed, details),
    )
    await db.commit()


async def get_product_mapping(
    marketplace: str, odoo_product_id: int | None = None, marketplace_product_id: str | None = None
) -> list[dict]:
    db = await get_db()
    conditions = ["marketplace = ?"]
    params: list = [marketplace]
    if odoo_product_id is not None:
        conditions.append("odoo_product_id = ?")
        params.append(odoo_product_id)
    if marketplace_product_id is not None:
        conditions.append("marketplace_product_id = ?")
        params.append(marketplace_product_id)
    sql = f"SELECT * FROM product_mapping WHERE {' AND '.join(conditions)}"
    cursor = await db.execute(sql, params)
    rows = await cursor.fetchall()
    return [dict(r) for r in rows]


async def upsert_product_mapping(
    marketplace: str,
    odoo_product_id: int,
    marketplace_product_id: str,
    marketplace_sku: str | None = None,
    marketplace_variant_id: str | None = None,
) -> None:
    db = await get_db()
    await db.execute(
        """
        INSERT INTO product_mapping (odoo_product_id, marketplace, marketplace_product_id, marketplace_sku, marketplace_variant_id, last_synced_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(marketplace, marketplace_product_id) DO UPDATE SET
            odoo_product_id = excluded.odoo_product_id,
            marketplace_sku = excluded.marketplace_sku,
            marketplace_variant_id = excluded.marketplace_variant_id,
            last_synced_at = excluded.last_synced_at
        """,
        (odoo_product_id, marketplace, marketplace_product_id, marketplace_sku, marketplace_variant_id, datetime.utcnow().isoformat()),
    )
    await db.commit()


async def get_order_cache(marketplace: str, marketplace_order_id: str | None = None) -> list[dict]:
    db = await get_db()
    if marketplace_order_id:
        cursor = await db.execute(
            "SELECT * FROM order_cache WHERE marketplace = ? AND marketplace_order_id = ?",
            (marketplace, marketplace_order_id),
        )
    else:
        cursor = await db.execute("SELECT * FROM order_cache WHERE marketplace = ?", (marketplace,))
    rows = await cursor.fetchall()
    return [dict(r) for r in rows]


async def upsert_order_cache(
    marketplace: str,
    marketplace_order_id: str,
    status: str,
    odoo_sale_order_id: int | None = None,
    buyer_name: str | None = None,
    total_amount: float | None = None,
    raw_data: str | None = None,
) -> None:
    db = await get_db()
    await db.execute(
        """
        INSERT INTO order_cache (marketplace, marketplace_order_id, status, odoo_sale_order_id, buyer_name, total_amount, raw_data)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(marketplace, marketplace_order_id) DO UPDATE SET
            status = excluded.status,
            odoo_sale_order_id = excluded.odoo_sale_order_id,
            buyer_name = excluded.buyer_name,
            total_amount = excluded.total_amount,
            raw_data = excluded.raw_data
        """,
        (marketplace, marketplace_order_id, status, odoo_sale_order_id, buyer_name, total_amount, raw_data),
    )
    await db.commit()


async def get_last_sync(marketplace: str, sync_type: str) -> dict | None:
    db = await get_db()
    cursor = await db.execute(
        "SELECT * FROM sync_log WHERE marketplace = ? AND sync_type = ? ORDER BY created_at DESC LIMIT 1",
        (marketplace, sync_type),
    )
    row = await cursor.fetchone()
    return dict(row) if row else None
