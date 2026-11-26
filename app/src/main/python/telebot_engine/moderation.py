"""Moderação de grupos: verificação na entrada, filtros, advertências, comandos e modo lento.

Configuração em ``config["moderation"]`` (vale para todos os grupos em que o bot é admin)::

    {"enabled": true,
     "captcha": {"enabled": true, "mode": "button" | "math", "timeout": 5, "fail": "kick" | "mute"},
     "delete_service": true,            # apaga os avisos "fulano entrou/saiu"
     "filters": {"links": true, "allowed_domains": ["meusite.com"], "words": ["palavrão"],
                 "forwards": false, "newcomer_hours": 24, "action": "warn" | "delete"},
     "warnings": {"limit": 3, "action": "mute" | "kick" | "ban", "mute_hours": 24},
     "commands": true, "slowmode": 0, "rules": "texto das regras"}

O bot precisa ser administrador do grupo (apagar mensagens, restringir e banir membros).
Admins do grupo e admins do bot nunca são filtrados nem advertidos.

Comandos (respondendo à mensagem da pessoa; só admins do grupo ou do bot):
``/ban`` ``/unban`` ``/kick`` ``/mute [10m|2h|1d]`` ``/unmute`` ``/warn [motivo]`` ``/unwarn``
``/del`` e ``/warns`` (também em português: ``/banir``, ``/silenciar``...). ``/regras`` (``/rules``)
vale para todo mundo.
"""

import json
import os
import random
import re
import threading
import time

from . import i18n, template
from .automations import keyword_matches
from .api import TelegramApi

CB = "mod:"
STATE_FILE = "moderation.json"
CLEANUP_AFTER = 60          # avisos do bot somem depois de 1 minuto
ADMIN_CACHE = 300
MEDIA_KEYS = ("photo", "video", "animation", "document", "audio", "voice", "video_note", "sticker")
COMMANDS = {
    "ban": "ban", "banir": "ban", "unban": "unban", "desbanir": "unban", "kick": "kick", "expulsar": "kick",
    "mute": "mute", "silenciar": "mute", "unmute": "unmute", "dessilenciar": "unmute",
    "warn": "warn", "advertir": "warn", "unwarn": "unwarn", "perdoar": "unwarn",
    "del": "del", "apagar": "del", "warns": "warns", "advertencias": "warns",
}
RULES_COMMANDS = ("regras", "rules")
SEND_PERMISSIONS = ("can_send_messages", "can_send_audios", "can_send_documents", "can_send_photos", "can_send_videos",
                    "can_send_video_notes", "can_send_voice_notes", "can_send_polls", "can_send_other_messages",
                    "can_add_web_page_previews")
_lock = threading.RLock()


def normalize(data):
    data = data if isinstance(data, dict) else {}
    cap = data.get("captcha") if isinstance(data.get("captcha"), dict) else {}
    fil = data.get("filters") if isinstance(data.get("filters"), dict) else {}
    war = data.get("warnings") if isinstance(data.get("warnings"), dict) else {}

    def num(value, lo, hi, default):
        try:
            return max(lo, min(hi, int(value)))
        except (TypeError, ValueError):
            return default

    def words(raw, limit=200):
        out = []
        for w in raw if isinstance(raw, list) else []:
            w = str(w).strip()[:100]
            if w and w not in out:
                out.append(w)
        return out[:limit]

    return {
        "enabled": bool(data.get("enabled", False)),
        "captcha": {"enabled": bool(cap.get("enabled", False)),
                    "mode": cap.get("mode") if cap.get("mode") in ("button", "math") else "button",
                    "timeout": num(cap.get("timeout"), 1, 30, 5),
                    "fail": cap.get("fail") if cap.get("fail") in ("kick", "mute") else "kick"},
        "delete_service": bool(data.get("delete_service", False)),
        "filters": {"links": bool(fil.get("links", False)),
                    "allowed_domains": [d.lower().lstrip(".") for d in words(fil.get("allowed_domains"), 50)],
                    "words": words(fil.get("words")),
                    "forwards": bool(fil.get("forwards", False)),
                    "newcomer_hours": num(fil.get("newcomer_hours"), 0, 24 * 30, 0),
                    "action": fil.get("action") if fil.get("action") in ("warn", "delete") else "warn"},
        "warnings": {"limit": num(war.get("limit"), 1, 20, 3),
                     "action": war.get("action") if war.get("action") in ("mute", "kick", "ban") else "mute",
                     "mute_hours": num(war.get("mute_hours"), 1, 24 * 366, 24)},
        "commands": bool(data.get("commands", True)),
        "slowmode": num(data.get("slowmode"), 0, 3600, 0),
        "rules": str(data.get("rules") or "")[:4000],
    }


def parse_duration(text, default):
    """``10m``, ``2h``, ``1d``, ``30`` (minutos) → segundos."""
    m = re.match(r"^\s*(\d{1,5})\s*([smhd]|min|h|d|dias?|days?|horas?|hours?)?\b", text or "", re.I)
    if not m:
        return default
    n, unit = int(m.group(1)), (m.group(2) or "m").lower()
    mult = {"s": 1, "m": 60, "min": 60}.get(unit, 3600 if unit.startswith("h") else 86400 if unit[0] == "d" else 60)
    return max(30, min(n * mult, 366 * 86400))


def _name(user):
    user = user or {}
    full = " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x)
    return full or ("@" + user["username"] if user.get("username") else str(user.get("id", "")))


def _mention(user):
    return '<a href="tg://user?id=%d">%s</a>' % (int(user.get("id") or 0), template.escape(_name(user)))


class Moderator:
    def __init__(self, engine):
        self.engine = engine
        self._admins = {}           # chat_id -> (expira, {ids})
        self._perms = {}            # chat_id -> (expira, permissões padrão)
        self._last = {}             # (chat, user) -> última mensagem (modo lento)
        self._rights_warned = {}    # chat_id -> quando avisou que faltam permissões
        self._side = None

    @property
    def settings(self):
        return self.engine.config.get("moderation") or normalize({})

    def t(self, key, *args):
        return i18n.t(key, self.engine.lang, *args)

    # -- estado (advertências, captchas pendentes, entradas, limpeza) -----------------------------

    def _path(self):
        return os.path.join(self.engine.base_dir, STATE_FILE)

    def _load(self):
        try:
            with open(self._path(), "r", encoding="utf-8") as fh:
                data = json.load(fh)
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _save(self, data):
        tmp = self._path() + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False)
        os.replace(tmp, self._path())

    def _update(self, change):
        with _lock:
            data = self._load()
            out = change(data)
            self._save(data)
            return out

    # -- Telegram -----------------------------------------------------------------------------

    def _api(self, side=False):
        if not side or self.engine.scheduler.sync:
            return self.engine.api
        if self._side is None:
            self._side = TelegramApi(self.engine.token)
        return self._side

    def _call(self, method, params, side=False):
        try:
            return self._api(side).call(method, params, timeout=20)
        except Exception as exc:  # noqa: BLE001
            text = str(exc).lower()
            if "not enough rights" in text or "need administrator" in text or "can't remove" in text \
                    or "not an administrator" in text or "have no rights" in text:
                self._missing_rights(params.get("chat_id"), method)
            return None

    def _missing_rights(self, chat_id, method):
        now = time.time()
        if now - self._rights_warned.get(chat_id, 0) < 3600:
            return
        self._rights_warned[chat_id] = now
        self.engine.emit("error", context=method, command=None, retry_in=None, fatal=False, code="mod_rights",
                         detail=str(chat_id), retry_after=None)

    def _say(self, chat_id, text, keyboard=None, cleanup=True, side=False, reply_to=None):
        params = {"chat_id": chat_id, "text": text, "parse_mode": "HTML", "link_preview_options": {"is_disabled": True}}
        if keyboard:
            params["reply_markup"] = keyboard
        if reply_to:
            params["reply_parameters"] = {"message_id": reply_to, "allow_sending_without_reply": True}
        sent = self._call("sendMessage", params, side)
        if cleanup and isinstance(sent, dict) and sent.get("message_id"):
            when = time.time() + CLEANUP_AFTER
            self._update(lambda d: d.setdefault("cleanup", []).append([chat_id, sent["message_id"], when]))
        return sent

    def _delete(self, chat_id, message_id, side=False):
        if message_id:
            self._call("deleteMessage", {"chat_id": chat_id, "message_id": message_id}, side)

    def chat_admins(self, chat_id):
        now = time.time()
        hit = self._admins.get(chat_id)
        if hit and hit[0] > now:
            return hit[1]
        res = self._call("getChatAdministrators", {"chat_id": chat_id})
        ids = {m.get("user", {}).get("id") for m in res} if isinstance(res, list) else set()
        self._admins[chat_id] = (now + ADMIN_CACHE, ids)
        return ids

    def is_admin(self, message, user=None):
        chat = message.get("chat") or {}
        sender_chat = message.get("sender_chat") or {}
        if sender_chat and sender_chat.get("id") == chat.get("id"):
            return True  # admin anônimo
        uid = (user or message.get("from") or {}).get("id")
        self.engine.maybe_reload_admins()
        return uid in self.engine.admins or uid in self.chat_admins(chat.get("id"))

    def default_permissions(self, chat_id):
        now = time.time()
        hit = self._perms.get(chat_id)
        if hit and hit[0] > now:
            return hit[1]
        chat = self._call("getChat", {"chat_id": chat_id}) or {}
        perms = chat.get("permissions") if isinstance(chat, dict) else None
        perms = perms or {k: True for k in SEND_PERMISSIONS}
        self._perms[chat_id] = (now + 600, perms)
        return perms

    def restrict(self, chat_id, user_id, seconds=None, side=False):
        params = {"chat_id": chat_id, "user_id": user_id, "permissions": {k: False for k in SEND_PERMISSIONS},
                  "use_independent_chat_permissions": True}
        if seconds:
            params["until_date"] = int(time.time() + seconds)
        return self._call("restrictChatMember", params, side) is not None

    def unrestrict(self, chat_id, user_id, side=False):
        params = {"chat_id": chat_id, "user_id": user_id, "permissions": self.default_permissions(chat_id),
                  "use_independent_chat_permissions": True}
        return self._call("restrictChatMember", params, side) is not None

    def kick(self, chat_id, user_id, side=False):
        ok = self._call("banChatMember", {"chat_id": chat_id, "user_id": user_id,
                                          "until_date": int(time.time()) + 60}, side) is not None
        self._call("unbanChatMember", {"chat_id": chat_id, "user_id": user_id, "only_if_banned": True}, side)
        return ok

    def ban(self, chat_id, user_id):
        return self._call("banChatMember", {"chat_id": chat_id, "user_id": user_id, "revoke_messages": False}) is not None

    def _log(self, action, chat, user, reason="", by=""):
        self.engine.emit("mod_action", action=action, chat=(chat or {}).get("title") or "", user=_name(user),
                         reason=reason, by=by)

    # -- mensagens ------------------------------------------------------------------------------

    def handle(self, message, parsed):
        """``True`` = a mensagem foi tratada aqui (apagada ou comando de moderação)."""
        s = self.settings
        chat = message.get("chat") or {}
        if parsed and parsed[0] in RULES_COMMANDS and (s["enabled"] or s["rules"]):
            self._say(chat.get("id"), s["rules"] or self.t("mod.no_rules"), cleanup=False)
            return True
        if not s["enabled"] or chat.get("type") not in ("group", "supergroup"):
            return False
        cid = chat.get("id")
        if message.get("new_chat_members"):
            self._on_join(message, s)
            return False  # os gatilhos de "entrou no grupo" continuam valendo
        if message.get("left_chat_member"):
            if s["delete_service"]:
                self._delete(cid, message.get("message_id"))
            key = "%s:%s" % (cid, message["left_chat_member"].get("id"))
            self._update(lambda d: d.get("captcha", {}).pop(key, None))
            return False
        if parsed and parsed[0] in COMMANDS and s["commands"]:
            return self._command(message, COMMANDS[parsed[0]], parsed[1], s)
        user = message.get("from") or {}
        if user.get("is_bot") and not message.get("sender_chat"):
            return False
        if self.is_admin(message):
            return False
        reason = self._violation(message, s)
        if reason is None:
            return False
        self._delete(cid, message.get("message_id"))
        if reason != "slowmode" and s["filters"]["action"] == "warn" and reason != "captcha":
            self.warn(chat, user, self.t("mod.reason_" + reason))
        else:
            self._log("deleted", chat, user, self.t("mod.reason_" + reason))
        return True

    def _violation(self, message, s):
        chat = message.get("chat") or {}
        user = message.get("from") or {}
        key = "%s:%s" % (chat.get("id"), user.get("id"))
        data = self._load()
        if key in (data.get("captcha") or {}):
            return "captcha"
        now = time.time()
        if s["slowmode"]:
            last = self._last.get(key, 0)
            self._last[key] = now
            if now - last < s["slowmode"]:
                return "slowmode"
        f = s["filters"]
        text = message.get("text") or message.get("caption") or ""
        if f["forwards"] and (message.get("forward_origin") or message.get("forward_from") or message.get("forward_from_chat")):
            return "forward"
        links = self._links(message)
        bad_links = [u for u in links if not self._allowed(u, f["allowed_domains"])]
        if f["links"] and bad_links:
            return "link"
        if f["words"] and keyword_matches({"words": f["words"], "match": "word", "case": False}, text):
            return "word"
        joined = (data.get("joined") or {}).get(key)
        if f["newcomer_hours"] and joined and now - joined < f["newcomer_hours"] * 3600:
            if bad_links or any(message.get(k) for k in MEDIA_KEYS):
                return "newcomer"
        return None

    @staticmethod
    def _links(message):
        text = message.get("text") or message.get("caption") or ""
        out = []
        for e in (message.get("entities") or []) + (message.get("caption_entities") or []):
            if e.get("type") == "url":
                out.append(_u16_slice(text, e.get("offset", 0), e.get("length", 0)))
            elif e.get("type") == "text_link" and e.get("url"):
                out.append(e["url"])
        if not out:
            out = re.findall(r"(?:https?://|www\.|t\.me/)\S+", text, re.I)
        return out

    @staticmethod
    def _allowed(url, domains):
        host = re.sub(r"^[a-z]+://", "", url.lower()).split("/")[0].split("?")[0]
        host = host[4:] if host.startswith("www.") else host
        return any(host == d or host.endswith("." + d) for d in domains)

    # -- advertências ---------------------------------------------------------------------------

    def warn(self, chat, user, reason, by=""):
        s = self.settings["warnings"]
        key = "%s:%s" % (chat.get("id"), user.get("id"))

        def change(d):
            w = d.setdefault("warnings", {})
            w[key] = w.get(key, 0) + 1
            count = w[key]
            if count >= s["limit"]:
                w.pop(key, None)
            return count
        count = self._update(change)
        self._log("warn", chat, user, reason, by)
        if count < s["limit"]:
            self._say(chat.get("id"), self.t("mod.warned", _mention(user), count, s["limit"], template.escape(reason)))
            return
        action = s["action"]
        if action == "ban":
            self.ban(chat.get("id"), user.get("id"))
            self._say(chat.get("id"), self.t("mod.limit_ban", _mention(user), s["limit"]), cleanup=False)
        elif action == "kick":
            self.kick(chat.get("id"), user.get("id"))
            self._say(chat.get("id"), self.t("mod.limit_kick", _mention(user), s["limit"]), cleanup=False)
        else:
            self.restrict(chat.get("id"), user.get("id"), s["mute_hours"] * 3600)
            self._say(chat.get("id"), self.t("mod.limit_mute", _mention(user), s["limit"], s["mute_hours"]), cleanup=False)
        self._log(action, chat, user, self.t("mod.reason_limit", s["limit"]), by)

    def warnings_of(self, chat_id, user_id):
        return (self._load().get("warnings") or {}).get("%s:%s" % (chat_id, user_id), 0)

    # -- comandos -------------------------------------------------------------------------------

    def _command(self, message, action, args, s):
        chat = message.get("chat") or {}
        cid = chat.get("id")
        me = message.get("from") or {}
        if not self.is_admin(message):
            self._delete(cid, message.get("message_id"))
            return True  # para quem não é admin, o comando não existe
        target_msg = message.get("reply_to_message") or {}
        target = target_msg.get("from") or {}
        if action == "unban" and not target and args.strip().lstrip("-").isdigit():
            target = {"id": int(args.strip()), "first_name": args.strip()}
        self._delete(cid, message.get("message_id"))
        if not target or target.get("is_bot") and target.get("id") == (self.engine.bot or {}).get("id"):
            self._say(cid, self.t("mod.need_reply"))
            return True
        if action not in ("unban", "unmute", "unwarn", "warns", "del") and target.get("id") in self.chat_admins(cid):
            self._say(cid, self.t("mod.target_admin"))
            return True
        by = _name(me)
        who = _mention(target)
        if action == "del":
            self._delete(cid, target_msg.get("message_id"))
            self._log("del", chat, target, "", by)
        elif action == "ban":
            if self.ban(cid, target["id"]):
                self._say(cid, self.t("mod.banned", who), cleanup=False)
                self._log("ban", chat, target, args, by)
        elif action == "unban":
            if self._call("unbanChatMember", {"chat_id": cid, "user_id": target["id"], "only_if_banned": True}) is not None:
                self._say(cid, self.t("mod.unbanned", who))
                self._log("unban", chat, target, "", by)
        elif action == "kick":
            if self.kick(cid, target["id"]):
                self._say(cid, self.t("mod.kicked", who))
                self._log("kick", chat, target, args, by)
        elif action == "mute":
            seconds = parse_duration(args, s["warnings"]["mute_hours"] * 3600)
            if self.restrict(cid, target["id"], seconds):
                self._say(cid, self.t("mod.muted", who, _duration(seconds, self.engine.lang)))
                self._log("mute", chat, target, args, by)
        elif action == "unmute":
            if self.unrestrict(cid, target["id"]):
                self._say(cid, self.t("mod.unmuted", who))
                self._log("unmute", chat, target, "", by)
        elif action == "warn":
            self.warn(chat, target, args.strip() or self.t("mod.reason_manual"), by)
        elif action == "unwarn":
            key = "%s:%s" % (cid, target["id"])
            self._update(lambda d: (d.setdefault("warnings", {}).pop(key, None)))
            self._say(cid, self.t("mod.unwarned", who))
            self._log("unwarn", chat, target, "", by)
        elif action == "warns":
            self._say(cid, self.t("mod.warn_count", who, self.warnings_of(cid, target["id"]), s["warnings"]["limit"]))
        return True

    # -- verificação na entrada ----------------------------------------------------------------

    def _on_join(self, message, s):
        chat = message.get("chat") or {}
        cid = chat.get("id")
        if s["delete_service"]:
            self._delete(cid, message.get("message_id"))
        now = time.time()
        me = (self.engine.bot or {}).get("id")
        members = [m for m in message["new_chat_members"] if not m.get("is_bot") and m.get("id") != me]

        def record(d):
            joined = d.setdefault("joined", {})
            for m in members:
                joined["%s:%s" % (cid, m["id"])] = now
            limit = now - 31 * 86400
            for k in [k for k, v in joined.items() if v < limit]:
                joined.pop(k, None)
        self._update(record)
        if not s["captcha"]["enabled"]:
            return
        adder = (message.get("from") or {}).get("id")
        for m in members:
            if adder and adder != m["id"] and adder in self.chat_admins(cid):
                continue  # adicionado por um admin: confiável
            self.start_captcha(chat, m, s["captcha"])

    def start_captcha(self, chat, user, cap):
        cid = chat.get("id")
        if not self.restrict(cid, user["id"]):
            return  # sem permissão para restringir: não adianta pedir verificação
        key = "%s:%s" % (cid, user["id"])
        if cap["mode"] == "math":
            a, b = random.randint(1, 9), random.randint(1, 9)
            answer = a + b
            options = sorted({answer} | set(random.sample([x for x in range(2, 19) if x != answer], 3)))
            text = self.t("mod.captcha_math", _mention(user), a, b, cap["timeout"])
            kb = {"inline_keyboard": [[{"text": str(o), "callback_data": "%scap:%s:%s:%s" % (CB, cid, user["id"], o)}
                                       for o in options]]}
        else:
            answer = 0
            text = self.t("mod.captcha_button", _mention(user), cap["timeout"])
            kb = {"inline_keyboard": [[{"text": self.t("mod.captcha_ok"),
                                        "callback_data": "%scap:%s:%s:0" % (CB, cid, user["id"])}]]}
        sent = self._say(cid, text, kb, cleanup=False)
        msg_id = sent.get("message_id") if isinstance(sent, dict) else None
        deadline = time.time() + cap["timeout"] * 60
        self._update(lambda d: d.setdefault("captcha", {}).__setitem__(
            key, {"answer": answer, "deadline": deadline, "message_id": msg_id, "name": _name(user),
                  "title": chat.get("title") or ""}))

    def handle_callback(self, query):
        data = query.get("data") or ""
        if not data.startswith(CB + "cap:"):
            return False
        try:
            _, cid, uid, value = data.split(":")[1:5] if data.count(":") >= 4 else ("", "", "", "")
            cid, uid, value = int(cid), int(uid), int(value)
        except ValueError:
            self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
            return True
        user = query.get("from") or {}
        if user.get("id") != uid:
            self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id"),
                                                          "text": self.t("mod.captcha_not_you"), "show_alert": True})
            return True
        key = "%s:%s" % (cid, uid)
        pending = (self._load().get("captcha") or {}).get(key)
        if pending is None:
            self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
            return True
        if value != pending["answer"]:
            self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id"),
                                                          "text": self.t("mod.captcha_wrong"), "show_alert": True})
            return True
        self._update(lambda d: d.get("captcha", {}).pop(key, None))
        self.unrestrict(cid, uid)
        self._delete(cid, pending.get("message_id"))
        self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id"), "text": self.t("mod.captcha_done")})
        self._log("verified", {"title": pending.get("title")}, user)
        return True

    # -- tempo (chamado pelo agendador) ---------------------------------------------------------

    def tick(self, now=None):
        now = now or time.time()
        data = self._load()
        if not data.get("captcha") and not data.get("cleanup"):
            return
        expired, to_delete = [], []

        def change(d):
            for key, p in list((d.get("captcha") or {}).items()):
                if p["deadline"] <= now:
                    expired.append((key, p))
                    d["captcha"].pop(key, None)
            keep = []
            for item in d.get("cleanup") or []:
                (to_delete if item[2] <= now else keep).append(item)
            d["cleanup"] = keep
        self._update(change)
        fail = self.settings["captcha"]["fail"]
        for key, p in expired:
            cid, uid = (int(x) for x in key.split(":"))
            self._delete(cid, p.get("message_id"), side=True)
            if fail == "kick":
                self.kick(cid, uid, side=True)
            self.engine.emit("mod_action", action="captcha_" + fail, chat=p.get("title") or "", user=p.get("name") or "",
                             reason="", by="")
        for cid, mid, _ in to_delete:
            self._delete(cid, mid, side=True)


def _u16_slice(text, offset, length):
    raw = text.encode("utf-16-le")
    return raw[offset * 2:(offset + length) * 2].decode("utf-16-le", "replace")


def _duration(seconds, lang):
    if seconds % 86400 == 0:
        return i18n.t("mod.dur_days", lang, seconds // 86400)
    if seconds % 3600 == 0:
        return i18n.t("mod.dur_hours", lang, seconds // 3600)
    return i18n.t("mod.dur_minutes", lang, max(1, seconds // 60))
