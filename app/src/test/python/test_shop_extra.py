"""Loja: cupons, doações, produtos físicos com frete, carrinho abandonado, Pix manual, Stripe e PayPal."""

import json
import os
import sys
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from test_engine import msg  # noqa: E402
from test_shop import ShopApi, ShopBase, product  # noqa: E402
from telebot_engine import engine  # noqa: E402
from telebot_engine.shop import db, flow, gateways  # noqa: E402


class FakeStripe:
    def __init__(self, key, request=None):
        assert key == "sk_test"

    def checkout(self, order, title, return_url, minutes=60):
        FakeStripe.order = order
        return "cs_1", "https://checkout.stripe.com/c/cs_1"

    def status(self, ref):
        return gateways.PAID


class ExtraShopTests(ShopBase):
    def setUp(self):
        super().setUp()
        self._stripe = flow.Stripe
        flow.Stripe = FakeStripe

    def tearDown(self):
        flow.Stripe = self._stripe
        super().tearDown()

    def last_order(self):
        return json.loads(db.list_orders(self.dir))[0] if isinstance(db.list_orders(self.dir), list) else None

    def orders(self):
        return db.list_orders(self.dir)

    def test_coupon_percent_then_pay(self):
        shop = {"products": [product("a", price=10000)], "gateways": {"stripe": {"enabled": True}},
                "coupons": [{"code": "promo10", "percent": 10}]}
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], shop, payments={"stripe_key": "sk_test"})
        eng.run()
        offer = api.sent[-1]
        buttons = [b["callback_data"] for row in offer["reply_markup"]["inline_keyboard"] for b in row]
        oid = buttons[0].split(":")[2]
        self.assertIn("shop:k:" + oid, buttons)  # botão de cupom
        eng, api, cb = self.make([[self.cb("shop:k:" + oid, 2)], [msg("PROMO10", update_id=3)],
                                  [self.cb("shop:g:%s:stripe" % oid, 4)]], shop, payments={"stripe_key": "sk_test"})
        eng.run()
        order = db.get_order(self.dir, oid)
        self.assertEqual((order["coupon"], order["discount"], order["total"]), ("PROMO10", 1000, 9000))
        self.assertEqual(FakeStripe.order["total"], 9000)
        self.assertIn("checkout.stripe.com", json.dumps(api.sent))
        eng.shop.tick(eng)  # a Stripe diz que foi pago
        self.assertEqual(db.get_order(self.dir, oid)["status"], "paid")

    def test_bad_and_free_coupon(self):
        shop = {"products": [product("a", price=500)], "gateways": {"mercadopago": {"enabled": True}},
                "coupons": [{"code": "GRATIS", "percent": 100}, {"code": "VELHO", "amount": 100, "expires": "2020-01-01"}]}
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], shop)
        eng.run()
        oid = json.loads(db.list_orders(self.dir))[0]["id"]
        eng, api, cb = self.make([[self.cb("shop:k:" + oid, 2)], [msg("velho", update_id=3)],
                                  [self.cb("shop:k:" + oid, 4)], [msg("gratis", update_id=5)]], shop)
        eng.run()
        texts = " ".join(p.get("text", "") for p in api.sent)
        self.assertIn("venceu", texts)
        order = db.get_order(self.dir, oid)
        self.assertEqual((order["status"], order["gateway"], order["total"]), ("paid", "coupon", 0))
        self.assertIn("Seu acesso, Ana", texts)  # entregue

    def test_donation_with_preset_and_custom_amount(self):
        shop = {"products": [product("d", type="donation", price=200, amounts=[500, 1000], delivery={"text": "Obrigado!"})],
                "gateways": {"manual": {"enabled": True, "pix_key": "chave@pix.com", "pix_name": "Ana", "pix_city": "Recife"}}}
        eng, api, cb = self.make([[self.cb("shop:b:d", 1)], [self.cb("shop:a:d:other", 2)], [msg("1", update_id=3)],
                                  [msg("7,50", update_id=4)]], shop)
        eng.run()
        ask = api.sent[0]
        self.assertEqual([b["text"] for b in ask["reply_markup"]["inline_keyboard"][0]], ["R$ 5,00", "R$ 10,00"])
        order = json.loads(db.list_orders(self.dir))[0]
        self.assertEqual(order["total"], 750)
        texts = json.dumps(api.sent, ensure_ascii=False)
        self.assertIn("a partir de R$ 2,00", texts)
        self.assertIn("br.gov.bcb.pix", texts)  # Pix copia e cola gerado aqui
        self.assertIn("api.qrserver.com", json.dumps(api.calls))

    def test_manual_payment_confirmed_by_admin(self):
        shop = {"products": [product("a", price=1000)], "gateways": {"manual": {"enabled": True, "instructions": "Banco X, conta 1"}}}
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], shop)
        eng.run()
        oid = json.loads(db.list_orders(self.dir))[0]["id"]
        eng, api, cb = self.make([[self.cb("shop:c:" + oid, 2)], [self.cb("shop:c:" + oid, 3)],
                                  [self.cb("shop:m:%s:ok" % oid, 4, user=42)], [self.cb("shop:m:%s:ok" % oid, 5, user=500)]], shop)
        eng.run()
        to_admin = [p for p in api.sent if p["chat_id"] == 500]
        self.assertEqual(len(to_admin), 2)  # aviso "diz que pagou" só uma vez + aviso de venda
        self.assertIn("diz que pagou", to_admin[0]["text"])
        self.assertEqual(db.get_order(self.dir, oid)["status"], "paid")

    def test_physical_product_asks_address_and_adds_shipping(self):
        shop = {"products": [product("p", type="physical", price=3000, delivery=None)],
                "gateways": {"mercadopago": {"enabled": True}}, "shipping": {"fee": 1500, "free_over": 0}}
        eng, api, cb = self.make([[self.cb("shop:b:p", 1)], [msg("rua", update_id=2)],
                                  [msg("Rua das Flores, 10, Centro, Recife 50000-000", update_id=3)]], shop)
        eng.run()
        order = json.loads(db.list_orders(self.dir))[0]
        self.assertEqual((order["shipping"], order["total"]), (1500, 4500))
        self.assertIn("Rua das Flores", order["address"])
        texts = " ".join(p.get("text", "") for p in api.sent)
        self.assertIn("Para onde enviamos", texts)
        self.assertIn("incompleto", texts)

    def test_abandoned_cart_reminder(self):
        shop = {"products": [product("a")], "gateways": {"mercadopago": {"enabled": True}},
                "abandoned": {"enabled": True, "minutes": 20}}
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], shop)
        eng.run()
        oid = json.loads(db.list_orders(self.dir))[0]["id"]
        now = time.time()
        eng.shop.tick(eng, now + 60)
        before = len(api.sent)
        eng.shop.tick(eng, now + 21 * 60)
        eng.shop.tick(eng, now + 25 * 60)
        reminders = [p for p in api.sent[before:] if "esqueceu" in p.get("text", "")]
        self.assertEqual(len(reminders), 1)
        self.assertEqual(reminders[0]["reply_markup"]["inline_keyboard"][0][0]["callback_data"], "shop:r:" + oid)


    def test_app_ship_notifies_with_tracking(self):
        shop = {"products": [product("p", type="physical", delivery=None)], "gateways": {"mercadopago": {"enabled": True}}}
        eng, api, cb = self.make([], shop)
        eng.config = engine.config_mod.load(eng.config_path)
        order = eng.shop.buy({"id": 42, "first_name": "Ana"}, 42, [{"i": "p"}])
        fake = ShopApi([])
        original = engine.TelegramApi
        engine.TelegramApi = lambda token: fake
        try:
            out = json.loads(flow.app_ship("1:x", self.dir, order["id"], "BR123<x>"))
        finally:
            engine.TelegramApi = original
        self.assertTrue(out["ok"])
        self.assertIn("BR123&lt;x&gt;", fake.sent[-1]["text"])
        self.assertTrue(db.get_order(self.dir, order["id"])["shipped"])


class PixCodeTests(unittest.TestCase):
    def test_crc_matches_central_bank_example(self):
        p = ("00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-4266554400005204000053039865802BR"
             "5913Fulano de Tal6008BRASILIA62070503***6304")
        self.assertEqual(gateways._crc16(p), "1D3D")
        code = gateways.pix_code("chave@x.com", "José da Silva", "São Paulo", 1990, "ABC-123")
        self.assertIn("540519.90", code)
        self.assertIn("5913Jose da Silva", code)
        self.assertTrue(code.endswith(gateways._crc16(code[:-4])))

    def test_stripe_amounts_and_lines(self):
        self.assertEqual(gateways.Stripe.unit_amount(150000, "CLP"), 1500)
        self.assertEqual(gateways.Stripe.unit_amount(1990, "BRL"), 1990)
        order = {"items": [{"name": "A", "qty": 2, "price": 1000}], "total": 2500, "discount": 0, "shipping": 500}
        self.assertEqual(sum(l["qty"] * l["price"] for l in gateways._lines(order)), 2500)
        order.update(discount=300, total=2200)
        self.assertEqual(gateways._lines(order), [{"name": "A", "qty": 1, "price": 2200}])

    def test_paypal_approved_is_captured(self):
        calls = []
        answers = [{"access_token": "T"}, {"id": "PP1", "links": [{"rel": "payer-action", "href": "https://paypal/x"}]},
                   {"status": "APPROVED"}, {"status": "COMPLETED"}]

        def request(method, url, body=None, headers=None, timeout=30):
            calls.append((method, url, body))
            return answers.pop(0)
        pp = gateways.PayPal("ID", "SECRET", sandbox=True, request=request)
        order = {"id": "ABC", "currency": "BRL", "total": 1990}
        self.assertEqual(pp.checkout(order, "Loja", "https://t.me/bot"), ("PP1", "https://paypal/x"))
        self.assertEqual(calls[1][2]["purchase_units"][0]["amount"], {"currency_code": "BRL", "value": "19.90"})
        self.assertEqual(pp.status("PP1"), gateways.PAID)
        self.assertTrue(calls[-1][1].endswith("/v2/checkout/orders/PP1/capture"))
        with self.assertRaises(gateways.PaymentError):
            pp.checkout(dict(order, currency="CLP"), "Loja", "https://t.me/bot")


if __name__ == "__main__":
    unittest.main()
