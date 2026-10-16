import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Consume responses serialized by the MySQL integration test, not hand-written SQL fixtures.
// This library also runs in k6; a data URL lets Node load its ESM without changing package defaults.
const libraryPath = new URL('../lib/bulk-write-benchmark.js', import.meta.url);
const library = await import(
  `data:text/javascript;base64,${readFileSync(libraryPath).toString('base64')}`
);
const responsePath = new URL('../../../build/contracts/bulk-delete-amenity-responses.json', import.meta.url);
let responses;
try {
  responses = JSON.parse(readFileSync(responsePath, 'utf8'));
} catch (error) {
  throw new Error(
    'Run AccommodationAmenityDeleteBenchmarkIntegrationTest.exportsActualResponsesForClientContract first: '
      + fileURLToPath(responsePath),
    { cause: error },
  );
}

const benchmark = library.ACCOMMODATION_AMENITY_DELETE_BENCHMARK;
assert.equal(responses.length, 4, 'both variants and both measurement scopes are required');

for (const variant of ['BEFORE', 'AFTER']) {
  for (const measurement of ['FULL_REPLACEMENT', 'DELETE_ONLY']) {
    const matching = responses.filter(({ data }) => (
      data.variant === variant && data.measurement === measurement
    ));
    assert.equal(matching.length, 1, 'duplicate or missing response');
    const payload = matching[0];
    test(`actual MySQL response passes k6: ${variant}/${measurement}`, () => {
      assert.equal(payload.data.dataset_size, 3);
      assert.equal(library.matchesBulkWriteResponseContract(
        payload, 3, variant, benchmark, measurement,
      ), true);
    });
    if (measurement === 'FULL_REPLACEMENT') {
      test(`rejects the obsolete contract without outbox: ${variant}`, () => {
        const obsolete = structuredClone(payload);
        obsolete.data.operation.hibernate_statements_by_type.INSERT -= 1;
        obsolete.data.operation.hibernate_statements_by_type.TOTAL -= 1;
        assert.equal(library.matchesBulkWriteResponseContract(
          obsolete, 3, variant, benchmark, measurement,
        ), false);
      });
    }
  }
}
