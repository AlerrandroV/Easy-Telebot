"""Loja: catálogo, vitrine, pedidos, gateways (simulados), entrega e assinaturas."""

import base64
import json
import os
import sys
import tempfile
import time
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import admins, engine  # noqa: E402
from telebot_engine.shop import catalog, db, flow, gateways  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402


class ShopApi(FakeApi):
    def call(self, method, params=None, timeout=30, files=None):
        if method in ("sendInvoice", "createChatInviteLink", "banChatMember", "unbanChatMember",
                      "answerPreCheckoutQuery", "answerCallbackQuery"):
            self.calls.append((method, params, files))
            if method == "sendInvoice":
                self.sent.append(dict(params, _method="sendInvoice"))
                return self._msg(params)
            if method == "createChatInviteLink":
                return {"invite_link": "https://t.me/+CONVITE"}
            return True
        return super().call(method, params, timeout, files)


class FakeMP:
    status_value = gateways.PENDING
    created = []

    def __init__(self, token, request=None):
        assert token == "MPTOKEN"

    def checkout(self, order, title, minutes=60):
        FakeMP.created.append(order["id"])
        return "PREF1", "https://mpago.la/abc"

    def pix(self, order, title, payer_email, minutes=30):
        return "PIX1", "00020126PIXCODE", b"\x89PNG fake"

    def status(self, gateway, ref, order_id):
        return FakeMP.status_value


class FakeBlink:
    def __init__(self, key, request=None):
        assert key == "BLINKKEY"

    def sats_for(self, cents, currency):
        return cents * 3

    def invoice(self, sats, memo, minutes=60):
        return "lnbc1fatura", "hash"

    def status(self, pr):
        return gateways.PAID


def product(pid, **kw):
    base = {"id": pid, "name": "Produto " + pid, "price": 1990, "description": "Descrição",
            "delivery": {"text": "Seu acesso, {nome}: https://exemplo.com/" + pid}}
    base.update(kw)
    return base


class ShopBase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self._mp, self._blink = flow.MercadoPago, flow.Blink
        flow.MercadoPago, flow.Blink = FakeMP, FakeBlink
        FakeMP.status_value = gateways.PENDING
        admins.redeem_code(self.dir, json.loads(admins.create_code(self.dir, 1))["code"],
                           {"id": 500, "first_name": "Dono"})

    def tearDown(self):
        flow.MercadoPago, flow.Blink = self._mp, self._blink
        db.close(self.dir)

    def make(self, script, shop, payments=None):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": [], "shop": dict({"enabled": True}, **shop)}, fh)
        cb = Callbacks()
        api = ShopApi(script)
        eng = engine.Engine("1:x", path, self.dir, cb, api=api,
                            payments=json.dumps(payments or {"mp_token": "MPTOKEN", "blink_key": "BLINKKEY"}))
        eng.shop.start_worker = lambda: None  # o teste roda as verificações na mão
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api, cb

    def cb(self, data, update_id, user=42):
        return {"update_id": update_id, "callback_query": {
            "id": "q%d" % update_id, "from": {"id": user, "first_name": "Ana"}, "data": data,
            "message": {"message_id": 1, "chat": {"id": user, "type": "private"}}}}



class ShopTests(ShopBase):
    def test_catalog_and_miniapp_url(self):
        eng, api, cb = self.make([[msg("/loja")]], {
            "products": [product("a"), product("b", active=False), product("c", stock=0)],
            "gateways": {"mercadopago": {"enabled": True}}})
        eng.run()
        listing = api.sent[0]
        rows = listing["reply_markup"]["inline_keyboard"]
        self.assertEqual(rows, [[{"text": "Produto a · R$ 19,90", "callback_data": "shop:p:a"}]])
        url = api.sent[1]["reply_markup"]["keyboard"][0][0]["web_app"]["url"]
        self.assertTrue(url.startswith(catalog.DEFAULT_STORE_URL + "#z="))
        z = url.split("#z=")[1]
        data = json.loads(zlib.decompress(base64.urlsafe_b64decode(z + "=" * (-len(z) % 4))))
        self.assertEqual([p["i"] for p in data["p"]], ["a"])
        self.assertEqual(data["p"][0]["f"], "R$ 19,90")
        menu = [p for m, p, f in api.calls if m == "setMyCommands"][0]["commands"]
        self.assertIn("loja", [c["command"] for c in menu])

    def test_checkout_link_then_auto_confirm_and_deliver_once(self):
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], {
            "products": [product("a")], "gateways": {"mercadopago": {"enabled": True}}})
        eng.run()
        pay = api.sent[-1]
        self.assertIn("Pague pelo Mercado Pago", pay["text"])
        self.assertEqual(pay["reply_markup"]["inline_keyboard"][0][0]["url"], "https://mpago.la/abc")
        order_id = FakeMP.created[-1]
        self.assertEqual(db.get_order(self.dir, order_id)["gateway"], "mp")

        eng.shop.tick(eng, now=time.time())
        self.assertEqual(db.get_order(self.dir, order_id)["status"], "pending")
        FakeMP.status_value = gateways.PAID
        eng.shop.tick(eng, now=time.time() + 20)
        eng.shop.tick(eng, now=time.time() + 40)  # não entrega de novo
        texts = [p.get("text", "") for p in api.sent]
        self.assertEqual(sum("Seu acesso, Ana: https://exemplo.com/a" in t for t in texts), 1)
        order = db.get_order(self.dir, order_id)
        self.assertEqual((order["status"], order["delivered"]), ("paid", 1))
        admin_note = [p for p in api.sent if p["chat_id"] == 500]
        self.assertIn("Nova venda: pedido " + order_id, admin_note[0]["text"])
        self.assertEqual(cb.of("sale")[0]["total"], "R$ 19,90")
        self.assertEqual(json.loads(db.stats(self.dir, 0))["totals"], {"BRL": 1990})

    def test_choose_gateway_bitcoin_and_check_button(self):
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], {
            "products": [product("a", stars=50)],
            "gateways": {"mercadopago": {"enabled": True, "pix": True, "payer_email": "loja@exemplo.com"},
                         "blink": {"enabled": True}, "stars": {"enabled": True}}})
        eng.run()
        choice = api.sent[-1]
        options = [r[0]["callback_data"].split(":")[-1] for r in choice["reply_markup"]["inline_keyboard"][:-1]]
        self.assertEqual(options, ["pix", "mp", "btc", "xtr"])
        oid = choice["reply_markup"]["inline_keyboard"][0][0]["callback_data"].split(":")[2]
        eng2, api2, _ = self.make([[self.cb("shop:g:%s:btc" % oid, 2)], [self.cb("shop:c:%s" % oid, 3)],
                                   [self.cb("shop:c:%s" % oid, 4, user=99)]], {
            "products": [product("a", stars=50)], "gateways": {"blink": {"enabled": True}}})
        eng2.run()
        invoice = [p for p in api2.sent if "lnbc1fatura" in p.get("text", "")][0]
        self.assertIn("5.970 sats", invoice["text"])
        self.assertEqual(db.get_order(self.dir, oid)["status"], "paid")
        answers = [p.get("text") for m, p, f in api2.calls if m == "answerCallbackQuery"]
        self.assertEqual(answers[-2:], ["Pagamento confirmado!", "Pedido não encontrado."])

    def test_pix_sends_qr_and_code(self):
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], {
            "products": [product("a")],
            "gateways": {"mercadopago": {"enabled": True, "pix": True, "payer_email": "loja@exemplo.com"}}})
        # duas opções (pix e link): escolhe pix
        eng.run()
        oid = api.sent[-1]["reply_markup"]["inline_keyboard"][0][0]["callback_data"].split(":")[2]
        eng2, api2, _ = self.make([[self.cb("shop:g:%s:pix" % oid, 2)]], {
            "products": [product("a")],
            "gateways": {"mercadopago": {"enabled": True, "pix": True, "payer_email": "loja@exemplo.com"}}})
        eng2.run()
        photo = [p for p in api2.sent if "_files" in p][0]
        self.assertIn("pedido " + oid, photo["caption"])
        self.assertIn("<code>00020126PIXCODE</code>", api2.sent[-1]["text"])
        self.assertFalse(os.listdir(os.path.join(self.dir, "shop_tmp")))  # QR temporário apagado

    def test_stars_flow(self):
        shop = {"products": [product("a", stars=50)], "gateways": {"stars": {"enabled": True}}}
        eng, api, cb = self.make([[self.cb("shop:b:a", 1)]], shop, payments={})
        eng.run()
        invoice = [p for p in api.sent if p.get("_method") == "sendInvoice"][0]
        self.assertEqual((invoice["currency"], invoice["prices"][0]["amount"]), ("XTR", 50))
        oid = invoice["payload"]
        paid = msg("", update_id=4)
        paid["message"]["successful_payment"] = {"currency": "XTR", "total_amount": 50, "invoice_payload": oid,
                                                 "telegram_payment_charge_id": "TG1"}
        eng2, api2, cb2 = self.make([
            [{"update_id": 2, "pre_checkout_query": {"id": "pc1", "from": {"id": 42}, "currency": "XTR",
                                                    "total_amount": 49, "invoice_payload": oid}}],
            [{"update_id": 3, "pre_checkout_query": {"id": "pc2", "from": {"id": 42}, "currency": "XTR",
                                                    "total_amount": 50, "invoice_payload": oid}}],
            [paid],
        ], shop, payments={})
        eng2.run()
        checks = [p["ok"] for m, p, f in api2.calls if m == "answerPreCheckoutQuery"]
        self.assertEqual(checks, [False, True])
        order = db.get_order(self.dir, oid)
        self.assertEqual((order["status"], order["gateway_ref"]), ("paid", "TG1"))
        self.assertTrue(any("Seu acesso" in p.get("text", "") for p in api2.sent))
        self.assertEqual(cb2.of("sale")[0]["total"], "50 estrelas")

    def test_miniapp_cart_uses_catalog_prices_and_stock(self):
        cart = msg("", update_id=1)
        cart["message"]["web_app_data"] = {"data": json.dumps({"cart": [
            {"i": "a", "q": 3, "v": 1}, {"i": "b", "q": 5}, {"i": "zzz", "q": 1}]}), "button_text": "Abrir loja"}
        eng, api, cb = self.make([[cart]], {
            "products": [product("a"), product("b", price=500, stock=2)],
            "gateways": {"mercadopago": {"enabled": True}}})
        eng.run()
        order = db.get_order(self.dir, FakeMP.created[-1])
        self.assertEqual([(i["id"], i["qty"]) for i in order["items"]], [("a", 3), ("b", 2)])
        self.assertEqual(order["total"], 3 * 1990 + 2 * 500)

    def test_subscription_invite_renewal_reminder_and_expiry(self):
        shop = {"products": [product("vip", type="subscription", chat_id=-1001, chat_title="Canal VIP", days=30,
                                     delivery=None)],
                "gateways": {"mercadopago": {"enabled": True}}}
        eng, api, cb = self.make([[self.cb("shop:b:vip", 1)]], shop)
        eng.run()
        FakeMP.status_value = gateways.PAID
        eng.shop.tick(eng, now=time.time() + 20)
        invite = [p for m, p, f in api.calls if m == "createChatInviteLink"][0]
        self.assertEqual((invite["chat_id"], invite["member_limit"]), (-1001, 1))
        welcome = [p for p in api.sent if "Entrar em Canal VIP" in json.dumps(p, ensure_ascii=False)][0]
        self.assertEqual(welcome["reply_markup"]["inline_keyboard"][0][0]["url"], "https://t.me/+CONVITE")
        sub = db.get_subscription(self.dir, 42, -1001)
        self.assertAlmostEqual(sub["expires_at"] - time.time(), 30 * 86400, delta=60)

        # renovação antes de vencer soma 30 dias
        FakeMP.status_value = gateways.PENDING
        eng.shop.buy({"id": 42, "first_name": "Ana"}, 42, [{"i": "vip", "q": 3}])
        FakeMP.status_value = gateways.PAID
        eng.shop.tick(eng, now=time.time() + 40)
        sub2 = db.get_subscription(self.dir, 42, -1001)
        self.assertEqual(sub2["expires_at"] - sub["expires_at"], 30 * 86400)
        self.assertEqual(len([m for m, p, f in api.calls if m == "createChatInviteLink"]), 1)

        eng.shop._last_subs = 0
        eng.shop.check_subscriptions(eng, now=sub2["expires_at"] - 2 * 86400)
        self.assertTrue(any("vence em" in p.get("text", "") for p in api.sent))
        eng.shop.check_subscriptions(eng, now=sub2["expires_at"] + 1)
        self.assertIn("banChatMember", [m for m, p, f in api.calls])
        self.assertEqual(db.get_subscription(self.dir, 42, -1001)["status"], "expired")
        self.assertEqual(cb.of("subscription_expired")[0]["chat"], "Canal VIP")

    def test_app_manual_confirm_and_remove_subscriber(self):
        shop = {"products": [product("vip", type="subscription", chat_id=-1001, chat_title="VIP", days=7,
                                     delivery=None)], "gateways": {"mercadopago": {"enabled": True}}}
        eng, api, cb = self.make([], shop)
        eng.config = engine.config_mod.load(eng.config_path)
        order = eng.shop.buy({"id": 42, "first_name": "Ana"}, 42, [{"i": "vip"}])
        fake = ShopApi([])
        original = engine.TelegramApi
        engine.TelegramApi = lambda token: fake
        try:
            out = json.loads(flow.app_confirm("1:x", self.dir, order["id"]))
            again = json.loads(flow.app_confirm("1:x", self.dir, order["id"]))
            removed = json.loads(flow.app_subscription("1:x", self.dir, 42, -1001, "remove"))
        finally:
            engine.TelegramApi = original
        self.assertEqual((out["ok"], out["delivered"]), (True, True))
        self.assertEqual(again["error"]["code"], "pay_already")
        self.assertTrue(removed["removed"])
        self.assertEqual(db.get_subscription(self.dir, 42, -1001)["status"], "removed")
        self.assertIn("encerrado por um administrador", fake.sent[-1]["text"])


class GatewayTests(unittest.TestCase):
    def fake(self, responses):
        calls = []

        def request(method, url, body=None, headers=None, timeout=30):
            calls.append((method, url, body, headers))
            return responses.pop(0)
        return request, calls

    def test_mercadopago_checkout_and_status(self):
        req, calls = self.fake([{"id": "P1", "init_point": "https://mp/x"}, {"results": [{"status": "pending"}]},
                                {"results": [{"status": "approved"}]}, {"status": "cancelled"}])
        mp = gateways.MercadoPago("TOKEN", request=req)
        order = {"id": "ABC", "currency": "BRL", "total": 2500,
                 "items": [{"id": "a", "name": "Curso", "qty": 1, "price": 2500}]}
        self.assertEqual(mp.checkout(order, "Minha Loja"), ("P1", "https://mp/x"))
        body = calls[0][2]
        self.assertEqual((body["external_reference"], body["items"][0]["unit_price"]), ("ABC", 25.0))
        self.assertEqual(calls[0][3]["Authorization"], "Bearer TOKEN")
        self.assertEqual(mp.status("mp", "P1", "ABC"), gateways.PENDING)
        self.assertEqual(mp.status("mp", "P1", "ABC"), gateways.PAID)
        self.assertEqual(mp.status("pix", "99", "ABC"), gateways.EXPIRED)
        self.assertIn("external_reference=ABC", calls[1][1])

    def test_blink_invoice_and_status(self):
        req, calls = self.fake([
            {"data": {"me": {"username": "loja", "defaultAccount": {"wallets": [
                {"id": "U1", "walletCurrency": "USD"}, {"id": "B1", "walletCurrency": "BTC", "balance": 7}]}}}},
            {"data": {"lnInvoiceCreate": {"invoice": {"paymentRequest": "lnbc1x", "paymentHash": "h"}, "errors": []}}},
            {"data": {"lnInvoicePaymentStatusByPaymentRequest": {"status": "PAID"}}},
            {"errors": [{"message": "Not authorized"}]},
        ])
        b = gateways.Blink("KEY", request=req)
        self.assertEqual(b.invoice(1000, "pedido"), ("lnbc1x", "h"))
        self.assertEqual(calls[1][2]["variables"]["i"]["walletId"], "B1")
        self.assertEqual(calls[1][3]["X-API-KEY"], "KEY")
        self.assertEqual(b.status("lnbc1x"), gateways.PAID)
        with self.assertRaises(gateways.PaymentError) as ctx:
            b.status("lnbc1x")
        self.assertEqual(ctx.exception.code, gateways.AUTH)

    def test_prices(self):
        self.assertEqual(catalog.format_price(123456, "BRL"), "R$ 1.234,56")
        self.assertEqual(catalog.format_price(500000, "CLP"), "CLP$ 5.000")


if __name__ == "__main__":
    unittest.main()


class HostingTests(unittest.TestCase):
    def fake(self, responses):
        calls = []

        def request(method, url, body=None, headers=None, timeout=60):
            calls.append((method, url, body, headers))
            return responses.pop(0)
        return request, calls

    def test_imgbb_upload(self):
        from telebot_engine.shop import hosting
        req, calls = self.fake([(200, {"data": {"url": "https://i.ibb.co/x/a.jpg"}}, b""),
                                (400, {"error": {"message": "Invalid API v1 key."}}, b"")])
        self.assertEqual(hosting.upload_image("K", "QUJD", request=req), "https://i.ibb.co/x/a.jpg")
        self.assertIn("key=K", calls[0][2])
        self.assertIn("image=QUJD", calls[0][2])
        with self.assertRaises(hosting.HostingError) as ctx:
            hosting.upload_image("K", "QUJD", request=req)
        self.assertEqual(ctx.exception.code, hosting.AUTH)

    def test_gist_create_update_and_recreate(self):
        from telebot_engine.shop import hosting
        raw = "https://gist.githubusercontent.com/u/G1/raw/abc123/catalogo.json"
        ok = (201, {"id": "G1", "files": {"catalogo.json": {"raw_url": raw}}}, b"")
        req, calls = self.fake([ok, (200, {"id": "G1", "files": {"catalogo.json": {"raw_url": raw}}}, b""),
                                (404, {"message": "Not Found"}, b""), ok, (401, {"message": "Bad credentials"}, b"")])
        self.assertEqual(hosting.publish_gist("T", "", "{}", request=req), {"gist_id": "G1", "raw_url": raw})
        self.assertEqual((calls[0][0], calls[0][2]["public"]), ("POST", False))
        self.assertEqual(calls[0][3]["Authorization"], "Bearer T")
        self.assertIn("User-Agent", calls[0][3])
        hosting.publish_gist("T", "G1", "{}", request=req)
        self.assertEqual((calls[1][0], calls[1][1]), ("PATCH", "https://api.github.com/gists/G1"))
        hosting.publish_gist("T", "G1", "{}", request=req)  # apagado no GitHub: cria outro
        self.assertEqual([c[0] for c in calls[2:4]], ["PATCH", "POST"])
        with self.assertRaises(hosting.HostingError) as ctx:
            hosting.publish_gist("T", "G1", "{}", request=req)
        self.assertEqual(ctx.exception.code, hosting.AUTH)

    def test_storefront_and_link(self):
        shop = catalog.normalize({"products": [product("a", stock=3), product("b"), product("c", active=False)]},
                                 lambda m: m)
        data = catalog.storefront_data(shop, "Loja", {"a": "data:image/jpeg;base64,AAAA"}, sold={"a": 1})
        self.assertEqual([(p["i"], p["q"]) for p in data["p"]], [("a", 2), ("b", -1)])
        self.assertEqual(data["p"][0]["m"], "data:image/jpeg;base64,AAAA")
        self.assertIn("#z=", catalog.miniapp_url(shop, "Loja"))
        shop["catalog_url"] = "https://gist.githubusercontent.com/u/G1/raw/abc/catalogo.json"
        self.assertEqual(catalog.miniapp_url(shop, "Loja"), catalog.DEFAULT_STORE_URL + "#u=" +
                         "https%3A%2F%2Fgist.githubusercontent.com%2Fu%2FG1%2Fraw%2Fabc%2Fcatalogo.json")
