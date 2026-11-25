"""Armazenamento de dados do bot: usuários, histórico e backup da configuração.

Configuração (``storage.json`` na pasta do bot + segredos guardados à parte)::

    {
      "type": "internal" | "device" | "firebase_rtdb" | "firestore" | "dynamodb",
      "store_users": true,          # quem falou com o bot
      "store_history": true,        # cada mensagem recebida e enviada pelo bot
      "device_path": "/storage/emulated/0/EasyTelebot/meubot",
      "firebase_url": "https://xxx-default-rtdb.firebaseio.com",
      "firebase_secret": "...",                     (segredo)
      "service_account": "{...json...}",            (segredo)
      "firestore_database": "(default)",
      "aws_region": "us-east-1", "aws_table": "easytelebot",
      "aws_access_key": "...", "aws_secret_key": "..."  (segredos)
    }

As funções públicas recebem a configuração em JSON e devolvem JSON
``{"ok": true, ...}`` ou ``{"ok": false, "error": {"code", "detail"}}``.
"""

import json
import os
import time

from .. import admins as admins_mod
from .. import chats
from .. import i18n
from . import http
from .backends import DynamoDBStore, FirebaseRealtimeStore, FirestoreStore, LocalStore
from .http import StorageError

TYPES = ("internal", "device", "firebase_rtdb", "firestore", "dynamodb")
USERS = "usuarios"
HISTORY = "historico"
BACKUP = "backup"


def parse_settings(settings):
    if isinstance(settings, str):
        try:
            settings = json.loads(settings or "{}")
        except ValueError:
            settings = {}
    settings = dict(settings or {})
    settings.setdefault("type", "internal")
    settings.setdefault("store_users", True)
    if "v" not in settings or "store_history" not in settings:
        # Antes da v2 o histórico vinha desligado; agora as mensagens vão para o banco
        # (dá para desligar de novo na tela do banco). Mesma regra de Storage.kt.
        settings["store_history"] = True
    return settings


def open_store(settings, bot_dir, namespace):
    """Cria o banco descrito em ``settings``. ``bot_dir`` = pasta do bot (para o interno)."""
    s = parse_settings(settings)
    kind = s["type"]
    if kind == "internal":
        return LocalStore(os.path.join(bot_dir, "db"))
    if kind == "device":
        return LocalStore(s.get("device_path"))
    if kind == "firebase_rtdb":
        return FirebaseRealtimeStore(s.get("firebase_url"), s.get("firebase_secret"), namespace)
    if kind == "firestore":
        return FirestoreStore(s.get("service_account"), namespace, s.get("firestore_database") or "(default)")
    if kind == "dynamodb":
        return DynamoDBStore(s.get("aws_access_key"), s.get("aws_secret_key"), s.get("aws_region"),
                             s.get("aws_table"), namespace)
    raise StorageError(http.CONFIG, "tipo de banco desconhecido: %s" % kind)


def _result(fn):
    try:
        out = fn() or {}
        out["ok"] = True
    except StorageError as exc:
        out = {"ok": False, "error": exc.to_dict()}
    except Exception as exc:  # noqa: BLE001 - tudo vira erro explicado
        out = {"ok": False, "error": {"code": http.UNKNOWN, "detail": "%s: %s" % (type(exc).__name__, exc)}}
    return json.dumps(out, ensure_ascii=False)


# -- operações usadas pelo motor ---------------------------------------------------

def record_user(store, user, chat, now=None):
    """Cria/atualiza o usuário: nome, @, primeira e última vez, total de mensagens."""
    if not user or not user.get("id"):
        return
    now = int(now or time.time())
    doc = store.get(USERS, user["id"]) or {"id": user["id"], "first_seen": now, "messages": 0}
    doc.update({
        "first_name": user.get("first_name") or "",
        "last_name": user.get("last_name") or "",
        "username": user.get("username") or "",
        "language": user.get("language_code") or "",
        "last_seen": now,
        "messages": int(doc.get("messages") or 0) + 1,
    })
    if chat and chat.get("type") != "private":
        groups = doc.get("groups") or []
        if chat.get("id") not in groups:
            doc["groups"] = (groups + [chat.get("id")])[-20:]
    store.put(USERS, user["id"], doc)


def record_message(store, update_id, message, text, direction="in", source="user"):
    """Uma mensagem no histórico. ``direction``: ``in`` (recebida) ou ``out`` (enviada pelo bot);
    ``source``: ``user``, ``auto`` (resposta de comando), ``manual`` (enviada pelo app)."""
    user = message.get("from") or {}
    chat = message.get("chat") or {}
    now = int(time.time())
    kind = chats.parse_message(message)["kind"]
    store.put(HISTORY, "%d-%s" % (now, update_id), {
        "at": now,
        "date": message.get("date") or now,
        "direction": direction,
        "source": source,
        "kind": kind,
        "message_id": message.get("message_id"),
        "user_id": user.get("id"),
        "username": user.get("username") or "",
        "name": user.get("first_name") or "",
        "chat_id": chat.get("id"),
        "chat_type": chat.get("type") or "",
        "chat": chat.get("title") or chat.get("first_name") or "",
        "text": text[:4096],
    })


# -- funções chamadas pelo app (Kotlin) -------------------------------------------

def test(settings, bot_dir, namespace):
    """Grava, lê e apaga um documento de teste. Na AWS, cria a tabela se faltar."""
    def run():
        store = open_store(settings, bot_dir, namespace)
        created = False
        if isinstance(store, DynamoDBStore):
            created = store.ensure_table()
        stamp = int(time.time())
        store.put("_teste", "conexao", {"ok": True, "at": stamp})
        back = store.get("_teste", "conexao")
        if not back or back.get("at") != stamp:
            raise StorageError(http.UNKNOWN, i18n.t("detail.storage_readback"))
        store.delete("_teste", "conexao")
        return {"table_created": created}
    return _result(run)


def backup(settings, bot_dir, namespace):
    """Envia config.json + admins para o banco (documento ``backup/ultimo``)."""
    def run():
        store = open_store(settings, bot_dir, namespace)
        with open(os.path.join(bot_dir, "config.json"), "r", encoding="utf-8") as fh:
            config = json.load(fh)
        admins = json.loads(admins_mod.list_admins(bot_dir))
        at = int(time.time())
        store.put(BACKUP, "ultimo", {"at": at, "config": json.dumps(config, ensure_ascii=False),
                                     "admins": json.dumps(admins, ensure_ascii=False)})
        return {"at": at, "commands": len(config.get("commands") or []), "admins": len(admins)}
    return _result(run)


def restore(settings, bot_dir, namespace):
    """Traz o último backup do banco e substitui config.json e admins deste bot."""
    def run():
        store = open_store(settings, bot_dir, namespace)
        doc = store.get(BACKUP, "ultimo")
        if not doc or not doc.get("config"):
            raise StorageError(http.NOT_FOUND, i18n.t("detail.storage_no_backup"))
        config = json.loads(doc["config"])
        admins = json.loads(doc.get("admins") or "[]")
        path = os.path.join(bot_dir, "config.json")
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(config, fh, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
        admins_mod.replace_all(bot_dir, admins)
        return {"at": doc.get("at"), "commands": len(config.get("commands") or []), "admins": len(admins)}
    return _result(run)


def list_docs(settings, bot_dir, namespace, collection, limit=100):
    def run():
        return {"items": open_store(settings, bot_dir, namespace).list(collection, int(limit))}
    return _result(run)
