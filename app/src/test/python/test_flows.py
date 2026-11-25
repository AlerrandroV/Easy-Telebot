"""Fluxos (etapas, menus que se editam, perguntas com variável) e os novos tipos de botão."""

import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import chats, engine, flows  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402


def node(nid, text, buttons=None, after=None, edit=True, title=None):
    return {"id": nid, "title": title or nid, "edit": edit,
            "messages": [{"text": text, "buttons": buttons or []}], "after": after or {"type": "none"}}


def press(data, update_id, message_id=50, text="menu"):
    return {"update_id": update_id, "callback_query": {
        "id": "q%d" % update_id, "from": {"id": 42, "first_name": "Ana"}, "data": data,
        "message": {"message_id": message_id, "text": text, "chat": {"id": 42, "type": "private"}}}}


def reply(text, update_id):
    u = msg(text, update_id=update_id)
    u["message"]["message_id"] = 100 + update_id
    return u


class ButtonTests(unittest.TestCase):
    def test_button_types(self):
        b = engine.build_button
        self.assertEqual(b({"text": "Site", "type": "url", "value": "exemplo.com"}),
                         {"text": "Site", "url": "https://exemplo.com"})
        self.assertEqual(b({"text": "Site", "url": "@canal"}), {"text": "Site", "url": "https://t.me/canal"})
        self.assertEqual(b({"text": "Pix", "type": "copy", "value": "chave@pix.com"}),
                         {"text": "Pix", "copy_text": {"text": "chave@pix.com"}})
        self.assertEqual(b({"text": "Menu", "type": "node", "value": "menu"}), {"text": "Menu", "callback_data": "fl:menu"})
        self.assertEqual(b({"text": "X", "type": "callback", "value": "pedido:1"}), {"text": "X", "callback_data": "cb:pedido:1"})
        self.assertEqual(b({"text": "Loja", "type": "webapp", "value": "https://loja.com"}),
                         {"text": "Loja", "web_app": {"url": "https://loja.com"}})
        self.assertEqual(b({"text": "Loja", "type": "webapp", "value": "https://loja.com"}, private=False),
                         {"text": "Loja", "url": "https://loja.com"})  # em grupo: link comum
        self.assertIsNone(b({"text": "Ruim", "type": "url", "value": "não é link"}))
        long = b({"text": "X", "type": "callback", "value": "á" * 80})
        self.assertLessEqual(len(long["callback_data"].encode("utf-8")), 64)


class FlowTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def make(self, script, nodes, commands=None):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": commands or [{"triggers": ["start"], "messages": [], "flow": "menu"},
                                                              {"triggers": ["ajuda"], "messages": [{"text": "ajuda"}]}],
                       "flows": {"nodes": nodes}}, fh)
        cb = Callbacks()
        api = FakeApi(script)
        eng = engine.Engine("1:x", path, self.dir, cb, api=api)
        eng.flows.sync = True
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api, cb

    def test_menu_navigation_question_and_variables(self):
        nodes = [
            node("menu", "Olá, {nome}! Escolha:", [[{"text": "Planos", "type": "node", "value": "planos"}],
                                                    [{"text": "Falar com a gente", "type": "node", "value": "cidade"}]]),
            node("planos", "Nossos planos...", [[{"text": "Voltar", "type": "node", "value": "menu"}]]),
            node("cidade", "De qual cidade você fala?", after={"type": "ask", "variable": "cidade", "node": "fim"},
                 edit=False),
            node("fim", "Obrigado, {nome}! Anotamos: {cidade}."),
        ]
        eng, api, cb = self.make([[msg("/start")], [press("fl:planos", 2)], [press("fl:cidade", 3)],
                                  [reply("Recife", 4)]], nodes)
        eng.run()
        texts = [p.get("text") for p in api.sent]
        self.assertEqual(texts[0], "Olá, Ana! Escolha:")
        self.assertEqual(api.sent[0]["reply_markup"]["inline_keyboard"][0][0]["callback_data"], "fl:planos")
        edits = [p for m, p, f in api.calls if m == "editMessageText"]
        self.assertEqual((edits[0]["message_id"], edits[0]["text"]), (50, "Nossos planos..."))  # menu editado no lugar
        self.assertEqual(texts[1:], ["De qual cidade você fala?", "Obrigado, Ana! Anotamos: Recife."])
        self.assertEqual(flows.user_state(self.dir, 42)["vars"]["cidade"], "Recife")
        self.assertEqual(cb.of("flow_answer")[0]["variable"], "cidade")

    def test_validation_and_command_cancels_question(self):
        nodes = [node("menu", "Quantos anos?", after={"type": "ask", "variable": "idade", "validate": "number",
                                                      "invalid": "Só o número, tipo 30.", "node": "ok"}),
                 node("ok", "Idade: {idade}")]
        eng, api, cb = self.make([[msg("/start")], [reply("trinta", 2)], [reply("/ajuda", 3)], [reply("30", 4)]], nodes)
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], ["Quantos anos?", "Só o número, tipo 30.", "ajuda"])
        self.assertNotIn("waiting", flows.user_state(self.dir, 42))  # o comando cancelou a pergunta

    def test_consecutive_messages_and_loop_guard(self):
        nodes = [node("menu", "1", after={"type": "goto", "node": "b"}), node("b", "2", after={"type": "goto", "node": "c"}),
                 node("c", "3")]
        eng, api, cb = self.make([[msg("/start")]], nodes)
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], ["1", "2", "3"])

        nodes = [node("menu", "de novo", after={"type": "goto", "node": "menu"})]
        eng, api, cb = self.make([[msg("/start")]], nodes)
        eng.run()
        self.assertEqual(len(api.sent), flows.MAX_STEPS)
        self.assertEqual(cb.of("error")[-1]["code"], "flow_loop")

    def test_custom_callback_without_scripts(self):
        eng, api, cb = self.make([[press("cb:pedido:9", 1)]], [node("menu", "x")])
        eng.run()
        self.assertIn("answerCallbackQuery", [m for m, p, f in api.calls])
        self.assertEqual(cb.of("custom_callback")[0]["data"], "pedido:9")


if __name__ == "__main__":
    unittest.main()


class BlockTests(unittest.TestCase):
    def test_blocks_run_in_order_and_legacy_is_converted(self):
        from telebot_engine import config as config_mod
        from test_termux import ScriptApi, TermuxCallbacks
        d = tempfile.mkdtemp()
        path = os.path.join(d, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": [
                {"triggers": ["tempo"], "blocks": [
                    {"type": "message", "text": "Consultando, {nome}...",
                     "buttons": [[{"text": "Canal", "type": "channel", "value": "@meucanal"}]]},
                    {"type": "termux", "command": "python ~/tempo.py", "timeout": 10},
                    {"type": "message", "text": "Fim."}]},
                {"triggers": ["velho"], "messages": [{"text": "a"}, {"text": "b"}]},
                {"triggers": ["vazio"], "blocks": []},
            ]}, fh)
        cmds = {c["triggers"][0]: c for c in config_mod.load(path)["commands"]}
        self.assertEqual([b["type"] for b in cmds["velho"]["blocks"]], ["message", "message"])
        self.assertEqual(cmds["tempo"]["script"]["command"], "python ~/tempo.py")
        cb = TermuxCallbacks(["28 °C"])
        api = ScriptApi([[msg("/tempo")], [msg("/vazio", update_id=2)]])
        eng = engine.Engine("1:x", path, d, cb, api=api)
        eng.termux.sync_scripts = True
        eng.termux._sender = lambda: eng
        api.engine = eng
        eng.sleep = lambda s: None
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], ["Consultando, Ana...", "28 °C", "Fim."])
        self.assertEqual(api.sent[0]["reply_markup"]["inline_keyboard"][0][0]["url"], "https://t.me/meucanal")
        self.assertEqual(cb.of("replied")[0]["messages"], 3)
        chats.close(d)
