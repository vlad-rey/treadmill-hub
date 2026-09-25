# 0003. Workout programs: built-in and custom

Status: accepted, 2026-09-24; refined 2026-09-25 — the hub runs the built-in programs (FitShow is not used)

## Context

The T12B has 12 built-in programs. We need to run both those and custom programs, see progress, and make sure a program doesn't stop if a client disconnects.

## Decision

- **The hub runs custom programs**: on a timer, it sends the treadmill "speed X, incline Y" commands. Format: a list of segments (duration, speed, incline) with repeats.
- **Built-in P1–P12**:
  1. if the protocol allows starting a program by command, we start it on the treadmill and the hub just displays telemetry;
  2. otherwise, we transcribe the program tables from the manual (or capture a profile from the treadmill) and run them on the hub as a regular program. These can be copied and edited.
- If a program is started from the treadmill's own console, the hub still records the workout in history.

## Consequences

- The program engine is part of the hub; the client only displays state.
- Option 2 requires the P1–P12 tables: from the manual or measured directly.

## 2026-09-25 refinement

We don't use the FitShow app or its program-launch protocol. The P1–P8 tables (8 levels × 18 segments) were taken from the T12B manual — `protocol/programs/programs-t12b.json`. The hub runs them as regular programs: the total time is split into 18 equal segments, speed is capped at the profile's limit, and manual correction applies until the end of the segment (as on the console).
