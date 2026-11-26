"""Atendimento: modo suporte, horário de atendimento e perguntas frequentes (FAQ) com busca.

Configuração em ``config["desk"]``::

    {"support": {"enabled": true, "admin_level": 1, "ack": "Recebemos sua mensagem!", "ack_hours": 12},
     "hours": {"enabled": true, "days": [0, 1, 2, 3, 4], "start": "09:00", "end": "18:00",
               "away": "Estamos fora do horário..."},
     "faq": {"enabled": true, "command": "faq", "items": [{"q": "Qual o horário?", "a": "8h às 18h",
             "keywords": ["que horas abre"]}]}}

Mensagens livres na conversa privada (que nenhum comando, gatilho ou espera tratou):

1. FAQ: se a pergunta parecer com uma das perguntas cadastradas, o bot responde sozinho.
2. Fora do horário: manda o aviso de ausência (no máximo uma vez a cada 6 h por pessoa).
3. Modo suporte: a mensagem chega aos admins no Telegram. O admin responde com
   "Responder" e o bot entrega a resposta à pessoa (texto, foto, áudio, qualquer coisa).
"""

import difflib
import json
import os
import re
import threading
import time

from . import admins as admins_mod
from . import i18n, template
from .pyfacil_names import fold

STATE_FILE = "desk.json"
AWAY_EVERY = 6 * 3600
MAX_MAP = 3000
CB = "dk:"
_lock = threading.RLock()
STOPWORDS = set("""
a o as os um uma uns umas de da do das dos e ou que em no na nos nas para pra por com sem como qual quais quanto
quando onde eu voce voces vc vcs me te se ser e sao tem ter ha isso esse essa este esta ai aqui la oi ola bom boa dia
tarde noite por favor obrigado obrigada gostaria queria saber
the a an is are was were of to in on at for from with what which how when where who do does did i you we it this
that my your can could would please hi hello thanks thank want know
el la los las un una y o de del en para por con como cual cuando donde que es son hola gracias quiero saber
le la les un une et ou de du des en pour par avec comment quel quelle quand ou qui est sont bonjour merci
""".split())


def _hhmm(value, default):
    m = re.match(r"^([01]?\d|2[0-3]):([0-5]\d)$", str(value or ""))
    return "%02d:%02d" % (int(m.group(1)), int(m.group(2))) if m else default


def normalize(data):
    data = data if isinstance(data, dict) else {}
    sup = data.get("support") if isinstance(data.get("support"), dict) else {}
    hrs = data.get("hours") if isinstance(data.get("hours"), dict) else {}
    faq = data.get("faq") if isinstance(data.get("faq"), dict) else {}
    items = []
    for it in faq.get("items") or []:
        if not isinstance(it, dict):
            continue
        q, a = str(it.get("q") or "").strip()[:300], str(it.get("a") or "").strip()[:4000]
        if q and a:
            kws = [str(k).strip()[:200] for k in it.get("keywords") or [] if str(k).strip()][:20]
            items.append({"q": q, "a": a, "keywords": kws})
    try:
        level = max(1, min(10, int(sup.get("admin_level") or 1)))
    except (TypeError, ValueError):
        level = 1
    command = re.sub(r"[^a-z0-9_]", "", str(faq.get("command") or "faq").lower())[:32] or "faq"
    return {
        "support": {"enabled": bool(sup.get("enabled", False)), "admin_level": level,
                    "ack": str(sup.get("ack") or "")[:1000], "ack_hours": max(0, min(168, int(sup.get("ack_hours") or 12)))},
        "hours": {"enabled": bool(hrs.get("enabled", False)),
                  "days": sorted({int(d) for d in hrs.get("days") or [0, 1, 2, 3, 4] if str(d).isdigit() and 0 <= int(d) <= 6}),
                  "start": _hhmm(hrs.get("start"), "09:00"), "end": _hhmm(hrs.get("end"), "18:00"),
                  "away": str(hrs.get("away") or "")[:2000]},
        "faq": {"enabled": bool(faq.get("enabled", False)) and bool(items), "command": command, "items": items[:200]},
    }


def tokens(text):
    return [w for w in re.findall(r"\w+", fold(text or "").lower()) if len(w) > 1 and w not in STOPWORDS]


def _similar(word, words):
    if word in words:
        return True
    return len(word) >= 4 and any(len(w) >= 4 and difflib.SequenceMatcher(None, word, w).ratio() >= 0.84 for w in words)


def faq_match(items, text, threshold=0.6):
    """Índice da pergunta mais parecida (ou ``None``)."""
    words = set(tokens(text))
    if not words:
        return None
    best, best_score = None, 0.0
    for i, item in enumerate(items):
        for phrase in [item["q"]] + item["keywords"]:
            q = list(dict.fromkeys(tokens(phrase)))
            if not q:
                continue
            hit = sum(1 for w in q if _similar(w, words))
            score = hit / len(q)
            if len(q) == 1 and hit == 1 and len(words) > 4:
                score = 0.6  # uma palavra só numa mensagem longa: conta, mas sem certeza
            if score > best_score:
                best, best_score = i, score
    return best if best_score >= threshold else None


class Desk:
    def __init__(self, engine):
        self.engine = engine

    @property
    def settings(self):
        return self.engine.config.get("desk") or normalize({})

    def t(self, key, *args):
        return i18n.t(key, self.engine.lang, *args)

    # -- estado -------------------------------------------------------------------------------

    def _path(self):
        return os.path.join(self.engine.base_dir, STATE_FILE)

    def _load(self):
        try:
            with open(self._path(), "r", encoding="utf-8") as fh:
                data = json.load(fh)
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _update(self, change):
        with _lock:
            data = self._load()
            out = change(data)
            tmp = self._path() + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False)
            os.replace(tmp, self._path())
            return out

    # -- horário -------------------------------------------------------------------------------

    def is_open(self, now_ts=None):
        h = self.settings["hours"]
        if not h["enabled"]:
            return True
        now = self.engine.scheduler.to_local(now_ts or time.time())
        hm = now.strftime("%H:%M")
        if h["start"] <= h["end"]:
            return now.weekday() in h["days"] and h["start"] <= hm < h["end"]
        # passa da meia-noite (ex.: 18:00 às 02:00)
        if hm >= h["start"]:
            return now.weekday() in h["days"]
        return (now.weekday() - 1) % 7 in h["days"] and hm < h["end"]

    # -- mensagens ------------------------------------------------------------------------------

    def is_bot_admin(self, uid):
        self.engine.maybe_reload_admins()
        return uid in self.engine.admins

    def handle_admin_reply(self, message):
        """Admin respondeu (com "Responder") a uma mensagem de suporte: entrega à pessoa."""
        chat = message.get("chat") or {}
        reply = message.get("reply_to_message") or {}
        if chat.get("type") != "private" or not reply or not self.is_bot_admin((message.get("from") or {}).get("id")):
            return False
        key = "%s:%s" % (chat.get("id"), reply.get("message_id"))
        target = (self._load().get("map") or {}).get(key)
        if target is None:
            return False
        eng = self.engine
        ok = eng._call_send("copyMessage", {"chat_id": target, "from_chat_id": chat["id"],
                                            "message_id": message["message_id"]}, self.t("label.support"))
        if ok:
            try:
                eng.api.call("setMessageReaction", {"chat_id": chat["id"], "message_id": message["message_id"],
                                                    "reaction": [{"type": "emoji", "emoji": "👍"}]}, timeout=10)
            except Exception:  # noqa: BLE001 - só um enfeite
                pass
            eng.emit("support_reply", admin=(message.get("from") or {}).get("first_name") or "", user=str(target))
        else:
            eng._call_send("sendMessage", {"chat_id": chat["id"], "text": self.t("desk.reply_failed"),
                                           "reply_parameters": {"message_id": message["message_id"]}}, self.t("label.support"))
        return True

    def handle_customer(self, message):
        """Mensagem livre na conversa privada. ``True`` se o atendimento cuidou dela."""
        s = self.settings
        chat = message.get("chat") or {}
        user = message.get("from") or {}
        if chat.get("type") != "private" or user.get("is_bot"):
            return False
        text = message.get("text") or message.get("caption") or ""
        if s["faq"]["enabled"] and text:
            i = faq_match(s["faq"]["items"], text)
            if i is not None:
                self.answer_faq(chat["id"], i, reply_to=message.get("message_id"))
                return True
        is_admin = self.is_bot_admin(user.get("id"))
        done = False
        away_sent = False
        if not self.is_open() and s["hours"]["away"] and not is_admin:
            away_sent = self._once(chat["id"], "away", AWAY_EVERY)
            if away_sent:
                self._send(chat["id"], template.render(s["hours"]["away"], self._ctx(user, chat), self.engine.lang))
            done = True
        if s["support"]["enabled"] and not is_admin:
            self.forward(message)
            if s["support"]["ack"] and not away_sent and \
                    self._once(chat["id"], "ack", max(1, s["support"]["ack_hours"]) * 3600):
                self._send(chat["id"], template.render(s["support"]["ack"], self._ctx(user, chat), self.engine.lang))
            done = True
        return done

    def _ctx(self, user, chat):
        return self.engine.flows._context(user, chat.get("id"), "")

    def _send(self, chat_id, html, keyboard=None, reply_to=None):
        params = {"chat_id": chat_id, "text": html, "parse_mode": "HTML", "link_preview_options": {"is_disabled": True}}
        if keyboard:
            params["reply_markup"] = keyboard
        if reply_to:
            params["reply_parameters"] = {"message_id": reply_to, "allow_sending_without_reply": True}
        return self.engine._call_send("sendMessage", params, self.t("label.support"))

    def _once(self, chat_id, what, every):
        """``True`` se ainda não mandou ``what`` para essa pessoa nesse intervalo (e marca)."""
        now = time.time()
        key = "%s:%s" % (what, chat_id)

        def change(d):
            last = d.setdefault("sent", {}).get(key, 0)
            if now - last < every:
                return False
            d["sent"][key] = now
            return True
        return self._update(change)

    def forward(self, message):
        """Copia a mensagem para cada admin (da classe configurada) com quem mandou no topo."""
        eng = self.engine
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        name = " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x) or str(user.get("id"))
        who = template.escape(name) + (" (@%s)" % template.escape(user["username"]) if user.get("username") else "")
        header = self.t("desk.header", who, chat.get("id"))
        level = self.settings["support"]["admin_level"]
        eng.maybe_reload_admins()
        ids = []
        for admin_id, admin in sorted(eng.admins.items()):
            if not admins_mod.can_use(admin.get("level"), level):
                continue
            if message.get("text") is not None:
                sent = eng._call_send("sendMessage", {"chat_id": admin_id, "parse_mode": "HTML",
                                                      "text": header + "\n\n" + template.escape(message["text"]),
                                                      "link_preview_options": {"is_disabled": True}}, self.t("label.support"))
                if isinstance(sent, dict):
                    ids.append((admin_id, sent.get("message_id")))
                continue
            head = eng._call_send("sendMessage", {"chat_id": admin_id, "text": header, "parse_mode": "HTML"},
                                  self.t("label.support"))
            copy = eng._call_send("copyMessage", {"chat_id": admin_id, "from_chat_id": chat["id"],
                                                  "message_id": message["message_id"]}, self.t("label.support"))
            for sent in (head, copy):
                if isinstance(sent, dict) and sent.get("message_id"):
                    ids.append((admin_id, sent["message_id"]))

        def change(d):
            m = d.setdefault("map", {})
            for admin_id, mid in ids:
                m["%s:%s" % (admin_id, mid)] = chat["id"]
            if len(m) > MAX_MAP:
                for k in list(m)[:len(m) - MAX_MAP]:
                    m.pop(k, None)
        if ids:
            self._update(change)
        eng.emit("support_message", user=name, admins=len({a for a, _ in ids}))

    # -- FAQ ----------------------------------------------------------------------------------

    def answer_faq(self, chat_id, index, reply_to=None, edit=None):
        items = self.settings["faq"]["items"]
        if not 0 <= index < len(items):
            return
        item = items[index]
        html = "<b>%s</b>\n\n%s" % (template.escape(item["q"]), template.markdown_to_html(item["a"]))
        self._send(chat_id, html, reply_to=reply_to)
        self.engine.emit("faq_answered", question=item["q"][:100])

    def faq_menu(self, chat_id):
        items = self.settings["faq"]["items"]
        if not items:
            return
        kb = {"inline_keyboard": [[{"text": it["q"][:60], "callback_data": "%sfaq:%d" % (CB, i)}] for i, it in enumerate(items[:50])]}
        self._send(chat_id, self.t("desk.faq_menu"), kb)

    def handle_command(self, message, parsed):
        s = self.settings
        if parsed and s["faq"]["enabled"] and parsed[0] == s["faq"]["command"]:
            self.faq_menu((message.get("chat") or {}).get("id"))
            return True
        return False

    def handle_callback(self, query):
        data = query.get("data") or ""
        if not data.startswith(CB + "faq:"):
            return False
        self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
        chat_id = ((query.get("message") or {}).get("chat") or {}).get("id") or (query.get("from") or {}).get("id")
        try:
            self.answer_faq(chat_id, int(data.split(":")[2]))
        except (ValueError, IndexError):
            pass
        return True
