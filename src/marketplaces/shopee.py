from __future__ import annotations

import asyncio
import re
import sys
from datetime import date
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


class ShopeeAdapter(BaseMarketplace):
    name = "shopee"

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._product_cache: dict[str, dict] = {}
        self._sc_fe_session: str = ""

    async def _ensure_browser(self, headless: bool = True):
        return await super()._ensure_browser(headless=headless)

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
                logger.info("[shopee] Stealth mode applied (playwright-stealth)")
            except Exception as e:
                logger.warning(f"[shopee] Stealth override failed: {e}")
        return page

    async def login_interactive(self) -> bool:
        self._save_on_close = False
        ctx = await self._ensure_browser(headless=False)
        page = await ctx.new_page()
        cdp = await ctx.new_cdp_session(page)
        await cdp.send(
            "Network.setUserAgentOverride",
            {"userAgent": _chromium_ua()},
        )
        await page.add_init_script("""
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            Object.defineProperty(navigator, 'languages', {get: () => ['id-ID', 'id', 'en-US', 'en']});
            Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});
            window.chrome = {runtime: {}};
        """)
        # Navigate straight to the seller login page. Going to /portal/sale first can
        # make Shopee open the login in a small, non-maximizable popup window on some
        # setups, which the user cannot interact with properly.
        login_url = await self._login_url()
        await page.goto(login_url)
        logger.info("[shopee] Browser opened. Please log in manually.")
        await self._dismiss_login_overlays(page)
        logger.info("[shopee] Waiting for redirect to seller portal (up to 5 minutes)...")
        try:
            portal_ok = await self._wait_for_seller_portal(page, timeout=300)
            await asyncio.sleep(5)
            url = page.url
            if not portal_ok or "seller/login" in url:
                logger.error("[shopee] Login timed out or failed. Existing session NOT modified.")
                return False
            screenshot_path = self._base_dir / "sessions" / "shopee_login_result.png"
            await page.screenshot(path=str(screenshot_path), full_page=True)
            logger.info(f"[shopee] Login screenshot saved: {screenshot_path}")
            logger.info(f"[shopee] Current URL: {page.url}")
            self._save_on_close = True
            await self._save_session()
            clear_login_alert(self.name)
            logger.success("[shopee] Login successful! Session saved.")
            return True
        except Exception:
            logger.error("[shopee] Login timed out or failed. Existing session NOT modified.")
            return False
        finally:
            await self.close()

    async def _login_url(self) -> str:
        """Build the seller login URL (accounts.shopee.*), with a return URL.

        Visiting /portal/sale while logged out can open the login page in a small
        popup window instead of the main window; navigating here directly avoids that.
        """
        from urllib.parse import quote

        seller = self.config.seller_center_url
        host = seller.split("//")[-1].split("/")[0]
        accounts_host = host.replace("seller.", "accounts.", 1)
        next_url = f"{seller}/portal/sale"
        return f"https://{accounts_host}/seller/login?next={quote(next_url, safe='')}"

    async def _wait_for_seller_portal(self, page: Page, timeout: float = 300.0) -> bool:
        """Wait until the page is stable on the seller portal URL.

        Navigating to /portal/sale when logged out briefly loads seller.shopee.co.id
        before a client-side redirect to accounts.shopee.co.id, so a plain
        wait_for_url("**/portal/**") can match the transient URL and fail immediately.
        This requires the URL to remain on the portal for a few seconds.
        """
        portal_prefix = f"{self.config.seller_center_url}/portal/"
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            if page.url.startswith(portal_prefix):
                await asyncio.sleep(6)
                if page.url.startswith(portal_prefix):
                    return True
            else:
                await asyncio.sleep(1)
        return False

    async def _dismiss_login_overlays(self, page: Page) -> None:
        """Shopee shows a fullscreen language-selection modal over the login form.

        The modal has a transparent backdrop (pointer-events: auto) that swallows all
        clicks, so the login form is visible but not interactive. Dismiss it by
        clicking the 'Bahasa Indonesia' option so the user can log in manually.
        """
        dismiss_js = """() => {
            const overlays = [...document.querySelectorAll('div')].filter((el) => {
                const cs = getComputedStyle(el);
                if (cs.position !== 'fixed' || cs.pointerEvents === 'none') return false;
                const z = parseInt(cs.zIndex, 10);
                if (Number.isNaN(z) || z < 1000) return false;
                const r = el.getBoundingClientRect();
                return r.width >= window.innerWidth * 0.9 && r.height >= window.innerHeight * 0.9;
            }).sort((a, b) => parseInt(getComputedStyle(b).zIndex, 10) - parseInt(getComputedStyle(a).zIndex, 10));

            for (const overlay of overlays) {
                const options = [...overlay.querySelectorAll('*')].filter((e) => {
                    if (e.children.length) return false;
                    const t = (e.textContent || '').trim();
                    return t === 'Bahasa Indonesia' || t === 'English';
                });
                if (!options.length) continue;
                const target = options.find((e) => e.textContent.trim() === 'Bahasa Indonesia') || options[0];
                ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach((ev) => {
                    target.dispatchEvent(new MouseEvent(ev, {bubbles: true, cancelable: true, view: window}));
                });
                return true;
            }
            return false;
        }"""

        try:
            for _ in range(8):
                dismissed = await page.evaluate(dismiss_js)
                if dismissed:
                    logger.info("[shopee] Dismissed language-selection modal over login form")
                    return
                await asyncio.sleep(1.5)
            logger.debug("[shopee] No login overlay found to dismiss")
        except Exception as e:
            logger.debug(f"[shopee] Could not dismiss login overlay: {e}")

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
                        "tier_index": default_model.get("tier_index", [0]),
                        "stock": p.get("stock_detail", {}).get("total_available_stock", 0),
                        "price": float(
                            p.get("price_detail", {}).get("selling_price_min", "0")
                        ),
                    }
            except Exception:
                pass

        def _on_request(req):
            if "search_product_list" in req.url:
                hdrs = req.headers
                if "sc-fe-session" in hdrs:
                    self._sc_fe_session = hdrs["sc-fe-session"]

        page.on("response", _on_response)
        page.on("request", _on_request)
        await page.goto(
            f"{self.config.seller_center_url}/portal/product/list/all?operationSortBy=recommend_v2"
        )
        await asyncio.sleep(10)
        page.remove_listener("response", _on_response)
        page.remove_listener("request", _on_request)

        if await self._check_login_needed(page):
            logger.error("[shopee] Session expired. Run 'login shopee' to re-authenticate.")
            raise SessionExpiredError("Shopee session expired - login required")

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

    async def _update_product_info(self, product_id: str, body_patch: dict) -> bool:
        cache = self._product_cache.get(product_id, {})
        model_id = cache.get("model_id", 0)
        tier_index = cache.get("tier_index", [0])
        if not model_id:
            logger.error(f"[shopee] No model_id cached for product {product_id}")
            return False

        model_data = {"id": model_id, "tier_index": tier_index, **body_patch}

        page = await self._get_page()
        try:
            result = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch(
                            "/api/v3/product/update_product_info",
                            {
                                method: "POST",
                                headers: {
                                    "Content-Type": "application/json",
                                    "caller-source": "local_pc",
                                    "sc-fe-ver": "21.155697",
                                    "sc-fe-session": args.session,
                                    "locale": "id",
                                },
                                body: JSON.stringify({
                                    product_id: args.pid,
                                    product_info: {
                                        model_list: [args.model],
                                    },
                                    is_draft: false,
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
                    "model": model_data,
                    "session": self._sc_fe_session,
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

        result = await self._update_product_info(
            marketplace_product_id,
            {"stock_setting_list": [{"location_id": "IDZ", "sellable_stock": qty}]},
        )
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

        price_str = f"{price:.2f}"
        result = await self._update_product_info(
            marketplace_product_id,
            {"price": price_str},
        )
        if result:
            logger.success(
                f"[shopee] Price updated for {marketplace_product_id}: Rp{price:,.0f}"
            )
        return result

    async def get_orders(self, status: str = "new") -> list[MarketOrder]:
        status_url = {
            "new": "3",
            "shipped": "5",
            "completed": "6",
            "cancelled": "4",
        }
        status_code = status_url.get(status, "3")
        page = await self._get_page()

        raw_card_bodies: list[dict] = []

        async def _intercept(route):
            resp = await route.fetch()
            body = await resp.json()
            raw_card_bodies.append(body)
            await route.fulfill(response=resp)

        await page.route("**/api/v3/order/get_order_list_card_list**", _intercept)

        await page.goto(f"{self.config.seller_center_url}/portal/sale/order?status={status_code}", timeout=25000)
        await asyncio.sleep(25)

        await page.unroute("**/api/v3/order/get_order_list_card_list**")

        url = page.url
        if "login" in url or "accounts.shopee" in url or "passport" in url:
            screenshot_path = self._base_dir / "sessions" / "shopee_sync_fail.png"
            await page.screenshot(path=str(screenshot_path), full_page=True)
            logger.error(f"[shopee] Session expired. Redirected to: {url}")
            logger.error(f"[shopee] Screenshot saved: {screenshot_path}")
            raise SessionExpiredError(
                f"Shopee session expired - login required (redirected to {url})"
            )

        orders: list[MarketOrder] = []
        try:
            card_data: dict[str, dict] = {}
            for body in raw_card_bodies:
                if isinstance(body, dict):
                    for c in body.get("data", {}).get("card_list", []):
                        polo = c.get("package_level_order_card")
                        oc = c.get("order_card")
                        ext = polo.get("order_ext_info", {}) if polo else (oc.get("order_ext_info", {}) if oc else {})
                        oid = str(ext.get("order_id", ""))
                        if oid:
                            card_data[oid] = c

            for card in card_data.values():
                try:
                    polo = card.get("package_level_order_card")
                    oc = card.get("order_card")

                    if polo:
                        header = polo.get("card_header", {})
                        ext = polo.get("order_ext_info", {})
                        items = []
                        for pkg in polo.get("package_list", []):
                            for group in pkg.get("item_info_group", {}).get("item_info_list", []):
                                for item in group.get("item_list", []):
                                    inner = item.get("inner_item_ext_info", {})
                                    items.append({
                                        "marketplace_product_id": str(inner.get("item_id", "")),
                                        "marketplace_sku": "",
                                        "product_name": item.get("name", ""),
                                        "qty": item.get("amount", 1),
                                        "price": 0.0,
                                        "model_id": inner.get("model_id", 0),
                                    })
                        pay_info = polo.get("package_list", [{}])[0].get("payment_info", {}) if polo.get("package_list") else {}
                    elif oc:
                        header = oc.get("card_header", {})
                        ext = oc.get("order_ext_info", {})
                        items = []
                        for group in oc.get("item_info_group", {}).get("item_info_list", []):
                            for item in group.get("item_list", []):
                                inner = item.get("inner_item_ext_info", {})
                                items.append({
                                    "marketplace_product_id": str(inner.get("item_id", "")),
                                    "marketplace_sku": "",
                                    "product_name": item.get("name", ""),
                                    "qty": item.get("amount", 1),
                                    "price": 0.0,
                                    "model_id": inner.get("model_id", 0),
                                })
                        pay_info = oc.get("payment_info", {})
                    else:
                        continue

                    order_id = str(ext.get("order_id", ""))
                    buyer_name = header.get("buyer_info", {}).get("username", "")
                    raw_price = pay_info.get("total_price", 0)
                    total_amount = raw_price / 100000 if raw_price > 100000 else float(raw_price) if raw_price else 0.0

                    shipping_addr = {}
                    courier_name = ""
                    tracking_number = ""
                    shipping_cost = 0.0
                    shipping_etd = ""
                    created_at = ""

                    detail = await self._fetch_order_detail(page, order_id)
                    if detail:
                        d = detail.get("data", {})
                        raw_addr = d.get("shipping_address", "")
                        if raw_addr:
                            shipping_addr = self._parse_shipping_address(raw_addr, buyer_name)
                            name = d.get("buyer_address_name", "")
                            if name and "*" not in name:
                                shipping_addr["name"] = name
                            phone = d.get("buyer_address_phone", "")
                            if phone and "*" not in phone:
                                shipping_addr["phone"] = phone

                        courier_name = d.get("actual_carrier") or courier_name
                        tracking_number = d.get("tracking_number") or tracking_number
                        ship_fee_raw = d.get("shipping_fee") or 0
                        try:
                            ship_fee = float(ship_fee_raw)
                        except (TypeError, ValueError):
                            ship_fee = 0.0
                        if ship_fee > 100000:
                            shipping_cost = ship_fee / 100000
                        elif ship_fee:
                            shipping_cost = ship_fee
                        created_at = str(d.get("create_time") or 0)
                        if created_at.isdigit() and int(created_at) <= 0:
                            created_at = ""
                        if d.get("items"):
                            detail_by_id: dict[str, dict] = {}
                            for di in d["items"]:
                                for key in ("item_id", "model_id"):
                                    if di.get(key):
                                        detail_by_id.setdefault(str(di[key]), di)
                            for it in items:
                                di = detail_by_id.get(it.get("marketplace_product_id", ""))
                                if not di:
                                    di = detail_by_id.get(str(it.get("model_id", "")))
                                if not di:
                                    continue
                                if di.get("product_name"):
                                    it["product_name"] = di["product_name"]
                                if di.get("shop_sku"):
                                    it["marketplace_sku"] = di["shop_sku"]
                                op = di.get("order_price")
                                if op:
                                    try:
                                        it["price"] = float(op)
                                    except (TypeError, ValueError):
                                        pass

                        if not buyer_name or buyer_name.startswith("Shopee"):
                            buyer_name = d.get("buyer_address_name") or buyer_name

                    if items and total_amount > 0:
                        if any(it.get("price") for it in items):
                            item_total = sum(it.get("price", 0) * it.get("qty", 1) for it in items)
                            if item_total > 0:
                                total_amount = item_total + shipping_cost
                        else:
                            total_qty = sum(i["qty"] for i in items)
                            for it in items:
                                it["price"] = total_amount / total_qty if total_qty else 0

                    orders.append(
                        MarketOrder(
                            order_id=order_id,
                            buyer_name=buyer_name,
                            items=items,
                            total_amount=total_amount,
                            status=status,
                            created_at=created_at,
                            shipping_address=shipping_addr,
                            courier_name=courier_name,
                            tracking_number=tracking_number,
                            shipping_cost=shipping_cost,
                            shipping_etd=shipping_etd,
                            raw=card,
                        )
                    )
                except Exception as e:
                    logger.warning(f"[shopee] Failed to parse order card: {e}")

            logger.info(f"[shopee] Found {len(orders)} orders (status={status})")
        except Exception as e:
            logger.error(f"[shopee] Failed to load orders: {e}")

        return orders

    async def _get_spc_cds(self, page) -> str:
        result = await page.evaluate("""() => {
            const links = document.querySelectorAll('link[href*="SPC_CDS"]');
            if (links.length) return "";
            const scripts = document.querySelectorAll('script');
            for (const s of scripts) {
                if (s.src && s.src.includes("SPC_CDS")) {
                    const m = s.src.match(/SPC_CDS=([a-f0-9-]+)/);
                    if (m) return m[1];
                }
            }
            return "";
        }""")
        return result or ""

    async def _fetch_order_detail(self, page, order_id: str) -> dict | None:
        try:
            spc_cds = await self._get_spc_cds(page)

            result = await page.evaluate(
                """async (args) => {
                    try {
                        const url = "/api/v3/order/get_one_order?order_id=" + args.order_id
                            + "&SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2";
                        const r = await fetch(url);
                        const data = await r.json();
                        if (data.code !== 0) return {code: data.code, message: data.message || "api error"};

                        const pkgUrl = "/api/v3/order/get_package?order_id=" + args.order_id
                            + "&SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2";
                        const pkgR = await fetch(pkgUrl);
                        const pkgData = await pkgR.json();

                        const d = data.data || {};
                        const items = (d.order_items || []).map((it) => ({
                            item_id: it.item_id,
                            model_id: it.model_id,
                            amount: it.amount,
                            order_price: it.order_price || "",
                            product_name: (it.product && it.product.name) || "",
                            shop_sku: (it.product && it.product.sku) || "",
                        }));

                        return {
                            code: 0,
                            data: {
                                order_sn: d.order_sn || "",
                                order_status: d.order_status || "",
                                create_time: d.create_time || 0,
                                shipping_address: d.shipping_address || "",
                                buyer_address_name: d.buyer_address_name || "",
                                buyer_address_phone: d.buyer_address_phone || "",
                                actual_carrier: d.actual_carrier || "",
                                shipping_fee: d.shipping_fee || "0",
                                tracking_number: (pkgData.data?.order_info?.package_list?.[0]?.short_code) || "",
                                items: items,
                            }
                        };
                    } catch (e) {
                        return {code: -1, message: e.message};
                    }
                }""",
                {"order_id": order_id, "spc_cds": spc_cds},
            )
            if result and result.get("code") == 0:
                return result
            logger.debug(f"[shopee] Order detail API for {order_id}: {result.get('message', 'error') if result else 'no response'}")
        except Exception as e:
            logger.debug(f"[shopee] Failed to fetch detail for {order_id}: {e}")
        return None

    async def is_order_cancelled(self, order_id: str) -> bool | None:
        page = await self._get_page()
        detail = await self._fetch_order_detail(page, order_id)
        if not detail:
            return None
        status = str(detail.get("data", {}).get("order_status", "")).upper()
        if status in ("CANCELLED", "CANCEL", "IN_CANCEL", "CANCEL_REFUND", "RETRY_SHIP"):
            return True
        if not status:
            return None
        return False

    async def download_shipping_label(self, order_id: str, output_dir: Path) -> Path | None:
        logger.info(f"[shopee] Downloading shipping label for order {order_id}")

        if output_dir is None:
            output_dir = self._label_output_dir()

        page = await self._get_page()

        try:
            await page.goto(f"{self.config.seller_center_url}/portal/sale/order", timeout=30000)
        except Exception:
            pass

        if await self._check_login_needed(page):
            logger.error("[shopee] Session expired, cannot download label.")
            return None

        ctx = page.context

        pdf_data: dict | None = None
        pdf_event = asyncio.Event()

        async def on_pdf_page(new_page):
            nonlocal pdf_data
            logger.info("[shopee] AWB print page opened")
            try:
                await new_page.wait_for_load_state("networkidle")
                for attempt in range(15):
                    await asyncio.sleep(2)
                    result = await new_page.evaluate("""async () => {
                        for (const f of document.querySelectorAll('iframe')) {
                            if (f.src && f.src.startsWith('blob:')) {
                                try {
                                    const resp = await fetch(f.src);
                                    const blob = await resp.blob();
                                    const reader = new FileReader();
                                    return await new Promise((resolve) => {
                                        reader.onload = () => resolve({
                                            size: blob.size,
                                            type: blob.type,
                                            b64: reader.result.split(',')[1],
                                        });
                                        reader.readAsDataURL(blob);
                                    });
                                } catch (e) { return {error: String(e)}; }
                            }
                        }
                        return null;
                    }""")
                    if result is not None:
                        pdf_data = result
                        pdf_event.set()
                        await new_page.close()
                        return
                pdf_data = {"error": "no blob iframe found after polling"}
            except Exception as e:
                logger.warning(f"[shopee] Error in AWB print page: {e}")
                pdf_data = {"error": str(e)}
            pdf_event.set()

        ctx.on("page", on_pdf_page)

        try:
            await page.wait_for_timeout(15000)
            spc_cds = await self._get_spc_cds(page)

            order_info = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch("/api/v3/order/get_one_order?order_id=" + args.order_id
                            + "&SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2");
                        const data = await r.json();
                        if (data.code !== 0) return {error: "get_one_order: " + (data.message || "api error")};
                        return {order_sn: data.data.order_sn};
                    } catch (e) { return {error: e.message}; }
                }""",
                {"order_id": order_id, "spc_cds": spc_cds},
            )
            if not order_info or order_info.get("error"):
                logger.warning(f"[shopee] Failed to verify order {order_id}: {order_info}")
                return None

            order_sn = order_info.get("order_sn", "")
            if not order_sn:
                logger.warning(f"[shopee] No order_sn found for {order_id}")
                return None

            clicked = await page.evaluate(
                """(args) => {
                    const cards = document.querySelectorAll('a.order-card');
                    for (const card of cards) {
                        if (!(card.textContent || '').includes(args.order_sn)) continue;
                        const btns = card.querySelectorAll('button');
                        for (const btn of btns) {
                            if (btn.textContent.trim() === 'Cetak Label') {
                                btn.click();
                                return true;
                            }
                        }
                    }
                    return false;
                }""",
                {"order_sn": order_sn},
            )

            if not clicked:
                logger.warning(f"[shopee] Cetak Label button not found for order {order_id} ({order_sn})")
                return None

            await asyncio.wait_for(pdf_event.wait(), timeout=45)

            if pdf_data and pdf_data.get("size", 0) > 0 and pdf_data.get("b64"):
                b64 = pdf_data["b64"]
                pdf_bytes = __import__("base64").b64decode(b64)
                output_dir.mkdir(parents=True, exist_ok=True)
                filename = f"{date.today().isoformat()}_shopee_{order_id}.pdf"
                filepath = output_dir / filename
                filepath.write_bytes(pdf_bytes)
                logger.info(f"[shopee] Shipping label saved ({len(pdf_bytes)} bytes): {filepath}")
                await self._enqueue_print(filepath, order_id)
                return filepath

            err_msg = pdf_data.get("error", "unknown") if pdf_data else "no pdf data"
            logger.warning(f"[shopee] Label download failed for {order_id} ({order_sn}): {err_msg}")
            return None

        except TimeoutError:
            logger.warning(f"[shopee] Timeout waiting for AWB print page for order {order_id}")
            return None
        except Exception as e:
            logger.error(f"[shopee] Label download error for {order_id}: {e}")
            return None
        finally:
            ctx.remove_listener("page", on_pdf_page)
            await self._save_session()

    async def arrange_pickup(self, order_id: str | None = None) -> list[str]:
        logger.info(f"[shopee] Arranging pickup for order_id={order_id or 'ALL'}")
        page = await self._get_page()

        try:
            await page.goto(f"{self.config.seller_center_url}/portal/sale/order?status=3", timeout=30000)
        except Exception:
            pass

        if await self._check_login_needed(page):
            logger.error("[shopee] Session expired, cannot arrange pickup.")
            return []

        await page.wait_for_timeout(15000)

        successful: list[str] = []

        if order_id:
            spc_cds = await self._get_spc_cds(page)
            order_info = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch("/api/v3/order/get_one_order?order_id=" + args.order_id
                            + "&SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2");
                        const data = await r.json();
                        if (data.code !== 0) {
                            return {error: "get_one_order: " + (data.message || "api error")};
                        }
                        return {order_sn: data.data.order_sn};
                    } catch (e) {
                        return {error: e.message};
                    }
                }""",
                {"order_id": order_id, "spc_cds": spc_cds},
            )
            if not order_info or order_info.get("error"):
                logger.warning(f"[shopee] Failed to verify order {order_id}: {order_info}")
                return []

            order_sn = order_info.get("order_sn", "")
            if not order_sn:
                logger.warning(f"[shopee] No order_sn found for {order_id}")
                return []

            clicked = await page.evaluate(
                """(args) => {
                    const cards = document.querySelectorAll('a.order-card');
                    for (const card of cards) {
                        if (!(card.textContent || '').includes(args.order_sn)) continue;
                        const btns = card.querySelectorAll('button');
                        for (const btn of btns) {
                            if (btn.textContent.trim() === 'Atur Pickup') {
                                btn.click();
                                return true;
                            }
                        }
                    }
                    return false;
                }""",
                {"order_sn": order_sn},
            )
            if not clicked:
                logger.warning(f"[shopee] Atur Pickup button not found for order {order_id} ({order_sn})")
                return []

            if await self._confirm_pickup_modal(page, order_id):
                successful.append(order_id)
        else:
            for attempt in range(50):
                await page.wait_for_timeout(2000)

                clicked_info = await page.evaluate(
                    """() => {
                        const cards = document.querySelectorAll('a.order-card');
                        for (const card of cards) {
                            const btns = card.querySelectorAll('button');
                            for (const btn of btns) {
                                if (btn.textContent.trim() === 'Atur Pickup') {
                                    const text = card.textContent || '';
                                    const snMatch = text.match(/(\\d{6}[A-Za-z0-9]{6,})\\b/);
                                    const href = card.href || '';
                                    const idMatch = href.match(/(\\d{15,17})/);
                                    btn.click();
                                    return {
                                        ok: true,
                                        order_sn: snMatch ? snMatch[0] : '',
                                        order_id: idMatch ? idMatch[1] : '',
                                    };
                                }
                            }
                        }
                        return { ok: false };
                    }"""
                )

                if not clicked_info.get("ok"):
                    logger.info("[shopee] No more Atur Pickup buttons found.")
                    break

                order_sn = clicked_info.get("order_sn", "")
                order_id = clicked_info.get("order_id", "")
                label = order_id or order_sn or f"order_{attempt}"
                logger.info(f"[shopee] Found Atur Pickup button ({label})")

                if await self._confirm_pickup_modal(page, label):
                    successful.append(order_id or order_sn or label)

        await self._save_session()

        if successful:
            logger.success(f"[shopee] Pickup arranged for {len(successful)} order(s): {successful}")
        else:
            logger.info("[shopee] No pickups were arranged.")

        return successful

    async def _confirm_pickup_modal(self, page: Page, label: str) -> bool:
        confirm_btn = page.locator('button[data-testid="arrange-shipment-confirm"]')
        try:
            await confirm_btn.wait_for(state="visible", timeout=15000)
            logger.info(f"[shopee] Pickup modal opened for {label}")
        except Exception:
            logger.warning(f"[shopee] Pickup modal did not appear for {label}")
            return False

        await page.wait_for_timeout(1000)
        await confirm_btn.click()
        logger.info(f"[shopee] Clicked Konfirmasi for {label}")

        await page.wait_for_timeout(3000)

        try:
            warning_confirm = page.locator(
                '.eds-modal:has-text("Perhatian") button:has-text("Confirm")'
            )
            if await warning_confirm.is_visible(timeout=3000):
                await warning_confirm.click()
                logger.info(f"[shopee] Confirmed warehouse warning for {label}")
                await page.wait_for_timeout(2000)
        except Exception:
            pass

        try:
            await confirm_btn.wait_for(state="hidden", timeout=10000)
            logger.success(f"[shopee] Pickup confirmed for {label}")
            return True
        except Exception:
            logger.warning(f"[shopee] Pickup modal did not close for {label}")
            return False

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
    def _parse_shipping_address(raw: str, default_name: str = "") -> dict:
        """Parse the Shopee shipping_address string into structured fields.

        Shopee returns the address as a comma-separated string, e.g.
        ``"Jl. Merdeka 10, Petukangan Utara, KOTA JAKARTA SELATAN,
        PESANGGRAHAN, DKI JAKARTA, ID, 12260"`` where the trailing parts are
        ``[street, kelurahan, city, district, state, country, postal]``.
        Falls back to using the whole string as the address when the shape
        does not match expectations.
        """
        if not raw or not raw.strip():
            return {}
        parts = [p.strip() for p in raw.split(",") if p.strip()]
        postal = ""
        if parts and parts[-1].isdigit():
            postal = parts.pop()
        if parts and parts[-1].upper() in ("ID", "IDN", "INDONESIA"):
            parts.pop()
        state = parts.pop() if parts else ""
        district = parts.pop() if parts else ""
        city = parts.pop() if parts else ""
        address = ", ".join(parts)
        if not address and city:
            address = city
            city = ""
        return {
            "name": default_name,
            "phone": "",
            "address": address,
            "city": city,
            "state": state,
            "district": district,
            "postal_code": postal,
        }

    @staticmethod
    def _parse_stock(text: str) -> int:
        if not text:
            return 0
        match = re.search(r"(\d+)", text)
        return int(match.group(1)) if match else 0
