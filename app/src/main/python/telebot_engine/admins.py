"""Administradores do bot, em classes (I = mais alta).

Um admin de classe N pode usar todo comando que exige a classe N ou uma classe
mais baixa (número maior). Ex.: classe III usa comandos de classe III, IV e V,
mas não os de classe II ou I. Pode haver quantos admins quiser em cada classe.

Para virar admin, o dono do bot gera um código no app (depois de confirmar o
desbloqueio do celular) e a pessoa manda ``/newadmin CÓDIGO`` para o bot em
conversa privada. O código é longo, vale poucos minutos e só pode ser usado uma
vez. Só o hash dele fica salvo.

Arquivos na pasta do bot:
- ``admins.json``: ``[{"id", "first_name", "last_name", "username", "level", "added_at"}]``
- ``admin_codes.json``: ``[{"hash", "level", "expires_at"}]``

Todas as leituras e gravações passam por este módulo e pela mesma trava, para o
app e o motor nunca gravarem ao mesmo tempo.
"""

import hashlib
import hmac
import json
import os
import secrets
import string
import threading
import time

ADMINS_FILE = "admins.json"
CODES_FILE = "admin_codes.json"
CODE_LENGTH = 32
CODE_TTL = 10 * 60
MIN_LEVEL = 1
MAX_LEVEL = 10
_ALPHABET = string.ascii_letters + string.digits

_lock = threading.RLock()


def _path(bot_dir, name):
    return os.path.join(bot_dir, name)


def _read(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, list) else []
    except (OSError, ValueError):
        return []


def _write(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def _hash(code):
    return hashlib.sha256(code.encode("utf-8")).hexdigest()


def clamp_level(level):
    try:
        level = int(level)
    except (TypeError, ValueError):
        level = MIN_LEVEL
    return max(MIN_LEVEL, min(MAX_LEVEL, level))


def can_use(admin_level, required_level):
    """Classe I (1) é a mais alta: pode usar tudo que exige classe >= 1."""
    return admin_level is not None and required_level is not None and int(admin_level) <= int(required_level)


# -- códigos ---------------------------------------------------------------------

def create_code(bot_dir, level, ttl=CODE_TTL):
    """Gera um código novo de uso único. Devolve JSON ``{"code", "level", "expires_at"}``."""
    code = "".join(secrets.choice(_ALPHABET) for _ in range(CODE_LENGTH))
    expires = int(time.time()) + int(ttl)
    with _lock:
        os.makedirs(bot_dir, exist_ok=True)
        path = _path(bot_dir, CODES_FILE)
        now = time.time()
        codes = [c for c in _read(path) if c.get("expires_at", 0) > now]
        codes.append({"hash": _hash(code), "level": clamp_level(level), "expires_at": expires})
        _write(path, codes)
    return json.dumps({"code": code, "level": clamp_level(level), "expires_at": expires})


def cancel_code(bot_dir, code):
    with _lock:
        path = _path(bot_dir, CODES_FILE)
        h = _hash(code or "")
        now = time.time()
        _write(path, [c for c in _read(path)
                      if c.get("expires_at", 0) > now and not hmac.compare_digest(c.get("hash", ""), h)])
    return True


def redeem_code(bot_dir, code, user):
    """Usa o código para tornar ``user`` (objeto ``from`` do Telegram) admin.

    Devolve o admin criado/atualizado, ou ``None`` se o código não vale.
    """
    code = (code or "").strip()
    if len(code) != CODE_LENGTH or not user or not user.get("id"):
        return None
    h = _hash(code)
    with _lock:
        path = _path(bot_dir, CODES_FILE)
        now = time.time()
        codes = [c for c in _read(path) if c.get("expires_at", 0) > now]
        match = next((c for c in codes if hmac.compare_digest(c.get("hash", ""), h)), None)
        if match is None:
            _write(path, codes)  # aproveita para limpar os vencidos
            return None
        codes.remove(match)
        _write(path, codes)

        admins_path = _path(bot_dir, ADMINS_FILE)
        admins = [a for a in _read(admins_path) if a.get("id") != user["id"]]
        admin = {
            "id": user["id"],
            "first_name": user.get("first_name") or "",
            "last_name": user.get("last_name") or "",
            "username": user.get("username") or "",
            "level": clamp_level(match.get("level")),
            "added_at": int(now),
        }
        admins.append(admin)
        _write(admins_path, admins)
        return admin


# -- lista de admins ---------------------------------------------------------------

def load(bot_dir):
    """``{user_id: admin}`` para o motor consultar."""
    with _lock:
        return {a["id"]: a for a in _read(_path(bot_dir, ADMINS_FILE)) if isinstance(a, dict) and "id" in a}


def list_admins(bot_dir):
    with _lock:
        admins = _read(_path(bot_dir, ADMINS_FILE))
    admins.sort(key=lambda a: (a.get("level", MAX_LEVEL), a.get("added_at", 0)))
    return json.dumps(admins, ensure_ascii=False)


def set_level(bot_dir, user_id, level):
    with _lock:
        path = _path(bot_dir, ADMINS_FILE)
        admins = _read(path)
        for a in admins:
            if a.get("id") == int(user_id):
                a["level"] = clamp_level(level)
        _write(path, admins)
    return True


def remove_admin(bot_dir, user_id):
    with _lock:
        path = _path(bot_dir, ADMINS_FILE)
        _write(path, [a for a in _read(path) if a.get("id") != int(user_id)])
    return True
