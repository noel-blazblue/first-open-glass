#!/usr/bin/env python3
"""Glasses AI Chat host — Rokid-style: ASR → agent answer → TTS.

Photo / look-at-store is an optional tool, not the default skill.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".cursor" / "glass-inbox.json"
LOG = ROOT / ".cursor" / "glass-inbox.jsonl"
SESSION = ROOT / ".cursor" / "glass-ai-session.json"
WAV = ROOT / ".cursor" / "glass-tts.wav"
ENV_FILE = ROOT / ".cursor" / "glass-ai.env"
PORT = 18765

SYSTEM = """你是眼镜上的 AI 助手，类似 Rokid 乐奇的连续语音问答。
用户刚通过眼镜麦克风说话，ASR 可能吞字、截断或同音错误。

默认能力是对话：听懂问题，用口语回答。不要把每句话都当成「看店」或拍照。
只有用户明确要看眼前的店/招牌/拍一张时，才调用工具 look。
只有用户明确要换下一家店时，才调用工具 next。

只输出一个 JSON，不要 markdown：
{"speak":"给用户听的口语回答，一两句，不超过80字","hud":"镜片短句，不超过16字","tool":"none|look|next"}

tool 默认 none。先回答，再决定要不要动相机。"""

_disabled: set[str] = set()
_history: list[dict[str, str]] = []


def load_dotenv() -> None:
    if not ENV_FILE.exists():
        return
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def compact(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def load_session() -> None:
    global _history
    if not SESSION.exists():
        return
    try:
        data = json.loads(SESSION.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        _history = []
        return
    migrated: list[dict[str, str]] = []
    for turn in data.get("history") or []:
        if turn.get("role") and turn.get("content") is not None:
            migrated.append({"role": str(turn["role"]), "content": str(turn["content"])})
        elif turn.get("user"):
            migrated.append({"role": "user", "content": str(turn["user"])})
            migrated.append({"role": "assistant", "content": str(turn.get("assistant") or "")})
    _history = migrated


def save_session() -> None:
    SESSION.parent.mkdir(parents=True, exist_ok=True)
    SESSION.write_text(
        json.dumps({"history": _history[-24:]}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def wants_look(spoken: str) -> bool:
    return any(
        token in spoken
        for token in ("看店", "拍照", "拍一张", "看看这家", "看一下这家", "看眼前", "帮我看这家")
    )


def wants_next(spoken: str) -> bool:
    return any(token in spoken for token in ("下一家", "换一家", "不是这家"))


def fallback(text: str, has_photo: bool) -> dict:
    spoken = compact(text)
    if wants_next(spoken):
        return {
            "speak": "好，看下一家。",
            "hud": "下一家",
            "tool": "next",
        }
    if wants_look(spoken):
        return {
            "speak": "好，我看一下眼前。",
            "hud": "看一眼",
            "tool": "look",
        }
    if spoken in {"你好", "在吗", "嗨", "喂", "乐奇", "开始"}:
        return {
            "speak": "我在。你可以直接问我问题，也可以说看店让我看眼前。",
            "hud": "我在听",
            "tool": "none",
        }
    heard = spoken[:24] or "你说的话"
    extra = "眼前已经拍过一张，你也可以继续问。" if has_photo else "需要看眼前时再说看店。"
    return {
        "speak": f"我听到「{heard}」。大模型还没接上，先只能简单聊。{extra}",
        "hud": heard[:16],
        "tool": "none",
    }


def parse_agent_json(raw: str) -> dict | None:
    match = re.search(r"\{.*\}", raw, re.S)
    if not match:
        return None
    try:
        data = json.loads(match.group(0))
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    speak = str(data.get("speak") or data.get("answer") or "").strip()
    if not speak:
        return None
    tool = str(data.get("tool") or data.get("cmd") or "none")
    if tool not in {"none", "look", "next"}:
        tool = "none"
    hud = compact(str(data.get("hud") or speak))[:16]
    return {"speak": speak[:80], "hud": hud, "tool": tool}


def chat_messages(question: str, has_photo: bool) -> list[dict[str, str]]:
    prefix = "（眼前有一张刚拍的照片）" if has_photo else ""
    messages = []
    for turn in _history[-12:]:
        messages.append({"role": turn["role"], "content": turn["content"]})
    messages.append({"role": "user", "content": prefix + question})
    return messages


def llm_anthropic(question: str, has_photo: bool) -> dict | None:
    if "anthropic" in _disabled:
        return None
    base = (os.environ.get("ANTHROPIC_BASE_URL") or "https://api.anthropic.com").rstrip("/")
    token = os.environ.get("ANTHROPIC_AUTH_TOKEN") or os.environ.get("ANTHROPIC_API_KEY") or ""
    if not token:
        return None
    body = json.dumps(
        {
            "model": os.environ.get("ANTHROPIC_MODEL") or "claude-sonnet-4-5",
            "max_tokens": 280,
            "system": SYSTEM,
            "messages": [
                {"role": item["role"], "content": item["content"]}
                for item in chat_messages(question, has_photo)
                if item["role"] in {"user", "assistant"}
            ],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        base + "/v1/messages",
        data=body,
        headers={
            "Content-Type": "application/json",
            "x-api-key": token,
            "anthropic-version": "2023-06-01",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=18) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        print(f"AI anthropic http={error.code}", flush=True)
        if error.code in {401, 403, 429} or "额度" in detail:
            _disabled.add("anthropic")
        return None
    except Exception as error:
        print(f"AI anthropic {type(error).__name__}", flush=True)
        return None
    chunks = [
        block.get("text") or ""
        for block in payload.get("content") or []
        if block.get("type") == "text"
    ]
    return parse_agent_json("\n".join(chunks))


def llm_openai(question: str, has_photo: bool) -> dict | None:
    if "openai" in _disabled:
        return None
    token = os.environ.get("OPENAI_API_KEY") or ""
    base = (os.environ.get("OPENAI_BASE_URL") or "").rstrip("/")
    if not token:
        # same proxy sometimes exposes OpenAI schema
        token = os.environ.get("ANTHROPIC_AUTH_TOKEN") or ""
        base = base or (os.environ.get("ANTHROPIC_BASE_URL") or "").rstrip("/")
    if not token or not base:
        return None
    body = json.dumps(
        {
            "model": os.environ.get("OPENAI_MODEL") or "gpt-4o-mini",
            "max_tokens": 280,
            "messages": [{"role": "system", "content": SYSTEM}]
            + chat_messages(question, has_photo),
        },
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        base + "/v1/chat/completions",
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=18) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        print(f"AI openai http={error.code}", flush=True)
        if error.code in {401, 403, 429} or "额度" in detail:
            _disabled.add("openai")
        return None
    except Exception as error:
        print(f"AI openai {type(error).__name__}", flush=True)
        return None
    raw = (
        (((payload.get("choices") or [{}])[0].get("message") or {}).get("content"))
        or ""
    )
    return parse_agent_json(raw)


def llm_turn(question: str, has_photo: bool) -> dict | None:
    return llm_anthropic(question, has_photo) or llm_openai(question, has_photo)


def synthesize(text: str) -> bool:
    spoken = (text or "").strip()
    if not spoken:
        return False
    WAV.parent.mkdir(parents=True, exist_ok=True)
    tmp = WAV.with_suffix(".tmp.wav")
    try:
        subprocess.run(
            [
                "say",
                "-v",
                "Tingting",
                "-o",
                str(tmp),
                "--data-format=LEI16@16000",
                spoken,
            ],
            check=True,
            timeout=20,
            capture_output=True,
        )
        tmp.replace(WAV)
        return WAV.exists() and WAV.stat().st_size > 44
    except Exception as error:
        print(f"TTS failed {type(error).__name__}", flush=True)
        return False


def decide(text: str, has_photo: bool) -> dict:
    result = llm_turn(text, has_photo) or fallback(text, has_photo)
    tool = result.get("tool") or "none"
    if tool not in {"none", "look", "next"}:
        tool = "none"
    speak = (result.get("speak") or "我在听。")[:80]
    hud = compact(result.get("hud") or speak)[:16]
    audio = synthesize(speak)
    payload = {
        "speak": speak,
        "hud": hud,
        "cmd": tool,
        "tool": tool,
        "question": "",
        "audio": audio,
    }
    _history.append({"role": "user", "content": text})
    _history.append({"role": "assistant", "content": speak})
    save_session()
    return payload


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send(
                200,
                {
                    "ok": True,
                    "turns": len(_history),
                    "llm": "on" if not _disabled else f"off:{','.join(sorted(_disabled))}",
                },
                "application/json",
            )
            return
        if path in {"/tts.wav", "/tts"}:
            if not WAV.exists():
                self.send_error(404)
                return
            data = WAV.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_error(404)

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        OUT.parent.mkdir(parents=True, exist_ok=True)
        text = raw.decode("utf-8", errors="replace")
        OUT.write_text(text, encoding="utf-8")
        with LOG.open("a", encoding="utf-8") as handle:
            handle.write(text.rstrip() + "\n")
        spoken = ""
        has_photo = False
        try:
            payload = json.loads(text)
            spoken = payload.get("text") or ""
            has_photo = bool(payload.get("hasPhoto"))
        except json.JSONDecodeError:
            spoken = text
        print(f"UTTERANCE {spoken}", flush=True)
        result = decide(spoken, has_photo)
        print(
            f"AI_REPLY tool={result['tool']} hud={result['hud']} speak={result['speak']} audio={result['audio']}",
            flush=True,
        )
        self._send(200, result, "application/json")

    def _send(self, code: int, payload: dict, content_type: str) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:
        return


if __name__ == "__main__":
    load_dotenv()
    load_session()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"AI chat host listening on 0.0.0.0:{PORT}", flush=True)
    server.serve_forever()
