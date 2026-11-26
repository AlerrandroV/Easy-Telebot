"""Formulários: várias perguntas em sequência, respostas guardadas e exportáveis (CSV).

Bloco ``form`` na resposta de um comando ou gatilho::

    {"type": "form", "id": "cadastro", "title": "Cadastro",
     "questions": [{"text": "Qual o seu nome?", "kind": "text", "var": "nome_completo"},
                   {"text": "Qual plano?", "kind": "choice", "options": ["Básico", "Premium"]}],
     "done": "Obrigado, {nome}!", "notify": true}

Tipos: ``text``, ``number``, ``email``, ``phone`` e ``choice`` (teclado com as opções).
Cada resposta pode virar uma variável (``var``) para as mensagens seguintes. Um comando no
meio cancela o formulário. As respostas ficam em ``forms.json`` (até 5.000 por formulário).
"""

import csv
import io
import json
import os
import re
import threading
import time

from . import admins as admins_mod
from . import i18n, template
from .pyfacil_names import fold

STATE_FILE = "forms.json"
KINDS = ("text", "number", "email", "phone", "choice")
MAX_RESPONSES = 5000
_lock = threading.RLock()
_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_VAR = re.compile(r"^[a-z_][a-z0-9_]{0,31}$")
_PATTERNS = {
    "number": re.compile(r"^\s*-?\d+([.,]\d+)?\s*$"),
    "email": re.compile(r"^\s*[^@\s]+@[^@\s]+\.[^@\s]+\s*$"),
    "phone": re.compile(r"^\s*\+?[\d\s().-]{8,20}\s*$"),
}


def normalize_block(b):
    fid = str(b.get("id") or "").strip()
    if not _ID.match(fid):
        return None
    questions = []
    for q in b.get("questions") or []:
        if not isinstance(q, dict) or not str(q.get("text") or "").strip():
            continue
        kind = q.get("kind") if q.get("kind") in KINDS else "text"
        options = [str(o).strip()[:64] for o in q.get("options") or [] if str(o).strip()][:12]
        if kind == "choice" and not options:
            kind = "text"
        var = str(q.get("var") or "").strip().lower()
        questions.append({"text": str(q["text"])[:1000], "kind": kind, "options": options,
                          "var": var if _VAR.match(var) else ""})
    if not questions:
        return None
    return {"type": "form", "id": fid, "title": str(b.get("title") or "").strip()[:100], "questions": questions[:30],
            "done": str(b.get("done") or "")[:2000], "notify": bool(b.get("notify", True))}


def valid(question, text):
    """A resposta serve? Devolve o valor limpo ou ``None``."""
    text = (text or "").strip()
    if not text:
        return None
    kind = question["kind"]
    if kind == "choice":
        return next((o for o in question["options"] if fold(o) == fold(text)), None)
    if kind in _PATTERNS and not _PATTERNS[kind].match(text):
        return None
    return text[:1000]


class Forms:
    def __init__(self, engine):
        self.engine = engine

    def t(self, key, *args):
        return i18n.t(key, self.engine.lang, *args)

    def block(self, fid):
        return (self.engine.config.get("forms") or {}).get(fid)

    def start(self, block, chat_id, user, sender=None):
        from .flows import _update
        sender = sender or self.engine
        waiting = {"form": block["id"], "step": 0, "answers": [], "chat_id": chat_id, "since": int(time.time())}
        _update(self.engine.base_dir, user.get("id"), lambda st: st.__setitem__("waiting", waiting))
        self.engine.emit("form_started", form=block["title"] or block["id"], user=user.get("first_name") or "")
        self.ask(block, 0, chat_id, user, sender)

    def ask(self, block, step, chat_id, user, sender=None, invalid=False):
        sender = sender or self.engine
        q = block["questions"][step]
        ctx = self.engine.flows._context(user, chat_id, "")
        text = template.render(q["text"], ctx, self.engine.lang)
        if invalid:
            text = self.t("form.invalid_" + q["kind"]) + "\n\n" + text
        params = {"chat_id": chat_id, "text": text, "parse_mode": "HTML"}
        if q["kind"] == "choice":
            rows = [[{"text": o}] for o in q["options"]]
            params["reply_markup"] = {"keyboard": rows, "resize_keyboard": True, "one_time_keyboard": True}
        elif step > 0 and block["questions"][step - 1]["kind"] == "choice":
            params["reply_markup"] = {"remove_keyboard": True}
        sender._call_send("sendMessage", params, block["title"] or self.t("label.form"))

    def answer(self, waiting, message, user, chat):
        """Resposta de uma pergunta do formulário. ``True`` sempre (a mensagem era para ele)."""
        from .flows import _update
        eng = self.engine
        block = self.block(waiting.get("form"))
        uid = user.get("id")
        if block is None:
            _update(eng.base_dir, uid, lambda st: st.pop("waiting", None))
            return False
        step = int(waiting.get("step") or 0)
        q = block["questions"][min(step, len(block["questions"]) - 1)]
        value = valid(q, message.get("text") or message.get("caption") or "")
        if value is None:
            self.ask(block, step, chat["id"], user, invalid=True)
            return True
        answers = list(waiting.get("answers") or []) + [value]

        def save(st):
            if q["var"]:
                st.setdefault("vars", {})[q["var"]] = value
            if len(answers) < len(block["questions"]):
                st["waiting"] = dict(waiting, step=step + 1, answers=answers)
            else:
                st.pop("waiting", None)
        _update(eng.base_dir, uid, save)
        if len(answers) < len(block["questions"]):
            self.ask(block, step + 1, chat["id"], user)
            return True
        self.finish(block, answers, chat, user)
        return True

    def finish(self, block, answers, chat, user):
        eng = self.engine
        record = {"at": int(time.time()), "user_id": user.get("id"),
                  "name": " ".join(x for x in (user.get("first_name"), user.get("last_name")) if x),
                  "username": user.get("username") or "", "answers": answers}
        with _lock:
            data = _load(eng.base_dir)
            items = data.setdefault(block["id"], [])
            items.append(record)
            del items[:-MAX_RESPONSES]
            _save(eng.base_dir, data)
        ctx = eng.flows._context(user, chat["id"], "")
        done = template.render(block["done"], ctx, eng.lang) if block["done"].strip() else self.t("form.done")
        eng._call_send("sendMessage", {"chat_id": chat["id"], "text": done, "parse_mode": "HTML",
                                       "reply_markup": {"remove_keyboard": True}}, block["title"] or self.t("label.form"))
        if block["notify"]:
            lines = [self.t("form.admin_title", template.escape(block["title"] or block["id"]),
                            template.escape(record["name"] or str(record["user_id"])))]
            for q, a in zip(block["questions"], answers):
                lines.append("<b>%s</b>\n%s" % (template.escape(template.html_to_plain(q["text"]))[:200], template.escape(a)))
            text = "\n\n".join(lines)
            eng.maybe_reload_admins()
            for admin_id, admin in sorted(eng.admins.items()):
                if admin.get("level") is not None:
                    eng._call_send("sendMessage", {"chat_id": admin_id, "text": text[:4000], "parse_mode": "HTML"},
                                   self.t("label.form"))
        if eng.storage:
            eng.storage.put_doc("formularios", "%s-%s-%s" % (block["id"], record["user_id"], record["at"]),
                                dict(record, form=block["id"], answers=json.dumps(answers, ensure_ascii=False)))
        eng.emit("form_done", form=block["title"] or block["id"], user=record["name"])


def _load(bot_dir):
    try:
        with open(os.path.join(bot_dir, STATE_FILE), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save(bot_dir, data):
    path = os.path.join(bot_dir, STATE_FILE)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False)
    os.replace(tmp, path)


# -- funções para o app ----------------------------------------------------------------------------

def count(bot_dir, form_id):
    return len(_load(bot_dir).get(form_id) or [])


def list_responses(bot_dir, form_id, limit=200):
    items = _load(bot_dir).get(form_id) or []
    return json.dumps(list(reversed(items))[:limit], ensure_ascii=False)


def export_csv(bot_dir, form_id, questions_json, lang=None):
    """CSV com uma linha por resposta (data, nome, @, ID e uma coluna por pergunta)."""
    try:
        questions = json.loads(questions_json) if questions_json else []
    except ValueError:
        questions = []
    out = io.StringIO()
    w = csv.writer(out)
    w.writerow([i18n.t("form.col_date", lang), i18n.t("form.col_name", lang), "@", "ID"] +
               [template.html_to_plain(str(q))[:100] for q in questions])
    for r in _load(bot_dir).get(form_id) or []:
        when = time.strftime("%Y-%m-%d %H:%M", time.localtime(r["at"]))
        w.writerow([when, r.get("name", ""), r.get("username", ""), r.get("user_id", "")] + list(r.get("answers") or []))
    return out.getvalue()


def clear(bot_dir, form_id):
    with _lock:
        data = _load(bot_dir)
        data.pop(form_id, None)
        _save(bot_dir, data)
    return json.dumps({"ok": True})
