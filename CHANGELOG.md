# Changelog

## 0.0.3

**Toyota diagnostic sub-codes.** BETSY 0.0.3 is the first release that can read and explain the
INF sub-code transmitted by a Gen 2 Prius. These sub-codes provide the detail behind a trouble
code—for example, which area of the high-voltage system has an isolation fault.

### Added

* **Toyota INF sub-code support.** BETSY reads the sub-code from the vehicle's diagnostic
  freeze-frame data and displays its meaning.
* **203 documented explanations.** Added explanations for 203 DTC/sub-code combinations across
  59 trouble codes.
* **Support for cars with multiple faults.** BETSY attributes sub-codes to their documented parent
  trouble codes, shares genuinely equivalent explanations, and leaves ambiguous combinations
  unresolved rather than guessing.
* Plain-language explanations for battery-block, motor, generator, inverter and hybrid-control
  trouble codes.
* Diagnostic results now distinguish hybrid-control, battery-control, engine, generic stored and
  generic pending trouble-code observations.

### Changed

* Raw diagnostic pages remain available alongside the interpreted sub-code explanations.
* Fault descriptions lead with what failed and what the result means for the owner.
* Shared diagnostic captures include the app build, diagnostic sweep and freeze-frame pages while
  removing vehicle-identifying information before the data can leave the phone.

### Fixed

* Multiple stored faults no longer suppress every sub-code explanation or cause one sub-code to be
  attached to unrelated trouble codes.
* Isolation sub-code `526` can correctly coexist with a later localising sub-code such as `611`,
  `612`, `613` or `614`.
* Ambiguous sub-code `123` is not assigned without enough evidence.

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
