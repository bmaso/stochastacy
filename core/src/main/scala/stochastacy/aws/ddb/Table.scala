package stochastacy.aws.ddb

/**
 * A `Table[X]` is a graph builder that builds a graph simulating the resource consumption of
 * a DynamoDB (DDB) table. A table component has one input stream and two output streams:
 * * An input stream of DynamoDB requests
 * * An output stream of DynamoDB responses
 * * An output stream of metrics describing resources consumed and operation metrics of the
 *   table over time
 *
 * ## Stochastic modeling of table state and interaction use-cases
 *
 * Rather than trying to maintain a representation of a table's actual contents, a `Table` instance models
 * table contents stochastically. There are a fixed and finite number of use-cases requiring interactions
 * with the table in the types of real-world systems we want this library to simulate. Each
 * use-case has its own stochastically-modeled performance profile.
 *
 * For example, imagine a table that maintains information about a fleet of IoT devices: the table
 * has one record per device, indexed on a unique deviceId. Each device has a location identifier string,
 * which encodes a hierarchy of more and more specific location indicators from right-to-left (eg,
 * "us-california-orange_county-capo_beach", used as a key in a secondary index. The table also maintains
 * an "active/inactive" flag and a timestamp when the device last contacted a central server.
 *
 * One use-case for interacting with this table is simple `GetItem` interaction, which retrieves the device
 * record for a deviceId. Instances of this interaction type successfully retrieve a record 1-2k in size
 * 99% of the time in our imagined system, irrespective of the number of devices stored in the table. We
 * model the table's behavior under this use-case using a binomial distribution with a $p$ value of 0.99.
 * Item size, which affects the RCUs consumed, would similarly be defined by a statistical distribution.
 * The {{GetItemUseCaseStochasticModel}} trait defines the statistical distributions needed to instantiate
 * the stochastic behavior of a table handling a single use-case of GetItem requests.
 *
 * Another use case for this table is a query for all the active IoT devices in a local area. The number
 * of read units consumed by a query in this use-case depends on the size of the table, which may
 * grow over time as more devices are added to the table. We can imagine the number of records
 * associated with each locality varies. The table maintains an estimate of the record count within the
 * table as part of the table state, and the distribution of devices in different locations would follow
 * a statistical distribution as well.
 * 
 * The table internally maintains some statistical models and statistially-derived estimates, such as
 * the table size, the number of records, or the sizes of table records. There is also a facility for
 * defining ad hoc models which can be included in metrics or used to statistically define the responses
 * to individual interaction use-cases.
 *
 * ## AWS table provisioning
 *
 * The resources consumed by interactions are highly dependent on table provisioning within AWS. Tables
 * may be OnDemand, or be provisioned with a specific number of read and write units. OnDemand has higher
 * cost per read and write unit, but doesn't suffer usage throttling. Provisioning a table reduces
 * per read unit and write unit costs, but does introduce throttling. This `Table` class includes
 * an implementation of DDB's throttling logic, including DDB's burst capacity feature, which
 * avoids throttling when the table experiences short-term interaction traffic spikes.
 *
 * DDB auto-scaling, another feature that can be turned on for a Table during provisioning, will automatically
 * raise and lower a table's read and write unit provisioning to target a specific utilization level. This library
 * includes an implementation of the AWS auto-scaling algorithm.
 */
trait Table:
  val impl: Any = ???