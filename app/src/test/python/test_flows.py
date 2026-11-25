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


class CallbackTreeAndWaitTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def make(self, script, commands):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": commands}, fh)
        cb = Callbacks()
        api = FakeApi(script)
        eng = engine.Engine("1:x", path, self.dir, cb, api=api)
        eng.flows.sync = True
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api, cb

    def photo(self, update_id):
        u = msg("", update_id=update_id)
        u["message"]["message_id"] = 200 + update_id
        del u["message"]["text"]
        u["message"]["photo"] = [{"file_id": "FOTO1", "file_unique_id": "u", "width": 90, "height": 90}]
        return u

    def test_wait_rules_like_the_profile_photo_example(self):
        cmd = {"triggers": ["foto"], "blocks": [
            {"type": "message", "text": "Me envie a sua foto de perfil."},
            {"type": "wait", "variable": "foto", "rules": [
                {"condition": {"field": "type", "op": "is", "value": "photo"},
                 "blocks": [{"type": "message", "text": "Foto de perfil atualizada com sucesso"}]},
                {"condition": {"field": "type", "op": "is", "value": "text"}, "keep": True,
                 "blocks": [{"type": "message", "text": "Erro! Por favor me envie uma foto valida!"}]},
            ]},
        ]}
        eng, api, cb = self.make([[msg("/foto")], [reply("oi", 2)], [self.photo(3)], [self.photo(4)]], [cmd])
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], [
            "Me envie a sua foto de perfil.", "Erro! Por favor me envie uma foto valida!",
            "Foto de perfil atualizada com sucesso"])  # a 2ª foto não é respondida: a espera acabou
        state = flows.user_state(self.dir, 42)
        self.assertNotIn("waiting", state)
        self.assertEqual(state["vars"]["foto"], "FOTO1")

    def test_otherwise_keeps_waiting_and_text_conditions(self):
        cmd = {"triggers": ["cupom"], "blocks": [
            {"type": "message", "text": "Digite o cupom"},
            {"type": "wait", "variable": "cupom", "rules": [
                {"condition": {"field": "text", "op": "equals", "value": "PROMO10"},
                 "blocks": [{"type": "message", "text": "Cupom {cupom} aplicado!"}]}],
             "otherwise": [{"type": "message", "text": "Cupom inválido, tente de novo."}], "otherwise_keep": True},
        ]}
        eng, api, cb = self.make([[msg("/cupom")], [reply("abc", 2)], [reply("promo10", 3)]], [cmd])
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], ["Digite o cupom", "Cupom inválido, tente de novo.",
                                                         "Cupom promo10 aplicado!"])

    def test_callback_folders_edit_the_menu_and_respect_admin_level(self):
        premium = {"type": "message", "text": "Premium: R$ 30 por mês."}
        planos = {"type": "message", "text": "Nossos planos:",
                  "buttons": [[{"text": "Premium", "type": "callback", "value": "premium",
                                "reaction": {"edit": True, "blocks": [premium]}}]]}
        menu = {"type": "message", "text": "Menu, {nome}",
                "buttons": [[{"text": "Planos", "type": "callback", "value": "planos",
                              "reaction": {"edit": True, "blocks": [planos]}},
                             {"text": "Sem resposta", "type": "callback", "value": "solto"}]]}
        painel = {"type": "message", "text": "painel",
                  "buttons": [[{"text": "Zerar", "type": "callback", "value": "zerar",
                                "reaction": {"blocks": [{"type": "message", "text": "zerado"}]}}]]}
        eng, api, cb = self.make([[msg("/menu")], [press("cb:planos", 2)], [press("cb:premium", 3)],
                                  [press("cb:solto", 4)], [press("cb:zerar", 5)]],
                                 [{"triggers": ["menu"], "blocks": [menu]},
                                  {"triggers": ["painel"], "admin_level": 1, "blocks": [painel]}])
        eng.run()
        first = api.sent[0]
        self.assertEqual(first["text"], "Menu, Ana")
        self.assertEqual([b["callback_data"] for b in first["reply_markup"]["inline_keyboard"][0]], ["cb:planos", "cb:solto"])
        edits = [p for m, p, f in api.calls if m == "editMessageText"]
        self.assertEqual([e["text"] for e in edits], ["Nossos planos:", "Premium: R$ 30 por mês."])
        self.assertEqual(edits[0]["reply_markup"]["inline_keyboard"][0][0]["callback_data"], "cb:premium")
        self.assertEqual(cb.of("custom_callback")[0]["data"], "solto")  # sem resposta: vai para os scripts
        self.assertNotIn("zerado", [p.get("text") for p in api.sent])  # botão de comando de admin
        self.assertEqual(len(api.sent), 1)


class TermuxCallbacks:
    def __init__(self, base):
        self.base = base
        self.commands = []

    def __getattr__(self, name):
        return getattr(self.base, name)

    def runTermux(self, command, env, timeout):  # noqa: N802
        self.commands.append((command, json.loads(env), timeout))
        return json.dumps({"ok": True, "stdout": "ok\n", "stderr": "", "exit_code": 0})


class PythonCodeTests(unittest.TestCase):
    """Bloco "Python facilitado" nos comandos e no "Aguardar resposta"."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        chats.close(self.dir)

    def make(self, script, commands):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": commands}, fh)
        cb = TermuxCallbacks(Callbacks())
        api = FakeApi(script)
        eng = engine.Engine("1:x", path, self.dir, cb, api=api)
        eng.flows.sync = True
        eng.termux.sync_scripts = True
        api.engine = eng
        eng.sleep = lambda s: None
        return eng, api, cb

    def test_listener_runs_until_stop(self):
        code = ("if mensagem.tipo == foto:\n"
                "    r = termux('node atualizar.js', mensagem.foto.id, tempo=60)\n"
                "    enviar_texto('Foto atualizada! ' + r.saida)\n"
                "    parar()\n"
                "else:\n"
                "    enviar_texto('Envie uma foto, {}'.replace('{}', usuario.nome))\n")
        cmd = {"triggers": ["foto"], "blocks": [
            {"type": "message", "text": "Me envie a foto"},
            {"type": "wait", "variable": "foto", "code": code},
        ]}
        photo = CallbackTreeAndWaitTests.photo
        eng, api, cb = self.make([[msg("/foto")], [reply("oi", 2)], [photo(None, 3)], [photo(None, 4)]], [cmd])
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], ["Me envie a foto", "Envie uma foto, Ana", "Foto atualizada! ok"])
        command, env, timeout = cb.commands[0]
        self.assertEqual(command, "node atualizar.js FOTO1")
        self.assertEqual(timeout, 60)
        self.assertEqual(env["TELEBOT_CHAT_ID"], "42")
        state = flows.user_state(self.dir, 42)
        self.assertNotIn("waiting", state)
        self.assertEqual(state["vars"]["foto"], "FOTO1")

    def test_code_block_variables_and_stop(self):
        cmds = [
            {"triggers": ["conta"], "blocks": [
                {"type": "code", "code": "variaveis['pontos'] = variaveis.get('pontos', 0) + 5\n"
                                         "enviar_texto(f'*{variaveis[\"pontos\"]}* pontos')\nparar()\n"},
                {"type": "message", "text": "nunca chega aqui"}]},
            {"triggers": ["saldo"], "blocks": [{"type": "message", "text": "Você tem {pontos}"}]},
        ]
        eng, api, cb = self.make([[msg("/conta")], [msg("/conta", update_id=2)], [msg("/saldo", update_id=3)]], cmds)
        eng.run()
        self.assertEqual([p["text"] for p in api.sent], ["<b>5</b> pontos", "<b>10</b> pontos", "Você tem 10"])

    def test_code_error_is_reported(self):
        cmd = {"triggers": ["x"], "blocks": [{"type": "code", "code": "enviar_texto(1/0)\n"}]}
        eng, api, cb = self.make([[msg("/x")]], [cmd])
        eng.run()
        err = cb.of("code_error")[0]
        self.assertEqual((err["line"], err["code"]), (1, "zero"))

    def test_button_click_goes_to_listener(self):
        code = ("if mensagem.tipo == botao:\n"
                "    enviar_texto('Escolheu ' + mensagem.dado)\n"
                "    parar()\n")
        cmd = {"triggers": ["menu"], "blocks": [
            {"type": "code", "code": "enviar_texto('Escolha', botoes=[[botao('Sim'), botao('Não')]])\n"},
            {"type": "wait", "code": code},
        ]}
        eng, api, cb = self.make([[msg("/menu")], [press("cb:nao", 2)]], [cmd])
        eng.run()
        self.assertEqual(api.sent[0]["reply_markup"]["inline_keyboard"][0][1]["callback_data"], "cb:nao")
        self.assertEqual(api.sent[1]["text"], "Escolheu nao")
        self.assertNotIn("waiting", flows.user_state(self.dir, 42))


class LanguageTests(unittest.TestCase):
    """Mensagens automáticas no idioma do bot e variáveis com nomes em inglês."""

    def test_i18n_texts_complete(self):
        from telebot_engine import i18n
        for key, table in i18n.TEXTS.items():
            for lang in i18n.SUPPORTED:
                self.assertIn(lang, table, key)
            a, b = table["pt"], table["en"]
            self.assertEqual(a.count("%"), b.count("%"), key)

    def test_english_bot_and_aliases(self):
        from telebot_engine import i18n, template
        ctx = template.build_context({"from": {"id": 1, "first_name": "Ann"}, "chat": {"id": 1}}, "x", "b", "d", "t")
        self.assertEqual(template.render("{name} {nome} {date}", ctx), "Ann Ann d")
        self.assertEqual(template.unknown_variables("{name} {foo}"), ["foo"])
        self.assertEqual(i18n.normalize("en-US"), "en")
        self.assertEqual(i18n.normalize("es-MX"), "")
        self.assertEqual(i18n.pick("en"), "en")

    def test_validation_message_in_bot_language(self):
        d = tempfile.mkdtemp()
        try:
            path = os.path.join(d, "config.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"version": 2, "language": "en", "commands": []}, fh)
            eng = engine.Engine("1:x", path, d, Callbacks(), api=FakeApi([]))
            eng.maybe_reload_config(force=True)
            self.assertEqual(eng.lang, "en")
            from telebot_engine import i18n
            self.assertEqual(i18n.t("shop.how_to_pay", eng.lang), "How would you like to pay?")
        finally:
            chats.close(d)
