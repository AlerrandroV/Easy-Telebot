"""Gateways de pagamento: Mercado Pago (link de checkout e Pix) e Blink (Bitcoin pela Lightning).

O celular não tem endereço público, então não dá para receber webhooks: o motor
consulta o status de cada cobrança pendente de tempos em tempos (e quando a
pessoa toca em "Já paguei").

Mercado Pago: token de acesso de produção (``APP_USR-...``) em Suas integrações.
Blink: chave de API com permissão de leitura e recebimento (dashboard.blink.sv).
"""

import base64
import json
import time
import uuid

from ..storage import http
from .. import i18n

# Códigos que o app sabe explicar (ErrorExplainer.kt).
CONFIG = "pay_config"      # faltou configurar algo
AUTH = "pay_auth"          # token/chave recusado
NETWORK = "pay_network"    # sem internet
REJECTED = "pay_rejected"  # o gateway recusou a cobrança (valor, moeda, conta...)
UNKNOWN = "pay_unknown"

PAID, PENDING, EXPIRED = "paid", "pending", "expired"

# site_id da conta do Mercado Pago -> moeda
MP_SITES = {"MLB": "BRL", "MLA": "ARS", "MLM": "MXN", "MLC": "CLP", "MCO": "COP", "MPE": "PEN", "MLU": "UYU"}


class PaymentError(Exception):
    def __init__(self, code, detail=""):
        super().__init__("%s: %s" % (code, detail))
        self.code = code
        self.detail = str(detail)[:500]

    def to_dict(self):
        return {"code": self.code, "detail": self.detail, "retry_after": None}


def _request(method, url, body=None, headers=None, timeout=30):
    try:
        status, raw = http.request(method, url, body, headers, timeout)
    except http.StorageError as exc:
        raise PaymentError(NETWORK if exc.code == http.NETWORK else CONFIG, exc.detail) from None
    data = http.parse_json(raw)
    if status in (401, 403):
        raise PaymentError(AUTH, "HTTP %s %s" % (status, (raw or b"")[:200].decode("utf-8", "replace")))
    if not 200 <= status < 300:
        msg = (data or {}).get("message") if isinstance(data, dict) else None
        raise PaymentError(REJECTED if 400 <= status < 500 else UNKNOWN,
                           "HTTP %s %s" % (status, msg or (raw or b"")[:300].decode("utf-8", "replace")))
    return data


def _amount(cents, currency):
    return round(cents / 100.0, 2)


class MercadoPago:
    BASE = "https://api.mercadopago.com"

    def __init__(self, token, request=None):
        if not token:
            raise PaymentError(CONFIG, "falta o token de acesso do Mercado Pago")
        self.token = token.strip()
        self.request = request or _request

    def _call(self, method, path, body=None, idempotent=False):
        headers = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}
        if idempotent:
            headers["X-Idempotency-Key"] = str(uuid.uuid4())
        return self.request(method, self.BASE + path, body, headers)

    def me(self):
        data = self._call("GET", "/users/me") or {}
        return {"name": data.get("nickname") or data.get("first_name") or "", "site": data.get("site_id") or "",
                "currency": MP_SITES.get(data.get("site_id"), "")}

    def checkout(self, order, title, minutes=60):
        """Link de pagamento (Pix, cartão, boleto...). Devolve ``(id, url)``."""
        expires = time.strftime("%Y-%m-%dT%H:%M:%S.000-00:00", time.gmtime(time.time() + minutes * 60))
        data = self._call("POST", "/checkout/preferences", {
            "items": [{"id": i["id"], "title": i["name"][:250], "quantity": i["qty"],
                       "unit_price": _amount(i["price"], order["currency"]), "currency_id": order["currency"]}
                      for i in order["items"]],
            "external_reference": order["id"],
            "statement_descriptor": title[:22],
            "expires": True,
            "expiration_date_to": expires,
        }) or {}
        if not data.get("init_point"):
            raise PaymentError(UNKNOWN, i18n.t("detail.mp_no_link"))
        return data.get("id"), data["init_point"]

    def pix(self, order, title, payer_email, minutes=30):
        """Cobrança Pix direto no chat. Devolve ``(id, copia_e_cola, png_bytes)``."""
        if not payer_email:
            raise PaymentError(CONFIG, "falta o e-mail do pagador para o Pix")
        expires = time.strftime("%Y-%m-%dT%H:%M:%S.000-00:00", time.gmtime(time.time() + minutes * 60))
        data = self._call("POST", "/v1/payments", {
            "transaction_amount": _amount(order["total"], order["currency"]),
            "description": ("%s - pedido %s" % (title, order["id"]))[:200],
            "payment_method_id": "pix",
            "external_reference": order["id"],
            "date_of_expiration": expires,
            "payer": {"email": payer_email},
        }, idempotent=True) or {}
        tx = ((data.get("point_of_interaction") or {}).get("transaction_data") or {})
        if not tx.get("qr_code"):
            raise PaymentError(UNKNOWN, i18n.t("detail.mp_no_pix"))
        png = base64.b64decode(tx["qr_code_base64"]) if tx.get("qr_code_base64") else None
        return data.get("id"), tx["qr_code"], png

    def status(self, gateway, ref, order_id):
        if gateway == "pix":
            data = self._call("GET", "/v1/payments/%s" % ref) or {}
            return _mp_status(data.get("status"))
        data = self._call("GET", "/v1/payments/search?external_reference=%s&sort=date_created&criteria=desc"
                          % order_id) or {}
        statuses = [r.get("status") for r in data.get("results") or []]
        return PAID if "approved" in statuses else PENDING


def _mp_status(status):
    if status == "approved":
        return PAID
    if status in ("cancelled", "rejected", "refunded", "charged_back", "expired"):
        return EXPIRED
    return PENDING


class Blink:
    URL = "https://api.blink.sv/graphql"

    def __init__(self, api_key, request=None):
        if not api_key:
            raise PaymentError(CONFIG, "falta a chave de API do Blink")
        self.key = api_key.strip()
        self.request = request or _request

    def _gql(self, query, variables=None, auth=True):
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["X-API-KEY"] = self.key
        data = self.request("POST", self.URL, {"query": query, "variables": variables or {}}, headers) or {}
        if data.get("errors"):
            msg = data["errors"][0].get("message", "")
            code = AUTH if "auth" in msg.lower() or "not authorized" in msg.lower() else REJECTED
            raise PaymentError(code, msg)
        return data.get("data") or {}

    def wallet(self):
        """Carteira em BTC da conta: ``(id, saldo_em_sats)``."""
        me = self._gql("query { me { username defaultAccount { wallets { id walletCurrency balance } } } }")
        account = ((me.get("me") or {}).get("defaultAccount") or {})
        wallets = account.get("wallets") or []
        btc = next((w for w in wallets if w.get("walletCurrency") == "BTC"), None)
        if btc is None:
            raise PaymentError(CONFIG, i18n.t("detail.blink_no_wallet"))
        return btc["id"], btc.get("balance") or 0, (me.get("me") or {}).get("username") or ""

    def sats_for(self, cents, currency):
        data = self._gql("query($a: Float!, $c: DisplayCurrency!) { currencyConversionEstimation(amount: $a, "
                         "currency: $c) { btcSatAmount } }", {"a": cents / 100.0, "c": currency}, auth=False)
        sats = int((data.get("currencyConversionEstimation") or {}).get("btcSatAmount") or 0)
        if sats <= 0:
            raise PaymentError(REJECTED, i18n.t("detail.blink_convert", None, currency))
        return sats

    def invoice(self, sats, memo, minutes=60):
        """Fatura Lightning. Devolve ``(payment_request, payment_hash)``."""
        wallet_id = self.wallet()[0]
        data = self._gql("mutation($i: LnInvoiceCreateInput!) { lnInvoiceCreate(input: $i) { "
                         "invoice { paymentRequest paymentHash satoshis } errors { message } } }",
                         {"i": {"walletId": wallet_id, "amount": int(sats), "memo": memo[:600],
                                "expiresIn": int(minutes)}})
        payload = data.get("lnInvoiceCreate") or {}
        if payload.get("errors"):
            raise PaymentError(REJECTED, payload["errors"][0].get("message", ""))
        inv = payload.get("invoice") or {}
        if not inv.get("paymentRequest"):
            raise PaymentError(UNKNOWN, i18n.t("detail.blink_no_invoice"))
        return inv["paymentRequest"], inv.get("paymentHash") or ""

    def status(self, payment_request):
        data = self._gql("query($i: LnInvoicePaymentStatusByPaymentRequestInput!) { "
                         "lnInvoicePaymentStatusByPaymentRequest(input: $i) { status } }",
                         {"i": {"paymentRequest": payment_request}})
        status = (data.get("lnInvoicePaymentStatusByPaymentRequest") or {}).get("status")
        return {"PAID": PAID, "EXPIRED": EXPIRED}.get(status, PENDING)


# -- testes de conexão chamados pelo app ------------------------------------------------

def _result(fn):
    try:
        out = fn()
        out["ok"] = True
    except PaymentError as exc:
        out = {"ok": False, "error": exc.to_dict()}
    except Exception as exc:  # noqa: BLE001
        out = {"ok": False, "error": {"code": UNKNOWN, "detail": "%s: %s" % (type(exc).__name__, exc)}}
    return json.dumps(out, ensure_ascii=False)


def test_mercadopago(token):
    return _result(lambda: MercadoPago(token).me())


def test_blink(api_key):
    def run():
        _, balance, username = Blink(api_key).wallet()
        return {"name": username, "balance": balance}
    return _result(run)


def test_stripe(key):
    return _result(lambda: Stripe(key).me())


def test_paypal(client_id, secret, sandbox=False):
    return _result(lambda: PayPal(client_id, secret, sandbox).me())


# -- Stripe (Checkout) --------------------------------------------------------------------

# Moedas sem centavos na Stripe (o valor vai em unidades inteiras).
STRIPE_ZERO_DECIMAL = {"CLP", "JPY", "KRW", "PYG", "VND", "XOF", "XAF", "UGX", "RWF"}


class Stripe:
    """Link de pagamento (Checkout Session) com cartão, Pix (Brasil), carteiras... conforme a conta.

    Chave secreta (``sk_live_...`` ou restrita ``rk_live_...`` com Checkout Sessions: escrita)."""

    BASE = "https://api.stripe.com/v1"

    def __init__(self, key, request=None):
        if not key:
            raise PaymentError(CONFIG, i18n.t("detail.stripe_no_key"))
        self.key = key.strip()
        self.request = request or _request

    def _call(self, method, path, form=None):
        import urllib.parse
        body = urllib.parse.urlencode(form, doseq=True) if form else None
        headers = {"Authorization": "Bearer " + self.key}
        if body:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        return self.request(method, self.BASE + path, body, headers) or {}

    def me(self):
        data = self._call("GET", "/account")
        name = ((data.get("business_profile") or {}).get("name") or (data.get("settings") or {}).get("dashboard", {}).get("display_name")
                or data.get("email") or data.get("id") or "")
        return {"name": name, "currency": str(data.get("default_currency") or "").upper()}

    @staticmethod
    def unit_amount(cents, currency):
        return int(cents // 100) if currency.upper() in STRIPE_ZERO_DECIMAL else int(cents)

    def checkout(self, order, title, return_url, minutes=60):
        form = {"mode": "payment", "success_url": return_url, "cancel_url": return_url,
                "client_reference_id": order["id"], "expires_at": int(time.time()) + max(30, minutes) * 60,
                "metadata[order]": order["id"]}
        for n, line in enumerate(_lines(order)):
            prefix = "line_items[%d]" % n
            form[prefix + "[quantity]"] = line["qty"]
            form[prefix + "[price_data][currency]"] = order["currency"].lower()
            form[prefix + "[price_data][unit_amount]"] = self.unit_amount(line["price"], order["currency"])
            form[prefix + "[price_data][product_data][name]"] = line["name"][:250]
        data = self._call("POST", "/checkout/sessions", form)
        if not data.get("url"):
            raise PaymentError(UNKNOWN, "Stripe: sem url")
        return data["id"], data["url"]

    def status(self, session_id):
        data = self._call("GET", "/checkout/sessions/" + session_id)
        if data.get("payment_status") == "paid":
            return PAID
        if data.get("status") == "expired":
            return EXPIRED
        return PENDING


# -- PayPal (Orders v2) ---------------------------------------------------------------------

PAYPAL_CURRENCIES = {"BRL", "USD", "EUR", "MXN", "GBP", "CAD", "AUD", "JPY"}


class PayPal:
    """Pedido do PayPal: a pessoa aprova no link; o motor captura o pagamento ao conferir.

    Credenciais de um app REST (developer.paypal.com): Client ID e Secret (modo Live)."""

    def __init__(self, client_id, secret, sandbox=False, request=None):
        if not client_id or not secret:
            raise PaymentError(CONFIG, i18n.t("detail.paypal_no_key"))
        self.client_id, self.secret = client_id.strip(), secret.strip()
        self.base = "https://api-m.sandbox.paypal.com" if sandbox else "https://api-m.paypal.com"
        self.request = request or _request
        self._token = None

    def _auth(self):
        if self._token is None:
            basic = base64.b64encode(("%s:%s" % (self.client_id, self.secret)).encode()).decode()
            data = self.request("POST", self.base + "/v1/oauth2/token", "grant_type=client_credentials",
                                {"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"}) or {}
            if not data.get("access_token"):
                raise PaymentError(AUTH, "PayPal: sem token")
            self._token = data["access_token"]
        return self._token

    def _call(self, method, path, body=None):
        headers = {"Authorization": "Bearer " + self._auth(), "Content-Type": "application/json"}
        return self.request(method, self.base + path, body, headers) or {}

    def me(self):
        self._auth()
        return {"name": "PayPal" + (" (sandbox)" if "sandbox" in self.base else "")}

    def checkout(self, order, title, return_url):
        cur = order["currency"].upper()
        if cur not in PAYPAL_CURRENCIES:
            raise PaymentError(REJECTED, i18n.t("detail.paypal_currency", None, cur))
        value = "%d" % (order["total"] // 100) if cur == "JPY" else "%.2f" % (order["total"] / 100.0)
        data = self._call("POST", "/v2/checkout/orders", {
            "intent": "CAPTURE",
            "purchase_units": [{"reference_id": order["id"], "description": (title + " - " + order["id"])[:127],
                                "amount": {"currency_code": cur, "value": value}}],
            "payment_source": {"paypal": {"experience_context": {
                "return_url": return_url, "cancel_url": return_url, "user_action": "PAY_NOW",
                "shipping_preference": "NO_SHIPPING", "brand_name": title[:127]}}},
        })
        link = next((l["href"] for l in data.get("links") or [] if l.get("rel") in ("payer-action", "approve")), None)
        if not link:
            raise PaymentError(UNKNOWN, "PayPal: sem link")
        return data["id"], link

    def status(self, paypal_id):
        data = self._call("GET", "/v2/checkout/orders/" + paypal_id)
        status = data.get("status")
        if status == "APPROVED":
            captured = self._call("POST", "/v2/checkout/orders/%s/capture" % paypal_id, {})
            status = captured.get("status")
        if status == "COMPLETED":
            return PAID
        if status in ("VOIDED",):
            return EXPIRED
        return PENDING


def _lines(order):
    """Itens com o desconto e o frete do pedido (a soma bate com o total)."""
    lines = [{"name": i["name"], "qty": i["qty"], "price": i["price"]} for i in order["items"]]
    discount = int(order.get("discount") or 0)
    shipping = int(order.get("shipping") or 0)
    if discount:
        # Stripe/PayPal não aceitam linha negativa: vira um item só com o valor final.
        return [{"name": ", ".join(i["name"] for i in order["items"])[:200], "qty": 1, "price": int(order["total"])}]
    if shipping:
        lines.append({"name": i18n.t("shop.shipping_line"), "qty": 1, "price": shipping})
    return lines


# -- Pix "copia e cola" sem banco no meio (BR Code estático) --------------------------------

def _emv(tag, value):
    value = str(value)
    return "%s%02d%s" % (tag, len(value), value)


def _crc16(data):
    crc = 0xFFFF
    for b in data.encode("utf-8"):
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return "%04X" % crc


def pix_code(key, name, city, cents, txid):
    """Código Pix copia e cola (BR Code estático com valor), aceito por qualquer banco."""
    import unicodedata

    def clean(text, size):
        text = unicodedata.normalize("NFKD", str(text)).encode("ascii", "ignore").decode()
        return "".join(c for c in text if c.isalnum() or c == " ").strip()[:size] or "NA"
    txid = "".join(c for c in str(txid) if c.isalnum())[:25] or "***"
    account = _emv("00", "br.gov.bcb.pix") + _emv("01", str(key).strip()[:77])
    payload = (_emv("00", "01") + _emv("26", account) + _emv("52", "0000") + _emv("53", "986") +
               _emv("54", "%.2f" % (cents / 100.0)) + _emv("58", "BR") + _emv("59", clean(name, 25)) +
               _emv("60", clean(city, 15)) + _emv("62", _emv("05", txid)) + "6304")
    return payload + _crc16(payload)
