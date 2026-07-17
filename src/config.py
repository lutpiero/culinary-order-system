from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml
from dotenv import load_dotenv
from pydantic import BaseModel, Field


class OdooConfig(BaseModel):
    url: str = "http://localhost:8069"
    db: str = "odoo_db"
    username: str = "admin"
    password: str = ""
    stock_location: str = "WH/Stock"


class MarketplaceConfig(BaseModel):
    enabled: bool = True
    seller_center_url: str = ""
    session_file: str = ""
    sync_interval_minutes: int = 10
    request_delay_seconds: float = 2.0


class SyncConfig(BaseModel):
    dry_run: bool = True
    stock: bool = True
    price: bool = True
    orders: bool = True
    products: bool = True
    conflict_resolution: str = "odoo_wins"
    min_price: float = 1000.0
    max_stock: int = 99999
    min_stock_floor: int = 0
    price_change_threshold: float = 500.0
    stock_change_threshold: int = 1
    skip_stock_zero: bool = True
    max_retries: int = 3


class ServerConfig(BaseModel):
    host: str = "127.0.0.1"
    port: int = 8100
    api_key: str = ""


class AppConfig(BaseModel):
    odoo: OdooConfig = Field(default_factory=OdooConfig)
    marketplaces: dict[str, MarketplaceConfig] = Field(default_factory=dict)
    sync: SyncConfig = Field(default_factory=SyncConfig)
    server: ServerConfig = Field(default_factory=ServerConfig)
    base_dir: Path = Field(default_factory=lambda: Path.cwd())


def _resolve_env_vars(obj: Any) -> Any:
    if isinstance(obj, str) and obj.startswith("${") and obj.endswith("}"):
        var_name = obj[2:-1]
        return os.environ.get(var_name, obj)
    if isinstance(obj, dict):
        return {k: _resolve_env_vars(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_resolve_env_vars(v) for v in obj]
    return obj


def load_config(config_path: str | Path | None = None) -> AppConfig:
    load_dotenv()

    if config_path is None:
        config_path = Path.cwd() / "config.yaml"
    else:
        config_path = Path(config_path)

    if not config_path.exists():
        return AppConfig()

    with open(config_path) as f:
        raw = yaml.safe_load(f) or {}

    raw = _resolve_env_vars(raw)

    marketplaces = {}
    for name, mp_data in raw.get("marketplaces", {}).items():
        mp_data.setdefault("session_file", f"sessions/{name}_state.json")
        marketplaces[name] = MarketplaceConfig(**mp_data)

    return AppConfig(
        odoo=OdooConfig(**raw.get("odoo", {})),
        marketplaces=marketplaces,
        sync=SyncConfig(**raw.get("sync", {})),
        server=ServerConfig(**raw.get("server", {})),
        base_dir=config_path.parent,
    )


_config: AppConfig | None = None


def get_config() -> AppConfig:
    global _config
    if _config is None:
        _config = load_config()
    return _config


def set_config(config: AppConfig) -> None:
    global _config
    _config = config
