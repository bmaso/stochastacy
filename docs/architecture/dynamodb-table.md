# DynamoDB Table Architecture

## Overview

A future complete DynamoDB `Table` component should be a composed Pekko graph built from smaller stages with distinct responsibilities. The goal is to model a table the same way the wider simulator models AWS resources: as a timed, stateful stream component that consumes requests and emits synchronous responses, resource-consumption events, and telemetry events.

`TableStage4` is the storage-facing core of that future `Table` simulator. It represents the part of the table that actually touches simulated storage: the place where item existence, item size, table byte totals, and direct read/write physical effects are determined.

## Layering

In the full `Table` component, requests would ideally flow through several conceptual layers before reaching `TableStage4`:

1. Request admission and shaping
2. Provisioned-capacity and throttling logic
3. Burst and adaptive-capacity behavior
4. Data-plane storage execution in `TableStage4`

Earlier layers can model whether a request is delayed, throttled, rejected, or otherwise transformed before it reaches storage. `TableStage4` sits below those concerns. By the time a request arrives here, the simulator should treat it as an operation that has already been admitted to the table's physical data plane.

## Why TableStage4 Exists

This separation is useful because it lets the future `Table` component be composed from simpler Pekko graphs with clear boundaries. `TableStage4` can stay focused on storage semantics and physical effects, while outer stages stay focused on scheduling, admission, and capacity policy.

That makes `TableStage4` the authoritative source of truth for the question: "what would the table itself do with this request if it were allowed to execute?"

## Responsibilities Of TableStage4

`TableStage4` is responsible for:

- inspecting and possibly mutating table state
- producing the synchronous DynamoDB response for an admitted request
- emitting resource-consumption facts caused by servicing the request
- emitting telemetry and metric events that summarize what happened

It is not responsible for account-wide limits, retries, or upstream admission decisions.

## Composition Goal

A complete future `Table` component can therefore be viewed as:

`incoming table request -> admission/capacity stages -> TableStage4 -> response/consumption/telemetry outputs`

This structure keeps the simulator extensible. As the model becomes more realistic, we should be able to add outer stages without needing to redesign `TableStage4` itself.
