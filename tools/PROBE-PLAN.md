# In-car diagnostic capture plan

BETSY already reads Toyota INF diagnostic sub-codes on the confirmed Gen2 path. The useful work
now is broader vehicle and fault coverage, especially cars carrying several DTCs at once.

Every path below is read-only. The tools reject DTC clearing, ECU reset, security access, session
control, actuator routines and writes before anything is transmitted. Clearing a DTC would destroy
the freeze page and its INF value.

## Preferred: share from the Android app

1. Put the car in READY.
2. Connect BETSY to the paired ELM327 adapter.
3. Open **DTC / INF codes** and let the sweep finish.
4. Check that hybrid-control, battery-control and engine observations are visible.
5. Tap **SHARE THIS SCAN**.
6. If available, add the independent diagnosis or symptoms in the optional notes.

The capture retains every raw response beside the decoded values. Unknown and ambiguous values are
useful; BETSY does not need to recognise a result before it can be shared.

## Desktop capture

Close the Android app first so only one process owns the adapter. The fault-focused probe reuses the
shared resilient session, rebuilds a stale Bluetooth pairing when needed and saves every response
incrementally:

```sh
python3 -u tools/probe_fault.py 2>&1 | tee /tmp/betsy-probe.log
```

For a broader read-only capability sweep:

```sh
python3 -u tools/probe_harvest.py 2>&1 | tee /tmp/betsy-harvest.log
```

Do not pipe either command through `tail`; it can hide live output until the process exits.

## Highest-value cases

- Several hybrid-control DTCs with one or more INF values. This exercises deterministic pair
  attribution without assuming page order.
- A different confirmed `(DTC, INF)` combination from `P0571-115`.
- `P0A1F` or `P3000`, to confirm whether the car reports the parent through hybrid control or
  battery control.
- A Gen2 whose battery data answers on `7E2`, which is implemented for capture but not yet
  vehicle-validated.
- A Gen3 Prius, whose path is implemented against recorded responses but still needs an on-car
  confirmation.

## Reading the result

On Gen2, `21C6` through `21CA` are five freeze pages on the hybrid-control ECU. A populated page
carries the INF value as a big-endian integer at payload bytes 29–30. An all-zero page is empty.

Page position does not identify the parent DTC. BETSY resolves only documented `(DTC, INF)` pairs:

- one matching parent: exact explanation;
- several matching parents with identical detail: one shared explanation;
- no match or conflicting matches: unresolved, with the raw value retained.

An engine code on `7E0` is a separate observation even if the same DTC number also exists in the
hybrid-control explanation table. It must not receive a hybrid INF explanation or a source-mismatch
warning.

See [`docs/PROTOCOL.md`](../docs/PROTOCOL.md#74-inf-detail-codes) for the wire details.
