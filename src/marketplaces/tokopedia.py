from __future__ import annotations

import asyncio
import re

from loguru import logger
from playwright.async_api import Page

from src.marketplaces.base import BaseMarketplace, MarketOrder, MarketProduct


class TokopediaAdapter(BaseMarketplace):
    name = "tokopedia"

    _product_cache: dict[str, dict] = {}

    async def login_interactive(self) -> bool:
        self._save_on_close = False
        ctx = await self._ensure_browser(headless=False)
        page = await ctx.new_page()
        await page.goto(f"{self.config.seller_center_url}/account/login")
        logger.info("[tokopedia] Browser opened. Please log in manually.")
        logger.info("[tokopedia] Waiting for redirect to seller portal (up to 5 minutes)...")
        try:
            for _ in range(300):
                await asyncio.sleep(1)
                url = page.url
                if "/account/login" not in url and "seller-id.tokopedia.com" in url:
                    logger.info(f"[tokopedia] Redirected to: {url}")
                    break
            else:
                raise TimeoutError("Login timeout - no redirect detected")
            await asyncio.sleep(3)
            self._save_on_close = True
            await self._save_session()
            logger.success("[tokopedia] Login successful! Session saved.")
            return True
        except Exception:
            logger.error("[tokopedia] Login timed out or failed. Existing session NOT modified.")
            return False
        finally:
            await self.close()

    async def _check_login_needed(self, page: Page) -> bool:
        url = page.url
        if "login" in url or "account" in url and "login" in url:
            return True
        return False

    async def get_products(self) -> list[MarketProduct]:
        page = await self._get_page()

        captured_data = {}

        async def _on_response(resp):
            if "products/list" in resp.url and resp.status == 200:
                try:
                    body = await resp.json()
                    if body.get("code") == 0:
                        captured_data["list"] = body
                except Exception:
                    pass

        page.on("response", _on_response)

        await page.goto(f"{self.config.seller_center_url}/product/manage", timeout=20000)
        await asyncio.sleep(12)

        if await self._check_login_needed(page):
            logger.error("[tokopedia] Session expired. Run 'login tokopedia' to re-authenticate.")
            return []

        products: list[MarketProduct] = []

        data = captured_data.get("list")
        if not data:
            try:
                raw = await page.evaluate("""async () => {
                    const r = await fetch("/api/v1/product/local/products/list?tab_id=2&page_number=1&page_size=50&sku_number=1&product_sort_fields=3&product_sort_types=0&same_product_page_size=3&is_need_target_stock=true&is_need_clearance_tag=true&locale=id-ID&language=id");
                    return await r.json();
                }""")
                if raw.get("code") == 0:
                    data = raw
            except Exception as e:
                logger.error(f"[tokopedia] Failed to fetch products: {e}")

        if data and data.get("data", {}).get("products"):
            for item in data["data"]["products"]:
                try:
                    pid = str(item.get("product_id", ""))
                    name = item.get("product_name", "")
                    skus = item.get("skus", [])
                    if not skus:
                        continue

                    sku_data = skus[0]
                    sku_id = str(sku_data.get("id", ""))
                    seller_sku = sku_data.get("seller_sku", "")

                    stock = 0
                    for q in sku_data.get("quantities", []):
                        stock += q.get("total_quantity", 0) or 0

                    price = 0.0
                    bp = sku_data.get("base_price", {})
                    raw_price = bp.get("sale_price", "0")
                    try:
                        price = float(raw_price)
                    except (ValueError, TypeError):
                        price = self._parse_price(str(raw_price))

                    self._product_cache[pid] = {
                        "sku_id": sku_id,
                        "seller_sku": seller_sku,
                        "stock": stock,
                        "price": price,
                        "warehouse_id": (
                            sku_data["quantities"][0]["warehouse_id"]
                            if sku_data.get("quantities")
                            else ""
                        ),
                    }

                    url = f"{self.config.seller_center_url}/product/manage"
                    products.append(
                        MarketProduct(
                            product_id=pid,
                            name=name.strip(),
                            sku=seller_sku.strip(),
                            price=price,
                            stock=stock,
                            url=url,
                        )
                    )
                except Exception as exc:
                    logger.warning(f"[tokopedia] Failed to parse product: {exc}")

        logger.info(f"[tokopedia] Found {len(products)} products")
        return products

    async def _api_update_stock(
        self, marketplace_product_id: str, qty: int
    ) -> bool:
        """Set absolute stock using delta-based set_stock API.

        The Tokopedia ``/api/v1/product/stock/alert/set_stock`` endpoint accepts
        a *delta* ``quantity`` (how much to add/subtract from the current stock).
        We compute the delta from the cached current stock.
        """
        cache = self._product_cache.get(marketplace_product_id)
        if not cache:
            logger.error(
                f"[tokopedia] No cache for product {marketplace_product_id}. "
                "Run get_products() first."
            )
            return False

        sku_id = cache["sku_id"]
        wh_id = cache["warehouse_id"]
        current_stock = cache["stock"]
        delta = qty - current_stock

        if delta == 0:
            logger.info(f"[tokopedia] Stock already {qty} for {marketplace_product_id}")
            return True

        page = await self._get_page()
        try:
            result = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch("/api/v1/product/stock/alert/set_stock", {
                            method: "POST",
                            headers: {"Content-Type": "application/json"},
                            body: JSON.stringify({
                                "product_id": args.pid,
                                "sku_id": args.sku_id,
                                "warehouse_quantity_list": [{
                                    "warehouse_id": args.wh_id,
                                    "quantity": args.delta
                                }]
                            })
                        });
                        return await r.json();
                    } catch(e) { return {e: e.message}; }
                }""",
                {
                    "pid": marketplace_product_id,
                    "sku_id": sku_id,
                    "wh_id": wh_id,
                    "delta": delta,
                },
            )

            if result.get("code") == 0:
                cache["stock"] = qty
                logger.success(
                    f"[tokopedia] Stock updated for {marketplace_product_id}: "
                    f"{current_stock} -> {qty} (delta={delta})"
                )
                return True
            else:
                msg = result.get("message", "unknown error")
                logger.error(
                    f"[tokopedia] Stock update failed for {marketplace_product_id}: {msg}"
                )
                return False
        except Exception as e:
            logger.error(f"[tokopedia] Stock update exception: {e}")
            return False

    async def _api_update_price(
        self, marketplace_product_id: str, price: float
    ) -> bool:
        """Attempt to update price via partial/edit.

        Note: The ``partial/edit`` endpoint currently returns ``code: 0``
        but does NOT persist price changes on the server side. This is a
        known Tokopedia seller-center limitation. Price updates are logged
        as attempted but not guaranteed.
        """
        cache = self._product_cache.get(marketplace_product_id)
        if not cache:
            logger.error(
                f"[tokopedia] No cache for product {marketplace_product_id}. "
                "Run get_products() first."
            )
            return False

        sku_id = cache["sku_id"]
        price_str = str(int(price))

        page = await self._get_page()
        try:
            result = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch("/api/v1/product/local/product/partial/edit", {
                            method: "POST",
                            headers: {"Content-Type": "application/json"},
                            body: JSON.stringify({
                                "product_id": args.pid,
                                "skus": [{
                                    "id": args.sku_id,
                                    "base_price": {"region": "ID", "sale_price": args.price}
                                }]
                            })
                        });
                        return await r.json();
                    } catch(e) { return {e: e.message}; }
                }""",
                {
                    "pid": marketplace_product_id,
                    "sku_id": sku_id,
                    "price": price_str,
                },
            )

            if result.get("code") == 0:
                cache["price"] = price
                logger.warning(
                    f"[tokopedia] Price update submitted for {marketplace_product_id}: "
                    f"Rp{price:,.0f} (server acknowledged, may not persist — verify manually)"
                )
                return True
            else:
                msg = result.get("message", "unknown error")
                logger.error(
                    f"[tokopedia] Price update failed for {marketplace_product_id}: {msg}"
                )
                return False
        except Exception as e:
            logger.error(f"[tokopedia] Price update exception: {e}")
            return False

    async def update_stock(self, marketplace_product_id: str, sku: str, qty: int) -> bool:
        if not self._product_cache:
            await self.get_products()
        return await self._api_update_stock(marketplace_product_id, qty)

    async def update_price(self, marketplace_product_id: str, sku: str, price: float) -> bool:
        if not self._product_cache:
            await self.get_products()
        return await self._api_update_price(marketplace_product_id, price)

    async def get_orders(self, status: str = "new") -> list[MarketOrder]:
        status_map = {
            "new": "101",
            "processed": "101",
            "shipped": "401",
            "completed": "601",
            "cancelled": "701",
        }
        search_tab = status_map.get(status, "101")
        page = await self._get_page()
        await page.goto(f"{self.config.seller_center_url}/order?shop_region=ID&status={status}")
        await asyncio.sleep(12)
        if await self._check_login_needed(page):
            logger.error("[tokopedia] Session expired.")
            return []

        orders: list[MarketOrder] = []
        try:
            raw = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch("/api/fulfillment/order/list", {
                            method: "POST",
                            headers: {"Content-Type": "application/json"},
                            body: JSON.stringify({
                                search_condition: {
                                    condition_list: {
                                        search_tab: {value: [args.tab]},
                                    },
                                },
                                sort_info: "11",
                                page_number: 1,
                                page_size: 50,
                            }),
                        });
                        return await r.json();
                    } catch (e) {
                        return {code: -1, message: e.message};
                    }
                }""",
                {"tab": search_tab},
            )

            if raw.get("code") != 0:
                logger.error(f"[tokopedia] Order API error: {raw.get('message', raw.get('msg', 'unknown'))}")
                return []

            for main_order in raw.get("data", {}).get("main_orders", []):
                try:
                    order_id = main_order.get("main_order_id", "")

                    trade = main_order.get("trade_order_module", {})
                    created_at = trade.get("create_time", "")

                    items = []
                    for sku in main_order.get("sku_module", []):
                        sku_price = sku.get("sku_unit_price", {})
                        unit_price = float(sku_price.get("price_val", "0") or "0")
                        qty = sku.get("quantity", 1)
                        items.append({
                            "marketplace_product_id": str(sku.get("product_id", "")),
                            "marketplace_sku": sku.get("seller_sku_name", ""),
                            "product_name": sku.get("product_name", ""),
                            "qty": qty,
                            "price": unit_price,
                            "sku_id": str(sku.get("sku_id", "")),
                        })

                    total_amount = sum(i["price"] * i["qty"] for i in items)
                    shipping = float(trade.get("shipping_fee", {}).get("price_val", "0") or "0")
                    total_amount += shipping

                    buyer_name = f"Tokopedia Buyer ({order_id})"

                    addr_module = main_order.get("address_module", {})
                    buyer_phone = ""
                    shipping_addr = {}
                    if addr_module:
                        buyer_phone = addr_module.get("phone", "")
                        shipping_addr = {
                            "name": addr_module.get("name", buyer_name),
                            "phone": buyer_phone,
                            "address": addr_module.get("address", addr_module.get("street", "")),
                            "city": addr_module.get("city", ""),
                            "state": addr_module.get("province", ""),
                            "district": addr_module.get("district", ""),
                            "postal_code": addr_module.get("zip_code", ""),
                        }
                        if not buyer_name or buyer_name.startswith("Tokopedia Buyer"):
                            buyer_name = addr_module.get("name", buyer_name)

                    courier_name = ""
                    tracking_number = ""
                    shipping_etd = ""
                    logistics = main_order.get("logistics_module", main_order.get("logistic", {}))
                    if logistics:
                        courier_name = logistics.get("logistic_name", logistics.get("courier_name", ""))
                        tracking_number = logistics.get("tracking_number", logistics.get("resi", ""))
                        shipping_etd = logistics.get("etd", "")

                    orders.append(
                        MarketOrder(
                            order_id=order_id,
                            buyer_name=buyer_name,
                            buyer_phone=buyer_phone,
                            items=items,
                            total_amount=total_amount,
                            status=status,
                            created_at=created_at,
                            shipping_address=shipping_addr,
                            courier_name=courier_name,
                            tracking_number=tracking_number,
                            shipping_cost=shipping,
                            shipping_etd=shipping_etd,
                            raw=main_order,
                        )
                    )
                except Exception as e:
                    logger.warning(f"[tokopedia] Failed to parse order: {e}")

            logger.info(f"[tokopedia] Found {len(orders)} orders (status={status})")
        except Exception as e:
            logger.error(f"[tokopedia] Failed to load orders: {e}")

        return orders

    async def create_product(self, product: MarketProduct) -> str | None:
        logger.warning("[tokopedia] create_product not yet implemented")
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
