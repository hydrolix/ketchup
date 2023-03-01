SELECT
    COUNT(*) AS count,
    toStartOfInterval("EventTime", INTERVAL 12 hour, 'America/Vancouver') AS time,
    MIN("Age") AS min_Age,
    AVG("Age") AS avg_Age,
    MAX("Age") AS max_Age
  FROM clickhouse_sample_data.hits
  WHERE ("EventTime" >= parseDateTimeBestEffort('2013-06-26T23:09:01.830Z') AND "EventTime" <= parseDateTimeBestEffort('2013-08-05T17:24:19.607Z'))
  GROUP BY 2
  SETTINGS enable_positional_arguments = 1