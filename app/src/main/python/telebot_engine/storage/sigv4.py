"""Assinatura AWS Signature Version 4 (só biblioteca padrão)."""

import datetime
import hashlib
import hmac
import urllib.parse


def _sign(key, msg):
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def signing_key(secret_key, date, region, service):
    k = _sign(("AWS4" + secret_key).encode("utf-8"), date)
    k = _sign(k, region)
    k = _sign(k, service)
    return _sign(k, "aws4_request")


def sign(method, url, headers, body, access_key, secret_key, region, service, now=None):
    """Devolve os cabeçalhos com ``X-Amz-Date`` e ``Authorization`` adicionados."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date = amz_date[:8]
    parts = urllib.parse.urlsplit(url)
    headers = dict(headers)
    headers["Host"] = parts.netloc
    headers["X-Amz-Date"] = amz_date
    body = body or b""
    if isinstance(body, str):
        body = body.encode("utf-8")

    canon_headers = sorted((k.lower(), " ".join(str(v).split())) for k, v in headers.items())
    signed_headers = ";".join(k for k, _ in canon_headers)
    query = "&".join(
        "%s=%s" % (urllib.parse.quote(k, safe="-_.~"), urllib.parse.quote(v, safe="-_.~"))
        for k, v in sorted(urllib.parse.parse_qsl(parts.query, keep_blank_values=True))
    )
    canonical = "\n".join([
        method,
        urllib.parse.quote(parts.path or "/", safe="/-_.~"),
        query,
        "".join("%s:%s\n" % kv for kv in canon_headers),
        signed_headers,
        hashlib.sha256(body).hexdigest(),
    ])
    scope = "%s/%s/%s/aws4_request" % (date, region, service)
    to_sign = "\n".join([
        "AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
    ])
    signature = hmac.new(signing_key(secret_key, date, region, service), to_sign.encode("utf-8"),
                         hashlib.sha256).hexdigest()
    headers["Authorization"] = "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s" % (
        access_key, scope, signed_headers, signature)
    return headers
