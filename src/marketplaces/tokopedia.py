from __future__ import annotations

import asyncio
import base64
import re
import sys
from datetime import date, datetime
from pathlib import Path

from loguru import logger
from playwright.async_api import Page
from playwright_stealth import Stealth

from src.marketplaces.base import BaseMarketplace, MarketOrder, MarketProduct, SessionExpiredError
from src.notify import clear_login_alert


def _chromium_ua() -> str:
    """Realistic Chrome UA matching the host OS (a mismatched UA is a bot tell)."""
    platform = (
        "Windows NT 10.0; Win64; x64"
        if sys.platform == "win32"
        else "X11; Linux x86_64"
        if sys.platform.startswith("linux")
        else "Macintosh; Intel Mac OS X 10_15_7"
    )
    return f"Mozilla/5.0 ({platform}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.7827.55 Safari/537.36"


class TokopediaAdapter(BaseMarketplace):
    name = "tokopedia"

    _product_cache: dict[str, dict] = {}

    async def _get_page(self, headless: bool = True):
        page = await super()._get_page(headless=headless)
        if not getattr(self, "_stealth_applied", False):
            self._stealth_applied = True
            try:
                cdp = await self._context.new_cdp_session(page)
                await cdp.send(
                    "Network.setUserAgentOverride",
                    {"userAgent": _chromium_ua()},
                )
                stealth = Stealth(
                    navigator_languages_override=("id-ID", "id", "en-US", "en"),
                    navigator_vendor_override="Google Inc.",
                    chrome_runtime=True,
                    navigator_user_agent=False,
                )
                await stealth.apply_stealth_async(page)
                logger.info("[tokopedia] Stealth mode applied (playwright-stealth)")
            except Exception as e:
                logger.warning(f"[tokopedia] Stealth override failed: {e}")
        return page

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
            clear_login_alert(self.name)
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
            raise SessionExpiredError("Tokopedia session expired - login required")

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
            raise SessionExpiredError("Tokopedia session expired - login required")

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
                    buyer_info = main_order.get("buyer_info_module", {})
                    nickname = buyer_info.get("buyer_nickname", "")
                    if nickname:
                        buyer_name = nickname

                    delivery = main_order.get("delivery_module", [{}])
                    delivery = delivery[0] if delivery else {}
                    courier_name = ""
                    tracking_number = ""
                    shipping_etd = ""
                    shipment_provider = delivery.get("shipment_provider_info", {})
                    if shipment_provider:
                        courier_name = shipment_provider.get("name", "")
                    logistics_service = delivery.get("logistics_service_info", {})
                    if logistics_service:
                        service_name = logistics_service.get("logistics_service_name", "")
                        if service_name and not courier_name:
                            courier_name = service_name
                        elif service_name and courier_name:
                            courier_name = f"{courier_name} ({service_name})"
                    tracking_number = delivery.get("last_tracking_no", "")

                    orders.append(
                        MarketOrder(
                            order_id=order_id,
                            buyer_name=buyer_name,
                            buyer_phone="",
                            items=items,
                            total_amount=total_amount,
                            status=status,
                            created_at=created_at,
                            shipping_address={},
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

    async def is_order_cancelled(self, order_id: str) -> bool | None:
        page = await self._get_page()
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
                        const data = await r.json();
                        if (data.code !== 0) return {code: data.code};
                        const orders = data.data.main_orders || [];
                        for (const o of orders) {
                            if (String(o.main_order_id) === String(args.order_id)) {
                                return {code: 0, cancelled: true};
                            }
                        }
                        return {code: 0, cancelled: false};
                    } catch (e) {
                        return {code: -1, message: e.message};
                    }
                }""",
                {"tab": "701", "order_id": order_id},
            )
            if raw.get("cancelled") is None:
                return None
            if raw.get("cancelled"):
                logger.info(f"[tokopedia] Order {order_id} is cancelled on marketplace")
                return True
            return False
        except Exception as e:
            logger.warning(f"[tokopedia] Could not check cancellation status for {order_id}: {e}")
            return None

    async def download_shipping_label(
        self,
        order_id: str,
        output_dir: Path,
        max_retries: int = 3,
        retry_wait_seconds: float = 180.0,
        headless: bool = True,
    ) -> Path | None:
        """Download a shipping label, resting and retrying when Tokopedia serves
        its anti-bot puzzle (slide-to-verify).

        The puzzle is transient: waiting a while lets it clear before the next
        attempt. A genuinely expired session raises ``SessionExpiredError`` and is
        not retried. Pass ``headless=False`` to watch the browser (useful for
        debugging why the order rows do not render).
        """
        logger.info(f"[tokopedia] Downloading shipping label for order {order_id}")
        for attempt in range(1, max_retries + 1):
            try:
                result = await self._download_shipping_label_once(order_id, output_dir, headless=headless)
            except SessionExpiredError:
                logger.error("[tokopedia] Session expired, cannot download label.")
                raise
            if result is not None:
                return result
            if attempt < max_retries:
                logger.info(
                    f"[tokopedia] label download attempt {attempt}/{max_retries} failed for "
                    f"{order_id}; resting {retry_wait_seconds:.0f}s before retry..."
                )
                await asyncio.sleep(retry_wait_seconds)
        return None

    async def _download_shipping_label_once(
        self, order_id: str, output_dir: Path, headless: bool = True
    ) -> Path | None:
        page = await self._get_page(headless=headless)

        try:
            await page.goto(
                f"{self.config.seller_center_url}/order?order_status[]=1&selected_sort=11&tab=to_ship",
                timeout=20000,
            )
        except Exception:
            pass

        if await self._check_login_needed(page):
            raise SessionExpiredError("Tokopedia session expired - login required")

        if await self._detect_captcha(page):
            logger.warning(
                "[tokopedia] Anti-spam puzzle detected on the order page. "
                "Waiting before retry so the puzzle can clear."
            )
            return None

        pdf_bytes: bytes | None = None

        try:
            pdf_bytes = await self._download_label_via_ui(page, order_id)
        except Exception as e:
            logger.debug(f"[tokopedia] UI label flow failed for {order_id}: {e}")

        if not pdf_bytes:
            try:
                pdf_bytes = await self._download_label_via_api(page, order_id)
            except Exception as e:
                logger.error(f"[tokopedia] API label flow failed for {order_id}: {e}")

        if not pdf_bytes:
            logger.warning(f"[tokopedia] Could not obtain a shipping label for order {order_id}")
            return None

        if not pdf_bytes.startswith(b"%PDF"):
            logger.warning(
                f"[tokopedia] Downloaded data is not a valid PDF for {order_id} "
                f"({len(pdf_bytes)} bytes)."
            )
            return None

        output_dir.mkdir(parents=True, exist_ok=True)
        filename = f"{date.today().isoformat()}_tokopedia_{order_id}.pdf"
        filepath = output_dir / filename
        filepath.write_bytes(pdf_bytes)
        logger.info(f"[tokopedia] Shipping label saved ({len(pdf_bytes)} bytes): {filepath}")
        await self._enqueue_print(filepath, order_id)
        return filepath

    async def _save_debug_screenshot(self, page: Page, tag: str) -> Path | None:
        """Save a full-page screenshot under ``logs/debug`` for troubleshooting.

        Also logs the current URL and a snippet of the visible page text so a
        headless run leaves a usable debug artifact (anti-bot puzzle, login
        redirect, empty tab, etc.).
        """
        try:
            debug_dir = Path(self._base_dir) / "logs" / "debug"
            debug_dir.mkdir(parents=True, exist_ok=True)
            timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            path = debug_dir / f"tokopedia_{tag}_{timestamp}.png"
            await page.screenshot(path=str(path), full_page=True)
            try:
                snippet = (await page.evaluate("() => document.body ? document.body.innerText.slice(0, 500) : ''")).replace("\n", " ")
            except Exception:
                snippet = ""
            logger.info(
                f"[tokopedia] debug screenshot saved: {path} | url={page.url} | text={snippet[:200]!r}"
            )
            return path
        except Exception as e:
            logger.warning(f"[tokopedia] failed to save debug screenshot: {e}")
            return None

    @staticmethod
    async def _detect_captcha(page: Page) -> bool:
        """Best-effort detection of the Tokopedia anti-spam puzzle."""
        try:
            return await page.evaluate(
                """() => {
                    const text = (document.body && document.body.innerText) || "";
                    if (/verifikasi captcha|verify captcha|antispam|puzzle/i.test(text)) return true;
                    for (const f of document.querySelectorAll("iframe")) {
                        const src = f.src || "";
                        if (/captcha|verify/i.test(src)) return true;
                    }
                    return false;
                }"""
            )
        except Exception:
            return False

    async def _download_label_via_ui(self, page: Page, order_id: str) -> bytes | None:
        """Drive the order table UI: row -> 'Tindakan lainnya' -> 'Cetak dokumen'
        -> check 'Label pengiriman (A6)' -> 'Cetak' -> capture the PDF popup."""
        logger.info("[tokopedia] Trying order-table UI label flow...")

        # Wait for the order list rows to render (the list is a lazy SPA).
        # Anchor on the order id text itself rather than CSS classes -- Tokopedia's
        # hashed class names match no stable selector.
        row_found = False
        for _ in range(10):
            await page.wait_for_timeout(2000)
            row_found = await page.evaluate(
                """(args) => {
                    const id = args.orderId;
                    const t = document.body ? (document.body.innerText || "") : "";
                    return t.includes(id);
                }""",
                {"orderId": order_id},
            )
            if row_found:
                break

        if not row_found:
            diag = await page.evaluate(
                """(args) => {
                    const id = args.orderId;
                    const t = document.body ? (document.body.innerText || "") : "";
                    return {
                        orderIdInDom: t.includes(id),
                        hasTindakan: /Tindakan/i.test(t),
                        bodyLen: t.length,
                        url: location.href,
                        snippet: t.slice(0, 300),
                    };
                }""",
                {"orderId": order_id},
            )
            logger.warning(f"[tokopedia] Order ID {order_id} not found in the DOM. Diagnostics: {diag}")
            await self._save_debug_screenshot(page, f"no_order_rows_{order_id}")
            return None

        # 1) click "Tindakan lainnya" inside the order row that contains the id.
        clicked = await page.evaluate(
            """(args) => {
                const id = args.orderId;
                // smallest element containing the order id (its cell in the row)
                let leaf = null;
                for (const el of document.querySelectorAll("body *")) {
                    const t = el.textContent || "";
                    if (t.includes(id) && (!leaf || t.length < (leaf.textContent || "").length)) leaf = el;
                }
                if (!leaf) return false;
                // walk up from the id to the row that also holds an action button
                let el = leaf;
                while (el && el !== document.body) {
                    const t = el.textContent || "";
                    if (t.length < 6000) {
                        const btn = [...el.querySelectorAll("button, [role=button]")].find(b => {
                            const bt = (b.innerText || b.textContent || "").trim();
                            return /Tindakan lainnya/i.test(bt) && bt.length < 40;
                        });
                        if (btn) { btn.click(); return true; }
                    }
                    el = el.parentElement;
                }
                return false;
            }""",
            {"orderId": order_id},
        )
        if not clicked:
            logger.warning(
                "[tokopedia] 'Tindakan lainnya' button not found near the order id; the UI may have changed."
            )
            await self._save_debug_screenshot(page, f"no_tindakan_button_{order_id}")
            return None
        await page.wait_for_timeout(1500)

        # 2) click "Cetak dokumen" in the expanded menu.
        clicked = await page.evaluate(
            """() => {
                const items = [...document.querySelectorAll("button, a, li, [role=menuitem], div")];
                const el = items.find(b => {
                    const t = (b.innerText || b.textContent || "").trim();
                    return /^Cetak dokumen$/i.test(t) || /Cetak dokumen/i.test(t) && t.length < 40;
                });
                if (el) { el.click(); return true; }
                return false;
            }""",
        )
        if not clicked:
            logger.info("[tokopedia] 'Cetak dokumen' menu item not found.")
            return None
        await page.wait_for_timeout(2500)

        # 3) in the modal, check the "Label pengiriman" checkbox.
        checked = await page.evaluate(
            """() => {
                const labels = [...document.querySelectorAll("label, .p-checkbox, [class*=checkbox], div")];
                const target = labels.find(l => {
                    const t = (l.innerText || l.textContent || "").trim();
                    return /Label pengiriman/i.test(t) && t.length < 60;
                });
                if (!target) return false;
                const input = target.querySelector("input[type=checkbox]") || target.closest("label") && target.closest("label").querySelector("input[type=checkbox]");
                if (input) {
                    if (!input.checked) input.click();
                    return true;
                }
                target.click();
                return true;
            }""",
        )
        if not checked:
            logger.info("[tokopedia] 'Label pengiriman' checkbox not found in modal.")
            return None
        await page.wait_for_timeout(1000)

        # 4) capture the PDF the "Cetak" button opens (new window / download).
        pdf_event = asyncio.Event()
        pdf_data: dict | None = None
        ctx = page.context

        async def on_pdf_page(new_page):
            nonlocal pdf_data
            try:
                await new_page.wait_for_load_state("networkidle", timeout=15000)
            except Exception:
                pass
            for attempt in range(15):
                await asyncio.sleep(2)
                result = await new_page.evaluate(
                    """async () => {
                        for (const f of document.querySelectorAll('iframe')) {
                            if (f.src && f.src.startsWith('blob:')) {
                                try {
                                    const resp = await fetch(f.src);
                                    const blob = await resp.blob();
                                    const reader = new FileReader();
                                    return await new Promise((resolve) => {
                                        reader.onload = () => resolve({
                                            size: blob.size,
                                            b64: reader.result.split(',')[1],
                                        });
                                        reader.readAsDataURL(blob);
                                    });
                                } catch (e) { return {error: String(e)}; }
                            }
                        }
                        const url = location.href;
                        if (/\\.pdf($|\\?)/i.test(url)) {
                            try {
                                const resp = await fetch(url);
                                const blob = await resp.blob();
                                const reader = new FileReader();
                                return await new Promise((resolve) => {
                                    reader.onload = () => resolve({size: blob.size, b64: reader.result.split(',')[1]});
                                    reader.readAsDataURL(blob);
                                });
                            } catch (e) { return {error: String(e)}; }
                        }
                        return null;
                    }"""
                )
                if result is not None:
                    pdf_data = result
                    pdf_event.set()
                    await new_page.close()
                    return
            pdf_data = {"error": "no PDF found in popup after polling"}
            pdf_event.set()

        ctx.on("page", on_pdf_page)
        try:
            clicked = await page.evaluate(
                """() => {
                    const modals = [...document.querySelectorAll("[class*=modal], [class*=dialog], [class*=popup], [class*=drawer]")];
                    const scope = modals[modals.length - 1] || document;
                    const btns = [...scope.querySelectorAll("button")];
                    const btn = btns.find(b => /^Cetak$/i.test((b.innerText || b.textContent || "").trim()));
                    if (btn) { btn.click(); return true; }
                    return false;
                }""",
            )
            if not clicked:
                logger.info("[tokopedia] 'Cetak' button not found in modal.")
                return None

            await asyncio.wait_for(pdf_event.wait(), timeout=45)
        except TimeoutError:
            logger.warning("[tokopedia] Timed out waiting for label PDF popup.")
        finally:
            ctx.remove_listener("page", on_pdf_page)

        if pdf_data and pdf_data.get("size", 0) > 1000 and pdf_data.get("b64"):
            return base64.b64decode(pdf_data["b64"])
        if pdf_data and pdf_data.get("error"):
            logger.info(f"[tokopedia] Popup capture error: {pdf_data['error']}")
        return None

    async def _download_label_via_api(self, page: Page, order_id: str) -> bytes | None:
        """Resolve the order to its fulfill unit(s), then call the same
        ``shipping_doc/generate`` endpoint the 'Cetak' button uses (A6 label,
        content type = shipping label) and download the returned PDF URL."""
        logger.info("[tokopedia] Falling back to shipping_doc/generate API...")

        resolved = await page.evaluate(
            """async (args) => {
                const target = String(args.id);
                for (let page_number = 1; page_number <= 10; page_number++) {
                    try {
                        const r = await fetch("/api/fulfillment/order/list", {
                            method: "POST",
                            headers: {"Content-Type": "application/json"},
                            body: JSON.stringify({
                                search_condition: { condition_list: { search_tab: { value: ["101"] } } },
                                sort_info: "11",
                                page_number: page_number,
                                page_size: 50,
                            }),
                        });
                        const j = await r.json();
                        if (j.code !== 0) continue;
                        const orders = (j.data && j.data.main_orders) || [];
                        if (orders.length === 0) return {error: "order not found"};
                        for (const o of orders) {
                            if (String(o.main_order_id) !== target) continue;
                            const fu = (o.fulfill_unit_id_mapper || [])
                                .map(m => m.fulfill_unit_id)
                                .filter(Boolean);
                            return {fulfill_unit_ids: fu};
                        }
                    } catch (e) { return {error: String(e)}; }
                }
                return {error: "order not found in the first 10 pages"};
            }""",
            {"id": order_id},
        )

        if not resolved or resolved.get("error") or not resolved.get("fulfill_unit_ids"):
            logger.warning(
                f"[tokopedia] Could not resolve order {order_id}: "
                f"{(resolved or {}).get('error', 'no fulfill unit ids')}"
            )
            return None

        fulfill_unit_ids = resolved["fulfill_unit_ids"]

        gen = await page.evaluate(
            """async (args) => {
                try {
                    const r = await fetch("/api/v1/fulfillment/shipping_doc/generate", {
                        method: "POST",
                        headers: {"Content-Type": "application/json"},
                        body: JSON.stringify({
                            fulfill_unit_id_list: args.fulfill_unit_ids,
                            content_type_list: [1],
                            template_type: 0,
                            op_scene: 2,
                            file_prefix: "Shipping label",
                            request_time: Date.now(),
                            print_source: 101,
                            print_option: {tmpl: 0, template_size: 0, layout: [0]},
                        }),
                    });
                    const j = await r.json();
                    return {code: j.code, message: j.message || j.msg, data: j.data || {}};
                } catch (e) {
                    return {code: -2, message: String(e), data: {}};
                }
            }""",
            {"fulfill_unit_ids": fulfill_unit_ids},
        )

        doc_url = (gen.get("data") or {}).get("doc_url")
        if gen.get("code") != 0 or not doc_url:
            logger.warning(f"[tokopedia] shipping_doc/generate failed for {order_id}: {gen.get('message')}")
            return None

        pdf = await page.evaluate(
            """async (args) => {
                try {
                    const r = await fetch(args.url);
                    const buf = await r.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    let binary = "";
                    for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
                    return {size: bytes.byteLength, b64: btoa(binary)};
                } catch (e) {
                    return {error: String(e)};
                }
            }""",
            {"url": doc_url},
        )

        if pdf.get("error") or pdf.get("size", 0) < 1000 or not pdf.get("b64"):
            logger.warning(f"[tokopedia] Failed to fetch PDF for {order_id}: {pdf.get('error', 'empty response')}")
            return None

        return base64.b64decode(pdf["b64"])

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
