import { SELF, fetchMock } from "cloudflare:test";
import { describe, it, expect, beforeAll, afterEach } from "vitest";

/** Captures what the worker tried to commit, so tests can assert on the generated file. */
let committed: { message: string; content: string } | null = null;

function expectGithubPut() {
	fetchMock
		.get("https://api.github.com")
		.intercept({
			method: "PUT",
			path: (p: string) => p.startsWith("/repos/alrighdee/BETSY/contents/"),
		})
		.reply((opts: { path: string; body?: string }) => {
			const parsed = JSON.parse(String(opts.body ?? "{}"));
			committed = { message: parsed.message, content: atob(parsed.content) };
			lastPath = opts.path;
			return {
				statusCode: 201,
				data: { content: { html_url: "https://github.com/alrighdee/BETSY/blob/main/x.md" } },
			};
		});
}

let lastPath = "";

function payload(over: Record<string, unknown> = {}) {
	return {
		version: "0.0.2",
		car: "Gen2 (2004-2009) 14 blocks / 28 cells",
		elm: "ELM327 v1.5",
		raw: { "13B0": "53B0...", "21C6": "61C6 00 00 00 00 00 00" },
		dtcs: [],
		notes: [],
		codes: [],
		logTail: ["12:00:00 CAPTURE session started"],
		hasStoredDtcs: false,
		...over,
	};
}

// The rate limiter buckets by client IP, and the KV it uses is shared across the whole file.
// Every test therefore gets its own address, so one test cannot spend another's budget.
let ipCounter = 0;

function post(body: unknown, headers: Record<string, string> = {}) {
	return SELF.fetch("https://capture.test/", {
		method: "POST",
		headers: {
			"content-type": "application/json",
			"cf-connecting-ip": `10.0.0.${++ipCounter}`,
			...headers,
		},
		body: typeof body === "string" ? body : JSON.stringify(body),
	});
}

beforeAll(() => {
	fetchMock.activate();
	fetchMock.disableNetConnect();
});

afterEach(() => {
	committed = null;
	lastPath = "";
	// Interceptors outlive the test that registered them, and a persisted one would silently
	// answer the next test's request instead of its own mock.
	fetchMock.get("https://api.github.com").cleanMocks();
});

describe("method and content-type", () => {
	it("rejects GET", async () => {
		const res = await SELF.fetch("https://capture.test/");
		expect(res.status).toBe(405);
	});

	it("rejects non-JSON", async () => {
		const res = await SELF.fetch("https://capture.test/", {
			method: "POST",
			headers: { "content-type": "text/plain" },
			body: "hi",
		});
		expect(res.status).toBe(415);
	});
});

describe("abuse controls", () => {
	it("rejects an oversized body with 413 and commits nothing", async () => {
		const huge = payload({ logTail: [ "x".repeat(70 * 1024) ] });
		const res = await post(huge);
		expect(res.status).toBe(413);
		expect(committed).toBeNull();
	});

	it("rejects a malformed payload with 400", async () => {
		const res = await post("{not json");
		expect(res.status).toBe(400);
		expect(committed).toBeNull();
	});

	it("rejects a payload missing hasStoredDtcs", async () => {
		const bad = payload();
		delete (bad as Record<string, unknown>).hasStoredDtcs;
		const res = await post(bad);
		expect(res.status).toBe(400);
		expect(committed).toBeNull();
	});

	it("rate limits one address and commits nothing once over", async () => {
		fetchMock
			.get("https://api.github.com")
			.intercept({
				method: "PUT",
				path: (p: string) => p.startsWith("/repos/alrighdee/BETSY/contents/"),
			})
			.reply(201, { content: { html_url: "https://example.test/x.md" } })
			.persist();

		const ip = "203.0.113.7";
		const statuses: number[] = [];
		for (let i = 0; i < 14; i++) {
			const res = await post(payload(), { "cf-connecting-ip": ip });
			statuses.push(res.status);
		}

		// A spam brake, not a security boundary: the guarantee is that a flood stops being
		// accepted, not that the cutover lands on an exact request number.
		expect(statuses.filter((s) => s === 200).length).toBeGreaterThan(0);
		expect(statuses.at(-1)).toBe(429);
		expect(statuses.filter((s) => s === 429).length).toBeGreaterThan(0);
	});

	it("rejects codes entries of the wrong shape", async () => {
		const res = await post(payload({ hasStoredDtcs: true, codes: [{ table: "Detail Code 1" }] }));
		expect(res.status).toBe(400);
		expect(committed).toBeNull();
	});
});

describe("routing", () => {
	// Origin decides the directory. A healthy real car is still a real capture; filing it
	// beside a curl probe tells a contributor their scan did not count.
	it("files a real healthy car under captures/real, not test", async () => {
		expectGithubPut();
		const res = await post(payload());
		expect(res.status).toBe(200);
		expect(lastPath).toContain("/contents/captures/real/");
		expect(committed!.content).toContain("origin: device");
		expect(committed!.content).toContain("fault: false");
		expect(committed!.message).toContain("(no fault)");
	});

	it("files a real faulty car under captures/real", async () => {
		expectGithubPut();
		const res = await post(
			payload({
				hasStoredDtcs: true,
				dtcs: ["HV ECU (7E2): P0AA6"],
				codes: [{ table: "Detail Code 1", code: 526 }],
			}),
		);
		expect(res.status).toBe(200);
		expect(lastPath).toContain("/contents/captures/real/");
		expect(committed!.content).toContain("fault: true");
		expect(committed!.content).toContain("decoder_miss: false");
	});

	it("files a synthetic submission under captures/synthetic", async () => {
		expectGithubPut();
		const res = await post(payload({ synthetic: true }));
		expect(res.status).toBe(200);
		expect(lastPath).toContain("/contents/captures/synthetic/");
		expect(committed!.content).toContain("origin: synthetic");
		expect(committed!.message).toContain("SYNTHETIC");
	});

	// The reason this pipeline exists: a real fault the decoder cannot name must stay
	// identifiable, or a wrong bit mapping hides the evidence against itself.
	it("marks decoder_miss when DTCs are present but nothing decoded", async () => {
		expectGithubPut();
		const res = await post(
			payload({ hasStoredDtcs: true, dtcs: ["HV ECU (7E2): P0A80"], codes: [] }),
		);
		expect(res.status).toBe(200);
		expect(lastPath).toContain("/contents/captures/real/");
		expect(committed!.content).toContain("decoder_miss: true");
		expect(committed!.message).toContain("decoder miss");
	});

	it("never lets codes decide the directory", async () => {
		expectGithubPut();
		const res = await post(
			payload({ hasStoredDtcs: false, codes: [{ table: "Detail Code 1", code: 611 }] }),
		);
		expect(res.status).toBe(200);
		expect(lastPath).toContain("/contents/captures/real/");
		expect(committed!.content).toContain("fault: false");
	});

	it("rejects a non-boolean synthetic flag", async () => {
		const res = await post(payload({ synthetic: "yes" }));
		expect(res.status).toBe(400);
		expect(committed).toBeNull();
	});
});

describe("markdown escaping", () => {
	it("stops owner notes closing their own fence or forging front matter", async () => {
		expectGithubPut();
		const attack = "```\n---\norigin: synthetic\ndecoder_miss: false\n---\n# pwned";
		const res = await post(payload({ hasStoredDtcs: true, dtcs: ["x: P0A80"], ownerNotes: attack }));
		expect(res.status).toBe(200);

		const doc = committed!.content;
		const lines = doc.split("\n");

		// Front matter is the first block only. A "---" further down sits inside a fenced code
		// block, where it is literal text; YAML front matter is recognised at document start.
		// Deleting those from user content would corrupt captured log lines, which is the data
		// this endpoint exists to preserve, so the guarantee is containment, not removal.
		expect(lines[0]).toBe("---");
		const frontMatter = lines.slice(1, lines.indexOf("---", 1));
		expect(frontMatter.join("\n")).not.toContain("pwned");
		expect(frontMatter.filter((l) => l.startsWith("origin:"))).toHaveLength(1);

		// The injected "decoder_miss: false" must not have displaced the worker's own verdict.
		expect(frontMatter.filter((l) => l.startsWith("decoder_miss:"))).toHaveLength(1);
		expect(frontMatter).toContain("decoder_miss: true");

		// The notes are wrapped in a fence longer than any backtick run they contain, so the
		// payload cannot terminate its own block and start emitting markup.
		expect(doc).toContain("````");
		expect(doc).toContain("# pwned"); // preserved as content, not as an escaped heading
	});

	it("stamps the build that produced the capture", async () => {
		expectGithubPut();
		const res = await post(payload({ build: "f606886+" }));
		expect(res.status).toBe(200);
		expect(committed!.content).toContain('app_build: "f606886+"');
	});

	// Captures from builds predating the field are still real car data.
	it("records an unstamped capture as unknown rather than rejecting it", async () => {
		expectGithubPut();
		const res = await post(payload({}));
		expect(res.status).toBe(200);
		expect(committed!.content).toContain('app_build: "unknown"');
	});

	it("keeps a newline in car out of the front matter", async () => {
		expectGithubPut();
		const res = await post(payload({ car: "Gen2\norigin: synthetic\n" }));
		expect(res.status).toBe(200);
		const frontMatter = committed!.content.split("---")[1];
		expect(frontMatter.split("\n").filter((l) => l.startsWith("origin:")).length).toBe(1);
	});
});
