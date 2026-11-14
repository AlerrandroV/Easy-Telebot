"""Conta de serviço do Google (Firestore/Firebase) sem bibliotecas externas.

Gera um JWT assinado com RS256 usando a chave privada do JSON da conta de
serviço e troca por um token de acesso OAuth2. A assinatura RSA é feita com
inteiros do Python (PKCS#1 v1.5 + SHA-256), o mesmo algoritmo das bibliotecas
oficiais.
"""

import base64
import hashlib
import json
import threading
import time
import urllib.parse

from . import http
from .http import StorageError

_SHA256_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


def _der_read(data, i):
    tag = data[i]
    length = data[i + 1]
    i += 2
    if length & 0x80:
        n = length & 0x7F
        length = int.from_bytes(data[i:i + n], "big")
        i += n
    return tag, data[i:i + length], i + length


def _der_items(data):
    items, i = [], 0
    while i < len(data):
        tag, value, i = _der_read(data, i)
        items.append((tag, value))
    return items


class RsaKey:
    def __init__(self, n, e, d, p, q, dp, dq, qinv):
        self.n, self.e, self.d = n, e, d
        self.p, self.q, self.dp, self.dq, self.qinv = p, q, dp, dq, qinv
        self.size = (n.bit_length() + 7) // 8

    @classmethod
    def from_pem(cls, pem):
        try:
            lines = [l.strip() for l in pem.strip().splitlines() if l.strip() and not l.startswith("-----")]
            der = base64.b64decode("".join(lines))
            _, body, _ = _der_read(der, 0)
            items = _der_items(body)
            # PKCS#8 (o formato do Google): versão, algoritmo, OCTET STRING com a chave PKCS#1.
            if len(items) >= 3 and items[1][0] == 0x30 and items[2][0] == 0x04:
                _, body, _ = _der_read(items[2][1], 0)
                items = _der_items(body)
            ints = [int.from_bytes(v, "big") for t, v in items if t == 0x02]
            return cls(*ints[1:9])
        except Exception as exc:  # noqa: BLE001
            raise StorageError(http.CONFIG, "chave privada inválida (%s)" % exc) from None

    def sign_sha256(self, message):
        digest = hashlib.sha256(message).digest()
        pad = self.size - 3 - len(_SHA256_PREFIX) - len(digest)
        em = b"\x00\x01" + b"\xff" * pad + b"\x00" + _SHA256_PREFIX + digest
        m = int.from_bytes(em, "big")
        # Teorema chinês do resto: ~4x mais rápido que pow(m, d, n).
        m1 = pow(m, self.dp, self.p)
        m2 = pow(m, self.dq, self.q)
        h = (self.qinv * (m1 - m2)) % self.p
        return (m2 + h * self.q).to_bytes(self.size, "big")


def _b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=")


class ServiceAccount:
    """Credencial a partir do JSON baixado no console (Contas de serviço › Chaves)."""

    def __init__(self, info):
        if isinstance(info, str):
            try:
                info = json.loads(info)
            except ValueError:
                raise StorageError(http.CONFIG, "o arquivo da conta de serviço não é um JSON válido") from None
        if not isinstance(info, dict) or info.get("type") != "service_account":
            raise StorageError(http.CONFIG, "isso não é o JSON de uma conta de serviço (type=service_account)")
        for key in ("client_email", "private_key", "project_id"):
            if not info.get(key):
                raise StorageError(http.CONFIG, "o JSON da conta de serviço não tem '%s'" % key)
        self.email = info["client_email"]
        self.project_id = info["project_id"]
        self.token_uri = info.get("token_uri") or "https://oauth2.googleapis.com/token"
        self.key = RsaKey.from_pem(info["private_key"])
        self._token = None
        self._expires = 0
        self._lock = threading.Lock()

    def jwt(self, scope, now=None):
        now = int(now or time.time())
        header = {"alg": "RS256", "typ": "JWT"}
        claims = {"iss": self.email, "scope": scope, "aud": self.token_uri, "iat": now, "exp": now + 3600}
        signing_input = _b64url(json.dumps(header).encode()) + b"." + _b64url(json.dumps(claims).encode())
        return (signing_input + b"." + _b64url(self.key.sign_sha256(signing_input))).decode()

    def access_token(self, scope):
        with self._lock:
            if self._token and time.time() < self._expires - 60:
                return self._token
            body = urllib.parse.urlencode({
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": self.jwt(scope),
            })
            status, raw = http.request("POST", self.token_uri, body,
                                       {"Content-Type": "application/x-www-form-urlencoded"})
            if status in (400, 401, 403):
                raise StorageError(http.AUTH, "o Google recusou a conta de serviço: %s" % raw.decode("utf-8", "replace")[:200])
            http.check(status, raw, "token")
            data = http.parse_json(raw) or {}
            self._token = data.get("access_token")
            self._expires = time.time() + int(data.get("expires_in", 3600))
            if not self._token:
                raise StorageError(http.AUTH, "resposta sem access_token")
            return self._token
