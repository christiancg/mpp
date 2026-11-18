from __future__ import annotations

import sys

import mpp
from tests.structure.source_files import ROOT

sys.path.insert(0, str(ROOT / "build_tools"))

from inject_docs import inject


def test_every_exported_name_resolves_once() -> None:
    assert len(mpp.__all__) == len(set(mpp.__all__))
    assert [name for name in mpp.__all__ if not hasattr(mpp, name)] == []


def test_every_public_class_and_function_has_api_docs() -> None:
    injected = inject()
    assert injected
