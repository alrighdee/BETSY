# Changelog

## [0.0.6](https://github.com/alrighdee/BETSY/compare/v0.0.5...v0.0.6) (2026-08-16)


### Documentation

* drop the demo from the 0.0.5 notes ([11511c9](https://github.com/alrighdee/BETSY/commit/11511c9c9e8ac989cdff629d5f55f797296b14ff))

## [0.0.5](https://github.com/alrighdee/BETSY/compare/v0.0.4...v0.0.5) (2026-08-16)

**Mostly a UX release.** BETSY already read the car correctly. It just did not show it well. The
battery monitor and the code screen have both been redrawn, and nothing about what is read from the
car has changed.

### Added

* **A battery monitor you can read at a glance.** Bars grow from the floor and scale to the current
  spread, so a few millivolts between blocks is visible rather than implied. The live bar rises
  faster than it falls, and the waiting state says it is waiting instead of showing an empty grid.
* **A code screen that leads with the answer.** The trouble code and its sub-code come first, then
  what the controller actually saw, what to look at, and how urgent it is.

### Fixed

* The monitor no longer opens on an empty pack. The first reply was sliced before the block count
  was known, so the first second of every session showed nothing.
* A Back control on the code screen and a Done control on settings, with the update banner's actions
  aligned.
* The pairing hint offers `1234` or `0000`, the codes these adapters actually ship with. It offered
  `6789`, which came from a mockup rather than a dongle.
* Connecting no longer flashes the start screen underneath the screen it just opened.

### Documentation

* The README shows a run of the app and a code with its sub-code explained.

## [0.0.4](https://github.com/alrighdee/BETSY/compare/v0.0.3...v0.0.4) (2026-08-15)


### Added

* scripted debug demo of connect, monitor, sweep and share ([bb6c2ab](https://github.com/alrighdee/BETSY/commit/bb6c2abce50b87b907d07828e877b0b0294acb1f))
* tell sideloaded installs when a newer GitHub release exists ([5635bc4](https://github.com/alrighdee/BETSY/commit/5635bc46094abb92bf49e5dade91856755b3afe0))


### Fixed

* accept the measured 16-byte 21CF combined response ([3cf4a34](https://github.com/alrighdee/BETSY/commit/3cf4a34ed0fe84239252899b01791a069dfee1c8))
* keep stored P0571 on the hybrid ECU in the demo fixture ([7d6dd29](https://github.com/alrighdee/BETSY/commit/7d6dd29dfe128aabea3b57e081f4682eb9004cb1))

## [0.0.3](https://github.com/alrighdee/BETSY/compare/v0.0.2...v0.0.3) (2026-08-14)

**Prius INF sub-codes through ELM327, no dealer software required.** BETSY 0.0.3 is the first public app we know of that reads and explains the INF sub-code transmitted by a Gen2 Prius directly through an ELM327-compatible adapter. These values provide the detail behind a trouble code: for example, which area of the high-voltage system has an isolation fault.

### Added

* **Toyota INF sub-code support.** BETSY reads the value transmitted in the vehicle's diagnostic freeze-page data and displays its meaning.
* **Direct ELM327 access.** No laptop or proprietary dealer diagnostic software is required.
* **294 documented explanations.** Added explanations for every DTC/sub-code combination this project has confirmed, 294 across 78 trouble codes. That includes the motor and generator phase-current sensors, where the sub-code separates a main sensor from its backup and says whether it died, disconnected, disagreed or drifted.
* **Support for cars with multiple faults.** BETSY attributes values to documented parent trouble codes, shares genuinely equivalent explanations and leaves ambiguous combinations unresolved rather than guessing.
* Plain-language explanations for battery-block, motor, generator, inverter and hybrid-control trouble codes.
* Separate hybrid-control, battery-control, engine, generic stored and generic pending diagnostic observations.

### Changed

* Raw diagnostic pages remain available alongside interpreted sub-code explanations.
* Fault descriptions lead with what failed and what the result means for the owner.
* Shared diagnostic captures include the app build, complete diagnostic sweep and freeze pages.

### Fixed
* **Corrected sub-code P0A1D-142.** It was described as an internal hybrid-controller fault, which pointed a repair at the controller. It means the controller is still being told to run after the power switch is off, and the fault is in the wiring or the power source control unit.
* Multiple stored faults no longer suppress every explanation or cause one sub-code to be attached to unrelated trouble codes.
* Isolation sub-code `526` correctly coexists with a later localising value such as `611`, `612`, `613` or `614`.
* Ambiguous sub-code `123` is not assigned without enough evidence.
* Engine-side trouble codes do not produce hybrid-control source-mismatch warnings.
* Vehicle-identifying information is removed before a diagnostic capture can leave the phone.

## [0.0.2](https://github.com/alrighdee/BETSY/compare/v0.0.1...v0.0.2) (2026-08-10)


### Added

* BETSY, a Toyota hybrid battery and trouble-code scanner ([9880156](https://github.com/alrighdee/BETSY/commit/98801567fa9d16a9ba1e5eebecafe4bccde66f42))

## 0.0.2 (2026-08-10)

### Added

* **Share a scan.** The DTC / INF screen can submit a capture to the project in one tap: raw
  responses, app version, detected layout and adapter model, with an optional notes field. A
  first-run screen states exactly what is sent and what is not before anything leaves the phone.
* A failed submission is kept and offered again next launch, so a scan is never lost to a bad
  connection and never needs the car a second time.
* The five INF detail tables are now read on every sweep, including on a healthy car. The
  all-zero result is the baseline that makes a faulty car's result readable.

### Changed

* **No INF bit-to-code mapping is shipped.** The tables are read and recorded verbatim; nothing
  in the data collected so far supports naming a sub-code, so the app names none.
* Captures are filed by origin, `captures/real/` or `captures/synthetic/`, with fault status in
  the file's front matter. Origin and outcome are independent, and a healthy real car is still a
  real capture.
* A layout that answers on `7E2` rather than `7E3` is recognised for capture only. Codes and raw
  tables are read; no live battery value is decoded, because that layout has never been checked
  against a real car.
* Connect screen: adapters that have worked before sort to the top, the transport control shares
  a row with its label, and the firmware chip is hidden when there is nothing to report.

### Fixed

* INF payloads are bounded by the response's declared ISO-TP length. Trailing frame padding was
  being decoded as table data, which would have surfaced as sub-codes that do not exist on any ECU
  padding with something other than zero.
* Rescan visibly rescans instead of redrawing an identical list.
