package io.vertx.starter;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.github.rjeschke.txtmark.Processor;

public class MainVerticle extends AbstractVerticle {

    private static final String SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id integer identity primary key, Name varchar(255) unique, Content clob)";
    private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name = ?";
    private static final String SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)";
    private static final String SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?";
    private static final String SQL_ALL_PAGES = "select Name from Pages";
    private static final String SQL_DELETE_PAGE = "delete from Pages where Id = ?";
    private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n" + "\n" + "Feel-free to write in Markdown!\n";

    private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

    private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();
    private JDBCClient dbClient;

    @Override
    public void start(Future<Void> startFuture) {
        Future<Void> steps = prepareDatabase().compose(aVoid -> startHttpServer());
        steps.setHandler(startFuture.completer());
    }

    private Future<Void> prepareDatabase() {
        Future<Void> future = Future.future();

        dbClient = JDBCClient.createShared(vertx, new JsonObject().put("url", "jdbc:hsqldb:file:db/wiki")
                .put("driver_class", "org.hsqldb.jdbcDriver").put("max_pool_size", 30));

        dbClient.getConnection(event -> {
            if (event.failed()) {
                LOGGER.error("Could not open a database connection", event.cause());
                future.fail(event.cause());
            } else {
                SQLConnection connection = event.result();
                connection.execute(SQL_CREATE_PAGES_TABLE, create -> {
                    connection.close();
                    if (create.failed()) {
                        LOGGER.error("Database preparation error", create.cause());
                        future.fail(create.cause());
                    } else {
                        future.complete();
                    }
                });
            }
        });

        return future;
    }

    private Future<Void> startHttpServer() {
        Future<Void> future = Future.future();

        HttpServer server = vertx.createHttpServer();

        Router router = Router.router(vertx);
        router.get("/").handler(this::indexHandler);
        router.get("/wiki/:page").handler(this::renderingPageHandler);
        router.post().handler(BodyHandler.create());
        router.post("/save").handler(this::pageUpdateHandler);
        router.post("/create").handler(this::pageCreateHandler);
        router.post("/delete").handler(this::pageDeletionHandler);

        server.requestHandler(router::accept).listen(8090, event -> {
            if (event.succeeded()) {
                LOGGER.info("HTTP Server running on port 8090");
                future.complete();
            } else {
                LOGGER.error("Could not start a HTTP server", event.cause());
                future.fail(event.cause());
            }
        });

        return future;
    }

    private void indexHandler(RoutingContext context) {
        dbClient.getConnection(event -> {
            if (event.succeeded()) {
                SQLConnection connection = event.result();
                connection.query(SQL_ALL_PAGES, res -> {
                    connection.close();

                    if (res.succeeded()) {
                        List<String> pages = res.result().getResults().stream().map(json -> json.getString(0)).sorted()
                                .collect(Collectors.toList());

                        context.put("title", "Wiki home");
                        context.put("pages", pages);

                        templateEngine.render(context, "templates", "/index.ftl", ar -> {
                            if (ar.succeeded()) {
                                context.response().putHeader("Content-Type", "text/html");
                                context.response().end(ar.result());
                            } else {
                                context.fail(ar.cause());
                            }
                        });

                    } else {
                        context.fail(res.cause());
                    }
                });
            } else {
                context.fail(event.cause());
            }
        });
    }

    private void renderingPageHandler(RoutingContext context) {
        String page = context.request().getParam("page");

        dbClient.getConnection(con -> {
            if (con.succeeded()) {
                SQLConnection connection = con.result();
                connection.queryWithParams(SQL_CREATE_PAGE, new JsonArray().add(page), fetch -> {
                    connection.close();

                    if (fetch.succeeded()) {
                        JsonArray row = fetch.result().getResults().stream().findFirst()
                                .orElseGet(() -> new JsonArray().add(-1).add(EMPTY_PAGE_MARKDOWN));
                        Integer id = row.getInteger(0);
                        String rawContent = row.getString(1);

                        context.put("title", page);
                        context.put("id", id);
                        context.put("newPage", fetch.result().getResults());
                        context.put("rawContent", rawContent);
                        context.put("content", Processor.process(rawContent));
                        context.put("timestamp", new Date().toString());

                        templateEngine.render(context, "templates/page.ftl", ar -> {
                            if (ar.succeeded()) {
                                context.response().putHeader("Content-Type", "text/html");
                                context.response().end(ar.result());
                            } else {
                                context.fail(ar.cause());
                            }
                        });
                    } else {
                        context.fail(fetch.cause());
                    }
                });
            } else {
                context.fail(con.cause());
            }
        });
    }

    private void pageUpdateHandler(RoutingContext context) {

    }

    private void pageCreateHandler(RoutingContext context) {

    }

    private void pageDeletionHandler(RoutingContext context) {

    }
}
