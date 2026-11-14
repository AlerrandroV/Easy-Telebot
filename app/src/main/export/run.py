#!/usr/bin/env python3
"""Roda este bot no computador (ou em qualquer servidor) com o mesmo motor do app.

Requisitos: Python 3.8 ou mais novo. Nenhum pacote extra.

    python3 run.py                 liga o bot
    python3 run.py newadmin [N]    gera um código para virar admin da classe N
                                   (1 = classe I, a mais alta; padrão 1)

O token é lido, nesta ordem, de: variável de ambiente BOT_TOKEN, arquivo
token.txt ao lado deste script, ou digitado no terminal.

ATENÇÃO: o mesmo bot não pode rodar em dois lugares ao mesmo tempo. Desligue-o
no celular antes de ligar aqui.
"""

import json
import os
import signal
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from telebot_engine import admins  # noqa: E402
from telebot_engine.engine import Engine  # noqa: E402


class ConsoleCallbacks:
    def onEvent(self, data):  # noqa: N802 - mesmo nome usado pelo app
        e = json.loads(data)
        kind = e.pop("type")
        stamp = time.strftime("%H:%M:%S")
        if kind == "received":
            print("%s <- %s: %s" % (stamp, e.get("user"), e.get("text")))
        elif kind == "replied":
            print("%s -> resposta para /%s" % (stamp, e.get("command")))
        elif kind == "error":
            print("%s !! erro (%s): %s" % (stamp, e.get("code"), e.get("detail")))
        else:
            print("%s .. %s %s" % (stamp, kind, json.dumps(e, ensure_ascii=False)))
        sys.stdout.flush()

    def nowLocal(self):  # noqa: N802
        return time.strftime("%d/%m/%Y %H:%M")


def read_token():
    token = os.environ.get("BOT_TOKEN", "").strip()
    if token:
        return token
    path = os.path.join(HERE, "token.txt")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read().strip()
    return input("Token do bot: ").strip()


def new_admin_code(level):
    data = json.loads(admins.create_code(HERE, level))
    minutes = (data["expires_at"] - int(time.time())) // 60
    print("Mande esta mensagem para o bot, em conversa privada, nos próximos %d minutos:" % minutes)
    print()
    print("    /newadmin %s" % data["code"])
    print()
    print("O código só funciona uma vez e o bot precisa estar ligado (python3 run.py).")


def read_storage():
    """storage.json (+ storage_secrets.json, se exportado com o token).

    No computador, "interno" e "pasta do aparelho" viram a pasta ./dados ao lado do run.py.
    """
    def load(name):
        path = os.path.join(HERE, name)
        if not os.path.exists(path):
            return {}
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)

    settings = load("storage.json")
    if not settings:
        return None
    settings.update({k: v for k, v in load("storage_secrets.json").items() if v})
    if settings.get("type") in ("internal", "device", None):
        settings["type"] = "device"
        settings["device_path"] = os.path.join(HERE, "dados")
    return json.dumps(settings)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "newadmin":
        new_admin_code(sys.argv[2] if len(sys.argv) > 2 else 1)
        return
    engine = Engine(read_token(), os.path.join(HERE, "config.json"), HERE, ConsoleCallbacks(),
                    storage=read_storage())

    def stop(*_):
        print("Parando…")
        engine.stop()

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    thread = threading.Thread(target=engine.run)
    thread.start()
    while thread.is_alive():
        thread.join(0.5)


if __name__ == "__main__":
    main()
