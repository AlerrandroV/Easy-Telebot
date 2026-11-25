import json
import os
import tempfile
import unittest

from telebot_engine import pyfacil


class FakeHost:
    callback_prefix = "cb:"
    stopping = False

    def __init__(self, termux_result=None, file_bytes=b"\xff\xd8jpeg"):
        self.sent = []
        self.logs = []
        self.termux_calls = []
        self.downloads = []
        self.slept = 0
        self.termux_result = termux_result or {"ok": True, "stdout": "pronto\n", "stderr": "", "exit_code": 0}
        self.tmp = tempfile.mkdtemp()
        self.file_bytes = file_bytes

    def send(self, chat_id, method, params, files=None):
        self.sent.append((method, params, files))
        return {"message_id": len(self.sent)}

    def download(self, info):
        self.downloads.append(info["file_id"])
        path = os.path.join(self.tmp, info["file_id"] + ".jpg")
        with open(path, "wb") as fh:
            fh.write(self.file_bytes)
        return path

    def termux(self, command, env, timeout, files):
        self.termux_calls.append((command, env, timeout, files))
        return self.termux_result

    def log(self, text):
        self.logs.append(text)

    def sleep(self, seconds):
        self.slept += seconds

    def now(self):
        return "25/11/2026 10:00"


USER = {"id": 7, "first_name": "Ana", "language_code": "pt-br"}
CHAT = {"id": 7, "type": "private"}
PHOTO = {"message_id": 5, "from": USER, "chat": CHAT,
         "photo": [{"file_id": "small", "file_size": 10, "width": 90}, {"file_id": "big", "file_size": 900, "width": 800}]}
TEXT = {"message_id": 6, "from": USER, "chat": CHAT, "text": "oi bot"}


def run(code, message=TEXT, host=None, **kw):
    host = host or FakeHost()
    return pyfacil.run(code, host, message=message, **kw), host


class ExampleTests(unittest.TestCase):
    CODE = (
        'if mensagem.tipo == "foto":\n'
        '    arquivo = mensagem.foto.baixar()\n'
        '    termux("node atualizar_foto_perfil.js", arquivo)\n'
        '    enviar_texto("Foto atualizada com sucesso!")\n'
        '    parar()\n'
        'else:\n'
        '    enviar_texto("Isso não é uma imagem, por favor, envie uma foto válida!")\n'
    )

    def test_photo_branch(self):
        out, host = run(self.CODE, PHOTO)
        self.assertTrue(out["ok"], out["error"])
        self.assertTrue(out["stopped"])
        self.assertEqual(host.downloads, ["big"])  # a maior foto
        command, env, timeout, files = host.termux_calls[0]
        self.assertTrue(command.startswith('node atualizar_foto_perfil.js "$TELEBOT_FILES_DIR"/telebot-'))
        self.assertEqual(len(files), 1)
        self.assertTrue(command.endswith(files[0]["name"]))
        self.assertEqual(timeout, 30)
        self.assertEqual(host.sent[0][1]["text"], "Foto atualizada com sucesso!")

    def test_text_branch_keeps_listening(self):
        out, host = run(self.CODE, TEXT)
        self.assertTrue(out["ok"])
        self.assertFalse(out["stopped"])
        self.assertIn("não é uma imagem", host.sent[0][1]["text"])
        self.assertEqual(host.termux_calls, [])

    def test_original_user_style(self):
        code = ("if mensagem.tipo == foto:\n"
                "    foto_base64 = base64(mensagem.foto)\n"
                "    termux(f\"node atualizar.js {foto_base64}\")\n"
                "    exit()\n")
        out, host = run(code, PHOTO)
        self.assertTrue(out["ok"], out["error"])
        self.assertTrue(out["stopped"])
        self.assertIn("node atualizar.js /9hqcGVn", host.termux_calls[0][0])


class LanguageTests(unittest.TestCase):
    def test_all_languages(self):
        samples = {
            "en": 'if message.type == photo:\n    send_text("ok " + user.name)\n    stop()\n',
            "es": 'if mensaje.tipo == foto:\n    enviar_texto("ok " + usuario.nombre)\n    detener()\n',
            "fr": 'if message.type == photo:\n    envoyer_texte("ok " + utilisateur.prénom)\n    arrêter()\n',
            "ru": 'if сообщение.тип == фото:\n    отправить_текст("ok " + пользователь.имя)\n    стоп()\n',
            "pt": 'if mensagem.tipo == foto:\n    enviar_texto("ok " + usuário.nome)\n    parar()\n',
        }
        for lang, code in samples.items():
            out, host = run(code, PHOTO)
            self.assertTrue(out["ok"], (lang, out["error"]))
            self.assertTrue(out["stopped"], lang)
            self.assertEqual(host.sent[0][1]["text"], "ok Ana", lang)
            self.assertEqual(pyfacil.detect_language(code, "pt"), lang if lang != "en" else "en")

    def test_kind_compares_in_any_language(self):
        out, host = run('if message.type == "foto" and mensagem.tipo in ["photo", "video"]:\n    send("sim")\n', PHOTO)
        self.assertTrue(out["ok"], out["error"])
        self.assertEqual(host.sent[0][1]["text"], "sim")

    def test_error_language_follows_code(self):
        out, _ = run('send_text(1 / 0)\n')
        self.assertEqual(out["error"]["message"], "Line 1: division by zero.")
        out, _ = run('enviar_texto(1 / 0)\n')
        self.assertEqual(out["error"]["message"], "Linha 1: divisão por zero.")
        out, _ = run('отправить_текст(1 / 0)\n')
        self.assertEqual(out["error"]["message"], "Строка 1: деление на ноль.")


class TermuxTests(unittest.TestCase):
    def test_arguments_and_result(self):
        code = ('r = termux("python3 x.py", "a b", 5, tempo=120, ambiente={"CIDADE": "Rio"})\n'
                'if r.ok:\n    enviar_texto(r.saida)\n')
        out, host = run(code)
        self.assertTrue(out["ok"], out["error"])
        command, env, timeout, files = host.termux_calls[0]
        self.assertEqual(command, "python3 x.py 'a b' 5")
        self.assertEqual(timeout, 120)
        self.assertEqual(env, {"CIDADE": "Rio"})
        self.assertEqual(host.sent[0][1]["text"], "pronto")

    def test_timeout_capped_and_english_names(self):
        host = FakeHost({"ok": True, "stdout": "", "stderr": "late", "exit_code": 124})
        out, host = run('r = termux("sleep 999", timeout=9999)\nif r.timed_out:\n    send(r.error)\n', host=host)
        self.assertEqual(host.termux_calls[0][2], pyfacil.MAX_TERMUX_TIMEOUT)
        self.assertEqual(host.sent[0][1]["text"], "late")

    def test_failure_is_a_result(self):
        host = FakeHost({"ok": False, "error": {"code": "termux_missing", "detail": ""}})
        out, host = run('r = termux("ls")\nif not r.ok:\n    enviar_texto("falhou: " + r.erro)\n', host=host)
        self.assertTrue(out["ok"])
        self.assertEqual(host.sent[0][1]["text"], "falhou: termux_missing")

    def test_bad_env_name(self):
        out, _ = run('termux("ls", ambiente={"x y": 1})\n')
        self.assertEqual(out["error"]["code"], "env_name")

    def test_file_too_big(self):
        host = FakeHost(file_bytes=b"x" * (pyfacil.MAX_FILE_TO_TERMUX + 1))
        out, _ = run('termux("ls", mensagem.foto)\n', PHOTO, host=host)
        self.assertEqual(out["error"]["code"], "file_big")


class LanguageFeatureTests(unittest.TestCase):
    def test_loops_lists_dicts_fstrings(self):
        code = ("total = 0\n"
                "for i in range(5):\n"
                "    if i == 3:\n"
                "        continue\n"
                "    total += i\n"
                "itens = {'a': 1}\n"
                "itens['b'] = 2\n"
                "n = 0\n"
                "while True:\n"
                "    n += 1\n"
                "    if n >= 3:\n"
                "        break\n"
                "nomes = ['x', 'y']\n"
                "nomes.append('z')\n"
                "enviar_texto(f'{total} {len(itens)} {n} {\", \".join(nomes)} {3.14159:.2f}')\n")
        out, host = run(code)
        self.assertTrue(out["ok"], out["error"])
        self.assertEqual(host.sent[0][1]["text"], "7 2 3 x, y, z 3.14")

    def test_variables_persist_and_args(self):
        out, host = run('variaveis["cidade"] = argumentos.upper()\nenviar_texto(variaveis.get("x", "?"))\n',
                        args="rio", variables={"x": 1})
        self.assertEqual(dict(out["variables"]), {"x": 1, "cidade": "RIO"})
        self.assertEqual(host.sent[0][1]["text"], "1")

    def test_buttons(self):
        code = ('enviar_texto("Escolha", botoes=[botao("Sim"), [botao("Site", link="https://x.org"), '
                'botao("Copiar", copiar="ABC")]])\n')
        out, host = run(code)
        self.assertTrue(out["ok"], out["error"])
        kb = host.sent[0][1]["reply_markup"]["inline_keyboard"]
        self.assertEqual(kb[0], [{"text": "Sim", "callback_data": "cb:sim"}])
        self.assertEqual(kb[1][0]["url"], "https://x.org")
        self.assertEqual(kb[1][1]["copy_text"], {"text": "ABC"})

    def test_button_click_message(self):
        msg = {"_button": "sim", "from": USER, "chat": CHAT, "message_id": 9}
        out, host = run('if mensagem.tipo == botao and mensagem.dado == "sim":\n    responder("ok")\n    parar()\n', msg)
        self.assertTrue(out["stopped"], out["error"])
        self.assertNotIn("reply_parameters", host.sent[0][1])

    def test_reply_and_photo(self):
        out, host = run('responder("a")\nenviar_foto(mensagem.foto, legenda="b")\n', PHOTO)
        self.assertEqual(host.sent[0][1]["reply_parameters"]["message_id"], 5)
        self.assertEqual(host.sent[1][0], "sendPhoto")
        self.assertEqual(host.sent[1][1]["photo"], "big")
        self.assertEqual(host.sent[1][1]["caption"], "b")

    def test_log_pause_random(self):
        out, host = run('registrar("x", 1)\npausa(2)\nn = aleatorio(1, 1)\nprint(escolher([n]))\n')
        self.assertTrue(out["ok"], out["error"])
        self.assertEqual(host.logs, ["x 1", "1"])
        self.assertEqual(host.slept, 2)


class SafetyTests(unittest.TestCase):
    def assertCode(self, code, expected, message=TEXT):
        out, _ = run(code, message)
        self.assertFalse(out["ok"], code)
        self.assertEqual(out["error"]["code"], expected, out["error"])
        return out

    def test_forbidden(self):
        for code in ("import os\n", "def f():\n    pass\n", "x = lambda: 1\n", "().__class__\n",
                     "mensagem._msg\n", "try:\n    pass\nexcept:\n    pass\n", "[x for x in 'ab']\n"):
            out, _ = run(code)
            self.assertIn(out["error"]["code"], ("not_allowed",), code)

    def test_no_python_internals(self):
        self.assertCode("open('x')\n", "unknown_name")
        self.assertCode("eval('1')\n", "unknown_name")
        self.assertCode("'a'.format_map({})\n", "unknown_attr")
        self.assertCode("mensagem.foo\n", "unknown_attr")

    def test_infinite_loop(self):
        self.assertCode("while True:\n    pass\n", "steps")

    def test_memory_bombs(self):
        self.assertCode("x = 'a' * 10000000\n", "too_big")
        self.assertCode("x = 10 ** 100000\n", "too_big")
        self.assertCode("x = list(range(100000000))\n", "too_big")
        self.assertCode("x = f'{1:999999999}'\n", "too_big")
        self.assertCode("x = 'a' * 90000\nwhile True:\n    x = x + 'a'\n", "too_big")

    def test_send_limit(self):
        self.assertCode("for i in range(50):\n    enviar_texto(str(i))\n", "sends")

    def test_read_only(self):
        self.assertCode("mensagem.texto = 'x'\n", "read_only")
        self.assertCode("variaveis = 1\n", "read_only")

    def test_type_error_is_explained(self):
        out = self.assertCode("enviar_texto('a' + 1)\n", "type")
        self.assertIn("str()", out["error"]["message"])

    def test_no_file(self):
        self.assertCode("mensagem.arquivo.baixar()\n", "no_file")
        self.assertCode("termux('ls', mensagem.foto)\n", "no_file")

    def test_storable(self):
        self.assertEqual(pyfacil.storable({"a": [1, "b"]}), {"a": [1, "b"]})
        self.assertIsNone(pyfacil.storable({"a": object()}))
        self.assertIsNone(pyfacil.storable({"a": "x" * (pyfacil.MAX_VARIABLES + 1)}))


class CheckTests(unittest.TestCase):
    def test_ok(self):
        res = pyfacil.check(ExampleTests.CODE)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["lang"], "pt")

    def test_syntax(self):
        res = pyfacil.check("if mensagem.tipo == foto\n    parar()\n")
        self.assertFalse(res["ok"])
        self.assertEqual(res["errors"][0]["code"], "syntax")
        self.assertEqual(res["errors"][0]["line"], 1)

    def test_unknown_names_with_hint(self):
        res = pyfacil.check("enviar_textoo('x')\nmensagem.tipoo\n")
        codes = [e["code"] for e in res["errors"]]
        self.assertEqual(codes, ["unknown_name", "unknown_attr"])
        self.assertIn("enviar_texto", res["errors"][0]["message"])

    def test_unknown_kwarg(self):
        res = pyfacil.check("termux('ls', tempoo=3)\n")
        self.assertEqual(res["errors"][0]["code"], "unknown_kwarg")

    def test_user_variables_are_known(self):
        self.assertTrue(pyfacil.check("x = 1\nfor i in [x]:\n    enviar_texto(str(i))\n")["ok"])

    def test_json_roundtrip(self):
        from telebot_engine import code_runner
        self.assertTrue(json.loads(code_runner.check("parar()"))["ok"])


if __name__ == "__main__":
    unittest.main()
