from __future__ import annotations

import asyncio
import re
from datetime import date
from pathlib import Path

from loguru import logger
from playwright.async_api import Page

from src.marketplaces.base import BaseMarketplace, MarketOrder, MarketProduct


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
                    {
                        "userAgent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/149.0.7827.55 Safari/537.36",
                    },
                )
                await page.add_init_script("""
                    Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                    Object.defineProperty(navigator, 'languages', {get: () => ['id-ID', 'id', 'en-US', 'en']});
                    Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});
                    window.chrome = {runtime: {}};
                    Object.defineProperty(navigator, 'maxTouchPoints', {get: () => 0});
                    const originalQuery = window.navigator.permissions.query;
                    window.navigator.permissions.query = (parameters) => (
                        parameters.name === 'notifications' ?
                            Promise.resolve({ state: Notification.permission }) :
                            originalQuery(parameters)
                    );
                """)
                logger.info("[shopee] Stealth mode applied (UA + webdriver + plugins)")
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
            {
                "userAgent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/149.0.7827.55 Safari/537.36",
            },
        )
        await page.add_init_script("""
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            Object.defineProperty(navigator, 'languages', {get: () => ['id-ID', 'id', 'en-US', 'en']});
            Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});
            window.chrome = {runtime: {}};
        """)
        await page.goto(f"{self.config.seller_center_url}/portal/sale")
        logger.info("[shopee] Browser opened. Please log in manually.")
        logger.info("[shopee] Waiting for redirect to seller portal (up to 5 minutes)...")
        try:
            await page.wait_for_url("**/portal/**", timeout=300_000)
            await asyncio.sleep(5)
            url = page.url
            if "accounts.shopee" in url or "seller/login" in url:
                logger.error(f"[shopee] Still on login page: {url}")
                return False
            screenshot_path = self._base_dir / "sessions" / "shopee_login_result.png"
            await page.screenshot(path=str(screenshot_path), full_page=True)
            logger.info(f"[shopee] Login screenshot saved: {screenshot_path}")
            logger.info(f"[shopee] Current URL: {page.url}")
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
            return []

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

                    detail = await self._fetch_order_detail(page, order_id)
                    if detail:
                        order_data = detail.get("data", {}).get("order_data", {})
                        addr = order_data.get("shipping_address", {})
                        if addr:
                            shipping_addr = {
                                "name": addr.get("name", buyer_name),
                                "phone": addr.get("phone", ""),
                                "address": addr.get("address", ""),
                                "city": addr.get("city", ""),
                                "state": addr.get("state", addr.get("province", "")),
                                "district": addr.get("district", ""),
                                "postal_code": addr.get("zipcode", addr.get("postal_code", "")),
                            }
                            if not buyer_name:
                                buyer_name = addr.get("name", buyer_name)

                        logistics = order_data.get("logistics", {})
                        if logistics:
                            courier_name = logistics.get("logistics_channel_name", logistics.get("logistics_name", ""))
                            tracking_number = logistics.get("tracking_number", "")
                            shipping_etd = logistics.get("estimated_delivery_time", logistics.get("etd", ""))

                        detail_pay = order_data.get("payment", {})
                        if detail_pay:
                            ship_fee = detail_pay.get("shipping_fee", 0)
                            shipping_cost = float(ship_fee) / 100000 if ship_fee > 100000 else float(ship_fee) if ship_fee else 0.0
                            if shipping_cost == 0:
                                logis_fee = detail_pay.get("logistics_fee", 0)
                                shipping_cost = float(logis_fee) / 100000 if logis_fee > 100000 else float(logis_fee) if logis_fee else 0.0

                        if not buyer_name or buyer_name.startswith("Shopee"):
                            buyer_name = order_data.get("buyer_username", buyer_name)

                    if items and total_amount > 0:
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
                            created_at="",
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

                        return {
                            code: 0,
                            data: {
                                order_data: {
                                    shipping_address: {
                                        name: data.data.buyer_name || "",
                                        phone: data.data.buyer_phone || "",
                                        address: (data.data.shipping_address || "").split(",").slice(0, -4).join(",").trim(),
                                        city: (data.data.shipping_address || "").split(",").slice(-4, -3)[0] || "",
                                        state: (data.data.shipping_address || "").split(",").slice(-3, -2)[0] || "",
                                    },
                                    logistics: {
                                        logistics_channel_name: data.data.actual_carrier || "",
                                        tracking_number: (pkgData.data?.order_info?.package_list?.[0]?.short_code) || "",
                                        estimated_delivery_time: "",
                                    },
                                    payment: {
                                        shipping_fee: parseInt(data.data.shipping_fee || "0"),
                                    },
                                    buyer_username: data.data.buyer_name || "",
                                }
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

    async def download_shipping_label(self, order_id: str, output_dir: Path) -> Path | None:
        page = await self._get_page()

        try:
            await page.goto(f"{self.config.seller_center_url}/portal/sale/order/{order_id}", timeout=15000)
        except Exception:
            pass

        if await self._check_login_needed(page):
            logger.error("[shopee] Session expired, cannot download label.")
            return None

        try:
            spc_cds = await self._get_spc_cds(page)

            order_info = await page.evaluate(
                """async (args) => {
                    try {
                        const url = "/api/v3/order/get_one_order?order_id=" + args.order_id
                            + "&SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2";
                        const r = await fetch(url);
                        const data = await r.json();
                        if (data.code !== 0) return {error: "get_one_order: " + (data.message || "api error")};
                        return {
                            order_sn: data.data.order_sn,
                            status: data.data.status,
                            status_label: data.data.status_info?.status || "",
                            actual_carrier: data.data.actual_carrier || "",
                        };
                    } catch (e) { return {error: e.message}; }
                }""",
                {"order_id": order_id, "spc_cds": spc_cds},
            )

            if not order_info or order_info.get("error"):
                logger.warning(f"[shopee] Failed to get order info for {order_id}: {order_info.get('error', 'unknown')}")
                return None

            order_sn = order_info.get("order_sn", "")
            if not order_sn:
                logger.warning(f"[shopee] No order_sn found for {order_id}")
                return None

            status = order_info.get("status", 0)
            if status < 3:
                logger.warning(
                    f"[shopee] Order {order_id} ({order_sn}) has status "
                    f"'{order_info.get('status_label', status)}' — not yet shipped, no waybill available."
                )
                return None

            waybill_result = await page.evaluate(
                """async (args) => {
                    try {
                        const url = "/api/v3/logistics/get_waybill_format?SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2";
                        const r = await fetch(url, {
                            method: "POST",
                            headers: {"Content-Type": "application/json"},
                            body: JSON.stringify({order_list: [{order_sn: args.order_sn}]})
                        });
                        const data = await r.json();
                        if (data.code !== 0) return {error: "get_waybill_format: " + (data.message || "api error")};
                        return data.data;
                    } catch (e) { return {error: e.message}; }
                }""",
                {"order_sn": order_sn, "spc_cds": spc_cds},
            )

            if not waybill_result or waybill_result.get("error"):
                msg = waybill_result.get("error", "unknown") if waybill_result else "no response"
                logger.warning(f"[shopee] Waybill format check failed for {order_sn}: {msg}")
                return None

            waybill_list = waybill_result.get("list", [])
            if not waybill_list:
                logger.warning(
                    f"[shopee] No waybills available for {order_sn} "
                    f"(status={order_info.get('status_label', status)}, carrier={order_info.get('actual_carrier', '')}). "
                    "Waybill may not be generated yet."
                )
                return None

            download_result = await page.evaluate(
                """async (args) => {
                    try {
                        const r = await fetch("/api/v3/logistics/create_sd_jobs?SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2", {
                            method: "POST",
                            headers: {"Content-Type": "application/json"},
                            body: JSON.stringify({
                                order_list: [{order_sn: args.order_sn}],
                                waybill_type: args.waybill_type || "NORMAL"
                            })
                        });
                        const data = await r.json();
                        if (data.code !== 0) return {error: "create_sd_jobs: " + (data.message || "api error")};
                        const jobId = data.data?.job_id;
                        if (!jobId) return {error: "no job_id returned"};

                        await new Promise(resolve => setTimeout(resolve, 3000));

                        const jobUrl = "/api/v3/logistics/get_sd_job?SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2&job_id=" + jobId;
                        const jobR = await fetch(jobUrl);
                        const jobData = await jobR.json();
                        if (jobData.code !== 0) return {error: "get_sd_job: " + (jobData.message || "api error")};

                        const fileId = jobData.data?.file_id;
                        if (!fileId) return {error: "job not ready yet, file_id missing"};

                        const dlUrl = "/api/v3/logistics/download_sd_job?SPC_CDS=" + args.spc_cds + "&SPC_CDS_VER=2&file_id=" + fileId;
                        const dlR = await fetch(dlUrl);
                        if (!dlR.ok) return {code: -1, message: "download HTTP " + dlR.status};

                        const contentType = dlR.headers.get("content-type") || "";
                        const buf = await dlR.arrayBuffer();
                        const bytes = new Uint8Array(buf);
                        let binary = "";
                        for (let i = 0; i < bytes.byteLength; i++) {
                            binary += String.fromCharCode(bytes[i]);
                        }
                        return {code: 0, data: btoa(binary), content_type: contentType};
                    } catch (e) { return {code: -1, message: e.message}; }
                }""",
                {"order_sn": order_sn, "spc_cds": spc_cds, "waybill_type": waybill_result.get("waybill_type", "NORMAL")},
            )

            if not download_result or download_result.get("code") != 0:
                msg = download_result.get("message", "unknown") if download_result else "no response"
                logger.warning(f"[shopee] Label download failed for {order_id}: {msg}")
                return None

            pdf_b64 = download_result.get("data", "")
            if not pdf_b64:
                logger.warning(f"[shopee] No PDF data returned for order {order_id}")
                return None

            output_dir.mkdir(parents=True, exist_ok=True)
            filename = f"{date.today().isoformat()}_shopee_{order_id}.pdf"
            filepath = output_dir / filename
            filepath.write_bytes(__import__("base64").b64decode(pdf_b64))
            logger.info(f"[shopee] Shipping label saved: {filepath}")
            return filepath

        except Exception as e:
            logger.error(f"[shopee] Failed to download label for {order_id}: {e}")
            return None

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
