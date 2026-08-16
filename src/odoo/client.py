from __future__ import annotations

import xmlrpc.client
from typing import Any

from loguru import logger

from src.config import OdooConfig


def _normalize_datetime(value: str) -> str:
    value = value.strip()
    if len(value) == 10 and value[4] == "-" and value[7] == "-":
        return f"{value} 00:00:00"
    return value


class OdooClient:
    def __init__(self, config: OdooConfig) -> None:
        self.config = config
        self._uid: int | None = None
        self._common: xmlrpc.client.ServerProxy | None = None
        self._models: xmlrpc.client.ServerProxy | None = None

    def _connect(self) -> None:
        url = self.config.url.rstrip("/")
        self._common = xmlrpc.client.ServerProxy(f"{url}/xmlrpc/2/common", allow_none=True)
        self._models = xmlrpc.client.ServerProxy(f"{url}/xmlrpc/2/object", allow_none=True)

    def authenticate(self) -> int:
        self._connect()
        assert self._common is not None
        self._uid = self._common.authenticate(
            self.config.db, self.config.username, self.config.password, {}
        )
        if not self._uid:
            raise ConnectionError(
                f"Odoo auth failed for {self.config.username}@{self.config.url}"
            )
        logger.info(f"Odoo authenticated as uid={self._uid}")
        return self._uid

    @property
    def uid(self) -> int:
        if self._uid is None:
            self.authenticate()
        return self._uid  # type: ignore[return-value]

    @property
    def models(self) -> xmlrpc.client.ServerProxy:
        if self._models is None:
            self._connect()
        return self._models  # type: ignore[return-value]

    def _call(self, model: str, method: str, args: list, kwargs: dict | None = None) -> Any:
        logger.debug(f"Odoo RPC: {model}.{method} args={args[:2]}...")
        result = self.models.execute_kw(
            self.config.db,
            self.uid,
            self.config.password,
            model,
            method,
            args,
            kwargs or {},
        )
        return result

    # ── Product methods ──

    def get_products(self, domain: list | None = None, fields: list[str] | None = None) -> list[dict]:
        if domain is None:
            domain = [["sale_ok", "=", True], ["type", "!=", "service"]]
        if fields is None:
            fields = ["name", "default_code", "list_price", "standard_price", "qty_available", "type"]
        return self._call("product.product", "search_read", [domain], {"fields": fields, "limit": 500})

    def get_product(self, product_id: int, fields: list[str] | None = None) -> dict:
        if fields is None:
            fields = ["name", "default_code", "list_price", "standard_price", "qty_available", "type"]
        result = self._call("product.product", "read", [[product_id]], {"fields": fields})
        if result:
            return result[0]
        raise ValueError(f"Product {product_id} not found")

    def update_product(self, product_id: int, vals: dict) -> bool:
        return self._call("product.product", "write", [[product_id], vals])

    # ── Stock methods ──

    def get_stock_quant(self, product_id: int) -> list[dict]:
        location_id = self._get_location_id(self.config.stock_location)
        return self._call(
            "stock.quant",
            "search_read",
            [[["product_id", "=", product_id], ["location_id", "=", location_id]]],
            {"fields": ["quantity", "reserved_quantity", "available_quantity"]},
        )

    def get_all_stock(self, domain: list | None = None) -> list[dict]:
        if domain is None:
            location_id = self._get_location_id(self.config.stock_location)
            domain = [["location_id", "=", location_id]]
        return self._call(
            "stock.quant",
            "search_read",
            [domain],
            {"fields": ["product_id", "location_id", "quantity", "reserved_quantity", "available_quantity"], "limit": 500},
        )

    def update_stock(self, product_id: int, new_qty: int) -> bool:
        location_id = self._get_location_id(self.config.stock_location)
        quants = self.get_stock_quant(product_id)
        if quants:
            quant_id = self._call(
                "stock.quant", "search",
                [[["product_id", "=", product_id], ["location_id", "=", location_id]]]
            )
            if quant_id:
                self._call(
                    "stock.quant", "write",
                    [[quant_id[0]], {"inventory_quantity": new_qty}]
                )
                self._call("stock.quant", "action_apply_inventory", [[quant_id[0]]])
                return True
        self._call(
            "stock.quant", "create",
            [{"product_id": product_id, "location_id": location_id, "inventory_quantity": new_qty}]
        )
        return True

    # ── Order methods ──

    def get_orders(
        self, domain: list | None = None, fields: list[str] | None = None
    ) -> list[dict]:
        if domain is None:
            domain = [["state", "=", "sale"]]
        if fields is None:
            fields = [
                "name", "partner_id", "date_order", "amount_total",
                "state", "order_line", "client_order_ref",
            ]
        return self._call("sale.order", "search_read", [domain], {"fields": fields, "limit": 200})

    def create_sale_order(
        self,
        partner_id: int,
        lines: list[dict],
        ref: str | None = None,
        date_order: str | None = None,
        shipping_note: str | None = None,
        shipping_partner_id: int | None = None,
    ) -> int:
        uom_id = self._get_default_uom()
        vals: dict[str, Any] = {
            "partner_id": partner_id,
            "order_line": [
                (0, 0, {
                    "product_id": line["product_id"],
                    "product_uom_qty": line["qty"],
                    "product_uom": uom_id,
                    "price_unit": line["price"],
                })
                for line in lines
            ],
        }
        if ref:
            vals["client_order_ref"] = ref
        if date_order:
            vals["date_order"] = _normalize_datetime(date_order)
        if shipping_partner_id:
            vals["partner_shipping_id"] = shipping_partner_id
        if shipping_note:
            vals["note"] = shipping_note
        return self._call("sale.order", "create", [vals])

    def create_shipping_partner(self, parent_id: int, name: str, address: dict) -> int:
        vals: dict[str, Any] = {
            "parent_id": parent_id,
            "name": name,
            "type": "delivery",
        }
        if address.get("phone"):
            vals["phone"] = address["phone"]
        if address.get("address"):
            vals["street"] = address["address"]
        if address.get("city"):
            vals["city"] = address["city"]
        if address.get("state"):
            state_id = self._call(
                "res.country.state", "search",
                [[["name", "=", address["state"]]]],
                {"limit": 1},
            )
            if state_id:
                vals["state_id"] = state_id[0]
        if address.get("district"):
            vals["street2"] = address["district"]
        if address.get("postal_code"):
            vals["zip"] = address["postal_code"]
        return self._call("res.partner", "create", [vals])

    def confirm_sale_order(self, order_id: int) -> bool:
        try:
            so_data = self._call(
                "sale.order", "read",
                [[order_id], ["date_order"]],
            )
            orig_date_order = so_data[0].get("date_order") if so_data else None
        except Exception:
            orig_date_order = None

        self._call("sale.order", "action_confirm", [[order_id]])
        logger.info(f"Odoo sale.order#{order_id} confirmed — stock.picking will be created")

        if orig_date_order:
            try:
                self._call(
                    "sale.order", "write",
                    [[order_id], {"date_order": orig_date_order}],
                )
            except Exception as e:
                logger.warning(f"Failed to restore date_order on sale.order#{order_id}: {e}")

        try:
            date_order = orig_date_order
            if date_order:
                scheduled = str(date_order)[:10]
                picking_ids = self._call(
                    "stock.picking",
                    "search",
                    [[["sale_id", "=", order_id], ["state", "not in", ["done", "cancel"]]]],
                )
                if picking_ids:
                    self._call(
                        "stock.picking", "write",
                        [picking_ids, {"scheduled_date": scheduled}],
                    )
                    logger.info(
                        f"Odoo stock.picking#{picking_ids} scheduled_date set to {scheduled}"
                    )
        except Exception as e:
            logger.warning(f"Failed to set picking scheduled_date for sale.order#{order_id}: {e}")
        return True

    def get_sale_order_date(self, sale_order_id: int) -> str | None:
        try:
            data = self._call("sale.order", "read", [[sale_order_id], ["date_order"]])
            return data[0].get("date_order") if data else None
        except Exception as e:
            logger.warning(f"Failed to read date_order for sale.order#{sale_order_id}: {e}")
            return None

    def validate_delivery_order(self, sale_order_id: int, done_date: str | None = None) -> bool:
        picking_ids = self._call(
            "stock.picking",
            "search",
            [[["sale_id", "=", sale_order_id], ["state", "not in", ["done", "cancel"]]]],
        )
        if not picking_ids:
            logger.warning(
                f"sale.order#{sale_order_id} has no pending stock.picking to validate"
            )
            return False

        # Re-attempt reservation so pickings whose moves were not reserved at confirm
        # time (e.g. stock temporarily unavailable) reserve whatever is now available.
        self._call("stock.picking", "action_confirm", [picking_ids])

        result = self._call("stock.picking", "button_validate", [picking_ids])

        if isinstance(result, dict) and result.get("res_model") == "stock.backorder.confirmation":
            wizard_id = self._call(
                "stock.backorder.confirmation",
                "create",
                [{"pick_ids": [(6, 0, picking_ids)]}],
            )
            self._call("stock.backorder.confirmation", "process", [[wizard_id]])

        if done_date:
            try:
                self._call("stock.picking", "write", [[picking_ids], {"date_done": done_date}])
                move_ids = self._call(
                    "stock.move",
                    "search",
                    [[["picking_id", "in", picking_ids]]],
                )
                if move_ids:
                    self._call("stock.move", "write", [[move_ids], {"date": done_date}])
            except Exception as e:
                logger.warning(
                    f"Failed to set done date {done_date} on stock.picking#{picking_ids}: {e}"
                )

        logger.info(
            f"Odoo stock.picking#{picking_ids} validated — stock deducted for sale.order#{sale_order_id}"
        )
        return True

    def create_invoice_from_sale_order(self, sale_order_id: int) -> int | None:
        so_data = self._call(
            "sale.order", "read",
            [[sale_order_id], ["partner_id", "name", "order_line", "date_order"]],
        )
        if not so_data:
            logger.warning(f"sale.order#{sale_order_id} not found")
            return None
        so = so_data[0]
        partner_id = so["partner_id"][0]
        so_name = so.get("name", "")
        date_order = so.get("date_order")

        line_data = self._call(
            "sale.order.line", "read",
            [so["order_line"], ["product_id", "product_uom_qty", "price_unit"]],
        )

        product_ids = [line["product_id"][0] for line in line_data if line.get("product_id")]
        product_accounts = self._get_product_accounts(product_ids)

        invoice_lines = []
        for line in line_data:
            product = line["product_id"]
            product_name = product[1] if isinstance(product, list) else str(product)
            pid = line["product_id"][0]
            invoice_lines.append((0, 0, {
                "name": product_name,
                "product_id": pid,
                "quantity": line["product_uom_qty"],
                "price_unit": line["price_unit"],
                "account_id": product_accounts.get(pid),
            }))

        vals: dict[str, Any] = {
            "move_type": "out_invoice",
            "partner_id": partner_id,
            "invoice_origin": so_name,
            "invoice_line_ids": invoice_lines,
        }
        if date_order:
            invoice_date = str(date_order)[:10]
            vals["invoice_date"] = invoice_date
            vals["date"] = invoice_date

        invoice_id = self._call("account.move", "create", [vals])
        self._call("account.move", "action_post", [[invoice_id]])
        logger.info(f"Odoo account.move#{invoice_id} created and posted from sale.order#{sale_order_id}")
        return invoice_id

    def get_or_create_partner(self, name: str, phone: str | None = None) -> int:
        domain = [("name", "=", name)]
        if phone:
            domain = ["|", ("name", "=", name), ("phone", "=", phone)]
        result = self._call("res.partner", "search", [domain], {"limit": 1})
        if result:
            return result[0]
        vals: dict[str, Any] = {"name": name}
        if phone:
            vals["phone"] = phone
        return self._call("res.partner", "create", [vals])

    # ── Internal ──

    def _get_default_uom(self) -> int:
        result = self._call("uom.uom", "search", [[["name", "=", "Units"]]], {"limit": 1})
        if not result:
            raise ValueError("UoM 'Units' not found in Odoo")
        return result[0]

    def _get_revenue_account(self) -> int:
        result = self._call(
            "account.account", "search",
            [[["account_type", "=", "income"]]],
            {"limit": 1},
        )
        if not result:
            raise ValueError("No revenue account found in Odoo")
        return result[0]

    def _get_product_accounts(self, product_ids: list[int]) -> dict[int, int]:
        accounts: dict[int, int] = {}
        fallback = self._get_revenue_account()

        if not product_ids:
            return accounts

        prod_data = self._call(
            "product.product", "read",
            [product_ids, ["property_account_income_id", "categ_id"]],
        )
        categ_ids = list({p["categ_id"][0] for p in prod_data if p.get("categ_id")})
        categ_accounts: dict[int, int] = {}
        if categ_ids:
            categ_data = self._call(
                "product.category", "read",
                [categ_ids, ["property_account_income_categ_id"]],
            )
            for c in categ_data:
                acc = c.get("property_account_income_categ_id")
                if acc:
                    categ_accounts[c["id"]] = acc[0]

        for p in prod_data:
            pid = p["id"]
            acc = p.get("property_account_income_id")
            if acc:
                accounts[pid] = acc[0]
            elif p.get("categ_id") and p["categ_id"][0] in categ_accounts:
                accounts[pid] = categ_accounts[p["categ_id"][0]]
            else:
                accounts[pid] = fallback

        return accounts

    def _get_location_id(self, complete_name: str) -> int:
        result = self._call(
            "stock.location", "search",
            [[["complete_name", "=", complete_name]]],
            {"limit": 1},
        )
        if not result:
            raise ValueError(f"Stock location '{complete_name}' not found in Odoo")
        return result[0]
