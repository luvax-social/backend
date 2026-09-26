package com.app.testsupport;

/** Testcontainers image pins shared by every integration test, kept aligned with production. */
public final class TestContainerImages {

    public static final String RABBITMQ = "rabbitmq:4.3-alpine";
    public static final String ELASTICSEARCH =
            "docker.elastic.co/elasticsearch/elasticsearch:9.2.5";

    private TestContainerImages() {}
}
