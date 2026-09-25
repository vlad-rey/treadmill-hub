# 0004. Calories: from the treadmill and our own calculation

Status: accepted, 2026-09-25

## Context

The treadmill reports energy expended: FTMS `Total Energy` (kcal) and, apparently, FitShow field `0x51` in tenths of a kcal. There's a suspicion that the treadmill doesn't account for incline or the user's weight (it doesn't know it).

## Decision

Show **both values** side by side:

1. **"Treadmill"** — as reported by FTMS (or, if more precise, from FitShow, ×0.1).
2. **"Calculated"** — using ACSM metabolic equations, computed every second from the current speed and incline, accounting for the profile's weight.

### ACSM formulas

`S` — speed, m/min; `G` — incline, as a fraction (5% = 0.05). VO₂ — ml/kg/min.

| Mode | VO₂ |
|---|---|
| Walking | `3.5 + 0.1·S + 1.8·S·G` |
| Running | `3.5 + 0.2·S + 0.9·S·G` |

- Walking up to 6.5 km/h, running from 8.0 km/h, linear interpolation in between (mixed-gait zone). Thresholds are configurable.
- kcal/min = VO₂ × weight (kg) / 1000 × 5 (≈ 5 kcal per liter of O₂).
- We show the **total** expenditure (including resting metabolism, as treadmills and watches do). We additionally store the "active" value (excluding the 3.5 ml/kg/min resting component).
- Integrated per sample (1 Hz) — correct even when speed and incline change within a program.

## Consequences

- The profile needs a **weight**. We don't compute from heart rate (owner's decision, 2026-09-25).
- Both values are stored in history; stats show the discrepancy between them.
- Hypothesis to check: does the treadmill's value change for the same workout at 0% versus 10% incline.
