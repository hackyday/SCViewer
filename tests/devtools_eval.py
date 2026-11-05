#!/usr/bin/env python3
"""Eval JS sul WebView SCViewer via DevTools (adb forward tcp:PORT -> socket).

Uso: python3 devtools_eval.py [--port 9222] [--list] "expr1" "expr2" ...
"""
import json, sys, websocket

WS = None
_next_id = 1

def connect(port):
    global WS
    import urllib.request
    targets = json.load(urllib.request.urlopen(f"http://localhost:{port}/json/list"))
    page = next(t for t in targets if t["type"] == "page")
    WS = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=25, suppress_origin=True)

def call(method, params=None):
    global _next_id
    msg_id = _next_id; _next_id += 1
    WS.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
    while True:
        resp = json.loads(WS.recv())
        if resp.get("id") == msg_id:
            return resp

def evaluate(expr):
    r = call("Runtime.evaluate", {"expression": expr, "returnByValue": True, "awaitPromise": True})
    if "exceptionDetails" in r.get("result", {}):
        return {"EXC": r["result"]["exceptionDetails"].get("exception", {}).get("description", "?")}
    return r.get("result", {}).get("result", {}).get("value")

if __name__ == "__main__":
    args = sys.argv[1:]
    port = 9222
    if args and args[0] == "--port":
        port = int(args[1]); args = args[2:]
    if args and args[0] == "--list":
        import urllib.request
        for t in json.load(urllib.request.urlopen(f"http://localhost:{port}/json/list")):
            print(t["type"], "|", t.get("url", "")[:100])
        sys.exit(0)
    connect(port)
    for expr in args:
        print(evaluate(expr))
