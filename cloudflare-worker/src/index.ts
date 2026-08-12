/**
 * BETSY capture endpoint.
 *
 * Accepts a scan from the app and commits it to the project repository. There is deliberately no
 * authentication: the whole point is that an owner with a broken car can contribute in one tap.
 * That makes this URL, which ships inside a public APK, an unauthenticated write into a git repo,
 * so every guard below exists to keep a bored stranger from filling it with commits.
 */

interface InfCode {
	table: string;
	code: number;
}

interface CapturePayload {
	version: string;
	/**
	 * Short git identity of the build that produced the capture, e.g. `f606886` or `f606886+` for a
	 * dirty tree. Optional: captures from builds older than this field simply have no stamp, and
	 * rejecting them would discard real car data to enforce bookkeeping.
	 */
	build?: string;
	car: string;
	elm: string;
	/** Every request issued during the sweep mapped to its verbatim response. */
	raw: Record<string, string>;
	dtcs: string[];
	notes: string[];
	/** The app decoder's reading of `raw`. Advisory only, never used for routing. */
	codes: InfCode[];
	logTail: string[];
	hasStoredDtcs: boolean;
	ownerNotes?: string;
	/**
	 * True only for payloads that never came off a car: test harnesses, curl probes, fixtures.
	 * The app always sends false.
	 *
	 * This is origin, not outcome, and the two are independent. A healthy real car is still a
	 * real capture; filing it beside a synthetic probe tells a contributor their scan did not
	 * count. Whether the car had a fault is recorded in the front matter instead.
	 *
	 * Self-declared, so it is a categorisation hint rather than a guarantee. Nothing downstream
	 * should trust it for anything that matters.
	 */
	synthetic?: boolean;
}

interface Env {
	GITHUB_TOKEN: string;
	/** Bound in wrangler.jsonc. Absent only in local tests, where rate limiting is skipped. */
	RATE_LIMIT?: KVNamespace;
}

const MAX_BODY_BYTES = 64 * 1024;
const MAX_PER_HOUR = 12;
const REPO = "alrighdee/BETSY";

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		if (request.method !== "POST") {
			return new Response("POST only", { status: 405 });
		}
		if (!request.headers.get("content-type")?.includes("application/json")) {
			return new Response("JSON only", { status: 415 });
		}

		// Check the declared length first so an oversized body is refused before it is buffered.
		const declared = Number(request.headers.get("content-length") ?? "0");
		if (declared > MAX_BODY_BYTES) {
			return new Response("payload too large", { status: 413 });
		}
		const text = await request.text();
		if (new TextEncoder().encode(text).length > MAX_BODY_BYTES) {
			return new Response("payload too large", { status: 413 });
		}

		const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
		if (await overRateLimit(env, ip)) {
			return new Response("rate limited", { status: 429 });
		}

		let body: CapturePayload;
		try {
			body = JSON.parse(text) as CapturePayload;
		} catch {
			return new Response("invalid JSON", { status: 400 });
		}

		const invalid = validate(body);
		if (invalid) {
			return new Response(invalid, { status: 400 });
		}

		// The directory records ORIGIN: did this come off a car. Whether that car had a fault is
		// a separate axis and lives in the front matter, because the two are independent. Filing
		// a healthy real car as "test" told contributors their scan did not count.
		const synthetic = body.synthetic === true;
		const hasFault = body.hasStoredDtcs === true;

		// Still never keyed on `codes`. The decoder is the thing this pipeline exists to verify,
		// so letting its output classify captures would let a wrong bit mapping bury the very
		// evidence that proves it wrong.
		const decoderMiss = hasFault && body.codes.length === 0;

		const ts = new Date().toISOString().replace(/[:.]/g, "-");
		const path = `captures/${synthetic ? "synthetic" : "real"}/${ts}.md`;
		const content = render(body, { synthetic, hasFault, decoderMiss, ts });

		const message = oneLine(
			`capture: ${synthetic ? "SYNTHETIC " : ""}${body.car}` +
				(hasFault ? (decoderMiss ? " (fault, decoder miss)" : " (fault)") : " (no fault)"),
			100,
		);

		const res = await fetch(`https://api.github.com/repos/${REPO}/contents/${path}`, {
			method: "PUT",
			headers: {
				Authorization: `Bearer ${env.GITHUB_TOKEN}`,
				Accept: "application/vnd.github+json",
				"Content-Type": "application/json",
				"User-Agent": "betsy-capture-worker",
			},
			body: JSON.stringify({ message, content: b64(content) }),
		});

		if (!res.ok) {
			// Never echo GitHub's response back: it can quote the token's own scopes.
			console.error(`github ${res.status}: ${(await res.text()).slice(0, 500)}`);
			return new Response("could not store capture", { status: 502 });
		}

		const file = (await res.json()) as { content?: { html_url?: string } };
		return Response.json({ ok: true, url: file.content?.html_url ?? "" });
	},
};

/**
 * Per-IP hourly cap. KV read-then-write is not atomic, so a determined caller can squeeze a few
 * extra through a race; that is acceptable for a spam brake, which only has to make flooding
 * tedious rather than impossible.
 */
async function overRateLimit(env: Env, ip: string): Promise<boolean> {
	if (!env.RATE_LIMIT) return false;
	const key = `rl:${ip}:${Math.floor(Date.now() / 3_600_000)}`;
	const count = Number((await env.RATE_LIMIT.get(key)) ?? "0");
	if (count >= MAX_PER_HOUR) return true;
	await env.RATE_LIMIT.put(key, String(count + 1), { expirationTtl: 7200 });
	return false;
}

/** Rejects anything that is not the shape the app sends, rather than committing it and hoping. */
function validate(b: CapturePayload): string | null {
	if (typeof b !== "object" || b === null) return "not an object";
	if (typeof b.hasStoredDtcs !== "boolean") return 'missing "hasStoredDtcs"';
	if (typeof b.raw !== "object" || b.raw === null || Array.isArray(b.raw)) return 'missing "raw"';
	for (const [k, v] of Object.entries(b.raw)) {
		if (typeof k !== "string" || typeof v !== "string") return '"raw" must be string->string';
	}
	if (!isStringArray(b.dtcs)) return '"dtcs" must be string[]';
	if (!isStringArray(b.notes)) return '"notes" must be string[]';
	if (!isStringArray(b.logTail)) return '"logTail" must be string[]';
	if (!Array.isArray(b.codes)) return '"codes" must be an array';
	for (const c of b.codes) {
		if (typeof c?.table !== "string" || typeof c?.code !== "number") {
			return '"codes" entries must be {table:string, code:number}';
		}
	}
	for (const f of ["version", "car", "elm"] as const) {
		if (typeof b[f] !== "string") return `missing "${f}"`;
	}
	if (b.ownerNotes !== undefined && typeof b.ownerNotes !== "string") {
		return '"ownerNotes" must be a string';
	}
	if (b.build !== undefined && typeof b.build !== "string") {
		return '"build" must be a string';
	}
	if (b.synthetic !== undefined && typeof b.synthetic !== "boolean") {
		return '"synthetic" must be a boolean';
	}
	return null;
}

function isStringArray(v: unknown): v is string[] {
	return Array.isArray(v) && v.every((x) => typeof x === "string");
}

function render(
	b: CapturePayload,
	meta: { synthetic: boolean; hasFault: boolean; decoderMiss: boolean; ts: string },
): string {
	const lines: string[] = [];

	// Front matter carries only values this worker produced, plus strings put through
	// JSON.stringify. Every field below is attacker-controlled, and a raw newline or a stray
	// `---` would otherwise let a submitter restructure the document.
	lines.push("---");
	lines.push(`captured: ${meta.ts}`);
	lines.push(`origin: ${meta.synthetic ? "synthetic" : "device"}`);
	lines.push(`fault: ${meta.hasFault}`);
	lines.push(`decoder_miss: ${meta.decoderMiss}`);
	lines.push(`car: ${JSON.stringify(oneLine(b.car, 120))}`);
	lines.push(`adapter: ${JSON.stringify(oneLine(b.elm, 120))}`);
	lines.push(`app_version: ${JSON.stringify(oneLine(b.version, 40))}`);
	// app_version only moves when a release is cut, so it cannot distinguish two builds from the
	// same release, which is most of them. app_build names the tree that did the reading.
	lines.push(`app_build: ${JSON.stringify(oneLine(b.build ?? "unknown", 40))}`);
	lines.push("---");
	lines.push("");

	lines.push(
		`# ${meta.synthetic ? "Synthetic capture" : "Capture"}: ${oneLine(b.car, 120)}`,
	);
	lines.push("");

	if (meta.synthetic) {
		lines.push("> Synthetic. Generated by a test harness, not read from a car.");
		lines.push("");
	}
	if (meta.decoderMiss) {
		lines.push(
			"> **Decoder miss.** The car reported stored DTCs and the INF decoder produced nothing.",
		);
		lines.push("> This is direct evidence the bit mapping needs work. See PROTOCOL.md §9.4.0.");
		lines.push("");
	} else if (!meta.hasFault && !meta.synthetic) {
		lines.push(
			"> No stored DTCs. A healthy-car baseline: proof the read path works on this adapter,",
		);
		lines.push("> and the control against which a faulty car's all-zero tables are judged.");
		lines.push("");
	}

	lines.push("## Stored DTCs");
	lines.push(b.dtcs.length ? b.dtcs.map((d) => `- ${oneLine(d, 200)}`).join("\n") : "_none_");
	lines.push("");

	lines.push("## Decoded INF codes (advisory, mapping unverified)");
	lines.push(
		b.codes.length
			? b.codes.map((c) => `- ${oneLine(c.table, 60)}: ${Number(c.code)}`).join("\n")
			: "_none decoded_",
	);
	lines.push("");

	lines.push("## Raw responses");
	lines.push(fenced(Object.entries(b.raw).map(([k, v]) => `${k}  ${v}`).join("\n")));
	lines.push("");

	if (b.notes.length) {
		lines.push("## Read notes");
		lines.push(fenced(b.notes.join("\n")));
		lines.push("");
	}

	if (b.ownerNotes && b.ownerNotes.trim()) {
		lines.push("## What the owner reports");
		lines.push(fenced(b.ownerNotes.slice(0, 4000)));
		lines.push("");
	}

	if (b.logTail.length) {
		lines.push("## Session log (tail)");
		lines.push(fenced(b.logTail.join("\n")));
		lines.push("");
	}

	return lines.join("\n");
}

/** Collapses to a single line and caps length, so a value cannot break out of its context. */
function oneLine(s: string, max: number): string {
	return String(s ?? "")
		.replace(/[\u0000-\u001F\u007F]+/g, " ")
		.replace(/\s+/g, " ")
		.trim()
		.slice(0, max);
}

/**
 * Wraps attacker-controlled text in a fence longer than any backtick run it contains, so the
 * content cannot terminate its own block and start emitting markup.
 */
function fenced(s: string): string {
	const body = String(s ?? "").replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "");
	const longest = (body.match(/`+/g) ?? []).reduce((n, run) => Math.max(n, run.length), 0);
	const fence = "`".repeat(Math.max(3, longest + 1));
	return `${fence}\n${body}\n${fence}`;
}

function b64(s: string): string {
	const buf = new TextEncoder().encode(s);
	let bin = "";
	for (let i = 0; i < buf.length; i++) bin += String.fromCharCode(buf[i]);
	return globalThis.btoa(bin);
}
