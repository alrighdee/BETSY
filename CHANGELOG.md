# Changelog

## [0.0.3](https://github.com/alrighdee/BETSY/compare/v0.0.2...v0.0.3) (2026-08-14)


### Added

* attribute sub-codes across multiple faults ([0f426df](https://github.com/alrighdee/BETSY/commit/0f426df7dca9cd6dd2555cc7b2e3aa16355ebed6))
* BETSY, a Toyota hybrid battery and trouble-code scanner ([e61a75e](https://github.com/alrighdee/BETSY/commit/e61a75e91b76023b287a2de785b48cfb64f7fe53))
* capture the sweep and the freeze frame, not the tail ([e8530e8](https://github.com/alrighdee/BETSY/commit/e8530e83b719e8521f3b788076615f4332186639))
* explain diagnostic sub-codes ([79f5926](https://github.com/alrighdee/BETSY/commit/79f59264cd6092f341943ca3169326b799637018))
* explain the battery block codes, which is the point of the app ([dae53e5](https://github.com/alrighdee/BETSY/commit/dae53e52a5261b736f1600eabccae1dd39f57bcd))
* explain the motor, generator and inverter faults ([6704c6e](https://github.com/alrighdee/BETSY/commit/6704c6e15e91626c82353e6fed5cd71ea3fb3106))
* explain what a trouble code actually means ([ab6fcda](https://github.com/alrighdee/BETSY/commit/ab6fcdad1e5603f90fc6fdfda42dc45d076d4cf1))
* Gen2 7E2 liveness, generic OBD, and diagnostic status sheet ([c5d907a](https://github.com/alrighdee/BETSY/commit/c5d907a5416ceefc94a2c8a7ac319d734af4a46b))
* read the INF sub-code the car transmits ([6d7aca2](https://github.com/alrighdee/BETSY/commit/6d7aca2d5f5668a5a0abd4b36b06340c7c6e7790))
* stamp the build that produced a capture ([4273343](https://github.com/alrighdee/BETSY/commit/4273343dcfed31b75b652bad32b7d0d3dae18df2))
* **tools:** find out which ECUs actually answer ([f687cc7](https://github.com/alrighdee/BETSY/commit/f687cc76fe4c1b3dca46bb32abff96f82b0d6987))
* **tools:** on-car probe runners that survive this adapter's Bluetooth behaviour ([d8329ed](https://github.com/alrighdee/BETSY/commit/d8329edbb26268bb53d5640bba427b1c0cb71e21))
* **worker:** flag the capture that can settle page assignment ([1c07cb9](https://github.com/alrighdee/BETSY/commit/1c07cb9a7f3db8b813a720f200c260159b81851f))


### Fixed

* 0100 is a sound Gen2 liveness probe, so drop the 21C6 fallback ([9d515a0](https://github.com/alrighdee/BETSY/commit/9d515a09039f0ed57032fffb16a18be97e266dae))
* describe confirmed sub-code decoding accurately ([1482f3e](https://github.com/alrighdee/BETSY/commit/1482f3eb758ccde83e2ec1113bcb21d8949a90f0))
* do not guess which DTC a sub-code belongs to ([5651089](https://github.com/alrighdee/BETSY/commit/56510898f623189c83b8a8be9906f2b78d19d037))
* keep the source-mismatch note quiet when nothing was at stake ([901b688](https://github.com/alrighdee/BETSY/commit/901b68879c8a5faacbbf92609118ad342fc50856))
* lead with the fault, not the instruction ([6c25cd8](https://github.com/alrighdee/BETSY/commit/6c25cd87d0095618f2428e78f7fc3a8cfb787171))
* say what a fault means, not what it is called ([dfbe5ec](https://github.com/alrighdee/BETSY/commit/dfbe5ec357c89ad2c79b9829b3ced786813a2cd2))
* swallow ATH0 restore failure to prevent ATH1 leakage across sweeps ([3391c50](https://github.com/alrighdee/BETSY/commit/3391c5011ff18c8eec0b0ece3b5bf3d4a43d47db))
* warn about a mis-sourced parent only when battery control reported it ([361af3b](https://github.com/alrighdee/BETSY/commit/361af3bd2f22afa9563392cdeaf27a302ced1cf3))
* **worker:** a published capture no longer calls the sub-code unverified ([3acd2af](https://github.com/alrighdee/BETSY/commit/3acd2af495403c3eff39bf83c90654b6e9a11a5b))
* **worker:** pin the Cloudflare account id ([25a5c46](https://github.com/alrighdee/BETSY/commit/25a5c46b20c651cebcab0e6637860e990e8ebe81))


### Documentation

* add a glossary ([096d935](https://github.com/alrighdee/BETSY/commit/096d93515bc52fad5baf91d7fae14a07a53bac8e))
* draft 0.0.3 release notes ([717e668](https://github.com/alrighdee/BETSY/commit/717e668b7353193420292d1039072b364ffe26fc))
* remove dates from comments and documentation ([a48f673](https://github.com/alrighdee/BETSY/commit/a48f673a1517e848b4f5f9b4aad4f4569dd213ca))
* reword a comment in DtcMeaning ([6eebb59](https://github.com/alrighdee/BETSY/commit/6eebb59840aafa6e5f3ba6a75dcfb702d95130ea))
* state the rules in place of internal spec references ([9a1177e](https://github.com/alrighdee/BETSY/commit/9a1177e9deda5ee15f156b6bdb8b1f1128628396))
* the INF is a value, not a bit, measured against a stored P0571 ([5df2dbd](https://github.com/alrighdee/BETSY/commit/5df2dbd6bc9189bce9076086e8cb233b1c4bd934))
* the sub-code is transmitted, not derived ([693d15d](https://github.com/alrighdee/BETSY/commit/693d15d4736d576c540974356659594265442663))

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
