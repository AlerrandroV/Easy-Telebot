"""Internet sem Termux: bloco HTTP, buscar() no Python, variáveis da internet e notícias (RSS/YouTube)."""

import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))
sys.path.insert(0, HERE)

from telebot_engine import chats, engine, feeds, netvars, pyfacil, template, webfetch  # noqa: E402
from test_engine import Callbacks, FakeApi, msg  # noqa: E402
from test_pyfacil import FakeHost  # noqa: E402


class Resp(io.BytesIO):
    def __init__(self, body, status=200):
        super().__init__(body if isinstance(body, bytes) else body.encode("utf-8"))
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeNet:
    """Troca webfetch._open: responde por pedaço da URL e guarda os pedidos."""

    def __init__(self, routes):
        self.routes = routes
        self.requests = []

    def __call__(self, req, timeout):
        self.requests.append(req)
        for part, (status, body) in self.routes.items():
            if part in req.full_url:
                if status >= 400:
                    raise urllib.error.HTTPError(req.full_url, status, "err", {}, io.BytesIO(body.encode()))
                return Resp(body, status)
        raise urllib.error.URLError("offline")


class NetCase(unittest.TestCase):
    routes = {}

    def setUp(self):
        self.net = FakeNet(self.routes)
        self._orig = webfetch._open
        webfetch._open = self.net
        netvars._cache.clear()

    def tearDown(self):
        webfetch._open = self._orig


class FetchTests(NetCase):
    routes = {"api.x/ok": (200, '{"data": {"items": [{"title": "Oi"}], "price": 10.0}}'),
              "api.x/fail": (404, "não achei"), "api.x/text": (200, "texto puro")}

    def test_fetch_and_extract(self):
        r = webfetch.fetch("https://api.x/ok")
        self.assertTrue(r["ok"])
        self.assertEqual(webfetch.extract(r["json"], "data.items.0.title"), "Oi")
        self.assertEqual(webfetch.extract(r["json"], "data.items[0].title"), "Oi")
        self.assertIsNone(webfetch.extract(r["json"], "data.nada"))
        self.assertEqual(webfetch.as_text(10.0), "10")
        self.assertEqual(webfetch.fetch("https://api.x/fail")["status"], 404)
        self.assertEqual(webfetch.fetch("ftp://x")["error"], "url")
        self.assertIn("offline", webfetch.fetch("https://outra.com")["error"])

    def test_prepare_encodes_values(self):
        block = webfetch.normalize_block({"method": "POST", "url": "https://api.x/ok?q={args}",
                                          "headers": "X-Key: {id}", "body": '{"nome": "{nome}"}', "body_type": "json"})
        url, headers, body = webfetch.prepare(block, {"args": "São Paulo & cia", "id": "7", "nome": 'Zé "Bala"'})
        self.assertEqual(url, "https://api.x/ok?q=S%C3%A3o%20Paulo%20%26%20cia")
        self.assertEqual(headers["X-Key"], "7")
        self.assertEqual(json.loads(body), {"nome": 'Zé "Bala"'})

    def test_test_block_for_app(self):
        out = json.loads(webfetch.test_block(json.dumps({"url": "https://api.x/ok", "path": "data.price"})))
        self.assertEqual((out["ok"], out["value"]), (True, "10"))


class EngineHttpTests(NetCase):
    routes = {"api.x/preco": (200, '{"preco": 42.5}'), "api.x/caiu": (500, "erro")}

    def setUp(self):
        super().setUp()
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        super().tearDown()
        chats.close(self.dir)

    def run_bot(self, commands, script):
        path = os.path.join(self.dir, "config.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": commands}, fh)
        api = FakeApi(script)
        eng = engine.Engine("1:x", path, self.dir, Callbacks(), api=api)
        eng.termux.sync_scripts = True
        eng.flows.sync = True
        api.engine = eng
        eng.sleep = lambda s: None
        eng.run()
        return api, eng

    def test_http_block_then_message(self):
        cmd = {"triggers": ["preco"], "blocks": [
            {"type": "http", "url": "https://api.x/preco?item={args}", "variable": "valor", "path": "preco"},
            {"type": "message", "text": "Custa {valor} ({valor_status})"}]}
        api, _ = self.run_bot([cmd], [[msg("/preco cafe")]])
        self.assertEqual(api.sent[0]["text"], "Custa 42.5 (200)")
        self.assertIn("item=cafe", self.net.requests[0].full_url)

    def test_http_failure_is_logged(self):
        cmd = {"triggers": ["x"], "blocks": [{"type": "http", "url": "https://api.x/caiu", "variable": "v"},
                                              {"type": "message", "text": "status {v_status}"}]}
        api, eng = self.run_bot([cmd], [[msg("/x")]])
        self.assertEqual(api.sent[0]["text"], "status 500")
        self.assertEqual([e["code"] for e in eng.cb.events if e["type"] == "error"], ["http_failed"])


class PythonFetchTests(NetCase):
    routes = {"api.x/j": (200, '{"a": [1, 2, 3]}'), "coingecko": (200, '{"bitcoin": {"brl": 500000.5}}')}

    def test_fetch_from_python(self):
        code = ('r = buscar("https://api.x/j")\n'
                'if r.ok:\n    enviar_texto(str(sum(r.json["a"])) + " " + str(r.status))\n'
                'p = buscar("https://api.x/j", json={"x": 1})\n'
                'enviar_texto(str(preco("BTC")))\n')
        out = pyfacil.run(code, host := FakeHost(), message={"from": {"id": 1}, "chat": {"id": 1}})
        self.assertTrue(out["ok"], out["error"])
        self.assertEqual([s[1]["text"] for s in host.sent], ["6 200", "500000.5"])
        self.assertEqual(self.net.requests[1].get_method(), "POST")
        self.assertEqual(json.loads(self.net.requests[1].data), {"x": 1})

    def test_fetch_limit(self):
        out = pyfacil.run("for i in range(30):\n    buscar('https://api.x/j')\n", FakeHost(), message={"chat": {"id": 1}})
        self.assertEqual(out["error"]["code"], "fetches")


class NetVarTests(NetCase):
    routes = {
        "coingecko": (200, '{"bitcoin": {"brl": 345678.9, "usd": 65000}}'),
        "frankfurter": (200, '{"rates": {"BRL": 5.4321}}'),
        "geocoding": (200, '{"results": [{"latitude": -23.5, "longitude": -46.6}]}'),
        "forecast": (200, '{"current": {"temperature_2m": 23.4, "weather_code": 2}}'),
    }

    def test_render_with_internet_variables(self):
        text = template.render("BTC {preço:BTC}, dólar {câmbio:USD}, SP {clima:São Paulo}, {x:y}", {}, "pt")
        self.assertEqual(text, "BTC R$ 345.678,90, dólar R$ 5,4321, SP 23 °C, parcialmente nublado, {x:y}")
        self.assertEqual(template.render("{price:BTC:USD}", {}, "en"), "US$ 65,000.00")

    def test_cache(self):
        template.render("{preço:BTC}", {}, "pt")
        template.render("{preco:btc}", {}, "pt")
        self.assertEqual(len(self.net.requests), 1)

    def test_offline(self):
        webfetch._open = FakeNet({})
        self.assertEqual(template.render("{clima:Lisboa}", {}, "pt"), "—")


RSS = """<?xml version="1.0"?><rss version="2.0"><channel><title>Blog</title>
<item><title>Post 3</title><link>https://b.com/3</link><guid>3</guid><description>&lt;p&gt;Novo &amp; bom&lt;/p&gt;</description></item>
<item><title>Post 2</title><link>https://b.com/2</link><guid>2</guid></item>
</channel></rss>"""
ATOM = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
<entry><id>yt:video:abc</id><title>Vídeo novo</title><link rel="alternate" href="https://www.youtube.com/watch?v=abc"/>
<author><name>Canal</name></author><media:group><media:thumbnail url="https://i.ytimg.com/abc.jpg"/>
<media:description>Descrição do vídeo</media:description></media:group></entry></feed>"""


class FeedTests(NetCase):
    routes = {"b.com/feed": (200, RSS), "youtube.com/feeds": (200, ATOM)}

    def test_parse_rss_and_youtube(self):
        items = feeds.parse(RSS)
        self.assertEqual([i["title"] for i in items], ["Post 3", "Post 2"])
        self.assertEqual(items[0]["summary"], "Novo & bom")
        yt = feeds.parse(ATOM)[0]
        self.assertEqual((yt["link"], yt["image"], yt["author"], yt["summary"]),
                         ("https://www.youtube.com/watch?v=abc", "https://i.ytimg.com/abc.jpg", "Canal", "Descrição do vídeo"))
        self.assertEqual(feeds.normalize_url("https://www.youtube.com/channel/UCabcdefghijkl"),
                         "https://www.youtube.com/feeds/videos.xml?channel_id=UCabcdefghijkl")

    def test_only_new_posts_reach_the_channel(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "config.json")
        auto = {"kind": "feed", "id": "f1", "trigger": {"url": "https://b.com/feed", "every": 10, "target": "chats",
                                                        "chats": [-100]},
                "blocks": [{"type": "message", "text": "*{titulo}*\n{link}"}]}
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"version": 2, "commands": [auto]}, fh)
        api = FakeApi([])
        eng = engine.Engine("1:x", path, d, Callbacks(), api=api)
        eng.scheduler.sync = True
        eng.maybe_reload_config(force=True)
        try:
            eng.scheduler.tick(1000)  # primeira vez: só marca o que já existe
            self.assertEqual(api.sent, [])
            self.net.routes["b.com/feed"] = (200, RSS.replace("<item><title>Post 3", "<item><title>Post 4</title>"
                                                              "<link>https://b.com/4</link><guid>4</guid></item>"
                                                              "<item><title>Post 3"))
            eng.scheduler.tick(1000 + 60)  # antes dos 10 minutos: nem consulta
            self.assertEqual(len(self.net.requests), 1)
            eng.scheduler.tick(1000 + 601)
            self.assertEqual([p["text"] for p in api.sent], ["<b>Post 4</b>\nhttps://b.com/4"])
            eng.scheduler.tick(1000 + 1300)
            self.assertEqual(len(api.sent), 1)
        finally:
            chats.close(d)


if __name__ == "__main__":
    unittest.main()
