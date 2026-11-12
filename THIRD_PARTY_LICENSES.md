> **Ngôn ngữ / Language:** [Tiếng Việt](#tieng-viet) · [English](#english)

<a id="tieng-viet"></a>

# Giấy phép phần mềm bên thứ ba

## Dịch vụ container (Docker)

| Thành phần | Image | Giấy phép | OSI/mở | Ghi chú |
| --- | --- | --- | --- | --- |
| Apache APISIX | `apache/apisix:3.19.0-ubuntu` | Apache-2.0 | Có | API gateway. |
| SeaweedFS | `chrislusf/seaweedfs:3.85` | Apache-2.0 | Có | Object storage và S3 gateway. |
| ClamAV | `clamav/clamav:1.4` | GPL-2.0 | Có | Dịch vụ quét malware độc lập qua mạng. |
| Text Embeddings Inference | `ghcr.io/huggingface/text-embeddings-inference:cpu-1.9` | Apache-2.0 | Có | Profile reranker, tắt mặc định. |
| Grafana | `grafana/grafana:11.3.1` | AGPL-3.0 | Có | Dịch vụ network-isolated, nguyên bản; không liên kết vào OpenVie. |
| PostgreSQL | `postgres:16-alpine` | PostgreSQL License | Có | Cơ sở dữ liệu nghiệp vụ và Keycloak. |
| Alertmanager | `prom/alertmanager:v0.27.0` | Apache-2.0 | Có | Profile monitoring, tắt mặc định. |
| Prometheus | `prom/prometheus:v2.55.1` | Apache-2.0 | Có | Profile monitoring, tắt mặc định. |
| Qdrant | `qdrant/qdrant:v1.19.2` | Apache-2.0 | Có | Vector database. |
| Keycloak | `quay.io/keycloak/keycloak:26.3.3` | Apache-2.0 | Có | Identity provider. |
| RabbitMQ | `rabbitmq:3-management-alpine` | MPL-2.0 | Có | Message broker, dùng nguyên bản qua mạng. |
| Redis | `redis:7.2-alpine` | BSD-3-Clause | Có | Cố ý ghim 7.2.x; Redis ≥7.4 là RSALv2/SSPL. |
| Autoheal | `willfarrell/autoheal:1.2.0` | MIT | Có | Tự phục hồi container. |

## Thành phần xử lý tài liệu

| Thành phần | Nguồn trong repo | Giấy phép | Ghi chú |
| --- | --- | --- | --- |
| Tesseract OCR + vie/eng traineddata | `rag-chatbot-fastapi/Dockerfile` | Apache-2.0 | Được cài trong image để OCR PDF scan/ảnh; `INGESTION_OCR_ENABLED=false` mặc định. |
| faster-whisper | `rag-chatbot-fastapi/pyproject.toml` (`asr` optional extra) | MIT | Chỉ được cài khi người vận hành chọn extra ASR; ASR tắt mặc định. |

## Thư viện sử dụng

### Java — `api/pom.xml`

| Thành phần | Giấy phép | Ghi chú |
| --- | --- | --- |
| (GNU Lesser General Public License) Logback Classic Module (ch.qos.logback:logback-classic:1.5.32 - http://logback.qos.ch/logback-classic) | Eclipse Public License - v 2.0 | Dùng theo EPL-2.0. |
| (GNU Lesser General Public License) Logback Core Module (ch.qos.logback:logback-core:1.5.32 - http://logback.qos.ch/logback-core) | Eclipse Public License - v 2.0 | Dùng theo EPL-2.0. |
| ClassMate (com.fasterxml:classmate:1.7.3 - https://github.com/FasterXML/java-classmate) | Apache License, Version 2.0 | — |
| Jackson-annotations (com.fasterxml.jackson.core:jackson-annotations:2.21 - https://github.com/FasterXML/jackson) | The Apache Software License, Version 2.0 | — |
| Jackson-core (com.fasterxml.jackson.core:jackson-core:2.21.2 - https://github.com/FasterXML/jackson-core) | The Apache Software License, Version 2.0 | — |
| jackson-databind (com.fasterxml.jackson.core:jackson-databind:2.21.2 - https://github.com/FasterXML/jackson) | The Apache Software License, Version 2.0 | — |
| Jackson-dataformat-YAML (com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.2 - https://github.com/FasterXML/jackson-dataformats-text) | The Apache Software License, Version 2.0 | — |
| Jackson datatype: JSR310 (com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.2 - https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310) | The Apache Software License, Version 2.0 | — |
| Java UUID Generator (com.fasterxml.uuid:java-uuid-generator:5.1.0 - https://github.com/cowtowncoder/java-uuid-generator) | The Apache Software License, Version 2.0 | — |
| docker-java-api (com.github.docker-java:docker-java-api:3.7.1 - https://github.com/docker-java/docker-java) | The Apache Software License, Version 2.0 | — |
| docker-java-transport (com.github.docker-java:docker-java-transport:3.7.1 - https://github.com/docker-java/docker-java) | The Apache Software License, Version 2.0 | — |
| docker-java-transport-zerodep (com.github.docker-java:docker-java-transport-zerodep:3.7.1 - https://github.com/docker-java/docker-java) | The Apache Software License, Version 2.0 | — |
| curvesapi (com.github.virtuald:curvesapi:1.08 - https://github.com/virtuald/curvesapi) | BSD License | — |
| Google Android Annotations Library (com.google.android:annotations:4.1.1.4 - http://source.android.com/) | Apache 2.0 | — |
| proto-google-common-protos (com.google.api.grpc:proto-google-common-protos:2.51.0 - https://github.com/googleapis/sdk-platform-java) | Apache-2.0 | — |
| FindBugs-jsr305 (com.google.code.findbugs:jsr305:3.0.2 - http://findbugs.sourceforge.net/) | The Apache Software License, Version 2.0 | — |
| Gson (com.google.code.gson:gson:2.11.0 - https://github.com/google/gson) | Apache-2.0 | — |
| error-prone annotations (com.google.errorprone:error_prone_annotations:2.30.0 - https://errorprone.info/error_prone_annotations) | Apache 2.0 | — |
| Guava InternalFutureFailureAccess and InternalFutures (com.google.guava:failureaccess:1.0.2 - https://github.com/google/guava/failureaccess) | The Apache Software License, Version 2.0 | — |
| Guava: Google Core Libraries for Java (com.google.guava:guava:33.3.1-android - https://github.com/google/guava) | Apache License, Version 2.0 | — |
| Guava ListenableFuture only (com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava - https://github.com/google/guava/listenablefuture) | The Apache Software License, Version 2.0 | — |
| J2ObjC Annotations (com.google.j2objc:j2objc-annotations:3.0.0 - https://github.com/google/j2objc/) | Apache License, Version 2.0 | — |
| Protocol Buffers [Core] (com.google.protobuf:protobuf-java:4.30.2 - https://developers.google.com/protocol-buffers/protobuf-java/) | BSD-3-Clause | — |
| (MPL 2.0) H2 Database Engine (com.h2database:h2:2.4.240 - https://h2database.com) | EPL 1.0 | Chỉ dùng cho kiểm thử; EPL-1.0/MPL-2.0. |
| json-path (com.jayway.jsonpath:json-path:2.10.0 - https://github.com/jayway/JsonPath) | The Apache Software License, Version 2.0 | — |
| Nimbus JOSE+JWT (com.nimbusds:nimbus-jose-jwt:10.4 - https://bitbucket.org/connect2id/nimbus-jose-jwt) | The Apache Software License, Version 2.0 | — |
| (GPL v2) (MPL 2.0) RabbitMQ Java Client (com.rabbitmq:amqp-client:5.27.1 - https://www.rabbitmq.com) | AL 2.0 | Dùng theo Apache-2.0/MPL-2.0. |
| istack common utility code runtime (com.sun.istack:istack-commons-runtime:4.1.2 - https://projects.eclipse.org/projects/ee4j/istack-commons/istack-commons-runtime) | Eclipse Distribution License - v 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| (The Apache Software License, Version 2.0) ArchUnit (com.tngtech.archunit:archunit:1.4.1 - https://github.com/TNG/ArchUnit) | BSD | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5-api:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5-engine:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5-engine-api:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| JSON library from Android SDK (com.vaadin.external.google:android-json:0.0.20131108.vaadin1 - http://developer.android.com/sdk) | Apache License 2.0 | — |
| HikariCP (com.zaxxer:HikariCP:7.0.2 - https://github.com/brettwooldridge/HikariCP) | The Apache Software License, Version 2.0 | — |
| SparseBitSet (com.zaxxer:SparseBitSet:1.3 - https://github.com/brettwooldridge/SparseBitSet) | The Apache Software License, Version 2.0 | — |
| Apache Commons Codec (commons-codec:commons-codec:1.19.0 - https://commons.apache.org/proper/commons-codec/) | Apache-2.0 | — |
| Apache Commons IO (commons-io:commons-io:2.21.0 - https://commons.apache.org/proper/commons-io/) | Apache-2.0 | — |
| Apache Commons Logging (commons-logging:commons-logging:1.3.6 - https://commons.apache.org/proper/commons-logging/) | Apache-2.0 | — |
| io.grpc:grpc-api (io.grpc:grpc-api:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-context (io.grpc:grpc-context:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-core (io.grpc:grpc-core:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-netty-shaded (io.grpc:grpc-netty-shaded:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-protobuf (io.grpc:grpc-protobuf:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-protobuf-lite (io.grpc:grpc-protobuf-lite:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-stub (io.grpc:grpc-stub:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-util (io.grpc:grpc-util:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| JJWT :: API (io.jsonwebtoken:jjwt-api:0.12.6 - https://github.com/jwtk/jjwt/jjwt-api) | Apache License, Version 2.0 | — |
| JJWT :: Impl (io.jsonwebtoken:jjwt-impl:0.12.6 - https://github.com/jwtk/jjwt/jjwt-impl) | Apache License, Version 2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JJWT :: Extensions :: Jackson (io.jsonwebtoken:jjwt-jackson:0.12.6 - https://github.com/jwtk/jjwt/jjwt-jackson) | Apache License, Version 2.0 | — |
| Lettuce (io.lettuce:lettuce-core:6.8.2.RELEASE - https://github.com/redis/lettuce) | MIT | — |
| micrometer-commons (io.micrometer:micrometer-commons:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-core (io.micrometer:micrometer-core:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-jakarta9 (io.micrometer:micrometer-jakarta9:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-observation (io.micrometer:micrometer-observation:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-observation-test (io.micrometer:micrometer-observation-test:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-registry-prometheus (io.micrometer:micrometer-registry-prometheus:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| Netty/Buffer (io.netty:netty-buffer:4.2.12.Final - https://netty.io/netty-buffer/) | Apache License, Version 2.0 | — |
| Netty/Codec (io.netty:netty-codec:4.2.12.Final - https://netty.io/netty-codec/) | Apache License, Version 2.0 | — |
| Netty/Codec/Base (io.netty:netty-codec-base:4.2.12.Final - https://netty.io/netty-codec-base/) | Apache License, Version 2.0 | — |
| Netty/Codec/Compression (io.netty:netty-codec-compression:4.2.12.Final - https://netty.io/netty-codec-compression/) | Apache License, Version 2.0 | — |
| Netty/Codec/DNS (io.netty:netty-codec-dns:4.2.12.Final - https://netty.io/netty-codec-dns/) | Apache License, Version 2.0 | — |
| Netty/Codec/HTTP (io.netty:netty-codec-http:4.2.12.Final - https://netty.io/netty-codec-http/) | Apache License, Version 2.0 | — |
| Netty/Codec/HTTP2 (io.netty:netty-codec-http2:4.2.12.Final - https://netty.io/netty-codec-http2/) | Apache License, Version 2.0 | — |
| Netty/Codec/Marshalling (io.netty:netty-codec-marshalling:4.2.12.Final - https://netty.io/netty-codec-marshalling/) | Apache License, Version 2.0 | — |
| Netty/Codec/Protobuf (io.netty:netty-codec-protobuf:4.2.12.Final - https://netty.io/netty-codec-protobuf/) | Apache License, Version 2.0 | — |
| Netty/Common (io.netty:netty-common:4.2.12.Final - https://netty.io/netty-common/) | Apache License, Version 2.0 | — |
| Netty/Handler (io.netty:netty-handler:4.2.12.Final - https://netty.io/netty-handler/) | Apache License, Version 2.0 | — |
| Netty/Resolver (io.netty:netty-resolver:4.2.12.Final - https://netty.io/netty-resolver/) | Apache License, Version 2.0 | — |
| Netty/Resolver/DNS (io.netty:netty-resolver-dns:4.2.12.Final - https://netty.io/netty-resolver-dns/) | Apache License, Version 2.0 | — |
| Netty/Transport (io.netty:netty-transport:4.2.12.Final - https://netty.io/netty-transport/) | Apache License, Version 2.0 | — |
| Netty/Transport/Classes/Epoll (io.netty:netty-transport-classes-epoll:4.2.12.Final - https://netty.io/netty-transport-classes-epoll/) | Apache License, Version 2.0 | — |
| Netty/Transport/Native/Unix/Common (io.netty:netty-transport-native-unix-common:4.2.12.Final - https://netty.io/netty-transport-native-unix-common/) | Apache License, Version 2.0 | — |
| perfmark:perfmark-api (io.perfmark:perfmark-api:0.27.0 - https://github.com/perfmark/perfmark) | Apache 2.0 | — |
| Non-Blocking Reactive Foundation for the JVM (io.projectreactor:reactor-core:3.8.5 - https://github.com/reactor/reactor-core) | Apache License, Version 2.0 | — |
| Prometheus Metrics Config (io.prometheus:prometheus-metrics-config:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-config) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Core (io.prometheus:prometheus-metrics-core:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-core) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Exposition Formats (io.prometheus:prometheus-metrics-exposition-formats:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-exposition-formats) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Exposition Text Formats (io.prometheus:prometheus-metrics-exposition-textformats:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-exposition-textformats) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Model (io.prometheus:prometheus-metrics-model:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-model) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Tracer Common (io.prometheus:prometheus-metrics-tracer-common:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-tracer/prometheus-metrics-tracer-common) | The Apache Software License, Version 2.0 | — |
| swagger-annotations-jakarta (io.swagger.core.v3:swagger-annotations-jakarta:2.2.47 - https://github.com/swagger-api/swagger-core/modules/swagger-annotations-jakarta) | Apache License 2.0 | — |
| swagger-core-jakarta (io.swagger.core.v3:swagger-core-jakarta:2.2.47 - https://github.com/swagger-api/swagger-core/modules/swagger-core-jakarta) | Apache License 2.0 | — |
| swagger-models-jakarta (io.swagger.core.v3:swagger-models-jakarta:2.2.47 - https://github.com/swagger-api/swagger-core/modules/swagger-models-jakarta) | Apache License 2.0 | — |
| Jakarta Activation API (jakarta.activation:jakarta.activation-api:2.1.4 - https://github.com/jakartaee/jaf-api) | EDL 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| (GPL2 w/ CPE) Jakarta Annotations API (jakarta.annotation:jakarta.annotation-api:3.0.0 - https://projects.eclipse.org/projects/ee4j.ca) | EPL 2.0 | Có Classpath Exception; dùng theo giấy phép ở cột trước. |
| Jakarta Dependency Injection (jakarta.inject:jakarta.inject-api:2.0.1 - https://github.com/eclipse-ee4j/injection-api) | The Apache Software License, Version 2.0 | — |
| (EPL 2.0) (GPL2 w/ CPE) Jakarta Mail API (jakarta.mail:jakarta.mail-api:2.1.5 - https://projects.eclipse.org/projects/ee4j/jakarta.mail-api) | EDL 1.0 | Có Classpath Exception; dùng theo giấy phép ở cột trước. |
| (Eclipse Public License v. 2.0) Jakarta Persistence API (jakarta.persistence:jakarta.persistence-api:3.2.0 - https://github.com/jakartaee/persistence) | Eclipse Distribution License v. 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| (GPL2 w/ CPE) jakarta.transaction API (jakarta.transaction:jakarta.transaction-api:2.0.1 - https://projects.eclipse.org/projects/ee4j.jta) | EPL 2.0 | Có Classpath Exception; dùng theo giấy phép ở cột trước. |
| Jakarta Validation API (jakarta.validation:jakarta.validation-api:3.1.1 - https://beanvalidation.org) | Apache License 2.0 | — |
| Jakarta XML Binding API (jakarta.xml.bind:jakarta.xml.bind-api:4.0.4 - https://github.com/jakartaee/jaxb-api/jakarta.xml.bind-api) | Eclipse Distribution License - v 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| Joda-Time (joda-time:joda-time:2.14.0 - https://www.joda.org/joda-time/) | Apache License, Version 2.0 | — |
| Byte Buddy (without dependencies) (net.bytebuddy:byte-buddy:1.17.8 - https://bytebuddy.net/byte-buddy) | Apache License, Version 2.0 | — |
| Byte Buddy agent (net.bytebuddy:byte-buddy-agent:1.17.8 - https://bytebuddy.net/byte-buddy-agent) | Apache License, Version 2.0 | — |
| (LGPL-2.1-or-later) Java Native Access (net.java.dev.jna:jna:5.18.1 - https://github.com/java-native-access/jna) | Apache-2.0 | Dùng theo Apache-2.0. |
| (MIT License) Logstash Logback Encoder (net.logstash.logback:logstash-logback-encoder:6.6 - https://github.com/logstash/logstash-logback-encoder) | Apache License, Version 2.0 | — |
| ASM based accessors helper used by json-smart (net.minidev:accessors-smart:2.6.0 - https://urielch.github.io/) | The Apache Software License, Version 2.0 | — |
| JSON Small and Fast Parser (net.minidev:json-smart:2.6.0 - https://urielch.github.io/) | The Apache Software License, Version 2.0 | — |
| ANTLR 4 Runtime (org.antlr:antlr4-runtime:4.13.2 - https://www.antlr.org/antlr4-runtime/) | BSD-3-Clause | — |
| Apache Commons Collections (org.apache.commons:commons-collections4:4.5.0 - https://commons.apache.org/proper/commons-collections/) | Apache-2.0 | — |
| Apache Commons Compress (org.apache.commons:commons-compress:1.28.0 - https://commons.apache.org/proper/commons-compress/) | Apache-2.0 | — |
| Apache Commons Lang (org.apache.commons:commons-lang3:3.19.0 - https://commons.apache.org/proper/commons-lang/) | Apache-2.0 | — |
| Apache Commons Math (org.apache.commons:commons-math3:3.6.1 - http://commons.apache.org/proper/commons-math/) | Apache License, Version 2.0 | — |
| Apache HttpClient (org.apache.httpcomponents:httpclient:4.5.13 - http://hc.apache.org/httpcomponents-client) | Apache License, Version 2.0 | — |
| Apache HttpCore (org.apache.httpcomponents:httpcore:4.4.16 - http://hc.apache.org/httpcomponents-core-ga) | Apache License, Version 2.0 | — |
| Apache Log4j API (org.apache.logging.log4j:log4j-api:2.25.4 - https://logging.apache.org/log4j/2.x/) | Apache-2.0 | — |
| Log4j API to SLF4J Adapter (org.apache.logging.log4j:log4j-to-slf4j:2.25.4 - https://logging.apache.org/log4j/2.x/) | Apache-2.0 | — |
| Apache POI - Common (org.apache.poi:poi:5.5.1 - https://poi.apache.org/) | Apache License, Version 2.0 | — |
| Apache POI - API based on OPC and OOXML schemas (org.apache.poi:poi-ooxml:5.5.1 - https://poi.apache.org/) | Apache License, Version 2.0 | — |
| Apache POI (org.apache.poi:poi-ooxml-lite:5.5.1 - https://poi.apache.org/) | Apache License, Version 2.0 | — |
| annotations-api (org.apache.tomcat:annotations-api:6.0.53 - http://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| tomcat-embed-core (org.apache.tomcat.embed:tomcat-embed-core:11.0.21 - https://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| tomcat-embed-el (org.apache.tomcat.embed:tomcat-embed-el:11.0.21 - https://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| tomcat-embed-websocket (org.apache.tomcat.embed:tomcat-embed-websocket:11.0.21 - https://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| XmlBeans (org.apache.xmlbeans:xmlbeans:5.3.0 - https://xmlbeans.apache.org/) | The Apache Software License, Version 2.0 | — |
| org.apiguardian:apiguardian-api (org.apiguardian:apiguardian-api:1.1.2 - https://github.com/apiguardian-team/apiguardian) | The Apache License, Version 2.0 | — |
| AspectJ Weaver (org.aspectj:aspectjweaver:1.9.25.1 - https://www.eclipse.org/aspectj/) | Eclipse Public License - v 2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| AssertJ Core (org.assertj:assertj-core:3.27.7 - https://assertj.github.io/doc/#assertj-core) | Apache-2.0 | — |
| Awaitility (org.awaitility:awaitility:4.3.0 - http://awaitility.org) | Apache 2.0 | — |
| Checker Qual (org.checkerframework:checker-qual:3.52.0 - https://checkerframework.org/) | The MIT License | — |
| Animal Sniffer Annotations (org.codehaus.mojo:animal-sniffer-annotations:1.24 - https://www.mojohaus.org/animal-sniffer/animal-sniffer-annotations) | MIT license | — |
| Angus Activation Registries (org.eclipse.angus:angus-activation:2.0.3 - https://github.com/eclipse-ee4j/angus-activation/angus-activation) | EDL 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| (EPL 2.0) (GPL2 w/ CPE) Angus Mail Provider (org.eclipse.angus:angus-mail:2.0.5 - http://eclipse-ee4j.github.io/angus-mail/angus-mail) | EDL 1.0 | Có Classpath Exception; dùng theo giấy phép ở cột trước. |
| org.eclipse.paho.client.mqttv3 (org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5 - http://www.eclipse.org/paho/org.eclipse.paho.client.mqttv3) | Eclipse Public License - Version 2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| Flowable - Batch Service (org.flowable:flowable-batch-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-batch-service) | Apache v2 | — |
| Flowable - Batch Service API (org.flowable:flowable-batch-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-batch-service-api) | Apache v2 | — |
| Flowable - BPMN Converter (org.flowable:flowable-bpmn-converter:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-bpmn-converter) | Apache v2 | — |
| Flowable - BPMN Model (org.flowable:flowable-bpmn-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-bpmn-model) | Apache v2 | — |
| Flowable - CMMN API (org.flowable:flowable-cmmn-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-cmmn-api) | Apache v2 | — |
| Flowable - CMMN Model (org.flowable:flowable-cmmn-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-cmmn-model) | Apache v2 | — |
| Flowable - Content API (org.flowable:flowable-content-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-content-api) | Apache v2 | — |
| Flowable - DMN API (org.flowable:flowable-dmn-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-dmn-api) | Apache v2 | — |
| Flowable - DMN Model (org.flowable:flowable-dmn-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-dmn-model) | Apache v2 | — |
| Flowable - Engine (org.flowable:flowable-engine:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-engine) | Apache v2 | — |
| Flowable - Engine Common (org.flowable:flowable-engine-common:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-engine-common) | Apache v2 | — |
| Flowable - Engine Common API (org.flowable:flowable-engine-common-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-engine-common-api) | Apache v2 | — |
| Flowable - Entity Link Service (org.flowable:flowable-entitylink-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-entitylink-service) | Apache v2 | — |
| Flowable - Entity Link Service API (org.flowable:flowable-entitylink-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-entitylink-service-api) | Apache v2 | — |
| Flowable - Event Registry (org.flowable:flowable-event-registry:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry) | Apache v2 | — |
| Flowable - Event Registry API (org.flowable:flowable-event-registry-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-api) | Apache v2 | — |
| Flowable - Event Registry Engine Configurator (org.flowable:flowable-event-registry-configurator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-configurator) | Apache v2 | — |
| Flowable - Event Registry JSON Converter (org.flowable:flowable-event-registry-json-converter:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-json-converter) | Apache v2 | — |
| Flowable - Event Registry Model (org.flowable:flowable-event-registry-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-model) | Apache v2 | — |
| Flowable - Event Registry - Spring (org.flowable:flowable-event-registry-spring:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-spring) | Apache v2 | — |
| Flowable - Event Registry Spring Configurator (org.flowable:flowable-event-registry-spring-configurator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-spring-configurator) | Apache v2 | — |
| Flowable - Event Subscription Service (org.flowable:flowable-eventsubscription-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-eventsubscription-service) | Apache v2 | — |
| Flowable - Event Subscription Service API (org.flowable:flowable-eventsubscription-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-eventsubscription-service-api) | Apache v2 | — |
| Flowable - Form API (org.flowable:flowable-form-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-form-api) | Apache v2 | — |
| Flowable - Form Model (org.flowable:flowable-form-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-form-model) | Apache v2 | — |
| Flowable - HTTP Common (org.flowable:flowable-http-common:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-http-common) | Apache v2 | — |
| Flowable - Identity Link Service (org.flowable:flowable-identitylink-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-identitylink-service) | Apache v2 | — |
| Flowable - Identity Link Service API (org.flowable:flowable-identitylink-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-identitylink-service-api) | Apache v2 | — |
| Flowable - IDM API (org.flowable:flowable-idm-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-idm-api) | Apache v2 | — |
| Flowable - IDM Engine (org.flowable:flowable-idm-engine:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-idm-engine) | Apache v2 | — |
| Flowable - IDM Engine Configurator (org.flowable:flowable-idm-engine-configurator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-idm-engine-configurator) | Apache v2 | — |
| Flowable - Image Generator (org.flowable:flowable-image-generator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-image-generator) | Apache v2 | — |
| Flowable - Job Service (org.flowable:flowable-job-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-job-service) | Apache v2 | — |
| Flowable - Job Service API (org.flowable:flowable-job-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-job-service-api) | Apache v2 | — |
| Flowable Job Spring Service (org.flowable:flowable-job-spring-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-job-spring-service) | Apache v2 | — |
| Flowable - Mail (org.flowable:flowable-mail:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-mail) | Apache v2 | — |
| Flowable - Process Validation (org.flowable:flowable-process-validation:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-process-validation) | Apache v2 | — |
| Flowable - Spring (org.flowable:flowable-spring:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring) | Apache v2 | — |
| Flowable - Spring Boot Autoconfigure (org.flowable:flowable-spring-boot-autoconfigure:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-boot/flowable-spring-boot-starters/flowable-spring-boot-autoconfigure) | Apache v2 | — |
| Flowable - Spring Boot Process Starter (org.flowable:flowable-spring-boot-starter-process:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-boot/flowable-spring-boot-starters/flowable-spring-boot-starter-process) | Apache v2 | — |
| Flowable - Spring Common (org.flowable:flowable-spring-common:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-common) | Apache v2 | — |
| Flowable - Spring Security (org.flowable:flowable-spring-security:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-security) | Apache v2 | — |
| Flowable - Task Service (org.flowable:flowable-task-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-task-service) | Apache v2 | — |
| Flowable - Task Service API (org.flowable:flowable-task-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-task-service-api) | Apache v2 | — |
| Flowable - Variable Service (org.flowable:flowable-variable-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-variable-service) | Apache v2 | — |
| Flowable - Variable Service API (org.flowable:flowable-variable-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-variable-service-api) | Apache v2 | — |
| flyway-core (org.flywaydb:flyway-core:11.14.1 - https://flywaydb.org/flyway-core) | Apache License, Version 2.0 | — |
| flyway-database-postgresql (org.flywaydb:flyway-database-postgresql:11.14.1 - https://flywaydb.org/flyway-database-postgresql) | Apache License, Version 2.0 | — |
| JAXB Core (org.glassfish.jaxb:jaxb-core:4.0.6 - https://eclipse-ee4j.github.io/jaxb-ri/) | Eclipse Distribution License - v 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JAXB Runtime (org.glassfish.jaxb:jaxb-runtime:4.0.6 - https://eclipse-ee4j.github.io/jaxb-ri/) | Eclipse Distribution License - v 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| TXW2 Runtime (org.glassfish.jaxb:txw2:4.0.6 - https://eclipse-ee4j.github.io/jaxb-ri/) | Eclipse Distribution License - v 1.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| Hamcrest (org.hamcrest:hamcrest:3.0 - http://hamcrest.org/JavaHamcrest/) | BSD-3-Clause | — |
| (Public Domain, per Creative Commons CC0) HdrHistogram (org.hdrhistogram:HdrHistogram:2.2.2 - http://hdrhistogram.github.io/HdrHistogram/) | BSD-2-Clause | — |
| Hibernate Models (org.hibernate.models:hibernate-models:1.0.1 - https://github.com/hibernate/hibernate-models) | Apache License Version 2.0 | — |
| Hibernate ORM - hibernate-core (org.hibernate.orm:hibernate-core:7.2.12.Final - https://hibernate.org/orm) | Apache License, Version 2.0 | — |
| Hibernate Validator Engine (org.hibernate.validator:hibernate-validator:9.0.1.Final - https://hibernate.org/validator) | Apache License 2.0 | — |
| JBoss Logging 3 (org.jboss.logging:jboss-logging:3.6.3.Final - https://www.jboss.org) | Apache License 2.0 | — |
| JetBrains Java Annotations (org.jetbrains:annotations:17.0.0 - https://github.com/JetBrains/java-annotations) | The Apache Software License, Version 2.0 | — |
| JSpecify annotations (org.jspecify:jspecify:1.0.0 - http://jspecify.org/) | The Apache License, Version 2.0 | — |
| JUnit Jupiter (Aggregator) (org.junit.jupiter:junit-jupiter:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JUnit Jupiter API (org.junit.jupiter:junit-jupiter-api:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JUnit Jupiter Engine (org.junit.jupiter:junit-jupiter-engine:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JUnit Jupiter Params (org.junit.jupiter:junit-jupiter-params:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JUnit Platform Commons (org.junit.platform:junit-platform-commons:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JUnit Platform Engine API (org.junit.platform:junit-platform-engine:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| LatencyUtils (org.latencyutils:LatencyUtils:2.0.3 - http://latencyutils.github.io/LatencyUtils/) | Public Domain, per Creative Commons CC0 | — |
| mockito-core (org.mockito:mockito-core:5.20.0 - https://github.com/mockito/mockito) | MIT | — |
| mockito-junit-jupiter (org.mockito:mockito-junit-jupiter:5.20.0 - https://github.com/mockito/mockito) | MIT | — |
| mybatis (org.mybatis:mybatis:3.5.19 - https://www.mybatis.org/mybatis-3/) | The Apache Software License, Version 2.0 | — |
| Objenesis (org.objenesis:objenesis:3.3 - http://objenesis.org/objenesis) | Apache License, Version 2.0 | — |
| org.opentest4j:opentest4j (org.opentest4j:opentest4j:1.3.0 - https://github.com/ota4j-team/opentest4j) | The Apache License, Version 2.0 | — |
| asm (org.ow2.asm:asm:9.7.1 - http://asm.ow2.io/) | BSD-3-Clause | — |
| PostgreSQL JDBC Driver (org.postgresql:postgresql:42.7.10 - https://jdbc.postgresql.org) | BSD-2-Clause | — |
| Project Lombok (org.projectlombok:lombok:1.18.46 - https://projectlombok.org) | The MIT License | — |
| reactive-streams (org.reactivestreams:reactive-streams:1.0.4 - http://www.reactive-streams.org/) | MIT-0 | — |
| Duct Tape (org.rnorth.duct-tape:duct-tape:1.0.8 - https://github.com/rnorth/duct-tape) | MIT | — |
| JSONassert (org.skyscreamer:jsonassert:1.5.3 - https://github.com/skyscreamer/JSONassert) | The Apache Software License, Version 2.0 | — |
| JCL 1.2 implemented over SLF4J (org.slf4j:jcl-over-slf4j:2.0.17 - http://www.slf4j.org) | Apache-2.0 | Giữ kèm thông báo giấy phép khi phát hành. |
| JUL to SLF4J bridge (org.slf4j:jul-to-slf4j:2.0.17 - http://www.slf4j.org) | MIT | — |
| SLF4J API Module (org.slf4j:slf4j-api:2.0.17 - http://www.slf4j.org) | MIT | — |
| springdoc-openapi-starter-common (org.springdoc:springdoc-openapi-starter-common:3.0.3 - https://springdoc.org/springdoc-openapi-starter-common/) | The Apache License, Version 2.0 | — |
| springdoc-openapi-starter-webmvc-api (org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3 - https://springdoc.org/springdoc-openapi-starter-webmvc-api/) | The Apache License, Version 2.0 | — |
| springdoc-openapi-starter-webmvc-ui (org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3 - https://springdoc.org/springdoc-openapi-starter-webmvc-ui/) | The Apache License, Version 2.0 | — |
| Spring AOP (org.springframework:spring-aop:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Aspects (org.springframework:spring-aspects:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Beans (org.springframework:spring-beans:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Context (org.springframework:spring-context:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Context Support (org.springframework:spring-context-support:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Core (org.springframework:spring-core:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Expression Language (SpEL) (org.springframework:spring-expression:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring JDBC (org.springframework:spring-jdbc:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Messaging (org.springframework:spring-messaging:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Object/Relational Mapping (org.springframework:spring-orm:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Object/XML Marshalling (org.springframework:spring-oxm:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring TestContext Framework (org.springframework:spring-test:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Transaction (org.springframework:spring-tx:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Web (org.springframework:spring-web:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Web MVC (org.springframework:spring-webmvc:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring AMQP Core (org.springframework.amqp:spring-amqp:4.0.3 - https://github.com/spring-projects/spring-amqp) | Apache License, Version 2.0 | — |
| Spring RabbitMQ Support (org.springframework.amqp:spring-rabbit:4.0.3 - https://github.com/spring-projects/spring-amqp) | Apache License, Version 2.0 | — |
| spring-boot (org.springframework.boot:spring-boot:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-actuator (org.springframework.boot:spring-boot-actuator:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-actuator-autoconfigure (org.springframework.boot:spring-boot-actuator-autoconfigure:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-amqp (org.springframework.boot:spring-boot-amqp:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-autoconfigure (org.springframework.boot:spring-boot-autoconfigure:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-commons (org.springframework.boot:spring-boot-data-commons:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-jpa (org.springframework.boot:spring-boot-data-jpa:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-jpa-test (org.springframework.boot:spring-boot-data-jpa-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-redis (org.springframework.boot:spring-boot-data-redis:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-redis-test (org.springframework.boot:spring-boot-data-redis-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-devtools (org.springframework.boot:spring-boot-devtools:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-flyway (org.springframework.boot:spring-boot-flyway:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-health (org.springframework.boot:spring-boot-health:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-hibernate (org.springframework.boot:spring-boot-hibernate:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-http-converter (org.springframework.boot:spring-boot-http-converter:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jackson (org.springframework.boot:spring-boot-jackson:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jdbc (org.springframework.boot:spring-boot-jdbc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jdbc-test (org.springframework.boot:spring-boot-jdbc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jpa (org.springframework.boot:spring-boot-jpa:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jpa-test (org.springframework.boot:spring-boot-jpa-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-mail (org.springframework.boot:spring-boot-mail:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-micrometer-metrics (org.springframework.boot:spring-boot-micrometer-metrics:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-micrometer-metrics-test (org.springframework.boot:spring-boot-micrometer-metrics-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-micrometer-observation (org.springframework.boot:spring-boot-micrometer-observation:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-netty (org.springframework.boot:spring-boot-netty:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-persistence (org.springframework.boot:spring-boot-persistence:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-resttestclient (org.springframework.boot:spring-boot-resttestclient:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-security (org.springframework.boot:spring-boot-security:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-security-oauth2-resource-server (org.springframework.boot:spring-boot-security-oauth2-resource-server:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-security-test (org.springframework.boot:spring-boot-security-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-servlet (org.springframework.boot:spring-boot-servlet:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-sql (org.springframework.boot:spring-boot-sql:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter (org.springframework.boot:spring-boot-starter:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-actuator (org.springframework.boot:spring-boot-starter-actuator:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-actuator-test (org.springframework.boot:spring-boot-starter-actuator-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-amqp (org.springframework.boot:spring-boot-starter-amqp:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-jpa (org.springframework.boot:spring-boot-starter-data-jpa:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-jpa-test (org.springframework.boot:spring-boot-starter-data-jpa-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-redis (org.springframework.boot:spring-boot-starter-data-redis:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-redis-test (org.springframework.boot:spring-boot-starter-data-redis-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-flyway (org.springframework.boot:spring-boot-starter-flyway:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jackson (org.springframework.boot:spring-boot-starter-jackson:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jackson-test (org.springframework.boot:spring-boot-starter-jackson-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jdbc (org.springframework.boot:spring-boot-starter-jdbc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jdbc-test (org.springframework.boot:spring-boot-starter-jdbc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-logging (org.springframework.boot:spring-boot-starter-logging:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-mail (org.springframework.boot:spring-boot-starter-mail:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-micrometer-metrics (org.springframework.boot:spring-boot-starter-micrometer-metrics:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-micrometer-metrics-test (org.springframework.boot:spring-boot-starter-micrometer-metrics-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security (org.springframework.boot:spring-boot-starter-security:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security-oauth2-resource-server (org.springframework.boot:spring-boot-starter-security-oauth2-resource-server:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security-oauth2-resource-server-test (org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security-test (org.springframework.boot:spring-boot-starter-security-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-test (org.springframework.boot:spring-boot-starter-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-tomcat (org.springframework.boot:spring-boot-starter-tomcat:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-tomcat-runtime (org.springframework.boot:spring-boot-starter-tomcat-runtime:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-validation (org.springframework.boot:spring-boot-starter-validation:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-validation-test (org.springframework.boot:spring-boot-starter-validation-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-webmvc (org.springframework.boot:spring-boot-starter-webmvc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-webmvc-test (org.springframework.boot:spring-boot-starter-webmvc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-test (org.springframework.boot:spring-boot-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-test-autoconfigure (org.springframework.boot:spring-boot-test-autoconfigure:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-tomcat (org.springframework.boot:spring-boot-tomcat:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-transaction (org.springframework.boot:spring-boot-transaction:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-validation (org.springframework.boot:spring-boot-validation:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-web-server (org.springframework.boot:spring-boot-web-server:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-webmvc (org.springframework.boot:spring-boot-webmvc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-webmvc-test (org.springframework.boot:spring-boot-webmvc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| Spring Data Core (org.springframework.data:spring-data-commons:4.0.5 - https://spring.io/projects/spring-data) | Apache License, Version 2.0 | — |
| Spring Data JPA (org.springframework.data:spring-data-jpa:4.0.5 - https://projects.spring.io/spring-data-jpa) | Apache License, Version 2.0 | — |
| Spring Data KeyValue (org.springframework.data:spring-data-keyvalue:4.0.5 - https://www.spring.io/spring-data/spring-data-keyvalue) | Apache License, Version 2.0 | — |
| Spring Data Redis (org.springframework.data:spring-data-redis:4.0.5 - https://spring.io/projects/spring-data-redis) | Apache License, Version 2.0 | — |
| spring-security-config (org.springframework.security:spring-security-config:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-core (org.springframework.security:spring-security-core:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-crypto (org.springframework.security:spring-security-crypto:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-oauth2-core (org.springframework.security:spring-security-oauth2-core:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-oauth2-jose (org.springframework.security:spring-security-oauth2-jose:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-oauth2-resource-server (org.springframework.security:spring-security-oauth2-resource-server:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-test (org.springframework.security:spring-security-test:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-web (org.springframework.security:spring-security-web:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| Testcontainers Core (org.testcontainers:testcontainers:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: Database-Commons (org.testcontainers:testcontainers-database-commons:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: JDBC (org.testcontainers:testcontainers-jdbc:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: JUnit Jupiter Extension (org.testcontainers:testcontainers-junit-jupiter:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: JDBC :: PostgreSQL (org.testcontainers:testcontainers-postgresql:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Swagger UI (org.webjars:swagger-ui:5.32.2 - https://www.webjars.org) | Apache-2.0 | — |
| webjars-locator-lite (org.webjars:webjars-locator-lite:1.1.3 - https://webjars.org) | MIT | — |
| org.xmlunit:xmlunit-core (org.xmlunit:xmlunit-core:2.10.4 - https://www.xmlunit.org/) | The Apache Software License, Version 2.0 | — |
| SnakeYAML (org.yaml:snakeyaml:2.5 - https://bitbucket.org/snakeyaml/snakeyaml) | Apache License, Version 2.0 | — |
| redis-authx-core (redis.clients.authentication:redis-authx-core:0.1.1-beta2 - https://github.com/redis/redis-authx-core) | MIT | — |
| AWS Java SDK :: Annotations (software.amazon.awssdk:annotations:2.31.70 - https://aws.amazon.com/sdkforjava/core/annotations) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Clients :: Apache (software.amazon.awssdk:apache-client:2.31.70 - https://aws.amazon.com/sdkforjava/http-clients/apache-client) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Arns (software.amazon.awssdk:arns:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Auth (software.amazon.awssdk:auth:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: AWS Core (software.amazon.awssdk:aws-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: AWS Query Protocol (software.amazon.awssdk:aws-query-protocol:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: AWS Xml Protocol (software.amazon.awssdk:aws-xml-protocol:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Checksums (software.amazon.awssdk:checksums:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Checksums SPI (software.amazon.awssdk:checksums-spi:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: AWS CRT Core (software.amazon.awssdk:crt-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Endpoints SPI (software.amazon.awssdk:endpoints-spi:2.31.70 - https://aws.amazon.com/sdkforjava/core/endpoints-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth (software.amazon.awssdk:http-auth:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth AWS (software.amazon.awssdk:http-auth-aws:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth Event Stream (software.amazon.awssdk:http-auth-aws-eventstream:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth SPI (software.amazon.awssdk:http-auth-spi:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Client Interface (software.amazon.awssdk:http-client-spi:2.31.70 - https://aws.amazon.com/sdkforjava/http-client-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Identity SPI (software.amazon.awssdk:identity-spi:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: Json Utils (software.amazon.awssdk:json-utils:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Metrics SPI (software.amazon.awssdk:metrics-spi:2.31.70 - https://aws.amazon.com/sdkforjava/core/metrics-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Clients :: Netty Non-Blocking I/O (software.amazon.awssdk:netty-nio-client:2.31.70 - https://aws.amazon.com/sdkforjava/http-clients/netty-nio-client) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Profiles (software.amazon.awssdk:profiles:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: Protocol Core (software.amazon.awssdk:protocol-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Regions (software.amazon.awssdk:regions:2.31.70 - https://aws.amazon.com/sdkforjava/core/regions) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Retries (software.amazon.awssdk:retries:2.31.70 - https://aws.amazon.com/sdkforjava/core/retries) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Retries API (software.amazon.awssdk:retries-spi:2.31.70 - https://aws.amazon.com/sdkforjava/core/retries-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Services :: Amazon S3 (software.amazon.awssdk:s3:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: SDK Core (software.amazon.awssdk:sdk-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Third Party :: Jackson-core (software.amazon.awssdk:third-party-jackson-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Utilities (software.amazon.awssdk:utils:2.31.70 - https://aws.amazon.com/sdkforjava/utils) | Apache License, Version 2.0 | — |
| AWS Event Stream (software.amazon.eventstream:eventstream:1.0.1 - https://github.com/awslabs/aws-eventstream-java) | Apache License, Version 2.0 | — |
| Jackson-core (tools.jackson.core:jackson-core:3.1.2 - https://github.com/FasterXML/jackson-core) | The Apache Software License, Version 2.0 | — |
| jackson-databind (tools.jackson.core:jackson-databind:3.1.2 - https://github.com/FasterXML/jackson) | The Apache Software License, Version 2.0 | — |

### Python — `rag-chatbot-fastapi/pyproject.toml`

| Gói | Giấy phép | Ghi chú |
| --- | --- | --- |
| aio-pika==9.6.2 | Permissive (validated at install) | — |
| boto3==1.42.94 | Permissive (validated at install) | — |
| fastapi==0.136.0 | Permissive (validated at install) | — |
| duckdb>=1.1,<2 | Permissive (validated at install) | — |
| grpcio>=1.71,<2 | Permissive (validated at install) | — |
| fastembed==0.8.0 | Permissive (validated at install) | — |
| httpx==0.28.1 | Permissive (validated at install) | — |
| kuzu==0.11.3 | Permissive (validated at install) | — |
| prometheus-client>=0.21,<1 | Permissive (validated at install) | — |
| protobuf>=5.29,<6 | Permissive (validated at install) | — |
| pypdf==6.4.0 | Permissive (validated at install) | — |
| pypdfium2>=4.30,<5 | Permissive (validated at install) | — |
| pillow>=11,<12 | Permissive (validated at install) | — |
| python-docx>=1.1,<2 | Permissive (validated at install) | — |
| openpyxl>=3.1,<4 | Permissive (validated at install) | — |
| polars>=1.30,<2 | Permissive (validated at install) | — |
| pyarrow>=19,<24 | Permissive (validated at install) | — |
| pydantic-settings==2.14.0 | Permissive (validated at install) | — |
| python-multipart==0.0.26 | Permissive (validated at install) | — |
| qdrant-client==1.17.1 | Permissive (validated at install) | — |
| redis==7.4.0 | Permissive (validated at install) | — |
| uvicorn[standard]==0.45.0 | Permissive (validated at install) | — |

### Frontend — `frontend/package.json`

| Gói lúc chạy trực tiếp | Giấy phép | Ghi chú |
| --- | --- | --- |
| @base-ui/react@1.4.1 | MIT | — |
| @dagrejs/dagre@3.1.3 | MIT | — |
| @hookform/resolvers@5.2.2 | MIT | — |
| @tanstack/react-query@5.100.9 | MIT | — |
| @tiptap/extension-link@3.29.0 | MIT | — |
| @tiptap/react@3.29.0 | MIT | — |
| @tiptap/starter-kit@3.29.0 | MIT | — |
| @xyflow/react@12.12.1 | MIT | — |
| class-variance-authority@0.7.1 | Apache-2.0 | — |
| clsx@2.1.1 | MIT | — |
| docx-preview@0.4.1 | Apache-2.0 | — |
| lucide-react@1.14.0 | ISC | — |
| next@16.2.12 | MIT | — |
| next-intl@4.13.2 | MIT | — |
| pdfjs-dist@6.4.299 | Apache-2.0 | — |
| react@19.2.4 | MIT | — |
| react-dom@19.2.4 | MIT | — |
| react-hook-form@7.75.0 | MIT | — |
| react-hot-toast@2.6.0 | MIT | — |
| read-excel-file@9.3.12 | MIT | — |
| shadcn@4.6.0 | MIT | — |
| tailwind-merge@3.5.0 | MIT | — |
| tw-animate-css@1.4.0 | MIT | — |
| zod@4.4.2 | MIT | — |
| zustand@5.0.12 | MIT | — |

<a id="english"></a>

# Third-Party Licenses

## Container services (Docker)

| Component | Image | License | OSI/open | Notes |
| --- | --- | --- | --- | --- |
| Apache APISIX | `apache/apisix:3.19.0-ubuntu` | Apache-2.0 | Yes | API gateway. |
| SeaweedFS | `chrislusf/seaweedfs:3.85` | Apache-2.0 | Yes | Object storage and S3 gateway. |
| ClamAV | `clamav/clamav:1.4` | GPL-2.0 | Yes | Standalone network malware-scanning service. |
| Text Embeddings Inference | `ghcr.io/huggingface/text-embeddings-inference:cpu-1.9` | Apache-2.0 | Yes | Optional reranker profile, disabled by default. |
| Grafana | `grafana/grafana:11.3.1` | AGPL-3.0 | Yes | Network-isolated, unmodified service; no code is linked into OpenVie. |
| PostgreSQL | `postgres:16-alpine` | PostgreSQL License | Yes | Application and Keycloak database. |
| Alertmanager | `prom/alertmanager:v0.27.0` | Apache-2.0 | Yes | Optional monitoring profile, disabled by default. |
| Prometheus | `prom/prometheus:v2.55.1` | Apache-2.0 | Yes | Optional monitoring profile, disabled by default. |
| Qdrant | `qdrant/qdrant:v1.19.2` | Apache-2.0 | Yes | Vector database. |
| Keycloak | `quay.io/keycloak/keycloak:26.3.3` | Apache-2.0 | Yes | Identity provider. |
| RabbitMQ | `rabbitmq:3-management-alpine` | MPL-2.0 | Yes | Message broker, used unmodified over the network. |
| Redis | `redis:7.2-alpine` | BSD-3-Clause | Yes | Intentionally pinned to 7.2.x; Redis ≥7.4 is RSALv2/SSPL. |
| Autoheal | `willfarrell/autoheal:1.2.0` | MIT | Yes | Container auto-healing. |

## Document-processing components

| Component | Repository source | License | Notes |
| --- | --- | --- | --- |
| Tesseract OCR + vie/eng traineddata | `rag-chatbot-fastapi/Dockerfile` | Apache-2.0 | Installed in the image for scanned-PDF and image OCR; `INGESTION_OCR_ENABLED=false` by default. |
| faster-whisper | `rag-chatbot-fastapi/pyproject.toml` (`asr` optional extra) | MIT | Installed only when an operator selects the ASR extra; ASR is disabled by default. |

## Libraries we use

### Java — `api/pom.xml`

| Component | License | Notes |
| --- | --- | --- |
| (GNU Lesser General Public License) Logback Classic Module (ch.qos.logback:logback-classic:1.5.32 - http://logback.qos.ch/logback-classic) | Eclipse Public License - v 2.0 | Used under EPL-2.0. |
| (GNU Lesser General Public License) Logback Core Module (ch.qos.logback:logback-core:1.5.32 - http://logback.qos.ch/logback-core) | Eclipse Public License - v 2.0 | Used under EPL-2.0. |
| ClassMate (com.fasterxml:classmate:1.7.3 - https://github.com/FasterXML/java-classmate) | Apache License, Version 2.0 | — |
| Jackson-annotations (com.fasterxml.jackson.core:jackson-annotations:2.21 - https://github.com/FasterXML/jackson) | The Apache Software License, Version 2.0 | — |
| Jackson-core (com.fasterxml.jackson.core:jackson-core:2.21.2 - https://github.com/FasterXML/jackson-core) | The Apache Software License, Version 2.0 | — |
| jackson-databind (com.fasterxml.jackson.core:jackson-databind:2.21.2 - https://github.com/FasterXML/jackson) | The Apache Software License, Version 2.0 | — |
| Jackson-dataformat-YAML (com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.2 - https://github.com/FasterXML/jackson-dataformats-text) | The Apache Software License, Version 2.0 | — |
| Jackson datatype: JSR310 (com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.2 - https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310) | The Apache Software License, Version 2.0 | — |
| Java UUID Generator (com.fasterxml.uuid:java-uuid-generator:5.1.0 - https://github.com/cowtowncoder/java-uuid-generator) | The Apache Software License, Version 2.0 | — |
| docker-java-api (com.github.docker-java:docker-java-api:3.7.1 - https://github.com/docker-java/docker-java) | The Apache Software License, Version 2.0 | — |
| docker-java-transport (com.github.docker-java:docker-java-transport:3.7.1 - https://github.com/docker-java/docker-java) | The Apache Software License, Version 2.0 | — |
| docker-java-transport-zerodep (com.github.docker-java:docker-java-transport-zerodep:3.7.1 - https://github.com/docker-java/docker-java) | The Apache Software License, Version 2.0 | — |
| curvesapi (com.github.virtuald:curvesapi:1.08 - https://github.com/virtuald/curvesapi) | BSD License | — |
| Google Android Annotations Library (com.google.android:annotations:4.1.1.4 - http://source.android.com/) | Apache 2.0 | — |
| proto-google-common-protos (com.google.api.grpc:proto-google-common-protos:2.51.0 - https://github.com/googleapis/sdk-platform-java) | Apache-2.0 | — |
| FindBugs-jsr305 (com.google.code.findbugs:jsr305:3.0.2 - http://findbugs.sourceforge.net/) | The Apache Software License, Version 2.0 | — |
| Gson (com.google.code.gson:gson:2.11.0 - https://github.com/google/gson) | Apache-2.0 | — |
| error-prone annotations (com.google.errorprone:error_prone_annotations:2.30.0 - https://errorprone.info/error_prone_annotations) | Apache 2.0 | — |
| Guava InternalFutureFailureAccess and InternalFutures (com.google.guava:failureaccess:1.0.2 - https://github.com/google/guava/failureaccess) | The Apache Software License, Version 2.0 | — |
| Guava: Google Core Libraries for Java (com.google.guava:guava:33.3.1-android - https://github.com/google/guava) | Apache License, Version 2.0 | — |
| Guava ListenableFuture only (com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava - https://github.com/google/guava/listenablefuture) | The Apache Software License, Version 2.0 | — |
| J2ObjC Annotations (com.google.j2objc:j2objc-annotations:3.0.0 - https://github.com/google/j2objc/) | Apache License, Version 2.0 | — |
| Protocol Buffers [Core] (com.google.protobuf:protobuf-java:4.30.2 - https://developers.google.com/protocol-buffers/protobuf-java/) | BSD-3-Clause | — |
| (MPL 2.0) H2 Database Engine (com.h2database:h2:2.4.240 - https://h2database.com) | EPL 1.0 | Test-only; EPL-1.0/MPL-2.0. |
| json-path (com.jayway.jsonpath:json-path:2.10.0 - https://github.com/jayway/JsonPath) | The Apache Software License, Version 2.0 | — |
| Nimbus JOSE+JWT (com.nimbusds:nimbus-jose-jwt:10.4 - https://bitbucket.org/connect2id/nimbus-jose-jwt) | The Apache Software License, Version 2.0 | — |
| (GPL v2) (MPL 2.0) RabbitMQ Java Client (com.rabbitmq:amqp-client:5.27.1 - https://www.rabbitmq.com) | AL 2.0 | Used under Apache-2.0/MPL-2.0. |
| istack common utility code runtime (com.sun.istack:istack-commons-runtime:4.1.2 - https://projects.eclipse.org/projects/ee4j/istack-commons/istack-commons-runtime) | Eclipse Distribution License - v 1.0 | Keep the applicable license notice when distributing. |
| (The Apache Software License, Version 2.0) ArchUnit (com.tngtech.archunit:archunit:1.4.1 - https://github.com/TNG/ArchUnit) | BSD | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5-api:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5-engine:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| ArchUnit (com.tngtech.archunit:archunit-junit5-engine-api:1.4.1 - https://github.com/TNG/ArchUnit) | The Apache Software License, Version 2.0 | — |
| JSON library from Android SDK (com.vaadin.external.google:android-json:0.0.20131108.vaadin1 - http://developer.android.com/sdk) | Apache License 2.0 | — |
| HikariCP (com.zaxxer:HikariCP:7.0.2 - https://github.com/brettwooldridge/HikariCP) | The Apache Software License, Version 2.0 | — |
| SparseBitSet (com.zaxxer:SparseBitSet:1.3 - https://github.com/brettwooldridge/SparseBitSet) | The Apache Software License, Version 2.0 | — |
| Apache Commons Codec (commons-codec:commons-codec:1.19.0 - https://commons.apache.org/proper/commons-codec/) | Apache-2.0 | — |
| Apache Commons IO (commons-io:commons-io:2.21.0 - https://commons.apache.org/proper/commons-io/) | Apache-2.0 | — |
| Apache Commons Logging (commons-logging:commons-logging:1.3.6 - https://commons.apache.org/proper/commons-logging/) | Apache-2.0 | — |
| io.grpc:grpc-api (io.grpc:grpc-api:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-context (io.grpc:grpc-context:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-core (io.grpc:grpc-core:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-netty-shaded (io.grpc:grpc-netty-shaded:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-protobuf (io.grpc:grpc-protobuf:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-protobuf-lite (io.grpc:grpc-protobuf-lite:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-stub (io.grpc:grpc-stub:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| io.grpc:grpc-util (io.grpc:grpc-util:1.71.0 - https://github.com/grpc/grpc-java) | Apache 2.0 | — |
| JJWT :: API (io.jsonwebtoken:jjwt-api:0.12.6 - https://github.com/jwtk/jjwt/jjwt-api) | Apache License, Version 2.0 | — |
| JJWT :: Impl (io.jsonwebtoken:jjwt-impl:0.12.6 - https://github.com/jwtk/jjwt/jjwt-impl) | Apache License, Version 2.0 | Keep the applicable license notice when distributing. |
| JJWT :: Extensions :: Jackson (io.jsonwebtoken:jjwt-jackson:0.12.6 - https://github.com/jwtk/jjwt/jjwt-jackson) | Apache License, Version 2.0 | — |
| Lettuce (io.lettuce:lettuce-core:6.8.2.RELEASE - https://github.com/redis/lettuce) | MIT | — |
| micrometer-commons (io.micrometer:micrometer-commons:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-core (io.micrometer:micrometer-core:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-jakarta9 (io.micrometer:micrometer-jakarta9:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-observation (io.micrometer:micrometer-observation:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-observation-test (io.micrometer:micrometer-observation-test:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| micrometer-registry-prometheus (io.micrometer:micrometer-registry-prometheus:1.16.5 - https://github.com/micrometer-metrics/micrometer) | The Apache Software License, Version 2.0 | — |
| Netty/Buffer (io.netty:netty-buffer:4.2.12.Final - https://netty.io/netty-buffer/) | Apache License, Version 2.0 | — |
| Netty/Codec (io.netty:netty-codec:4.2.12.Final - https://netty.io/netty-codec/) | Apache License, Version 2.0 | — |
| Netty/Codec/Base (io.netty:netty-codec-base:4.2.12.Final - https://netty.io/netty-codec-base/) | Apache License, Version 2.0 | — |
| Netty/Codec/Compression (io.netty:netty-codec-compression:4.2.12.Final - https://netty.io/netty-codec-compression/) | Apache License, Version 2.0 | — |
| Netty/Codec/DNS (io.netty:netty-codec-dns:4.2.12.Final - https://netty.io/netty-codec-dns/) | Apache License, Version 2.0 | — |
| Netty/Codec/HTTP (io.netty:netty-codec-http:4.2.12.Final - https://netty.io/netty-codec-http/) | Apache License, Version 2.0 | — |
| Netty/Codec/HTTP2 (io.netty:netty-codec-http2:4.2.12.Final - https://netty.io/netty-codec-http2/) | Apache License, Version 2.0 | — |
| Netty/Codec/Marshalling (io.netty:netty-codec-marshalling:4.2.12.Final - https://netty.io/netty-codec-marshalling/) | Apache License, Version 2.0 | — |
| Netty/Codec/Protobuf (io.netty:netty-codec-protobuf:4.2.12.Final - https://netty.io/netty-codec-protobuf/) | Apache License, Version 2.0 | — |
| Netty/Common (io.netty:netty-common:4.2.12.Final - https://netty.io/netty-common/) | Apache License, Version 2.0 | — |
| Netty/Handler (io.netty:netty-handler:4.2.12.Final - https://netty.io/netty-handler/) | Apache License, Version 2.0 | — |
| Netty/Resolver (io.netty:netty-resolver:4.2.12.Final - https://netty.io/netty-resolver/) | Apache License, Version 2.0 | — |
| Netty/Resolver/DNS (io.netty:netty-resolver-dns:4.2.12.Final - https://netty.io/netty-resolver-dns/) | Apache License, Version 2.0 | — |
| Netty/Transport (io.netty:netty-transport:4.2.12.Final - https://netty.io/netty-transport/) | Apache License, Version 2.0 | — |
| Netty/Transport/Classes/Epoll (io.netty:netty-transport-classes-epoll:4.2.12.Final - https://netty.io/netty-transport-classes-epoll/) | Apache License, Version 2.0 | — |
| Netty/Transport/Native/Unix/Common (io.netty:netty-transport-native-unix-common:4.2.12.Final - https://netty.io/netty-transport-native-unix-common/) | Apache License, Version 2.0 | — |
| perfmark:perfmark-api (io.perfmark:perfmark-api:0.27.0 - https://github.com/perfmark/perfmark) | Apache 2.0 | — |
| Non-Blocking Reactive Foundation for the JVM (io.projectreactor:reactor-core:3.8.5 - https://github.com/reactor/reactor-core) | Apache License, Version 2.0 | — |
| Prometheus Metrics Config (io.prometheus:prometheus-metrics-config:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-config) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Core (io.prometheus:prometheus-metrics-core:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-core) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Exposition Formats (io.prometheus:prometheus-metrics-exposition-formats:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-exposition-formats) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Exposition Text Formats (io.prometheus:prometheus-metrics-exposition-textformats:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-exposition-textformats) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Model (io.prometheus:prometheus-metrics-model:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-model) | The Apache Software License, Version 2.0 | — |
| Prometheus Metrics Tracer Common (io.prometheus:prometheus-metrics-tracer-common:1.4.3 - http://github.com/prometheus/client_java/client_java/prometheus-metrics-tracer/prometheus-metrics-tracer-common) | The Apache Software License, Version 2.0 | — |
| swagger-annotations-jakarta (io.swagger.core.v3:swagger-annotations-jakarta:2.2.47 - https://github.com/swagger-api/swagger-core/modules/swagger-annotations-jakarta) | Apache License 2.0 | — |
| swagger-core-jakarta (io.swagger.core.v3:swagger-core-jakarta:2.2.47 - https://github.com/swagger-api/swagger-core/modules/swagger-core-jakarta) | Apache License 2.0 | — |
| swagger-models-jakarta (io.swagger.core.v3:swagger-models-jakarta:2.2.47 - https://github.com/swagger-api/swagger-core/modules/swagger-models-jakarta) | Apache License 2.0 | — |
| Jakarta Activation API (jakarta.activation:jakarta.activation-api:2.1.4 - https://github.com/jakartaee/jaf-api) | EDL 1.0 | Keep the applicable license notice when distributing. |
| (GPL2 w/ CPE) Jakarta Annotations API (jakarta.annotation:jakarta.annotation-api:3.0.0 - https://projects.eclipse.org/projects/ee4j.ca) | EPL 2.0 | Includes the Classpath Exception; use follows the license shown. |
| Jakarta Dependency Injection (jakarta.inject:jakarta.inject-api:2.0.1 - https://github.com/eclipse-ee4j/injection-api) | The Apache Software License, Version 2.0 | — |
| (EPL 2.0) (GPL2 w/ CPE) Jakarta Mail API (jakarta.mail:jakarta.mail-api:2.1.5 - https://projects.eclipse.org/projects/ee4j/jakarta.mail-api) | EDL 1.0 | Includes the Classpath Exception; use follows the license shown. |
| (Eclipse Public License v. 2.0) Jakarta Persistence API (jakarta.persistence:jakarta.persistence-api:3.2.0 - https://github.com/jakartaee/persistence) | Eclipse Distribution License v. 1.0 | Keep the applicable license notice when distributing. |
| (GPL2 w/ CPE) jakarta.transaction API (jakarta.transaction:jakarta.transaction-api:2.0.1 - https://projects.eclipse.org/projects/ee4j.jta) | EPL 2.0 | Includes the Classpath Exception; use follows the license shown. |
| Jakarta Validation API (jakarta.validation:jakarta.validation-api:3.1.1 - https://beanvalidation.org) | Apache License 2.0 | — |
| Jakarta XML Binding API (jakarta.xml.bind:jakarta.xml.bind-api:4.0.4 - https://github.com/jakartaee/jaxb-api/jakarta.xml.bind-api) | Eclipse Distribution License - v 1.0 | Keep the applicable license notice when distributing. |
| Joda-Time (joda-time:joda-time:2.14.0 - https://www.joda.org/joda-time/) | Apache License, Version 2.0 | — |
| Byte Buddy (without dependencies) (net.bytebuddy:byte-buddy:1.17.8 - https://bytebuddy.net/byte-buddy) | Apache License, Version 2.0 | — |
| Byte Buddy agent (net.bytebuddy:byte-buddy-agent:1.17.8 - https://bytebuddy.net/byte-buddy-agent) | Apache License, Version 2.0 | — |
| (LGPL-2.1-or-later) Java Native Access (net.java.dev.jna:jna:5.18.1 - https://github.com/java-native-access/jna) | Apache-2.0 | Used under Apache-2.0. |
| (MIT License) Logstash Logback Encoder (net.logstash.logback:logstash-logback-encoder:6.6 - https://github.com/logstash/logstash-logback-encoder) | Apache License, Version 2.0 | — |
| ASM based accessors helper used by json-smart (net.minidev:accessors-smart:2.6.0 - https://urielch.github.io/) | The Apache Software License, Version 2.0 | — |
| JSON Small and Fast Parser (net.minidev:json-smart:2.6.0 - https://urielch.github.io/) | The Apache Software License, Version 2.0 | — |
| ANTLR 4 Runtime (org.antlr:antlr4-runtime:4.13.2 - https://www.antlr.org/antlr4-runtime/) | BSD-3-Clause | — |
| Apache Commons Collections (org.apache.commons:commons-collections4:4.5.0 - https://commons.apache.org/proper/commons-collections/) | Apache-2.0 | — |
| Apache Commons Compress (org.apache.commons:commons-compress:1.28.0 - https://commons.apache.org/proper/commons-compress/) | Apache-2.0 | — |
| Apache Commons Lang (org.apache.commons:commons-lang3:3.19.0 - https://commons.apache.org/proper/commons-lang/) | Apache-2.0 | — |
| Apache Commons Math (org.apache.commons:commons-math3:3.6.1 - http://commons.apache.org/proper/commons-math/) | Apache License, Version 2.0 | — |
| Apache HttpClient (org.apache.httpcomponents:httpclient:4.5.13 - http://hc.apache.org/httpcomponents-client) | Apache License, Version 2.0 | — |
| Apache HttpCore (org.apache.httpcomponents:httpcore:4.4.16 - http://hc.apache.org/httpcomponents-core-ga) | Apache License, Version 2.0 | — |
| Apache Log4j API (org.apache.logging.log4j:log4j-api:2.25.4 - https://logging.apache.org/log4j/2.x/) | Apache-2.0 | — |
| Log4j API to SLF4J Adapter (org.apache.logging.log4j:log4j-to-slf4j:2.25.4 - https://logging.apache.org/log4j/2.x/) | Apache-2.0 | — |
| Apache POI - Common (org.apache.poi:poi:5.5.1 - https://poi.apache.org/) | Apache License, Version 2.0 | — |
| Apache POI - API based on OPC and OOXML schemas (org.apache.poi:poi-ooxml:5.5.1 - https://poi.apache.org/) | Apache License, Version 2.0 | — |
| Apache POI (org.apache.poi:poi-ooxml-lite:5.5.1 - https://poi.apache.org/) | Apache License, Version 2.0 | — |
| annotations-api (org.apache.tomcat:annotations-api:6.0.53 - http://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| tomcat-embed-core (org.apache.tomcat.embed:tomcat-embed-core:11.0.21 - https://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| tomcat-embed-el (org.apache.tomcat.embed:tomcat-embed-el:11.0.21 - https://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| tomcat-embed-websocket (org.apache.tomcat.embed:tomcat-embed-websocket:11.0.21 - https://tomcat.apache.org/) | Apache License, Version 2.0 | — |
| XmlBeans (org.apache.xmlbeans:xmlbeans:5.3.0 - https://xmlbeans.apache.org/) | The Apache Software License, Version 2.0 | — |
| org.apiguardian:apiguardian-api (org.apiguardian:apiguardian-api:1.1.2 - https://github.com/apiguardian-team/apiguardian) | The Apache License, Version 2.0 | — |
| AspectJ Weaver (org.aspectj:aspectjweaver:1.9.25.1 - https://www.eclipse.org/aspectj/) | Eclipse Public License - v 2.0 | Keep the applicable license notice when distributing. |
| AssertJ Core (org.assertj:assertj-core:3.27.7 - https://assertj.github.io/doc/#assertj-core) | Apache-2.0 | — |
| Awaitility (org.awaitility:awaitility:4.3.0 - http://awaitility.org) | Apache 2.0 | — |
| Checker Qual (org.checkerframework:checker-qual:3.52.0 - https://checkerframework.org/) | The MIT License | — |
| Animal Sniffer Annotations (org.codehaus.mojo:animal-sniffer-annotations:1.24 - https://www.mojohaus.org/animal-sniffer/animal-sniffer-annotations) | MIT license | — |
| Angus Activation Registries (org.eclipse.angus:angus-activation:2.0.3 - https://github.com/eclipse-ee4j/angus-activation/angus-activation) | EDL 1.0 | Keep the applicable license notice when distributing. |
| (EPL 2.0) (GPL2 w/ CPE) Angus Mail Provider (org.eclipse.angus:angus-mail:2.0.5 - http://eclipse-ee4j.github.io/angus-mail/angus-mail) | EDL 1.0 | Includes the Classpath Exception; use follows the license shown. |
| org.eclipse.paho.client.mqttv3 (org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5 - http://www.eclipse.org/paho/org.eclipse.paho.client.mqttv3) | Eclipse Public License - Version 2.0 | Keep the applicable license notice when distributing. |
| Flowable - Batch Service (org.flowable:flowable-batch-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-batch-service) | Apache v2 | — |
| Flowable - Batch Service API (org.flowable:flowable-batch-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-batch-service-api) | Apache v2 | — |
| Flowable - BPMN Converter (org.flowable:flowable-bpmn-converter:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-bpmn-converter) | Apache v2 | — |
| Flowable - BPMN Model (org.flowable:flowable-bpmn-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-bpmn-model) | Apache v2 | — |
| Flowable - CMMN API (org.flowable:flowable-cmmn-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-cmmn-api) | Apache v2 | — |
| Flowable - CMMN Model (org.flowable:flowable-cmmn-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-cmmn-model) | Apache v2 | — |
| Flowable - Content API (org.flowable:flowable-content-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-content-api) | Apache v2 | — |
| Flowable - DMN API (org.flowable:flowable-dmn-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-dmn-api) | Apache v2 | — |
| Flowable - DMN Model (org.flowable:flowable-dmn-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-dmn-model) | Apache v2 | — |
| Flowable - Engine (org.flowable:flowable-engine:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-engine) | Apache v2 | — |
| Flowable - Engine Common (org.flowable:flowable-engine-common:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-engine-common) | Apache v2 | — |
| Flowable - Engine Common API (org.flowable:flowable-engine-common-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-engine-common-api) | Apache v2 | — |
| Flowable - Entity Link Service (org.flowable:flowable-entitylink-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-entitylink-service) | Apache v2 | — |
| Flowable - Entity Link Service API (org.flowable:flowable-entitylink-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-entitylink-service-api) | Apache v2 | — |
| Flowable - Event Registry (org.flowable:flowable-event-registry:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry) | Apache v2 | — |
| Flowable - Event Registry API (org.flowable:flowable-event-registry-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-api) | Apache v2 | — |
| Flowable - Event Registry Engine Configurator (org.flowable:flowable-event-registry-configurator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-configurator) | Apache v2 | — |
| Flowable - Event Registry JSON Converter (org.flowable:flowable-event-registry-json-converter:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-json-converter) | Apache v2 | — |
| Flowable - Event Registry Model (org.flowable:flowable-event-registry-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-model) | Apache v2 | — |
| Flowable - Event Registry - Spring (org.flowable:flowable-event-registry-spring:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-spring) | Apache v2 | — |
| Flowable - Event Registry Spring Configurator (org.flowable:flowable-event-registry-spring-configurator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-event-registry-spring-configurator) | Apache v2 | — |
| Flowable - Event Subscription Service (org.flowable:flowable-eventsubscription-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-eventsubscription-service) | Apache v2 | — |
| Flowable - Event Subscription Service API (org.flowable:flowable-eventsubscription-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-eventsubscription-service-api) | Apache v2 | — |
| Flowable - Form API (org.flowable:flowable-form-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-form-api) | Apache v2 | — |
| Flowable - Form Model (org.flowable:flowable-form-model:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-form-model) | Apache v2 | — |
| Flowable - HTTP Common (org.flowable:flowable-http-common:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-http-common) | Apache v2 | — |
| Flowable - Identity Link Service (org.flowable:flowable-identitylink-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-identitylink-service) | Apache v2 | — |
| Flowable - Identity Link Service API (org.flowable:flowable-identitylink-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-identitylink-service-api) | Apache v2 | — |
| Flowable - IDM API (org.flowable:flowable-idm-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-idm-api) | Apache v2 | — |
| Flowable - IDM Engine (org.flowable:flowable-idm-engine:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-idm-engine) | Apache v2 | — |
| Flowable - IDM Engine Configurator (org.flowable:flowable-idm-engine-configurator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-idm-engine-configurator) | Apache v2 | — |
| Flowable - Image Generator (org.flowable:flowable-image-generator:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-image-generator) | Apache v2 | — |
| Flowable - Job Service (org.flowable:flowable-job-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-job-service) | Apache v2 | — |
| Flowable - Job Service API (org.flowable:flowable-job-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-job-service-api) | Apache v2 | — |
| Flowable Job Spring Service (org.flowable:flowable-job-spring-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-job-spring-service) | Apache v2 | — |
| Flowable - Mail (org.flowable:flowable-mail:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-mail) | Apache v2 | — |
| Flowable - Process Validation (org.flowable:flowable-process-validation:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-process-validation) | Apache v2 | — |
| Flowable - Spring (org.flowable:flowable-spring:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring) | Apache v2 | — |
| Flowable - Spring Boot Autoconfigure (org.flowable:flowable-spring-boot-autoconfigure:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-boot/flowable-spring-boot-starters/flowable-spring-boot-autoconfigure) | Apache v2 | — |
| Flowable - Spring Boot Process Starter (org.flowable:flowable-spring-boot-starter-process:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-boot/flowable-spring-boot-starters/flowable-spring-boot-starter-process) | Apache v2 | — |
| Flowable - Spring Common (org.flowable:flowable-spring-common:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-common) | Apache v2 | — |
| Flowable - Spring Security (org.flowable:flowable-spring-security:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-spring-security) | Apache v2 | — |
| Flowable - Task Service (org.flowable:flowable-task-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-task-service) | Apache v2 | — |
| Flowable - Task Service API (org.flowable:flowable-task-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-task-service-api) | Apache v2 | — |
| Flowable - Variable Service (org.flowable:flowable-variable-service:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-variable-service) | Apache v2 | — |
| Flowable - Variable Service API (org.flowable:flowable-variable-service-api:8.0.0 - https://www.flowable.org/modules/flowable-bom/flowable-dependencies/flowable-parent/flowable-variable-service-api) | Apache v2 | — |
| flyway-core (org.flywaydb:flyway-core:11.14.1 - https://flywaydb.org/flyway-core) | Apache License, Version 2.0 | — |
| flyway-database-postgresql (org.flywaydb:flyway-database-postgresql:11.14.1 - https://flywaydb.org/flyway-database-postgresql) | Apache License, Version 2.0 | — |
| JAXB Core (org.glassfish.jaxb:jaxb-core:4.0.6 - https://eclipse-ee4j.github.io/jaxb-ri/) | Eclipse Distribution License - v 1.0 | Keep the applicable license notice when distributing. |
| JAXB Runtime (org.glassfish.jaxb:jaxb-runtime:4.0.6 - https://eclipse-ee4j.github.io/jaxb-ri/) | Eclipse Distribution License - v 1.0 | Keep the applicable license notice when distributing. |
| TXW2 Runtime (org.glassfish.jaxb:txw2:4.0.6 - https://eclipse-ee4j.github.io/jaxb-ri/) | Eclipse Distribution License - v 1.0 | Keep the applicable license notice when distributing. |
| Hamcrest (org.hamcrest:hamcrest:3.0 - http://hamcrest.org/JavaHamcrest/) | BSD-3-Clause | — |
| (Public Domain, per Creative Commons CC0) HdrHistogram (org.hdrhistogram:HdrHistogram:2.2.2 - http://hdrhistogram.github.io/HdrHistogram/) | BSD-2-Clause | — |
| Hibernate Models (org.hibernate.models:hibernate-models:1.0.1 - https://github.com/hibernate/hibernate-models) | Apache License Version 2.0 | — |
| Hibernate ORM - hibernate-core (org.hibernate.orm:hibernate-core:7.2.12.Final - https://hibernate.org/orm) | Apache License, Version 2.0 | — |
| Hibernate Validator Engine (org.hibernate.validator:hibernate-validator:9.0.1.Final - https://hibernate.org/validator) | Apache License 2.0 | — |
| JBoss Logging 3 (org.jboss.logging:jboss-logging:3.6.3.Final - https://www.jboss.org) | Apache License 2.0 | — |
| JetBrains Java Annotations (org.jetbrains:annotations:17.0.0 - https://github.com/JetBrains/java-annotations) | The Apache Software License, Version 2.0 | — |
| JSpecify annotations (org.jspecify:jspecify:1.0.0 - http://jspecify.org/) | The Apache License, Version 2.0 | — |
| JUnit Jupiter (Aggregator) (org.junit.jupiter:junit-jupiter:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Keep the applicable license notice when distributing. |
| JUnit Jupiter API (org.junit.jupiter:junit-jupiter-api:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Keep the applicable license notice when distributing. |
| JUnit Jupiter Engine (org.junit.jupiter:junit-jupiter-engine:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Keep the applicable license notice when distributing. |
| JUnit Jupiter Params (org.junit.jupiter:junit-jupiter-params:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Keep the applicable license notice when distributing. |
| JUnit Platform Commons (org.junit.platform:junit-platform-commons:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Keep the applicable license notice when distributing. |
| JUnit Platform Engine API (org.junit.platform:junit-platform-engine:6.0.3 - https://junit.org/) | Eclipse Public License v2.0 | Keep the applicable license notice when distributing. |
| LatencyUtils (org.latencyutils:LatencyUtils:2.0.3 - http://latencyutils.github.io/LatencyUtils/) | Public Domain, per Creative Commons CC0 | — |
| mockito-core (org.mockito:mockito-core:5.20.0 - https://github.com/mockito/mockito) | MIT | — |
| mockito-junit-jupiter (org.mockito:mockito-junit-jupiter:5.20.0 - https://github.com/mockito/mockito) | MIT | — |
| mybatis (org.mybatis:mybatis:3.5.19 - https://www.mybatis.org/mybatis-3/) | The Apache Software License, Version 2.0 | — |
| Objenesis (org.objenesis:objenesis:3.3 - http://objenesis.org/objenesis) | Apache License, Version 2.0 | — |
| org.opentest4j:opentest4j (org.opentest4j:opentest4j:1.3.0 - https://github.com/ota4j-team/opentest4j) | The Apache License, Version 2.0 | — |
| asm (org.ow2.asm:asm:9.7.1 - http://asm.ow2.io/) | BSD-3-Clause | — |
| PostgreSQL JDBC Driver (org.postgresql:postgresql:42.7.10 - https://jdbc.postgresql.org) | BSD-2-Clause | — |
| Project Lombok (org.projectlombok:lombok:1.18.46 - https://projectlombok.org) | The MIT License | — |
| reactive-streams (org.reactivestreams:reactive-streams:1.0.4 - http://www.reactive-streams.org/) | MIT-0 | — |
| Duct Tape (org.rnorth.duct-tape:duct-tape:1.0.8 - https://github.com/rnorth/duct-tape) | MIT | — |
| JSONassert (org.skyscreamer:jsonassert:1.5.3 - https://github.com/skyscreamer/JSONassert) | The Apache Software License, Version 2.0 | — |
| JCL 1.2 implemented over SLF4J (org.slf4j:jcl-over-slf4j:2.0.17 - http://www.slf4j.org) | Apache-2.0 | Keep the applicable license notice when distributing. |
| JUL to SLF4J bridge (org.slf4j:jul-to-slf4j:2.0.17 - http://www.slf4j.org) | MIT | — |
| SLF4J API Module (org.slf4j:slf4j-api:2.0.17 - http://www.slf4j.org) | MIT | — |
| springdoc-openapi-starter-common (org.springdoc:springdoc-openapi-starter-common:3.0.3 - https://springdoc.org/springdoc-openapi-starter-common/) | The Apache License, Version 2.0 | — |
| springdoc-openapi-starter-webmvc-api (org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3 - https://springdoc.org/springdoc-openapi-starter-webmvc-api/) | The Apache License, Version 2.0 | — |
| springdoc-openapi-starter-webmvc-ui (org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3 - https://springdoc.org/springdoc-openapi-starter-webmvc-ui/) | The Apache License, Version 2.0 | — |
| Spring AOP (org.springframework:spring-aop:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Aspects (org.springframework:spring-aspects:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Beans (org.springframework:spring-beans:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Context (org.springframework:spring-context:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Context Support (org.springframework:spring-context-support:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Core (org.springframework:spring-core:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Expression Language (SpEL) (org.springframework:spring-expression:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring JDBC (org.springframework:spring-jdbc:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Messaging (org.springframework:spring-messaging:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Object/Relational Mapping (org.springframework:spring-orm:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Object/XML Marshalling (org.springframework:spring-oxm:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring TestContext Framework (org.springframework:spring-test:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Transaction (org.springframework:spring-tx:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Web (org.springframework:spring-web:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring Web MVC (org.springframework:spring-webmvc:7.0.7 - https://github.com/spring-projects/spring-framework) | Apache License, Version 2.0 | — |
| Spring AMQP Core (org.springframework.amqp:spring-amqp:4.0.3 - https://github.com/spring-projects/spring-amqp) | Apache License, Version 2.0 | — |
| Spring RabbitMQ Support (org.springframework.amqp:spring-rabbit:4.0.3 - https://github.com/spring-projects/spring-amqp) | Apache License, Version 2.0 | — |
| spring-boot (org.springframework.boot:spring-boot:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-actuator (org.springframework.boot:spring-boot-actuator:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-actuator-autoconfigure (org.springframework.boot:spring-boot-actuator-autoconfigure:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-amqp (org.springframework.boot:spring-boot-amqp:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-autoconfigure (org.springframework.boot:spring-boot-autoconfigure:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-commons (org.springframework.boot:spring-boot-data-commons:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-jpa (org.springframework.boot:spring-boot-data-jpa:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-jpa-test (org.springframework.boot:spring-boot-data-jpa-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-redis (org.springframework.boot:spring-boot-data-redis:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-data-redis-test (org.springframework.boot:spring-boot-data-redis-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-devtools (org.springframework.boot:spring-boot-devtools:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-flyway (org.springframework.boot:spring-boot-flyway:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-health (org.springframework.boot:spring-boot-health:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-hibernate (org.springframework.boot:spring-boot-hibernate:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-http-converter (org.springframework.boot:spring-boot-http-converter:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jackson (org.springframework.boot:spring-boot-jackson:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jdbc (org.springframework.boot:spring-boot-jdbc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jdbc-test (org.springframework.boot:spring-boot-jdbc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jpa (org.springframework.boot:spring-boot-jpa:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-jpa-test (org.springframework.boot:spring-boot-jpa-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-mail (org.springframework.boot:spring-boot-mail:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-micrometer-metrics (org.springframework.boot:spring-boot-micrometer-metrics:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-micrometer-metrics-test (org.springframework.boot:spring-boot-micrometer-metrics-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-micrometer-observation (org.springframework.boot:spring-boot-micrometer-observation:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-netty (org.springframework.boot:spring-boot-netty:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-persistence (org.springframework.boot:spring-boot-persistence:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-resttestclient (org.springframework.boot:spring-boot-resttestclient:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-security (org.springframework.boot:spring-boot-security:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-security-oauth2-resource-server (org.springframework.boot:spring-boot-security-oauth2-resource-server:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-security-test (org.springframework.boot:spring-boot-security-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-servlet (org.springframework.boot:spring-boot-servlet:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-sql (org.springframework.boot:spring-boot-sql:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter (org.springframework.boot:spring-boot-starter:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-actuator (org.springframework.boot:spring-boot-starter-actuator:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-actuator-test (org.springframework.boot:spring-boot-starter-actuator-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-amqp (org.springframework.boot:spring-boot-starter-amqp:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-jpa (org.springframework.boot:spring-boot-starter-data-jpa:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-jpa-test (org.springframework.boot:spring-boot-starter-data-jpa-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-redis (org.springframework.boot:spring-boot-starter-data-redis:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-data-redis-test (org.springframework.boot:spring-boot-starter-data-redis-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-flyway (org.springframework.boot:spring-boot-starter-flyway:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jackson (org.springframework.boot:spring-boot-starter-jackson:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jackson-test (org.springframework.boot:spring-boot-starter-jackson-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jdbc (org.springframework.boot:spring-boot-starter-jdbc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-jdbc-test (org.springframework.boot:spring-boot-starter-jdbc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-logging (org.springframework.boot:spring-boot-starter-logging:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-mail (org.springframework.boot:spring-boot-starter-mail:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-micrometer-metrics (org.springframework.boot:spring-boot-starter-micrometer-metrics:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-micrometer-metrics-test (org.springframework.boot:spring-boot-starter-micrometer-metrics-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security (org.springframework.boot:spring-boot-starter-security:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security-oauth2-resource-server (org.springframework.boot:spring-boot-starter-security-oauth2-resource-server:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security-oauth2-resource-server-test (org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-security-test (org.springframework.boot:spring-boot-starter-security-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-test (org.springframework.boot:spring-boot-starter-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-tomcat (org.springframework.boot:spring-boot-starter-tomcat:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-tomcat-runtime (org.springframework.boot:spring-boot-starter-tomcat-runtime:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-validation (org.springframework.boot:spring-boot-starter-validation:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-validation-test (org.springframework.boot:spring-boot-starter-validation-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-webmvc (org.springframework.boot:spring-boot-starter-webmvc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-starter-webmvc-test (org.springframework.boot:spring-boot-starter-webmvc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-test (org.springframework.boot:spring-boot-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-test-autoconfigure (org.springframework.boot:spring-boot-test-autoconfigure:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-tomcat (org.springframework.boot:spring-boot-tomcat:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-transaction (org.springframework.boot:spring-boot-transaction:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-validation (org.springframework.boot:spring-boot-validation:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-web-server (org.springframework.boot:spring-boot-web-server:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-webmvc (org.springframework.boot:spring-boot-webmvc:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| spring-boot-webmvc-test (org.springframework.boot:spring-boot-webmvc-test:4.0.6 - https://spring.io/projects/spring-boot) | Apache License, Version 2.0 | — |
| Spring Data Core (org.springframework.data:spring-data-commons:4.0.5 - https://spring.io/projects/spring-data) | Apache License, Version 2.0 | — |
| Spring Data JPA (org.springframework.data:spring-data-jpa:4.0.5 - https://projects.spring.io/spring-data-jpa) | Apache License, Version 2.0 | — |
| Spring Data KeyValue (org.springframework.data:spring-data-keyvalue:4.0.5 - https://www.spring.io/spring-data/spring-data-keyvalue) | Apache License, Version 2.0 | — |
| Spring Data Redis (org.springframework.data:spring-data-redis:4.0.5 - https://spring.io/projects/spring-data-redis) | Apache License, Version 2.0 | — |
| spring-security-config (org.springframework.security:spring-security-config:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-core (org.springframework.security:spring-security-core:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-crypto (org.springframework.security:spring-security-crypto:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-oauth2-core (org.springframework.security:spring-security-oauth2-core:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-oauth2-jose (org.springframework.security:spring-security-oauth2-jose:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-oauth2-resource-server (org.springframework.security:spring-security-oauth2-resource-server:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-test (org.springframework.security:spring-security-test:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| spring-security-web (org.springframework.security:spring-security-web:7.0.5 - https://spring.io/projects/spring-security) | Apache License, Version 2.0 | — |
| Testcontainers Core (org.testcontainers:testcontainers:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: Database-Commons (org.testcontainers:testcontainers-database-commons:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: JDBC (org.testcontainers:testcontainers-jdbc:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: JUnit Jupiter Extension (org.testcontainers:testcontainers-junit-jupiter:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Testcontainers :: JDBC :: PostgreSQL (org.testcontainers:testcontainers-postgresql:2.0.5 - https://java.testcontainers.org) | MIT | — |
| Swagger UI (org.webjars:swagger-ui:5.32.2 - https://www.webjars.org) | Apache-2.0 | — |
| webjars-locator-lite (org.webjars:webjars-locator-lite:1.1.3 - https://webjars.org) | MIT | — |
| org.xmlunit:xmlunit-core (org.xmlunit:xmlunit-core:2.10.4 - https://www.xmlunit.org/) | The Apache Software License, Version 2.0 | — |
| SnakeYAML (org.yaml:snakeyaml:2.5 - https://bitbucket.org/snakeyaml/snakeyaml) | Apache License, Version 2.0 | — |
| redis-authx-core (redis.clients.authentication:redis-authx-core:0.1.1-beta2 - https://github.com/redis/redis-authx-core) | MIT | — |
| AWS Java SDK :: Annotations (software.amazon.awssdk:annotations:2.31.70 - https://aws.amazon.com/sdkforjava/core/annotations) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Clients :: Apache (software.amazon.awssdk:apache-client:2.31.70 - https://aws.amazon.com/sdkforjava/http-clients/apache-client) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Arns (software.amazon.awssdk:arns:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Auth (software.amazon.awssdk:auth:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: AWS Core (software.amazon.awssdk:aws-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: AWS Query Protocol (software.amazon.awssdk:aws-query-protocol:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: AWS Xml Protocol (software.amazon.awssdk:aws-xml-protocol:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Checksums (software.amazon.awssdk:checksums:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Checksums SPI (software.amazon.awssdk:checksums-spi:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: AWS CRT Core (software.amazon.awssdk:crt-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Endpoints SPI (software.amazon.awssdk:endpoints-spi:2.31.70 - https://aws.amazon.com/sdkforjava/core/endpoints-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth (software.amazon.awssdk:http-auth:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth AWS (software.amazon.awssdk:http-auth-aws:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth Event Stream (software.amazon.awssdk:http-auth-aws-eventstream:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Auth SPI (software.amazon.awssdk:http-auth-spi:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Client Interface (software.amazon.awssdk:http-client-spi:2.31.70 - https://aws.amazon.com/sdkforjava/http-client-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Identity SPI (software.amazon.awssdk:identity-spi:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: Json Utils (software.amazon.awssdk:json-utils:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Metrics SPI (software.amazon.awssdk:metrics-spi:2.31.70 - https://aws.amazon.com/sdkforjava/core/metrics-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: HTTP Clients :: Netty Non-Blocking I/O (software.amazon.awssdk:netty-nio-client:2.31.70 - https://aws.amazon.com/sdkforjava/http-clients/netty-nio-client) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Profiles (software.amazon.awssdk:profiles:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Core :: Protocols :: Protocol Core (software.amazon.awssdk:protocol-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Regions (software.amazon.awssdk:regions:2.31.70 - https://aws.amazon.com/sdkforjava/core/regions) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Retries (software.amazon.awssdk:retries:2.31.70 - https://aws.amazon.com/sdkforjava/core/retries) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Retries API (software.amazon.awssdk:retries-spi:2.31.70 - https://aws.amazon.com/sdkforjava/core/retries-spi) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Services :: Amazon S3 (software.amazon.awssdk:s3:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: SDK Core (software.amazon.awssdk:sdk-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Third Party :: Jackson-core (software.amazon.awssdk:third-party-jackson-core:2.31.70 - https://aws.amazon.com/sdkforjava) | Apache License, Version 2.0 | — |
| AWS Java SDK :: Utilities (software.amazon.awssdk:utils:2.31.70 - https://aws.amazon.com/sdkforjava/utils) | Apache License, Version 2.0 | — |
| AWS Event Stream (software.amazon.eventstream:eventstream:1.0.1 - https://github.com/awslabs/aws-eventstream-java) | Apache License, Version 2.0 | — |
| Jackson-core (tools.jackson.core:jackson-core:3.1.2 - https://github.com/FasterXML/jackson-core) | The Apache Software License, Version 2.0 | — |
| jackson-databind (tools.jackson.core:jackson-databind:3.1.2 - https://github.com/FasterXML/jackson) | The Apache Software License, Version 2.0 | — |

### Python — `rag-chatbot-fastapi/pyproject.toml`

| Package | License | Notes |
| --- | --- | --- |
| aio-pika==9.6.2 | Permissive (validated at install) | — |
| boto3==1.42.94 | Permissive (validated at install) | — |
| fastapi==0.136.0 | Permissive (validated at install) | — |
| duckdb>=1.1,<2 | Permissive (validated at install) | — |
| grpcio>=1.71,<2 | Permissive (validated at install) | — |
| fastembed==0.8.0 | Permissive (validated at install) | — |
| httpx==0.28.1 | Permissive (validated at install) | — |
| kuzu==0.11.3 | Permissive (validated at install) | — |
| prometheus-client>=0.21,<1 | Permissive (validated at install) | — |
| protobuf>=5.29,<6 | Permissive (validated at install) | — |
| pypdf==6.4.0 | Permissive (validated at install) | — |
| pypdfium2>=4.30,<5 | Permissive (validated at install) | — |
| pillow>=11,<12 | Permissive (validated at install) | — |
| python-docx>=1.1,<2 | Permissive (validated at install) | — |
| openpyxl>=3.1,<4 | Permissive (validated at install) | — |
| polars>=1.30,<2 | Permissive (validated at install) | — |
| pyarrow>=19,<24 | Permissive (validated at install) | — |
| pydantic-settings==2.14.0 | Permissive (validated at install) | — |
| python-multipart==0.0.26 | Permissive (validated at install) | — |
| qdrant-client==1.17.1 | Permissive (validated at install) | — |
| redis==7.4.0 | Permissive (validated at install) | — |
| uvicorn[standard]==0.45.0 | Permissive (validated at install) | — |

### Frontend — `frontend/package.json`

| Direct runtime package | License | Notes |
| --- | --- | --- |
| @base-ui/react@1.4.1 | MIT | — |
| @dagrejs/dagre@3.1.3 | MIT | — |
| @hookform/resolvers@5.2.2 | MIT | — |
| @tanstack/react-query@5.100.9 | MIT | — |
| @tiptap/extension-link@3.29.0 | MIT | — |
| @tiptap/react@3.29.0 | MIT | — |
| @tiptap/starter-kit@3.29.0 | MIT | — |
| @xyflow/react@12.12.1 | MIT | — |
| class-variance-authority@0.7.1 | Apache-2.0 | — |
| clsx@2.1.1 | MIT | — |
| docx-preview@0.4.1 | Apache-2.0 | — |
| lucide-react@1.14.0 | ISC | — |
| next@16.2.12 | MIT | — |
| next-intl@4.13.2 | MIT | — |
| pdfjs-dist@6.4.299 | Apache-2.0 | — |
| react@19.2.4 | MIT | — |
| react-dom@19.2.4 | MIT | — |
| react-hook-form@7.75.0 | MIT | — |
| react-hot-toast@2.6.0 | MIT | — |
| read-excel-file@9.3.12 | MIT | — |
| shadcn@4.6.0 | MIT | — |
| tailwind-merge@3.5.0 | MIT | — |
| tw-animate-css@1.4.0 | MIT | — |
| zod@4.4.2 | MIT | — |
| zustand@5.0.12 | MIT | — |
