"""Testes do armazenamento (rodam sem internet: os bancos na nuvem usam HTTP falso)."""

import datetime
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "main", "python"))

from telebot_engine import admins, engine  # noqa: E402
from telebot_engine import storage  # noqa: E402
from telebot_engine.storage import backends, gauth, http, sigv4  # noqa: E402
from telebot_engine.storage.worker import StorageWorker  # noqa: E402

AWS_SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"


def wait_for(cond, timeout=3):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.02)
    return False


class SignatureTests(unittest.TestCase):
    def test_sigv4_official_examples(self):
        now = datetime.datetime(2015, 8, 30, 12, 36, tzinfo=datetime.timezone.utc)
        self.assertEqual(sigv4.signing_key(AWS_SECRET, "20150830", "us-east-1", "iam").hex(),
                         "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9")
        h = sigv4.sign("GET", "https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08",
                       {"Content-Type": "application/x-www-form-urlencoded; charset=utf-8"}, b"",
                       "AKIDEXAMPLE", AWS_SECRET, "us-east-1", "iam", now)
        self.assertTrue(h["Authorization"].endswith(
            "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"))
        h = sigv4.sign("GET", "https://example.amazonaws.com/", {}, b"", "AKIDEXAMPLE", AWS_SECRET,
                       "us-east-1", "service", now)
        self.assertTrue(h["Authorization"].endswith(
            "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"))

    @unittest.skipUnless(shutil.which("openssl"), "openssl não instalado")
    def test_rs256_matches_openssl(self):
        d = tempfile.mkdtemp()
        key = os.path.join(d, "k.pem")
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                        "-out", key], check=True, capture_output=True)
        msg = os.path.join(d, "m")
        with open(msg, "wb") as fh:
            fh.write(b"a.b")
        expected = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key, msg],
                                  check=True, capture_output=True).stdout
        with open(key) as fh:
            self.assertEqual(gauth.RsaKey.from_pem(fh.read()).sign_sha256(b"a.b"), expected)

    def test_bad_service_account(self):
        with self.assertRaises(http.StorageError) as ctx:
            gauth.ServiceAccount('{"type": "authorized_user"}')
        self.assertEqual(ctx.exception.code, http.CONFIG)


class LocalStoreTests(unittest.TestCase):
    def test_crud_and_list(self):
        s = backends.LocalStore(tempfile.mkdtemp())
        s.put("usuarios", 1, {"a": 1})
        s.put("usuarios", "../x", {"a": 2})  # nome perigoso vira seguro
        self.assertEqual(s.get("usuarios", 1), {"a": 1})
        self.assertEqual(len(s.list("usuarios")), 2)
        s.delete("usuarios", 1)
        self.assertIsNone(s.get("usuarios", 1))
        self.assertEqual(s.list("vazia"), [])

    def test_permission_error(self):
        s = backends.LocalStore("/proc/nao-pode-escrever")
        with self.assertRaises(http.StorageError) as ctx:
            s.put("c", 1, {})
        self.assertIn(ctx.exception.code, (http.PERMISSION, http.UNKNOWN))

    def test_record_user_counts(self):
        s = backends.LocalStore(tempfile.mkdtemp())
        user = {"id": 9, "first_name": "Ana", "username": "ana"}
        storage.record_user(s, user, {"type": "private"}, now=100)
        storage.record_user(s, user, {"type": "group", "id": -5}, now=200)
        doc = s.get("usuarios", 9)
        self.assertEqual((doc["first_seen"], doc["last_seen"], doc["messages"], doc["groups"]), (100, 200, 2, [-5]))


class FakeHttp:
    """Substitui http.request; guarda as chamadas e responde com o que mandarmos."""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, method, url, body=None, headers=None, timeout=30):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.calls.append((method, url, body, headers or {}))
        status, data = self.responses.pop(0)
        return status, json.dumps(data).encode() if not isinstance(data, bytes) else data


class CloudTests(unittest.TestCase):
    def setUp(self):
        self._orig = http.request

    def tearDown(self):
        http.request = self._orig

    def fake(self, responses):
        f = FakeHttp(responses)
        http.request = f
        return f

    def test_firebase_paths_and_auth(self):
        f = self.fake([(200, None), (200, {"x": 1}), (401, {"error": "Permission denied"})])
        s = backends.FirebaseRealtimeStore("https://p-default-rtdb.firebaseio.com/", "SEGREDO", "123")
        s.put("usuarios", 7, {"x": 1})
        self.assertEqual(s.get("usuarios", 7), {"x": 1})
        method, url, body, _ = f.calls[0]
        self.assertEqual(method, "PUT")
        self.assertTrue(url.startswith("https://p-default-rtdb.firebaseio.com/easytelebot/123/usuarios/7.json?auth=SEGREDO"))
        with self.assertRaises(http.StorageError) as ctx:
            s.get("usuarios", 8)
        self.assertEqual(ctx.exception.code, http.AUTH)

    def test_firestore_encoding_roundtrip(self):
        data = {"s": "oi", "i": 5, "f": 1.5, "b": True, "n": None, "l": [1, "a"], "m": {"k": "v"}}
        enc = backends._fs_encode(data)
        self.assertEqual(enc["mapValue"]["fields"]["i"], {"integerValue": "5"})
        self.assertEqual(backends._fs_decode(enc), data)

    def test_dynamodb_request_is_signed(self):
        f = self.fake([(200, {}), (200, {"Item": {"data": {"S": '{"a": 1}'}}}),
                       (400, {"__type": "com.amazonaws.dynamodb.v20120810#ResourceNotFoundException",
                              "message": "Requested resource not found"})])
        s = backends.DynamoDBStore("AKID", "SECRET", "sa-east-1", "easytelebot", "123")
        s.put("usuarios", 7, {"a": 1})
        self.assertEqual(s.get("usuarios", 7), {"a": 1})
        method, url, body, headers = f.calls[0]
        self.assertEqual(url, "https://dynamodb.sa-east-1.amazonaws.com/")
        self.assertEqual(headers["X-Amz-Target"], "DynamoDB_20120810.PutItem")
        self.assertIn("Credential=AKID/", headers["Authorization"])
        self.assertIn("/sa-east-1/dynamodb/aws4_request", headers["Authorization"])
        self.assertEqual(json.loads(body)["Item"]["pk"], {"S": "123#usuarios"})
        with self.assertRaises(http.StorageError) as ctx:
            s.get("usuarios", 8)
        self.assertEqual(ctx.exception.code, http.NOT_FOUND)

    def test_missing_aws_fields(self):
        out = json.loads(storage.test({"type": "dynamodb", "aws_region": "us-east-1"}, tempfile.mkdtemp(), "1"))
        self.assertFalse(out["ok"])
        self.assertEqual(out["error"]["code"], http.CONFIG)


class BackupAndEngineTests(unittest.TestCase):
    def test_backup_restore_roundtrip(self):
        bot = tempfile.mkdtemp()
        with open(os.path.join(bot, "config.json"), "w") as fh:
            json.dump({"version": 2, "commands": [{"triggers": ["a"], "messages": [{"text": "x"}]}]}, fh)
        admins.redeem_code(bot, json.loads(admins.create_code(bot, 2))["code"], {"id": 5, "first_name": "Z"})
        settings = {"type": "internal"}
        self.assertTrue(json.loads(storage.test(settings, bot, "1"))["ok"])
        out = json.loads(storage.backup(settings, bot, "1"))
        self.assertEqual((out["commands"], out["admins"]), (1, 1))
        os.remove(os.path.join(bot, "config.json"))
        admins.remove_admin(bot, 5)
        out = json.loads(storage.restore(settings, bot, "1"))
        self.assertTrue(out["ok"])
        self.assertTrue(os.path.exists(os.path.join(bot, "config.json")))
        self.assertEqual(list(admins.load(bot)), [5])

    def test_restore_without_backup(self):
        out = json.loads(storage.restore({"type": "internal"}, tempfile.mkdtemp(), "1"))
        self.assertEqual(out["error"]["code"], http.NOT_FOUND)

    def test_worker_records_and_redacts(self):
        bot = tempfile.mkdtemp()
        events = []
        w = StorageWorker({"type": "internal", "store_users": True, "store_history": True}, bot, "1",
                          lambda t, **k: events.append((t, k)))
        w.start()
        user = {"id": 3, "first_name": "Bia"}
        w.record({"message_id": 1, "from": user, "chat": {"id": 3, "type": "private"}, "text": "/newadmin SEGREDO123"})
        w.record({"message_id": 2, "from": user, "chat": {"id": 3, "type": "private"}, "text": "oi"})
        w.stop()
        w.join(3)
        store = backends.LocalStore(os.path.join(bot, "db"))
        self.assertEqual(store.get("usuarios", 3)["messages"], 2)
        texts = sorted(d["text"] for d in store.list("historico"))
        self.assertEqual(texts, ["/newadmin ••••", "oi"])
        for root, _, files in os.walk(bot):
            for f in files:
                with open(os.path.join(root, f), encoding="utf-8") as fh:
                    self.assertNotIn("SEGREDO123", fh.read())

    def test_worker_reports_error_once(self):
        events = []
        w = StorageWorker({"type": "device", "device_path": "/proc/sem-permissao"}, tempfile.mkdtemp(), "1",
                          lambda t, **k: events.append((t, k)))
        w.start()
        for i in range(5):
            w.record({"message_id": i, "from": {"id": 1}, "chat": {"id": 1, "type": "private"}, "text": "x"})
        w.stop()
        w.join(3)
        errors = [k for t, k in events if t == "error"]
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0]["context"], "storage")


class EngineIntegrationTests(unittest.TestCase):
    def test_engine_saves_users(self):
        sys.path.insert(0, HERE)
        from test_engine import Callbacks, FakeApi, msg
        bot = tempfile.mkdtemp()
        path = os.path.join(bot, "config.json")
        with open(path, "w") as fh:
            json.dump({"version": 2, "commands": [{"triggers": ["oi"], "messages": [{"text": "oi"}]}]}, fh)
        second = msg("bom dia", update_id=2)
        second["message"]["message_id"] = 8
        api = FakeApi([[msg("/oi"), second]])
        # Sem "v": configuração antiga -> o histórico passa a ser gravado.
        eng = engine.Engine("777:x", path, bot, Callbacks(), api=api,
                            storage=json.dumps({"type": "internal", "store_users": True, "store_history": False}))
        api.engine = eng
        eng.sleep = lambda s: None
        eng.run()
        eng.storage.join(3)
        doc = backends.LocalStore(os.path.join(bot, "db")).get("usuarios", 42)
        self.assertEqual(doc["messages"], 2)  # conta também mensagens que não são comandos
        self.assertEqual(len(api.sent), 1)
        history = backends.LocalStore(os.path.join(bot, "db")).list("historico")
        got = sorted((d["direction"], d["source"], d["text"]) for d in history)
        self.assertEqual(got, [("in", "user", "/oi"), ("in", "user", "bom dia"), ("out", "auto", "oi")])

    def test_history_can_be_turned_off(self):
        s = storage.parse_settings({"v": 2, "store_history": False})
        self.assertFalse(s["store_history"])
        self.assertTrue(storage.parse_settings({})["store_history"])


if __name__ == "__main__":
    unittest.main()
