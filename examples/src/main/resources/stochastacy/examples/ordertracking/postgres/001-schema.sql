create schema if not exists stochastacy_demo;

create table if not exists stochastacy_demo.demo_batches (
  batch_id text primary key,
  scenario_id text not null,
  trial_count integer not null,
  parallelism integer not null,
  simulation_ticks bigint not null,
  base_seed bigint not null,
  read_consistency text not null,
  table_name text not null,
  generated_at timestamp with time zone not null default current_timestamp,
  source_jsonl_path text null
);

create table if not exists stochastacy_demo.demo_records (
  batch_id text not null references stochastacy_demo.demo_batches(batch_id),
  record_type text not null,
  scenario_id text not null,
  trial_id integer null,
  tick bigint null,
  metric text not null,
  statistic text null,
  "value" numeric not null
);

create index if not exists demo_records_batch_type_metric_stat_tick_idx
  on stochastacy_demo.demo_records(batch_id, record_type, metric, statistic, tick);

create index if not exists demo_records_batch_type_metric_trial_idx
  on stochastacy_demo.demo_records(batch_id, record_type, metric, trial_id);

create index if not exists demo_records_batch_scenario_idx
  on stochastacy_demo.demo_records(batch_id, scenario_id);

create or replace view stochastacy_demo.aggregate_time_series as
select *
from stochastacy_demo.demo_records
where record_type = 'aggregate-time-series';

create or replace view stochastacy_demo.aggregate_summary as
select *
from stochastacy_demo.demo_records
where record_type = 'aggregate-summary';

create or replace view stochastacy_demo.trial_time_series as
select *
from stochastacy_demo.demo_records
where record_type = 'trial-time-series';

create or replace view stochastacy_demo.trial_summary as
select *
from stochastacy_demo.demo_records
where record_type = 'trial-summary';
