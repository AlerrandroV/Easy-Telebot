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
