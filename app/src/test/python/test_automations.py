"""Gatilhos além dos comandos: palavra-chave, tipo de mensagem, eventos, link de convite, inline."""

import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import automations, chats, config, engine  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402


class Api(FakeApi):
    OTHER = ("answerInlineQuery", "approveChatJoinRequest", "declineChatJoinRequest")

    def call(self, method, params=None, timeout=30, files=None):
        if method in self.OTHER:
            self.calls.append((method, params, files))
            return True
        return super().call(method, params, timeout, files)


def text(t):
    return [{"type": "message", "text": t}]


def auto(kind, trigger, blocks, **extra):
    return dict({"kind": kind, "trigger": trigger, "blocks": blocks}, **extra)


class SpecTests(unittest.TestCase):
    def test_normalize(self):
        self.assertIsNone(automations.normalize_spec("keyword", {"words": []}))
        self.assertIsNone(automations.normalize_spec("keyword", {"words": ["(abc"], "match": "regex"}))
        self.assertIsNone(automations.normalize_spec("event", {"event": "nada"}))
        self.assertEqual(automations.normalize_spec("start", {"words": ["promo", "não vale"]})["words"], ["promo"])
        self.assertEqual(automations.normalize_spec("media", {"kinds": ["photo", "x"]})["kinds"], ["photo"])

    def test_keyword_matching(self):
        def m(match, words, t, case=False):
            return automations.keyword_matches({"match": match, "words": words, "case": case}, t)
        self.assertEqual(m("contains", ["preço"], "Qual o PRECO?"), "preço")
        self.assertIsNone(m("word", ["oi"], "boituva"))
        self.assertEqual(m("word", ["oi"], "Oi, tudo bem?"), "oi")
        self.assertEqual(m("starts", ["bom dia"], "  Bom dia gente"), "bom dia")
        self.assertIsNone(m("equals", ["menu"], "menu completo"))
        self.assertEqual(m("regex", [r"\d{5}-?\d{3}"], "meu cep 01310-100"), r"\d{5}-?\d{3}")
        self.assertIsNone(m("contains", ["Oi"], "oi", case=True))

    def test_config_split(self):
        cfg = config.normalize({"commands": [
            {"triggers": ["start"], "blocks": text("oi")},
            auto("keyword", {"words": ["preço"]}, text("R$ 10")),
            auto("keyword", {"words": []}, text("incompleto")),
            auto("event", {"event": "join"}, text("bem-vindo"), enabled=False),
        ]})
        self.assertEqual([c["triggers"] for c in cfg["commands"]], [["start"]])
        self.assertEqual([a["kind"] for a in cfg["automations"]], ["keyword"])


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def run_bot(self, script, commands):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": commands}, fh)
        api = Api(script)
        eng = engine.Engine("1:x", path, self.dir, Callbacks(), api=api)
        eng.flows.sync = True
        api.engine = eng
        eng.sleep = lambda s: None
        eng.run()
        return api, eng

    def test_keyword_in_private_and_group(self):
        cmds = [auto("keyword", {"words": ["preço", "valor"], "match": "contains"}, text("Custa {args}!")),
                auto("keyword", {"words": ["oi"], "match": "word", "where": "private"}, text("Olá, {name}"))]
        group = msg("Qual o valor?", update_id=2, chat_type="supergroup", title="G")
        api, _ = self.run_bot([[msg("oi bot")], [group], [msg("oi", update_id=3, chat_type="group")]], cmds)
        self.assertEqual([p["text"] for p in api.sent], ["Olá, Ana", "Custa Qual o valor?!"])
        self.assertEqual(api.sent[1]["reply_parameters"]["message_id"], 7)  # em grupo responde à mensagem

    def test_keyword_admin_only_is_silent_for_others(self):
        cmds = [auto("keyword", {"words": ["relatorio"]}, text("segredo"), admin_level=1)]
        api, _ = self.run_bot([[msg("relatorio")]], cmds)
        self.assertEqual(api.sent, [])

    def test_media_trigger(self):
        photo = msg("", update_id=1)
        del photo["message"]["text"]
        photo["message"]["photo"] = [{"file_id": "F", "file_unique_id": "u", "width": 1, "height": 1}]
        loc = msg("", update_id=2)
        del loc["message"]["text"]
        loc["message"]["location"] = {"latitude": 1, "longitude": 2}
        cmds = [auto("media", {"kinds": ["photo"]}, text("Bela foto!")),
                auto("media", {"kinds": ["location", "contact"]}, text("Recebi seu endereço"))]
        api, _ = self.run_bot([[photo], [loc]], cmds)
        self.assertEqual([p["text"] for p in api.sent], ["Bela foto!", "Recebi seu endereço"])

    def test_join_and_leave_events(self):
        join = msg("", chat_type="supergroup", title="Grupo")
        del join["message"]["text"]
        join["message"]["new_chat_members"] = [{"id": 1, "is_bot": True, "first_name": "Bot"},
                                               {"id": 77, "first_name": "Bia"}]
        leave = msg("", update_id=2, chat_type="supergroup", title="Grupo")
        del leave["message"]["text"]
        leave["message"]["left_chat_member"] = {"id": 77, "first_name": "Bia"}
        cmds = [auto("event", {"event": "join"}, text("Bem-vinda, {nome}!")),
                auto("event", {"event": "leave"}, text("Tchau, {nome}"))]
        api, _ = self.run_bot([[join], [leave]], cmds)
        self.assertEqual([p["text"] for p in api.sent], ["Bem-vinda, Bia!", "Tchau, Bia"])

    def test_join_request_approves_and_greets_in_private(self):
        req = {"update_id": 1, "chat_join_request": {"chat": {"id": -100, "type": "supergroup", "title": "VIP"},
                                                     "from": {"id": 55, "first_name": "Rui"}, "user_chat_id": 55,
                                                     "date": 1}}
        cmds = [auto("event", {"event": "join_request", "approve": "approve"}, text("Aprovado em {args}, {nome}"))]
        api, _ = self.run_bot([[req]], cmds)
        self.assertIn(("approveChatJoinRequest", {"chat_id": -100, "user_id": 55}, None), api.calls)
        self.assertEqual(api.sent[0]["chat_id"], 55)
        self.assertEqual(api.sent[0]["text"], "Aprovado em VIP, Rui")

    def test_reaction_with_emoji_filter(self):
        def react(uid, emoji, old=()):
            return {"update_id": uid, "message_reaction": {
                "chat": {"id": -100, "type": "supergroup"}, "message_id": 9, "user": {"id": 5, "first_name": "Lu"},
                "date": 1, "old_reaction": [{"type": "emoji", "emoji": e} for e in old],
                "new_reaction": [{"type": "emoji", "emoji": emoji}]}}
        cmds = [auto("event", {"event": "reaction", "emojis": ["🔥"]}, text("{nome} reagiu {args}"))]
        api, _ = self.run_bot([[react(1, "👍")], [react(2, "🔥")], [react(3, "🔥", old=["🔥"])]], cmds)
        self.assertEqual([p["text"] for p in api.sent], ["Lu reagiu 🔥"])

    def test_bot_added_to_group(self):
        change = {"update_id": 1, "my_chat_member": {
            "chat": {"id": -100, "type": "supergroup", "title": "G"}, "from": {"id": 42, "first_name": "Ana"},
            "date": 1, "old_chat_member": {"status": "left", "user": {"id": 1}},
            "new_chat_member": {"status": "member", "user": {"id": 1}}}}
        cmds = [auto("event", {"event": "bot_added"}, text("Olá, grupo!"))]
        api, _ = self.run_bot([[change]], cmds)
        self.assertEqual(api.sent[0]["chat_id"], -100)

    def test_start_parameter(self):
        cmds = [{"triggers": ["start"], "blocks": text("Início normal")},
                auto("start", {"words": ["ref_"], "prefix": True}, text("Indicado por {args}"))]
        api, _ = self.run_bot([[msg("/start ref_123")], [msg("/start", update_id=2)]], cmds)
        self.assertEqual([p["text"] for p in api.sent], ["Indicado por ref_123", "Início normal"])

    def test_inline_results(self):
        q = {"update_id": 1, "inline_query": {"id": "Q", "from": {"id": 5, "first_name": "Lu"}, "query": "preco",
                                              "offset": ""}}
        cmds = [auto("inline", {"words": ["preço"], "title": "Tabela"}, text("Tabela para {nome}"), id="a1"),
                auto("inline", {"words": ["horario"]}, text("8h às 18h"), id="a2"),
                auto("inline", {}, text("Sempre aparece"), id="a3", description="Geral")]
        api, _ = self.run_bot([[q]], cmds)
        call = next(c for c in api.calls if c[0] == "answerInlineQuery")[1]
        self.assertEqual([r["id"] for r in call["results"]], ["a1", "a3"])
        self.assertEqual(call["results"][0]["input_message_content"]["message_text"], "Tabela para Lu")
        self.assertEqual(call["results"][1]["title"], "Geral")

    def test_reply_keyboard(self):
        cmds = [{"triggers": ["menu"], "blocks": [{"type": "message", "text": "Escolha", "reply_keyboard": {
            "rows": [["Preços", "Horário"], [{"text": "Enviar contato", "request": "contact"}]]}}]},
                {"triggers": ["fim"], "blocks": [{"type": "message", "text": "ok", "reply_keyboard": {"remove": True}}]},
                auto("keyword", {"words": ["Preços"], "match": "equals"}, text("Tabela"))]
        api, _ = self.run_bot([[msg("/menu")], [msg("preços", update_id=2)], [msg("/fim", update_id=3)]], cmds)
        kb = api.sent[0]["reply_markup"]
        self.assertEqual(kb["keyboard"][1][0], {"text": "Enviar contato", "request_contact": True})
        self.assertEqual(api.sent[1]["text"], "Tabela")
        self.assertEqual(api.sent[2]["reply_markup"], {"remove_keyboard": True})


if __name__ == "__main__":
    unittest.main()
