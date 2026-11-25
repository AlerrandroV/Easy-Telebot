"""Bancos de dados suportados. Todos guardam "documentos" JSON em "coleções".

Interface comum: ``put(colecao, id, dados)``, ``get(colecao, id)``,
``delete(colecao, id)``, ``list(colecao, limite)``.

Cada bot usa um "espaço" (``namespace`` = id do bot) para vários bots poderem
dividir o mesmo banco.
"""

import json
import os
import re
import time
import urllib.parse

from . import http
from .. import i18n
from .gauth import ServiceAccount
from .http import StorageError
from .sigv4 import sign

_SAFE = re.compile(r"[^A-Za-z0-9_.-]")


def _safe(name):
    return _SAFE.sub("_", str(name))[:200] or "_"


class LocalStore:
    """Arquivos JSON numa pasta: interna do app ou no armazenamento do aparelho."""

    kind = "local"

    def __init__(self, root):
        if not root:
            raise StorageError(http.CONFIG, i18n.t("detail.storage_no_folder"))
        self.root = root

    def _dir(self, collection):
        return os.path.join(self.root, _safe(collection))

    def _wrap(self, fn):
        try:
            return fn()
        except PermissionError as exc:
            raise StorageError(http.PERMISSION, exc) from None
        except OSError as exc:
            if getattr(exc, "errno", None) in (1, 13, 30):  # EPERM, EACCES, EROFS
                raise StorageError(http.PERMISSION, exc) from None
            raise StorageError(http.UNKNOWN, exc) from None

    def put(self, collection, doc_id, data):
        def run():
            d = self._dir(collection)
            os.makedirs(d, exist_ok=True)
            path = os.path.join(d, _safe(doc_id) + ".json")
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False, indent=1)
            os.replace(tmp, path)
        self._wrap(run)

    def get(self, collection, doc_id):
        def run():
            path = os.path.join(self._dir(collection), _safe(doc_id) + ".json")
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    return json.load(fh)
            except FileNotFoundError:
                return None
            except ValueError:
                return None
        return self._wrap(run)

    def delete(self, collection, doc_id):
        def run():
            try:
                os.remove(os.path.join(self._dir(collection), _safe(doc_id) + ".json"))
            except FileNotFoundError:
                pass
        self._wrap(run)

    def list(self, collection, limit=100):
        def run():
            d = self._dir(collection)
            if not os.path.isdir(d):
                return []
            files = [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".json")]
            files.sort(key=os.path.getmtime, reverse=True)
            out = []
            for f in files[:limit]:
                try:
                    with open(f, "r", encoding="utf-8") as fh:
                        out.append(json.load(fh))
                except (OSError, ValueError):
                    pass
            return out
        return self._wrap(run)


class FirebaseRealtimeStore:
    """Firebase Realtime Database pela API REST. Caminho: ``easytelebot/<bot>/<coleção>/<id>``."""

    kind = "firebase_rtdb"

    def __init__(self, url, secret, namespace):
        url = (url or "").strip().rstrip("/")
        if not url.startswith("https://"):
            raise StorageError(http.CONFIG, i18n.t("detail.rtdb_https"))
        self.base = url
        self.secret = (secret or "").strip()
        self.ns = _safe(namespace)

    def _url(self, *path, query=None):
        q = dict(query or {})
        if self.secret:
            q["auth"] = self.secret
        p = "/".join(urllib.parse.quote(_safe(x)) for x in ("easytelebot", self.ns) + path)
        return "%s/%s.json%s" % (self.base, p, ("?" + urllib.parse.urlencode(q)) if q else "")

    def _call(self, method, url, body=None):
        status, raw = http.request(method, url, body, {"Content-Type": "application/json"})
        if status in (401, 403):
            raise StorageError(http.AUTH, "Firebase recusou o acesso (segredo errado ou regras do banco)")
        if status == 404:
            raise StorageError(http.NOT_FOUND, i18n.t("detail.rtdb_not_found"))
        http.check(status, raw, "Firebase")
        return http.parse_json(raw)

    def put(self, collection, doc_id, data):
        self._call("PUT", self._url(collection, doc_id), data)

    def get(self, collection, doc_id):
        return self._call("GET", self._url(collection, doc_id))

    def delete(self, collection, doc_id):
        self._call("DELETE", self._url(collection, doc_id))

    def list(self, collection, limit=100):
        data = self._call("GET", self._url(collection, query={"orderBy": '"$key"', "limitToLast": int(limit)}))
        return list(data.values()) if isinstance(data, dict) else []


def _fs_encode(v):
    if v is None:
        return {"nullValue": None}
    if isinstance(v, bool):
        return {"booleanValue": v}
    if isinstance(v, int):
        return {"integerValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    if isinstance(v, (list, tuple)):
        return {"arrayValue": {"values": [_fs_encode(x) for x in v]}}
    if isinstance(v, dict):
        return {"mapValue": {"fields": {str(k): _fs_encode(x) for k, x in v.items()}}}
    return {"stringValue": str(v)}


def _fs_decode(v):
    if "nullValue" in v:
        return None
    if "booleanValue" in v:
        return v["booleanValue"]
    if "integerValue" in v:
        return int(v["integerValue"])
    if "doubleValue" in v:
        return float(v["doubleValue"])
    if "arrayValue" in v:
        return [_fs_decode(x) for x in v["arrayValue"].get("values", [])]
    if "mapValue" in v:
        return {k: _fs_decode(x) for k, x in v["mapValue"].get("fields", {}).items()}
    for key in ("stringValue", "timestampValue", "referenceValue", "bytesValue"):
        if key in v:
            return v[key]
    return None


class FirestoreStore:
    """Cloud Firestore (Firebase ou Google Cloud) com conta de serviço.

    Caminho: ``easytelebot/<bot>/<coleção>/<id>``.
    """

    kind = "firestore"
    SCOPE = "https://www.googleapis.com/auth/datastore"

    def __init__(self, service_account, namespace, database="(default)"):
        self.sa = ServiceAccount(service_account)
        self.ns = _safe(namespace)
        self.root = "https://firestore.googleapis.com/v1/projects/%s/databases/%s/documents" % (
            urllib.parse.quote(self.sa.project_id), urllib.parse.quote(database or "(default)"))

    def _url(self, collection, doc_id=None, query=None):
        parts = ["easytelebot", self.ns, _safe(collection)] + ([_safe(doc_id)] if doc_id is not None else [])
        url = self.root + "/" + "/".join(urllib.parse.quote(p) for p in parts)
        return url + ("?" + urllib.parse.urlencode(query) if query else "")

    def _call(self, method, url, body=None, allow_404=False):
        token = self.sa.access_token(self.SCOPE)
        status, raw = http.request(method, url, body, {
            "Authorization": "Bearer " + token, "Content-Type": "application/json",
        })
        if status == 404 and allow_404:
            return None
        if status == 403:
            raise StorageError(http.AUTH, i18n.t("detail.firestore_forbidden") + ": "
                               + raw.decode("utf-8", "replace")[:200])
        http.check(status, raw, "Firestore")
        return http.parse_json(raw)

    def put(self, collection, doc_id, data):
        self._call("PATCH", self._url(collection, doc_id), {"fields": _fs_encode(dict(data))["mapValue"]["fields"]})

    def get(self, collection, doc_id):
        doc = self._call("GET", self._url(collection, doc_id), allow_404=True)
        return _fs_decode({"mapValue": {"fields": doc.get("fields", {})}}) if doc else None

    def delete(self, collection, doc_id):
        self._call("DELETE", self._url(collection, doc_id), allow_404=True)

    def list(self, collection, limit=100):
        data = self._call("GET", self._url(collection, query={"pageSize": int(limit)}), allow_404=True) or {}
        return [_fs_decode({"mapValue": {"fields": d.get("fields", {})}}) for d in data.get("documents", [])]


class DynamoDBStore:
    """AWS DynamoDB. Uma tabela com chave ``pk`` (``<bot>#<coleção>``) e ``sk`` (id).

    Se a tabela não existir, ``ensure_table()`` cria (cobrança por uso).
    """

    kind = "dynamodb"

    def __init__(self, access_key, secret_key, region, table, namespace):
        for name, value in (("aws_access", access_key), ("aws_secret", secret_key),
                            ("aws_region", region), ("aws_table", table)):
            if not (value or "").strip():
                raise StorageError(http.CONFIG, i18n.t("detail.aws_missing", None, i18n.t("detail." + name)))
        self.access_key = access_key.strip()
        self.secret_key = secret_key.strip()
        self.region = region.strip()
        self.table = table.strip()
        self.ns = _safe(namespace)
        self.url = "https://dynamodb.%s.amazonaws.com/" % self.region

    def _call(self, action, payload):
        body = json.dumps(payload).encode("utf-8")
        headers = sign("POST", self.url, {
            "Content-Type": "application/x-amz-json-1.0",
            "X-Amz-Target": "DynamoDB_20120810." + action,
        }, body, self.access_key, self.secret_key, self.region, "dynamodb")
        status, raw = http.request("POST", self.url, body, headers)
        if 200 <= status < 300:
            return http.parse_json(raw) or {}
        err = http.parse_json(raw) or {}
        kind = str(err.get("__type", "")).split("#")[-1]
        message = err.get("message") or err.get("Message") or ""
        if kind in ("UnrecognizedClientException", "InvalidSignatureException", "AccessDeniedException",
                    "MissingAuthenticationTokenException", "ExpiredTokenException"):
            raise StorageError(http.AUTH, "%s: %s" % (kind, message))
        if kind == "ResourceNotFoundException":
            raise StorageError(http.NOT_FOUND, i18n.t("detail.aws_no_table", None, self.table, self.region))
        raise StorageError(http.UNKNOWN, "%s %s: %s" % (status, kind, message))

    def ensure_table(self, wait=30):
        try:
            self._call("DescribeTable", {"TableName": self.table})
            return False
        except StorageError as exc:
            if exc.code != http.NOT_FOUND:
                raise
        self._call("CreateTable", {
            "TableName": self.table,
            "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"},
                                     {"AttributeName": "sk", "AttributeType": "S"}],
            "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"},
                          {"AttributeName": "sk", "KeyType": "RANGE"}],
            "BillingMode": "PAY_PER_REQUEST",
        })
        deadline = time.time() + wait
        while time.time() < deadline:
            info = self._call("DescribeTable", {"TableName": self.table})
            if info.get("Table", {}).get("TableStatus") == "ACTIVE":
                break
            time.sleep(2)
        return True

    def _key(self, collection, doc_id):
        return {"pk": {"S": "%s#%s" % (self.ns, _safe(collection))}, "sk": {"S": str(doc_id)}}

    def put(self, collection, doc_id, data):
        item = self._key(collection, doc_id)
        item["data"] = {"S": json.dumps(data, ensure_ascii=False)}
        item["updated_at"] = {"N": str(int(time.time()))}
        self._call("PutItem", {"TableName": self.table, "Item": item})

    def get(self, collection, doc_id):
        res = self._call("GetItem", {"TableName": self.table, "Key": self._key(collection, doc_id)})
        item = res.get("Item")
        return json.loads(item["data"]["S"]) if item else None

    def delete(self, collection, doc_id):
        self._call("DeleteItem", {"TableName": self.table, "Key": self._key(collection, doc_id)})

    def list(self, collection, limit=100):
        res = self._call("Query", {
            "TableName": self.table,
            "KeyConditionExpression": "pk = :pk",
            "ExpressionAttributeValues": {":pk": {"S": "%s#%s" % (self.ns, _safe(collection))}},
            "Limit": int(limit),
            "ScanIndexForward": False,
        })
        return [json.loads(i["data"]["S"]) for i in res.get("Items", [])]
