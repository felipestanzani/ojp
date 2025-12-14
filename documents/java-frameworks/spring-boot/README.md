# Spring boot

To integrate OJP into your Spring Boot project follow the steps:

## 1 Add the maven dependency to your project.
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.0-beta</version>
</dependency>
```

## 2 Remove the local connection pool
Spring boot by default comes with HikariCP connection pool, as OJP replaces completely the connection pool, remove it in the pom.xml as follows.
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
    <exclusions>
        <!--When using OJP proxied connection pool the local pool needs to be removed -->
        <exclusion>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```
## 3 Change your connection URL
In your application.properties(or yaml) file, update your database connection URL, and add the OJP jdbc driver class as in the following example:

```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_h2:~/test
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
## Sets the datasource to not use hikari CP connection pool and open single connections instead
spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource
``` 

The example above is for `h2` but it is similar to any other database, you just need to add the `ojp[host:port]_` pattern immediately after `jdbc:`. `[host:port]` indicates the host and port you have your OJP proxy server running.

## Troubleshooting 
Spring Boot has used `Logback` as its default logging framework since its early versions. When using Spring Boot starters, such as spring-boot-starter-web, the spring-boot-starter-logging dependency is automatically included, which in turn transitively pulls in Logback. 

While Spring Boot's internal logging utilizes Commons Logging, it provides default configurations for various logging implementations, including Logback. Logback is given preference if found on the classpath. This design ensures that dependent libraries using other logging frameworks (like Java Util Logging or Log4J) are routed correctly through SLF4J, which Logback 
implements.

Therefore, Logback has been the default logging system in Spring Boot for a significant period, effectively since its inception and the introduction of its starter dependencies.

The follwing error occurs when there are conflicting logging implementations on the classpath, specifically multiple SLF4J (Simple Logging Facade for Java) providers. The warnings indicate that both the SLF4J Simple provider (`org.slf4j.simple.SimpleServiceProvider`) and Logback provider (`ch.qos.logback.classic.spi.LogbackServiceProvider`) are present in the classpath. While SLF4J automatically selects the Simple provider as the actual implementation, the application (likely Spring Boot) expects Logback to be the active logging context since Logback is detected on the classpath. This mismatch creates an illegal state where the LoggerFactory is not using Logback despite its presence, causing the application to fail during startup. The conflict typically arises when dependencies include different SLF4J implementations, such as when using the `ojp-jdbc-driver` (which includes SLF4J Simple) alongside Spring Boot applications that default to Logback for logging.

Error:
```shell
SLF4J(W): Class path contains multiple SLF4J providers.
SLF4J(W): Found provider [org.slf4j.simple.SimpleServiceProvider@75412c2f]
SLF4J(W): Found provider [ch.qos.logback.classic.spi.LogbackServiceProvider@282ba1e]
SLF4J(W): See https://www.slf4j.org/codes.html#multiple_bindings for an explanation.
SLF4J(I): Actual provider is of type [org.slf4j.simple.SimpleServiceProvider@75412c2f]
Exception in thread "main" java.lang.IllegalStateException: LoggerFactory is not a Logback LoggerContext but Logback is on the classpath. Either remove Logback or the competing implementation (class org.slf4j.simple.SimpleLoggerFactory loaded from file:/[current.user]/caches/modules-2/files-2.1/org.openjproxy/ojp-jdbc-driver/0.3.0-beta/995b086ffda9e29cc2a5694f3c5c0ddebf30adb9/ojp-jdbc-driver-0.3.0-beta.jar). If you are using WebLogic you will need to add 'org.slf4j' to prefer-application-packages in WEB-INF/weblogic.xml
	at org.springframework.util.Assert.state(Assert.java:101)
	at org.springframework.boot.logging.logback.LogbackLoggingSystem.getLoggerContext(LogbackLoggingSystem.java:410)
	at org.springframework.boot.logging.logback.LogbackLoggingSystem.beforeInitialize(LogbackLoggingSystem.java:129)
	at org.springframework.boot.context.logging.LoggingApplicationListener.onApplicationStartingEvent(LoggingApplicationListener.java:238)
	at org.springframework.boot.context.logging.LoggingApplicationListener.onApplicationEvent(LoggingApplicationListener.java:220)
	at org.springframework.context.event.SimpleApplicationEventMulticaster.doInvokeListener(SimpleApplicationEventMulticaster.java:185)
	at org.springframework.context.event.SimpleApplicationEventMulticaster.invokeListener(SimpleApplicationEventMulticaster.java:178)
	at org.springframework.context.event.SimpleApplicationEventMulticaster.multicastEvent(SimpleApplicationEventMulticaster.java:156)
	at org.springframework.context.event.SimpleApplicationEventMulticaster.multicastEvent(SimpleApplicationEventMulticaster.java:138)
	at org.springframework.boot.context.event.EventPublishingRunListener.multicastInitialEvent(EventPublishingRunListener.java:136)
	at org.springframework.boot.context.event.EventPublishingRunListener.starting(EventPublishingRunListener.java:75)
	at org.springframework.boot.SpringApplicationRunListeners.lambda$starting$0(SpringApplicationRunListeners.java:54)
	at java.base/java.lang.Iterable.forEach(Iterable.java:75)
	at org.springframework.boot.SpringApplicationRunListeners.doWithListeners(SpringApplicationRunListeners.java:118)
	at org.springframework.boot.SpringApplicationRunListeners.starting(SpringApplicationRunListeners.java:54)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:310)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1361)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1350)
	at com.example.ojp.OjpDemoApplicationKt.main(OjpDemoApplication.kt:13)

Process finished with exit code 1
```

Solution (JVM argument): 
```shell
JAVA_OPTS="-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider"
```
