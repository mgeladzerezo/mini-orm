# The connection pool

`mini-orm-pool` is a `javax.sql.DataSource` with no dependency except the JDK. It can be used
without the ORM.

```java
MiniPool pool = new MiniPool(PoolConfig.builder()
        .jdbcUrl("jdbc:postgresql://localhost/app").username("app").password("secret")
        .maxSize(10).minIdle(2).acquireTimeout(Duration.ofSeconds(5))
        .leakDetectionThreshold(Duration.ofSeconds(30))
        .build());
try (Connection c = pool.getConnection()) { ... }   // close() returns it to the pool
pool.close();                                        // orderly shutdown
```

## Behaviour

* **Bounded and fair.** At most `maxSize` physical connections. Waiting threads are served in
  arrival order (`fair(true)`, default) and `getConnection()` fails with a timeout after
  `acquireTimeout`.
* **Validation on borrow.** A connection idle for longer than `validateAfterIdle` is checked with
  `Connection.isValid` before it is handed out; a dead one is discarded and another is taken, so the
  caller does not see the failure.
* **Housekeeping thread.** Retires connections older than `maxLifetime`, closes idle ones above
  `minIdle` after `idleTimeout`, and tops up to `minIdle`.
* **Leak detection.** A connection held longer than `leakDetectionThreshold` is logged with the
  stack trace of the thread that borrowed it.
* **Proxy connection.** What the caller gets is a proxy. `close()` returns it to the pool, rolls
  back an open transaction, resets autocommit, isolation and read-only, and closes statements the
  caller leaked (statement tracking). Using it after `close()` throws.
* **Broken-connection eviction.** An `SQLException` with a connection-class SQLState (`08xxx`,
  `57P01` and similar, plus `extraFatalSqlStates`) evicts the connection when it is returned.
* **Metrics.** `pool.metrics()` returns active, idle, waiting, totals, timeouts, evictions by
  reason, leaks and an acquire-time histogram with percentiles.

## Tuning knobs

| Setting | Default | Guidance |
| --- | --- | --- |
| `maxSize` | 10 | Start near the number of concurrent transactions you need, not the number of requests. Beyond what the database can run in parallel, more connections only add queueing. |
| `minIdle` | 0 | Set to avoid connection set-up latency on the first requests. |
| `acquireTimeout` | 30 s | Shorter than your request timeout, so overload shows up as an error rather than a hung thread. |
| `validateAfterIdle` | 500 ms | Zero validates on every borrow (safest, one round trip each). |
| `maxLifetime` | 30 min | Shorter than any database or proxy limit on connection age. |
| `idleTimeout` | 10 min | Shrinks the pool after a burst. |
| `leakDetectionThreshold` | off | Turn on in test and staging. |
| `fair` | true | `false` lets a returning thread re-take the connection; higher throughput, no ordering guarantee. |

## Benchmark

`PoolBenchmark` (JMH, test scope, HikariCP as the comparison) measures the cost of the pool itself
against a stub driver without I/O. Command:

```
./mvnw -pl mini-orm-pool -Pbenchmark test-compile exec:exec -Dbenchmark.args="PoolBenchmark -t 8 -p maxSize=8"
```

Results: **not yet measured** in this repository. Do not quote numbers from this document.
