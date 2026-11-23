# Changelog

## [0.0.10](https://github.com/alrighdee/BETSY/compare/v0.0.9...v0.0.10) (2026-11-23)


### Documentation

* credit the contributor in the 0.0.8 notes ([108688b](https://github.com/alrighdee/BETSY/commit/108688bbe15a15c84af6d0d7b0f7e972b4c4fff1))

## [0.0.9](https://github.com/alrighdee/BETSY/compare/v0.0.8...v0.0.9) (2026-11-23)


### Documentation

* use wire vocabulary for the OEM definition references ([7739315](https://github.com/alrighdee/BETSY/commit/773931503a76987d2e16dae75b7055c18d8f037a))

## [0.0.8](https://github.com/alrighdee/BETSY/compare/v0.0.7...v0.0.8) (2026-11-23)

**Battery decoding corrections and response validation, contributed by
[@KostyaSha](https://github.com/KostyaSha).** Delta SOC, charge and discharge limits, block
temperatures and vehicle speed now follow the OEM unit definitions exactly, and every response is
checked for completeness, framing and adapter errors before a value reaches the screen or a
capture.

### Fixed

* block headerless reads until ATH0 is confirmed ([2721c2f](https://github.com/alrighdee/BETSY/commit/2721c2fff006ccafddc96b80da2bcdcd7d1c5435))
* correct Gen2 CF length and battery response boundaries ([c5ac454](https://github.com/alrighdee/BETSY/commit/c5ac454389d2dd36e416423d1fee213dedfbf55e))
* correct Gen2 Delta SOC scaling ([d652402](https://github.com/alrighdee/BETSY/commit/d652402f896e69ce932004b02aefb911047e4473))
* correct Gen2 Delta SOC scaling ([2e3d63f](https://github.com/alrighdee/BETSY/commit/2e3d63fd4fa1098e7d23e99874af7fd3e96aee30))
* display battery power limits in kilowatts ([2578deb](https://github.com/alrighdee/BETSY/commit/2578debe7d9ddb57d003dae6b4e2ed9408928523))
* display battery power limits in kilowatts ([027b97c](https://github.com/alrighdee/BETSY/commit/027b97c4b3ed87f8187c21f0cc49db136c3bc793))
* distinguish incomplete DTC reads from confirmed empty results ([5f40784](https://github.com/alrighdee/BETSY/commit/5f40784f13f0ba50437a32cfe5e5239a411658a5))
* keep battery polling active when optional speed reads fail ([b8c045b](https://github.com/alrighdee/BETSY/commit/b8c045b4f01f352bf31bda238b2922f92cb26d9b))
* keep INF explanations scoped to their ECU source ([e25373a](https://github.com/alrighdee/BETSY/commit/e25373a9490a448630d771cb595a1ced8f1cd86e))
* mark stale battery readings after polling failures ([b751a09](https://github.com/alrighdee/BETSY/commit/b751a09d5bd533003ddd0f65ff5d4326ac84ce91))
* match Gen2 battery temperatures to the OEM definitions ([c9bf7e0](https://github.com/alrighdee/BETSY/commit/c9bf7e0d3d8b0071b13fa0dc52af3b8031a6bdf6))
* match NHW20 battery temperatures to the OEM definitions ([8045907](https://github.com/alrighdee/BETSY/commit/8045907d36fca4c50e2e525aa80a9c5889cdc969))
* preserve enhanced DTC read completion in capture exports ([8838346](https://github.com/alrighdee/BETSY/commit/883834638edfb86489d733638320d89a3ee4fd32))
* preserve Gen2 current and voltage decimal scaling ([19b4ef2](https://github.com/alrighdee/BETSY/commit/19b4ef2f3b626e3d6c6f0c89b05f36a82a8d6f69))
* preserve INF page read completion in capture exports ([70e091b](https://github.com/alrighdee/BETSY/commit/70e091b6b204329a8fbb06fc32d48feabf03334d))
* preserve mode 02 freeze-frame refusals and PID errors ([fd33611](https://github.com/alrighdee/BETSY/commit/fd33611c13d74227019275b1bb7f7a43113a9cee))
* preserve negative DTC replies and ELM errors in captures ([dc52a4c](https://github.com/alrighdee/BETSY/commit/dc52a4ce73c915263589647b6dad5e50698690aa))
* preserve original DTC and INF replies in captures ([1d10bd0](https://github.com/alrighdee/BETSY/commit/1d10bd0d80c778406caa848e62676a18480095dc))
* preserve partial replies on transport failures ([ac85f7c](https://github.com/alrighdee/BETSY/commit/ac85f7c2a66cdc5d6754f73f1229edcb33e988d9))
* prevent late replies from crossing failed exchanges ([d5e16eb](https://github.com/alrighdee/BETSY/commit/d5e16eb12e9ed23fc3e6f1fd86f522d81790b8b7))
* prevent stale INF replies after transport timeouts ([e1a6823](https://github.com/alrighdee/BETSY/commit/e1a68235db9531682ca08fcf65fb2673836effe9))
* reject ELM error text before decoding PID data ([093ee7b](https://github.com/alrighdee/BETSY/commit/093ee7b6352fd9d1d83ddd1441d00c64fefc285b))
* reject incomplete generic DTC results with extra responder lines ([efb6f56](https://github.com/alrighdee/BETSY/commit/efb6f56422216a08ca97e87709340602d5d59b18))
* reject incomplete hex bytes in diagnostic responses ([76e4db1](https://github.com/alrighdee/BETSY/commit/76e4db1395e6ba5a7d9bd1e8e5a3efff0cfd7253))
* reject truncated counted DTC responses ([752936b](https://github.com/alrighdee/BETSY/commit/752936bb2c7dd2c304ada300db419ca576ae9b98))
* reject truncated Gen2 INF freeze pages ([a09b7e0](https://github.com/alrighdee/BETSY/commit/a09b7e0c805a5ec5fb1389610ef3fa52748ab6b9))
* remove the unsupported inferred battery cell count ([78779e4](https://github.com/alrighdee/BETSY/commit/78779e436f14a5720906a9b99f43ae3b3ca28228))
* retain INF values outside the three-digit range ([f4c263f](https://github.com/alrighdee/BETSY/commit/f4c263f228c82559396df6d08b3e543b63a54c1a))
* stop counted DTC lists at the zero-word marker ([4ff3328](https://github.com/alrighdee/BETSY/commit/4ff3328b990316dc4cf0b2247dddaabee87add1a))
* store vehicle speed in kilometers per hour ([6b8410e](https://github.com/alrighdee/BETSY/commit/6b8410e6ea8bcf9bc58d370af5a3755e53dbf673))
* store vehicle speed in kilometers per hour ([7b6cd48](https://github.com/alrighdee/BETSY/commit/7b6cd48b2f59679877b308303d8b2089be35ea96))
* validate ATH acknowledgements and report restoration failures ([c6801c9](https://github.com/alrighdee/BETSY/commit/c6801c99074299c639e5737ee21572ea87d76c6f))
* validate combined speed and RPM response boundaries ([414eee7](https://github.com/alrighdee/BETSY/commit/414eee7c6c3149c7f57360c58d5ae6bcc75b0e55))
* validate counted DTC response boundaries ([d263f10](https://github.com/alrighdee/BETSY/commit/d263f1018cc6f75b10c83a876aed4dab7f0d7759))
* validate ECU selection acknowledgements ([d8a29ee](https://github.com/alrighdee/BETSY/commit/d8a29ee82a2571ac4cd8a38e7bd975c70e1c2df1))
* validate Gen2 battery response section boundaries ([9bc0895](https://github.com/alrighdee/BETSY/commit/9bc08950864ea5bf57c5e31eacf9b0da2d761120))
* validate Gen2 detection responses before enabling live data ([b36fe80](https://github.com/alrighdee/BETSY/commit/b36fe80d7d4fb6226cb5689c3876d9e012861700))
* validate Gen2 generic DTC single-frame responses ([861fdec](https://github.com/alrighdee/BETSY/commit/861fdec657913a5014d47b1b860a9c06fbbdd5c3))
* validate Gen2 mode 01 PID 00 liveness responses ([f39bd8d](https://github.com/alrighdee/BETSY/commit/f39bd8d2fa5cd318a8425ad9dc5cd5cf5df3b477))
* validate mode 02 freeze-frame PID value lengths ([f1c9060](https://github.com/alrighdee/BETSY/commit/f1c9060dc13160ee11ff22a07280a343daaface9))
* validate the ECU-reported Gen2 battery block count ([ccb1150](https://github.com/alrighdee/BETSY/commit/ccb115093f03a4d07f8b0ba75276e8099594a98d))


### Documentation

* correct INF snapshot field descriptions ([7d7011f](https://github.com/alrighdee/BETSY/commit/7d7011f7c2965bccf5405f3266b504d9d2270c35))
* note the latest release and link the changelog ([3c49efb](https://github.com/alrighdee/BETSY/commit/3c49efb7bbe9bb98017d8164ff7c71c056667ca5))

## [0.0.7](https://github.com/alrighdee/BETSY/compare/v0.0.6...v0.0.7) (2026-10-04)

**More of the car's codes, explained.** Legacy and current names for the same fault now read alike,
and sixteen Gen2 control, interlock and inverter-sensor codes that previously showed only a number
now carry a plain-language explanation.

### Added

* **Legacy aliases and Gen2 explanations, contributed by
  [@KostyaSha](https://github.com/KostyaSha).** Twelve documented aliases let a fault read the same
  under its current and legacy trouble-code name, and sixteen Gen2 immobiliser, transmission,
  crash-signal, interlock, relay and inverter-temperature main codes now carry a plain-language
  explanation instead of showing only a code.

## [0.0.6](https://github.com/alrighdee/BETSY/compare/v0.0.5...v0.0.6) (2026-09-07)

**Ordinary codes now have a name.** Codes this project has not written a paragraph for used to
appear as a bare number. The codes screen now shows the generic OBD-II title. Hybrid explanations
this project writes itself are unchanged.

### Added

* **Generic OBD titles for unexplained codes.** A stored code outside the hybrid table now shows
  its standard OBD-II title instead of a bare number. Hand-written hybrid paragraphs still win
  when both exist.

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
