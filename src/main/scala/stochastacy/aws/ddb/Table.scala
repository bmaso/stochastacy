package stochastacy.aws.ddb

/**
 * A `Table` is a graph builder that builds a graph simulating the resource consumption of
 * a DynamoDB (DDB) table. In an actual DDB table for each of the different types of read and
 * write operations is _highly_ dependent on two factors:
 *
 * 2. **The contents of the table at operation time**. For example, during a Scan operation an empty table
 *   consumes far fewer read units than a multi-Tb table.
 * 1. **The table provisioning**. Tables may have OnDemand provisioning, or be provisioned with a certain
 *   number of read units and write units.
 *
 * ## Stochastic modeling of table state and interaction use-cases
 *
 * Rather than trying to maintain any version of a table's actual contents, a `Table` instance models
 * table contents stochastically. There are a fixed and finite number of use-cases requiring interactions
 * with the table in the types of real-world systems we want this library to simulate. Each
 * use-case has its own performance profile, which can be modeled stochastically.
 *
 * For example, imagine a table that maintains information about a fleet of IoT devices: the table
 * has one record per device, indexed on a unique deviceId. Each device has a location identifier string,
 * which encodes a hierarchy of more and more specific location indicators from right-to-left (eg,
 * "us-california-orange_county-capo_beach", used as a key in a secondary index. The table also maintains
 * an "active/inactive" flag and a timestamp when the device last contacted a central server.
 *
 * One use-case for interacting with this table is simple GetItem, which retrieves the device record
 * for a deviceId. Instances of this interaction type consume one read unit and consume 1k of response
 * network traffic 99% of the time, irrespective of the number of devices stored in the table. We model this
 * simple use-case with a binomial distribution with a $p$ value of 0.99.
 *
 * Another use case for this table is a query for all the active IoT devices in a local area. The number
 * of read units consumed by a query in this use-case depends on the size of the table, which may
 * grow over time as more devices are added to the table. We can imagine the number of records
 * associated with each locality varies. To model this case we might choose to maintain some estimate
 * of the record count within the table (described below). Further we would use real-world
 * observations to construct a statistical model of read units consumed per interaction, or we might make
 * an educated wish that the read units consumed by this interaction type follows a
 * Poisson or possibly Beta-Poisson distribution, with the $\beta$ and $\lambda$ parameters dependent
 * on the table record count estimation.
 *
 * A concrete `Table` instance includes a model of essential metrics describing the table state, such
 * as an estimate of the record count. This is called the table's _estimated state metrics_.
 *
 * For each anticipated query, write, and delete interaction use-case a `Table` instance provides a
 * statistical model for the resources consumed by each interaction, as well as a method updating the
 * table's estimated state metrics. The statistical model may be informed by the estimated state metrics,
 * which provides for evolution of the model as the table contents grows, shrinks, or undergoes
 * whatever qualitative change one would want to model.
 *
 * ## AWS table provisioning
 *
 * The resources consumed by interactions are highly dependent on table provisioning within AWS. Tables
 * may be OnDemand, or be provisioned with a specific number of read and write units. OnDemand has higher
 * cost per read and write unit, but doesn't suffer usage throttling. Provisioning a table reduces
 * per read unit and write unit costs, but does introduce throttling. This `Table` class includes
 * an implementation of DDB's throttling logic, including DDB's "boost capacity" feature, which
 * avoids throttling when the table experiences short-term interaction traffic spikes.
 *
 * DDB auto-scaling will automatically raise and lower a table's read and write unit provisioning when
 * certain threshholds are met. This library includes an implementation of the AWS auto-scaling algorithm.
 */
class Table
