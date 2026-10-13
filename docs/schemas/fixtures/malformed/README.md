# Malformed inputs

These are **not valid JSON documents** and several are not `.json` files at all. They exist to
test the layer *before* schema validation: the HTTP endpoint's parser, body limits and content-type
handling. Every one of them must produce an RFC 9457 `400` (or `413`/`415` where noted), must
publish nothing, and must never surface a stack trace or parser internals to the caller.

| File | What it tests | Expected |
|---|---|---|
| `truncated-body.json` | Connection dropped mid-body | 400, parser error not leaked |
| `not-json.txt` | Wrong content entirely | 400 (or 415 if content-type is checked first) |
| `form-encoded.txt` | `application/x-www-form-urlencoded` body | 415 |
| `top-level-array.json` | Valid JSON, wrong shape — an array, not an object | 400 |
| `json-null.json` | Valid JSON literal `null` | 400 |
| `duplicate-keys.json` | `uid` appears twice | 400 `malformed_json` — Input parses with strict duplicate detection since DL-168, so no copy of a member is silently chosen over another |
| `invalid-utf8.json` | A raw `0xE9` byte (Latin-1 `é`) inside a string | 400 `malformed_json`. Input parses the bytes, as the Writer does; before DL-168 it decoded them to U+FFFD first, answered 200, and the Writer dead-lettered the record |
| `trailing-content.json` | A valid record followed by a second JSON value | 400 `malformed_json` — trailing content is refused, not ignored (DL-168) |
| `deep-nesting.json` | 400 levels of nesting | 400 via a parser depth limit, never a `StackOverflowError` |
| `oversized-string.json` | A 64 KB string field | 400 or 413 depending on where the bound sits |

`oversized-string.json` is deliberately only 64 KB so it stays cheap to keep in Git. The real
body-size cap is larger; the load-test harness generates a payload above the configured limit
rather than committing one.
