"""Transforma exceções em códigos que o app sabe explicar.

O texto de cada explicação fica no lado Kotlin (strings.xml), para poder ser
traduzido. Aqui só decidimos *qual* problema aconteceu.
"""

from .api import NetworkError, TelegramError

INVALID_TOKEN = "invalid_token"
NETWORK = "network"
CONFLICT = "conflict"
WEBHOOK_ACTIVE = "webhook_active"
CHAT_NOT_FOUND = "chat_not_found"
CANT_INITIATE = "cant_initiate"
BLOCKED_BY_USER = "blocked_by_user"
NOT_ENOUGH_RIGHTS = "not_enough_rights"
TOO_MANY_REQUESTS = "too_many_requests"
PARSE_ENTITIES = "parse_entities"
TOKEN_FORMAT = "token_format"
MEDIA_ERROR = "media_error"
MEDIA_MISSING = "media_missing"
TOO_LONG = "too_long"
BAD_BUTTON_URL = "bad_button_url"
PHOTO_UNSUPPORTED = "photo_unsupported"
UNKNOWN = "unknown"


def classify(exc):
    """Devolve um dict ``{"code", "detail", "retry_after"}`` para a exceção."""
    if isinstance(exc, NetworkError):
        return {"code": NETWORK, "detail": exc.reason, "retry_after": None}
    if isinstance(exc, TelegramError):
        desc = exc.description.lower()
        code = UNKNOWN
        if exc.error_code == 401 or (exc.error_code == 404 and desc.strip() == "not found"):
            code = INVALID_TOKEN
        elif exc.error_code == 409 and "webhook" in desc:
            code = WEBHOOK_ACTIVE
        elif exc.error_code == 409:
            code = CONFLICT
        elif exc.error_code == 429:
            code = TOO_MANY_REQUESTS
        elif "chat not found" in desc:
            code = CHAT_NOT_FOUND
        elif "can't initiate conversation" in desc:
            code = CANT_INITIATE
        elif "bot was blocked by the user" in desc:
            code = BLOCKED_BY_USER
        elif "not enough rights" in desc or "have no rights" in desc \
                or "need administrator rights" in desc:
            code = NOT_ENOUGH_RIGHTS
        elif "can't parse entities" in desc:
            code = PARSE_ENTITIES
        elif "message is too long" in desc or "caption is too long" in desc \
                or "too long" in desc:
            code = TOO_LONG
        elif "button_url_invalid" in desc or "wrong http url" in desc \
                or "unsupported url protocol" in desc:
            code = BAD_BUTTON_URL
        elif "file" in desc and ("wrong" in desc or "too big" in desc or "invalid" in desc
                                 or "failed to get http url" in desc or "type of file" in desc
                                 or "image_process_failed" in desc or "photo_invalid" in desc):
            code = MEDIA_ERROR
        elif "image_process_failed" in desc or "photo_invalid" in desc or "dimensions" in desc:
            code = MEDIA_ERROR
        return {
            "code": code,
            "detail": "%s %s" % (exc.error_code, exc.description),
            "retry_after": exc.retry_after,
        }
    if isinstance(exc, (FileNotFoundError, IsADirectoryError)):
        return {"code": MEDIA_MISSING, "detail": str(exc), "retry_after": None}
    return {"code": UNKNOWN, "detail": "%s: %s" % (type(exc).__name__, exc), "retry_after": None}
