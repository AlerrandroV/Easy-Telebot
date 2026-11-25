"""Loja no Telegram: catálogo, carrinho (mini app), cobrança, entrega e assinaturas de canais.

Fluxo da pessoa:
1. ``/loja`` (ou ``/start loja``): lista de produtos com botões e, se ligado, o botão
   "Abrir loja" que abre a vitrine (mini app).
2. Escolhe um produto (ou monta o carrinho na vitrine) -> pedido -> forma de pagamento.
3. Paga: Mercado Pago (link ou Pix no chat), Bitcoin pela Lightning (Blink) ou
   Telegram Stars. O bot confere sozinho a cada poucos segundos (e no botão "Já paguei").
4. Pago: entrega a mensagem do produto (texto, arquivo, link...) ou, se for assinatura,
   um convite pessoal para o canal/grupo, válido por 24 h e para uma pessoa só.

Assinaturas: perto de vencer, a pessoa recebe um lembrete com botão para renovar;
vencida, é removida do canal/grupo (sem banimento: pode voltar se renovar).
"""

import json
import os
import threading
import time

from .. import admins as admins_mod
from .. import errors, i18n, template
from ..api import Aborted, TelegramApi, TelegramError
from . import catalog, db
from .gateways import EXPIRED, PAID, Blink, MercadoPago, PaymentError

CB = "shop:"
ORDER_TTL = 60 * 60       # pedido sem pagamento expira em 1 h
CHECK_EVERY = 15          # segundos entre verificações de pagamento
SUBS_EVERY = 5 * 60       # segundos entre verificações de assinaturas
MAX_QTY = 20
GATEWAYS = ("pix", "mp", "btc", "xtr")  # nomes na tela: i18n "shop.gw_<código>"


def parse_payments(payments):
    if isinstance(payments, str):
        try:
            payments = json.loads(payments or "{}")
        except ValueError:
            payments = {}
    payments = payments if isinstance(payments, dict) else {}
    return {"mp_token": str(payments.get("mp_token") or ""), "blink_key": str(payments.get("blink_key") or "")}


def _name(user):
    full = " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x)
    if user.get("username"):
        return "%s (@%s)" % (full, user["username"]) if full else "@" + user["username"]
    return full or str(user.get("id") or user.get("user_id") or "")


class ShopFlow:
    def __init__(self, engine, payments=None):
        self.engine = engine
        self.secrets = parse_payments(payments)
        self._worker = None
        self._last_subs = 0
        self._checked = {}  # id do pedido -> última consulta ao gateway

    # -- atalhos ------------------------------------------------------------------------

    @property
    def shop(self):
        return self.engine.config.get("shop") or catalog.normalize({}, lambda m: None)

    @property
    def enabled(self):
        return bool(self.shop.get("enabled"))

    @property
    def base_dir(self):
        return self.engine.base_dir

    def price(self, cents):
        return catalog.format_price(cents, self.shop["currency"])

    def title(self):
        bot = self.engine.bot or {}
        return bot.get("first_name") or bot.get("username") or self.t("shop.default_title")

    def t(self, key, *args):
        """Texto no idioma do bot."""
        return i18n.t(key, self.engine.lang, *args)

    def date(self, ts):
        return time.strftime(self.t("fmt.date"), time.localtime(ts))

    def _context(self, user, chat_id):
        stamp = self.engine._now()
        date, _, hour = stamp.partition(" ")
        bot_username = (self.engine.bot or {}).get("username", "")
        return template.build_context({"from": user, "chat": {"id": chat_id, "type": "private"}}, "",
                                      bot_username, date, hour)

    def _send(self, sender, chat_id, text, keyboard=None, html=True):
        params = {"chat_id": chat_id, "text": text, "link_preview_options": {"is_disabled": True}}
        if html:
            params["parse_mode"] = "HTML"
        if keyboard:
            params["reply_markup"] = {"inline_keyboard": keyboard}
        return sender._call_send("sendMessage", params, "loja")

    @staticmethod
    def _raw(sender, method, params):
        """Chamada direta (sem registro no mensageiro). ``None`` se o Telegram recusar."""
        try:
            return sender.api.call(method, params, timeout=30)
        except Aborted:
            raise
        except Exception as exc:  # noqa: BLE001
            sender.emit("error", context=method, command="loja", retry_in=None, fatal=False, **errors.classify(exc))
            return None

    def _answer(self, query, text=None, alert=False):
        params = {"callback_query_id": query.get("id")}
        if text:
            params["text"] = text
            params["show_alert"] = alert
        self.engine._try_call("answerCallbackQuery", params)

    def sold(self):
        return db.sold_counts(self.base_dir)

    def remaining(self, product, sold=None):
        if product["stock"] < 0:
            return None
        sold = self.sold() if sold is None else sold
        return max(0, product["stock"] - sold.get(product["id"], 0))

    def for_sale(self):
        sold = self.sold()
        return [p for p in catalog.available(self.shop) if self.remaining(p, sold) != 0]

    def gateways(self, order):
        g = self.shop["gateways"]
        out = []
        if g["mercadopago"]["enabled"] and self.secrets["mp_token"]:
            if g["mercadopago"]["pix"] and g["mercadopago"]["payer_email"]:
                out.append("pix")
            out.append("mp")
        if g["blink"]["enabled"] and self.secrets["blink_key"]:
            out.append("btc")
        if g["stars"]["enabled"] and order.get("stars"):
            out.append("xtr")
        return out

    # -- entrada (thread do motor) --------------------------------------------------------

    def handle_message(self, message, parsed):
        """``True`` se a mensagem era da loja (o motor não responde mais nada)."""
        if message.get("successful_payment"):
            self.on_stars_paid(message)
            return True
        if not self.enabled:
            return False
        if message.get("web_app_data"):
            self.on_cart(message)
            return True
        if parsed is None:
            return False
        name, args = parsed
        if name == "start" and args.strip().lower() in ("loja", "shop"):
            name = self.shop["triggers"][0]
        if name in self.shop["triggers"]:
            self.show_catalog(message)
            return True
        if name == catalog.SUBS_TRIGGER and any(p["type"] == "subscription" for p in self.shop["products"]):
            self.show_subscriptions(message)
            return True
        return False

    def handle_callback(self, query):
        data = query.get("data") or ""
        if not data.startswith(CB):
            return False
        parts = data[len(CB):].split(":")
        user = query.get("from") or {}
        chat_id = ((query.get("message") or {}).get("chat") or {}).get("id") or user.get("id")
        action, arg = parts[0], parts[1] if len(parts) > 1 else ""
        if action == "p":
            self._answer(query)
            self.show_product(chat_id, arg)
        elif action == "b":
            self._answer(query)
            self.buy(user, chat_id, [{"i": arg, "q": 1}])
        elif action in ("g", "c", "x"):
            order = db.get_order(self.base_dir, arg)
            if order is None or order["user_id"] != user.get("id"):
                self._answer(query, self.t("shop.order_not_found"))
            elif action == "x":
                if order["status"] == "pending":
                    db.set_status(self.base_dir, order["id"], "cancelled")
                self._answer(query, self.t("shop.order_cancelled"))
            elif order["status"] == "paid":
                self._answer(query, self.t("shop.order_paid"))
            elif order["status"] != "pending":
                self._answer(query, self.t("shop.order_expired"), alert=True)
            elif action == "g":
                self._answer(query)
                self.charge(order, parts[2] if len(parts) > 2 else "", self.engine)
            else:
                self.check_now(query, order)
        else:
            self._answer(query)
        return True

    def on_pre_checkout(self, query):
        """Telegram Stars: confirma que o pedido ainda vale antes de cobrar."""
        order = db.get_order(self.base_dir, query.get("invoice_payload"))
        ok = (order is not None and order["status"] == "pending" and query.get("currency") == "XTR"
              and int(query.get("total_amount") or 0) == (order.get("stars") or -1))
        params = {"pre_checkout_query_id": query.get("id"), "ok": ok}
        if not ok:
            params["error_message"] = self.t("shop.order_unavailable")
        self.engine._try_call("answerPreCheckoutQuery", params)

    def on_stars_paid(self, message):
        pay = message.get("successful_payment") or {}
        self.complete(pay.get("invoice_payload"), self.engine, "xtr", pay.get("telegram_payment_charge_id"))

    # -- catálogo ---------------------------------------------------------------------------

    def show_catalog(self, message):
        chat = message.get("chat") or {}
        user = message.get("from") or {}
        if chat.get("type") != "private":
            username = (self.engine.bot or {}).get("username", "")
            self._send(self.engine, chat.get("id"), self.t("shop.private_only"),
                       [[{"text": self.t("shop.open"), "url": "https://t.me/%s?start=loja" % username}]] if username else None)
            return
        products = self.for_sale()
        if not products:
            self._send(self.engine, chat["id"], self.t("shop.empty"))
            return
        intro = template.render(self.shop["intro"] or self.t("shop.intro"), self._context(user, chat["id"]))
        keyboard = [[{"text": "%s · %s" % (p["name"], self.price(p["price"])), "callback_data": CB + "p:" + p["id"]}]
                    for p in products[:50]]
        self._send(self.engine, chat["id"], intro, keyboard)
        if self.shop["miniapp"] and self.shop["store_url"].startswith("https://"):
            self.engine._call_send("sendMessage", {
                "chat_id": chat["id"],
                "text": self.t("shop.storefront_hint"),
                "reply_markup": {"keyboard": [[{"text": self.t("shop.open"), "web_app": {
                    "url": catalog.miniapp_url(self.shop, self.title(), self.sold())}}]], "resize_keyboard": True},
            }, "loja")

    def show_product(self, chat_id, product_id):
        p = catalog.find(self.shop, product_id)
        if p is None or p not in self.for_sale():
            self._send(self.engine, chat_id, self.t("shop.product_gone"))
            return
        lines = ["<b>%s</b>" % template.escape(p["name"])]
        if p["description"]:
            lines += ["", template.escape(p["description"])]
        lines += ["", "<b>%s</b>" % self.price(p["price"])]
        if p["type"] == "subscription":
            lines[-1] += " · acesso por %d dias a %s" % (p["days"], template.escape(p["chat_title"] or "canal VIP"))
        left = self.remaining(p)
        if left is not None:
            lines.append("%d em estoque" % left)
        keyboard = [[{"text": "Assinar" if p["type"] == "subscription" else "Comprar",
                      "callback_data": CB + "b:" + p["id"]}]]
        text = "\n".join(lines)
        photo = p.get("photo")
        if photo and len(template.html_to_plain(text)) <= 1024:
            media = {"type": "photo", "file": photo, "name": os.path.basename(photo)}
            params = {"chat_id": chat_id, "caption": text, "parse_mode": "HTML",
                      "reply_markup": {"inline_keyboard": keyboard}}
            if self.engine._send_media(media, params, "loja"):
                return
        self._send(self.engine, chat_id, text, keyboard)

    def show_subscriptions(self, message):
        user = message.get("from") or {}
        chat_id = (message.get("chat") or {}).get("id")
        subs = db.user_subscriptions(self.base_dir, user.get("id"))
        if not subs:
            self._send(self.engine, chat_id, self.t("shop.no_subscriptions", self.shop["triggers"][0]))
            return
        lines, keyboard, now = [self.t("shop.your_subscriptions"), ""], [], time.time()
        for s in subs:
            active = s["status"] == "active" and s["expires_at"] > now
            lines.append("%s: %s %s" % (template.escape(s["chat_title"] or self.t("shop.channel")),
                                        self.t("shop.active_until" if active else "shop.expired_on"),
                                        self.date(s["expires_at"])))
            p = catalog.find(self.shop, s["product_id"])
            if p and p in self.for_sale():
                keyboard.append([{"text": self.t("shop.renew_named", s["chat_title"] or p["name"]),
                                  "callback_data": CB + "b:" + p["id"]}])
        self._send(self.engine, chat_id, "\n".join(lines), keyboard)

    # -- pedido e cobrança --------------------------------------------------------------------

    def on_cart(self, message):
        """Carrinho enviado pela vitrine: ``{"cart": [{"i": id, "q": quantidade}]}``."""
        try:
            data = json.loads((message.get("web_app_data") or {}).get("data") or "{}")
        except ValueError:
            data = {}
        cart = data.get("cart") if isinstance(data, dict) else None
        chat_id = (message.get("chat") or {}).get("id")
        if not isinstance(cart, list) or not cart:
            self._send(self.engine, chat_id, self.t("shop.cart_empty"))
            return
        self.buy(message.get("from") or {}, chat_id, cart)

    def buy(self, user, chat_id, cart):
        """Cria o pedido com os preços atuais do catálogo (nunca os da vitrine) e oferece o pagamento."""
        sold, items = self.sold(), []
        for entry in cart[:30]:
            if not isinstance(entry, dict):
                continue
            p = catalog.find(self.shop, str(entry.get("i") or ""))
            if p is None or not p["active"]:
                continue
            qty = 1 if p["type"] == "subscription" else max(1, min(MAX_QTY, int(entry.get("q") or 1)))
            left = self.remaining(p, sold)
            if left is not None:
                qty = min(qty, left)
            if qty <= 0:
                continue
            items.append({"id": p["id"], "name": p["name"], "qty": qty, "price": p["price"], "stars": p["stars"],
                          "type": p["type"], "chat_id": p["chat_id"], "chat_title": p["chat_title"],
                          "days": p["days"]})
        if not items:
            self._send(self.engine, chat_id, self.t("shop.products_gone"))
            return None
        order = db.create_order(self.base_dir, user, chat_id, items, self.shop["currency"])
        self._mirror(order)
        self.engine.emit("order_created", order=order["id"], total=self.price(order["total"]),
                         buyer=_name(user))
        self.offer_payment(order)
        return order

    def summary(self, order):
        lines = [self.t("shop.order_title", order["id"])]
        for i in order["items"]:
            lines.append("%s%s · %s" % ("%dx " % i["qty"] if i["qty"] > 1 else "", template.escape(i["name"]),
                                        self.price(i["price"] * i["qty"])))
        lines.append(self.t("shop.total_bold", self.price(order["total"])))
        return "\n".join(lines)

    def offer_payment(self, order):
        options = self.gateways(order)
        chat_id = order["chat_id"]
        if not options:
            self._send(self.engine, chat_id, self.summary(order) + "\n\n" + self.t("shop.no_payments"))
            self.engine.emit("error", context="shop", command="loja", retry_in=None, fatal=False,
                             code="pay_no_gateway", detail="", retry_after=None)
            return
        if len(options) == 1:
            self.charge(order, options[0], self.engine)
            return
        keyboard = [[{"text": self.t("shop.gw_" + g) if g != "xtr" else self.t("shop.gw_xtr_n", order["stars"]),
                      "callback_data": "%sg:%s:%s" % (CB, order["id"], g)}] for g in options]
        keyboard.append([{"text": self.t("shop.cancel_order"), "callback_data": "%sx:%s" % (CB, order["id"])}])
        self._send(self.engine, chat_id, self.summary(order) + "\n\n" + self.t("shop.how_to_pay"), keyboard)

    def charge(self, order, gateway, sender):
        chat_id = order["chat_id"]
        if gateway not in self.gateways(order):
            self._send(sender, chat_id, self.t("shop.gateway_unavailable"))
            return
        paid_btn = [{"text": self.t("shop.already_paid"), "callback_data": "%sc:%s" % (CB, order["id"])}]
        try:
            if gateway == "mp":
                ref, url = MercadoPago(self.secrets["mp_token"]).checkout(order, self.title())
                db.set_payment(self.base_dir, order["id"], "mp", ref, url)
                self._send(sender, chat_id, self.summary(order) + "\n\n" + self.t("shop.pay_mp"),
                           [[{"text": self.t("shop.pay_amount", self.price(order["total"])), "url": url}], paid_btn])
            elif gateway == "pix":
                mp = self.shop["gateways"]["mercadopago"]
                ref, code, png = MercadoPago(self.secrets["mp_token"]).pix(order, self.title(), mp["payer_email"])
                db.set_payment(self.base_dir, order["id"], "pix", ref)
                if png:
                    self._send_png(sender, chat_id, png, order)
                self._send(sender, chat_id, self.t("shop.pix_code", template.escape(code)), [paid_btn])
            elif gateway == "btc":
                blink = Blink(self.secrets["blink_key"])
                sats = blink.sats_for(order["total"], order["currency"])
                pr, _ = blink.invoice(sats, self.t("shop.invoice_memo", self.title(), order["id"]))
                db.set_payment(self.base_dir, order["id"], "btc", pr)
                self._send(sender, chat_id, self.summary(order) + "\n\n" + self.t(
                    "shop.pay_btc", "{:,}".format(sats).replace(",", self.t("shop.thousands_sep")),
                    template.escape(pr)), [paid_btn])
            elif gateway == "xtr":
                db.set_payment(self.base_dir, order["id"], "xtr", "")
                title = order["items"][0]["name"] if len(order["items"]) == 1 else self.t("shop.order_plain", order["id"])
                sender._call_send("sendInvoice", {
                    "chat_id": chat_id, "title": title[:32],
                    "description": self.t("shop.invoice_memo", ", ".join(i["name"] for i in order["items"]),
                                          order["id"])[:255],
                    "payload": order["id"], "currency": "XTR",
                    "prices": [{"label": title[:32], "amount": int(order["stars"])}],
                }, "loja")
        except PaymentError as exc:
            sender.emit("error", context="shop", command="loja", retry_in=None, fatal=False, **exc.to_dict())
            self._send(sender, chat_id, self.t("shop.charge_failed"))

    def _send_png(self, sender, chat_id, png, order):
        folder = os.path.join(self.base_dir, "shop_tmp")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "pix_%s.png" % order["id"])
        try:
            with open(path, "wb") as fh:
                fh.write(png)
            sender._call_send("sendPhoto", {"chat_id": chat_id, "caption": self.t("shop.pix_caption",
                self.price(order["total"]), order["id"])}, "loja", files={"photo": path})
        finally:
            try:
                os.remove(path)
            except OSError:
                pass

    def status_of(self, order):
        gw, ref = order.get("gateway"), order.get("gateway_ref")
        if gw in ("mp", "pix"):
            return MercadoPago(self.secrets["mp_token"]).status(gw, ref, order["id"])
        if gw == "btc":
            return Blink(self.secrets["blink_key"]).status(ref)
        return None

    def check_now(self, query, order):
        try:
            status = self.status_of(order)
        except PaymentError as exc:
            self.engine.emit("error", context="shop", command="loja", retry_in=None, fatal=False, **exc.to_dict())
            status = None
        if status == PAID:
            self._answer(query, self.t("shop.payment_confirmed"))
            self.complete(order["id"], self.engine)
        elif order.get("gateway") == "xtr":
            self._answer(query)
        else:
            self._answer(query, self.t("shop.payment_not_yet"), alert=True)

    # -- pagamento confirmado -----------------------------------------------------------------

    def complete(self, order_id, sender, gateway=None, ref=None):
        """Marca como pago e entrega. Só uma chamada por pedido passa daqui."""
        if not order_id or not db.mark_paid(self.base_dir, order_id, gateway, ref):
            return False
        order = db.get_order(self.base_dir, order_id)
        self._mirror(order)
        problems = self.deliver(order, sender)
        db.set_delivered(self.base_dir, order_id, not problems, "; ".join(problems) or None)
        self.notify_admins(order, problems, sender)
        sender.emit("sale", order=order_id, total=self.price(order["total"]) if order["gateway"] != "xtr"
                    else "%d estrelas" % (order["stars"] or 0),
                    buyer=_name(order), gateway=order["gateway"], problems=len(problems))
        return True

    def deliver(self, order, sender):
        user = {"id": order["user_id"], "first_name": order["first_name"], "last_name": order["last_name"],
                "username": order["username"]}
        chat_id = order["chat_id"]
        self._send(sender, chat_id, self.t("shop.thanks") + "\n\n" + self.summary(order))
        problems = []
        context = self._context(user, chat_id)
        for item in order["items"]:
            if item["type"] == "subscription":
                problem = self.grant(sender, user, item)
                if problem:
                    problems.append(problem)
                continue
            p = catalog.find(self.shop, item["id"])
            delivery = p and p.get("delivery")
            if not delivery:
                continue
            for _ in range(item["qty"] if delivery.get("media") is None else 1):
                if not sender.send_message(chat_id, delivery, context, "loja"):
                    problems.append(self.t("shop.problem_delivery", item["name"]))
                    break
                if item["qty"] > 1 and delivery.get("media") is None and "{" not in delivery.get("text", ""):
                    break  # mesma mensagem: uma basta
        if problems:
            self._send(sender, chat_id, self.t("shop.delivery_problem", order["id"]))
        return problems

    def grant(self, sender, user, item):
        """Assinatura paga: cria/renova e manda o convite pessoal. Devolve um problema ou ``None``."""
        chat_id = item.get("chat_id")
        title = item.get("chat_title") or item["name"]
        if not chat_id:
            return self.t("shop.problem_no_chat", item["name"])
        expires, renewed = db.extend_subscription(self.base_dir, user, chat_id, title, item["id"], item["days"])
        if renewed:
            self._send(sender, user["id"], self.t("shop.sub_renewed", template.escape(title), self.date(expires)))
            return None
        self._raw(sender, "unbanChatMember", {"chat_id": chat_id, "user_id": user["id"], "only_if_banned": True})
        link = self._raw(sender, "createChatInviteLink", {
            "chat_id": chat_id, "member_limit": 1, "expire_date": int(time.time()) + 86400,
            "name": self.t("shop.invite_name", user["id"])[:32]})
        url = (link or {}).get("invite_link") if isinstance(link, dict) else None
        if not url:
            return self.t("shop.problem_invite", title)
        self._send(sender, user["id"], self.t("shop.sub_active", template.escape(title), self.date(expires)),
                   [[{"text": self.t("shop.join", title[:40]), "url": url}]])
        return None

    def notify_admins(self, order, problems, sender):
        lines = [self.t("shop.admin_new_sale", order["id"]), self.t("shop.admin_buyer", _name(order), order["user_id"])]
        lines += ["%dx %s" % (i["qty"], i["name"]) for i in order["items"]]
        if order["gateway"] == "xtr":
            lines.append(self.t("shop.admin_total_stars", order["stars"] or 0))
        else:
            gw = order["gateway"]
            lines.append(self.t("shop.admin_total", self.price(order["total"]), self.t("shop.gw_" + gw) if gw in GATEWAYS else ""))
        if problems:
            lines += ["", self.t("shop.admin_problem")] + problems
        text = "\n".join(lines)
        level = self.shop["notify_level"]
        for admin_id, admin in sorted(self.engine.admins.items()):
            if admins_mod.can_use(admin.get("level"), level):
                self._send(sender, admin_id, text, html=False)

    def _mirror(self, order):
        """Cópia do pedido no banco do bot (Firebase, AWS...), se houver."""
        storage = self.engine.storage
        if storage and order:
            storage.put_doc("pedidos", order["id"], dict(order, items=json.dumps(order["items"], ensure_ascii=False)))

    # -- verificação em segundo plano ---------------------------------------------------------

    def start_worker(self):
        if self._worker is None:
            self._worker = threading.Thread(target=self._loop, name="shop", daemon=True)
            self._worker.start()

    def _loop(self):
        from ..engine import Engine  # import aqui: engine importa este módulo

        eng = self.engine
        sender = Engine(eng.token, eng.config_path, eng.data_dir, eng.cb, api=TelegramApi(eng.token))
        while not eng.stopping:
            try:
                sender.config, sender.bot, sender.admins = eng.config, eng.bot, eng.admins
                sender.storage = eng.storage
                if self.enabled:
                    self.tick(sender)
            except Aborted:
                return
            except Exception as exc:  # noqa: BLE001 - a loja nunca derruba o bot
                eng.emit("error", context="shop", command="loja", retry_in=None, fatal=False,
                         code="pay_unknown", detail="%s: %s" % (type(exc).__name__, exc), retry_after=None)
            eng._stop.wait(CHECK_EVERY)

    def tick(self, sender, now=None):
        """Uma rodada: confere cobranças pendentes, expira as velhas e cuida das assinaturas."""
        now = now or time.time()
        for order in db.pending_with_charge(self.base_dir):
            if now - self._checked.get(order["id"], 0) < CHECK_EVERY - 1:
                continue
            self._checked[order["id"]] = now
            try:
                status = self.status_of(order)
            except PaymentError:
                continue  # sem internet etc.: tenta na próxima rodada
            if status == PAID:
                self.complete(order["id"], sender)
            elif status == EXPIRED:
                db.set_status(self.base_dir, order["id"], "expired")
        for order in db.expire_old(self.base_dir, ORDER_TTL, now):
            self._checked.pop(order["id"], None)
        if now - self._last_subs >= SUBS_EVERY:
            self._last_subs = now
            self.check_subscriptions(sender, now)

    def check_subscriptions(self, sender, now=None):
        now = int(now or time.time())
        for s in db.due_subscriptions(self.base_dir, now):
            title = s["chat_title"] or "canal"
            kicked = self._raw(sender, "banChatMember", {"chat_id": s["chat_id"], "user_id": s["user_id"],
                                                          "until_date": now + 60})
            if kicked:
                self._raw(sender, "unbanChatMember", {"chat_id": s["chat_id"], "user_id": s["user_id"],
                                                      "only_if_banned": True})
            db.set_subscription(self.base_dir, s["user_id"], s["chat_id"], status="expired")
            self._send(sender, s["user_id"], self.t("shop.sub_ended", template.escape(title)), self._renew_button(s))
            sender.emit("subscription_expired", user=_name(s), chat=title, removed=bool(kicked))
        days = self.shop["remind_days"]
        if days:
            for s in db.reminders_due(self.base_dir, days, now):
                db.set_subscription(self.base_dir, s["user_id"], s["chat_id"], reminded=1)
                self._send(sender, s["user_id"], self.t("shop.sub_reminder", template.escape(s["chat_title"] or self.t("shop.channel")),
                                                        self.date(s["expires_at"])), self._renew_button(s))

    def _renew_button(self, sub):
        p = catalog.find(self.shop, sub["product_id"])
        if p and p in self.for_sale():
            return [[{"text": self.t("shop.renew_price", self.price(p["price"])), "callback_data": CB + "b:" + p["id"]}]]
        return None


# -- funções chamadas pelo app (Kotlin) --------------------------------------------------

class _AppCallbacks:
    def __init__(self):
        self.errors = []

    def onEvent(self, data):  # noqa: N802
        e = json.loads(data)
        if e.get("type") == "error":
            self.errors.append({k: e.get(k) for k in ("code", "detail", "retry_after")})

    def nowLocal(self):  # noqa: N802
        return time.strftime("%d/%m/%Y %H:%M")


def _app_engine(token, bot_dir, payments=None):
    from .. import config as config_mod
    from ..engine import Engine

    cb = _AppCallbacks()
    eng = Engine(token, os.path.join(bot_dir, "config.json"), bot_dir, cb, payments=payments)
    eng.config = config_mod.load(eng.config_path)
    eng.maybe_reload_admins(force=True)
    eng.bot = eng.api.call("getMe", timeout=20)
    return eng, cb


def _app_result(fn):
    try:
        out = fn() or {}
        out.setdefault("ok", True)
    except PaymentError as exc:
        out = {"ok": False, "error": exc.to_dict()}
    except TelegramError as exc:
        out = {"ok": False, "error": errors.classify(exc)}
    except Exception as exc:  # noqa: BLE001
        out = {"ok": False, "error": errors.classify(exc)}
    return json.dumps(out, ensure_ascii=False)


def app_confirm(token, bot_dir, order_id, payments=None):
    """Pagamento confirmado à mão pelo dono (ex.: recebeu em dinheiro): marca como pago e entrega."""
    def run():
        eng, cb = _app_engine(token, bot_dir, payments)
        order = db.get_order(bot_dir, order_id)
        if order is None or order["status"] == "paid":
            return {"ok": False, "error": {"code": "pay_already", "detail": "", "retry_after": None}}
        eng.shop.complete(order_id, eng, order.get("gateway") or "manual", "manual")
        return {"delivered": bool(db.get_order(bot_dir, order_id)["delivered"])}
    return _app_result(run)


def app_subscription(token, bot_dir, user_id, chat_id, action, days=0):
    """``extend`` (soma ``days``) ou ``remove`` (tira do canal agora)."""
    def run():
        sub = db.get_subscription(bot_dir, user_id, chat_id)
        if sub is None:
            return {"ok": False, "error": {"code": "pay_not_found", "detail": "", "retry_after": None}}
        eng, cb = _app_engine(token, bot_dir)
        shop = eng.shop
        title = sub["chat_title"] or "canal"
        user = {"id": sub["user_id"], "first_name": sub["first_name"], "last_name": sub["last_name"],
                "username": sub["username"]}
        if action == "extend":
            item = {"id": sub["product_id"], "name": title, "chat_id": sub["chat_id"], "chat_title": title,
                    "days": int(days)}
            problem = shop.grant(eng, user, item)
            if problem:
                return {"ok": False, "error": {"code": "pay_invite", "detail": problem, "retry_after": None}}
            return {}
        now = int(time.time())
        kicked = shop._raw(eng, "banChatMember", {"chat_id": sub["chat_id"], "user_id": sub["user_id"],
                                                  "until_date": now + 60})
        if kicked:
            shop._raw(eng, "unbanChatMember", {"chat_id": sub["chat_id"], "user_id": sub["user_id"],
                                               "only_if_banned": True})
        db.set_subscription(bot_dir, sub["user_id"], sub["chat_id"], status="removed", expires_at=now)
        shop._send(eng, sub["user_id"], shop.t("shop.sub_removed", template.escape(title)))
        return {"removed": bool(kicked)}
    return _app_result(run)
