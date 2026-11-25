"""HTTP simples (biblioteca padrão) para os bancos externos, com erros classificados."""

import http.client
import json
import ssl
import urllib.parse

from .. import i18n

_SSL = ssl.create_default_context()

# Códigos que o app sabe explicar (veja ErrorExplainer.kt).
CONFIG = "storage_config"          # faltou preencher algo / formato errado
AUTH = "storage_auth"              # credencial recusada (401/403)
NOT_FOUND = "storage_not_found"    # banco, projeto ou tabela não existe
NETWORK = "storage_network"        # sem internet / host não encontrado
PERMISSION = "storage_permission"  # sem permissão de arquivos no aparelho
UNKNOWN = "storage_unknown"


class StorageError(Exception):
    def __init__(self, code, detail=""):
        super().__init__("%s: %s" % (code, detail))
        self.code = code
        self.detail = str(detail)[:500]

    def to_dict(self):
        return {"code": self.code, "detail": self.detail, "retry_after": None}


def request(method, url, body=None, headers=None, timeout=30):
    """Faz a requisição e devolve ``(status, bytes)``. Erros de rede viram ``StorageError``."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "https" or not parts.hostname:
        raise StorageError(CONFIG, i18n.t("detail.https_required"))
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query
    if isinstance(body, (dict, list)):
        body = json.dumps(body, ensure_ascii=False).encode("utf-8")
    elif isinstance(body, str):
        body = body.encode("utf-8")
    conn = http.client.HTTPSConnection(parts.hostname, parts.port or 443, timeout=timeout, context=_SSL)
    try:
        conn.request(method, path, body=body, headers=headers or {})
        resp = conn.getresponse()
        return resp.status, resp.read()
    except (OSError, http.client.HTTPException) as exc:
        raise StorageError(NETWORK, exc) from None
    finally:
        conn.close()


def parse_json(raw):
    try:
        return json.loads(raw.decode("utf-8")) if raw else None
    except ValueError:
        return None


def check(status, raw, what):
    """Levanta ``StorageError`` adequado se ``status`` não for 2xx."""
    if 200 <= status < 300:
        return
    text = raw.decode("utf-8", "replace")[:300] if raw else ""
    if status in (401, 403):
        raise StorageError(AUTH, "%s: HTTP %s %s" % (what, status, text))
    if status == 404:
        raise StorageError(NOT_FOUND, "%s: HTTP 404 %s" % (what, text))
    raise StorageError(UNKNOWN, "%s: HTTP %s %s" % (what, status, text))
