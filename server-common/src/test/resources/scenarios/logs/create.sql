create table logs (
  agent varchar(255),
  bytes UInt64,
  clientip varchar(32),
  extension varchar(16),
  geo Tuple (
    srcdest char(5),
    src char(2),
    dest char(2),
    coordinates Tuple (
      lat double,
      lon double
    )
  ),
  host varchar(128),
  `index` varchar(64),
  ip varchar(32),
  machine Tuple (
    ram UInt64,
    os varchar(32)
  ),
  memory Int64,
  message varchar(1024),
  phpmemory Int64,
  referer varchar(255),
  request varchar(255),
  response UInt32,
  tags Array(varchar(32)),
  timestamp varchar(64),
  url varchar(512),
  utc_time varchar(64),
  event Tuple (
    dataset varchar(64)
  ),
  time timestamp materialized parseDateTime64BestEffort(timestamp)
) engine=MergeTree order by time;
