# Changelog

## [0.0.3](https://github.com/alrighdee/BETSY/compare/v0.0.2...v0.0.3) (2026-08-14)

**Prius INF sub-codes through ELM327—no dealer software required.** BETSY 0.0.3 is the first public app we know of that reads and explains the INF sub-code transmitted by a Gen2 Prius directly through an ELM327-compatible adapter. These values provide the detail behind a trouble code—for example, which area of the high-voltage system has an isolation fault.

### Added

* **Toyota INF sub-code support.** BETSY reads the value transmitted in the vehicle's diagnostic freeze-page data and displays its meaning.
* **Direct ELM327 access.** No laptop or proprietary dealer diagnostic software is required.
* **204 documented explanations.** Added explanations for 204 DTC/sub-code combinations across 60 trouble codes.
* **Support for cars with multiple faults.** BETSY attributes values to documented parent trouble codes, shares genuinely equivalent explanations and leaves ambiguous combinations unresolved rather than guessing.
* Plain-language explanations for battery-block, motor, generator, inverter and hybrid-control trouble codes.
* Separate hybrid-control, battery-control, engine, generic stored and generic pending diagnostic observations.

### Changed

* Raw diagnostic pages remain available alongside interpreted sub-code explanations.
* Fault descriptions lead with what failed and what the result means for the owner.
* Shared diagnostic captures include the app build, complete diagnostic sweep and freeze pages.

### Fixed

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
