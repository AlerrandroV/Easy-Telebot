"""Variáveis que buscam dados na internet, direto no texto das mensagens.

- ``{preço:BTC}`` (ou ``{price:ETH:USD}``): cotação de criptomoeda (CoinGecko).
- ``{câmbio:USD}`` (ou ``{rate:EUR:BRL}``): câmbio de moedas (Frankfurter, dados do BCE).
- ``{clima:São Paulo}`` (ou ``{weather:Lisboa}``): temperatura e tempo agora (Open-Meteo).

Serviços gratuitos e sem chave. Sem a moeda de destino, vale real (app em português) ou
dólar (inglês). Resultados ficam guardados por alguns minutos para não sobrecarregar os
serviços nem deixar o bot lento. Se der erro, aparece "—".
"""

import threading
import time
import urllib.parse

from . import i18n, webfetch
from .pyfacil_names import fold

UNAVAILABLE = "—"
_cache = {}
_lock = threading.Lock()

KINDS = {
    "preco": "price", "price": "price", "cotacao": "price", "precio": "price", "prix": "price", "цена": "price",
    "cambio": "rate", "rate": "rate", "exchange": "rate", "taux": "rate", "курс": "rate",
    "clima": "weather", "weather": "weather", "meteo": "weather", "погода": "weather",
}
COINS = {
    "btc": "bitcoin", "eth": "ethereum", "sol": "solana", "usdt": "tether", "usdc": "usd-coin", "bnb": "binancecoin",
    "xrp": "ripple", "ada": "cardano", "doge": "dogecoin", "ltc": "litecoin", "trx": "tron", "dot": "polkadot",
    "ton": "the-open-network", "avax": "avalanche-2", "link": "chainlink", "xmr": "monero", "shib": "shiba-inu",
    "bch": "bitcoin-cash", "pol": "polygon-ecosystem-token", "matic": "matic-network",
}
SYMBOLS = {"BRL": "R$", "USD": "US$", "EUR": "€", "GBP": "£", "JPY": "¥", "MXN": "MX$", "ARS": "AR$", "CLP": "CLP$",
           "COP": "COL$", "PEN": "S/", "CAD": "C$", "AUD": "A$", "CHF": "CHF", "RUB": "₽", "CNY": "¥", "INR": "₹"}
_WEATHER = {
    0: ("céu limpo", "clear sky"), 1: ("quase limpo", "mainly clear"), 2: ("parcialmente nublado", "partly cloudy"),
    3: ("nublado", "overcast"), 45: ("neblina", "fog"), 48: ("neblina", "fog"),
    51: ("garoa fraca", "light drizzle"), 53: ("garoa", "drizzle"), 55: ("garoa forte", "dense drizzle"),
    61: ("chuva fraca", "light rain"), 63: ("chuva", "rain"), 65: ("chuva forte", "heavy rain"),
    66: ("chuva congelante", "freezing rain"), 67: ("chuva congelante", "freezing rain"),
    71: ("neve fraca", "light snow"), 73: ("neve", "snow"), 75: ("neve forte", "heavy snow"), 77: ("neve", "snow"),
    80: ("pancadas de chuva", "rain showers"), 81: ("pancadas de chuva", "rain showers"),
    82: ("tempestade de chuva", "violent rain showers"), 85: ("pancadas de neve", "snow showers"),
    86: ("pancadas de neve", "snow showers"), 95: ("trovoadas", "thunderstorm"), 96: ("trovoadas com granizo", "thunderstorm with hail"),
    99: ("trovoadas com granizo", "thunderstorm with hail"),
}


def _cached(key, ttl, compute):
    now = time.time()
    with _lock:
        hit = _cache.get(key)
        if hit and hit[0] > now:
            return hit[1]
    value = compute()
    with _lock:
        _cache[key] = (now + (ttl if value is not None else 30), value)  # erro: tenta de novo em 30 s
        if len(_cache) > 500:
            for k in [k for k, v in _cache.items() if v[0] < now]:
                _cache.pop(k, None)
    return value


def _default_currency(lang=None):
    return "BRL" if i18n.pick(lang) == "pt" else "USD"


def money(value, currency, lang=None, decimals=2):
    """``R$ 345.678,90`` (pt) ou ``US$345,678.90`` (en)."""
    text = "{:,.{d}f}".format(value, d=decimals)
    if i18n.pick(lang) == "pt":
        text = text.replace(",", "\x00").replace(".", ",").replace("\x00", ".")
    symbol = SYMBOLS.get(currency.upper(), currency.upper())
    return "%s %s" % (symbol, text)


def _decimals(value):
    if value >= 100:
        return 2
    if value >= 1:
        return 2 if value >= 10 else 4
    return 6


# -- provedores ---------------------------------------------------------------------------------

def price(symbol, currency=None, lang=None):
    """Cotação (número) ou ``None``."""
    coin = COINS.get(fold(symbol).strip(), fold(symbol).strip().replace(" ", "-"))
    cur = (currency or _default_currency(lang)).lower()

    def compute():
        res = webfetch.fetch("https://api.coingecko.com/api/v3/simple/price?ids=%s&vs_currencies=%s"
                             % (urllib.parse.quote(coin), urllib.parse.quote(cur)), timeout=8)
        value = webfetch.extract(res["json"], "%s.%s" % (coin, cur)) if res["ok"] else None
        return float(value) if isinstance(value, (int, float)) else None
    return _cached(("price", coin, cur), 60, compute)


def rate(code, to=None, lang=None):
    """Quanto vale 1 ``code`` em ``to`` (número) ou ``None``."""
    src = str(code).strip().upper()
    dst = (to or _default_currency(lang)).strip().upper()
    if src == dst:
        dst = "USD" if src != "USD" else "EUR"

    def compute():
        res = webfetch.fetch("https://api.frankfurter.app/latest?from=%s&to=%s"
                             % (urllib.parse.quote(src), urllib.parse.quote(dst)), timeout=8)
        value = webfetch.extract(res["json"], "rates.%s" % dst) if res["ok"] else None
        return float(value) if isinstance(value, (int, float)) else None
    return _cached(("rate", src, dst), 600, compute), dst


def weather(city, lang=None):
    """``"23 °C, parcialmente nublado"`` ou ``None``."""
    lang = i18n.pick(lang)
    name = str(city).strip()

    def place():
        res = webfetch.fetch("https://geocoding-api.open-meteo.com/v1/search?count=1&language=%s&name=%s"
                             % (lang, urllib.parse.quote(name)), timeout=8)
        first = webfetch.extract(res["json"], "results.0") if res["ok"] else None
        return (first["latitude"], first["longitude"]) if isinstance(first, dict) and "latitude" in first else None

    def compute():
        where = _cached(("geo", fold(name)), 86400, place)
        if where is None:
            return None
        res = webfetch.fetch("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                             "&current=temperature_2m,weather_code" % where, timeout=8)
        cur = webfetch.extract(res["json"], "current") if res["ok"] else None
        if not isinstance(cur, dict) or cur.get("temperature_2m") is None:
            return None
        desc = _WEATHER.get(int(cur.get("weather_code") or 0), ("", ""))[0 if lang == "pt" else 1]
        temp = round(float(cur["temperature_2m"]))
        return ("%d °C, %s" % (temp, desc)).rstrip(", ")
    return _cached(("weather", fold(name), lang), 600, compute)


def resolve(name, arg, lang=None):
    """Valor de ``{nome:argumento}`` como texto. ``None`` = não é uma variável da internet."""
    kind = KINDS.get(fold(name))
    if kind is None:
        return None
    parts = [p.strip() for p in str(arg).split(":") if p.strip()]
    if not parts:
        return UNAVAILABLE
    if kind == "price":
        cur = (parts[1] if len(parts) > 1 else _default_currency(lang)).upper()
        value = price(parts[0], cur, lang)
        return money(value, cur, lang, _decimals(value)) if value is not None else UNAVAILABLE
    if kind == "rate":
        value, dst = rate(parts[0], parts[1] if len(parts) > 1 else None, lang)
        return money(value, dst, lang, _decimals(value)) if value is not None else UNAVAILABLE
    return weather(":".join(parts), lang) or UNAVAILABLE
