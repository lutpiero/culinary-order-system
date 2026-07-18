from __future__ import annotations

import xmlrpc.client
from typing import Any

from loguru import logger

from src.config import OdooConfig


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

    def create_sale_order(self, partner_id: int, lines: list[dict], ref: str | None = None, date_order: str | None = None) -> int:
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
            vals["date_order"] = date_order
        return self._call("sale.order", "create", [vals])

    def confirm_sale_order(self, order_id: int) -> bool:
        self._call("sale.order", "action_confirm", [[order_id]])
        logger.info(f"Odoo sale.order#{order_id} confirmed — stock.picking will be created")
        return True

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

    def _get_location_id(self, complete_name: str) -> int:
        result = self._call(
            "stock.location", "search",
            [[["complete_name", "=", complete_name]]],
            {"limit": 1},
        )
        if not result:
            raise ValueError(f"Stock location '{complete_name}' not found in Odoo")
        return result[0]
