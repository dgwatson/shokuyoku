package com.mcneilio.shokuyoku;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.mcneilio.shokuyoku.controller.Controller;
import com.mcneilio.shokuyoku.format.Firehose;
import com.mcneilio.shokuyoku.model.*;
import com.mcneilio.shokuyoku.util.DBUtil;
import com.mcneilio.shokuyoku.util.HiveConnector;
import com.mcneilio.shokuyoku.util.ShokuyokuTypes;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.PathTemplateMatch;
import org.apache.hadoop.hive.metastore.api.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.*;
import org.hibernate.Session;
import org.apache.kafka.common.TopicPartition;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

public class Service {
    public Service() {
        verifyEnvironment();
        System.out.println("Shokuyoku will start listening for requests on: " + System.getenv("LISTEN_ADDR") + ":" + System.getenv("LISTEN_PORT"));
        Properties props = new Properties();
        props.put("bootstrap.servers", System.getenv("KAFKA_SERVERS"));
        props.put("acks", "all");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        this.producer = new KafkaProducer<>(props);
        this.controller = new Controller();
    }

    protected void start() throws URISyntaxException, FileNotFoundException {
        LoadBalancingProxyClient loadBalancer = new LoadBalancingProxyClient()
            .addHost(new URI("http://localhost:3000"))
            .setConnectionsPerThread(20);
        ProxyHandler pr = ProxyHandler.builder().setProxyClient(loadBalancer).setMaxRequestTime(30000).build();

        HiveConnector hive = HiveConnector.getConnector();

        Gson gson = new Gson();

        SessionFactory sessionFactory = DBUtil.getSessionFactory();

        Path path = Paths.get((System.getenv("UI_PATH") != null ? System.getenv("UI_PATH"): "ui/build"));
        Path indexPath = Paths.get(path.toString(), "index.html");
        ResourceHandler staticServer = new ResourceHandler(new PathResourceManager(path, 100));
        InputStream in = new BufferedInputStream(new FileInputStream(indexPath.toFile()));
        String indexHTML = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));

        Session writeSession = sessionFactory.openSession();
        Undertow server = Undertow.builder()
            .addHttpListener(Integer.parseInt(System.getenv("LISTEN_PORT")), System.getenv("LISTEN_ADDR"))
            .setHandler(Handlers.path().addPrefixPath("/types", Handlers.routing()
                .get("/", exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(gson.toJson(ShokuyokuTypes.getSupportedTypeStrings()));
                    exchange.getResponseSender().close();
                })
            ).addPrefixPath("/batch_modifiers", Handlers.routing()
                    .post("/", exchange -> {
                        exchange.getRequestReceiver().receiveFullBytes((e, m) -> {

                            BatchModifierRequest createTableRequest = gson.fromJson(new String(m), BatchModifierRequest.class);

                            for (BatchModifierRequest.ColumnModifier modifier: createTableRequest.getModifiers()){
                                EventTypeColumnModifier eventTypeColumnModifier = new EventTypeColumnModifier(new EventTypeColumn.EventTypeColumnKey(modifier.getEvent_type(), modifier.getName()), EventTypeColumnModifier.EventColumnModifierType.valueOf(modifier.getType().toUpperCase()), new Timestamp(System.currentTimeMillis()));
                                Transaction trx = writeSession.beginTransaction();
                                writeSession.persist(eventTypeColumnModifier);
                                trx.commit();
                            }

                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                            exchange.getResponseSender().send(gson.toJson(ShokuyokuTypes.getSupportedTypeStrings()));
                            exchange.getResponseSender().close();
                        });
                    })
                ).addPrefixPath("/deltas", Handlers.routing().get("/event_type", exchange -> {

                Query q = sessionFactory.openSession().createQuery("select et from EventType et", EventType.class);
                List<EventType> list = q.list();

                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(gson.toJson(list));
                exchange.getResponseSender().close();
            }).get("/event_type/{name}", exchange -> {
                String eventType = exchange.getQueryParameters().get("name").getFirst().toString();
                Query q = sessionFactory.openSession().createQuery("select et from EventTypeColumn et where et.name.name = :event_type", EventTypeColumn.class);
                q.setParameter("event_type", eventType);
                List<EventType> list = q.list();

                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(gson.toJson(list));
                exchange.getResponseSender().close();
            })).addPrefixPath("/schemas", Handlers.routing()
                .get("/", exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(hive.listDatabases());
                    exchange.getResponseSender().close();
                })
                .get("/{database}", exchange -> {
                    PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(hive.listTables(params.getParameters().get("database")) + "\n");
                    exchange.getResponseSender().close();
                })
                .post("/{database}", exchange -> {
                    PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                    exchange.getRequestReceiver().receiveFullBytes((e, m) -> {
                        hive.addTable(params.getParameters().get("db"), new String(m));
                    });
                    exchange.getResponseSender().close();
                })
                .get("/{database}/table/{tableName}", exchange -> {
                    PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    if (!params.getParameters().get("tableName").isEmpty()) {
                        String tableStr = hive.getTable(params.getParameters().get("database"), params.getParameters().get("tableName")) + "\n";
                        if (tableStr != null)
                            exchange.getResponseSender().send(tableStr);
                        else {
                            exchange.setStatusCode(404);
                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                            exchange.getResponseSender().send("{}");
                            exchange.getResponseSender().close();
                        }
                    } else
                        exchange.getResponseSender().send("{}\n");
                    exchange.getResponseSender().close();
                })
                .delete("/{database}/table/{tableName}", exchange -> {
                    PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    if (!params.getParameters().get("tableName").isEmpty()) {
                        hive.deleteTable(params.getParameters().get("database"), params.getParameters().get("tableName"));
                    } else
                        exchange.getResponseSender().send("{}\n");
                    exchange.getResponseSender().close();
                })
                .delete("/{database}/table/{tableName}/column/{columnName}", exchange -> {
                    PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    if (!params.getParameters().get("tableName").isEmpty() && !params.getParameters().get("columnName").isEmpty()) {
                        hive.dropColumns(params.getParameters().get("database"), params.getParameters().get("tableName"), new String[]{params.getParameters().get("columnName")});
                    } else
                        exchange.getResponseSender().send("{}\n");
                    exchange.getResponseSender().close();
                })
                .post("/{database}/table/{tableName}", exchange -> {
                    PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getRequestReceiver().receiveFullBytes((e, m) -> {
                        CreateTableRequest createTableRequest = gson.fromJson(new String(m), CreateTableRequest.class);

                        Table tbl = createTableRequest.toHiveTable(params.getParameters().get("database"));

                        try {
                            hive.addTable(tbl);
                        } catch (Exception ex) {
                            System.out.println("Exception Creating table");
                            ex.printStackTrace();
                        }
                        exchange.getResponseSender().send("might have accepted it" + "\n");
                        exchange.getResponseSender().close();
                    });

                })
                    .post("/{database}/table/{tableName}/delete_columns", exchange -> {
                        PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        exchange.getRequestReceiver().receiveFullBytes((e, m) -> {
                            String[] columns = gson.fromJson(new String(m), String[].class);

                            try {
                                hive.dropColumns(params.getParameters().get("database"), params.getParameters().get("tableName"), columns);
                            } catch (Exception ex) {
                                System.out.println("ASD");
                            }
                            exchange.getResponseSender().send("might have accepted it" + "\n");
                            exchange.getResponseSender().close();
                        });

                    })
                .put("/{database}/table/{tableName}", exchange -> {
                    PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getRequestReceiver().receiveFullBytes((e, m) -> {
                        CreateTableRequest createTableRequest = gson.fromJson(new String(m), CreateTableRequest.class);

                        Table tbl = createTableRequest.toHiveTable(params.getParameters().get("database"));

                        try {
                            hive.updateTable(params.getParameters().get("database"), params.getParameters().get("tableName"), tbl);
                        } catch (Exception ex) {
                            System.out.println("Error updating table");
                            ex.printStackTrace();
                        }
                        exchange.getResponseSender().send("might have accepted it" + "\n");
                        exchange.getResponseSender().close();
                    });
                }))
                //.addPrefixPath("/static/", staticServer)
                .addPrefixPath("/", new HttpHandler() {
                @Override
                public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
                    if (httpServerExchange.getRequestMethod().equals(new HttpString("GET"))) {
                        if(System.getenv("DEV_UI")!=null) {
                            pr.handleRequest(httpServerExchange);
                        }else {
                            if (httpServerExchange.getRequestPath().startsWith("/static/")) {
                                staticServer.handleRequest(httpServerExchange);
                            }else{
                                httpServerExchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");
                                httpServerExchange.getResponseSender().send(indexHTML);
                            }
                        }

                        return;
                    }

                    String kafkaTopic = System.getenv("SERVICE_KAFKA_TOPIC") != null ? System.getenv("SERVICE_KAFKA_TOPIC") : System.getenv("KAFKA_TOPIC");

                    httpServerExchange.getRequestReceiver().receiveFullString((httpServerExchange1, s) -> {
                        JSONObject rq = new JSONObject(s);
                        Firehose f = new Firehose(rq.getString("event"), s);
                        producer.send(new ProducerRecord<>(kafkaTopic, f.getByteArray()),
                            (recordMetadata, e) -> {
                                if (e != null) {
                                    System.out.println("Error producing kafka record.");
                                    e.printStackTrace();
                                } else {
                                    System.out.println("Kafka record produced.");
                                }
                            });
                    });
                }
            })
            .addPrefixPath("/kafka", Handlers.routing()
                .get("/offsets", exchange -> {
                    exchange.getResponseSender().send(gson.toJson(controller.getOffsets()));
                    exchange.getResponseSender().close();
                })
                .put("/offsets", exchange -> {
                    exchange.getRequestReceiver().receiveFullBytes((e, m) -> {
                        Type mapType = new TypeToken<Map<TopicPartition, OffsetAndMetadata>>() {}.getType();
                        controller.setOffsets(gson.fromJson(new String(m), mapType));
                        exchange.setStatusCode(202);
                        exchange.getResponseSender().close();
                    });
                })
            ))
            .build();
        server.start();
    }

    private void verifyEnvironment() {
        boolean missingEnv = false;
        if (System.getenv("KAFKA_SERVERS") == null) {
            System.out.println("KAFKA_SERVERS environment variable should contain a comma-separated list of kafka servers. e.g. localhost:9092,localhost:9093");
            missingEnv = true;
        }
        if (System.getenv("KAFKA_TOPIC") == null && System.getenv("SERVICE_KAFKA_TOPIC") == null) {
            System.out.println("KAFKA_TOPIC environment variable should contain the topic to subscribe to. e.g. events");
            missingEnv = true;
        }
        if (System.getenv("LISTEN_ADDR") == null) {
            System.out.println("LISTEN_ADDR environment variable should contain the address to listen on. e.g. localhost");
            missingEnv = true;
        }
        if (System.getenv("LISTEN_PORT") == null) {
            System.out.println("LISTEN_PORT environment variable should contain the port to listen on e.g. 8080");
            missingEnv = true;
        }
        if(System.getenv("KAFKA_GROUP_ID") == null) {
            System.out.println("KAFKA_GROUP_ID environment variable should contain the name of the Kafka group. e.g. shokuyoku");
            missingEnv = true;
        }
        if (missingEnv) {
            System.out.println("Missing required environment variable(s); exiting.");
            System.exit(1);
        }
    }

    private final Producer<String, byte[]> producer;
    private final Controller controller;
}
