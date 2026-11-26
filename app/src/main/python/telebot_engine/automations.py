"""Gatilhos além dos comandos: o bot reage sem ninguém digitar ``/comando``.

Cada gatilho fica na lista ``commands`` do ``config.json`` com ``"kind"`` diferente de
``"command"`` e as opções em ``"trigger"``; a resposta são os mesmos blocos dos comandos.

- ``keyword``: palavra-chave no texto (contém, palavra inteira, começa com, igual ou
  expressão regular). Em grupo, o bot só vê mensagens comuns se o modo de privacidade
  estiver desligado no @BotFather (/setprivacy) ou se ele for admin.
- ``media``: tipo de mensagem (foto, áudio, localização, contato...).
- ``event``: ``join`` (entrou no grupo), ``leave`` (saiu), ``join_request`` (pediu para
  entrar; pode aprovar/recusar sozinho), ``reaction`` (reagiu a uma mensagem),
  ``bot_added`` (o bot foi adicionado a um grupo/canal) e ``bot_promoted`` (virou admin).
- ``start``: link de convite com parâmetro (``t.me/bot?start=promo``); ``{args}`` = parâmetro.
- ``inline``: resultados do modo inline (``@bot texto`` em qualquer conversa).
"""

import re

from . import admins as admins_mod
from . import chats, template
from .pyfacil_names import fold

KINDS = ("command", "keyword", "media", "event", "start", "inline")
MATCHES = ("contains", "word", "starts", "equals", "regex")
WHERE = ("all", "private", "groups")
MEDIA_KINDS = ("photo", "video", "animation", "voice", "audio", "video_note", "document", "sticker",
               "location", "contact", "poll")
EVENTS = ("join", "leave", "join_request", "reaction", "bot_added", "bot_promoted")
APPROVE = ("none", "approve", "decline")
START_PARAM = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
# Updates que os gatilhos precisam receber do Telegram.
UPDATES = ["chat_join_request", "message_reaction", "inline_query"]


def _words(raw, limit=200):
    out = []
    for w in raw if isinstance(raw, list) else []:
        w = str(w).strip()[:limit]
        if w and w not in out:
            out.append(w)
    return out[:50]


def normalize_spec(kind, raw):
    """Opções do gatilho. ``None`` = gatilho incompleto (fica de fora)."""
    raw = raw if isinstance(raw, dict) else {}
    where = raw.get("where") if raw.get("where") in WHERE else "all"
    if kind == "keyword":
        match = raw.get("match") if raw.get("match") in MATCHES else "contains"
        case = bool(raw.get("case", False))
        words = _words(raw.get("words"))
        if match == "regex":
            ok = []
            for w in words:
                try:
                    re.compile(w)
                    ok.append(w)
                except re.error:
                    pass
            words = ok
        return {"words": words, "match": match, "case": case, "where": where} if words else None
    if kind == "media":
        kinds = [k for k in raw.get("kinds") or [] if k in MEDIA_KINDS]
        return {"kinds": kinds, "where": where} if kinds else None
    if kind == "event":
        event = raw.get("event")
        if event not in EVENTS:
            return None
        approve = raw.get("approve") if raw.get("approve") in APPROVE else "none"
        return {"event": event, "approve": approve, "emojis": _words(raw.get("emojis"), 16)[:20], "where": where}
    if kind == "start":
        params = [p for p in _words(raw.get("words"), 64) if START_PARAM.match(p)]
        return {"words": params, "prefix": bool(raw.get("prefix", False))} if params else None
    if kind == "inline":
        return {"words": _words(raw.get("words")), "title": str(raw.get("title") or "").strip()[:64],
                "description": str(raw.get("description") or "").strip()[:128]}
    return None


def keyword_matches(spec, text):
    """Primeira palavra que serviu (ou ``None``)."""
    if not text:
        return None
    case = spec["case"]
    for w in spec["words"]:
        if spec["match"] == "regex":
            if re.search(w, text, 0 if case else re.IGNORECASE):
                return w
            continue
        t, ww = (text, w) if case else (fold(text), fold(w))
        if spec["match"] == "contains" and ww in t:
            return w
        if spec["match"] == "word" and re.search(r"(?<!\w)%s(?!\w)" % re.escape(ww), t):
            return w
        if spec["match"] == "starts" and t.lstrip().startswith(ww):
            return w
        if spec["match"] == "equals" and t.strip() == ww.strip():
            return w
    return None


def _where_ok(spec, chat):
    kind = (chat or {}).get("type")
    where = spec.get("where", "all")
    if where == "private":
        return kind == "private"
    if where == "groups":
        return kind in ("group", "supergroup")
    return True


class AutomationRunner:
    def __init__(self, engine):
        self.engine = engine

    @property
    def items(self):
        return self.engine.config.get("automations") or []

    def of(self, kind):
        return [a for a in self.items if a["kind"] == kind]

    def _allowed(self, auto, user):
        level = auto.get("admin_level")
        if level is None:
            return True
        eng = self.engine
        eng.maybe_reload_admins()
        admin = eng.admins.get((user or {}).get("id"))
        return admins_mod.can_use(admin and admin.get("level"), level)

    def _run(self, auto, label, chat_id, user, args="", message=None, reply=False, update=None):
        eng = self.engine
        if message is not None and (message.get("from") or {}).get("id") != (user or {}).get("id"):
            message = dict(message, **{"from": user})  # ex.: quem entrou no grupo, não quem adicionou
        reply_to = None
        if reply and message and (message.get("chat") or {}).get("type") != "private":
            reply_to = {"message_id": message.get("message_id"), "allow_sending_without_reply": True}
        eng.emit("automation", kind=auto["kind"], label=label, event=auto["trigger"].get("event") or "",
                 user=_name(user))
        eng.start_blocks(auto["blocks"], label, chat_id, user, args, reply_to=reply_to,
                         update=update if update is not None else ({"message": message} if message else {}),
                         kind=auto["kind"])

    # -- mensagens comuns ---------------------------------------------------------------------

    def handle_message(self, message):
        """Mensagem que não é comando. ``True`` se algum gatilho respondeu."""
        chat = message.get("chat") or {}
        user = message.get("from") or {}
        if message.get("new_chat_members"):
            me = (self.engine.bot or {}).get("id")
            done = False
            for member in message["new_chat_members"]:
                if member.get("id") != me:
                    done = self.event("join", chat, member, message=message) or done
            return done
        if message.get("left_chat_member"):
            member = message["left_chat_member"]
            if member.get("id") == (self.engine.bot or {}).get("id"):
                return False
            return self.event("leave", chat, member, message=message)
        text = message.get("text") or message.get("caption") or ""
        if text:
            for auto in self.of("keyword"):
                spec = auto["trigger"]
                if not _where_ok(spec, chat):
                    continue
                word = keyword_matches(spec, text)
                if word is not None and self._allowed(auto, user):
                    self._run(auto, word, chat.get("id"), user, text, message, reply=True)
                    return True
        kind = chats.parse_message(message).get("kind")
        for auto in self.of("media"):
            spec = auto["trigger"]
            if kind in spec["kinds"] and _where_ok(spec, chat) and self._allowed(auto, user):
                self._run(auto, kind, chat.get("id"), user, text, message, reply=True)
                return True
        return False

    def handle_start(self, message, param):
        """``/start parametro`` (link de convite). ``True`` se algum gatilho respondeu."""
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        for auto in self.of("start"):
            spec = auto["trigger"]
            hit = next((p for p in spec["words"] if param == p or (spec["prefix"] and param.startswith(p))), None)
            if hit is not None and self._allowed(auto, user):
                self._run(auto, hit, chat.get("id"), user, param, message)
                return True
        return False

    # -- eventos ------------------------------------------------------------------------------

    def event(self, name, chat, user, message=None, update=None, args="", emoji=None, chat_id=None):
        done = False
        for auto in self.of("event"):
            spec = auto["trigger"]
            if spec["event"] != name or not _where_ok(spec, chat):
                continue
            if name == "reaction" and spec["emojis"] and emoji not in spec["emojis"]:
                continue
            self._run(auto, name, chat_id or chat.get("id"), user, args, message, update=update)
            done = True
        return done

    def handle_join_request(self, req):
        """Pedido para entrar num grupo/canal: aprova/recusa (se configurado) e responde no privado."""
        eng = self.engine
        chat = req.get("chat") or {}
        user = req.get("from") or {}
        done = False
        for auto in self.of("event"):
            spec = auto["trigger"]
            if spec["event"] != "join_request" or not _where_ok(spec, chat):
                continue
            if spec["approve"] in ("approve", "decline"):
                method = "approveChatJoinRequest" if spec["approve"] == "approve" else "declineChatJoinRequest"
                try:
                    eng.api.call(method, {"chat_id": chat.get("id"), "user_id": user.get("id")}, timeout=20)
                except Exception as exc:  # noqa: BLE001
                    from . import errors
                    eng.emit("error", context=method, command=None, retry_in=None, fatal=False,
                             **errors.classify(exc))
            target = req.get("user_chat_id") or user.get("id")
            if auto["blocks"]:
                self._run(auto, "join_request", target, user, chat.get("title") or "",
                          update={"chat_join_request": req})
            done = True
        return done

    def handle_reaction(self, update):
        r = update.get("message_reaction") or {}
        user = r.get("user") or {}
        if not user or user.get("is_bot"):
            return False
        old = {x.get("emoji") or x.get("custom_emoji_id") for x in r.get("old_reaction") or []}
        added = [x.get("emoji") or x.get("custom_emoji_id") for x in r.get("new_reaction") or []]
        added = [e for e in added if e and e not in old]
        if not added:
            return False
        chat = r.get("chat") or {}
        return self.event("reaction", chat, user, update={"message_reaction": r}, args=added[0], emoji=added[0])

    def handle_my_membership(self, change):
        """O próprio bot foi adicionado a um grupo/canal ou promovido a admin."""
        old = (change.get("old_chat_member") or {}).get("status")
        new = (change.get("new_chat_member") or {}).get("status")
        chat = change.get("chat") or {}
        if chat.get("type") == "private":
            return False
        user = change.get("from") or {}
        if old in ("left", "kicked", None) and new in ("member", "administrator"):
            self.event("bot_added", chat, user, update={"my_chat_member": change})
        if new == "administrator" and old != "administrator":
            self.event("bot_promoted", chat, user, update={"my_chat_member": change})

    # -- modo inline --------------------------------------------------------------------------

    def handle_inline(self, query):
        eng = self.engine
        items = self.of("inline")
        if not items:
            return False
        text = (query.get("query") or "").strip()
        user = query.get("from") or {}
        stamp = eng._now()
        date, _, hour = stamp.partition(" ")
        ctx = template.build_context({"from": user, "chat": {"id": user.get("id"), "type": "private"}}, text,
                                     (eng.bot or {}).get("username", ""), date, hour)
        results = []
        for auto in items:
            spec = auto["trigger"]
            if spec["words"] and text and not any(fold(w) in fold(text) for w in spec["words"]):
                continue
            if not self._allowed(auto, user):
                continue
            result = self._result(auto, ctx)
            if result:
                results.append(result)
            if len(results) >= 50:
                break
        try:
            eng.api.call("answerInlineQuery", {"inline_query_id": query.get("id"), "results": results,
                                               "cache_time": 10, "is_personal": True}, timeout=20)
        except Exception:  # noqa: BLE001 - a pessoa já pode ter digitado outra coisa
            pass
        eng.emit("inline_query", user=_name(user), query=text[:100], results=len(results))
        return True

    def _result(self, auto, ctx):
        eng = self.engine
        msg = next((b["message"] for b in auto["blocks"] if b["type"] == "message"), None)
        if msg is None:
            return None
        from .engine import build_keyboard
        body = template.render(msg["text"], ctx) if msg["text"].strip() else ""
        spec = auto["trigger"]
        title = spec["title"] or auto.get("description") or template.html_to_plain(body).split("\n")[0][:64] or "•"
        base = {"id": auto["id"][:64], "title": title}
        if spec["description"]:
            base["description"] = spec["description"]
        kb = build_keyboard(msg["buttons"], private=False)
        if kb:
            base["reply_markup"] = kb
        media = msg.get("media")
        file_id = None
        if media:
            file_id = media.get("file_id") if not media.get("file") else eng._media_cache.get(media["file"])
        if media and file_id:
            kind = media["type"]
            spec_map = {"photo": ("photo", "photo_file_id"), "video": ("video", "video_file_id"),
                        "animation": ("gif", "gif_file_id"), "document": ("document", "document_file_id"),
                        "audio": ("audio", "audio_file_id"), "voice": ("voice", "voice_file_id"),
                        "sticker": ("sticker", "sticker_file_id")}
            if kind in spec_map:
                rtype, field = spec_map[kind]
                out = dict(base, type=rtype, **{field: file_id})
                if kind in ("sticker", "audio"):
                    out.pop("title", None)  # esses tipos não têm título
                if body and kind != "sticker":
                    out["caption"] = body[:1024]
                    out["parse_mode"] = "HTML"
                return out
        if not body:
            return None
        return dict(base, type="article", input_message_content={
            "message_text": body, "parse_mode": "HTML",
            "link_preview_options": {"is_disabled": not msg.get("link_preview")}})


def _name(user):
    user = user or {}
    return " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x) or \
        ("@" + user["username"] if user.get("username") else str(user.get("id") or ""))
