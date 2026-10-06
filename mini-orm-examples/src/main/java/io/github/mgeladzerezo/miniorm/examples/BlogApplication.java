package io.github.mgeladzerezo.miniorm.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mgeladzerezo.miniorm.MiniOrm;
import io.github.mgeladzerezo.miniorm.examples.Domain.Author;
import io.github.mgeladzerezo.miniorm.examples.Domain.Comment;
import io.github.mgeladzerezo.miniorm.examples.Domain.Post;
import io.github.mgeladzerezo.miniorm.pool.MiniPool;
import io.github.mgeladzerezo.miniorm.pool.PoolConfig;
import io.github.mgeladzerezo.miniorm.pool.PoolMetrics;
import io.github.mgeladzerezo.miniorm.query.Criteria;
import io.github.mgeladzerezo.miniorm.query.Sort;
import io.github.mgeladzerezo.miniorm.sql.SqlEvent;
import io.github.mgeladzerezo.miniorm.sql.SqlRecorder;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A small blog backed by mini-orm and its pool. It prints every SQL statement it runs and
 * serves a page with live pool metrics and the recent SQL log.
 *
 * <p>Configuration through environment variables: {@code JDBC_URL} (default: in-memory H2),
 * {@code JDBC_USER}, {@code JDBC_PASSWORD}, {@code PORT} (default 8204).
 */
public final class BlogApplication {

    private final MiniPool pool;
    private final MiniOrm orm;
    private final SqlRecorder recorder = new SqlRecorder(200);

    private BlogApplication(String url, String user, String password) {
        PoolConfig.Builder config = PoolConfig.builder().jdbcUrl(url).maxSize(8).minIdle(2)
                .leakDetectionThreshold(Duration.ofSeconds(10));
        if (user != null) {
            config.username(user).password(password);
        }
        this.pool = new MiniPool(config.build());
        this.orm = MiniOrm.builder().dataSource(pool).entities(Author.class, Post.class, Comment.class)
                .logSql(System.out::println, Duration.ofMillis(200)).listener(recorder).build();
    }

    /**
     * Starts the application.
     *
     * @param args ignored
     * @throws IOException if the port cannot be bound
     */
    public static void main(String[] args) throws IOException {
        String url = System.getenv().getOrDefault("JDBC_URL", "jdbc:h2:mem:blog;DB_CLOSE_DELAY=-1");
        BlogApplication app = new BlogApplication(url, System.getenv("JDBC_USER"), System.getenv("JDBC_PASSWORD"));
        app.prepareSchemaAndData();
        app.serve(Integer.parseInt(System.getenv().getOrDefault("PORT", "8204")));
    }

    private void prepareSchemaAndData() {
        if (!orm.schema().diff().isEmpty()) {
            orm.schema().drop();
            orm.schema().create();
        }
        orm.runInTransaction(session -> {
            if (session.from(Author.class).exists()) {
                return;
            }
            Author ada = new Author("Ada");
            Author linus = new Author("Linus");
            session.persist(ada);
            session.persist(linus);
            Post first = new Post(ada, "Notes on the Analytical Engine", "Operations, not numbers.");
            Post second = new Post(linus, "Why batching matters", "One round trip beats fifty.");
            session.persist(first);
            session.persist(second);
            session.persist(new Comment(first, "Ahead of her time."));
            session.persist(new Comment(second, "Measure before you tune."));
        });
    }

    private void serve(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/posts", this::posts);
        server.createContext("/api/metrics", exchange -> respond(exchange, 200, "application/json", metricsJson()));
        server.createContext("/api/sql", exchange -> respond(exchange, 200, "application/json", sqlJson()));
        server.createContext("/api/health", exchange -> respond(exchange, 200, "application/json", "{\"status\":\"UP\"}"));
        server.createContext("/", this::staticFile);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            pool.close();
        }));
        server.start();
        System.out.println("Blog example listening on http://localhost:" + port);
    }

    // ------------------------------------------------------------------ endpoints

    private void posts(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "application/json", postsJson());
            } else if (path.equals("/api/posts")) {
                Map<String, String> form = form(exchange);
                orm.runInTransaction(session -> {
                    Author author = session.from(Author.class).where(Author::getName, Criteria.eq(form.get("author")))
                            .first().orElseGet(() -> {
                                Author created = new Author(form.get("author"));
                                session.persist(created);
                                return created;
                            });
                    session.persist(new Post(author, form.get("title"), form.get("body")));
                });
                respond(exchange, 201, "application/json", "{\"created\":true}");
            } else {
                long postId = Long.parseLong(path.substring("/api/posts/".length(), path.lastIndexOf("/comments")));
                Map<String, String> form = form(exchange);
                orm.runInTransaction(session ->
                        session.persist(new Comment(session.getReference(Post.class, postId), form.get("text"))));
                respond(exchange, 201, "application/json", "{\"created\":true}");
            }
        } catch (RuntimeException e) {
            respond(exchange, 400, "application/json", "{\"error\":" + quote(String.valueOf(e.getMessage())) + "}");
        }
    }

    /** Two statements for the whole page: the posts with their authors joined, then all comments by IN. */
    private String postsJson() {
        return orm.inSession(session -> {
            List<Post> posts = session.from(Post.class).fetch(Post::getAuthor).fetch(Post::getComments)
                    .orderBy(Sort.desc(Post::getCreatedAt)).list();
            return posts.stream().map(post -> "{\"id\":" + post.getId() + ",\"title\":" + quote(post.getTitle())
                    + ",\"body\":" + quote(post.getBody()) + ",\"author\":" + quote(post.getAuthor().getName())
                    + ",\"version\":" + post.getVersion() + ",\"comments\":["
                    + post.getComments().stream().map(c -> quote(c.getText())).collect(Collectors.joining(","))
                    + "]}").collect(Collectors.joining(",", "[", "]"));
        });
    }

    private String metricsJson() {
        PoolMetrics m = pool.metrics();
        return "{\"active\":" + m.active() + ",\"idle\":" + m.idle() + ",\"waiting\":" + m.waiting()
                + ",\"total\":" + m.total() + ",\"maxSize\":" + m.maxSize() + ",\"acquired\":" + m.acquired()
                + ",\"timeouts\":" + m.acquireTimeouts() + ",\"created\":" + m.connectionsCreated()
                + ",\"leaks\":" + m.leaksDetected() + ",\"acquireP50Micros\":"
                + m.acquireTime().percentileNanos(0.5) / 1000 + ",\"acquireP99Micros\":"
                + m.acquireTime().percentileNanos(0.99) / 1000 + "}";
    }

    private String sqlJson() {
        List<SqlEvent> recent = recorder.recent();
        return recent.reversed().stream().map(e -> quote(e.describe())).collect(Collectors.joining(",", "[", "]"));
    }

    // ------------------------------------------------------------------ plumbing

    private void staticFile(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String resource = "/static" + (path.equals("/") ? "/index.html" : path);
        try (InputStream in = BlogApplication.class.getResourceAsStream(resource)) {
            if (in == null || path.contains("..")) {
                respond(exchange, 404, "text/plain", "not found");
                return;
            }
            String type = resource.endsWith(".js") ? "text/javascript" : resource.endsWith(".css") ? "text/css"
                    : "text/html; charset=utf-8";
            respond(exchange, 200, type, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        Map<String, String> values = new HashMap<>();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : (text == null ? "" : text).toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }
}
