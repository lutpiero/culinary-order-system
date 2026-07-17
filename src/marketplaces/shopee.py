from __future__ import annotations

import asyncio
import re

from loguru import logger
from playwright.async_api import Page

from src.marketplaces.base import BaseMarketplace, MarketOrder, MarketProduct


class ShopeeAdapter(BaseMarketplace):
    name = "shopee"

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._product_cache: dict[str, dict] = {}

    async def login_interactive(self) -> bool:
        self._save_on_close = False
        ctx = await self._ensure_browser(headless=False)
        page = await ctx.new_page()
        await page.goto(f"{self.config.seller_center_url}/portal/sale")
        logger.info("[shopee] Browser opened. Please log in manually.")
        logger.info("[shopee] Waiting for redirect to seller portal (up to 5 minutes)...")
        try:
            await page.wait_for_url("**/portal/**", timeout=300_000)
            await asyncio.sleep(3)
            self._save_on_close = True
            await self._save_session()
            logger.success("[shopee] Login successful! Session saved.")
            return True
        except Exception:
            logger.error("[shopee] Login timed out or failed. Existing session NOT modified.")
            return False
        finally:
            await self.close()

    async def _check_login_needed(self, page: Page) -> bool:
        url = page.url
        if "login" in url or "passport" in url or "sso" in url:
            return True
        return False

    async def get_products(self) -> list[MarketProduct]:
        page = await self._get_page()

        async def _on_response(resp):
            if "search_product_list" not in resp.url:
                return
            try:
                body = await resp.json()
                for p in body.get("data", {}).get("products", []):
                    pid = str(p["id"])
                    models = p.get("model_list", [])
                    default_model = next(
                        (m for m in models if m.get("is_default")),
                        models[0] if models else {},
                    )
                    self._product_cache[pid] = {
                        "model_id": default_model.get("id", 0) if default_model else 0,
                        "stock": p.get("stock_detail", {}).get("total_available_stock", 0),
                        "price": float(
                            p.get("price_detail", {}).get("selling_price_min", "0")
                        ),
                    }
            except Exception:
                pass

        page.on("response", _on_response)
        await page.goto(
            f"{self.config.seller_center_url}/portal/product/list/all?operationSortBy=recommend_v2"
        )
        await asyncio.sleep(10)
        page.remove_listener("response", _on_response)

        if await self._check_login_needed(page):
            logger.error("[shopee] Session expired. Run 'login shopee' to re-authenticate.")
            return []

        products: list[MarketProduct] = []
        try:
            raw = await page.evaluate("""() => {
                const rows = document.querySelectorAll('.eds-table__row');
                const results = [];
                for (const row of rows) {
                    try {
                        const checkbox = row.querySelector('input[type="checkbox"]');
                        const productId = checkbox ? checkbox.name : '';
                        const img = row.querySelector('.product-image img');
                        const name = img ? img.alt : '';
                        const skuEl = row.querySelector('.product-sku');
                        const skuText = skuEl ? skuEl.textContent.trim() : '';
                        const sku = skuText.replace(/^SKU\\s*Induk:\\s*/, '').trim();
                        const priceEl = row.querySelector('.list-view-price');
                        const price = priceEl ? priceEl.textContent.trim() : '';
                        const stockEl = row.querySelector('.list-view-stock');
                        const stock = stockEl ? stockEl.textContent.trim() : '';
                        if (productId && name) {
                            results.push({ productId, name, sku, price, stock });
                        }
                    } catch(e) {}
                }
                return results;
            }""")

            for item in raw:
                pid = item["productId"]
                api_info = self._product_cache.get(pid, {})
                price = api_info.get("price", self._parse_price(item["price"]))
                stock = api_info.get("stock", self._parse_stock(item["stock"]))
                url = f"{self.config.seller_center_url}/portal/product/{pid}"
                products.append(
                    MarketProduct(
                        product_id=pid,
                        name=item["name"].strip(),
                        sku=item["sku"].strip(),
                        price=price,
                        stock=stock,
                        model_id=api_info.get("model_id", 0),
                        url=url,
                    )
                )
            logger.info(f"[shopee] Found {len(products)} products")
        except Exception as e:
            logger.error(f"[shopee] Failed to load product list: {e}")

        return products

    async def _api_update(
        self, product_id: str, field: str, value: float | int
    ) -> bool:
        model_id = self._product_cache.get(product_id, {}).get("model_id", 0)
        if not model_id:
            logger.error(f"[shopee] No model_id cached for product {product_id}")
            return False

        page = await self._get_page()
        try:
            result = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch(
                            "/api/v3/product/update_product_info_for_quick_edit",
                            {
                                method: "POST",
                                headers: {
                                    "Content-Type": "application/json",
                                    "caller-source": "local_pc",
                                    "sc-fe-ver": "21.155649",
                                    "locale": "id",
                                },
                                body: JSON.stringify({
                                    product_id: args.pid,
                                    model_id: args.mid,
                                    [args.field]: args.value,
                                }),
                            }
                        );
                        return await r.json();
                    } catch (e) {
                        return {code: -1, message: e.message};
                    }
                }""",
                {
                    "pid": int(product_id),
                    "mid": int(model_id),
                    "field": field,
                    "value": value,
                },
            )
            if result.get("code") == 0:
                return True
            logger.error(
                f"[shopee] API error for {product_id}: "
                f"code={result.get('code')} msg={result.get('message')}"
            )
            return False
        except Exception as e:
            logger.error(f"[shopee] API request failed for {product_id}: {e}")
            return False

    async def update_stock(
        self, marketplace_product_id: str, sku: str, qty: int
    ) -> bool:
        if not self._product_cache:
            logger.error("[shopee] Product cache empty. Run get_products() first.")
            return False

        result = await self._api_update(marketplace_product_id, "stock", qty)
        if result:
            logger.success(
                f"[shopee] Stock updated for {marketplace_product_id}: {qty}"
            )
        return result

    async def update_price(
        self, marketplace_product_id: str, sku: str, price: float
    ) -> bool:
        if not self._product_cache:
            logger.error("[shopee] Product cache empty. Run get_products() first.")
            return False

        result = await self._api_update(marketplace_product_id, "price", price)
        if result:
            logger.success(
                f"[shopee] Price updated for {marketplace_product_id}: Rp{price:,.0f}"
            )
        return result

    async def get_orders(self, status: str = "new") -> list[MarketOrder]:
        status_map = {
            "new": "3",
            "shipped": "5",
            "completed": "6",
            "cancelled": "4",
        }
        status_code = status_map.get(status, "3")
        page = await self._get_page()
        await page.goto(
            f"{self.config.seller_center_url}/portal/sale?status={status_code}"
        )
        await asyncio.sleep(8)
        if await self._check_login_needed(page):
            logger.error("[shopee] Session expired.")
            return []

        orders: list[MarketOrder] = []
        try:
            raw = await page.evaluate("""() => {
                const rows = document.querySelectorAll('.eds-table__row');
                const results = [];
                for (const row of rows) {
                    try {
                        const cells = row.querySelectorAll('td');
                        let orderId = '', buyer = '', amount = '';
                        for (const cell of cells) {
                            const text = cell.textContent.trim();
                            if (/^\\d{4,}$/.test(text) && !orderId) {
                                orderId = text;
                            }
                            if (text.includes('Rp') && !amount) {
                                amount = text;
                            }
                        }
                        const buyerEl = row.querySelector('[class*="buyer"], [class*="username"]');
                        buyer = buyerEl ? buyerEl.textContent.trim() : '';
                        if (orderId) {
                            results.push({ orderId, buyer, amount });
                        }
                    } catch(e) {}
                }
                return results;
            }""")

            for item in raw:
                orders.append(
                    MarketOrder(
                        order_id=item["orderId"],
                        buyer_name=item["buyer"],
                        total_amount=self._parse_price(item["amount"]),
                        status=status,
                    )
                )
            logger.info(f"[shopee] Found {len(orders)} orders (status={status})")
        except Exception as e:
            logger.error(f"[shopee] Failed to load orders: {e}")

        return orders

    async def create_product(self, product: MarketProduct) -> str | None:
        logger.warning(
            "[shopee] create_product not yet implemented - use Seller Center UI"
        )
        return None

    @staticmethod
    def _parse_price(text: str) -> float:
        if not text:
            return 0.0
        cleaned = re.sub(r"[^\d.,]", "", text)
        cleaned = cleaned.replace(".", "").replace(",", ".")
        try:
            return float(cleaned)
        except ValueError:
            return 0.0

    @staticmethod
    def _parse_stock(text: str) -> int:
        if not text:
            return 0
        match = re.search(r"(\d+)", text)
        return int(match.group(1)) if match else 0
