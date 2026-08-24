#!/usr/bin/env python3
"""Compatibility entry: utterance ingest is the AI host."""

from pathlib import Path
import runpy

runpy.run_path(str(Path(__file__).with_name("glass_ai_host.py")), run_name="__main__")
