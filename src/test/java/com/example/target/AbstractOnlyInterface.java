package com.example.target;

/** Has no concrete method at all, so there is nothing for either instrumentation tier to probe. */
public interface AbstractOnlyInterface {
    String onlyAbstract();
}
