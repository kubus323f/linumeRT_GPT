# WebHMI audit fixes — 2026-10-28

Base: `aa0b5e3255de3a144a6699e4cb21265113811b90` (fork `kubus323f/linumeRT_GPT`).

## What changed
- Strict Host parsing including bracketed IPv6 with invalid-port rejection.
- Correct inverse work coordinates: subtract tool offset, subtract G5x, undo XY rotation, subtract G92 (G52 shares G92 state).
- Reader captures G92, XY rotation and native machine scale; browser receives linear coordinates and following errors in mm.
- LinuxCNC backend rejects RUN/MDI/JOG in invalid task/interpreter state, and RUN without all axes homed or loaded program.
- Reject HOME/JOG operations with mismatched number of axes and joints; STOP attempts every joint for this mismatch.
- Unit tests cover these regressions.

The work-coordinate transform follows `rotate_and_offset` and `unoffset_and_unrotate_pos` in LinuxCNC's `src/emc/task/emccanon.cc`.

## Limits and safety gates
- Not tested on real hardware; not a safety-rated implementation.
- Mapping of complex kinematics is intentionally refused where joint counts reveal an unsafe assumption. Same-count nontrivial kinematics need a separate capability handshake.
- G68 tilted work planes and other advanced kinematic transforms are outside the implemented transform.
- A process-external watchdog for LinuxCNC continuous jogging is **not implemented**. Before physical motion, independent external E-STOP/watchdog integration and hardware acceptance tests remain mandatory.
- CI tests are necessary but not sufficient for physical deployment.
