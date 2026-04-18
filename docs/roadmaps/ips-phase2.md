# IPS Phase 2

## Goal

Extend the initial public showing from table-only DynamoDB simulation into index-aware and query-oriented DynamoDB simulation.

## Planned Phase-2 Scope

Phase 2 is expected to include:

- global secondary indexes
- local secondary indexes
- `Query`
- `Scan`
- PartiQL query support

## Likely Architectural Themes

- composed table-plus-index graph structure
- request routing between base table and index targets
- write propagation from table writes into index state
- index-specific resource consumption, metrics, and pricing inputs
- clearer modeling of read paths that are not direct `GetItem` lookups

## Likely Questions To Answer

- should indexes be modeled as separate internal graph components inside a larger table resource?
- how should index state be represented stochastically?
- which read operations should be supported first in phase 2?
- how should table writes affect index state and index-side accounting?
- how should PartiQL map onto the lower-level table and index operation model?

## Starting Assumption

Phase 2 should build on the phase-1 separation that already exists between:

- execution and response generation
- raw consumption events
- additive usage aggregation
- time-based usage aggregation
- pricing

The new phase-2 work should extend those layers rather than bypass them.
