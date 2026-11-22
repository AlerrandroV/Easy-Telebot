"""Hospedagem da vitrine: imagens no imgbb e o catálogo (JSON) num Gist do GitHub.

O celular não tem endereço público. A página da vitrine é fixa (GitHub Pages) e
precisa buscar o catálogo em algum lugar público:

- ``catalogo.json`` vai para um Gist secreto do dono do bot (só ele pode editar;
  quem tem o link pode ler). O bot manda o link da versão exata publicada
  (``raw_url`` com o hash da revisão), então não há cache velho.
- As fotos vão para o imgbb (se houver chave) e o JSON leva só os links; sem
  chave, o app embute miniaturas pequenas (``data:image/jpeg;base64,...``).

Credenciais: chave de API do imgbb (api.imgbb.com) e um token do GitHub só com
permissão de Gists. Ficam cifradas no celular e nunca vão para o JSON.
"""

import json

from ..storage import http
from . import catalog

CONFIG = "host_config"
AUTH = "host_auth"
NETWORK = "host_network"
REJECTED = "host_rejected"
UNKNOWN = "host_unknown"

IMGBB_URL = "https://api.imgbb.com/1/upload"
GITHUB_API = "https://api.github.com"
GIST_FILE = "catalogo.json"


class HostingError(Exception):
    def __init__(self, code, detail=""):
        super().__init__("%s: %s" % (code, detail))
        self.code = code
        self.detail = str(detail)[:500]

    def to_dict(self):
        return {"code": self.code, "detail": self.detail, "retry_after": None}


def _request(method, url, body=None, headers=None, timeout=60):
    try:
        status, raw = http.request(method, url, body, headers, timeout)
    except http.StorageError as exc:
        raise HostingError(NETWORK if exc.code == http.NETWORK else CONFIG, exc.detail) from None
    return status, http.parse_json(raw), raw


def upload_image(key, b64, request=None):
    """Envia uma imagem (base64, sem o prefixo data:) ao imgbb. Devolve o link direto."""
    if not key:
        raise HostingError(CONFIG, "falta a chave do imgbb")
    import urllib.parse
    body = urllib.parse.urlencode({"key": key, "image": b64})
    status, data, raw = (request or _request)("POST", IMGBB_URL, body,
                                              {"Content-Type": "application/x-www-form-urlencoded"})
    if status == 200 and isinstance(data, dict) and (data.get("data") or {}).get("url"):
        return data["data"]["url"]
    msg = ((data or {}).get("error") or {}).get("message") if isinstance(data, dict) else ""
    if status in (400, 401, 403) and "key" in (msg or "").lower():
        raise HostingError(AUTH, "imgbb: %s" % msg)
    raise HostingError(REJECTED if 400 <= status < 500 else UNKNOWN, "imgbb HTTP %s %s" % (status, msg or ""))


def publish_gist(token, gist_id, content, description="Catálogo da loja (Easy Telebot)", request=None):
    """Cria ou atualiza o Gist. Devolve ``{"gist_id", "raw_url"}`` (raw da revisão publicada)."""
    if not token:
        raise HostingError(CONFIG, "falta o token do GitHub")
    request = request or _request
    headers = {"Authorization": "Bearer " + token.strip(), "Accept": "application/vnd.github+json",
               "Content-Type": "application/json", "User-Agent": "EasyTelebot",
               "X-GitHub-Api-Version": "2022-11-28"}
    body = {"description": description, "files": {GIST_FILE: {"content": content}}}
    status, data = None, None
    if gist_id:
        status, data, _ = request("PATCH", "%s/gists/%s" % (GITHUB_API, gist_id), body, headers)
    if not gist_id or status == 404:  # primeira vez, ou o Gist foi apagado no GitHub
        status, data, _ = request("POST", GITHUB_API + "/gists", dict(body, public=False), headers)
    if status in (401, 403):
        raise HostingError(AUTH, "GitHub HTTP %s %s" % (status, (data or {}).get("message", "")))
    if status not in (200, 201) or not isinstance(data, dict):
        raise HostingError(REJECTED if status and 400 <= status < 500 else UNKNOWN,
                           "GitHub HTTP %s %s" % (status, (data or {}).get("message", "") if isinstance(data, dict) else ""))
    raw = ((data.get("files") or {}).get(GIST_FILE) or {}).get("raw_url")
    if not raw:
        raise HostingError(UNKNOWN, "o GitHub não devolveu o endereço do arquivo")
    return {"gist_id": data.get("id"), "raw_url": raw}


def _result(fn):
    try:
        out = fn() or {}
        out["ok"] = True
    except HostingError as exc:
        out = {"ok": False, "error": exc.to_dict()}
    except Exception as exc:  # noqa: BLE001
        out = {"ok": False, "error": {"code": UNKNOWN, "detail": "%s: %s" % (type(exc).__name__, exc)}}
    return json.dumps(out, ensure_ascii=False)


# -- funções chamadas pelo app (Kotlin) ------------------------------------------------

def app_upload_image(key, b64):
    return _result(lambda: {"url": upload_image(key, b64)})


def app_publish(config_path, title, images_json, token, gist_id):
    """Monta o catálogo da vitrine (preços e estoque de agora) e publica no Gist."""
    def run():
        from .. import config as config_mod
        import os
        from . import db
        shop = config_mod.load(config_path)["shop"]
        images = json.loads(images_json or "{}")
        data = catalog.storefront_data(shop, title, images, db.sold_counts(os.path.dirname(config_path)))
        content = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
        out = publish_gist(token, gist_id, content)
        out.update(products=len(data["p"]), bytes=len(content.encode("utf-8")))
        return out
    return _result(run)
