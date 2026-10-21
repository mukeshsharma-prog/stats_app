# zra-reader tenant isolation

Run time (UTC): 2026-10-19 04:21:30

Synthetic data only. Bearer tokens/passwords are not recorded. `curl --config -` receives the Authorization header on stdin.

## PASS: Keycloak claims

```sh
./docker/token.sh zra-reader --claims
```

HTTP status: N/A (helper decodes a successfully issued token)

```json
{
  "tenant": "zra_test",
  "aud": "stats-api",
  "roles": [
    "stats.readOnly"
  ]
}
```

## PASS: Load record A

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary @docs/verification/zra-isolation-2026-10-19-requests/A.json http://localhost:8081/internal/stats
```

HTTP status: 200

```json
{"status":"accepted","traceId":"49d48b7f-ba79-4fbc-895b-9b3e7c238544"}
```

## PASS: Load record B

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary @docs/verification/zra-isolation-2026-10-19-requests/B.json http://localhost:8081/internal/stats
```

HTTP status: 200

```json
{"status":"accepted","traceId":"82865fe1-a907-4e57-8306-e0ae907a3740"}
```

## PASS: Load record C

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary @docs/verification/zra-isolation-2026-10-19-requests/C.json http://localhost:8081/internal/stats
```

HTTP status: 200

```json
{"status":"accepted","traceId":"8f23c2d0-a9fa-4e2f-b829-59ac1ddd039e"}
```

## PASS: Persistence prerequisite A

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-A
```

HTTP status: 200

```json
{"uid":"ZRA-44a36ce0c455-A","tenant":"zra_test","maintenant":"zra_test","clsbox":"808A47270048","type":"lorastats","version":1,"ts":{"server":1792383691,"device":1792383691,"converter":1792383691,"datastore":1792383691},"status":["conversion ok"],"data":{"rx_nr_pkts":219,"rx_crc_missing":0,"rx_crc_bad":48,"tx_success":0,"tx_failed":0,"tx_peek":0,"tx_peek_index_zero":0,"tx_override":0,"tx_busy":0,"tx_dequeue_error":0,"tx_type_beacon":0,"chip_comm_error":0,"util_fopen_failed":0,"rx_short_pkt":166,"fsk_received":0,"timespan":1800}}
```

## PASS: Persistence prerequisite B

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-B
```

HTTP status: 200

```json
{"uid":"ZRA-44a36ce0c455-B","tenant":"tenant-alpha","maintenant":"tenant-alpha","clsbox":"F7B3CC03099D","type":"lorastats","version":1,"ts":{"server":1792383691,"device":1792383691,"converter":1792383691,"datastore":1792383691},"status":["conversion ok"],"data":{"rx_nr_pkts":219,"rx_crc_missing":0,"rx_crc_bad":48,"tx_success":0,"tx_failed":0,"tx_peek":0,"tx_peek_index_zero":0,"tx_override":0,"tx_busy":0,"tx_dequeue_error":0,"tx_type_beacon":0,"chip_comm_error":0,"util_fopen_failed":0,"rx_short_pkt":166,"fsk_received":0,"timespan":1800}}
```

## PASS: Persistence prerequisite C

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-C
```

HTTP status: 200

```json
{"uid":"ZRA-44a36ce0c455-C","tenant":"tenant-alpha","maintenant":"zra_test","clsbox":"B6170233F2EB","type":"lorastats","version":1,"ts":{"server":1792383691,"device":1792383691,"converter":1792383691,"datastore":1792383691},"status":["conversion ok"],"data":{"rx_nr_pkts":219,"rx_crc_missing":0,"rx_crc_bad":48,"tx_success":0,"tx_failed":0,"tx_peek":0,"tx_peek_index_zero":0,"tx_override":0,"tx_busy":0,"tx_dequeue_error":0,"tx_type_beacon":0,"chip_comm_error":0,"util_fopen_failed":0,"rx_short_pkt":166,"fsk_received":0,"timespan":1800}}
```

## PASS: List contains only A

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - 'http://localhost:8083/v1/stats/lorastats?from=1792383690&to=1792383692&limit=1000'
```

HTTP status: 200

```json
{"items":[{"uid":"ZRA-44a36ce0c455-A","tenant":"zra_test","maintenant":"zra_test","clsbox":"808A47270048","type":"lorastats","version":1,"ts":{"server":1792383691,"device":1792383691,"converter":1792383691,"datastore":1792383691},"status":["conversion ok"],"data":{"rx_nr_pkts":219,"rx_crc_missing":0,"rx_crc_bad":48,"tx_success":0,"tx_failed":0,"tx_peek":0,"tx_peek_index_zero":0,"tx_override":0,"tx_busy":0,"tx_dequeue_error":0,"tx_type_beacon":0,"chip_comm_error":0,"util_fopen_failed":0,"rx_short_pkt":166,"fsk_received":0,"timespan":1800}}],"nextCursor":null,"limit":1000}
```

## PASS: UID A is visible

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-A
```

HTTP status: 200

```json
{"uid":"ZRA-44a36ce0c455-A","tenant":"zra_test","maintenant":"zra_test","clsbox":"808A47270048","type":"lorastats","version":1,"ts":{"server":1792383691,"device":1792383691,"converter":1792383691,"datastore":1792383691},"status":["conversion ok"],"data":{"rx_nr_pkts":219,"rx_crc_missing":0,"rx_crc_bad":48,"tx_success":0,"tx_failed":0,"tx_peek":0,"tx_peek_index_zero":0,"tx_override":0,"tx_busy":0,"tx_dequeue_error":0,"tx_type_beacon":0,"chip_comm_error":0,"util_fopen_failed":0,"rx_short_pkt":166,"fsk_received":0,"timespan":1800}}
```

## PASS: UID B is hidden

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-B
```

HTTP status: 404

```json
{"type":"https://stats.example/errors/record_not_found","title":"Record not found","status":404,"detail":"No record with that uid exists for this tenant.","instance":"/v1/stats/lorastats/ZRA-44a36ce0c455-B","statType":"lorastats","traceId":"42afacbf-80a9-4845-8610-f1ad37a0eff3"}
```

## PASS: UID C is hidden despite matching maintenant

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-C
```

HTTP status: 404

```json
{"type":"https://stats.example/errors/record_not_found","title":"Record not found","status":404,"detail":"No record with that uid exists for this tenant.","instance":"/v1/stats/lorastats/ZRA-44a36ce0c455-C","statType":"lorastats","traceId":"60f38f67-8437-4cf5-89d4-6c9015fe8f99"}
```

## PASS: Gateway B is empty

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET --config - http://localhost:8083/v1/stats/lorastats/F7B3CC03099D/1792383690
```

HTTP status: 200

```json
{"items":[],"nextCursor":null,"limit":100}
```

## PASS: No token: List contains only A

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET 'http://localhost:8083/v1/stats/lorastats?from=1792383690&to=1792383692&limit=1000'
```

HTTP status: 401

```json
{"type":"https://stats.example/errors/unauthorized","title":"Unauthorized","status":401,"detail":"A valid bearer token is required.","instance":"/v1/stats/lorastats","traceId":"a9dc4363-af9d-4cfc-b703-848916838ba2"}
```

## PASS: No token: UID A is visible

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-A
```

HTTP status: 401

```json
{"type":"https://stats.example/errors/unauthorized","title":"Unauthorized","status":401,"detail":"A valid bearer token is required.","instance":"/v1/stats/lorastats/ZRA-44a36ce0c455-A","traceId":"e1c186c0-a16b-4d29-94bf-1a50c29b9274"}
```

## PASS: No token: UID B is hidden

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-B
```

HTTP status: 401

```json
{"type":"https://stats.example/errors/unauthorized","title":"Unauthorized","status":401,"detail":"A valid bearer token is required.","instance":"/v1/stats/lorastats/ZRA-44a36ce0c455-B","traceId":"07331d74-dba9-4781-bc60-401022d88ad9"}
```

## PASS: No token: UID C is hidden despite matching maintenant

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-C
```

HTTP status: 401

```json
{"type":"https://stats.example/errors/unauthorized","title":"Unauthorized","status":401,"detail":"A valid bearer token is required.","instance":"/v1/stats/lorastats/ZRA-44a36ce0c455-C","traceId":"76b7e7c2-3fec-4ab6-b2d5-9b70225d961b"}
```

## PASS: No token: Gateway B is empty

```sh
curl --silent --show-error --max-time 30 --output '$BODY' --write-out '%{http_code}' -X GET http://localhost:8083/v1/stats/lorastats/F7B3CC03099D/1792383690
```

HTTP status: 401

```json
{"type":"https://stats.example/errors/unauthorized","title":"Unauthorized","status":401,"detail":"A valid bearer token is required.","instance":"/v1/stats/lorastats/F7B3CC03099D/1792383690","traceId":"0fd785ac-47f8-4df7-b7bf-1a8ffe786401"}
```

Result: PASS; 0 failed checks.
