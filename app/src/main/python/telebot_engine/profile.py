"""Perfil do bot: nome, descrição, "sobre" e foto, editados direto pelo app."""

import json

from . import errors
from .api import TelegramApi, TelegramError
from .engine import download_profile_photo

NAME_LIMIT = 64
DESCRIPTION_LIMIT = 512
SHORT_DESCRIPTION_LIMIT = 120


def _ok(**data):
    data["ok"] = True
    return json.dumps(data, ensure_ascii=False)


def _fail(exc):
    return json.dumps({"ok": False, "error": errors.classify(exc)}, ensure_ascii=False)


def get_profile(token, photo_path):
    """Lê nome, descrições e foto atuais do bot."""
    api = TelegramApi(token.strip())
    try:
        me = api.call("getMe", timeout=20)
        name = (api.call("getMyName", timeout=20) or {}).get("name", "")
        desc = (api.call("getMyDescription", timeout=20) or {}).get("description", "")
        short = (api.call("getMyShortDescription", timeout=20) or {}).get("short_description", "")
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    try:
        photo = download_profile_photo(api, me["id"], photo_path)
    except Exception:  # noqa: BLE001 - foto é opcional
        photo = None
    return _ok(bot=me, name=name or me.get("first_name", ""), description=desc,
               short_description=short, photo=photo)


def set_name(token, name):
    try:
        TelegramApi(token.strip()).call("setMyName", {"name": name[:NAME_LIMIT]}, timeout=20)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    return _ok()


def set_description(token, text):
    try:
        TelegramApi(token.strip()).call(
            "setMyDescription", {"description": text[:DESCRIPTION_LIMIT]}, timeout=20)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    return _ok()


def set_short_description(token, text):
    try:
        TelegramApi(token.strip()).call(
            "setMyShortDescription", {"short_description": text[:SHORT_DESCRIPTION_LIMIT]}, timeout=20)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    return _ok()


def set_photo(token, image_path, photo_path):
    """Troca a foto de perfil do bot (se a Bot API permitir) e baixa a nova para ``photo_path``.

    Se o Telegram não oferecer esse método, devolve o código ``photo_unsupported``
    e o app mostra como trocar pelo @BotFather.
    """
    api = TelegramApi(token.strip())
    try:
        api.call(
            "setMyProfilePhoto",
            {"photo": {"type": "static", "photo": "attach://upload"}},
            timeout=120,
            files={"upload": image_path},
        )
    except TelegramError as exc:
        if exc.error_code == 404 or "method not found" in exc.description.lower():
            return json.dumps({"ok": False, "error": {
                "code": errors.PHOTO_UNSUPPORTED, "detail": exc.description, "retry_after": None,
            }})
        return _fail(exc)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    try:
        me = api.call("getMe", timeout=20)
        photo = download_profile_photo(api, me["id"], photo_path)
    except Exception:  # noqa: BLE001
        photo = None
    return _ok(photo=photo)
