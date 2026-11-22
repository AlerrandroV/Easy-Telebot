"""Catálogo da loja: configuração (dentro do ``config.json``, campo ``shop``), preços e vitrine.

Preços são guardados em centavos (inteiro) para nunca ter erro de arredondamento.

Produto::

    {"id", "name", "description", "price": 1990, "stars": 150 | null,
     "type": "digital" | "subscription", "active": true, "stock": -1 (ilimitado),
     "photo": "media/x.jpg" | null, "image_url": "https://..." (vitrine) | "",
     "chat_id": -100..., "chat_title": "Canal VIP", "days": 30,      (só assinatura)
     "delivery": {mensagem igual à dos comandos: text, media, buttons}}
"""

import base64
import json
import zlib

CURRENCIES = {
    "BRL": "R$", "USD": "US$", "EUR": "€", "ARS": "ARS$", "MXN": "MX$",
    "CLP": "CLP$", "COP": "COP$", "PEN": "S/", "UYU": "UY$",
}
NO_CENTS = ("CLP", "COP")  # moedas sem centavos no Mercado Pago
PRODUCT_TYPES = ("digital", "subscription")
DEFAULT_STORE_URL = "https://alerrandrov.github.io/Easy-Telebot/loja/"
DEFAULT_TRIGGERS = ["loja", "catalogo"]
SUBS_TRIGGER = "assinaturas"


def _int(value, default=0, lo=None, hi=None):
    try:
        value = int(value)
    except (TypeError, ValueError):
        value = default
    if lo is not None:
        value = max(lo, value)
    if hi is not None:
        value = min(hi, value)
    return value


def _product(item, index, message_normalizer):
    if not isinstance(item, dict) or not str(item.get("name") or "").strip():
        return None
    kind = item.get("type") if item.get("type") in PRODUCT_TYPES else "digital"
    stars = item.get("stars")
    delivery = message_normalizer(item.get("delivery") or {}) if item.get("delivery") else None
    return {
        "id": str(item.get("id") or "p%d" % index),
        "name": str(item["name"]).strip()[:64],
        "description": str(item.get("description") or "").strip()[:700],
        "price": _int(item.get("price"), 0, lo=0),
        "stars": _int(stars, 0, lo=1, hi=100000) if stars not in (None, "", 0) else None,
        "type": kind,
        "active": bool(item.get("active", True)),
        "stock": _int(item.get("stock"), -1, lo=-1),
        "photo": item.get("photo") or None,
        "image_url": str(item.get("image_url") or "").strip(),
        "chat_id": _int(item.get("chat_id"), 0) or None,
        "chat_title": str(item.get("chat_title") or ""),
        "days": _int(item.get("days"), 30, lo=1, hi=3650),
        "delivery": delivery,
    }


def normalize(data, message_normalizer):
    """Configuração da loja com todos os campos e valores válidos."""
    data = data if isinstance(data, dict) else {}
    gw = data.get("gateways") if isinstance(data.get("gateways"), dict) else {}
    mp = gw.get("mercadopago") if isinstance(gw.get("mercadopago"), dict) else {}
    blink = gw.get("blink") if isinstance(gw.get("blink"), dict) else {}
    stars = gw.get("stars") if isinstance(gw.get("stars"), dict) else {}
    currency = str(data.get("currency") or "BRL").upper()
    products = [p for p in (_product(x, i, message_normalizer)
                            for i, x in enumerate(data.get("products") or [])) if p]
    triggers = [str(t).strip().lstrip("/").lower() for t in data.get("triggers") or DEFAULT_TRIGGERS]
    return {
        "enabled": bool(data.get("enabled", False)),
        "currency": currency if currency in CURRENCIES else "BRL",
        "triggers": [t for t in triggers if t] or list(DEFAULT_TRIGGERS),
        "intro": str(data.get("intro") or "Confira nossos produtos:")[:1000],
        "miniapp": bool(data.get("miniapp", True)),
        "store_url": str(data.get("store_url") or DEFAULT_STORE_URL).strip(),
        "notify_level": _int(data.get("notify_level"), 1, lo=1, hi=10),
        "remind_days": _int(data.get("remind_days"), 3, lo=0, hi=30),
        "products": products,
        "gateways": {
            "mercadopago": {"enabled": bool(mp.get("enabled")), "pix": bool(mp.get("pix")),
                            "payer_email": str(mp.get("payer_email") or "").strip()},
            "blink": {"enabled": bool(blink.get("enabled"))},
            "stars": {"enabled": bool(stars.get("enabled"))},
        },
    }


def format_price(cents, currency):
    symbol = CURRENCIES.get(currency, currency)
    if currency in NO_CENTS:
        whole = "{:,}".format(cents // 100).replace(",", ".")
        return "%s %s" % (symbol, whole)
    whole, frac = divmod(int(cents), 100)
    return "%s %s,%02d" % (symbol, "{:,}".format(whole).replace(",", "."), frac)


def find(shop, product_id):
    return next((p for p in shop["products"] if p["id"] == product_id), None)


def available(shop):
    """Produtos à venda agora (ativos e com estoque)."""
    return [p for p in shop["products"] if p["active"] and p["stock"] != 0]


def miniapp_url(shop, title):
    """Endereço da vitrine com o catálogo embutido (``#z=`` = JSON comprimido em base64url).

    A página é estática e não fala com nenhum servidor: mostra os produtos e devolve o
    carrinho ao bot com ``Telegram.WebApp.sendData``. O bot recalcula os preços.
    """
    data = {
        "t": title[:64],
        "c": shop["currency"],
        "s": CURRENCIES.get(shop["currency"], shop["currency"]),
        "p": [{
            "i": p["id"], "n": p["name"], "d": p["description"][:300], "v": p["price"],
            "f": format_price(p["price"], shop["currency"]), "k": p["type"],
            "y": p["days"] if p["type"] == "subscription" else 0,
            "m": p["image_url"], "q": p["stock"],
        } for p in available(shop)],
    }
    raw = zlib.compress(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), 9)
    return shop["store_url"].split("#")[0] + "#z=" + base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
