"""Fluxos: etapas ligadas entre si (um fluxograma de conversa).

Cada etapa manda as suas mensagens (com qualquer tipo de botão) e depois:

- ``none``: para;
- ``goto``: segue sozinha para outra etapa (mensagens em sequência, com pausa);
- ``ask``: espera a próxima mensagem da pessoa, valida, guarda numa variável
  (ex.: ``{cidade}``, usável em qualquer texto dali em diante) e segue.

Botões ``node`` ("ir para etapa") montam menus. Se a etapa tem ``edit`` ligado e é
uma mensagem só de texto, abrir por botão **edita** a mensagem do menu em vez de
mandar outra (navegação de menu "no lugar").

Configuração (``config.json`` -> ``flows``)::

    {"nodes": [{"id", "title", "messages": [...], "edit": bool, "delay": 0..30,
                "after": {"type": "none"|"goto"|"ask", "node": id,
                          "variable": "cidade", "validate": "text"|"number"|"email"|"phone",
                          "invalid": "texto se a resposta não servir"}}]}

Estado por pessoa em ``flow_state.json``: etapa esperando resposta e as variáveis.
"""

import json
import os
import re
import threading
import time

from . import template

CALLBACK = "fl:"
CUSTOM_CALLBACK = "cb:"
STATE_FILE = "flow_state.json"
MAX_STEPS = 25            # proteção contra etapas que se chamam em círculo
WAIT_TTL = 24 * 3600      # pergunta sem resposta por 24 h é esquecida
VAR_NAME = re.compile(r"^[a-z_][a-z0-9_]{0,31}$")
AFTER_TYPES = ("none", "goto", "ask")
VALIDATIONS = {
    "text": (re.compile(r"\S"), "Mande a resposta em texto, por favor."),
    "number": (re.compile(r"^\s*-?\d+([.,]\d+)?\s*$"), "Mande só números, por favor."),
    "email": (re.compile(r"^\s*[^@\s]+@[^@\s]+\.[^@\s]+\s*$"), "Esse e-mail não parece válido. Tente de novo."),
    "phone": (re.compile(r"^\s*\+?[\d\s().-]{8,20}\s*$"), "Esse telefone não parece válido. Tente de novo."),
}

_lock = threading.RLock()


def normalize(data, message_normalizer):
    data = data if isinstance(data, dict) else {}
    nodes = []
    seen = set()
    for i, item in enumerate(data.get("nodes") or []):
        if not isinstance(item, dict):
            continue
        nid = str(item.get("id") or "n%d" % i)[:40]
        if nid in seen:
            continue
        seen.add(nid)
        after = item.get("after") if isinstance(item.get("after"), dict) else {}
        kind = after.get("type") if after.get("type") in AFTER_TYPES else "none"
        variable = str(after.get("variable") or "").strip().lower()
        if kind == "ask" and not VAR_NAME.match(variable):
            variable = "resposta"
        try:
            delay = max(0, min(30, int(item.get("delay") or 0)))
        except (TypeError, ValueError):
            delay = 0
        nodes.append({
            "id": nid,
            "title": str(item.get("title") or nid)[:64],
            "messages": [m for m in (message_normalizer(x) for x in item.get("messages") or []) if m],
            "edit": bool(item.get("edit", True)),
            "delay": delay,
            "after": {
                "type": kind,
                "node": str(after.get("node") or "") or None,
                "variable": variable,
                "validate": after.get("validate") if after.get("validate") in VALIDATIONS else "text",
                "invalid": str(after.get("invalid") or "")[:1000],
            },
        })
    return {"nodes": nodes}


# -- estado por pessoa ----------------------------------------------------------------------

def _path(bot_dir):
    return os.path.join(bot_dir, STATE_FILE)


def _load(bot_dir):
    try:
        with open(_path(bot_dir), "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save(bot_dir, data):
    tmp = _path(bot_dir) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False)
    os.replace(tmp, _path(bot_dir))


def user_state(bot_dir, user_id):
    with _lock:
        return _load(bot_dir).get(str(user_id)) or {}


def _update(bot_dir, user_id, change):
    with _lock:
        data = _load(bot_dir)
        st = data.get(str(user_id)) or {}
        change(st)
        data[str(user_id)] = st
        _save(bot_dir, data)


def valid_answer(text, kind):
    rule = VALIDATIONS.get(kind) or VALIDATIONS["text"]
    return bool(text) and bool(rule[0].search(text))


class FlowRunner:
    def __init__(self, engine):
        self.engine = engine
        self.sync = False  # testes: roda na mesma thread

    @property
    def nodes(self):
        return {n["id"]: n for n in (self.engine.config.get("flows") or {}).get("nodes") or []}

    # -- entrada (thread do motor) ----------------------------------------------------------

    def handle_callback(self, query):
        """Botões ``fl:`` (ir para etapa) e ``cb:`` (personalizado). ``True`` se tratou."""
        data = query.get("data") or ""
        if data.startswith(CUSTOM_CALLBACK):
            if self.engine.termux.api is not None:
                return False  # os scripts da API local recebem e respondem
            self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
            self.engine.emit("custom_callback", data=data[len(CUSTOM_CALLBACK):])
            return True
        if not data.startswith(CALLBACK):
            return False
        self.engine._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
        msg = query.get("message") or {}
        chat = msg.get("chat") or {}
        user = query.get("from") or {}
        editable = msg.get("message_id") if msg.get("text") is not None and not msg.get("photo") else None
        self.start(data[len(CALLBACK):], chat.get("id") or user.get("id"), user, edit_message=editable)
        return True

    def handle_message(self, message, parsed):
        """Resposta a uma pergunta de fluxo. Um comando cancela a pergunta (e segue normal)."""
        user = message.get("from") or {}
        chat = message.get("chat") or {}
        uid = user.get("id")
        if uid is None:
            return False
        state = user_state(self.engine.base_dir, uid)
        waiting = state.get("waiting")
        if not waiting or waiting.get("chat_id") != chat.get("id"):
            return False
        if parsed is not None or time.time() - waiting.get("since", 0) > WAIT_TTL:
            _update(self.engine.base_dir, uid, lambda st: st.pop("waiting", None))
            return False
        node = self.nodes.get(waiting.get("node"))
        if node is None or node["after"]["type"] != "ask":
            _update(self.engine.base_dir, uid, lambda st: st.pop("waiting", None))
            return False
        after = node["after"]
        text = (message.get("text") or message.get("caption") or "").strip()
        if not valid_answer(text, after["validate"]):
            self.engine._call_send("sendMessage", {
                "chat_id": chat.get("id"),
                "text": after["invalid"] or VALIDATIONS[after["validate"]][1],
                "reply_parameters": {"message_id": message.get("message_id"), "allow_sending_without_reply": True},
            }, "fluxo")
            return True
        var = after["variable"]

        def save(st):
            st.pop("waiting", None)
            st.setdefault("vars", {})[var] = text[:1000]
            st["vars"]["resposta"] = text[:1000]
        _update(self.engine.base_dir, uid, save)
        if self.engine.storage:
            self.engine.storage.put_doc("respostas", "%s-%s" % (uid, var), {
                "user_id": uid, "name": user.get("first_name") or "", "username": user.get("username") or "",
                "variable": var, "value": text[:1000], "node": node["id"], "at": int(time.time())})
        self.engine.emit("flow_answer", node=node["title"], variable=var, user=user.get("first_name") or "")
        if after["node"]:
            self.start(after["node"], chat.get("id"), user)
        return True

    # -- execução ---------------------------------------------------------------------------

    def start(self, node_id, chat_id, user, args="", reply_to=None, edit_message=None):
        job = (node_id, chat_id, user, args, reply_to, edit_message)
        if self.sync:
            self._run(*job)
        else:
            threading.Thread(target=self._run, args=job, name="flow", daemon=True).start()

    def _sender(self):
        if self.sync:
            return self.engine
        import copy
        from .api import TelegramApi
        sender = copy.copy(self.engine)
        sender.api = TelegramApi(self.engine.token)
        return sender

    def _context(self, user, chat_id, args):
        eng = self.engine
        stamp = eng._now()
        date, _, hour = stamp.partition(" ")
        ctx = template.build_context({"from": user, "chat": {"id": chat_id}}, args,
                                     (eng.bot or {}).get("username", ""), date, hour)
        for k, v in (user_state(eng.base_dir, user.get("id")).get("vars") or {}).items():
            if VAR_NAME.match(k) and k not in ctx:
                ctx[k] = v
        return ctx

    def _run(self, node_id, chat_id, user, args, reply_to, edit_message):
        eng = self.engine
        sender = self._sender()
        steps = 0
        while node_id and not eng.stopping:
            node = self.nodes.get(node_id)
            if node is None:
                eng.emit("error", context="flow", command=None, retry_in=None, fatal=False,
                         code="flow_missing", detail=node_id, retry_after=None)
                return
            steps += 1
            if steps > MAX_STEPS:
                eng.emit("error", context="flow", command=None, retry_in=None, fatal=False,
                         code="flow_loop", detail=node["title"], retry_after=None)
                return
            if node["delay"]:
                try:
                    sender.api.call("sendChatAction", {"chat_id": chat_id, "action": "typing"}, timeout=10)
                except Exception:  # noqa: BLE001 - só o "digitando…"
                    pass
                eng._stop.wait(node["delay"])
            context = self._context(user, chat_id, args)
            self.send_node(sender, node, chat_id, context, reply_to, edit_message)
            eng.emit("flow_step", node=node["title"])
            reply_to = edit_message = None
            after = node["after"]
            if after["type"] == "ask":
                waiting = {"node": node["id"], "chat_id": chat_id, "since": int(time.time())}
                _update(eng.base_dir, user.get("id"), lambda st: st.__setitem__("waiting", waiting))
                return
            node_id = after["node"] if after["type"] == "goto" else None

    def send_node(self, sender, node, chat_id, context, reply_to=None, edit_message=None):
        msgs = node["messages"]
        if edit_message and node["edit"] and len(msgs) == 1 and not msgs[0].get("media"):
            from .engine import build_keyboard
            params = {"chat_id": chat_id, "message_id": edit_message, "parse_mode": "HTML",
                      "text": template.render(msgs[0]["text"], context),
                      "link_preview_options": {"is_disabled": not msgs[0].get("link_preview")}}
            kb = build_keyboard(msgs[0]["buttons"], private=int(chat_id) > 0)
            if kb:
                params["reply_markup"] = kb
            try:
                sender.api.call("editMessageText", params, timeout=30)
                return 1
            except Exception as exc:  # noqa: BLE001
                if "not modified" in str(exc):
                    return 1
                # mensagem antiga demais, com mídia etc.: manda uma nova
        sent = 0
        for msg in msgs:
            if sender.send_message(chat_id, msg, context, "fluxo", reply_to):
                sent += 1
                reply_to = None
        return sent
