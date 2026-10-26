# Malformed inputs

Most of these are **not valid JSON documents** and several are not `.json` files at all. They
exist to test the layer *before* schema validation: the HTTP endpoint's parser, body limits and
content-type handling. Each must produce the outcome below and never surface a stack trace or
parser internals to the caller; a refused one publishes nothing. `MalformedFixturesTest`
(stats-input) sends every file over HTTP and asserts exactly this table.

The last two are the exception: they are well-formed envelopes whose stress sits inside `data`.
Input validates the envelope only, so it accepts and publishes them, and the Writer's payload
gate dead-letters them (`payload_schema`, no retry). Refusing them at Input would move payload
validation into Input, where a 400 is unrecoverable data loss (docs/ARCHITECTURE.md §3).

| File | What it tests | Expected |
|---|---|---|
| `truncated-body.json` | Connection dropped mid-body | 400, parser error not leaked |
| `not-json.txt` | Wrong content entirely | 400 (or 415 if content-type is checked first) |
| `form-encoded.txt` | `application/x-www-form-urlencoded` body | 415 when sent with that content type (Spring's own response, no problem body yet); 400 `malformed_json` when sent as `application/json` |
| `top-level-array.json` | Valid JSON, wrong shape — an array, not an object | 400 |
| `json-null.json` | Valid JSON literal `null` | 400 |
| `duplicate-keys.json` | `uid` appears twice | 400 `malformed_json` — Input parses with strict duplicate detection, so no copy of a member is silently chosen over another |
| `invalid-utf8.json` | A raw `0xE9` byte (Latin-1 `é`) inside a string | 400 `malformed_json`. Input parses the bytes, as the Writer does; it once decoded them to U+FFFD first, answered 200, and the Writer dead-lettered the record |
| `trailing-content.json` | A valid record followed by a second JSON value | 400 `malformed_json` — trailing content is refused, not ignored |
| `deep-nesting.json` | 400 levels of nesting inside `data` | **200**: under the parser's 1,000-level limit, so Input publishes it and the Writer dead-letters it. Past the limit (generated in the test, 5,000 levels) it is 400 `malformed_json`, never a `StackOverflowError` |
| `oversized-string.json` | A 64 KB string inside `data` | **200**: the body is under the 256 KB cap and `data` is the Writer's gate, which dead-letters it. A body over the cap is 413 |

`oversized-string.json` is deliberately only 64 KB so it stays cheap to keep in Git. The real
body-size cap is larger; the load-test harness generates a payload above the configured limit
rather than committing one.
