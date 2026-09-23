package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;

/**
 * Java handlers that reach {@code HttpServer} as hidden classes, one {@code invokedynamic} site
 * each, so a test can tell which site produced which endpoint.
 */
public final class JavaHandlers {
    /** Implemented by a handler lambda that the lambda factory reports under this interface, not {@link HttpHandler}. */
    public interface NamedHandler extends HttpHandler {
    }

    /** A lambda with no captured values. */
    public static HttpHandler lambda() {
        return exchange -> respond(exchange);
    }

    /** A lambda that captures {@code body}, so its implementation takes it as a leading parameter. */
    public static HttpHandler capturingLambda(String body) {
        return exchange -> respond(exchange, body);
    }

    /** A reference to the static method {@link #handleStatically}. */
    public static HttpHandler staticReference() {
        return JavaHandlers::handleStatically;
    }

    /** A reference to {@link #handle}, bound to this instance. */
    public HttpHandler boundReference() {
        return this::handle;
    }

    /** A reference to {@code handle} on {@code target}, where the static type is the interface. */
    public static HttpHandler interfaceReference(HttpHandler target) {
        return target::handle;
    }

    /** A lambda at a site that a test runs before the hook is installed, and nowhere else. */
    public static HttpHandler lambdaSpunEarly() {
        return exchange -> respond(exchange);
    }

    /** A lambda whose functional interface is {@link NamedHandler}. */
    public static HttpHandler namedHandlerLambda() {
        NamedHandler handler = exchange -> respond(exchange);
        return handler;
    }

    /** Registers {@code handler} from this class, so the caller of {@code createContext} is not the class that built the handler. */
    public static void register(HttpServer server, String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    /** The target of {@link #staticReference}. */
    public static void handleStatically(HttpExchange exchange) throws IOException {
        respond(exchange);
    }

    /** The target of {@link #boundReference}. */
    public void handle(HttpExchange exchange) throws IOException {
        respond(exchange);
    }

    static void respond(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
