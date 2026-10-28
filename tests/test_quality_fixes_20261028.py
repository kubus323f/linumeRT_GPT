"""Regression tests for audit fixes; LinuxCNC hardware not required.

SPDX-License-Identifier: GPL-2.0-or-later
"""
from dataclasses import replace
from types import SimpleNamespace

import pytest

from linumert.cnc.model import AxisSnapshot, InterpState, MachineSnapshot, TaskState
from linumert_webhmi.app import _host_only
from linumert_webhmi.backend import CommandError, LinuxCNCBackend
from linumert_webhmi.schema import snapshot_to_dict


@pytest.mark.parametrize("host,expected", [
    ("[::1]:8765", "[::1]"), ("[::1]", "[::1]"),
    ("localhost:8765", "localhost"), ("127.0.0.1:8765", "127.0.0.1"),
    ("[::1]:invalid", ""), ("[::1", ""), ("[::1]:99999", ""),
    ("localhost:bad", ""), ("localhost/evil", ""),
    ("user@localhost:8765", ""), ("::1", ""), ("localhost?q=1", ""),
])
def test_host_header_parsing(host, expected):
    assert _host_only(host) == expected


def snap(**kwargs):
    return MachineSnapshot(
        timestamp=0.0,
        axes=(AxisSnapshot("X", position=100, commanded=100.1, soft_max=300),
              AxisSnapshot("Y", position=200),
              AxisSnapshot("Z", position=-10)), **kwargs)


def test_g5x_g92_tlo_inverses():
    state = snap(work_offset={"X": 20, "Y": 30, "Z": 2},
                 g92_offset={"X": 5, "Y": -4, "Z": 3},
                 tool_offset={"X": 1, "Z": 2})
    assert state.work_position("X") == pytest.approx(74)
    assert state.work_position("Y") == pytest.approx(174)
    assert state.work_position("Z") == pytest.approx(-17)


def test_xy_rotation_inverse():
    # LinuxCNC: machine = rotate(work + G92) + G5x + tool_offset.
    state = snap(work_offset={"X": 10, "Y": 20}, tool_offset={"X": 2},
                 g92_offset={"X": 3, "Y": 4}, rotation_xy=90)
    assert state.work_position("X") == pytest.approx(177)
    assert state.work_position("Y") == pytest.approx(-92)


def test_native_inches_are_converted_to_mm():
    state = snap(linear_units=1 / 25.4, work_offset={"X": 1},
                 g92_offset={"X": 0.5})
    axes = {a["name"]: a for a in snapshot_to_dict(state)["axes"]}
    assert axes["X"]["position"] == pytest.approx(2540)
    assert axes["X"]["work_position"] == pytest.approx(2501.9)
    assert axes["X"]["following_error"] == pytest.approx(2.54)
    assert axes["X"]["soft_max"] == pytest.approx(7620)


def test_unknown_units_are_not_labeled_mm():
    result = snapshot_to_dict(snap(linear_units=float("nan")))
    assert result["axes"][0]["position"] is None
    assert result["axes"][0]["following_error"] is None


def test_nontrivial_joint_count_blocks_motion_but_stops_all():
    calls = []
    reader = SimpleNamespace(_stat=SimpleNamespace(joints=4))
    controller = SimpleNamespace(jog=lambda *a: calls.append(a))
    bridge = LinuxCNCBackend(module=SimpleNamespace(JOG_STOP=123),
                             reader=reader, command=controller)
    bridge._last = snap()
    with pytest.raises(CommandError, match="mapowanie"):
        bridge._joint_index("X")
    with pytest.raises(CommandError, match="mapowanie"):
        bridge.home(None)
    bridge.jog_stop("X")
    assert calls == [(123, True, i) for i in range(4)]


def test_run_refuses_unsafe_state_without_sending_commands():
    calls = []
    reader = SimpleNamespace(_stat=SimpleNamespace())
    controller = SimpleNamespace(
        mode=lambda *a: calls.append(("mode", a)),
        auto=lambda *a: calls.append(("auto", a)))
    bridge = LinuxCNCBackend(module=SimpleNamespace(MODE_AUTO=2, AUTO_RUN=10),
                             reader=reader, command=controller)
    state = snap()
    for scenario in (
        state,
        replace(state, task_state=TaskState.ON),
        replace(state, task_state=TaskState.ON, interp_state=InterpState.READING),
    ):
        bridge._last = scenario
        with pytest.raises(CommandError, match="załadowany program"):
            bridge.run(1)
    assert calls == []


def test_mdi_refuses_estop_without_sending_commands():
    calls = []
    bridge = LinuxCNCBackend(
        module=SimpleNamespace(MODE_MDI=4),
        reader=SimpleNamespace(_stat=SimpleNamespace()),
        command=SimpleNamespace(mode=lambda *a: calls.append(a)))
    bridge._last = snap()
    with pytest.raises(CommandError, match="ON i IDLE"):
        bridge.mdi("G0 X1")
    assert calls == []
