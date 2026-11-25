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

from . import admins as admins_mod
from . import chats, i18n, template

CALLBACK = "fl:"
CUSTOM_CALLBACK = "cb:"
STATE_FILE = "flow_state.json"
MAX_STEPS = 25            # proteção contra etapas que se chamam em círculo
WAIT_TTL = 24 * 3600      # pergunta sem resposta por 24 h é esquecida
VAR_NAME = re.compile(r"^[a-z_][a-z0-9_]{0,31}$")
AFTER_TYPES = ("none", "goto", "ask")
VALIDATIONS = {
    # (padrão, chave do texto em i18n pedindo de novo)
    "text": (re.compile(r"\S"), "flow.invalid_text"),
    "number": (re.compile(r"^\s*-?\d+([.,]\d+)?\s*$"), "flow.invalid_number"),
    "email": (re.compile(r"^\s*[^@\s]+@[^@\s]+\.[^@\s]+\s*$"), "flow.invalid_email"),
    "phone": (re.compile(r"^\s*\+?[\d\s().-]{8,20}\s*$"), "flow.invalid_phone"),
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


MESSAGE_KINDS = {"text", "photo", "video", "animation", "video_note", "sticker", "voice", "audio", "document",
                 "location", "contact", "poll"}


def matches(cond, message):
    """Condição de uma regra do Aguardar resposta. Ex.: tipo é foto; texto contém "sim"."""
    field, op, value = cond.get("field"), cond.get("op"), (cond.get("value") or "")
    if field == "any":
        return True
    if field == "type":
        kind = chats.parse_message(message)["kind"]
        same = kind == value or (value == "video" and kind in ("video", "video_note"))
        return not same if op == "is_not" else same
    text = (message.get("text") or message.get("caption") or "").strip()
    low, val = text.casefold(), value.strip().casefold()
    if op == "equals":
        return low == val
    if op == "contains":
        return bool(val) and val in low
    if op == "starts":
        return bool(val) and low.startswith(val)
    if op == "ends":
        return bool(val) and low.endswith(val)
    if op in ("number", "email", "phone"):
        return valid_answer(text, op)
    return False


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
        reaction = (self.engine.config.get("callbacks") or {}).get(data[len(CUSTOM_CALLBACK):]) \
            if data.startswith(CUSTOM_CALLBACK) else None
        if reaction is not None:
            return self._react(query, reaction)
        if data.startswith(CUSTOM_CALLBACK):
            if self._button_to_listener(query):
                return True
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

    def _react(self, query, reaction):
        """Botão callback com resposta configurada no app (uma "pasta" do comando)."""
        eng = self.engine
        eng._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
        user = query.get("from") or {}
        msg = query.get("message") or {}
        level = reaction.get("admin_level")
        if level is not None:
            eng.maybe_reload_admins()
            admin = eng.admins.get(user.get("id"))
            if not admins_mod.can_use(admin and admin.get("level"), level):
                return True  # botão de comando de admin: quem não pode, não usa
        chat_id = (msg.get("chat") or {}).get("id") or user.get("id")
        editable = msg.get("message_id") if reaction.get("edit") and msg.get("text") is not None else None
        eng.emit("callback_reaction", button=reaction.get("text") or "")
        eng.start_blocks(reaction["blocks"], reaction.get("text") or i18n.t("label.button"), chat_id, user,
                         update={"callback_query": query}, edit_message=editable)
        return True

    # -- Aguardar resposta (bloco com regras) -----------------------------------------------

    def _button_to_listener(self, query):
        """Botão sem resposta própria apertado durante um "Aguardar resposta" com código:
        o código recebe o clique (``mensagem.tipo == botao``, ``mensagem.dado``)."""
        eng = self.engine
        user = query.get("from") or {}
        chat = (query.get("message") or {}).get("chat") or {}
        waiting = user_state(eng.base_dir, user.get("id")).get("waiting") or {}
        block = (eng.config.get("waits") or {}).get(waiting.get("wait"))
        if not block or not block.get("code") or waiting.get("chat_id") != chat.get("id"):
            return False
        eng._try_call("answerCallbackQuery", {"callback_query_id": query.get("id")})
        eng.start_blocks([{"type": "code", "code": block["code"], "listener": True}],
                         waiting.get("name") or i18n.t("label.answer"), chat.get("id"), user,
                         update={"callback_query": query})
        return True

    def end_wait(self, user):
        _update(self.engine.base_dir, (user or {}).get("id"), lambda st: st.pop("waiting", None))

    def wait_for(self, block, chat_id, user, name):
        waiting = {"wait": block.get("id"), "chat_id": chat_id, "since": int(time.time()), "name": name}
        _update(self.engine.base_dir, user.get("id"), lambda st: st.__setitem__("waiting", waiting))

    def _handle_wait(self, waiting, message, user, chat):
        eng = self.engine
        uid = user.get("id")
        block = (eng.config.get("waits") or {}).get(waiting.get("wait"))
        if block is None:  # a configuração mudou: esquece a espera
            _update(eng.base_dir, uid, lambda st: st.pop("waiting", None))
            return False
        if block.get("code"):
            # Python facilitado: o código decide; a espera continua até ele chamar parar().
            text = (message.get("text") or message.get("caption") or "").strip()
            if block["variable"]:
                value = (text or chats.parse_message(message).get("file_id") or "")[:1000]
                _update(eng.base_dir, uid, lambda st: st.setdefault("vars", {}).update(
                    {block["variable"]: value, "resposta": value}))
            eng.emit("wait_answer", rule=None, name=waiting.get("name") or "", user=user.get("first_name") or "")
            eng.start_blocks([{"type": "code", "code": block["code"], "listener": True}],
                             waiting.get("name") or i18n.t("label.answer"), chat.get("id"), user, text,
                             update={"message": message})
            return True
        index = next((i for i, r in enumerate(block["rules"]) if matches(r["condition"], message)), None)
        if index is not None:
            rule = block["rules"][index]
            blocks, keep = rule["blocks"], rule["keep"]
        else:
            blocks, keep = block["otherwise"], block["otherwise_keep"]
        text = (message.get("text") or message.get("caption") or "").strip()
        value = text or chats.parse_message(message).get("file_id") or ""

        def save(st):
            if not keep:
                st.pop("waiting", None)
            if block["variable"] and (index is not None or not block["rules"]):
                st.setdefault("vars", {})[block["variable"]] = value[:1000]
                st["vars"]["resposta"] = value[:1000]
        _update(eng.base_dir, uid, save)
        eng.emit("wait_answer", rule=index, name=waiting.get("name") or "", user=user.get("first_name") or "")
        eng.start_blocks(blocks, waiting.get("name") or i18n.t("label.answer"), chat.get("id"), user, text,
                         update={"message": message})
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
        if waiting.get("wait"):
            return self._handle_wait(waiting, message, user, chat)
        node = self.nodes.get(waiting.get("node"))
        if node is None or node["after"]["type"] != "ask":
            _update(self.engine.base_dir, uid, lambda st: st.pop("waiting", None))
            return False
        after = node["after"]
        text = (message.get("text") or message.get("caption") or "").strip()
        if not valid_answer(text, after["validate"]):
            self.engine._call_send("sendMessage", {
                "chat_id": chat.get("id"),
                "text": after["invalid"] or i18n.t(VALIDATIONS[after["validate"]][1], self.engine.lang),
                "reply_parameters": {"message_id": message.get("message_id"), "allow_sending_without_reply": True},
            }, i18n.t("label.flow"))
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

    def _context(self, user, chat_id, args, message=None):
        eng = self.engine
        stamp = eng._now()
        date, _, hour = stamp.partition(" ")
        ctx = template.build_context(message or {"from": user, "chat": {"id": chat_id}}, args,
                                     (eng.bot or {}).get("username", ""), date, hour)
        for k, v in (user_state(eng.base_dir, user.get("id")).get("vars") or {}).items():
            if VAR_NAME.match(k) and k not in ctx:
                ctx[k] = v
        if "resposta" in ctx:
            ctx.setdefault("answer", ctx["resposta"])  # mesmo valor, nome em inglês
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

    @staticmethod
    def edit_text(sender, chat_id, message_id, msg, context):
        """Troca o texto e os botões de uma mensagem (menu). ``False`` se não deu (mídia,
        mensagem antiga...): aí quem chamou manda uma mensagem nova."""
        if msg.get("media") or not msg.get("text", "").strip():
            return False
        from .engine import build_keyboard
        params = {"chat_id": chat_id, "message_id": message_id, "parse_mode": "HTML",
                  "text": template.render(msg["text"], context),
                  "link_preview_options": {"is_disabled": not msg.get("link_preview")}}
        kb = build_keyboard(msg["buttons"], private=int(chat_id) > 0)
        if kb:
            params["reply_markup"] = kb
        try:
            sender.api.call("editMessageText", params, timeout=30)
            return True
        except Exception as exc:  # noqa: BLE001
            return "not modified" in str(exc)

    def send_node(self, sender, node, chat_id, context, reply_to=None, edit_message=None):
        msgs = node["messages"]
        if edit_message and node["edit"] and len(msgs) == 1 and self.edit_text(sender, chat_id, edit_message,
                                                                                msgs[0], context):
            return 1
        sent = 0
        for msg in msgs:
            if sender.send_message(chat_id, msg, context, i18n.t("label.flow"), reply_to):
                sent += 1
                reply_to = None
        return sent
