# stochastacy

A stochastic data generator, utilizing the Pekko Streaming API.

## Concepts

### Streams of TimedEvent

- Each AWS component is modeled as a single Pekko graph stage
  - resource state is maintained internally to the graph component
  - each component is a state machine driven by a totally-ordered series of events

- The _input_ to a graph component is a stream of elements representing individual atomic interactions with the component
  - eg for an AWS DDB table component, the component has an input port for receiving DDB table requests

- Input is segmented sequentially into time windows
  - each request event has a timestamp
  - all events sent within the same time window will appear sequentially in the stream, and they will
    _not_ be interleaved with any events associated with a different time window
  - the _first_ event each time window is a `Tick` event, which "announces" the termination of the previous
    window, and the consummation of a new time window

- Time window size is configured in the consuming component during component creation
  - the component will dynamically verify consecutive `Tick` events occur exactly 1 time period apart

- Each component also has one output port, to which interaction responses are sent
  - each request will have a single response
    - throttling and failure responses, as well as success responses, will be intermixed in the stream
  - like the input stream, the response stream is a stream of events with timestamps, partitioned
    sequentially by time window, where each time window is proceeded by a `Tick` instance

- Note that a request and the associated response may _not_ occur within equivalent time windows

- Each component also has a second output port, to which resource consumption messages are sent
  - these elements represent the resources consumed when generating a response for a request
  - resources consumed includes RCUs/WCUs, burst units, records read/written/deleted from persistent storage,
    bytes read/written/deleted from persistent storage

### TimedEvent use-cases

- In stochastacy's domain, it is assumed we can partition request events into a relatively small, finite set of
  "use-cases" 
- Each use-case is a stochastic behavioral contract, defining stochastically the probability density function
  defining the number/magnitude of resources consumed by requests
- For example, imagine a DynamoDB table used four different ways
  - The four ways:
    - for user access verification with each client request (a GetItem query consuming a fe read units,
      a small number that increases slowly with table record count because of indexes on the table)
    - for a list of user resources that meet some criteria (a Scan query consuming a much more variable number
      of read units per request, dependent on the number of records in the table)
    - to write new user records, which consumes a few write units, and increases the number of records in the
      table with each request
    - there is also a TTL for table records, so there is a background TTL process that consumes read units
      in proportion to the number of records in the table, and consumes a relatively consistent number of write units
      per request
  - Each request is associated with a use-case, which determines the request latency (wall clock time) and other
    resources consumed by the request

## Running the Example Simulations

Each simulation follows a three-step workflow: **generate** (run Monte Carlo trials, write JSONL), **stage** (load JSONL into Postgres), **view** (print a Grafana URL to open in a browser). Docker must be running (`docker compose up -d`) before staging, and Grafana must be reachable for the view URL to work.

---

### 1. Order-Tracking Demo

A simple e-commerce order-tracking table with `GetItem`, `PutItem`, and `Scan` use-cases. Demonstrates the baseline DynamoDB on-demand cost model: per-tick RCU/WCU consumption, storage growth, and cumulative cost trajectory across 100 Monte Carlo trials over a 20 minute window.

```bash
sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase2Bridge generate --batch-id order-tracking-001 --output /tmp/order-tracking-001.jsonl --trial-count 100 --parallelism 8 --simulation-ticks 1200'

sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase2Bridge stage --input /tmp/order-tracking-001.jsonl --batch-id order-tracking-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200'

sbt 'examples/runMain stochastacy.examples.ordertracking.OrderTrackingPhase2Bridge view --batch-id order-tracking-001'
```

---

### 2. Thermostat Fleet — Single Region

An IoT thermostat fleet writing telemetry, serving customer-support queries via GSI, and running periodic fleet-dashboard scans. Demonstrates on-demand throttling under load spikes (morning and evening peaks, random alert storms), hot-partition enforcement, burst capacity rescue, GSI write amplification, LSI item-collection size limits, and dynamic partition topology evolution.

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate --batch-id thermostat-sr-001 --output /tmp/thermostat-sr-001.jsonl --mode single-region --trial-count 100 --parallelism 8 --simulation-ticks 1200'

sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge stage --input /tmp/thermostat-sr-001.jsonl --batch-id thermostat-sr-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200'

sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-sr-001 --mode single-region'
```

---

### 3. Thermostat Fleet — Multi-Region (Global Table)

The same thermostat fleet workload spread across three AWS regions (us-east-1, eu-west-1, ap-southeast-1) as a DynamoDB Global Table. Demonstrates stochastic cross-region replication lag, replicated write capacity unit (rWCU) billing, per-region capacity and cost breakdown, and cross-region data transfer costs.

```bash
sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate --batch-id thermostat-mr-001 --output /tmp/thermostat-mr-001.jsonl --mode multi-region --trial-count 100 --parallelism 8 --simulation-ticks 1200'

sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge stage --input /tmp/thermostat-mr-001.jsonl --batch-id thermostat-mr-001 --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200'

sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-mr-001 --mode multi-region'
```

---

### 4. Thermostat Fleet — Mixed Billing Mode

The thermostat fleet in a single region, running first in on-demand mode then switching to provisioned capacity mid-simulation. Demonstrates the **right-sizing trap**: the table is provisioned at 110% of the observed on-demand mean, which is below the morning-spike peak. Throttles spike immediately after the mode switch, then disappear after the provisioned capacity is scaled up at the two-thirds mark. Grafana panels show billing mode timeline, throttle rate, provisioned vs. consumed capacity utilization, and cost composition (consumption-driven on-demand cost vs. reservation-driven provisioned cost).

```bash
 # Generate                                                                                                                                                            
  sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge generate --batch-id thermostat-fleet-mm-001 --output                                 
  /tmp/thermostat-fleet-mm-001.jsonl --mode mixed-mode --trial-count 100 --parallelism 8 --simulation-ticks 1200'                                                       
                                                                                                                                                                        
  # Stage                                                                                                                                                               
  sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge stage --input /tmp/thermostat-fleet-mm-001.jsonl --batch-id thermostat-fleet-mm-001  
  --db-url jdbc:postgresql://localhost:5432/stochastacy_demo --db-user stochastacy --db-password stochastacy --trial-count 100 --parallelism 8 --simulation-ticks 1200' 
   
  # View                                                                                                                                                                
  sbt 'examples/runMain stochastacy.examples.thermostatfleet.ThermostatFleetBridge view --batch-id thermostat-fleet-mm-001 --mode mixed-mode'
```

---

## DynamoDB Table simulator development curriculum

1. Phase 1 - Table data plane with usecases consisting of `GetItem` and `PutItem` operations 
2. Phase 2 - Table data plane with usecases consisting of _all_ possible table query and write operations
3. Phase 3 - Table data plane as Phase 2, with RCU, WCU, and other resources consumable by the data plane
4. Phase 4 - Table data plane as Phase 3, with _all_ consumable resources and metrics