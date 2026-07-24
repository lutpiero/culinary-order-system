from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path

from loguru import logger
from playwright.async_api import Browser, BrowserContext, async_playwright

from src.config import MarketplaceConfig


@dataclass
class MarketProduct:
    product_id: str
    name: str
    sku: str = ""
    price: float = 0.0
    stock: int = 0
    model_id: int = 0
    variants: list[dict] = field(default_factory=list)
    url: str = ""
    status: str = "active"


@dataclass
class MarketOrder:
    order_id: str
    buyer_name: str = ""
    buyer_phone: str = ""
    buyer_email: str = ""
    items: list[dict] = field(default_factory=list)
    total_amount: float = 0.0
    status: str = ""
    created_at: str = ""
    shipping_address: dict = field(default_factory=dict)
    courier_name: str = ""
    tracking_number: str = ""
    shipping_cost: float = 0.0
    shipping_etd: str = ""
    raw: dict = field(default_factory=dict)


class BaseMarketplace(ABC):
    name: str = "base"

    def __init__(self, config: MarketplaceConfig, base_dir: Path) -> None:
        self.config = config
        self._base_dir = base_dir
        self._playwright = None
        self._browser: Browser | None = None
        self._context: BrowserContext | None = None
        self._page = None
        self._save_on_close: bool = True

    def _session_path(self) -> Path:
        path = Path(self.config.session_file)
        if not path.is_absolute():
            path = self._base_dir / path
        return path

    async def _ensure_browser(self, headless: bool = True) -> BrowserContext:
        if self._context:
            return self._context
        self._playwright = await async_playwright().start()
        self._browser = await self._playwright.chromium.launch(headless=headless)
        session_file = self._session_path()
        if session_file.exists():
            self._context = await self._browser.new_context(storage_state=str(session_file))
            logger.info(f"[{self.name}] Loaded existing session from {session_file}")
        else:
            self._context = await self._browser.new_context()
            logger.warning(f"[{self.name}] No session file found at {session_file}. Run 'login' first.")
        return self._context

    async def _save_session(self) -> None:
        if self._context:
            session_file = self._session_path()
            session_file.parent.mkdir(parents=True, exist_ok=True)
            await self._context.storage_state(path=str(session_file))
            logger.info(f"[{self.name}] Session saved to {session_file}")

    async def _get_page(self, headless: bool = True):
        ctx = await self._ensure_browser(headless=headless)
        if self._page and not self._page.is_closed():
            return self._page
        self._page = await ctx.new_page()
        return self._page

    async def close(self) -> None:
        if self._context and self._save_on_close:
            await self._save_session()
        if self._browser:
            await self._browser.close()
        if self._playwright:
            await self._playwright.stop()
        self._context = None
        self._browser = None
        self._playwright = None
        self._page = None

    async def _check_login_needed(self, page) -> bool:
        """Override in subclass to detect login redirects."""
        return False

    @abstractmethod
    async def login_interactive(self) -> bool:
        """Open browser for manual login, save session after."""
        ...

    @abstractmethod
    async def get_products(self) -> list[MarketProduct]:
        ...

    @abstractmethod
    async def update_stock(self, marketplace_product_id: str, sku: str, qty: int) -> bool:
        ...

    @abstractmethod
    async def update_price(self, marketplace_product_id: str, sku: str, price: float) -> bool:
        ...

    @abstractmethod
    async def get_orders(self, status: str = "new") -> list[MarketOrder]:
        ...

    @abstractmethod
    async def create_product(self, product: MarketProduct) -> str | None:
        ...

    async def download_shipping_label(self, order_id: str, output_dir: Path) -> Path | None:
        """Download shipping label PDF for an order. Returns file path or None."""
        return None
