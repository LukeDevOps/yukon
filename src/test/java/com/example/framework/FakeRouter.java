package com.example.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A minimal stand-in for a web framework's router, used to prove {@code EndpointInstrumentation}
 * against a fixture rather than a real framework dependency.
 */
public class FakeRouter {
    public static final class Route {
        public final String verb;
        public final String path;
        public final Runnable handler;

        public Route(String verb, String path, Runnable handler) {
            this.verb = verb;
            this.path = path;
            this.handler = handler;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Route)) return false;
            Route route = (Route) other;
            return Objects.equals(verb, route.verb) && Objects.equals(path, route.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(verb, path);
        }
    }

    private final List<Route> routes = new ArrayList<>();

    /** Registers a route through the framework's normal registration hook. */
    public void addRoute(String verb, String path, Runnable handler) {
        routes.add(new Route(verb, path, handler));
    }

    /** Adds a route without going through {@link #addRoute}, so advice on {@link #addRoute} never sees it. */
    public void addQuietly(String verb, String path, Runnable handler) {
        routes.add(new Route(verb, path, handler));
    }

    /** Exposes every route this router holds, for a module's declare walk to read reflectively. */
    public List<Route> routes() {
        return routes;
    }

    /**
     * Marks the route table as finished building. The fixture's stand-in for a framework that
     * only reveals its routes as a whole object, late, such as Spring's {@code RouterFunction}:
     * advice on this method hands the router itself to {@code YukonEndpoints.declare} rather than
     * reporting one route at a time the way {@link #addRoute}'s advice does.
     */
    public void publishRoutes() {
    }

    public boolean dispatch(String verb, String path) {
        for (Route route : routes) {
            if (route.verb.equals(verb) && route.path.equals(path)) {
                invoke(route);
                return true;
            }
        }
        return false;
    }

    private void invoke(Route route) {
        route.handler.run();
    }
}
